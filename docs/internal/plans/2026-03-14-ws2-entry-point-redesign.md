# WS2: Entry Point & Configuration Redesign — Detailed Implementation Plan

> **Workstream**: 2 of 8
> **Phase**: 2 (after WS1b)
> **Scope**: `IsometricScene` overload collapse, config types, gesture event types, stroke sealed type, canvas sizing, cross-field validation
> **Findings**: F1, F2, F5/F72, F20, F21, F22, F41
> **Depends on**: WS1b (naming/validation changes apply to params referenced here)
> **Coordinate with**: WS4 (deletes `IsometricCanvas`), WS7 F64 (SideEffect fixes in same file), WS1b F37 (HitOrder enum)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.1, §3.7

---

## Execution Order

The 7 findings decompose into 7 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: `StrokeStyle` sealed type + cross-field validation (F41)
2. **Step 2**: Gesture event types — `TapEvent`, `DragEvent` (F5/F72)
3. **Step 3**: `GestureConfig` wrapper (F22)
4. **Step 4**: `SceneConfig` / `AdvancedSceneConfig` (F1)
5. **Step 5**: Redesign `IsometricScene` overloads + escape hatches (F1, F2)
6. **Step 6**: Remove hardcoded `.fillMaxSize()` (F20)
7. **Step 7**: Fix canvas init dimensions — 0x0 until measured (F21)

Steps 1-3 are parallelizable (new types with no interdependency beyond step 3 consuming step 2's event types). Step 4 depends on steps 1-3 (config types compose `StrokeStyle` and `GestureConfig`). Step 5 depends on step 4. Steps 6-7 are parallelizable after step 5 (both modify the `IsometricScene` body).

---

## Step 1: `StrokeStyle` Sealed Type + Cross-Field Validation (F41)

### Rationale

The current API exposes two parameters — `drawStroke: Boolean` and `strokeWidth: Float` — plus a hardcoded stroke color (`Color.Black.copy(alpha = 0.1f)` in the renderer). These create contradictory states that are silently accepted: `drawStroke = true` with `strokeWidth = 0f`, or `drawStroke = false` with a non-default `strokeWidth` (wasted configuration). A sealed type makes impossible states unrepresentable.

### Best Practice

Kotlin sealed types replace boolean flag combinations with named, self-documenting variants. Each variant carries only the parameters relevant to that mode. `when` expressions over sealed types get exhaustive checking — the compiler catches unhandled rendering modes.

### Files and Changes

#### 1a. New file: `StrokeStyle.kt`

**Location**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/StrokeStyle.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.IsoColor

@Immutable
sealed class StrokeStyle {
    object FillOnly : StrokeStyle() {
        override fun toString(): String = "StrokeStyle.FillOnly"
    }

    data class Stroke(
        val width: Float = 1f,
        val color: IsoColor = IsoColor.BLACK
    ) : StrokeStyle() {
        init {
            require(width > 0f) { "Stroke width must be positive, got $width" }
        }
    }

    data class FillAndStroke(
        val width: Float = 1f,
        val color: IsoColor = IsoColor.BLACK
    ) : StrokeStyle() {
        init {
            require(width > 0f) { "Stroke width must be positive, got $width" }
        }
    }
}
```

**Validation**: `require(width > 0f)` in both `Stroke` and `FillAndStroke` constructors. Zero-width strokes are no longer silently accepted — the sealed type forces the caller to use `FillOnly` instead.

#### 1b. `CompositionLocals.kt` — Replace `LocalStrokeWidth` + `LocalDrawStroke` with `LocalStrokeStyle`

**Current** (lines 34-43): `LocalStrokeWidth` (lines 34-36) and `LocalDrawStroke` (lines 41-43).
```kotlin
val LocalStrokeWidth = compositionLocalOf { 1f }
val LocalDrawStroke = compositionLocalOf { true }
```

**After**:
```kotlin
val LocalStrokeStyle = compositionLocalOf<StrokeStyle> { StrokeStyle.FillAndStroke() }
```

Delete `LocalStrokeWidth` and `LocalDrawStroke`. All consumers switch to reading `LocalStrokeStyle.current`.

#### 1c. `IsometricRenderer.kt` — Consume `StrokeStyle` in render methods

**Current**: `render()` and `renderNative()` accept `strokeWidth: Float` and `drawStroke: Boolean` as separate parameters.

**After**: Accept a single `strokeStyle: StrokeStyle` parameter. The render loop becomes:

```kotlin
fun DrawScope.render(
    rootNode: IsometricNode,
    context: RenderContext,
    strokeStyle: StrokeStyle
) {
    // ... prepare scene ...

    val strokeColor = when (strokeStyle) {
        is StrokeStyle.FillOnly -> null
        is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
        is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
    }

    for (cached in cachedCommands) {
        when (strokeStyle) {
            is StrokeStyle.FillOnly -> {
                drawPath(cached.path, cached.fillColor)
            }
            is StrokeStyle.Stroke -> {
                drawPath(cached.path, strokeColor!!, style = Stroke(width = strokeStyle.width))
            }
            is StrokeStyle.FillAndStroke -> {
                drawPath(cached.path, cached.fillColor)
                drawPath(cached.path, strokeColor!!, style = Stroke(width = strokeStyle.width))
            }
        }
    }
}
```

The stroke color is derived from `strokeStyle.color` (converted via `toComposeColor()`) — replacing the hardcoded `Color.Black.copy(alpha = 0.1f)` that was previously baked into `CachedRenderCommand.strokeColor`.

#### 1d. `IsometricScene.kt` — Replace boolean params with `StrokeStyle`

In the composable body, replace the `strokeWidth`/`drawStroke` parameters with `strokeStyle` and update the `CompositionLocalProvider` and render call:

**Current** (line 189-190):
```kotlin
LocalStrokeWidth provides strokeWidth,
LocalDrawStroke provides drawStroke,
```

**After**:
```kotlin
LocalStrokeStyle provides strokeStyle,
```

**Current** (lines 298-316, render call):
```kotlin
if (useNativeCanvas) {
    renderNative(rootNode = rootNode, context = renderContext, strokeWidth = strokeWidth, drawStroke = drawStroke)
} else {
    render(rootNode = rootNode, context = renderContext, strokeWidth = strokeWidth, drawStroke = drawStroke)
}
```

**After**:
```kotlin
if (useNativeCanvas) {
    renderNative(rootNode = rootNode, context = renderContext, strokeStyle = strokeStyle)
} else {
    render(rootNode = rootNode, context = renderContext, strokeStyle = strokeStyle)
}
```

### Verification

- Compile: all `drawStroke`/`strokeWidth` references are removed from the public API
- Existing tests that pass `drawStroke = true, strokeWidth = 1f` → update to `strokeStyle = StrokeStyle.FillAndStroke(width = 1f)`
- New test: `StrokeStyle.Stroke(width = 0f)` throws `IllegalArgumentException`
- New test: `StrokeStyle.FillOnly` produces no stroke draw calls

---

## Step 2: Gesture Event Types (F5/F72)

### Rationale

The current gesture callbacks use fixed-arity lambdas: `(Double, Double, IsometricNode?) -> Unit` for tap (3 params — coordinates + hit-test result) and `(Double, Double) -> Unit` for drag (2 params — delta coordinates). Adding metadata (pressure, pointerId, timestamp) requires changing the lambda signature — a breaking change at every call site. Data class event types grow without breaking callers: new fields get default values.

### Best Practice

Android's own gesture APIs (`PointerInputChange`, `DragGestureDetector`) use data objects to carry event metadata. This is the standard pattern for evolvable event payloads.

### Files and Changes

#### 2a. New file: `GestureEvents.kt`

**Location**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/GestureEvents.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

/**
 * Emitted when the user taps the scene.
 *
 * @param x Screen x-coordinate of the tap (px)
 * @param y Screen y-coordinate of the tap (px)
 * @param node The hit-tested node at (x, y), or null if no node was hit
 */
data class TapEvent(
    val x: Double,
    val y: Double,
    val node: IsometricNode? = null
)

/**
 * Emitted during drag gestures (start and move).
 *
 * @param x Screen x-coordinate (px). For drag-start: the initial press position.
 *          For drag-move: the delta since last event.
 * @param y Screen y-coordinate (px). Same semantics as [x].
 */
data class DragEvent(
    val x: Double,
    val y: Double
)
```

**Evolvability**: Adding `pressure: Float = 0f`, `pointerId: Long = 0L`, or `timestamp: Long = 0L` in the future only requires adding a defaulted field — no call site changes.

**Note on `node` in `TapEvent`**: The current `onTap` callback already receives `(x, y, node)`. Collapsing this into the event type keeps hit-test results co-located with coordinates.

---

## Step 3: `GestureConfig` Wrapper (F22)

### Rationale

Gesture detection is always active in `IsometricScene` when `enableGestures = true` (the default), even when no callbacks are provided. The `pointerInput` modifier installs a coroutine that runs `awaitPointerEventScope` in an infinite loop, consuming pointer events and interfering with parent scrolling. A `GestureConfig` wrapper with an `enabled` property derived from callback presence solves this: no callbacks = no pointer input installation.

### Best Practice

Compose Material's `clickable()`, `draggable()`, and `scrollable()` all take configuration objects rather than bare lambdas. Grouping callbacks into a config type also reduces the parameter count on the host composable.

### Files and Changes

#### 3a. New file: `GestureConfig.kt`

**Location**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/GestureConfig.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Stable

/**
 * Configuration for gesture handling within an [IsometricScene].
 *
 * When all callbacks are null, [enabled] is false and no pointer input
 * processing is installed — avoiding interference with parent scrolling
 * and eliminating the overhead of an idle pointer event loop.
 *
 * @param onTap Called when the user taps the scene
 * @param onDrag Called during a drag with the delta since the last event
 * @param onDragStart Called when a drag gesture begins (movement exceeds [dragThreshold])
 * @param onDragEnd Called when a drag gesture ends (pointer released)
 * @param dragThreshold Minimum movement in pixels before a press is promoted to a drag
 */
@Stable
class GestureConfig(
    val onTap: ((TapEvent) -> Unit)? = null,
    val onDrag: ((DragEvent) -> Unit)? = null,
    val onDragStart: ((DragEvent) -> Unit)? = null,
    val onDragEnd: (() -> Unit)? = null,
    val dragThreshold: Float = 8f
) {
    init {
        require(dragThreshold >= 0f) { "dragThreshold must be non-negative, got $dragThreshold" }
    }

    /** True when at least one callback is provided — controls pointer input installation. */
    val enabled: Boolean
        get() = onTap != null || onDrag != null || onDragStart != null || onDragEnd != null

    companion object {
        /** No gesture handling. Pointer input is not installed. */
        val Disabled = GestureConfig()
    }

    override fun equals(other: Any?): Boolean =
        other is GestureConfig &&
            onTap == other.onTap &&
            onDrag == other.onDrag &&
            onDragStart == other.onDragStart &&
            onDragEnd == other.onDragEnd &&
            dragThreshold == other.dragThreshold

    override fun hashCode(): Int {
        var result = onTap?.hashCode() ?: 0
        result = 31 * result + (onDrag?.hashCode() ?: 0)
        result = 31 * result + (onDragStart?.hashCode() ?: 0)
        result = 31 * result + (onDragEnd?.hashCode() ?: 0)
        result = 31 * result + dragThreshold.hashCode()
        return result
    }

    override fun toString(): String = "GestureConfig(enabled=$enabled, dragThreshold=$dragThreshold)"
}
```

**Key behavioral change**: The `enableGestures: Boolean` parameter is deleted. The `if (enableGestures)` guard in `IsometricScene.kt` line 216 becomes `if (gestures.enabled)`, which is automatically false when `GestureConfig.Disabled` (the default) is used — fixing F22 without a separate boolean.

#### 3b. `IsometricScene.kt` — Update gesture handling block

**Current** (lines 216-281): Checks `enableGestures` boolean, then uses 4 separate lambda parameters with hardcoded `8f` drag threshold.

**After**: Checks `gestures.enabled`, reads callbacks from `gestures`, uses `gestures.dragThreshold`:

```kotlin
.then(
    if (gestures.enabled) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                var isDragging = false
                var dragStartPos: Offset? = null

                while (true) {
                    val event = awaitPointerEvent()

                    when (event.type) {
                        PointerEventType.Press -> {
                            val position = event.changes.first().position
                            dragStartPos = position
                            isDragging = false
                        }

                        PointerEventType.Move -> {
                            val position = event.changes.first().position
                            val start = dragStartPos

                            if (start != null) {
                                val delta = position - start

                                if (!isDragging && delta.getDistance() > gestures.dragThreshold) {
                                    isDragging = true
                                    gestures.onDragStart?.invoke(
                                        DragEvent(start.x.toDouble(), start.y.toDouble())
                                    )
                                }

                                if (isDragging) {
                                    gestures.onDrag?.invoke(
                                        DragEvent(delta.x.toDouble(), delta.y.toDouble())
                                    )
                                    dragStartPos = position
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }

                        PointerEventType.Release -> {
                            val position = event.changes.first().position

                            if (isDragging) {
                                gestures.onDragEnd?.invoke()
                            } else {
                                val hitNode = renderer.hitTest(
                                    rootNode = rootNode,
                                    x = position.x.toDouble(),
                                    y = position.y.toDouble(),
                                    context = currentRenderContext,
                                    width = currentCanvasWidth,
                                    height = currentCanvasHeight
                                )
                                gestures.onTap?.invoke(
                                    TapEvent(
                                        x = position.x.toDouble(),
                                        y = position.y.toDouble(),
                                        node = hitNode
                                    )
                                )
                            }

                            isDragging = false
                            dragStartPos = null
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }
)
```

**Behavioral changes**:
- Tap callback is only invoked if `gestures.onTap != null` (null-safe `?.invoke`)
- Drag callbacks are only invoked if their respective callbacks are non-null
- The `8f` magic number is replaced with `gestures.dragThreshold`
- When `GestureConfig.Disabled` is used (the default), no `pointerInput` modifier is installed at all

---

## Step 4: `SceneConfig` / `AdvancedSceneConfig` (F1)

### Rationale

The current `IsometricScene` composable has 21 parameters in a flat signature. Beginners see benchmark-only params (`forceRebuild`, `frameVersion`, `onHitTestReady`, `onFlagsReady`) next to essential params (`modifier`, `content`). The Compose convention (see `TextField`, `TopAppBar`, `Scaffold`) is to group related configuration into typed objects, leaving the composable signature with only the essential surface: `modifier`, config, and `content`.

Splitting into `SceneConfig` (beginner/intermediate) and `AdvancedSceneConfig` (benchmark/debug) provides progressive disclosure: beginners never see `forceRebuild` or `spatialIndexCellSize`.

### Best Practice

`AdvancedSceneConfig` extends `SceneConfig` so that advanced users get all the basic configuration plus the specialized knobs. This follows the Compose pattern where `TextFieldDefaults.textFieldColors()` gives basic colors, and more specific overloads add advanced styling.

### Files and Changes

#### 4a. New file: `SceneConfig.kt`

**Location**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/SceneConfig.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.IsometricEngine.Companion.DEFAULT_LIGHT_DIRECTION
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

/**
 * Core configuration for an [IsometricScene].
 *
 * Groups rendering, lighting, and visual style settings that most users will
 * want to customize. For benchmark hooks, cache control, and other advanced
 * knobs, use [AdvancedSceneConfig].
 *
 * @param renderOptions Depth sorting, backface culling, bounds checking, broad-phase sort
 * @param lightDirection Unit vector for per-face dot-product lighting
 * @param defaultColor Default shape color when none is specified in the composable
 * @param colorPalette Theme palette available via CompositionLocal
 * @param strokeStyle Fill, stroke, or fill-and-stroke rendering mode
 * @param gestures Gesture callback configuration — disabled by default
 * @param useNativeCanvas Use Android native canvas (2x faster, Android-only)
 */
@Immutable
open class SceneConfig(
    val renderOptions: RenderOptions = RenderOptions.Default,
    val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    val defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val colorPalette: ColorPalette = ColorPalette(),
    val strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    val gestures: GestureConfig = GestureConfig.Disabled,
    val useNativeCanvas: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        other != null &&
            other.javaClass == this.javaClass &&
            other is SceneConfig &&
            renderOptions == other.renderOptions &&
            lightDirection == other.lightDirection &&
            defaultColor == other.defaultColor &&
            colorPalette == other.colorPalette &&
            strokeStyle == other.strokeStyle &&
            gestures == other.gestures &&
            useNativeCanvas == other.useNativeCanvas

    override fun hashCode(): Int {
        var result = renderOptions.hashCode()
        result = 31 * result + lightDirection.hashCode()
        result = 31 * result + defaultColor.hashCode()
        result = 31 * result + colorPalette.hashCode()
        result = 31 * result + strokeStyle.hashCode()
        result = 31 * result + gestures.hashCode()
        result = 31 * result + useNativeCanvas.hashCode()
        return result
    }

    override fun toString(): String =
        "SceneConfig(renderOptions=$renderOptions, lightDirection=$lightDirection, " +
            "strokeStyle=$strokeStyle, gestures=$gestures, useNativeCanvas=$useNativeCanvas)"
}
```

#### 4b. New file: `AdvancedSceneConfig.kt`

**Location**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/AdvancedSceneConfig.kt`

```kotlin
package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Stable
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.IsometricEngine.Companion.DEFAULT_LIGHT_DIRECTION
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

/**
 * Extended configuration for [IsometricScene] with benchmark and debug knobs.
 *
 * Inherits all [SceneConfig] fields and adds:
 * - [engine]: custom engine instance (projection angle, scale)
 * - [enablePathCaching] / [enableSpatialIndex] / [spatialIndexCellSize]: renderer cache controls
 * - [forceRebuild]: disable caching per frame (benchmark use)
 * - [frameVersion]: external redraw signal for static-scene benchmarks
 * - [onHitTestReady]: escape hatch providing a hit-test function
 * - [onFlagsReady]: reports the actual runtime flag snapshot to benchmarks
 * - [onEngineReady]: callback providing access to the [IsometricEngine] after creation
 * - [onRendererReady]: callback providing access to the [IsometricRenderer] after creation
 */
@Stable
class AdvancedSceneConfig(
    renderOptions: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    colorPalette: ColorPalette = ColorPalette(),
    strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    gestures: GestureConfig = GestureConfig.Disabled,
    useNativeCanvas: Boolean = false,
    val engine: IsometricEngine = IsometricEngine(),
    val enablePathCaching: Boolean = true,
    val enableSpatialIndex: Boolean = true,
    val spatialIndexCellSize: Double = IsometricRenderer.DEFAULT_SPATIAL_INDEX_CELL_SIZE,
    val forceRebuild: Boolean = false,
    val frameVersion: Long = 0L,
    val onHitTestReady: ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? = null,
    val onFlagsReady: ((RuntimeFlagSnapshot) -> Unit)? = null,
    val onEngineReady: ((IsometricEngine) -> Unit)? = null,
    val onRendererReady: ((IsometricRenderer) -> Unit)? = null
) : SceneConfig(
    renderOptions = renderOptions,
    lightDirection = lightDirection,
    defaultColor = defaultColor,
    colorPalette = colorPalette,
    strokeStyle = strokeStyle,
    gestures = gestures,
    useNativeCanvas = useNativeCanvas
) {
    override fun equals(other: Any?): Boolean =
        other is AdvancedSceneConfig &&
            super.equals(other) &&
            engine == other.engine &&
            enablePathCaching == other.enablePathCaching &&
            enableSpatialIndex == other.enableSpatialIndex &&
            spatialIndexCellSize == other.spatialIndexCellSize &&
            forceRebuild == other.forceRebuild &&
            frameVersion == other.frameVersion

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + engine.hashCode()
        result = 31 * result + enablePathCaching.hashCode()
        result = 31 * result + enableSpatialIndex.hashCode()
        result = 31 * result + spatialIndexCellSize.hashCode()
        result = 31 * result + forceRebuild.hashCode()
        result = 31 * result + frameVersion.hashCode()
        return result
    }

    override fun toString(): String =
        "AdvancedSceneConfig(enablePathCaching=$enablePathCaching, enableSpatialIndex=$enableSpatialIndex, " +
            "forceRebuild=$forceRebuild, frameVersion=$frameVersion, ${super.toString()})"
}
```

**Design decisions**:

- `engine` is on `AdvancedSceneConfig` only — beginners use a default engine created internally.
- `enablePathCaching`, `enableSpatialIndex`, `spatialIndexCellSize` move to advanced config — these are implementation details that beginners should never see.
- `onHitTestReady` and `onFlagsReady` move to advanced config — they exist solely for benchmark instrumentation.
- `onEngineReady` and `onRendererReady` are new (F2) — escape hatches for imperative access after scene creation.
- Callback functions are excluded from `equals`/`hashCode` (function identity is unstable in Compose). Only value fields participate.

---

## Step 5: Redesign `IsometricScene` Overloads + Escape Hatches (F1, F2)

### Rationale

The current API has two overloads: a 21-parameter primary and a 5-parameter simplified version that delegates to the primary with `enableGestures = false`. After introducing config types, the composable collapses to two clean overloads distinguished by config type — not parameter count.

### Files and Changes

#### 5a. `IsometricScene.kt` — Replace both existing overloads

**Current**: 21-param primary (lines 74-97) + 5-param simplified (lines 320-339)

**After**: Two overloads — simple and advanced:

```kotlin
/**
 * Simple isometric scene composable.
 *
 * Covers the common case: rendering shapes with optional gestures and styling.
 * For benchmark hooks, custom engine instances, and cache control, use the
 * overload that accepts [AdvancedSceneConfig].
 *
 * @param modifier Modifier for sizing and layout (no default sizing applied)
 * @param config Scene rendering, lighting, and gesture configuration
 * @param content Scene construction lambda
 */
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: SceneConfig = SceneConfig(),
    content: @Composable IsometricScope.() -> Unit
) {
    IsometricScene(
        modifier = modifier,
        config = AdvancedSceneConfig(
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection,
            defaultColor = config.defaultColor,
            colorPalette = config.colorPalette,
            strokeStyle = config.strokeStyle,
            gestures = config.gestures,
            useNativeCanvas = config.useNativeCanvas
        ),
        content = content
    )
}

/**
 * Advanced isometric scene composable.
 *
 * Provides full control over the rendering pipeline, including benchmark hooks,
 * cache settings, and imperative access to the engine and renderer.
 *
 * @param modifier Modifier for sizing and layout (no default sizing applied)
 * @param config Advanced configuration including benchmark hooks and cache control
 * @param content Scene construction lambda
 */
@Composable
fun IsometricScene(
    modifier: Modifier = Modifier,
    config: AdvancedSceneConfig,
    content: @Composable IsometricScope.() -> Unit
) {
    val rootNode = remember { GroupNode() }
    val engine = remember { config.engine }
    val renderer = remember(engine, config.enablePathCaching, config.enableSpatialIndex, config.spatialIndexCellSize) {
        IsometricRenderer(
            engine = engine,
            enablePathCaching = config.enablePathCaching,
            enableSpatialIndex = config.enableSpatialIndex,
            spatialIndexCellSize = config.spatialIndexCellSize
        )
    }

    // F2: Fire escape hatches once after creation
    DisposableEffect(engine, renderer) {
        config.onEngineReady?.invoke(engine)
        config.onRendererReady?.invoke(renderer)
        onDispose { }
    }

    // ... remainder of the composable body (see steps 6-7 for further modifications) ...
}
```

**Key behavioral changes**:

1. The simple overload delegates to the advanced overload by wrapping `SceneConfig` into `AdvancedSceneConfig` — all advanced fields get their defaults.
2. Overload resolution is type-based (`SceneConfig` vs `AdvancedSceneConfig`), not parameter-count-based.
3. The simple overload can also be called with zero config: `IsometricScene(modifier) { ... }` uses `SceneConfig()` defaults.
4. `enableGestures: Boolean` is gone — replaced by `GestureConfig.enabled` (step 3).

#### 5b. `IsometricScene.kt` — Wire `onEngineReady` / `onRendererReady` (F2)

The `DisposableEffect(engine, renderer)` block fires `config.onEngineReady?.invoke(engine)` and `config.onRendererReady?.invoke(renderer)` once the composable body has created or remembered these objects. Using `DisposableEffect` (not `SideEffect`) because:
1. The callbacks should fire once per engine/renderer instance, not on every recomposition — `DisposableEffect` with keys achieves this.
2. `SideEffect` would contradict WS7 F64, which specifically converts existing `SideEffect` calls to `DisposableEffect` in this same file.
3. `DisposableEffect` is synchronous (no coroutine scope, unlike `LaunchedEffect`), matching the callback design.
4. The `onDispose { }` block is intentionally empty — no cleanup needed for notification-style callbacks.

#### 5c. `IsometricScene.kt` — Flatten config reads into body

All config field reads inside the composable body change from parameter names to `config.*`:

| Before | After |
|--------|-------|
| `renderOptions` | `config.renderOptions` |
| `lightDirection` | `config.lightDirection` |
| `enablePathCaching` | `config.enablePathCaching` |
| `enableSpatialIndex` | `config.enableSpatialIndex` |
| `spatialIndexCellSize` | `config.spatialIndexCellSize` |
| `useNativeCanvas` | `config.useNativeCanvas` |
| `forceRebuild` | `config.forceRebuild` |
| `frameVersion` | `config.frameVersion` |
| `enableGestures` | `config.gestures.enabled` |
| `onTap(...)` | `config.gestures.onTap?.invoke(TapEvent(...))` |
| `onDrag(...)` | `config.gestures.onDrag?.invoke(DragEvent(...))` |
| `onDragStart(...)` | `config.gestures.onDragStart?.invoke(DragEvent(...))` |
| `onDragEnd()` | `config.gestures.onDragEnd?.invoke()` |
| `strokeWidth` / `drawStroke` | `config.strokeStyle` (sealed when) |
| `onHitTestReady` | `config.onHitTestReady` |
| `onFlagsReady` | `config.onFlagsReady` |
| `defaultColor` | `config.defaultColor` |
| `colorPalette` | `config.colorPalette` |

#### 5d. `IsometricScene.kt` — Update `CompositionLocalProvider`

**Current** (lines 185-192):
```kotlin
CompositionLocalProvider(
    LocalDefaultColor provides defaultColor,
    LocalLightDirection provides lightDirection,
    LocalRenderOptions provides renderOptions,
    LocalStrokeWidth provides strokeWidth,
    LocalDrawStroke provides drawStroke,
    LocalColorPalette provides colorPalette
)
```

**After**:
```kotlin
CompositionLocalProvider(
    LocalDefaultColor provides config.defaultColor,
    LocalLightDirection provides config.lightDirection,
    LocalRenderOptions provides config.renderOptions,
    LocalStrokeStyle provides config.strokeStyle,
    LocalColorPalette provides config.colorPalette
)
```

#### 5e. `IsometricScene.kt` — Remove `rememberUpdatedState` for deleted params

**Current** (lines 206-209):
```kotlin
val currentOnTap by rememberUpdatedState(onTap)
val currentOnDragStart by rememberUpdatedState(onDragStart)
val currentOnDrag by rememberUpdatedState(onDrag)
val currentOnDragEnd by rememberUpdatedState(onDragEnd)
```

**After**: Replace with a single `rememberUpdatedState` for the gestures config:
```kotlin
val currentGestures by rememberUpdatedState(config.gestures)
```

Then reference `currentGestures.onTap`, etc. in the pointer input block.

### Verification

- The simple overload `IsometricScene(modifier) { Shape(...) }` compiles and renders
- The advanced overload `IsometricScene(modifier, config = AdvancedSceneConfig(forceRebuild = true)) { ... }` compiles
- `onEngineReady` callback fires with a non-null `IsometricEngine`
- `onRendererReady` callback fires with a non-null `IsometricRenderer`
- All benchmark code (`BenchmarkScreen.kt`) updates to use `AdvancedSceneConfig`

---

## Step 6: Remove Hardcoded `.fillMaxSize()` (F20)

### Rationale

`IsometricScene.kt` line 214 hardcodes `.fillMaxSize()` inside the Canvas modifier chain. This prevents users from controlling the scene size — `IsometricScene(modifier = Modifier.size(200.dp))` is overridden by the internal `fillMaxSize()`. Users should control sizing via the `modifier` parameter.

### Best Practice

Compose composables should not impose sizing constraints. The `modifier` parameter is the standard mechanism for external sizing. Material's `Surface`, `Card`, and `Box` all apply the user's modifier without overriding it.

### Files and Changes

#### 6a. `IsometricScene.kt` — Delete `.fillMaxSize()` from Canvas modifier

**Current** (line 213-214):
```kotlin
Canvas(
    modifier = modifier
        .fillMaxSize()
        .then(
```

**After**:
```kotlin
Canvas(
    modifier = modifier
        .then(
```

**Impact**: Callers that relied on the implicit `fillMaxSize()` must now pass `Modifier.fillMaxSize()` explicitly. This is a breaking change — intentional per user preference (no deprecation cycles).

**Call site updates required**:
- `app/src/main/kotlin/.../ComposeActivity.kt` — likely already passes `Modifier.fillMaxSize()`
- `app/src/main/kotlin/.../RuntimeApiActivity.kt` — check and add if needed
- `isometric-benchmark/src/main/kotlin/.../BenchmarkScreen.kt` — already passes `Modifier.fillMaxSize()`
- `app/src/main/kotlin/.../OptimizedPerformanceSample.kt` — check and add if needed
- Any test files using `IsometricScene` — add explicit `Modifier.fillMaxSize()` where full-size is desired

#### 6b. Remove `fillMaxSize` import

**Current** (line 4): `import androidx.compose.foundation.layout.fillMaxSize`

**After**: Delete the import (no longer used in this file).

### Verification

- `IsometricScene(modifier = Modifier.size(200.dp)) { ... }` renders at 200x200dp (not full screen)
- `IsometricScene(modifier = Modifier.fillMaxSize()) { ... }` renders full screen (same as current behavior, now explicit)

---

## Step 7: Fix Canvas Init Dimensions — 0x0 Until Measured (F21)

### Rationale

Canvas dimensions are initialized to `800x600` (hardcoded magic numbers at line 131-132):
```kotlin
var canvasWidth by remember { mutableStateOf(800) }
var canvasHeight by remember { mutableStateOf(600) }
```

These values are wrong for every device. On first composition, the scene renders at 800x600 regardless of actual layout size, then snaps to the real size on the next frame. This causes a visible layout jump and wastes the first render pass with incorrect projection math.

### Best Practice

Initialize to 0x0 and skip rendering until the first measurement provides real dimensions. This matches how Compose's `SubcomposeLayout` and `BoxWithConstraints` handle deferred sizing.

### Files and Changes

#### 7a. `IsometricScene.kt` — Change initial dimensions to 0x0

**Current** (lines 131-132):
```kotlin
var canvasWidth by remember { mutableStateOf(800) }
var canvasHeight by remember { mutableStateOf(600) }
```

**After**:
```kotlin
var canvasWidth by remember { mutableStateOf(0) }
var canvasHeight by remember { mutableStateOf(0) }
```

#### 7b. `IsometricScene.kt` — Guard render calls on non-zero dimensions

**Current** (lines 293-316): Render calls execute unconditionally.

**After**: Wrap the render block in a size guard:

```kotlin
// Update canvas size from actual layout
canvasWidth = size.width.toInt()
canvasHeight = size.height.toInt()

// Skip rendering until we have real dimensions
if (canvasWidth > 0 && canvasHeight > 0) {
    with(renderer) {
        when (config.strokeStyle) {
            // ... render with strokeStyle ...
        }
    }
}
```

This ensures:
1. No render attempt with 0x0 dimensions (would produce degenerate projection)
2. No render attempt with stale 800x600 dimensions (would produce wrong projection on first frame)
3. The Canvas draw lambda still runs (it must, to pick up `size`), but the actual render is skipped

#### 7c. `RuntimeFlagSnapshot` — Update to reflect 0x0 init

`RuntimeFlagSnapshot` captures `canvasWidth` and `canvasHeight`. Benchmark validation code that checks `canvasWidth == 800` or `canvasHeight == 600` must be updated to accept 0 as the initial value, or to wait until the first real measurement.

### Verification

- First-frame render is skipped (no visible 800x600 flash)
- Second-frame render uses actual layout dimensions
- Benchmark harness still receives correct dimensions after measurement

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1 (`StrokeStyle`) | Sealed type replaces `drawStroke`/`strokeWidth` booleans | WS1 F41 cross-field validation is superseded — sealed type makes contradictory states unrepresentable |
| Step 3 (`GestureConfig`) | Grouped gesture callbacks with `enabled` property | WS1b F37 (`HitOrder` enum) — hit-test behavior config can be added to `GestureConfig` or `SceneConfig` |
| Step 4 (`SceneConfig`/`AdvancedSceneConfig`) | Typed config objects | WS4 (deletes `IsometricCanvas`) — `IsometricScene` with `SceneConfig` becomes the sole entry point |
| Step 4 (`AdvancedSceneConfig`) | `engine` and renderer cache params | WS5 F52 (decomposes `IsometricEngine`) — `AdvancedSceneConfig.engine` wraps the existing monolith; WS5 splits it without changing the config API |
| Step 5 (`onEngineReady`/`onRendererReady`) | Imperative escape hatches | WS5 — engine decomposition may change the type returned by `onEngineReady`, but the callback signature (`(IsometricEngine) -> Unit`) remains stable |
| Step 5 (overload redesign) | New `IsometricScene` signature using `DisposableEffect` | WS7 F64 (SideEffect → DisposableEffect) — both modify `IsometricScene.kt`; WS2 already uses `DisposableEffect` for new code, **coordinate to avoid merge conflicts** on existing `SideEffect` conversions |
| Step 6 (remove `fillMaxSize`) | User-controlled sizing | WS4 — `IsometricCanvas` deletion removes the other composable that has this same bug |
| Step 1 (`StrokeStyle`) | `LocalStrokeStyle` replaces `LocalStrokeWidth`/`LocalDrawStroke` | WS3 — any rename of CompositionLocals should target `LocalStrokeStyle`, not the deleted locals |
| Step 4 (`SceneConfig`) | `lightDirection` on config | WS1 Step 3 (Vector `i/j/k` → `x/y/z`) — `SceneConfig.lightDirection` is typed as `Vector`; WS1's rename changes the field names on the `Vector` instance, not on `SceneConfig` |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `StrokeStyle.kt` | 1 | **new file** — sealed type with `FillOnly`, `Stroke`, `FillAndStroke` variants; `require()` on stroke width |
| `GestureEvents.kt` | 2 | **new file** — `TapEvent`, `DragEvent` data classes |
| `GestureConfig.kt` | 3 | **new file** — wraps gesture callbacks + drag threshold; `enabled` derived property; `Disabled` companion |
| `SceneConfig.kt` | 4 | **new file** — groups `renderOptions`, `lightDirection`, `defaultColor`, `colorPalette`, `strokeStyle`, `gestures`, `useNativeCanvas` |
| `AdvancedSceneConfig.kt` | 4 | **new file** — extends `SceneConfig` with `engine`, cache flags, benchmark hooks, escape hatches |
| `IsometricScene.kt` | 1, 3, 5, 6, 7 | Replace 21-param + 5-param overloads with `SceneConfig` + `AdvancedSceneConfig` overloads; delete `fillMaxSize()`; init canvas to 0x0; wire `onEngineReady`/`onRendererReady`; replace gesture lambdas with `GestureConfig`; replace stroke booleans with `StrokeStyle` |
| `IsometricRenderer.kt` | 1 | `render()`/`renderNative()` accept `StrokeStyle` instead of `strokeWidth`/`drawStroke`; update draw logic to `when (strokeStyle)` |
| `CompositionLocals.kt` | 1 | Delete `LocalStrokeWidth` + `LocalDrawStroke`; add `LocalStrokeStyle` |
| `BenchmarkScreen.kt` | 5 | Update `IsometricScene` call to use `AdvancedSceneConfig`; move `forceRebuild`, `frameVersion`, `onHitTestReady`, `onFlagsReady`, cache flags into config |
| `ComposeActivity.kt` | 5, 6 | Update `IsometricScene` call to use `SceneConfig`; add explicit `Modifier.fillMaxSize()` if not already present |
| `RuntimeApiActivity.kt` | 5, 6 | Same as `ComposeActivity.kt` |
| `OptimizedPerformanceSample.kt` | 5, 6 | Same as `ComposeActivity.kt` |
| `PrimitiveLevelsExample.kt` | 5, 6 | Same as `ComposeActivity.kt` |
| `IsometricCanvasSnapshotTest.kt` | 5 | Update any `IsometricScene` usage to new overloads |
| `IsometricRendererNativeCanvasTest.kt` | 1 | Update `strokeWidth`/`drawStroke` params to `StrokeStyle` |
| `IsometricRendererTest.kt` | 1 | Update `render()` calls to use `StrokeStyle` |
