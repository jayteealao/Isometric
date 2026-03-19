# WS7: Compose Runtime Correctness — Detailed Implementation Plan

> **Workstream**: 7 of 8
> **Phase**: 3 (after WS2)
> **Scope**: CompositionLocal types, effect lifecycle, Java interop annotations
> **Findings**: F63, F64, F70
> **Depends on**: WS2 (F64 edits `IsometricScene.kt` restructured by WS2)
> **Coordinate with**: WS1b (new `IsoColor` constants need `@JvmField`; `@JvmOverloads` survives param renames), WS3 (final public names like `IsoColor` and `NoCulling`)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.9, §3.5, §3.10

---

## Execution Order

The 3 findings decompose into 3 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: `compositionLocalOf` → `staticCompositionLocalOf` (F63)
2. **Step 2**: `SideEffect` → `DisposableEffect` with cleanup (F64)
3. **Step 3**: Java interop annotations — `@JvmOverloads`, `@JvmField` (F70)

Steps 1–2 are independent of each other but both touch the compose module. Step 3 is independent of all other steps.

> **F37 moved to WS1b** — it's an API naming/design change (`reverseSort`/`useRadius` → `HitOrder` enum + `touchRadius: Double`), not a Compose runtime correctness issue. It touches `IsometricEngine.findItemAt()` in the core module.

---

## Step 1: `compositionLocalOf` → `staticCompositionLocalOf` (F63)

### Rationale

`compositionLocalOf` installs per-subscriber recomposition tracking: every composable that reads the local is individually tracked and recomposed when the value changes. This is correct for frequently-changing values (e.g., scroll offset, animation progress).

The 6 `compositionLocalOf` declarations in `CompositionLocals.kt` provide color palette, render options, light direction, stroke config, and default color — values that are set once at the `IsometricScene` boundary and almost never change during a session. The per-subscriber tracking is pure overhead: it allocates tracking state for every reader and runs diffing logic on every recomposition pass, even though the values are stable.

`staticCompositionLocalOf` skips per-subscriber tracking entirely. When the value changes, it invalidates the entire subtree — which is the correct behavior for rarely-changing configuration. This matches how Compose's own `LocalContext`, `LocalConfiguration`, and `LocalDensity` are declared in the framework.

### Best Practice

The Compose framework itself uses `staticCompositionLocalOf` for all configuration-level locals. The rule: if a value changes less than once per user interaction, use `staticCompositionLocalOf`. Only use `compositionLocalOf` for values that change on every frame or every gesture event.

### Files and Changes

**Coordination note with WS2**: if WS2 lands first, `LocalStrokeWidth` and `LocalDrawStroke` no longer exist. In that case, apply F63 to `LocalStrokeStyle` instead. The invariant is simple: every scene-level `CompositionLocal` in `CompositionLocals.kt` should become `staticCompositionLocalOf`.

#### 1a. `CompositionLocals.kt` — 6 declarations

**Current** (lines 13, 20, 27, 34, 41, 60):
```kotlin
val LocalDefaultColor = compositionLocalOf {
    IsoColor(33.0, 150.0, 243.0)
}

val LocalLightDirection = compositionLocalOf {
    IsometricEngine.DEFAULT_LIGHT_DIRECTION.normalize()
}

val LocalRenderOptions = compositionLocalOf {
    RenderOptions.Default
}

val LocalStrokeWidth = compositionLocalOf {
    1f
}

val LocalDrawStroke = compositionLocalOf {
    true
}

val LocalColorPalette = compositionLocalOf {
    ColorPalette()
}
```

**After**:
```kotlin
val LocalDefaultColor = staticCompositionLocalOf {
    IsoColor(33.0, 150.0, 243.0)
}

val LocalLightDirection = staticCompositionLocalOf {
    IsometricEngine.DEFAULT_LIGHT_DIRECTION.normalize()
}

val LocalRenderOptions = staticCompositionLocalOf {
    RenderOptions.Default
}

val LocalStrokeWidth = staticCompositionLocalOf {
    1f
}

val LocalDrawStroke = staticCompositionLocalOf {
    true
}

val LocalColorPalette = staticCompositionLocalOf {
    ColorPalette()
}
```

**`LocalBenchmarkHooks`** (line 71): Already `staticCompositionLocalOf` — no change needed.

**Import cleanup**: Remove the `compositionLocalOf` import (line 4) since no declarations use it after this step.

### Verification

- All existing tests pass unchanged — the behavioral contract is identical when values do not change mid-composition (which they do not in any test or sample).
- Manually verify that changing a `CompositionLocalProvider` value at the `IsometricScene` boundary still triggers recomposition of the entire subtree. This is guaranteed by the `staticCompositionLocalOf` contract.

---

## Step 2: `SideEffect` → `DisposableEffect` with Cleanup (F64)

### Rationale

`SideEffect` runs after every successful recomposition but provides no cleanup mechanism. When used for listener wiring (assigning callbacks, registering observers), it creates a resource leak: if the callback lambda changes identity between recompositions, the old callback is never unregistered — it just gets overwritten. More critically, if the composable leaves the tree, `SideEffect` provides no `onDispose` to tear down the wiring.

`DisposableEffect` provides `onDispose {}` for cleanup and accepts keys that control when the effect re-executes. For listener wiring, the correct pattern is `DisposableEffect(key) { ... onDispose { ... } }` where the key is the value being wired.

### Best Practice

The Compose documentation explicitly states: "Use `SideEffect` to publish Compose state to non-Compose code" (read-only bridging). For any operation that needs cleanup — listener registration, callback wiring, resource allocation — use `DisposableEffect`.

### Files and Changes

#### 2a. `IsometricScene.kt` — 4 `SideEffect` blocks → 2 `DisposableEffect` blocks

The 4 `SideEffect` blocks (lines 117, 125, 145, 158) serve related purposes and can be logically grouped into fewer effects.

**Block 1** (line 117) — Dirty notification wiring:
```kotlin
// Current
SideEffect {
    rootNode.onDirty = { sceneVersion++ }
}
```

This assigns a callback to `rootNode.onDirty`. If the composable leaves the tree, the callback retains a reference to the `sceneVersion` mutable state, preventing garbage collection. The callback should be cleared on dispose.

**Block 2** (line 125) — Benchmark hooks and forceRebuild:
```kotlin
// Current
SideEffect {
    renderer.benchmarkHooks = currentBenchmarkHooks
    renderer.forceRebuild = forceRebuild
}
```

Bridges CompositionLocal-read values into the imperative renderer. No cleanup needed for these assignments (they are plain property sets), but grouping them with block 1 is cleaner.

**Block 3** (line 145) — Hit-test function callback:
```kotlin
// Current
SideEffect {
    onHitTestReady?.invoke { x, y ->
        renderer.hitTest(
            rootNode = rootNode,
            x = x, y = y,
            context = renderContext,
            width = canvasWidth,
            height = canvasHeight
        )
    }
}
```

Provides a hit-test function to the caller. The lambda captures `renderer`, `rootNode`, `renderContext`, `canvasWidth`, `canvasHeight`. If these change, the old lambda (held by the caller) becomes stale. Should re-fire when dependencies change, and the caller should be notified when the function becomes invalid.

**Block 4** (line 158) — Runtime flag snapshot:
```kotlin
// Current
SideEffect {
    onFlagsReady?.invoke(
        RuntimeFlagSnapshot(
            enablePathCaching = enablePathCaching,
            enableSpatialIndex = enableSpatialIndex,
            enableBroadPhaseSort = renderOptions.enableBroadPhaseSort,
            forceRebuild = renderer.forceRebuild,
            useNativeCanvas = useNativeCanvas,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )
    )
}
```

Reports runtime flags to benchmarks. Pure notification — no cleanup needed, but should be grouped.

**After** — Merge into 2 `DisposableEffect` blocks:

```kotlin
// Effect 1: Wire dirty notification and renderer config.
// Keyed on rootNode and renderer — if either is recreated, re-wire.
DisposableEffect(rootNode, renderer) {
    rootNode.onDirty = { sceneVersion++ }
    renderer.benchmarkHooks = currentBenchmarkHooks
    renderer.forceRebuild = forceRebuild

    onDispose {
        rootNode.onDirty = null
        renderer.benchmarkHooks = null
    }
}

// Effect 2: Publish hit-test function and runtime flags to callers.
// Keyed on the callback values — re-publish when they change.
DisposableEffect(onHitTestReady, onFlagsReady, renderContext, canvasWidth, canvasHeight) {
    onHitTestReady?.invoke { x, y ->
        renderer.hitTest(
            rootNode = rootNode,
            x = x, y = y,
            context = renderContext,
            width = canvasWidth,
            height = canvasHeight
        )
    }
    onFlagsReady?.invoke(
        RuntimeFlagSnapshot(
            enablePathCaching = enablePathCaching,
            enableSpatialIndex = enableSpatialIndex,
            enableBroadPhaseSort = renderOptions.enableBroadPhaseSort,
            forceRebuild = renderer.forceRebuild,
            useNativeCanvas = useNativeCanvas,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight
        )
    )

    onDispose {
        // Notify callers that the hit-test function is no longer valid
        onHitTestReady?.invoke(null)
    }
}
```

**Design decisions**:
- Block 2's `benchmarkHooks`/`forceRebuild` assignments move into Effect 1 because they depend on `renderer`. When `renderer` is recreated (due to `enablePathCaching`/`enableSpatialIndex` changes), the assignments must re-run. However, `currentBenchmarkHooks` and `forceRebuild` can also change independently of `renderer`. To handle this, add them as keys: `DisposableEffect(rootNode, renderer, currentBenchmarkHooks, forceRebuild)`.
- Block 3's hit-test lambda should be nullified on dispose so callers do not hold a stale reference.
- Block 4 is pure notification (fire-and-forget) — no cleanup needed, but grouping with block 3 reduces effect count.

**Refined Effect 1**:
```kotlin
DisposableEffect(rootNode, renderer, currentBenchmarkHooks, forceRebuild) {
    rootNode.onDirty = { sceneVersion++ }
    renderer.benchmarkHooks = currentBenchmarkHooks
    renderer.forceRebuild = forceRebuild

    onDispose {
        rootNode.onDirty = null
        renderer.benchmarkHooks = null
    }
}
```

**Import cleanup**: Remove the `SideEffect` import (line 9) since no code uses it after this step.

### Verification

- Existing tests pass — the effects fire at the same lifecycle points (after successful composition).
- Verify that disposing the `IsometricScene` composable clears `rootNode.onDirty` and `renderer.benchmarkHooks` (add a test that enters and exits the composition and asserts null).
- Verify that changing `currentBenchmarkHooks` triggers effect re-execution (the old hooks are cleared, new ones assigned).

---

## Step 3: Java Interop Annotations (F70)

### Rationale

Kotlin constructors with default parameters compile to a single constructor with all parameters plus a synthetic overload with a bitmask. Java callers cannot use default parameters — they must pass every argument. `@JvmOverloads` generates bytecode overloads that Java callers can use naturally: `new Prism(origin)`, `new Prism(origin, 2.0)`, etc.

Similarly, Kotlin `val` properties in companion objects compile to a getter method (`RenderOptions.Companion.getDefault()`). `@JvmField` exposes them as static fields (`RenderOptions.Default`), which is the idiomatic Java access pattern for constants.

### Best Practice

The Kotlin documentation recommends `@JvmOverloads` on all public constructors with default parameters when Java interop is expected. `@JvmField` is recommended for all `val` constants in companion objects that serve as named instances (singletons, presets, sentinels).

### Files and Changes

#### 3a. Constructors — `@JvmOverloads` (8 classes)

Add `@JvmOverloads constructor` to each class. The annotation goes before the `constructor` keyword.

**`Point.kt`** — 3 defaulted params (`x`, `y`, `z`):
```kotlin
// Before
class Point(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)

// After
class Point @JvmOverloads constructor(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0)
```

Generates: `Point()`, `Point(x)`, `Point(x, y)`, `Point(x, y, z)`.

**`Vector.kt`** — no defaults currently, but WS1 may add defaults. Add `@JvmOverloads` preemptively only if WS1 adds defaults in the same phase. If Vector keeps no defaults, skip — `@JvmOverloads` with zero defaults is a no-op.

**`Prism.kt`** — 3 defaulted dimension params (after WS1 renames: `width`, `depth`, `height`):
```kotlin
// After (using WS1-renamed params if WS1 lands first; annotation survives either way)
class Prism @JvmOverloads constructor(
    position: Point = Point.ORIGIN,
    width: Double = 1.0,
    depth: Double = 1.0,
    height: Double = 1.0
)
```

**`Pyramid.kt`** — same pattern as Prism.

**`Cylinder.kt`** — 3 defaulted params (`radius`, `height`, `vertices` after WS1 reorder):
```kotlin
class Cylinder @JvmOverloads constructor(
    position: Point = Point.ORIGIN,
    radius: Double = 1.0,
    height: Double = 1.0,
    vertices: Int = 20
)
```

**`Stairs.kt`** — `stepCount` has no default. If WS1 adds `position: Point = Point.ORIGIN`, the annotation generates `Stairs()` (position-only default) and `Stairs(position, stepCount)`.

**`RenderOptions.kt`** — 5 defaulted params:
```kotlin
// Before
data class RenderOptions(
    val enableDepthSorting: Boolean = true,
    ...
)

// After
data class RenderOptions @JvmOverloads constructor(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val enableBroadPhaseSort: Boolean = false,
    val broadPhaseCellSize: Double = DEFAULT_BROAD_PHASE_CELL_SIZE
)
```

**`IsometricEngine.kt`** — 5 defaulted params:
```kotlin
// Before
class IsometricEngine(
    private val angle: Double = PI / 6,
    ...
)

// After
class IsometricEngine @JvmOverloads constructor(
    private val angle: Double = PI / 6,
    private val scale: Double = 70.0,
    private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
)
```

#### 3b. Companion constants — `@JvmField` (8 constants)

**`IsoColor.kt`** — 5 color constants:
```kotlin
companion object {
    @JvmField val WHITE = IsoColor(255.0, 255.0, 255.0)
    @JvmField val BLACK = IsoColor(0.0, 0.0, 0.0)
    @JvmField val RED = IsoColor(255.0, 0.0, 0.0)
    @JvmField val GREEN = IsoColor(0.0, 255.0, 0.0)
    @JvmField val BLUE = IsoColor(0.0, 0.0, 255.0)
}
```

Java access changes from `IsoColor.Companion.getWHITE()` → `IsoColor.WHITE`.

**Note**: WS1 adds `GRAY`, `DARK_GRAY`, `LIGHT_GRAY`, `CYAN`, `ORANGE`, `PURPLE`, `YELLOW`, `BROWN` to the companion. Those should also get `@JvmField`. If WS1 lands first, add `@JvmField` to the new constants in this step. If WS7 lands first, note in WS1 plan that new constants need `@JvmField`.

**`Point.kt`** — `ORIGIN` already has `@JvmField` (line 16). No change needed.

**`RenderOptions.kt`** — 3 preset constants:
```kotlin
companion object {
    const val DEFAULT_BROAD_PHASE_CELL_SIZE: Double = 100.0

    @JvmField val Default = RenderOptions()
    @JvmField val Performance = RenderOptions(
        enableDepthSorting = false,
        enableBackfaceCulling = true,
        enableBoundsChecking = true,
        enableBroadPhaseSort = false
    )
    @JvmField val NoCulling = RenderOptions(
        enableDepthSorting = true,
        enableBackfaceCulling = false,
        enableBoundsChecking = false,
        enableBroadPhaseSort = false
    )
}
```

`DEFAULT_BROAD_PHASE_CELL_SIZE` is already `const val` — no `@JvmField` needed (`const` implies it).

#### 3c. `fun interface` consideration

Single-method callback interfaces (e.g., `OnItemClickListener`) could be annotated with `fun interface` to enable SAM conversion for both Kotlin and Java callers. However, the current codebase uses lambda types directly (`(Double, Double) -> Unit`) rather than named interfaces. This is a WS2 concern (gesture callback redesign) — not in scope for WS7.

### Verification

- All existing tests pass — annotations are binary-compatible additions.
- Add a Java test file that constructs `Point()`, `Prism(Point.ORIGIN)`, `IsometricEngine()` using the generated overloads.
- Add a Java test file that accesses `IsoColor.WHITE`, `RenderOptions.Default`, `RenderOptions.Performance`, `RenderOptions.NoCulling` as static fields.

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1 (CompositionLocal types) | `staticCompositionLocalOf` declarations | Independent — no other WS touches CompositionLocals |
| Step 2 (DisposableEffect) | Cleaned-up effects in `IsometricScene.kt` | WS2 F19 (IsometricScene restructuring) — WS2 modifies the same file. If WS2 lands first, the `SideEffect` blocks may have moved; apply F64 to wherever they land. If WS7 lands first, WS2 must preserve the `DisposableEffect` pattern. |
| Step 3 (`@JvmOverloads`) | Overloaded constructors | WS1 F26/F27/F28 (param renames) — `@JvmOverloads` generates overloads based on current param names. When WS1 renames params, the overloads regenerate with new names. Order does not matter. |
| Step 3 (`@JvmField`) | Static field access for `IsoColor` constants | WS1b F7 (new `IsoColor` constants) — new constants added by WS1b should also get `@JvmField`. Whichever WS lands second should add the annotation to the other's constants. |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `CompositionLocals.kt` | 1 | 6x `compositionLocalOf` → `staticCompositionLocalOf`, remove unused import |
| `IsometricScene.kt` | 2 | 4x `SideEffect` → 2x `DisposableEffect` with `onDispose`, remove unused import |
| `IsometricEngine.kt` | 3 | Add `@JvmOverloads` to constructor |
| `Point.kt` | 3 | Add `@JvmOverloads` to constructor (already has `@JvmField` on ORIGIN) |
| `Prism.kt` | 3 | Add `@JvmOverloads` to constructor |
| `Pyramid.kt` | 3 | Add `@JvmOverloads` to constructor |
| `Cylinder.kt` | 3 | Add `@JvmOverloads` to constructor |
| `Stairs.kt` | 3 | Add `@JvmOverloads` to constructor |
| `RenderOptions.kt` | 3 | Add `@JvmOverloads` to constructor; add `@JvmField` to `Default`, `Performance`, `NoCulling` |
| `IsoColor.kt` | 3 | Add `@JvmField` to `WHITE`, `BLACK`, `RED`, `GREEN`, `BLUE` |
