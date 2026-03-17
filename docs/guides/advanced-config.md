---
title: Advanced Configuration
description: AdvancedSceneConfig lifecycle hooks, custom engines, and escape hatches
sidebar:
  order: 8
---

`AdvancedSceneConfig` extends `SceneConfig` with lifecycle hooks, cache control, and the ability to inject a custom engine. Most scenes only need `SceneConfig`. Reach for the advanced variant when you need to observe or intercept the rendering pipeline.

## When to Use AdvancedSceneConfig

Use this decision tree:

- Need lifecycle hooks (draw overlays, intercept hit testing, log errors)? Use `AdvancedSceneConfig`.
- Need a custom or mock `SceneProjector` engine? Use `AdvancedSceneConfig`.
- Need `enablePathCaching`, `forceRebuild`, or `frameVersion`? Use `AdvancedSceneConfig`.
- None of the above? `SceneConfig` is sufficient.

`AdvancedSceneConfig` accepts every parameter that `SceneConfig` does, so migration is a one-line change to the constructor name.

## Lifecycle Hooks Overview

All hooks are optional and default to `null`. They fire at specific points in the rendering lifecycle:

| Hook | Signature | Fires when | Use case |
|------|-----------|------------|----------|
| `onHitTestReady` | `(hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit` | Spatial index is built | Store the hit-test function for imperative queries outside gesture callbacks |
| `onFlagsReady` | `(RuntimeFlagSnapshot) -> Unit` | Config is applied to the runtime | Inspect which flags are active for diagnostics or telemetry |
| `onRenderError` | `(commandId: String, error: Throwable) -> Unit` | A render command throws | Log or report rendering failures without crashing the scene |
| `onEngineReady` | `(SceneProjector) -> Unit` | Engine is created or injected | Store a reference for coordinate conversion (world-to-screen, screen-to-world) |
| `onRendererReady` | `(IsometricRenderer) -> Unit` | Renderer is initialized | Inspect or configure the renderer directly |
| `onBeforeDraw` | `DrawScope.() -> Unit` | Immediately before scene drawing | Draw backgrounds, grids, or guidelines underneath the scene |
| `onAfterDraw` | `DrawScope.() -> Unit` | Immediately after scene drawing | Draw debug overlays, bounding boxes, or HUD elements on top |
| `onPreparedSceneReady` | `(PreparedScene) -> Unit` | Projection completes | Export, serialize, or inspect the projected render commands |

## Debug Overlay Example

Use `onAfterDraw` to render debug information on top of the scene. The callback receives a `DrawScope`, so you have full access to Compose drawing primitives:

```kotlin
@Composable
fun DebugScene() {
    IsometricScene(
        config = AdvancedSceneConfig(
            onAfterDraw = {
                // Draw a translucent grid overlay
                val gridSpacing = 70f
                val lineColor = Color.Gray.copy(alpha = 0.3f)
                for (x in 0..((size.width / gridSpacing).toInt())) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x * gridSpacing, 0f),
                        end = Offset(x * gridSpacing, size.height),
                        strokeWidth = 1f
                    )
                }
                for (y in 0..((size.height / gridSpacing).toInt())) {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, y * gridSpacing),
                        end = Offset(size.width, y * gridSpacing),
                        strokeWidth = 1f
                    )
                }
            }
        )
    ) {
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(33, 150, 243))
        Shape(geometry = Prism(Point(2.0, 0.0, 0.0)), color = IsoColor(76, 175, 80))
    }
}
```

You can also use `onBeforeDraw` to paint a background gradient or watermark that sits behind all scene geometry.

## Error Handling

`onRenderError` fires when a specific render command fails. The callback receives the `commandId` and the `Throwable`, allowing you to log the error without crashing the entire scene:

```kotlin
IsometricScene(
    config = AdvancedSceneConfig(
        onRenderError = { commandId, error ->
            Log.e("Isometric", "Render failed for command $commandId", error)
            // Report to crash analytics, show a toast, etc.
        }
    )
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

Other commands in the scene continue to render normally. This is useful in production to catch edge cases without a blank screen.

## Engine Access

Use `onEngineReady` to capture a reference to the `SceneProjector`. This is useful for coordinate conversion -- for example, mapping a screen tap to a world-space position outside of gesture callbacks:

```kotlin
@Composable
fun EngineAccessScene() {
    var engine by remember { mutableStateOf<SceneProjector?>(null) }

    IsometricScene(
        config = AdvancedSceneConfig(
            onEngineReady = { engine = it }
        )
    ) {
        Shape(geometry = Prism(Point.ORIGIN))
    }

    // Use the engine reference elsewhere in the composition
    engine?.let { proj ->
        Text("Projection version: ${proj.projectionVersion}")
    }
}
```

The engine reference remains valid for the lifetime of the scene composable.

## Custom Engine

The `engine` parameter accepts any `SceneProjector` implementation. This is the primary extension point for custom projection logic or testing:

```kotlin
// Use a custom engine with different projection parameters
val customEngine = IsometricEngine(
    angle = PI / 4,         // 45-degree projection instead of 30
    scale = 50.0,           // smaller scale
    colorDifference = 0.15, // subtler face shading
    lightColor = IsoColor(255, 240, 220)  // warm light
)

IsometricScene(
    config = AdvancedSceneConfig(engine = customEngine)
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

For testing, you can inject a mock `SceneProjector` to verify that your composables add the expected shapes without actually rendering:

```kotlin
class MockProjector : SceneProjector {
    val addedShapes = mutableListOf<Shape>()

    override fun add(shape: Shape, color: IsoColor) {
        addedShapes.add(shape)
    }

    override fun add(path: Path, color: IsoColor, originalShape: Shape?, id: String?, ownerNodeId: String?) {}

    override fun clear() { addedShapes.clear() }

    override fun projectScene(
        width: Int,
        height: Int,
        renderOptions: RenderOptions,
        lightDirection: Vector
    ) = PreparedScene(emptyList(), width, height)

    override fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder,
        touchRadius: Double
    ): RenderCommand? = null

    override val projectionVersion: Long get() = 0L
}
```

## Cache Control

Two parameters give you manual control over the PreparedScene cache:

- **`forceRebuild = true`** -- disables caching entirely. The scene re-projects every frame. Use this temporarily when debugging visual glitches to rule out stale cache as the cause.
- **`frameVersion`** -- an external cache key. The scene re-projects whenever this value changes. Useful when you modify something the dirty-tracking system cannot detect (such as a parameter on a custom `SceneProjector`).

```kotlin
@Composable
fun CacheControlScene() {
    var version by remember { mutableLongStateOf(0L) }

    IsometricScene(
        config = AdvancedSceneConfig(
            frameVersion = version
        )
    ) {
        Shape(geometry = Prism(Point.ORIGIN))
    }

    Button(onClick = { version++ }) {
        Text("Force re-render")
    }
}
```

> **Caution**
>
`forceRebuild = true` defeats the PreparedScene cache and can significantly increase CPU usage in complex scenes. Only use it for debugging, not in production.
