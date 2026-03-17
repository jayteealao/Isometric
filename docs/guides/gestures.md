---
title: Gestures
description: Handle tap and drag interactions with spatial hit testing
sidebar:
  order: 3
---

Gesture handling is configured through `GestureConfig`, passed inside a `SceneConfig`. By default, gestures are disabled (`GestureConfig.Disabled`).

## Tap Handling

Provide an `onTap` callback to receive tap events. Each `TapEvent` contains screen coordinates and an optional hit-tested node:

```kotlin
IsometricScene(
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event: TapEvent ->
                // event.x, event.y — screen coordinates
                // event.node — the IsometricNode that was hit (nullable)
                println("Tapped: ${event.node?.nodeId}")
            }
        )
    )
) {
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor.BLUE)
}
```

### TapEvent

| Property | Type | Description |
|----------|------|-------------|
| `x` | `Double` | Screen X coordinate of the tap |
| `y` | `Double` | Screen Y coordinate of the tap |
| `node` | `IsometricNode?` | The node under the tap point, or `null` if tapping empty space |

### DragEvent

| Property | Type | Description |
|----------|------|-------------|
| `x` | `Double` | Screen X coordinate of the current pointer position |
| `y` | `Double` | Screen Y coordinate of the current pointer position |

## Drag Handling

`GestureConfig` provides three drag callbacks:

- **onDragStart** — fired once when the drag begins (after exceeding the threshold), receives a `DragEvent`
- **onDrag** — fired on each move during the drag, receives a `DragEvent`
- **onDragEnd** — fired when the pointer is released

The `dragThreshold` property (default `8f`) controls how many pixels of movement are required before a drag gesture is recognized. This prevents accidental drags during taps.

## Example: Tap to Change Color, Drag to Pan

```kotlin
@Composable
fun InteractiveScene() {
    var shapeColor by remember { mutableStateOf(IsoColor.BLUE) }
    val camera = remember { CameraState() }

    IsometricScene(
        config = SceneConfig(
            cameraState = camera,
            gestures = GestureConfig(
                onTap = { event ->
                    if (event.node != null) {
                        shapeColor = IsoColor(
                            (0..255).random(),
                            (0..255).random(),
                            (0..255).random()
                        )
                    }
                },
                onDrag = { event ->
                    camera.pan(event.x / 50.0, -event.y / 50.0)
                }
            )
        )
    ) {
        Shape(geometry = Prism(Point.ORIGIN), color = shapeColor)
    }
}
```

## Hit Testing Performance

Hit testing uses spatial indexing, giving O(1) lookup performance regardless of scene complexity. This means tap and drag callbacks respond quickly even in scenes with hundreds of shapes.

## Disabling Gestures

Gestures are disabled by default. You can also explicitly pass `GestureConfig.Disabled` to make intent clear:

```kotlin
IsometricScene(
    config = SceneConfig(gestures = GestureConfig.Disabled)
) { ... }
```
