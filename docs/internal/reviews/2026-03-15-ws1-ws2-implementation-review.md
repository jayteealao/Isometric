# WS1 + WS2 Implementation Review

> **Date**: 2026-03-15
> **Scope**: All uncommitted changes on `feat/api-changes` implementing WS1a (core type hardening), WS1b (core type modernization), and WS2 (entry point & configuration redesign)
> **Files reviewed**: 41 files (34 WS1, 7 WS2 modified + 5 WS2 new)
> **Build**: All modules compile. All unit tests pass (core + compose + android-view + app + benchmark).
> **Second pass**: 2026-03-15 — full re-read of all WS2 files; 3 new findings added (W2-4, W2-5, W2-P2)

---

## Summary

| | WS1 | WS2 |
|---|---|---|
| **Verdict** | Solid — 2 bugs, 3 plan deviations (all improvements) | 6 bugs (1 high, 1 high visual, 1 medium, 3 low), 2 deviations, 2 perf concerns |
| **Architecture** | Correct. Validation, operators, data→class, covariant translate all land cleanly | Correct. 21-param monolith properly decomposed into typed config objects |
| **Test coverage** | Good — new validation, operator, subtype-preservation, and hit-order tests | Adequate — call sites updated, but no new unit tests for config types |

---

## WS1 Findings

### BUG W1-1: `IsometricRendererTest` — previously-dead test now enabled

**Severity**: Low
**File**: `isometric-compose/src/test/.../IsometricRendererTest.kt`

```diff
+    @Test
     fun `stable hitTest reuses prepared scene unless forceRebuild is enabled`() {
```

The `@Test` annotation was **added** to an existing test function that previously lacked it. This means a test that was never running is now enabled. JUnit discovers test methods by annotation — a `fun` without `@Test` is just a regular function that never executes. Verify this test passes independently and isn't flaky before committing.

**Action**: Run the specific test in isolation to confirm it passes.

---

### BUG W1-2: `Circle` init validation fires AFTER `Path` superclass guard

**Severity**: Low
**File**: `isometric-core/.../paths/Circle.kt`

```kotlin
class Circle(
    origin: Point, radius: Double = 1.0, vertices: Int = 20
) : Path(createCirclePoints(origin, radius, vertices)) {
    init {
        require(radius > 0.0) { "Circle radius must be positive, got $radius" }
        require(vertices >= 3) { "Circle needs at least 3 vertices, got $vertices" }
    }
```

If called as `Circle(Point.ORIGIN, 1.0, vertices = 2)`:
1. `createCirclePoints(origin, 1.0, 2)` generates 2 points
2. `Path(2-point list)` triggers `require(this.points.size >= 3)` → throws **"Path requires at least 3 points"**
3. Circle's `init` **never reached** — the Circle-specific "Circle needs at least 3 vertices" message never fires

The WS1a plan (section 1b) explicitly warned about this scenario.

**Fix**: Move validation into the companion factory:
```kotlin
companion object {
    private fun createCirclePoints(origin: Point, radius: Double, vertices: Int): List<Point> {
        require(radius > 0.0) { "Circle radius must be positive, got $radius" }
        require(vertices >= 3) { "Circle needs at least 3 vertices, got $vertices" }
        // ... existing generation code ...
    }
}
```

---

### DEVIATION W1-D1: `RenderContext.copy()` preserves accumulated transforms (CORRECT)

**Plan said**: "Private accumulated transform state is **reset** to defaults"
**Implementation does**: **Preserves** accumulated transforms

The implementation is more correct than the plan. If you're mid-render with accumulated transforms and want to change `renderOptions`, resetting the transform state would corrupt the render tree. The new test `render context copy preserves accumulated transforms` validates this. No action needed.

---

### DEVIATION W1-D2: Cylinder secondary constructor kept (not deleted)

**Plan said**: Delete `Cylinder(origin, vertices, height)` as redundant
**Implementation**: Keeps it, updated to new parameter names

Pragmatic choice — the primary constructor reordered parameters (`height` moved before `vertices`), so the secondary constructor serves as a compatibility bridge for the old `(position, vertices, height)` positional calling pattern. No action needed.

---

### DEVIATION W1-D3: Additional `fromHex(String)` overload

**Plan specified**: Only `fromHex(hex: Long)`
**Implementation adds**: `fromHex(hex: String)` with `#` and `0x` prefix stripping

Pure addition that improves usability. Well-implemented with clear 6/8 digit length validation. No action needed.

---

### WS1 Correctness Matrix

| Area | Status | Notes |
|---|---|---|
| IsoColor validation | PASS | Channel range guards, `withLightness()` coerceIn for float imprecision |
| IsoColor lazy HSL | PASS | `LazyThreadSafetyMode.NONE` correct — no thread sharing during render |
| IsoColor `Int` constructor | PASS | Delegates to `Double` constructor, validation fires correctly |
| IsoColor `fromHex(Long)` edge cases | PASS | Handles negative (sign-extended ARGB), RGB <= 0xFFFFFF, ARGB > 0xFFFFFF |
| Shape dimension guards | PASS | Prism, Pyramid, Cylinder, Stairs all validated |
| Path minimum-3-points guard | PASS | Defensive copy via `points.toList()` is excellent |
| Path `depth` precalculation | PASS | Uses `this.points` (after defensive copy) correctly |
| Shape `orderedPaths()` sort | PASS | `sortedByDescending` produces identical ordering to old bubble sort |
| Vector `i/j/k` to `x/y/z` | PASS | All references updated; `crossProduct` locals renamed to `cx/cy/cz` |
| Infix `cross`/`dot` operators | PASS | Mathematically identical to companion statics, verified by test |
| Point operators | PASS | `Point - Point = Vector`, `Point + Vector = Point` — correct affine semantics |
| `data class` to regular class | PASS | Point, Vector, RenderCommand, PreparedScene, RenderOptions, RenderContext, ColorPalette |
| Explicit `equals`/`hashCode`/`toString` | PASS | All converted classes have correct implementations |
| RenderOptions explicit `copy()` | PASS | Matches old data class signature exactly |
| Covariant `translate()` overrides | PASS | Prism, Pyramid, Cylinder, Stairs, Octahedron, Knot |
| `Shape.translate()` now `open` | PASS | Required for covariant overrides |
| `rotateX/Y/Z`/`scale` stay non-open | PASS | Correct — rotation can't preserve parametric subtype |
| HitOrder enum | PASS | Clean replacement of boolean `reverseSort` |
| `touchRadius` replaces booleans | PASS | Default 0.0 = disabled; positive = enabled |
| IsometricView defaults preserved | PASS | `hitOrder = BACK_TO_FRONT`, `touchRadius = 0.0` matches old defaults |
| Color extension extraction | PASS | Moved from member extensions to top-level; `coerceIn` removed |
| RuntimeApiActivity color fix | PASS | `sin()` can return negative — clamped to `[0,255]` via `(sin(x)+1)/2 * 255` |
| Cylinder parameter reorder | PASS | `(position, radius, height, vertices)` — height before vertices |
| All `findItemAt` callsites | PASS | Engine, Renderer (x2), Canvas, View, Tests — all updated |
| `hullPoints` extraction | PASS | Eliminates duplicate `.map {}` in hit test |

### WS1 Minor Observations

1. **`Path` has no `equals`/`hashCode`** — Uses reference equality. This is fine (pre-existing behavior), but means `RenderCommand.equals()` compares paths by reference.
2. **`closerThan` dead guard** — `Path.closerThan()` still has `if (pathA.points.size < 3) return 0` which is now redundant since `Path` requires >= 3 points. Harmless dead code.
3. **`IsoColor` stays `data class`** — Explicitly per-plan (WS1b section 1e). `h`/`s`/`l` are body-declared computed properties correctly excluded from data class equality.

---

## WS2 Findings

### BUG W2-1 (Significant): `remember { config.engine }` captures stale engine

**Severity**: High
**File**: `isometric-compose/.../IsometricScene.kt` line 70

```kotlin
val engine = remember { config.engine }
```

`remember { }` without keys executes its lambda **once** during initial composition and caches the result forever. If the caller passes a different `AdvancedSceneConfig` with a new engine on recomposition, the old engine is permanently retained.

Before WS2, this was `remember { IsometricEngine() }` — the engine was always created internally, so there was nothing to go stale. Now that `config.engine` is user-provided, the engine can change.

**Impact**: If a caller dynamically updates `AdvancedSceneConfig(engine = IsometricEngine(scale = 200.0))`, the scene silently ignores it and keeps rendering with the original engine.

**Fix**:
```kotlin
val engine = remember(config.engine) { config.engine }
```

---

### BUG W2-2: `DisposableEffect` callbacks don't re-fire when config changes

**Severity**: Low
**File**: `isometric-compose/.../IsometricScene.kt` lines 80-84

```kotlin
DisposableEffect(engine, renderer) {
    config.onEngineReady?.invoke(engine)
    config.onRendererReady?.invoke(renderer)
    onDispose { }
}
```

When `config` itself changes (new `AdvancedSceneConfig` instance with different callbacks) but engine/renderer stay the same, `DisposableEffect` doesn't re-fire because its keys are `engine` and `renderer`, not `config`.

**Scenario**: User provides `AdvancedSceneConfig(onEngineReady = callback1)`, then recomposes with `onEngineReady = callback2`. The engine hasn't changed, so `callback2` is never called.

**Mitigation**: If these are truly fire-once notification callbacks, document this behavior. If re-notification is desired, add `config.onEngineReady` and `config.onRendererReady` as keys.

---

### BUG W2-3: `PrimitiveLevelsExample.kt` renders at 0x0

**Severity**: Medium
**File**: `app/.../PrimitiveLevelsExample.kt` lines 266, 312

```kotlin
IsometricScene {   // no modifier — was relying on internal fillMaxSize()
    Shape(...)
}
```

Before WS2, the internal `fillMaxSize()` ensured these rendered at full size. After WS2 removes it, these scenes get no explicit size and render at 0x0 if their parent doesn't provide constraints.

**Fix**: Add `modifier = Modifier.fillMaxSize()` to these call sites, same as was done for `RuntimeApiActivity.kt`.

---

### PERF W2-P1: `strokeStyle.color.toComposeColor()` called per command in hot loop

**Severity**: Medium (performance)
**Files**: `IsometricRenderer.kt` — 3 locations (cached path loop, native canvas loop, fallback renderPreparedScene)

```kotlin
for (i in paths.indices) {
    val cached = paths[i]
    is StrokeStyle.FillAndStroke -> {
        drawPath(cached.path, cached.fillColor, style = Fill)
        drawPath(cached.path, strokeStyle.color.toComposeColor(),  // per iteration!
                 style = Stroke(width = strokeStyle.width))
    }
}
```

`toComposeColor()` performs division and `toFloat()` conversions on every command. Since stroke style is constant for the entire frame, this should be hoisted outside the loop.

The old code had this right — `strokeColor` was cached in `CachedRenderCommand` (now `CachedPath`). WS2 removed `strokeColor` from `CachedPath` (correct — it's no longer per-command) but didn't hoist the replacement.

**Fix**: Compute once before the loop:
```kotlin
val strokeComposeColor = when (strokeStyle) {
    is StrokeStyle.FillOnly -> null
    is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
    is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
}
// ... then use strokeComposeColor in the loop
```

Same fix needed for `toAndroidColor()` in the native canvas loop.

---

### BUG W2-4 (Visual Regression): Default stroke color changed from 10% opacity to fully opaque

**Severity**: High (visual)
**Files**: `StrokeStyle.kt`, `IsometricRenderer.kt`

The old renderer hardcoded the stroke color as `Color.Black.copy(alpha = 0.1f)` — a subtle, barely-visible 10% opacity black outline that gave shapes definition without being visually heavy. The native canvas path used the equivalent `android.graphics.Color.argb(25, 0, 0, 0)`.

The new `StrokeStyle.FillAndStroke()` defaults to `color: IsoColor = IsoColor.BLACK`, which is `IsoColor(0, 0, 0, a=255)` — fully opaque black. Every existing scene that uses the default stroke style will now render with thick, fully opaque black outlines instead of the subtle 10% opacity edges.

This is not caught by snapshot tests because they all use the legacy `IsometricCanvas` API, not `IsometricScene`.

**Fix**: Change the default stroke color to match the old visual:
```kotlin
// In StrokeStyle.kt
data class FillAndStroke(
    val width: Float = 1f,
    val color: IsoColor = IsoColor(0, 0, 0, 25)  // ~10% opacity, matching old Color.Black.copy(alpha = 0.1f)
) : StrokeStyle()

data class Stroke(
    val width: Float = 1f,
    val color: IsoColor = IsoColor(0, 0, 0, 25)  // same default
) : StrokeStyle()
```

`0.1f * 255 = 25.5`, so `a = 25` is the closest integer match. Alternatively `IsoColor(0.0, 0.0, 0.0, 25.5)` for exact parity.

---

### BUG W2-5 (Pre-existing, surfaced by review): Sub-composition `CompositionLocalProvider` captures stale config values

**Severity**: Low (pre-existing)
**File**: `isometric-compose/.../IsometricScene.kt` lines 159-167

```kotlin
DisposableEffect(composition) {
    composition.setContent {
        CompositionLocalProvider(
            LocalDefaultColor provides config.defaultColor,       // captured by value
            LocalLightDirection provides config.lightDirection,   // captured by value
            LocalRenderOptions provides config.renderOptions,     // captured by value
            LocalStrokeStyle provides config.strokeStyle,         // captured by value
            LocalColorPalette provides config.colorPalette        // captured by value
        ) {
            IsometricScopeImpl.currentContent()
        }
    }
    onDispose { composition.dispose() }
}
```

The `DisposableEffect(composition)` fires once (keyed on `composition`, which is stable). The `config.*` values read inside are captured from the enclosing scope at that moment. On recomposition with a new `config` (e.g., changed `defaultColor`), the `DisposableEffect` does NOT re-fire because `composition` hasn't changed. The child sub-composition continues providing the stale values.

This is **pre-existing** — the same pattern existed before WS2 with bare parameters (`defaultColor`, `lightDirection`, etc.) instead of `config.*`. WS2 inherits the bug without changing it.

**Impact**: If a caller dynamically changes `SceneConfig(defaultColor = newColor)`, child composables reading `LocalDefaultColor.current` see the old color. In practice, most users use static configs, so this rarely manifests.

**Fix** (for a future WS): Either key the `DisposableEffect` on the config values, or wrap them in `rememberUpdatedState` and read the `.value` inside the `setContent` lambda:
```kotlin
val currentDefaultColor by rememberUpdatedState(config.defaultColor)
val currentLightDirection by rememberUpdatedState(config.lightDirection)
// ... etc.
DisposableEffect(composition) {
    composition.setContent {
        CompositionLocalProvider(
            LocalDefaultColor provides currentDefaultColor,
            // ...
        ) { ... }
    }
    onDispose { composition.dispose() }
}
```

---

### PERF W2-P2: `Stroke(width = ...)` object allocated per command in hot loop

**Severity**: Low-Medium (performance)
**File**: `IsometricRenderer.kt` — lines 195, 203, 523, 531

```kotlin
for (i in paths.indices) {
    drawPath(cached.path, ..., style = Stroke(width = strokeStyle.width))  // new Stroke() per iteration
}
```

`Stroke(width = ...)` constructs a new `Stroke` object on every draw call. Since the stroke width is constant for the entire frame (same `strokeStyle` throughout), a single `Stroke` instance should be created once and reused across the loop.

This compounds with W2-P1 (`toComposeColor()` per iteration). Both should be hoisted together:
```kotlin
val strokeComposeColor = strokeStyle.color.toComposeColor()
val strokeDrawStyle = Stroke(width = strokeStyle.width)
for (i in paths.indices) {
    drawPath(cached.path, strokeComposeColor, style = strokeDrawStyle)
}
```

Applies to both the cached-path and fallback `renderPreparedScene` code paths. The native canvas path is unaffected (it mutates `strokePaint` fields directly, not allocating `Stroke` objects).

---

### DEVIATION W2-D1: `StrokeStyle.FillOnly` is `data object`, not `object`

**Plan**: `object FillOnly : StrokeStyle()`
**Implementation**: `data object FillOnly : StrokeStyle()`

`data object` adds a compiler-generated `toString()` returning `"FillOnly"`. Minor improvement. No action needed.

---

### DEVIATION W2-D2: `AdvancedSceneConfig` is `@Immutable`, not `@Stable`

**Plan**: `@Stable`
**Implementation**: `@Immutable`

`@Immutable` is a stronger contract — it tells Compose the object will **never** change after construction. However, `AdvancedSceneConfig` holds `val engine: IsometricEngine`, and `IsometricEngine` has mutable internal state (`private var nextId = 0`). This technically violates the `@Immutable` contract. The mutation only happens during the render phase (not composition), so it's practically safe.

**Recommendation**: Change to `@Stable` to match the plan and avoid the contract violation.

---

### WS2 Correctness Matrix

| Area | Status | Notes |
|---|---|---|
| StrokeStyle sealed type | PASS | `FillOnly`, `Stroke`, `FillAndStroke` — correct variant design |
| StrokeStyle validation | PASS | `require(width > 0f)` on both stroke variants |
| StrokeStyle `when` exhaustiveness | PASS | All 3 render methods use exhaustive `when` |
| CachedPath removes `strokeColor` | PASS | Stroke color now from `StrokeStyle`, not per-command |
| Native canvas stroke color | **FAIL** | Uses `strokeStyle.color.toAndroidColor()` — but default `IsoColor.BLACK` is fully opaque, old was `argb(25,0,0,0)` (see W2-4) |
| Default stroke visual parity | **FAIL** | Old: 10% opacity black. New: 100% opacity black. Every default scene visually regresses (see W2-4) |
| `TapEvent` / `DragEvent` | PASS | Clean, evolvable event types |
| `GestureConfig.enabled` derivation | PASS | `false` when all callbacks null |
| `GestureConfig.Disabled` singleton | PASS | Proper companion object pattern |
| `SceneConfig` defaults | PASS | Match old `IsometricScene` defaults exactly |
| `AdvancedSceneConfig` defaults | PASS | `enablePathCaching = false` matches old default |
| `SceneConfig.equals()` uses `javaClass` | PASS | Prevents `SceneConfig == AdvancedSceneConfig` false positives |
| `AdvancedSceneConfig.equals()` excludes callbacks | PASS | Intentional — lambda identity unstable in Compose |
| `AdvancedSceneConfig.spatialIndexCellSize` validation | PASS | `require(isFinite() && > 0.0)` in `init {}` |
| `fillMaxSize()` removed | PASS | Import also deleted |
| Canvas init 0x0 | PASS | `mutableStateOf(0)` for both width/height |
| 0x0 render guard | PASS | `if (canvasWidth > 0 && canvasHeight > 0)` wraps render block |
| `frameVersion` read preserved | PASS | `config.frameVersion` still read in Canvas lambda |
| `CompositionLocalProvider` updated | PASS | `LocalStrokeStyle` replaces `LocalStrokeWidth` + `LocalDrawStroke` |
| `rememberUpdatedState` consolidated | PASS | 4 gesture states merged to single `currentGestures` |
| Gesture drag threshold | PASS | `currentGestures.dragThreshold` replaces hardcoded `8f` |
| Null-safe callback invocation | PASS | `?.invoke()` on all gesture callbacks |
| BenchmarkScreen migration | PASS | All params moved into `AdvancedSceneConfig` |
| RuntimeApiActivity migration | PASS | Uses `TapEvent`/`DragEvent`; `fillMaxSize()` added |
| OptimizedPerformanceSample migration | PASS | Both call sites use `AdvancedSceneConfig` |
| Native canvas test migration | PASS | `GestureConfig.Disabled`, `fillMaxSize()` added |
| Old simplified overload deleted | PASS | Replaced by `SceneConfig` overload |

### WS2 Minor Observations

1. **`BenchmarkScreen.kt` uses FQN** — `gestures = io.fabianterhorst.isometric.compose.runtime.GestureConfig.Disabled` — should use import. Cosmetic.
2. **Legacy `IsometricCanvas`/`ComposeRenderer` untouched** — Still has old `strokeWidth`/`drawStroke`. Expected (WS4 deletes them).
3. **`onHitTestReady`/`onFlagsReady` remain in `SideEffect`** — Correct. They need to fire on every recomposition to provide the latest hit-test function.
4. **`GestureConfig.dragThreshold` default** — `8f` matches old hardcoded value.
5. **`IsometricRendererNativeCanvasTest.kt`** — Uses FQN `androidx.compose.ui.Modifier.fillMaxSize()` instead of importing. Cosmetic.
6. **Snapshot tests don't cover `IsometricScene`** — All Paparazzi snapshot tests use the legacy `IsometricCanvas` API. The stroke color regression (W2-4) is invisible to the test suite. Consider adding at least one `IsometricScene` snapshot test.
7. **`AdvancedSceneConfig.spatialIndexCellSize` validated twice** — Both `AdvancedSceneConfig.init {}` and `IsometricRenderer.init {}` validate `spatialIndexCellSize > 0.0 && isFinite()`. Redundant but harmless (belt-and-suspenders).
8. **`SceneConfig.equals()` javaClass check is correct for the inheritance hierarchy** — Prevents `SceneConfig() == AdvancedSceneConfig()` even when base fields match. `AdvancedSceneConfig.equals()` delegates to `super.equals()` where `this.javaClass` correctly resolves to `AdvancedSceneConfig` at runtime.
9. **`StrokeStyle.Stroke` (stroke-only, no fill) is wireframe mode** — Renders all edges in the single stroke color, not per-face colors. This is the intended behavior per the plan. Useful for debug visualization.
10. **`GestureConfig.equals()` compares lambdas** — Lambda equality is reference-based. A `GestureConfig` constructed inline during composition will almost never equal itself across recompositions (new lambda instance each time). This is fine because `rememberUpdatedState` handles freshness, but it means `@Stable`'s "notify on change" contract is technically imprecise — Compose will see a "change" on every recomposition. The `pointerInput(Unit)` key prevents coroutine restart regardless.

---

## Action Items

| ID | Severity | Workstream | Description |
|---|---|---|---|
| W1-1 | Low | WS1 | Verify newly-enabled `stable hitTest` test passes in isolation |
| W1-2 | Low | WS1 | Move Circle validation into companion `createCirclePoints` factory |
| W2-1 | **High** | WS2 | Fix `remember { config.engine }` to key on `config.engine` |
| W2-4 | **High** | WS2 | Fix default stroke color from `IsoColor.BLACK` (opaque) to `IsoColor(0,0,0,25)` (~10% opacity) to match old visual |
| W2-3 | Medium | WS2 | Add `Modifier.fillMaxSize()` to `PrimitiveLevelsExample.kt` call sites |
| W2-P1 | Medium | WS2 | Hoist `toComposeColor()`/`toAndroidColor()` out of per-command render loops |
| W2-P2 | Medium | WS2 | Hoist `Stroke(width = ...)` allocation out of per-command render loops (combine with W2-P1) |
| W2-2 | Low | WS2 | Document or fix `DisposableEffect` callback re-fire behavior |
| W2-5 | Low | WS2 | Sub-composition stale config capture (pre-existing — fix in future WS) |
| W2-D2 | Low | WS2 | Change `AdvancedSceneConfig` from `@Immutable` to `@Stable` |

---

## Cross-Boundary Analysis: WS1 ↔ WS2

WS1 (committed) changed core types in `isometric-core` and `isometric-compose`. WS2 (uncommitted) redesigned the entry point and configuration layer in `isometric-compose/runtime`. The following analysis verifies that the two workstreams interact correctly at their boundaries.

### Boundary Check Results

| # | Question | Result | Details |
|---|----------|--------|---------|
| 1 | Does WS2's `RenderContext` use WS1's renamed `Vector` fields (`x/y/z`)? | **PASS** | `RenderContext` constructs `Vector` via `Vector(2.0, -1.0, 3.0)` positional args — naming doesn't matter. `lightDirection` is typed as `Vector` and flows correctly through `SceneConfig` → `AdvancedSceneConfig` → `RenderContext`. |
| 2 | Does WS2's `SceneConfig` use WS1's `RenderOptions` correctly? | **PASS** | `SceneConfig.renderOptions` defaults to `RenderOptions.Quality` (WS1 companion constant). `AdvancedSceneConfig` passes it through to `RenderContext`. The `RenderOptions` class itself (converted from `data class` in WS1) is consumed by value — no structural dependency on data class features. |
| 3 | Does WS2's `StrokeStyle` interact with WS1's `IsoColor` correctly? | **PASS** | `StrokeStyle.Stroke` and `StrokeStyle.FillAndStroke` hold `color: IsoColor`. `IsoColor` kept its `data class` status in WS1 (intentional per plan). The `toComposeColor()` and `toAndroidColor()` extension functions (extracted to top-level in WS1) work correctly on the `IsoColor` instances stored in `StrokeStyle`. |
| 4 | Does WS2's renderer use WS1's `HitOrder` enum? | **PASS** | `IsometricRenderer` calls `engine.findItemAt(...)` with `hitOrder` parameter. WS1 changed this from `reverseSort: Boolean` to `hitOrder: HitOrder`. The renderer passes `HitOrder.FRONT_TO_BACK` (hardcoded), which is correct for UI hit testing (top-most shape first). |
| 5 | Does WS2's `IsometricScene` work with WS1's `Path.depth` precalculation? | **PASS** | `Path.depth` changed from a computed property to a precalculated `val` in WS1. The renderer's `prepareFaces()` sorts by depth — this is transparent to the renderer since it just reads `path.depth`. No code in WS2 constructs `Path` objects directly. |
| 6 | Does WS2's `Shape()` composable work with WS1's covariant `translate()`? | **PASS** | `Shape()` composable calls `shape.translate(position)`. WS1 made `Shape.translate()` open and added covariant overrides on `Prism`, `Pyramid`, etc. The composable is typed as `Shape` — covariant return types mean `Prism.translate()` returns `Prism` but is stored as `Shape`, which is correct. |
| 7 | Does WS2's `GestureConfig` pass `Point` correctly to hit testing? | **PASS** | `GestureConfig.onTap` receives `TapEvent(x, y, node)` where `x`/`y` are screen coordinates (doubles). These are passed to `renderer.hitTest()` which uses `IsometricEngine.findItemAt()`. WS1's `Point` operators aren't involved in the hit-test coordinate path — the engine works with raw doubles internally. |
| 8 | Does WS2's config hierarchy respect WS1's `@Immutable`/`@Stable` contracts? | **PASS** | `SceneConfig` and `AdvancedSceneConfig` are both `@Immutable`. They hold WS1 types: `RenderOptions` (regular class, immutable by design), `Vector` (regular class, immutable), `IsoColor` (data class, immutable), `ColorPalette` (regular class, immutable). All WS1 types satisfy the immutability contract. The one exception is `IsometricEngine` (mutable internal counter) — noted in W2-D2 as reason to prefer `@Stable`. |
| 9 | Does WS2's `CompositionLocalProvider` supply WS1's converted types correctly? | **PASS** | `LocalRenderOptions`, `LocalLightDirection`, `LocalDefaultColor`, `LocalColorPalette` all provide WS1 types. Since WS1 removed `data class` from these types and added explicit `equals()`, the `CompositionLocal` change-detection still works — `ProvidableCompositionLocal` uses `equals()` to determine if the value changed, and all converted types implement `equals()` correctly. |
| 10 | Do WS2's test files compile against WS1's API changes? | **PASS** | `IsometricRendererNativeCanvasTest` uses `RenderOptions.Quality`, `Vector(2.0, -1.0, 3.0).normalize()`, `Prism(Point.ORIGIN, 1.0, 1.0, 1.0)`, `IsoColor.BLUE` — all WS1 APIs. The test compiles and the build passes. |

### Cross-Boundary Observation: `data class` Usage Divergence

WS1 deliberately moved core types **away** from `data class` (Point, Vector, RenderOptions, RenderContext, RenderCommand, PreparedScene, ColorPalette) to prevent destructuring/copy abuse and maintain encapsulation. `IsoColor` was explicitly kept as `data class` because its HSL properties are lazy body-declared fields excluded from data class equality.

WS2 introduces **new** types that use `data class`:
- `TapEvent` — small event DTO, immutable, all fields in primary constructor
- `DragEvent` — small event DTO, immutable, all fields in primary constructor
- `StrokeStyle.Stroke` — small config value, immutable, 2 fields
- `StrokeStyle.FillAndStroke` — small config value, immutable, 2 fields

This is **intentionally correct**, not a contradiction. WS1's rationale for removing `data class` was:
1. Core types had accumulated body-declared properties that `data class` equality would ignore
2. Core types were subclassed (Shape → Prism), and `data class` inheritance is problematic
3. `copy()` on core types could create invalid states

None of these concerns apply to WS2's event/config types — they are small, flat, final DTOs with no body-declared state and no inheritance. `data class` is the right choice for them.

### Boundary Verdict

**No breaking cross-boundary issues found.** WS1 and WS2 changes are cleanly separated — WS1 modified the types that WS2 consumes, but all consumption sites use the public API surface correctly. The type system enforces correctness at the boundary: WS2's config objects hold WS1 types by their class type, not by data class features.
