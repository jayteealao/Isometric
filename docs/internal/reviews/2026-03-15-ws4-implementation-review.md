# WS4 Implementation Review — Safety Net & Lifecycle

> **Date**: 2026-03-15
> **Scope**: All uncommitted changes on `feat/api-changes` implementing WS4
> **Plan ref**: `docs/plans/2026-03-14-ws4-safety-net-lifecycle.md`
> **Methodology**: Fresh-eyes review of `git diff` against plan spec, full file reads for boundary/behavior analysis, cross-referenced with source code
> **Passes**: 3 (initial diff review + deep boundary/behavior review + resolution verification & behavioral flow analysis)

---

## Summary

22 files changed (+479 −662). All 7 plan steps are represented in the diff. The core safety mechanisms (atomic IDs, try/catch, Closeable, experimental annotations, platform check, legacy deletion) are correctly implemented. Three review passes identified **10 findings**: all **10 are now RESOLVED** (R1–R4 in pass 2, R5–R10 in pass 4). The diff evolved between passes as fixes were applied — the third pass verified those fixes and performed behavioral flow analysis of the code's runtime interactions.

---

## Finding Index

| # | Severity | Step | Title |
|---|----------|------|-------|
| ~~R1~~ | ~~Must~~ | ~~4~~ | ~~`rebuildCache` clears engine before try boundary~~ — **RESOLVED** (see §R1) |
| ~~R2~~ | ~~Must~~ | ~~1~~ | ~~`ComposeRenderer.kt` is now dead code but was not deleted~~ — **RESOLVED** (see §R2) |
| ~~R3~~ | ~~Should~~ | ~~1~~ | ~~Orphaned legacy state holders~~ — **RESOLVED** (see §R3) |
| ~~R4~~ | ~~Should~~ | ~~1~~ | ~~Stale `MainActivity.kt` description~~ — **RESOLVED** (see §R4) |
| ~~R5~~ | ~~Should~~ | ~~—~~ | ~~`AnimatedSample` removed~~ — **RESOLVED** (see §R5) |
| ~~R6~~ | ~~Should~~ | ~~6~~ | ~~`@file:OptIn` used broadly~~ — **RESOLVED** (see §R6) |
| ~~R7~~ | ~~**Must**~~ | ~~5~~ | ~~`DisposableEffect` disposes composition when renderer changes~~ — **RESOLVED** (see §R7) |
| ~~R8~~ | ~~Should~~ | ~~6~~ | ~~`@property:ExperimentalIsometricApi` doesn't cover constructor parameter~~ — **RESOLVED (won't fix)** (see §R8) |
| ~~R9~~ | ~~Should~~ | ~~4~~ | ~~`onRenderError` has no ergonomic wiring path~~ — **RESOLVED** (see §R9) |
| ~~R10~~ | ~~Should~~ | ~~5~~ | ~~`close()` doesn't guard against post-close usage~~ — **RESOLVED** (see §R10) |

---

## ~~R1~~ — `rebuildCache` Engine Ordering — RESOLVED

**Status**: The code now correctly calls `rootNode.render(context)` BEFORE `engine.clear()`, with an explicit "point of no return" comment at line 424. The retry-loop concern from the first review pass is addressed — if `render()` throws, the engine retains the previous frame's data and no cache fields are mutated. The `onRenderError` callback fires and the renderer continues drawing the last good frame.

The tight retry loop still exists (each frame attempts `render()` again because `isDirty` stays true and `cacheValid` stays false), but now each retry is cheap — just a `render()` call and catch, no engine mutation. This is acceptable behavior: the scene will self-heal when the problematic node is removed or fixed.

---

## ~~R2~~ — `ComposeRenderer.kt` Not Deleted — RESOLVED

**Status**: `ComposeRenderer.kt` (48 lines) is now deleted. The diff shows `D isometric-compose/.../compose/ComposeRenderer.kt`. Verified zero remaining references: `grep -r "ComposeRenderer" --include="*.kt"` returns no matches across the entire codebase. The duplicated `renderIsometric()` and `toComposePath()` functionality is removed — `IsometricRenderer` is the sole render implementation.

---

## ~~R7~~ — `DisposableEffect(composition, renderer)` Crashes When Renderer Changes — RESOLVED

**File**: `IsometricScene.kt` lines 187-203

**Problem**: WS4 changed the `DisposableEffect` key from `(composition)` to `(composition, renderer)` so the old renderer gets `close()` on replacement. But this introduces a **composition lifecycle violation**.

**Current code**:
```kotlin
DisposableEffect(composition, renderer) {
    composition.setContent { ... }
    onDispose {
        composition.dispose()
        renderer.close()
    }
}
```

**What happens when renderer config changes** (e.g., `enablePathCaching` toggled at runtime):

1. `remember(engine, config.enablePathCaching, ...)` creates a NEW `IsometricRenderer`
2. `composition` stays the SAME — its `remember(compositionContext)` key didn't change
3. `DisposableEffect` sees `renderer` changed → fires `onDispose` on OLD effect:
   - `composition.dispose()` — marks the composition as disposed (`disposed = true`)
   - `renderer.close()` — closes the OLD renderer (correct)
4. `DisposableEffect` re-runs the effect body with current values:
   - `composition.setContent { ... }` — **calls `setContent()` on a disposed composition**
5. Jetpack Compose's `Composition.setContent()` calls `check(!disposed)` → **throws `IllegalStateException`**

**Why it doesn't crash today**: The renderer only changes when `engine`, `enablePathCaching`, `enableSpatialIndex`, or `spatialIndexCellSize` change at runtime. With the simple `SceneConfig()` overload these are constant, so the bug never triggers. But any `AdvancedSceneConfig` usage that dynamically toggles renderer parameters (e.g., benchmarks toggling `enablePathCaching`) will crash.

**Fix**: Separate the renderer lifecycle from the composition lifecycle:

```kotlin
// Close renderer when it changes or scene leaves tree
DisposableEffect(renderer) {
    onDispose {
        renderer.close()
    }
}

// Manage composition lifecycle independently
DisposableEffect(composition) {
    composition.setContent {
        CompositionLocalProvider(...) {
            IsometricScopeImpl.currentContent()
        }
    }
    onDispose {
        composition.dispose()
    }
}
```

This ensures:
- When `renderer` changes → old renderer closed, composition stays alive
- When `composition` changes → old composition disposed, composition content re-set
- When scene leaves tree → both dispose in their natural order

**Status**: Fixed — `DisposableEffect` split into two independent effects in `IsometricScene.kt`: one keyed on `renderer` (calls `renderer.close()`) and one keyed on `composition` (manages `setContent`/`dispose`). Renderer recreation no longer disposes the composition.

---

## ~~R3~~ — Orphaned Legacy State Holders — RESOLVED

**Status**: Both files are now deleted:
- `IsometricSceneState.kt` (52 lines) — `D` in diff
- `RememberIsometricSceneState.kt` (15 lines) — `D` in diff

Verified zero remaining references: `grep -r "IsometricSceneState\|rememberIsometricSceneState\|RememberIsometricSceneState" --include="*.kt"` returns no matches. The entire legacy state-management layer (`IsometricCanvas` → `IsometricSceneState` → `rememberIsometricSceneState`) is cleanly excised.

---

## ~~R4~~ — Stale Sample Card Description — RESOLVED

**Status**: `MainActivity.kt` now reads:
```kotlin
title = "Compose Scene API",
description = "IsometricScene samples: shapes and interaction",
```

Both the title ("Compose Canvas API" → "Compose Scene API") and description ("State-driven IsometricCanvas samples: shapes, animation, interaction" → "IsometricScene samples: shapes and interaction") are updated to match the actual `IsometricScene` API usage in `ComposeActivity`. The "animation" reference is correctly removed since `AnimatedSample` was deleted (R5).

---

## ~~R5~~ — AnimatedSample Removed — RESOLVED

**File**: `app/src/main/kotlin/io/fabianterhorst/isometric/sample/ComposeActivity.kt`

**Problem**: The entire `AnimatedSample` composable was deleted and its tab removed from the sample screen.

**Status**: Fixed — `AnimatedSample` restored using the runtime `IsometricScene` API with `delay(16)` animation loop. A `TODO(WS8)` comment marks the `delay(16)` → `withFrameNanos` conversion for WS8. The "Animated" tab is back in the sample chooser.

---

## ~~R6~~ — Broad `@file:OptIn` Instead of Targeted Annotations — RESOLVED

**Problem**: Production files used `@file:OptIn(ExperimentalIsometricApi::class)` which silences opt-in warnings for the entire file.

**Status**: Fixed — production files now use targeted `@OptIn` on specific declarations:
- `IsometricEngine.kt`: `@file:OptIn` replaced with `@OptIn` on `sortPaths()` method only
- `IsometricScene.kt`: `@file:OptIn` replaced with `@OptIn` on `RuntimeFlagSnapshot` class and the `SideEffect` block that reads `enableBroadPhaseSort`
- `RenderOptions.kt`: Retains `@file:OptIn` (defensible — it *defines* the experimental properties)
- Test and benchmark files: Retain `@file:OptIn` (acceptable for non-production code)

---

## ~~R8~~ — `@property:ExperimentalIsometricApi` Doesn't Cover Constructor Parameter — RESOLVED (won't fix)

**File**: `RenderOptions.kt` lines 16-19

**Problem**: `@property:` use-site target annotates the property getter only, not the constructor parameter. Construction via `RenderOptions(enableBroadPhaseSort = true)` produces no opt-in warning.

**Status**: Won't fix — **Kotlin language limitation**. Attempted applying `@ExperimentalIsometricApi` (bare, targeting `VALUE_PARAMETER`) and `@get:ExperimentalIsometricApi` to constructor `val` parameters. Both produce compiler errors:
- `@ExperimentalIsometricApi` on a constructor `val` → "Opt-in requirement marker annotation cannot be used on parameter"
- `@get:ExperimentalIsometricApi` → "cannot be used on getter"
- Same errors on `copy()` function parameters

Kotlin's `@RequiresOptIn` mechanism does not support gating constructor parameters or function parameters, regardless of the `@Target` declaration. The `@property:` target on the property getter is the best available coverage. Consumers who *read* the experimental properties get the warning; consumers who only *construct* with them do not. This is a known limitation documented here for future reference.

The `copy()` parameters are left unannotated for the same reason.

---

## ~~R9~~ — `onRenderError` Has No Wiring Path from Config — RESOLVED

**Problem**: `onRenderError` was only settable via the `onRendererReady` escape hatch, not through config.

**Status**: Fixed — `onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null` added to `AdvancedSceneConfig`. Wired in `IsometricScene.kt` via `SideEffect { renderer.onRenderError = config.onRenderError }`, matching the existing `benchmarkHooks` wiring pattern.

---

## ~~R10~~ — `close()` Doesn't Guard Against Post-Close Usage — RESOLVED

**Problem**: No enforcement of post-close contract — `rebuildCache()` after `close()` silently succeeded.

**Status**: Fixed — added `private var closed = false` flag. `close()` sets `closed = true`. `ensurePreparedScene()` starts with `check(!closed) { "Renderer has been closed and cannot be used for rendering" }`. The `close clears callbacks` test updated to verify `IllegalStateException` on post-close `rebuildCache()` instead of silently succeeding.

---

## Code Boundary Analysis

### Boundary 1: `IsometricNode.children` visibility × `IsometricApplier` access

**Flow**: `IsometricApplier` mutates the node tree via `parent.children.add()`, `.removeAt()`, `.move()`, `.clear()`. After WS4, `children` is `internal open` on `IsometricNode`.

**Verification**: `IsometricApplier` is in the same module (`isometric-compose`) → `internal` is visible. All 5 call sites in the Applier (lines 54, 73, 92, 108, 109) compile correctly. External modules (like `app/`) can no longer access `children`, which is the intended encapsulation goal.

**Edge case**: `PrimitiveLevelsExample.kt` in the `app` module defines `MultiShapeNode extends IsometricNode`. Before WS4, it had `override val children = mutableListOf()`. After WS4, it drops the override since the base class provides a default. Since `children` is `internal` in the base class, `app` can't see it — but the default initializer still runs, giving `MultiShapeNode` an empty list. Since `MultiShapeNode` is a leaf node (no children), this is correct.

**Future risk**: WS6's `CustomNode` (F58) needs `children` access. Since `CustomNode` will be in the same module, `internal` works. If it moves to a separate module, this breaks. The plan documents this correctly.

### Boundary 2: `DisposableEffect` lifecycle × `SideEffect` ordering

**Flow**: `IsometricScene` uses multiple `SideEffect` blocks (lines 116-168) to bridge composition values into the imperative renderer, and a `DisposableEffect` (line 187) to manage the composition lifecycle.

**Compose phase ordering**: During recomposition, the execution order is:
1. `remember` blocks evaluate (potentially creating new renderer)
2. `SideEffect` blocks are SCHEDULED (not yet executed)
3. `DisposableEffect` onDispose fires if keys changed
4. `DisposableEffect` body runs if keys changed
5. `SideEffect` blocks execute AFTER composition completes

**Consequence for R7**: When `renderer` changes:
- Step 3 disposes the composition (R7 bug)
- Step 5 runs `SideEffect { renderer.benchmarkHooks = ... }` on the NEW renderer — this is correct
- But the composition is already disposed, so the Canvas lambda never fires — the `SideEffect` runs but the render path is dead

After applying the R7 fix (separate DisposableEffects), the ordering becomes safe: the renderer `DisposableEffect` closes the old renderer, the composition `DisposableEffect` stays untouched, and `SideEffect` blocks wire the new renderer correctly.

### Boundary 3: `close()` × `SideEffect` callback wiring

**Flow**: `close()` nulls `benchmarkHooks` and `onRenderError`. But the next `SideEffect` run re-assigns `renderer.benchmarkHooks = currentBenchmarkHooks`.

**Current behavior**: In the only path where `close()` fires (`onDispose`), the scene is leaving the tree, so no subsequent `SideEffect` runs. No conflict.

**After R7 fix**: When renderer changes, the OLD renderer is closed in its `DisposableEffect`. The NEW renderer is a fresh instance — `SideEffect` assigns hooks to the NEW renderer, not the closed old one. No conflict.

### Boundary 4: `AtomicLong` node IDs × `RenderCommand` IDs × hit-test maps

**Flow**:
1. `IsometricNode.nodeId` = `"node_${nextId.getAndIncrement()}"` — globally unique, process-scoped
2. `ShapeNode.render()` creates command IDs = `"${nodeId}_${path.hashCode()}"` — unique if nodeId is unique (hashCode adds per-path disambiguation)
3. `PathNode.render()` uses `nodeId` directly as command ID — 1:1 mapping
4. `BatchNode.render()` uses `"${nodeId}_${index}_${path.hashCode()}"` — unique per batch item
5. `rebuildCache()` builds `nodeIdMap`, `commandIdMap`, `commandOrderMap`, `commandToNodeMap` from these IDs
6. `hitTest()` uses `commandToNodeMap` for O(1) command→node resolution

**Correctness**: The ID chain is sound. `AtomicLong` guarantees monotonic uniqueness. The `hashCode()` suffix in ShapeNode/BatchNode command IDs prevents collisions between paths within the same shape. The maps are rebuilt on every `rebuildCache()`, so stale IDs are never queried.

**Subtle note**: Node IDs are never reset. If a scene is destroyed and recreated, the new scene's first node gets IDs continuing from where the old scene left off. This is correct (uniqueness > sequential beauty) but means tests should never assert on specific ID values.

### Boundary 5: `rebuildCache()` error recovery × `needsUpdate()` retry behavior

**Flow**: After the R1 fix, when `render()` throws:
1. `engine.clear()` is NOT called (it's after `render()`)
2. `cachedPreparedScene`, `cacheValid`, all maps retain their previous values
3. `rootNode.markClean()` is NOT called → `rootNode.isDirty` remains `true`
4. `onRenderError("rebuild", e)` fires
5. Next frame: `needsUpdate()` returns `true` (isDirty=true) → `rebuildCache()` runs again
6. `render()` throws again → repeat

**Assessment**: This is a bounded retry loop — one `render()` call + one `onRenderError` callback per frame. No engine mutation, no cache corruption. The loop exits automatically when:
- The bad node is removed from the tree (Compose Runtime removes it → Applier fires → markDirty → next rebuild succeeds)
- The bad node fixes itself (e.g., data updates that caused the exception are resolved)

This is acceptable behavior — the renderer keeps trying without losing the last good frame. The `onRenderError` callback at ~60/sec is the only cost, and since it's `null` by default (R9), there's no overhead in production.

### Boundary 6: `Closeable.close()` × `clearCache()` × engine shared reference

**Flow**: `close()` calls `clearCache()` which nulls all cache fields (`cachedPreparedScene`, `cachedPaths`, `spatialIndex`, all maps). It then nulls `benchmarkHooks` and `onRenderError`.

**What `close()` does NOT do**:
- Does not null the `engine` field (it's a `private val` constructor parameter)
- Does not call `engine.close()` or `engine.clear()` — the engine is shared with `IsometricScene`
- Does not set a `closed` flag (R10)

**Consequence**: The `engine` reference survives `close()`. The engine may still contain shapes/paths from the last successful `rebuildCache()`. When a new `IsometricRenderer` is created (because config changed), it receives the same engine instance (via `remember(config.engine) { config.engine }`). The new renderer's first `rebuildCache()` calls `engine.clear()` before adding new commands, so stale engine state is cleaned up.

This is correct — the engine is "borrowed" by the renderer, not "owned" by it. `close()` releases the renderer's caches without interfering with the shared engine.

### Boundary 7: `@ExperimentalIsometricApi` annotation propagation

**Flow**: `Knot` class and `RenderOptions.enableBroadPhaseSort/broadPhaseCellSize` are annotated. Code that uses them needs `@OptIn`.

**Propagation chain for `enableBroadPhaseSort`**:
1. `RenderOptions(enableBroadPhaseSort = true)` — **no warning** (R8: constructor parameter unannotated)
2. `RenderOptions.Default` (companion object) — constructed with `@file:OptIn` in RenderOptions.kt — **OK**
3. `AdvancedSceneConfig.renderOptions` → passed through to `RenderContext.renderOptions` → read in `IsometricRenderer.rebuildCache()` at `context.renderOptions` — **no warning** (reading the `RenderContext` wrapper, not the property directly)
4. `IsometricScene.kt` reads `config.renderOptions.enableBroadPhaseSort` at line 162 — **would warn** without `@file:OptIn` (property getter is annotated)

**Conclusion**: The annotation is only half-effective. The construction and `copy()` paths are unprotected (R8). The property read path is protected but globally opted-in via `@file:OptIn` (R6). In practice, the annotation currently provides zero consumer friction — every file that touches broad-phase already has `@file:OptIn`.

---

## Pass 3: Behavioral Flow Analysis

The third pass verified R2/R3/R4 fixes and traced runtime behavior across component boundaries. No new must-fix or should-fix findings were discovered. The following behavioral observations supplement the Code Boundary Analysis from pass 2.

### Flow 1: ComposeActivity Migration — API Semantics Change

The `InteractiveSample` migration changes the tap callback from command-level to node-level identity:

```kotlin
// OLD (IsometricCanvas):
onItemClick = { item -> clickedItem = item.commandId }
// e.g., "node_42_<hashCode>" — identifies a single face of the shape

// NEW (IsometricScene):
onTap = { event: TapEvent -> clickedItem = event.node?.nodeId }
// e.g., "node_42" — identifies the shape as a whole
```

**Behavioral difference**: The new API reports which shape was tapped (semantically correct), not which face polygon was hit (implementation detail). The `?.nodeId` null-safe access also means tapping empty space sets `clickedItem = null`, clearing the display — the old `onItemClick` only fired on hits, so the last-clicked text persisted until another shape was tapped. Both changes are improvements.

### Flow 2: `forceRebuild` × Spatial Index Rebuild Cost

When `forceRebuild = true` (benchmarks only), `ensurePreparedScene()` calls `clearCache()` every frame, wiping the spatial index, path cache, and all ID maps. Then `rebuildCache()` reconstructs everything from scratch. The per-frame cost chain:

1. `clearCache()` — nulls 6 cache fields (O(1))
2. `rootNode.render(context)` — traverses entire node tree (O(n))
3. `engine.clear()` + `engine.add()` × n — engine population (O(n))
4. `engine.projectScene()` — 3D→2D projection + depth sort (O(n log n))
5. `buildCommandMaps()` — 3 HashMap builds (O(n))
6. `buildPathCache()` — Compose Path conversion (O(n))
7. `buildSpatialIndex()` — grid insertion + bounds calc (O(n))

With `forceRebuild = false` (production), steps 1-7 only run when `needsUpdate()` returns true (dirty tree, changed viewport, or changed render options). This is correct: `forceRebuild` is explicitly for measuring per-frame overhead.

**No race condition**: `ensurePreparedScene()` is synchronous — `clearCache()` and `rebuildCache()` execute in the same call before any read of cache fields. Hit-test and render paths both enter through `ensurePreparedScene()`, so they always see a consistent cache.

### Flow 3: `close clears callbacks` Test — Documents R10 Contradiction

The test at line 844-857 does:
```kotlin
renderer.close()
renderer.rebuildCache(root, defaultContext, 800, 600)
// ↑ succeeds silently — renderer "re-opens" after close()
```

This is not a test bug per se — it correctly demonstrates that callbacks are null after close (the `CountingHooks` aren't invoked during the post-close rebuild). But it also demonstrates the R10 concern: `rebuildCache()` after `close()` silently succeeds, contradicting the KDoc contract "should not be used for rendering." After R10 is fixed (adding a `closed` flag), this test should be updated to `assertThrows` on post-close usage.

### Flow 4: Legacy Deletion Completeness Verification

After the R2/R3 fixes, the entire legacy Compose API surface is now deleted:

| Legacy Component | Status | References |
|-----------------|--------|------------|
| `Color.kt` (deprecated wrapper) | Deleted | 0 |
| `IsometricCanvas.kt` (entry point) | Deleted | 0 |
| `ComposeRenderer.kt` (render object) | Deleted | 0 |
| `IsometricSceneState.kt` (state holder) | Deleted | 0 |
| `RememberIsometricSceneState.kt` (remember factory) | Deleted | 0 |

The `io.fabianterhorst.isometric.compose` package now contains zero public API files. All remaining files are in the `io.fabianterhorst.isometric.compose.runtime` subpackage, which is the intended new API surface. This is a clean break with no migration bridges — consistent with the project's preference for direct breaking changes over deprecation cycles.

### Flow 5: `ExperimentalIsometricApi` Target List × R8 Fix Compatibility

The annotation's target list is `CLASS, FUNCTION, PROPERTY, VALUE_PARAMETER`. For the R8 fix (removing `@property:` prefix):

- Kotlin's default use-site target resolution for `val` in a primary constructor: **param** → **property** → **field**
- Since `VALUE_PARAMETER` is in the target list, bare `@ExperimentalIsometricApi` would apply to the constructor parameter (first match in resolution order)
- The `PROPERTY` getter would then be unannotated — consumers could read `opts.enableBroadPhaseSort` without opt-in
- Full coverage requires both: `@ExperimentalIsometricApi @get:ExperimentalIsometricApi val enableBroadPhaseSort`
- The `copy()` function's parameters are regular value parameters — `VALUE_PARAMETER` target covers them

The annotation definition is already compatible with the R8 fix. No changes to `ExperimentalIsometricApi.kt` are needed.

---

## Positive Observations

### What was done well:

1. **`children` visibility refactoring (Step 2)** — Elegant change from `abstract` to `open` with a default empty list. This eliminates redundant `override val children = mutableListOf()` in all leaf nodes (ShapeNode, PathNode, BatchNode, and the external `MultiShapeNode` in PrimitiveLevelsExample). GroupNode retains its override for clarity. The `internal` visibility correctly blocks external mutation while allowing same-module Applier access.

2. **Atomic ID generation (Step 3)** — Clean `AtomicLong` companion replacement for `System.identityHashCode(this)`. The test coverage is thorough: 10k uniqueness test + concurrent 2-thread test with `CountDownLatch`. The KDoc on `nodeId` correctly documents the guarantee.

3. **Error handling (Step 4)** — All four render paths (fast/cached, native, fallback/prepared-scene, rebuildCache) are wrapped. The `onRenderError` callback design is minimal and correct — nullable function type means zero allocation overhead in production when not set. The R1 fix correctly orders `render()` before `engine.clear()`.

4. **Closeable lifecycle (Step 5)** — `close()` correctly extends `clearCache()` by also nulling callback references. The design of not touching the shared `engine` is the right ownership boundary.

5. **Experimental annotations (Step 6)** — `@RequiresOptIn` annotation is well-defined with `Level.WARNING` (not ERROR), `AnnotationRetention.BINARY`, and comprehensive target coverage. Knot's KDoc upgrade is informative and actionable.

6. **Platform safety (Step 7)** — `remember {}` wrapping `Class.forName` is the correct Compose-idiomatic way to run a one-time construction check. The error message is actionable with a clear remediation path.

7. **Snapshot test migration (Step 1)** — Rather than deleting `IsometricCanvasSnapshotTest.kt`, the implementation migrated all 13 test cases from the legacy `IsometricCanvas` API to the runtime `IsometricScene` API. This preserves visual regression coverage while validating the new API. The `Path` import alias (`import ... Path as IsoPath`) avoids the `Shape` composable / `Shape` class name collision cleanly.

8. **ComposeActivity migration** — All sample code migrated from legacy `IsometricCanvas` + `rememberIsometricSceneState` to `IsometricScene` + `SceneConfig`/`GestureConfig`. The interactive sample correctly uses the new `TapEvent`-based gesture callback pattern.

---

## Step-by-Step Coverage Verification

| Plan Step | Finding(s) | Status | Notes |
|-----------|-----------|--------|-------|
| Step 1: Delete legacy files | F8 | Done | `Color.kt` deleted |
| Step 1: Delete legacy files | F13/F44/F12 | Done | `IsometricCanvas.kt`, `ComposeRenderer.kt`, `IsometricSceneState.kt`, `RememberIsometricSceneState.kt` all deleted (R2/R3 resolved) |
| Step 2: Encapsulation | F74 | Done | `children` → `internal open` with default, leaf nodes simplified |
| Step 3: Atomic IDs | F43 | Done | `AtomicLong` counter, comprehensive tests |
| Step 4: Error handling | F23 | Done | Try/catch in all 4 paths, `engine.clear()` correctly ordered after `render()` (R1 resolved) |
| Step 5: Lifecycle | F25 | Done | `Closeable` + `close()` correct, `DisposableEffect` split into two effects (R7 resolved), closed-state guard added (R10 resolved) |
| Step 6: Experimental | F24 | Done | `enableBroadPhaseSort` + `broadPhaseCellSize` annotated with `@property:` (R8: constructor param annotation is a Kotlin limitation — won't fix) |
| Step 6: Experimental | F11 | Done | `Knot` class annotated, KDoc updated |
| Step 7: Platform safety | F42 | Done | `Class.forName` check in `remember {}` block |

---

## File Change Inventory

| File | Plan says | Actual | Match? |
|------|-----------|--------|--------|
| `Color.kt` | DELETE | Deleted | Yes |
| `IsometricCanvas.kt` | DELETE | Deleted | Yes |
| `ComposeRenderer.kt` | DELETE (F44) | Deleted | Yes (R2 resolved) |
| `IsometricSceneState.kt` | Not mentioned | Deleted (orphan cleanup) | Yes (R3 resolved) |
| `RememberIsometricSceneState.kt` | Not mentioned | Deleted (orphan cleanup) | Yes (R3 resolved) |
| `IsometricNode.kt` | Steps 2, 3 | Modified | Yes |
| `IsometricRenderer.kt` | Steps 4, 5 | Modified | Yes (R1 resolved) |
| `IsometricScene.kt` | Steps 5, 7 | Modified | Yes (R7 resolved) |
| `RenderOptions.kt` | Step 6 | Modified | Yes (R8: Kotlin limitation, won't fix) |
| `Knot.kt` | Step 6 | Modified | Yes |
| `ExperimentalIsometricApi.kt` | Step 6 (NEW) | Created | Yes |
| `IsometricCanvasSnapshotTest.kt` | DELETE or update | Updated (migrated) | Yes |
| `IsometricRendererTest.kt` | Steps 4, 5 | Modified (WS4 tests added) | Yes |
| `ComposeActivity.kt` | Not in plan | Migrated from legacy API | Yes (bonus) |
| `PrimitiveLevelsExample.kt` | Not in plan | `children` override removed | Yes (consequence of Step 2) |
| `IsometricEngine.kt` | Step 6 (@OptIn) | Targeted `@OptIn` on `sortPaths()` | Yes (R6 resolved) |
| `IsometricEngineTest.kt` | Step 6 (@OptIn) | `@file:OptIn` added | Yes (acceptable for tests) |
| Benchmark files (5) | Step 6 (@OptIn) | `@file:OptIn` added | Yes (acceptable for benchmarks) |
| `MainActivity.kt` | Not in plan | Description updated | Yes (R4 resolved) |

---

## Test Coverage Assessment

| Test Category | Plan Requirement | Actual | Quality |
|--------------|-----------------|--------|---------|
| Error: rebuildCache throws | Yes | `onRenderError is invoked when rebuildCache throws` | Good — verifies callback ID and message |
| Error: cache preserved on failure | Yes | `rebuildCache preserves previous cache on failure` | Good — correctly verifies old scene retained |
| Lifecycle: close() idempotent | Yes | `close is idempotent` | Good — calls twice, checks no throw |
| Lifecycle: callbacks cleared | Yes | `close clears callbacks` | Adequate — indirect verification; calls `rebuildCache` after `close` which contradicts documented contract (R10) |
| IDs: uniqueness | Yes | `node IDs are unique across many nodes` | Good — 10k nodes |
| IDs: concurrent safety | Yes | `node IDs are unique across concurrent creation` | Good — 2 threads × 5k |
| Snapshot: legacy API migrated | Yes | 13 test cases migrated | Excellent |
| Error: render path catch | Mentioned in plan | **Not tested** | Gap — no test injects a bad command into the draw loop (fast/native/fallback paths) |
| Platform: useNativeCanvas check | Mentioned in plan | **Not tested** | Gap — no test verifies the `IllegalStateException` on non-Android |
| Lifecycle: DisposableEffect key | Not in plan | **Not tested** | Gap — no test verifies renderer recreation doesn't crash (R7) |

---

## Verdict

The implementation covers all 7 plan steps and is **complete**. All 10 review findings are resolved. The code quality across each step is high — the `children` refactoring, atomic IDs, try/catch placement, Closeable design, experimental annotations, and platform check are all well-executed with appropriate rationale. The legacy API deletion is clean (R2/R3/R4), the composition lifecycle crash is fixed (R7), error callback wiring is ergonomic (R9), and post-close safety is enforced (R10). The only non-fix is R8, a documented Kotlin language limitation.

**All 10 findings resolved**:
- ~~R1~~: `rebuildCache` engine ordering fixed (render before clear)
- ~~R2~~: `ComposeRenderer.kt` deleted
- ~~R3~~: Orphaned `IsometricSceneState.kt` + `RememberIsometricSceneState.kt` deleted
- ~~R4~~: `MainActivity.kt` description updated
- ~~R5~~: `AnimatedSample` restored with runtime API + `TODO(WS8)` marker
- ~~R6~~: Targeted `@OptIn` in production files (`IsometricEngine.kt`, `IsometricScene.kt`)
- ~~R7~~: `DisposableEffect` split into two independent lifecycle effects
- ~~R8~~: Won't fix — Kotlin `@RequiresOptIn` limitation prevents constructor/function parameter annotation; `@property:` is best available
- ~~R9~~: `onRenderError` added to `AdvancedSceneConfig` and wired via `SideEffect`
- ~~R10~~: `closed` flag + `check(!closed)` guard in `ensurePreparedScene()`
