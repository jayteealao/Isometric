---
title: Drag & Camera
description: Drag-to-pan, pinch-to-zoom, reset, the drag lifecycle, and tap-to-select-then-drag
sidebar:
  order: 9
---

Task-oriented recipes for moving the viewport and moving a single node. Every snippet below mirrors
a compiled sample in
`app/src/main/kotlin/io/github/jayteealao/isometric/sample/InteractionSamplesActivity.kt`; the
`// Source:` comment names the demo function each one comes from. For *why* `onDrag` carries a delta
rather than an absolute position, see the
[Gestures explanation](gestures.md#why-ondrag-is-a-delta-not-a-position).

## Built-in Drag-to-Pan

Hand a `CameraState` to `SceneConfig.cameraState` and drag-to-pan works with no gesture code of your
own: when no `onDrag` is supplied, the scene's own pointer handler pans the camera.

```kotlin
// Source: InteractionSamplesActivity.kt — CameraControlSample
val cameraState = remember { CameraState() }

// Drag-to-pan fires automatically because cameraState is set and no onDrag is supplied.
IsometricScene(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    config = SceneConfig(cameraState = cameraState)
) {
    Shape(
        geometry = Prism(position = Point(0.0, 0.0, 0.1)),
        color = IsoColor(33.0, 150.0, 243.0)
    )
}
```

Zoom and recenter by calling `CameraState` directly from your own controls:

```kotlin
// Source: InteractionSamplesActivity.kt — CameraControlSample
Button(onClick = { cameraState.zoomBy(1.2) }) { Text("Zoom In") }
Button(onClick = { cameraState.zoomBy(1.0 / 1.2) }) { Text("Zoom Out") }
Button(onClick = { cameraState.reset() }) { Text("Reset") }
```

## Pinch-to-Zoom

Pinch-to-zoom is a recipe, not a built-in: wire `detectTransformGestures` to `cameraState.zoomBy`.
The per-gesture `zoomFactor` is always positive, so it is a valid `zoomBy` factor.

> **Caution**
>
Put `detectTransformGestures` in its **own** `Modifier.pointerInput` block, separate from the
scene's own tap/drag handler. Stacking multiple gesture detectors in a single `pointerInput` lambda
silently dead-codes all but the first.

```kotlin
// Source: InteractionSamplesActivity.kt — PinchZoomRecipeSample
val cameraState = remember { CameraState() }

// The pinch detector lives in a SEPARATE pointerInput from the scene's internal
// tap/drag handler — this modifier is applied before the scene chains its own.
IsometricScene(
    modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .pointerInput(Unit) {
            detectTransformGestures { _, _, zoomFactor, _ ->
                cameraState.zoomBy(zoomFactor.toDouble())
            }
        },
    config = SceneConfig(cameraState = cameraState)
) {
    Shape(geometry = Prism(position = Point(1.0, 1.0, 0.1)), color = IsoColor.BLUE)
}
```

## Drag Lifecycle (onDragStart / onDrag / onDragEnd)

React to each phase of a drag, or raise the drag threshold from its `8f` default. `onDragStart`
carries the **absolute** start position in `x`/`y`; each `onDrag` carries a **per-event** `delta` you
accumulate.

```kotlin
// Source: InteractionSamplesActivity.kt — DragLifecycleSample
IsometricScene(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    config = SceneConfig(
        gestures = GestureConfig(
            dragThreshold = 32f,
            onDragStart = { event ->
                lastEvent = "DRAG_START"
                startX = event.x
                startY = event.y
                accumulatedDx = 0.0
                accumulatedDy = 0.0
                dragEvents = 0
            },
            onDrag = { event ->
                lastEvent = "DRAG"
                // delta is non-null in onDrag; accumulate it to track total travel.
                event.delta?.let { d ->
                    accumulatedDx += d.dx
                    accumulatedDy += d.dy
                    dragEvents++
                }
            },
            onDragEnd = {
                lastEvent = "DRAG_END"
            }
        )
    )
) {
    Shape(
        geometry = Prism(position = Point(-1.0, -1.0, 0.0), width = 8.0, depth = 6.0, height = 0.1),
        color = IsoColor.LIGHT_GRAY
    )
}
```

> **Note**
>
`onDrag` receives a **per-event delta**, not an absolute position — accumulate the deltas to track
total travel. The `delta` field carries movement in **screen pixels**, not world units; the library
converts screen deltas to world coordinates internally when moving nodes. See
[Gestures](gestures.md#why-ondrag-is-a-delta-not-a-position) for the rationale.

## Tap-to-Select Then Drag (the Hero)

Tap a node to select it, drag the selected node to move only that node, drag empty space to pan, and
tap empty space to deselect — all wired by the library. Hand a `rememberNodeDragState()` to
`SceneConfig.nodeDragState`; the scene selects the tapped node, drags it, clamps it to the supplied
`NodeDragBounds`, and leaves background drags to the camera. Read `selectedNodeId` to reflect the
selection in your own UI.

```kotlin
// Source: InteractionSamplesActivity.kt — DragNodeSample
val dragState = rememberNodeDragState(
    bounds = NodeDragBounds(minX = -4.0, maxX = 4.0, minY = -4.0, maxY = 4.0)
)
val cameraState = remember { CameraState() }
val selectedId = dragState.selectedNodeId

IsometricScene(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    config = SceneConfig(
        cameraState = cameraState,
        nodeDragState = dragState
    )
) {
    // Ground slab.
    Shape(
        geometry = Prism(position = Point(-1.0, -1.0, 0.0), width = 8.0, depth = 6.0, height = 0.1),
        color = IsoColor.LIGHT_GRAY
    )
    // Each draggable prism carries a stable nodeId; the selected one is highlighted.
    prisms.forEach { (id, pos) ->
        Shape(
            geometry = Prism(position = pos, width = 1.0, depth = 1.0, height = 1.0),
            color = if (id == selectedId) IsoColor.YELLOW else IsoColor.BLUE,
            nodeId = id
        )
    }
}
```

The `bounds` confine a dragged node to an engine-space box, so it can never be lost off-scene. Omit
`bounds` to use the generous `NodeDragBounds.Default`. Selection is single-node: selecting one prism
deselects any other.

---

See [Camera & Viewport](camera.md) for the full `CameraState` reference, and
[Scene Configuration](../reference/scene-config.md) for the `GestureConfig` parameter table.
