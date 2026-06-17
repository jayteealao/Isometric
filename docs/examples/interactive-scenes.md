---
title: Interactive Scenes
description: Copy-paste recipes for tap, long-press, alpha, drag, and camera interactions
sidebar:
  order: 3
---

Each recipe is a self-contained `@Composable`. They use the **per-node interaction props**
(`onClick`, `onLongClick`, `alpha`, `testTag`, `nodeId`) when a single shape needs behavior,
and fall back to a scene-level `GestureConfig` only where there is no per-node equivalent —
background taps, drag, and camera panning. For the full prop reference, see
[Per-Node Interactions](../guides/interactions.md).

## Tap to Select

Attach an `onClick` directly to each shape. No scene-level handler or hit-test plumbing is
needed — the callback fires when that specific shape is tapped.

```kotlin
@Composable
fun TapToSelect() {
    var selected by remember { mutableStateOf<String?>(null) }
    val palette = remember {
        listOf(IsoColor.RED, IsoColor.GREEN, IsoColor.BLUE, IsoColor.ORANGE)
    }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        ForEach(items = palette.indices.toList(), key = { it }) { i ->
            val id = "box_$i"
            val isSelected = selected == id
            Shape(
                geometry = Prism(Point(i * 1.5, 0.0, 0.0)),
                color = if (isSelected) IsoColor.YELLOW else palette[i],
                nodeId = id,
                onClick = { selected = if (selected == id) null else id }
            )
        }
    }
}
```

> **Tip**
>
The older approach uses a scene-level `GestureConfig.onTap` that reads `event.node?.nodeId`
and matches it against your own ids. Per-node `onClick` removes that indirection — the shape
that was tapped is the one whose callback runs.

## Long-Press to Lock

`onLongClick` fires after a ~500&nbsp;ms press. When it fires on a node, the trailing tap is
suppressed — that node's `onClick` does **not** also run on release. Here, long-press locks a
tile (dimming it with `alpha`) and a normal tap unlocks it.

```kotlin
@Composable
fun LongPressToLock() {
    val locked = remember { mutableStateMapOf<Int, Boolean>() }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        ForEach((0 until 9).toList(), key = { it }) { i ->
            val isLocked = locked[i] == true
            Shape(
                geometry = Prism(Point((i % 3) * 1.8, (i / 3) * 1.8, 0.0)),
                color = IsoColor(((i % 3) + 1) * 80.0, ((i / 3) + 1) * 80.0, 150.0),
                alpha = if (isLocked) 0.3f else 1f,
                nodeId = "tile_$i",
                onClick = { if (isLocked) locked.remove(i) },   // suppressed right after a long-press
                onLongClick = { locked[i] = true }
            )
        }
    }
}
```

> **Note**
>
Suppression only happens when the hit node has an `onLongClick`. A slow press on a node
without one — or on empty space — still dispatches as a normal tap.

## Fade with Alpha

`alpha` is a per-node opacity multiplier in `0f..1f`. It is plain Compose state, so drive it
from a `Slider`, an `Animatable`, or a vsync loop. `alpha` works on `Shape`, `Path`, `Batch`,
and `CustomNode` alike (a `Batch` shares one alpha across all its shapes).

```kotlin
@Composable
fun AlphaControl() {
    var alpha by remember { mutableStateOf(0.5f) }

    Column(modifier = Modifier.fillMaxSize()) {
        Slider(value = alpha, onValueChange = { alpha = it }, valueRange = 0.05f..1f)

        IsometricScene(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Opaque base for contrast
            Shape(
                geometry = Prism(Point(-3.0, -3.0, -0.1), 6.0, 6.0, 0.1),
                color = IsoColor.DARK_GRAY
            )
            // Controllable opacity
            Shape(
                geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0),
                color = IsoColor.BLUE,
                alpha = alpha,
                testTag = "alpha-box"
            )
        }
    }
}
```

> **Caution**
>
A node whose `alpha < 1f` allocates a new `IsoColor` (via `color.withAlpha(alpha)`) each
frame the alpha changes. That is fine for a handful of fading nodes; for hundreds of
simultaneously-animating nodes, prefer animating a shared value or batching.

## Toggle Detail with a Tap

Tapping the building's walls toggles its roof. The per-node `onClick` replaces the older
pattern of a scene-level `onTap` that has to null-check `event.node`. `If` removes the roof
from the scene graph entirely when hidden — it is not just invisible, it is not depth-sorted.

```kotlin
@Composable
fun InteractiveBuilding() {
    var showRoof by remember { mutableStateOf(true) }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Group {
            // Foundation
            Shape(geometry = Prism(Point.ORIGIN, 4.0, 4.0, 0.3), color = IsoColor.GRAY)
            // Walls — tap to toggle the roof
            Shape(
                geometry = Prism(Point(0.2, 0.2, 0.3), 3.6, 3.6, 2.5),
                color = IsoColor(33, 150, 243),
                onClick = { showRoof = !showRoof }
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

## City Builder — All Props Together

A mini city builder combining every per-node prop: `onClick` selects, `onLongClick` demolishes,
`alpha` dims the selection, `nodeId` gives stable identity, and `testTag` labels nodes for tests.

```kotlin
private data class CitySlot(
    val id: Int,
    val name: String,
    val color: IsoColor,
    val height: Double,
    val position: Point
)

@Composable
fun CityBuilder() {
    var selected by remember { mutableStateOf<Int?>(null) }
    val demolished = remember { mutableStateMapOf<Int, Boolean>() }

    val slots = remember {
        listOf(
            CitySlot(0, "Office", IsoColor.BLUE, 2.5, Point(0.0, 0.0, 0.1)),
            CitySlot(1, "Shop", IsoColor.ORANGE, 1.5, Point(2.0, 0.0, 0.1)),
            CitySlot(2, "Tower", IsoColor.PURPLE, 3.5, Point(0.0, 2.0, 0.1)),
            CitySlot(3, "House", IsoColor.CYAN, 1.0, Point(2.0, 2.0, 0.1)),
        )
    }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        Shape(
            geometry = Prism(Point(-1.0, -1.0, 0.0), 6.0, 6.0, 0.1),
            color = IsoColor.LIGHT_GRAY,
            nodeId = "city-ground"
        )
        slots.forEach { slot ->
            If(demolished[slot.id] != true) {
                val isSelected = selected == slot.id
                Shape(
                    geometry = Prism(slot.position, 1.5, 1.5, slot.height),
                    color = if (isSelected) IsoColor.YELLOW else slot.color,
                    alpha = if (isSelected) 0.8f else 1f,
                    nodeId = "building-${slot.id}",
                    testTag = "city-${slot.name.lowercase()}",
                    onClick = { selected = if (selected == slot.id) null else slot.id },
                    onLongClick = {
                        demolished[slot.id] = true
                        if (selected == slot.id) selected = null
                    }
                )
            }
        }
    }
}
```

## Scene-Level Taps: Background and Coordinates

Per-node `onClick` covers "this shape was tapped." For **empty-space taps** or the **raw screen
coordinates** of a tap, use a scene-level `GestureConfig.onTap` — `event.node` is `null` when the
tap misses every shape. When both are present, the scene-level `onTap` runs first, then the hit
node's `onClick`.

```kotlin
@Composable
fun TapToAdd() {
    val placed = remember { mutableStateListOf<Point>() }

    val gestures = remember {
        GestureConfig(
            onTap = { event ->
                if (event.node == null) {          // background tap → place a new prism
                    val x = (event.x / 80.0).toInt().toDouble()
                    val y = (event.y / 80.0).toInt().toDouble()
                    placed.add(Point(x, y, 0.0))
                }
            }
        )
    }

    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = SceneConfig(gestures = gestures)
    ) {
        Shape(
            geometry = Prism(Point(-5.0, -5.0, -0.1), 10.0, 10.0, 0.1),
            color = IsoColor.LIGHT_GRAY
        )
        ForEach(items = placed.toList(), key = { "${it.x}_${it.y}" }) { pos ->
            Shape(geometry = Prism(pos), color = IsoColor(33, 150, 243))
        }
    }
}
```

## Drag to Pan

Use `CameraState` for viewport panning. When a `CameraState` is provided and no `onDrag`
callback is set, the scene auto-pans on drag. To customize, supply `onDrag` and call
`camera.pan(...)` yourself.

```kotlin
@Composable
fun DragToPan() {
    val camera = remember { CameraState() }

    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = SceneConfig(cameraState = camera)   // built-in drag-to-pan
    ) {
        ForEach((0 until 8).toList(), key = { it }) { x ->
            ForEach((0 until 8).toList(), key = { it }) { y ->
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

For custom drag handling, scale by inverse zoom for a consistent feel:

```kotlin
val gestures = remember {
    GestureConfig(
        onDrag = { event -> camera.pan(event.x / camera.zoom, event.y / camera.zoom) }
    )
}
```
