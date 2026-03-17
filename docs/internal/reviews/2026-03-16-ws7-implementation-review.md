# WS7 Implementation Review — Compose Runtime Correctness (Fresh-Eyes)

**Date**: 2026-03-16
**Branch**: `feat/api-changes`
**Commits reviewed**: `8a46bf5` (F63), `81fe454` (F64), `6493ed9` (F70), `e2cc585` (review fixes)
**Plan ref**: `docs/plans/2026-03-14-ws7-compose-runtime-correctness.md`

---

## Summary

**4 findings**: 1 Should-fix, 2 Could-improve, 1 Nit

The implementation is fundamentally correct. F63 (staticCompositionLocalOf) and F70 (Java interop) are clean. F64 (DisposableEffect conversion) has correct effect lifecycle semantics with one key completeness gap that affects benchmark flag validation. No crashes, no data corruption, no WS6 regressions.

---

## Finding Index

| # | Severity | File | Lines | Short description |
|---|----------|------|-------|-------------------|
| 1 | Should-fix | IsometricScene.kt | 157 | Effect 2 keys missing `config.forceRebuild` and `config.useNativeCanvas` — `RuntimeFlagSnapshot` can go stale |
| 2 | Could-improve | IsometricRenderer.kt | 67 | Stale comment: says "SideEffect" but code now uses DisposableEffect |
| 3 | Could-improve | IsometricScene.kt | 113 | `config.forceRebuild` used as DisposableEffect key is a val on AdvancedSceneConfig — correct but relies on callers creating new config objects |
| 4 | Nit | RenderOptions.kt | 71 | Preset name `NoDepthSorting` differs from plan's `Performance` (plan was written against a different version) |

---

## Detailed Findings

### F1 — Effect 2 keys incomplete for `RuntimeFlagSnapshot` (Should-fix)

**File**: `isometric-compose/src/main/kotlin/.../IsometricScene.kt`, lines 157-178

**Problem**: Effect 2 is keyed on `(renderer, rootNode, renderContext, canvasWidth, canvasHeight)`. Inside the effect body, `currentOnFlagsReady` publishes a `RuntimeFlagSnapshot` that reads several values:

| Value in snapshot | Covered by key? | Why? |
|---|---|---|
| `config.enablePathCaching` | Yes | Changes trigger `renderer` recreation (line 79) |
| `config.enableSpatialIndex` | Yes | Same — `renderer` key |
| `config.renderOptions.enableBroadPhaseSort` | Yes | Changes trigger `renderContext` recreation (line 142) |
| `renderer.forceRebuild` | **No** | Set by Effect 1 from `config.forceRebuild`, but not in Effect 2's keys |
| `config.useNativeCanvas` | **No** | Not tied to any key |
| `canvasWidth`, `canvasHeight` | Yes | Directly in keys |

When `config.forceRebuild` or `config.useNativeCanvas` change without triggering renderer or renderContext recreation, `onFlagsReady` will publish a stale `RuntimeFlagSnapshot`.

**Impact**: Benchmarks reading `RuntimeFlagSnapshot` may see stale `forceRebuild` or `useNativeCanvas` values. This does not affect rendering correctness but defeats the purpose of the flag validation mechanism.

**Suggested fix**:
```kotlin
DisposableEffect(renderer, rootNode, renderContext, canvasWidth, canvasHeight, config.forceRebuild, config.useNativeCanvas) {
```

---

### F2 — Stale comment in IsometricRenderer (Could-improve)

**File**: `isometric-compose/src/main/kotlin/.../IsometricRenderer.kt`, line 67

**Problem**: The `benchmarkHooks` property KDoc says:
> Set via [IsometricScene]'s SideEffect from [LocalBenchmarkHooks].

After WS7-F64, this is set via `DisposableEffect`, not `SideEffect`.

**Suggested fix**: Change "SideEffect" to "DisposableEffect".

---

### F3 — `config.forceRebuild` as DisposableEffect key is structurally correct but subtle (Could-improve)

**File**: `isometric-compose/src/main/kotlin/.../IsometricScene.kt`, line 113

**Problem**: Effect 1 uses `config.forceRebuild` as a key. Since `AdvancedSceneConfig` is a class (not a data class for the advanced properties), and `forceRebuild` is a `val`, changing it requires creating a new `AdvancedSceneConfig` instance. This is the correct Compose pattern (config objects are immutable) but is worth noting for future maintainers: if `forceRebuild` were ever made mutable, the effect key would break.

**Impact**: None currently. Informational for future maintenance.

---

### F4 — Plan deviation: RenderOptions preset name (Nit)

**File**: `isometric-core/src/main/kotlin/.../RenderOptions.kt`, line 71

**Problem**: The WS7 plan specified a preset named `Performance`, but the implementation has `NoDepthSorting`. The `@JvmField` annotation was correctly applied regardless. This is a plan-vs-implementation naming mismatch from an earlier refactor.

**Impact**: None. Plan accuracy only.

---

## Section Analysis

### A. Plan-Spec Comparison

| Plan Step | Status | Notes |
|-----------|--------|-------|
| F63: compositionLocalOf to staticCompositionLocalOf | Fully implemented | All 5 locals converted. `compositionLocalOf` import removed. `LocalStrokeStyle` (WS2's replacement for `LocalStrokeWidth`/`LocalDrawStroke`) correctly converted. `LocalBenchmarkHooks` and `LocalIsometricEngine` were already static — no change needed. |
| F64: SideEffect to DisposableEffect | Implemented with justified deviation | 4 SideEffects became 2 DisposableEffects + 1 retained SideEffect. The retained SideEffect (`onPreparedSceneReady`) is a pure read-only state bridge with no cleanup needs — correct use of SideEffect per Compose docs. |
| F70: @JvmOverloads on constructors | Fully implemented | 8 classes: Point, Prism, Pyramid, Cylinder, Stairs, RenderOptions, IsometricEngine, IsoColor (both constructors). Vector correctly skipped (no default params). |
| F70: @JvmField on companion constants | Fully implemented | IsoColor (13 colors), RenderOptions (3 presets), IsometricEngine.DEFAULT_LIGHT_DIRECTION, Point.ORIGIN (already had it). `const val DEFAULT_BROAD_PHASE_CELL_SIZE` correctly not annotated (`const` implies field access). |

### B. Effect Lifecycle Correctness

**Effect 1** — `DisposableEffect(rootNode, renderer, currentBenchmarkHooks, config.forceRebuild)`:

- `rootNode.onDirty = { sceneVersion++ }`: The lambda captures the `sceneVersion` property delegate (backed by `MutableState<Long>`). Each invocation reads/writes through `State.value`. The closure does NOT capture a bare `Long` — it captures the delegate's getter/setter. Verified correct.
- `renderer.benchmarkHooks = currentBenchmarkHooks`: Plain val from `LocalBenchmarkHooks.current`, used as a key. When hooks change, effect re-executes. Correct.
- `renderer.forceRebuild = config.forceRebuild`: `config` is a new object each recomposition if the value changed (since `AdvancedSceneConfig` properties are `val`). Used as key. Correct.
- `renderer.onRenderError = { id, error -> currentOnRenderError?.invoke(id, error) }`: Reads through `rememberUpdatedState` delegate on each error callback invocation. Correctly handles callback identity changes without effect re-execution. Correct.
- `onDispose`: Clears `onDirty`, `benchmarkHooks`, `onRenderError`. Complete cleanup. Correct.

**Retained SideEffect** (onPreparedSceneReady, line 131):

- Reads `renderer.currentPreparedScene` (a `get()` property on the renderer) and publishes to callback.
- No resource allocation, no listener registration, no cleanup needed.
- `currentOnPreparedSceneReady` backed by `rememberUpdatedState` — always invokes latest callback.
- Correct use of SideEffect per Compose documentation: "publish Compose state to non-Compose code."

**Effect 2** — `DisposableEffect(renderer, rootNode, renderContext, canvasWidth, canvasHeight)`:

- Hit-test lambda captures `renderer, rootNode, renderContext, canvasWidth, canvasHeight` — all in key list. When any changes, effect re-executes and publishes a fresh hit-test function. Correct.
- `currentOnHitTestReady` and `currentOnFlagsReady` use `rememberUpdatedState` — avoids effect re-execution when caller passes new lambda instances. Correct.
- `onDispose` publishes `{ _, _ -> null }` — return type is `IsometricNode?`, matching `(Double, Double) -> IsometricNode?`. Type-correct.
- Key gap for `RuntimeFlagSnapshot`: see Finding 1.

**Effect ordering when renderer changes** (trace):
1. `renderer` key changes (due to `enablePathCaching`/`enableSpatialIndex`/`spatialIndexCellSize` change)
2. Effect 1 `onDispose` fires: clears `rootNode.onDirty`, old `renderer.benchmarkHooks`, old `renderer.onRenderError`
3. Effect 2 `onDispose` fires: publishes no-op hit-test to caller
4. Renderer close `DisposableEffect` (line 202) `onDispose` fires: calls old `renderer.close()`
5. New `renderer` created via `remember(...)`
6. Effect 1 body fires: wires `rootNode.onDirty`, new `renderer.benchmarkHooks`, `forceRebuild`, `onRenderError`
7. Effect 2 body fires: publishes fresh hit-test and flags
8. Renderer close `DisposableEffect` body fires (new key): registers cleanup for new renderer

No race conditions. Dispose runs synchronously before re-setup. Correct.

### C. Key Completeness for Effect 2 Fix (commit e2cc585)

The fix (adding `renderer` and `rootNode` to Effect 2 keys) was necessary because the hit-test lambda captured both. The `rememberUpdatedState` migration for `onHitTestReady` and `onFlagsReady` prevents unnecessary effect churn from inline lambdas. Both changes are correct.

Remaining gap: `config.forceRebuild` and `config.useNativeCanvas` in `RuntimeFlagSnapshot` (Finding 1).

### D. Java Interop Correctness

- **`@JvmOverloads` syntax**: All 8 annotations placed correctly before `constructor` keyword. Verified on Point, Prism, Pyramid, Cylinder, Stairs, RenderOptions, IsometricEngine, IsoColor (primary + secondary).
- **IsoColor dual constructors**: Primary generates `(Double,Double,Double,Double)` and `(Double,Double,Double)`. Secondary generates `(Int,Int,Int,Int)` and `(Int,Int,Int)`. Different JVM types — no overload conflict. Verified.
- **`@JvmField` on `val` only**: All 18 annotated properties are `val`. No `var`, no `const val`. Correct.
- **Companion constant mutability**: All `@JvmField` constants are immutable `val` properties pointing to immutable objects (IsoColor is a data class, RenderOptions has no mutable state, Vector is immutable). No computed properties. Correct.

### E. Cross-Cutting Concerns

- **Thread safety**: `sceneVersion++` writes to `MutableState<Long>` — Compose's snapshot system handles thread safety. `renderer.benchmarkHooks` and `renderer.forceRebuild` are written in DisposableEffect (main thread) and read in DrawScope (main thread). No cross-thread writes without synchronization. `IsometricEngine.projectionVersion` is `@Volatile` — untouched by WS7. No issues.
- **WS6 interaction**: `projectionVersion`, camera transforms (lines 367-379), hit-test inverse transform (lines 306-317), default drag-to-pan (lines 288-293) — all untouched by WS7. No regressions.
- **Missing files**: None. All files listed in the plan were modified. `Vector.kt` was correctly skipped (no default params).

### F. Comment Accuracy

- **IsometricRenderer.kt line 67**: Stale — says "SideEffect", should say "DisposableEffect". See Finding 2.
- **IsometricScene.kt line 104**: "bridged into the imperative renderer via DisposableEffect" — accurate.
- **IsometricScene.kt line 129**: "Uses rememberUpdatedState so the SideEffect always calls the latest callback" — accurate.
- **IsometricScene.kt line 153-154**: "Callback keys use rememberUpdatedState to avoid churn from inline lambdas" — accurate.
- **CompositionLocals.kt line 88**: References `staticCompositionLocalOf` — accurate.

---

## Verdict

**Approve with minor fixes.**

1. **F1 (Should-fix)**: Add `config.forceRebuild, config.useNativeCanvas` to Effect 2 keys. One-line change, prevents stale benchmark flag snapshots.
2. **F2 (Could-improve)**: Fix stale "SideEffect" comment in IsometricRenderer.kt.

The effect lifecycle is correct, variable captures are verified, Java interop is clean, and WS6 compatibility is maintained. No blocking issues.
