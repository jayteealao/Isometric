---
title: Interactive Scenes
description: Copy-paste recipes for tap, drag, and camera interactions
sidebar:
  order: 3
---

Each recipe below is a self-contained `@Composable` function demonstrating a common interaction pattern.

## Tap to Highlight

Tapping a shape changes its color. Tapping the background (empty space) resets the selection.

```kotlin
@Composable
fun TapToHighlight() {
    var selectedNodeId by remember { mutableStateOf<String?>(null) }

    val gestures = remember {
        GestureConfig(
            onTap = { event: TapEvent ->
                selectedNodeId = event.node?.nodeId
            }
        )
    }

    IsometricScene(config = SceneConfig(gestures = gestures)) {
        ForEach((0 until 3).toList()) { i ->
            val isSelected = selectedNodeId != null &&
                selectedNodeId == "node_$i" // see note below

            Shape(
                geometry = Prism(Point(i * 2.0, 0.0, 0.0)),
                color = if (isSelected) IsoColor.ORANGE else IsoColor(33, 150, 243)
            )
        }
    }
}
```

`TapEvent.node` is `null` when the tap lands on empty space, which clears the selection. When it hits a shape, `node.nodeId` identifies which one was tapped.

> **Note**
>
Node IDs are auto-generated (e.g., `node_0`, `node_1`). In practice, compare `event.node?.nodeId` against a value you store when the node is created, rather than hard-coding ID strings.

## Drag to Pan

Use `CameraState` with `GestureConfig.onDrag` for smooth viewport panning. When a `CameraState` is provided and no `onDrag` callback is set, the scene automatically pans on drag.

```kotlin
@Composable
fun DragToPan() {
    val camera = remember { CameraState() }

    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = SceneConfig(cameraState = camera)
    ) {
        // Build a large grid that extends beyond the viewport
        ForEach((0 until 8).toList()) { x ->
            ForEach((0 until 8).toList()) { y ->
                Shape(
                    geometry = Prism(Point(x * 1.5, y * 1.5, 0.0)),
                    color = IsoColor(
                        (30 + x * 28).coerceAtMost(255),
                        (100 + y * 20).coerceAtMost(255),
                        200
                    )
                )
            }
        }
    }
}
```

The default drag-to-pan behaviour is built in when `cameraState` is non-null and `gestures.onDrag` is null. To add custom drag handling, set the `onDrag` callback:

```kotlin
val camera = remember { CameraState() }

val gestures = remember {
    GestureConfig(
        onDrag = { event: DragEvent ->
            // Scale drag speed by inverse zoom for consistent feel
            camera.pan(event.x / camera.zoom, event.y / camera.zoom)
        }
    )
}
```

## Tap to Add Shapes

Maintain a list of shapes in `remember` and add a new `Prism` at the tap location.

```kotlin
@Composable
fun TapToAdd() {
    val shapes = remember { mutableStateListOf<Point>() }

    val gestures = remember {
        GestureConfig(
            onTap = { event: TapEvent ->
                if (event.node == null) {
                    // Place shapes on a rough grid based on tap position
                    val x = (event.x / 80.0).toInt().toDouble()
                    val y = (event.y / 80.0).toInt().toDouble()
                    shapes.add(Point(x, y, 0.0))
                }
            }
        )
    }

    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = SceneConfig(gestures = gestures)
    ) {
        // Platform
        Shape(
            geometry = Prism(Point(-5.0, -5.0, -0.1), 10.0, 10.0, 0.1),
            color = IsoColor.LIGHT_GRAY
        )

        // Dynamically placed shapes
        ForEach(
            items = shapes.toList(),
            key = { "${it.x}_${it.y}" }
        ) { pos ->
            Shape(
                geometry = Prism(pos),
                color = IsoColor(33, 150, 243)
            )
        }
    }
}
```

Using `key` in `ForEach` ensures Compose can efficiently diff the list when new items are added, avoiding unnecessary recomposition of existing shapes.

## Shape Info Display

Tap a shape and display its `nodeId` in a `Text` composable above the scene.

```kotlin
@Composable
fun ShapeInfoDisplay() {
    var tappedInfo by remember { mutableStateOf("Tap a shape to see its info") }

    val gestures = remember {
        GestureConfig(
            onTap = { event: TapEvent ->
                tappedInfo = if (event.node != null) {
                    "Tapped: ${event.node.nodeId} at (${event.x.toInt()}, ${event.y.toInt()})"
                } else {
                    "Tapped background at (${event.x.toInt()}, ${event.y.toInt()})"
                }
            }
        )
    }

    Column {
        Text(
            text = tappedInfo,
            modifier = Modifier.padding(16.dp)
        )
        IsometricScene(
            modifier = Modifier.fillMaxSize(),
            config = SceneConfig(gestures = gestures)
        ) {
            Shape(
                geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0),
                color = IsoColor(33, 150, 243)
            )
            Shape(
                geometry = Cylinder(Point(3.0, 0.0, 0.0), 0.8, 2.0),
                color = IsoColor(76, 175, 80)
            )
            Shape(
                geometry = Octahedron(Point(0.0, 3.0, 0.0)),
                color = IsoColor(255, 152, 0)
            )
        }
    }
}
```

The `Column` layout places the `Text` above the `IsometricScene`. Because `tappedInfo` is Compose state, the text updates immediately when a tap is detected.

## Interactive Building

A building with a toggleable roof. Tap the building to show or hide the roof using `If`.

```kotlin
@Composable
fun InteractiveBuilding() {
    var showRoof by remember { mutableStateOf(true) }

    val gestures = remember {
        GestureConfig(
            onTap = { event: TapEvent ->
                if (event.node != null) {
                    showRoof = !showRoof
                }
            }
        )
    }

    IsometricScene(config = SceneConfig(gestures = gestures)) {
        Group(position = Point(0.0, 0.0, 0.0)) {
            // Foundation
            Shape(
                geometry = Prism(Point.ORIGIN, 4.0, 4.0, 0.3),
                color = IsoColor.GRAY
            )

            // Walls
            Shape(
                geometry = Prism(Point(0.2, 0.2, 0.3), 3.6, 3.6, 2.5),
                color = IsoColor(33, 150, 243)
            )

            // Door
            Shape(
                geometry = Prism(Point(1.5, 0.0, 0.3), 1.0, 0.2, 1.5),
                color = IsoColor(121, 85, 72)
            )

            // Conditional roof
            If(showRoof) {
                Shape(
                    geometry = Pyramid(Point(0.0, 0.0, 2.8), 4.0, 4.0, 1.5),
                    color = IsoColor(160, 60, 50)
                )
            }
        }
    }
}
```

When `showRoof` is `false`, the `If` composable removes the `Pyramid` from the scene graph entirely -- it is not just hidden, it is not rendered or depth-sorted at all.
