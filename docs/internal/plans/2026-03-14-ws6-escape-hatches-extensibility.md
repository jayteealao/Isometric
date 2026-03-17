# WS6: Escape Hatches & Extensibility — Detailed Implementation Plan

> **Workstream**: 6 of 8
> **Phase**: 4 (after WS5 engine decomposition)
> **Scope**: User-facing extension points, projection API, mutable engine params, render hooks, custom nodes
> **Findings**: F3/F58, F56, F57, F59, F60, F61, F62, F4, F19, F76, F78
> **Depends on**: WS5 (engine decomposition exposes internal seams)
> **Coordinate with**: WS1 (open `Shape` base + translate overrides), WS4 (internal children), WS7 (staticCompositionLocalOf), WS2 (SceneConfig/AdvancedSceneConfig)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.10

---

## Execution Order

The 11 findings decompose into 10 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: `Point2D` arithmetic operations (F62)
2. **Step 2**: `RenderBenchmarkHooks` default implementations (F78)
3. **Step 3**: Depth formula parameterization (F76)
4. **Step 4**: Public projection API — `worldToScreen()`/`screenToWorld()` (F56)
5. **Step 5**: Mutable engine `angle`/`scale` with validation (F60)
6. **Step 6**: `LocalIsometricEngine` CompositionLocal (F61)
7. **Step 7**: `CameraState` for programmatic viewport control (F19)
8. **Step 8**: Render hooks — `onBeforeDraw`/`onAfterDraw`/`onPreparedSceneReady` (F57, F59)
9. **Step 9**: Per-subtree `renderOptions` override (F4)
10. **Step 10**: `CustomNode` composable for user-defined nodes (F3/F58)

Steps 1–2 are independent and parallelizable. Step 3 must precede step 4 (projection uses depth formula). Steps 4–6 are sequential (projection feeds mutable params feeds CompositionLocal). Step 7 depends on steps 5–6. Steps 8–10 are sequential (render hooks feed per-subtree options feed custom nodes).

**Surface baseline**: WS6 builds on the post-WS2/WS3/WS5 API. That means config additions should extend `SceneConfig` / `AdvancedSceneConfig`, renderer traversal should use `renderTo(...)`, and examples should use settled names like `IsoColor`, `projectScene()`, and `commandId`.

---

## Step 1: `Point2D` Arithmetic Operations (F62)

### Rationale

`Point2D` is a bare `data class Point2D(val x: Double, val y: Double)` with zero operations. Users who receive projected screen coordinates (from `worldToScreen()` in step 4, or from `PreparedScene` render commands) cannot perform basic 2D math without manually extracting fields. This is the most fundamental escape hatch — enabling computation on projected coordinates.

### Best Practice

Kotlin operator overloads (`plus`, `minus`, `times`) provide idiomatic arithmetic. `distanceTo()` is the most common 2D geometric operation. Keeping `Point2D` as a `data class` is safe because it has exactly two fixed fields (`x`, `y`) that will never grow — it represents a mathematical point in 2D space.

### Files and Changes

#### 1a. `Point2D.kt` — Add operators and `distanceTo()`

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/Point2D.kt`

**Current** (line 6–9):
```kotlin
data class Point2D(
    val x: Double,
    val y: Double
)
```

**After**:
```kotlin
data class Point2D(
    val x: Double,
    val y: Double
) {
    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Double): Point2D = Point2D(x * scalar, y * scalar)
    operator fun div(scalar: Double): Point2D = Point2D(x / scalar, y / scalar)
    operator fun unaryMinus(): Point2D = Point2D(-x, -y)

    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun distanceToSquared(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }

    companion object {
        val ZERO = Point2D(0.0, 0.0)
    }
}
```

**Design notes**:
- `div` included alongside `times` for symmetry — both are common in screen-space math (e.g., midpoint computation: `(a + b) / 2.0`).
- `distanceToSquared()` avoids `sqrt()` for comparison-based operations (e.g., "is point within radius?").
- `ZERO` constant mirrors `Point.ORIGIN` for screen space.

### Verification

- Compile check: `Point2D` is used in `IsometricEngine.translatePoint()` (line 208), `RenderCommand.points`, `cullPath()`, `buildConvexHull()`, `itemInDrawingBounds()`. All existing usage is field access only — no call sites break.
- Add unit tests for each operator: `Point2D(1.0, 2.0) + Point2D(3.0, 4.0) == Point2D(4.0, 6.0)`, etc.

---

## Step 2: `RenderBenchmarkHooks` Default Implementations (F78)

### Rationale

`RenderBenchmarkHooks` has 6 abstract methods with no defaults. Adding a seventh method (e.g., `onSpatialIndexBuild()`) breaks every implementor at compile time. There is only one implementation (`BenchmarkHooksImpl`), but the interface is public — external benchmark integrations would also break.

### Best Practice

Kotlin interfaces support default method implementations. Adding `{}` (empty body) to each method makes them no-op by default. Existing implementations that override all six continue to work unchanged. New methods can be added freely.

### Files and Changes

#### 2a. `IsometricRenderer.kt` — Add default bodies to `RenderBenchmarkHooks`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Current** (lines 27–34):
```kotlin
interface RenderBenchmarkHooks {
    fun onPrepareStart()
    fun onPrepareEnd()
    fun onDrawStart()
    fun onDrawEnd()
    fun onCacheHit()
    fun onCacheMiss()
}
```

**After**:
```kotlin
interface RenderBenchmarkHooks {
    fun onPrepareStart() {}
    fun onPrepareEnd() {}
    fun onDrawStart() {}
    fun onDrawEnd() {}
    fun onCacheHit() {}
    fun onCacheMiss() {}
}
```

#### 2b. `BenchmarkHooksImpl.kt` — No changes needed

**File**: `isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkHooksImpl.kt`

`BenchmarkHooksImpl` already overrides all 6 methods explicitly. Adding defaults to the interface does not affect it — the `override` keyword continues to work. No changes needed.

### Verification

- Compile check: `BenchmarkHooksImpl` (the only implementor) overrides all 6 methods. Adding defaults is a source- and binary-compatible change.
- Existing tests in `IsometricRendererTest.kt` that reference `RenderBenchmarkHooks` continue to pass unchanged.
- Verify that a new implementation with `object : RenderBenchmarkHooks {}` compiles (zero overrides required).

---

## Step 3: Depth Formula Parameterization (F76)

### Rationale

`Point.depth()` returns `x + y - 2 * z`. This is a simplification of the isometric depth formula that assumes `cos(30°) ≈ sin(30°) ≈ 0.5`, which is only valid when the engine angle is `PI / 6` (30 degrees). For any other engine angle, depth ordering produces incorrect results — shapes overlap or render in wrong order.

`Path.depth()` aggregates `Point.depth()`, so the same bug propagates.

### Best Practice

Parameterize the depth computation on the engine angle. Since `Point` and `Path` are core types that should remain stateless (they don't hold a reference to the engine), the parameterized depth should be an overload that accepts the angle, while the existing `depth()` remains as the default-angle shorthand.

### Files and Changes

#### 3a. `Point.kt` — Add parameterized `depth(angle: Double)`

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/Point.kt`

**Current** (lines 127–129):
```kotlin
fun depth(): Double {
    return x + y - 2 * z
}
```

**After**:
```kotlin
/**
 * The depth of a point in the isometric plane using the default 30° angle.
 * Equivalent to depth(PI / 6).
 */
fun depth(): Double {
    return x + y - 2 * z
}

/**
 * The depth of a point in the isometric plane for an arbitrary engine angle.
 * The formula weights x/y by cos/sin of the projection angle and z by a
 * factor that preserves correct depth ordering for the given viewing angle.
 *
 * @param angle The isometric projection angle in radians (e.g., PI / 6 for 30°)
 */
fun depth(angle: Double): Double {
    val cosA = kotlin.math.cos(angle)
    val sinA = kotlin.math.sin(angle)
    return x * cosA + y * sinA - 2 * z
}
```

**Design note**: The existing `depth()` is preserved for backward compatibility and performance (avoids trig calls when the default angle is used). The parameterized version is used by the engine when `angle != PI / 6`.

#### 3b. `Path.kt` — Add parameterized `depth(angle: Double)`

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/Path.kt`

**Current** (lines 64–67):
```kotlin
fun depth(): Double {
    if (points.isEmpty()) return 0.0
    return points.sumOf { it.depth() } / points.size
}
```

**After**:
```kotlin
/**
 * Average depth using the default 30° angle.
 */
fun depth(): Double {
    if (points.isEmpty()) return 0.0
    return points.sumOf { it.depth() } / points.size
}

/**
 * Average depth for an arbitrary engine angle.
 *
 * @param angle The isometric projection angle in radians
 */
fun depth(angle: Double): Double {
    if (points.isEmpty()) return 0.0
    return points.sumOf { it.depth(angle) } / points.size
}
```

#### 3c. `IsometricEngine.kt` — Use parameterized depth in `sortPaths()`

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

The `sortPaths()` method (line 291) does not call `depth()` directly — it uses `closerThan()` which computes plane-based depth ordering via cross/dot products. However, the `observer` point at line 293 (`Point(-10.0, -10.0, 20.0)`) and the depth heuristic in `closerThan()` both assume the default angle.

**After WS5**: When WS5 decomposes the engine, the depth sorter will have access to the engine angle. At that point, `closerThan()` should accept the angle parameter. For now, store the angle as a field and pass it to the new `depth(angle)` overloads in any future depth-sorting code that uses direct depth comparison.

No immediate change needed in `sortPaths()` since it uses plane intersection, not `depth()`. The parameterized `depth(angle)` is consumed by callers who compare individual path depths (e.g., broad-phase bucket ordering, external scene inspection).

### Verification

- `depth()` (no-arg) behavior unchanged — existing tests pass.
- New test: `Point(1.0, 1.0, 0.0).depth(PI / 6)` should equal `Point(1.0, 1.0, 0.0).depth()` (same angle = same result).
- New test: `Point(1.0, 0.0, 0.0).depth(PI / 4)` should return `cos(PI/4)` ≈ `0.707`, not `1.0`.

---

## Step 4: Public Projection API (F56)

### Rationale

`IsometricEngine.translatePoint()` is `private` (line 207). Users have no way to convert 3D world coordinates to 2D screen coordinates, or vice versa. This blocks: custom overlays, label positioning, coordinate display, debug visualization, hit-test verification, and any non-standard rendering that needs to know where a 3D point appears on screen.

### Best Practice

Expose two public methods: `worldToScreen()` (3D → 2D) and `screenToWorld()` (2D → 3D). The inverse projection is not unique (a 2D screen point maps to a line in 3D space), so `screenToWorld()` returns the point on a specified plane (defaulting to `z = 0`).

### Files and Changes

#### 4a. `IsometricEngine.kt` — Add `worldToScreen()` and `screenToWorld()`

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

**Current** (lines 207–212): `translatePoint()` is private, hardcodes `originX`/`originY` from viewport dimensions.

**After**: Add two public methods that accept viewport dimensions (needed for origin computation):

```kotlin
/**
 * Project a 3D world point to 2D screen coordinates.
 *
 * @param point The 3D world point
 * @param viewportWidth The viewport width in pixels
 * @param viewportHeight The viewport height in pixels
 * @return The 2D screen position
 */
fun worldToScreen(point: Point, viewportWidth: Int, viewportHeight: Int): Point2D {
    val originX = viewportWidth / 2.0
    val originY = viewportHeight * 0.9
    return translatePoint(point, originX, originY)
}

/**
 * Unproject a 2D screen point back to 3D world coordinates on a given plane.
 *
 * The inverse projection is not unique — a screen point corresponds to a line
 * in 3D space. This method returns the intersection of that line with the
 * horizontal plane at the specified Z height.
 *
 * @param screenPoint The 2D screen position
 * @param viewportWidth The viewport width in pixels
 * @param viewportHeight The viewport height in pixels
 * @param z The Z plane to project onto (default: 0.0)
 * @return The 3D world point on the specified Z plane
 */
fun screenToWorld(
    screenPoint: Point2D,
    viewportWidth: Int,
    viewportHeight: Int,
    z: Double = 0.0
): Point {
    val originX = viewportWidth / 2.0
    val originY = viewportHeight * 0.9

    // Solve the system of equations from translatePoint():
    //   screenX = originX + x * T[0][0] + y * T[1][0]
    //   screenY = originY - x * T[0][1] - y * T[1][1] - z * scale
    //
    // Rearranging:
    //   x * T[0][0] + y * T[1][0] = screenX - originX
    //   x * T[0][1] + y * T[1][1] = originY - screenY - z * scale
    //
    // This is a 2x2 linear system: [T[0][0] T[1][0]] [x]   [screenX - originX          ]
    //                                [T[0][1] T[1][1]] [y] = [originY - screenY - z*scale]

    val a = transformation[0][0]
    val b = transformation[1][0]
    val c = transformation[0][1]
    val d = transformation[1][1]

    val rhs1 = screenPoint.x - originX
    val rhs2 = originY - screenPoint.y - z * scale

    val det = a * d - b * c
    require(det != 0.0) { "Degenerate projection matrix — cannot invert" }

    val worldX = (rhs1 * d - rhs2 * b) / det
    val worldY = (rhs2 * a - rhs1 * c) / det

    return Point(worldX, worldY, z)
}
```

**Visibility change**: The `transformation` matrix (line 25, `private val transformation: Array<DoubleArray>`) remains private. The public methods use it internally. No change to `translatePoint()` visibility — the public API is the named methods above.

**After WS5**: When WS5 extracts a `SceneProjector` interface, `worldToScreen()` and `screenToWorld()` become methods on that interface. The engine delegates to the projector.

### Verification

- Round-trip test: `screenToWorld(worldToScreen(point, w, h), w, h, point.z)` should return the original point (within floating-point tolerance).
- Edge case: `worldToScreen(Point.ORIGIN, 800, 600)` should return `Point2D(400.0, 540.0)` (center x, 90% down y).
- Edge case: `screenToWorld(Point2D(400.0, 540.0), 800, 600)` should return `Point.ORIGIN`.

---

## Step 5: Mutable Engine `angle`/`scale` with Validation (F60)

### Rationale

`IsometricEngine.angle` and `IsometricEngine.scale` are `private val` constructor parameters (line 14). Users cannot adjust projection parameters after construction. This forces destruction and recreation of the engine (and all its cached state) to change the viewing angle or zoom level — a common operation for interactive scenes.

### Best Practice

Convert to `var` with `require()` guards in a custom setter. When the value changes, recompute the `transformation` matrix. Keep the constructor parameters as initial values.

### Files and Changes

#### 5a. `IsometricEngine.kt` — Make `angle` and `scale` mutable

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`

**Current** (lines 13–14):
```kotlin
class IsometricEngine(
    private val angle: Double = PI / 6,
    private val scale: Double = 70.0,
    ...
)
```

**After**:
```kotlin
class IsometricEngine(
    angle: Double = PI / 6,
    scale: Double = 70.0,
    private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) {
    var angle: Double = angle
        set(value) {
            require(value.isFinite()) { "angle must be finite, got $value" }
            field = value
            recomputeTransformation()
        }

    var scale: Double = scale
        set(value) {
            require(value.isFinite() && value > 0.0) { "scale must be positive and finite, got $value" }
            field = value
            recomputeTransformation()
        }

    private var transformation: Array<DoubleArray>

    init {
        require(angle.isFinite()) { "angle must be finite, got $angle" }
        require(scale.isFinite() && scale > 0.0) { "scale must be positive and finite, got $scale" }
        transformation = computeTransformation()
        // ... rest of existing init ...
    }

    private fun computeTransformation(): Array<DoubleArray> = arrayOf(
        doubleArrayOf(scale * cos(angle), scale * sin(angle)),
        doubleArrayOf(scale * cos(PI - angle), scale * sin(PI - angle))
    )

    private fun recomputeTransformation() {
        transformation = computeTransformation()
    }
    // ...
}
```

**Breaking change**: `angle` and `scale` change from `private val` to `var`. No external code currently accesses them (they were `private`), so the visibility increase is purely additive. The `private val` → backing field conversion changes the `transformation` from `val` to `var` — an internal change only.

**Design note**: `lightDirection`, `colorDifference`, and `lightColor` remain `private val` for now. They are less commonly adjusted at runtime and can be made mutable in a future iteration if demand exists.

### Verification

- Existing tests pass unchanged (construction behavior identical).
- New test: create engine, change `angle`, call `worldToScreen()`, verify output uses new angle.
- New test: `engine.scale = -1.0` throws `IllegalArgumentException`.
- New test: `engine.angle = Double.NaN` throws `IllegalArgumentException`.

---

## Step 6: `LocalIsometricEngine` CompositionLocal (F61)

### Rationale

Child composables inside `IsometricScene` have no way to access the `IsometricEngine`. The engine is created via `remember {}` in `IsometricScene` (line 100) and stored as a local variable. A child composable that needs `worldToScreen()` (step 4), `angle`/`scale` inspection (step 5), or any engine capability must receive it as an explicit parameter threaded through every intermediate composable.

### Best Practice

Provide the engine via a `CompositionLocal`. Use `staticCompositionLocalOf` (not `compositionLocalOf`) because the engine instance is set once per scene and never changes — tracking recomposition for it is wasted overhead. WS7 converts all existing `compositionLocalOf` to `staticCompositionLocalOf`; this new local should use the correct type from the start.

### Files and Changes

#### 6a. `CompositionLocals.kt` — Add `LocalIsometricEngine`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/CompositionLocals.kt`

**After** (add at end of file, before the closing of the file):
```kotlin
/**
 * CompositionLocal for providing the IsometricEngine to child composables.
 *
 * Uses [staticCompositionLocalOf] because the engine instance is set once per
 * scene and never changes — avoiding unnecessary recomposition tracking.
 *
 * Access within an IsometricScope:
 * ```
 * val engine = LocalIsometricEngine.current
 * val screenPos = engine.worldToScreen(point, viewportWidth, viewportHeight)
 * ```
 */
val LocalIsometricEngine = staticCompositionLocalOf<IsometricEngine> {
    error("No IsometricEngine provided. LocalIsometricEngine must be used within an IsometricScene.")
}
```

#### 6b. `IsometricScene.kt` — Provide engine via `LocalIsometricEngine`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricScene.kt`

**Current** (lines 185–192): `CompositionLocalProvider` provides 6 locals.

**After**: Add `LocalIsometricEngine` to the provider block:
```kotlin
CompositionLocalProvider(
    LocalIsometricEngine provides engine,
    LocalDefaultColor provides defaultColor,
    LocalLightDirection provides lightDirection,
    LocalRenderOptions provides renderOptions,
    LocalStrokeStyle provides strokeStyle,
    LocalColorPalette provides colorPalette
) {
    IsometricScopeImpl.currentContent()
}
```

### Verification

- Compile check: `LocalIsometricEngine` is only read inside `IsometricScene`'s composition — no existing code reads it, so no breakage.
- New test: composable inside `IsometricScene` reads `LocalIsometricEngine.current` and calls `worldToScreen()`.
- Error case: reading `LocalIsometricEngine.current` outside an `IsometricScene` throws with a clear error message.

---

## Step 7: `CameraState` for Programmatic Viewport Control (F19)

### Rationale

The camera is fixed at construction. There is no API for programmatic pan, zoom, or rotation. The camera origin is hardcoded in `IsometricEngine.translatePoint()` as `originX = width / 2.0`, `originY = height * 0.9` (lines 208–209). Users building interactive scenes (map viewers, editors, games) need mutable camera state.

### Best Practice

Introduce a `CameraState` class that holds pan offset, zoom factor, and rotation. Make it observable via Compose's `mutableStateOf` so changes trigger recomposition. Integrate with `IsometricScene` via an optional parameter.

### Files and Changes

#### 7a. New file: `CameraState.kt`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/CameraState.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue

/**
 * Mutable camera state for programmatic viewport control.
 *
 * All properties are backed by Compose snapshot state — mutations trigger
 * recomposition of any composable that reads them.
 *
 * @param panX Initial horizontal pan offset in pixels (default: 0.0)
 * @param panY Initial vertical pan offset in pixels (default: 0.0)
 * @param zoom Initial zoom factor (default: 1.0, must be positive)
 */
@Stable
class CameraState(
    panX: Double = 0.0,
    panY: Double = 0.0,
    zoom: Double = 1.0
) {
    var panX: Double by mutableDoubleStateOf(panX)
    var panY: Double by mutableDoubleStateOf(panY)
    var zoom: Double by mutableDoubleStateOf(zoom)

    /**
     * Pan the camera by a delta.
     */
    fun pan(deltaX: Double, deltaY: Double) {
        panX += deltaX
        panY += deltaY
    }

    /**
     * Zoom the camera by a factor around the current center.
     *
     * @param factor Multiplicative zoom factor (e.g., 1.1 for 10% zoom in)
     */
    fun zoomBy(factor: Double) {
        require(factor > 0.0) { "Zoom factor must be positive, got $factor" }
        zoom *= factor
    }

    /**
     * Reset to default state.
     */
    fun reset() {
        panX = 0.0
        panY = 0.0
        zoom = 1.0
    }
}
```

#### 7b. `SceneConfig.kt` / `AdvancedSceneConfig.kt` / `IsometricScene.kt` — Integrate optional `CameraState`

**Files**:
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/SceneConfig.kt`
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/AdvancedSceneConfig.kt`
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricScene.kt`

Add `cameraState` to the config surface rather than re-expanding the `IsometricScene` signature:
```kotlin
open class SceneConfig(
    // ... existing fields ...
    val cameraState: CameraState? = null
)

class AdvancedSceneConfig(
    // ... existing ctor params ...
    cameraState: CameraState? = null,
    // ... advanced-only params ...
) : SceneConfig(
    // ... existing args ...
    cameraState = cameraState
)
```

Inside `IsometricScene`, read `config.cameraState` from the config object and apply camera transforms before rendering:
```kotlin
// Apply camera transforms
val cameraState = config.cameraState
val effectivePanX = cameraState?.panX ?: 0.0
val effectivePanY = cameraState?.panY ?: 0.0
val effectiveZoom = cameraState?.zoom ?: 1.0

// Modify the engine's scale based on camera zoom
// and offset the rendering origin based on pan
```

**Integration with engine**: The camera pan/zoom is applied at the rendering level (translating/scaling the `DrawScope`) rather than modifying the engine's projection. This keeps the engine's coordinate system stable while allowing visual transforms:

```kotlin
Canvas(modifier = ...) {
    // ... existing sceneVersion/frameVersion reads ...

    if (cameraState != null) {
        // Read state properties to subscribe to changes
        val panX = cameraState.panX
        val panY = cameraState.panY
        val zoom = cameraState.zoom

        drawContext.transform.translate(panX.toFloat(), panY.toFloat())
        drawContext.transform.scale(zoom.toFloat(), zoom.toFloat())
    }

    // ... existing render call ...
}
```

If WS2 has not landed yet, use the pre-config split stroke locals that still exist at that point. The final converged surface is `LocalStrokeStyle`.

This keeps WS6 additive: camera control extends the config types WS2 already introduced instead of reopening the pre-WS2 flat parameter list.

#### 7c. `IsometricScene.kt` — Wire `onDrag` to camera pan (opt-in)

When `config.cameraState` is provided and `config.gestures` is enabled, the default drag behavior can optionally drive camera panning. This is a convenience — users can still override `config.gestures.onDrag` for custom behavior.

The integration is additive: if the user provides both `config.cameraState` and `config.gestures.onDrag`, the explicit drag callback takes precedence.

### Verification

- New test: create `CameraState(panX = 100.0)`, verify that shapes render offset by 100px.
- New test: `CameraState.zoomBy(2.0)` doubles the zoom factor.
- New test: `CameraState.zoomBy(-1.0)` throws `IllegalArgumentException`.
- Existing tests pass unchanged (default `config.cameraState = null` means no camera transforms applied).

---

## Step 8: Render Hooks (F57, F59)

### Rationale

There is no way to inject custom rendering before or after the scene, or to intercept the `PreparedScene` for inspection/debugging. The `PreparedScene` is computed inside `IsometricRenderer.ensurePreparedScene()` (line 111) and consumed immediately — never exposed. Users building debug overlays, performance monitors, or custom post-processing have no hook point.

### Best Practice

Add the hooks to `AdvancedSceneConfig`, not directly back onto `IsometricScene`. That preserves WS2's progressive-disclosure boundary: common usage stays simple, advanced rendering hooks stay in the advanced config type.

### Files and Changes

#### 8a. `AdvancedSceneConfig.kt` / `IsometricScene.kt` — Add render hooks

**Files**:
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/AdvancedSceneConfig.kt`
- `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricScene.kt`

Add three advanced-only config fields:
```kotlin
class AdvancedSceneConfig(
    // ... existing advanced params ...
    val onBeforeDraw: (DrawScope.() -> Unit)? = null,
    val onAfterDraw: (DrawScope.() -> Unit)? = null,
    val onPreparedSceneReady: ((PreparedScene) -> Unit)? = null
) : SceneConfig(/* ... */)
```

Inside the `Canvas` draw block, invoke the hooks from `config` at the appropriate lifecycle points:
```kotlin
Canvas(modifier = ...) {
    // ... existing sceneVersion/frameVersion reads ...
    // ... existing canvas size update ...

    // Hook: before draw
    config.onBeforeDraw?.invoke(this)

    // Render the scene
    with(renderer) {
        if (useNativeCanvas) {
            renderNative(rootNode = rootNode, context = renderContext, strokeWidth = strokeWidth, drawStroke = drawStroke)
        } else {
            render(rootNode = rootNode, context = renderContext, strokeWidth = strokeWidth, drawStroke = drawStroke)
        }
    }

    // Hook: after draw
    config.onAfterDraw?.invoke(this)
}
```

#### 8b. `IsometricRenderer.kt` — Expose `PreparedScene` via callback

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt`

**Current** (line 105): `internal val currentPreparedScene: PreparedScene? get() = cachedPreparedScene`

The `currentPreparedScene` is already `internal`. To expose it through the hook, `IsometricScene` reads it after rendering and invokes the callback:

```kotlin
// In IsometricScene's Canvas block, after the render call:
renderer.currentPreparedScene?.let { scene ->
    config.onPreparedSceneReady?.invoke(scene)
}
```

**Design note**: The callback fires on every draw pass that has a valid `PreparedScene`. This includes cache hits (scene unchanged) — the user receives the same scene object. This is intentional: a debug overlay needs the scene on every frame, not just on changes.

### Verification

- New test: `onBeforeDraw` is invoked before any shape rendering.
- New test: `onAfterDraw` receives a `DrawScope` with the correct canvas size.
- New test: `onPreparedSceneReady` receives a `PreparedScene` with correct command count matching the number of rendered paths.
- Existing tests pass unchanged (all hooks default to `null`).

---

## Step 9: Per-Subtree `renderOptions` Override (F4)

### Rationale

`RenderOptions` are scene-global — set once on `IsometricScene` and applied uniformly to all shapes. A user who wants to disable backface culling for a specific transparent shape, or disable depth sorting for a known-flat UI overlay, must either set the option globally (affecting all shapes) or split into multiple `IsometricScene` instances (losing shared coordinate space).

### Best Practice

Allow `Group` and individual shape composables to accept an optional `renderOptions` override. The override applies to the subtree rooted at that node. Child overrides take precedence over parent overrides. `null` (the default) inherits from the parent.

### Files and Changes

#### 9a. `IsometricNode.kt` — Add `renderOptions` to base node

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricNode.kt`

Add to `IsometricNode` (after line 53, after `isVisible`):
```kotlin
/**
 * Optional per-node render options override.
 * When non-null, overrides the inherited render options for this node and its subtree.
 * When null (default), inherits from the parent context.
 */
var renderOptions: RenderOptions? = null
```

#### 9b. `IsometricNode.kt` — Modify `GroupNode.renderTo()` to use per-node options

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricNode.kt`

**Current** `GroupNode.renderTo()` after WS5 (lines will shift from the pre-WS5 version):
```kotlin
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return
    val childContext = context.withTransform(
        position = position, rotation = rotation, scale = scale,
        rotationOrigin = rotationOrigin, scaleOrigin = scaleOrigin
    )
    for (child in childrenSnapshot) {
        child.renderTo(output, childContext)
    }
}
```

**After**:
```kotlin
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return

    // Apply per-node render options override if set
    val effectiveContext = if (renderOptions != null) {
        context.withRenderOptions(renderOptions!!)
    } else {
        context
    }

    val childContext = effectiveContext.withTransform(
        position = position, rotation = rotation, scale = scale,
        rotationOrigin = rotationOrigin, scaleOrigin = scaleOrigin
    )
    for (child in childrenSnapshot) {
        child.renderTo(output, childContext)
    }
}
```

#### 9c. `RenderContext.kt` — Add `withRenderOptions()`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/RenderContext.kt`

Add method:
```kotlin
/**
 * Create a new context with overridden render options.
 */
fun withRenderOptions(options: RenderOptions): RenderContext {
    return RenderContext(
        width = width,
        height = height,
        renderOptions = options,
        lightDirection = lightDirection,
        accumulatedPosition = accumulatedPosition,
        accumulatedRotation = accumulatedRotation,
        accumulatedScale = accumulatedScale,
        rotationOrigin = rotationOrigin,
        scaleOrigin = scaleOrigin
    )
}
```

Because WS1/WS4 remove the synthetic data-class `copy()`, `withRenderOptions()` should construct a new `RenderContext` directly while preserving the accumulated transform state.

#### 9d. `IsometricComposables.kt` — Add `renderOptions` parameter to `Group` and `Shape`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricComposables.kt`

Add `renderOptions: RenderOptions? = null` to `Group` and `Shape` signatures, and wire them in the `update` block:

```kotlin
@IsometricComposable
@Composable
fun IsometricScope.Group(
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    renderOptions: RenderOptions? = null,
    content: @Composable IsometricScope.() -> Unit
) {
    ReusableComposeNode<GroupNode, IsometricApplier>(
        factory = { GroupNode() },
        update = {
            // ... existing sets ...
            set(renderOptions) { this.renderOptions = it; markDirty() }
        },
        content = { IsometricScopeImpl.content() }
    )
}
```

Same pattern for `Shape`, `Path`, and `Batch` composables.

### Verification

- New test: `Group(renderOptions = RenderOptions(enableBackfaceCulling = false))` disables culling for shapes inside the group only.
- Existing tests pass unchanged (`renderOptions = null` inherits parent context, preserving current behavior).
- New test: nested groups with different `renderOptions` — inner override takes precedence.

---

## Step 10: `CustomNode` Composable for User-Defined Nodes (F3/F58)

### Rationale

There is no way for users to define custom geometry beyond the built-in shapes (`Prism`, `Pyramid`, `Cylinder`, `Stairs`, `Octahedron`, `Knot`). The `IsometricNode` is explicitly documented as "Open for extension to support custom node types via low-level ComposeNode primitives" (`IsometricNode.kt` line 12), and all node types are public. But there is no composable DSL support — users must drop down to raw `ComposeNode<MyNode, IsometricApplier>(...)` calls and understand the Applier's `GroupNode` parent requirement.

### Best Practice

Provide a `CustomNode` composable that accepts user-defined render commands. The user supplies a lambda that receives a `RenderContext` and returns a list of `RenderCommand`. This lambda is called during the render traversal, giving the user full control over geometry while remaining within the Compose lifecycle.

### Files and Changes

#### 10a. `IsometricNode.kt` — Add `CustomRenderNode`

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricNode.kt`

Add a new node type:
```kotlin
/**
 * Node that delegates rendering to a user-provided function.
 *
 * This is the escape hatch for users who need geometry beyond the built-in shapes.
 * The [renderFunction] receives the accumulated [RenderContext] and returns
 * render commands that will be included in the scene's depth sorting and drawing.
 *
 * @param renderFunction A function that produces render commands for this node.
 *   The function is called during tree traversal with the accumulated transforms.
 *   It should return render commands with `points = emptyList()` — the engine
 *   will project them to screen space during `projectScene()`.
 */
class CustomRenderNode(
    var renderFunction: (context: RenderContext) -> List<RenderCommand>
) : IsometricNode() {
    internal override val children = mutableListOf<IsometricNode>()

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return

        val localContext = context.withTransform(
            position = position,
            rotation = rotation,
            scale = scale,
            rotationOrigin = rotationOrigin,
            scaleOrigin = scaleOrigin
        )

        output += renderFunction(localContext)
    }
}
```

#### 10b. `IsometricComposables.kt` — Add `CustomNode` composable

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricComposables.kt`

```kotlin
/**
 * Add a custom-rendered node to the isometric scene.
 *
 * This is the escape hatch for geometry beyond built-in shapes. The [render]
 * function is called during tree traversal with the accumulated [RenderContext],
 * and should return [RenderCommand]s representing the custom geometry.
 *
 * Example — a custom ground plane:
 * ```
 * CustomNode(render = { context ->
 *     val groundPath = Path(
 *         Point(-5.0, -5.0, 0.0),
 *         Point(5.0, -5.0, 0.0),
 *         Point(5.0, 5.0, 0.0),
 *         Point(-5.0, 5.0, 0.0)
 *     )
 *     val transformedPath = context.applyTransformsToPath(groundPath)
 *     listOf(
 *         RenderCommand(
 *             commandId = "ground",
 *             points = emptyList(),
 *             color = IsoColor(200.0, 200.0, 200.0),
 *             originalPath = transformedPath,
 *             originalShape = null,
 *             ownerNodeId = null
 *         )
 *     )
 * })
 * ```
 *
 * @param position Local position offset
 * @param rotation Local rotation around Z axis
 * @param scale Local scale factor
 * @param rotationOrigin Origin point for rotation
 * @param scaleOrigin Origin point for scaling
 * @param visible Whether the node is visible
 * @param renderOptions Optional per-node render options override
 * @param render Function producing render commands from the accumulated context
 */
@IsometricComposable
@Composable
fun IsometricScope.CustomNode(
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    renderOptions: RenderOptions? = null,
    render: (context: RenderContext) -> List<RenderCommand>
) {
    ReusableComposeNode<CustomRenderNode, IsometricApplier>(
        factory = { CustomRenderNode(render) },
        update = {
            set(render) { this.renderFunction = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) { this.rotation = it; markDirty() }
            set(scale) { this.scale = it; markDirty() }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(renderOptions) { this.renderOptions = it; markDirty() }
        }
    )
}
```

#### 10c. `IsometricApplier.kt` — No changes needed

`IsometricApplier.insertBottomUp()` (line 50) requires `current` to be a `GroupNode`. `CustomRenderNode` is a leaf node (has no children DSL content parameter), so it will always be inserted into a `GroupNode` parent. The applier code works unchanged.

**WS1 cross-reference**: WS1 keeps `Shape` open, so users can still create domain-specific `Shape` subclasses when that lower-level extension point is sufficient. `CustomNode` complements that by covering cases where users need arbitrary render commands rather than reusable geometry types.

**WS4 cross-reference**: WS4 restricts `IsometricNode.children` to `internal` visibility. `CustomRenderNode` is in the same module (`isometric-compose`) so it can access `children`. External subclasses of `IsometricNode` would not be able to — but `CustomNode` composable is the supported extension point, not subclassing.

### Verification

- New test: `CustomNode` renders a triangle path and it appears in the `PreparedScene`.
- New test: `CustomNode` inside a `Group` with position offset — verify transforms accumulate correctly.
- New test: `CustomNode` with `visible = false` produces no render commands.
- Existing tests pass unchanged (no existing composable signatures change).

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1 (Point2D ops) | `Point2D.plus/minus/times/distanceTo` | Step 4 (projection API returns `Point2D` — consumers can do math on results) |
| Step 2 (hook defaults) | Extensible `RenderBenchmarkHooks` | Step 8 (render hooks could add new hook methods without breakage) |
| Step 3 (depth param) | `Point.depth(angle)`, `Path.depth(angle)` | WS5 F52 (decomposed depth sorter uses parameterized depth) |
| Step 4 (projection API) | `worldToScreen()`, `screenToWorld()` | Step 7 (CameraState uses projection for zoom-to-point), WS5 (SceneProjector interface wraps these methods) |
| Step 5 (mutable engine) | `engine.angle`, `engine.scale` as `var` | Step 6 (LocalIsometricEngine exposes mutable engine to child composables) |
| Step 6 (LocalIsometricEngine) | `LocalIsometricEngine` CompositionLocal | Step 7 (CameraState integration reads engine from local), WS7 (staticCompositionLocalOf — we already use this type) |
| Step 7 (CameraState) | Programmatic pan/zoom | WS2 (camera state integrates into SceneConfig/AdvancedSceneConfig) |
| Step 8 (render hooks) | `onBeforeDraw`, `onAfterDraw`, `onPreparedSceneReady` | WS2 (hooks integrate into AdvancedSceneConfig) |
| Step 9 (per-subtree options) | `renderOptions` on `IsometricNode` | Step 10 (CustomNode accepts per-node renderOptions) |
| Step 10 (CustomNode) | `CustomNode` composable, `CustomRenderNode` | Complements WS1's open `Shape` base — users can choose geometry subclasses or arbitrary render commands |

### Inbound dependencies from other workstreams

| From | Provides | Used in step |
|------|----------|-------------|
| WS1 Step 4 | Open `Shape` base + final built-ins | Step 10 — `CustomNode` complements, rather than replaces, user-defined `Shape` subclasses |
| WS1 Step 5 | `Point` operators (`plus`, `minus`, `times`) | Step 4 — `screenToWorld` math uses Point arithmetic |
| WS4 | `IsometricNode.children` → `internal` | Step 10 — CustomRenderNode must be in same module |
| WS5 | `SceneProjector` interface | Step 4 — `worldToScreen`/`screenToWorld` migrate to SceneProjector |
| WS5 | Decomposed engine | Steps 5–6 — mutable params and CompositionLocal may target decomposed components |
| WS7 | `staticCompositionLocalOf` for all locals | Step 6 — we already use `staticCompositionLocalOf` for new local |
| WS2 | `SceneConfig`/`AdvancedSceneConfig` | Steps 7–8 — camera state and render hooks integrate into config |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `Point2D.kt` | 1 | Operators, `distanceTo`, `distanceToSquared`, `ZERO` constant |
| `Point.kt` | 3 | Parameterized `depth(angle)` overload |
| `Path.kt` | 3 | Parameterized `depth(angle)` overload |
| `IsometricEngine.kt` | 4, 5 | `worldToScreen()`, `screenToWorld()`, mutable `angle`/`scale` with validation, `recomputeTransformation()` |
| `CompositionLocals.kt` | 6 | `LocalIsometricEngine` (`staticCompositionLocalOf`) |
| `CameraState.kt` | 7 | **new file** — mutable camera state with pan/zoom |
| `IsometricScene.kt` | 6, 7, 8 | Provide `LocalIsometricEngine`, read `cameraState` and render hooks from config, invoke hooks in Canvas block |
| `SceneConfig.kt` / `AdvancedSceneConfig.kt` | 7, 8 | Add `cameraState` and advanced render-hook fields |
| `IsometricNode.kt` | 9, 10 | `renderOptions` on base node, `GroupNode.renderTo()` applies overrides, **new** `CustomRenderNode` |
| `RenderContext.kt` | 9 | `withRenderOptions()` method |
| `IsometricComposables.kt` | 9, 10 | `renderOptions` param on `Group`/`Shape`/`Path`/`Batch`, **new** `CustomNode` composable |
| `IsometricRenderer.kt` | 2 | Default no-op bodies on `RenderBenchmarkHooks` interface |
| `BenchmarkHooksImpl.kt` | 2 | No changes (existing overrides unaffected) |
