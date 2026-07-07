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

> **Tip**
>
`GestureConfig.onTap` is a **scene-level** handler: it fires for every tap and hit-tests
the node for you. If you only need to know when a *specific* shape is tapped, attach an
`onClick` directly to that node instead &mdash; no `GestureConfig` is required, because the
scene always installs its pointer-input handler. See
[Per-Node Interactions](interactions.md). When both are present, the scene-level
`onTap` runs first, then the node's `onClick`.

Nodes also handle **double-tap** (`onDoubleClick`) and **long-press** (`onLongClick`); the long-press
timeout is configurable per scene via `GestureConfig.longPressTimeoutMs` (default `500L`). See
[Per-Node Interactions](interactions.md).

### TapEvent

| Property | Type | Description |
|----------|------|-------------|
| `x` | `Double` | Screen X coordinate of the tap |
| `y` | `Double` | Screen Y coordinate of the tap |
| `node` | `IsometricNode?` | The node under the tap point, or `null` if tapping empty space |

### DragEvent

`onDragStart` and `onDrag` both receive a `DragEvent`. The `x`/`y` fields are the **absolute**
pointer position; the `delta` field is the **per-event** movement, present only during `onDrag`.

| Property | Type | Description |
|----------|------|-------------|
| `x` | `Double` | Absolute screen X of the pointer, in pixels. The drag-start position in `onDragStart`; the live position in `onDrag`. |
| `y` | `Double` | Absolute screen Y of the pointer, in pixels. The drag-start position in `onDragStart`; the live position in `onDrag`. |
| `delta` | `DragDelta?` | Per-event pointer translation (`delta.dx` / `delta.dy`, in pixels) — the movement since the previous drag event. Non-`null` in `onDrag`; `null` in `onDragStart`. Accumulate these to track total travel; this is the value camera autopan sums. |

`DragDelta` is a simple `(dx, dy)` pair of `Double` pixel offsets.

## Drag Handling

`GestureConfig` provides three drag callbacks:

- **onDragStart** — fired once when the drag begins (after exceeding the threshold); the `DragEvent` carries the absolute start position in `x`/`y` and `delta == null`
- **onDrag** — fired on each move during the drag; the `DragEvent` carries the live absolute position in `x`/`y` and the per-event movement in `delta`
- **onDragEnd** — fired when the pointer is released

The `dragThreshold` property (default `8f`) controls how many pixels of movement are required before a drag gesture is recognized. This prevents accidental drags during taps. The long-press timeout is configurable through `GestureConfig.longPressTimeoutMs` (default `500L`).

> **Note**
>
The scene consumes pointer events **only when actively handling the gesture** — that is, when at
least one of `GestureConfig.onDrag`, a `CameraState`, or a `NodeDragState` is present and the
pointer is dragging. An otherwise inert scene (no drag handler, no camera, no node-drag state)
lets all pointer events pass through to parent scrollables and other composables unobstructed.
This means you can safely embed an `IsometricScene` without drag handling inside a
`LazyColumn` or `HorizontalPager` &mdash; the host scrollable will receive the gesture.

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
                    // onDrag carries a per-event delta — accumulate it onto the camera.
                    event.delta?.let { camera.pan(it.dx, it.dy) }
                }
            )
        )
    ) {
        Shape(geometry = Prism(Point.ORIGIN), color = shapeColor)
    }
}
```

## Why `onDrag` Is a Delta, Not a Position

The camera autopan accumulates per-event deltas. Each `onDrag` reports how far the pointer moved
*since the previous event* in `event.delta`, and panning sums those movements:
`event.delta?.let { camera.pan(it.dx, it.dy) }`. If `onDrag` instead reported an absolute position,
every call would jump the camera to that coordinate rather than nudge it — the viewport would snap to
the finger instead of following it.

That is why the field split exists: `x`/`y` are absolute (useful in `onDragStart` to record *where* a
drag began, e.g. the origin of a selection rectangle), while `delta` is relative (what you sum to
track travel). The single-node drag helper moves a selected node with the same delta — see the
[Drag & Camera how-to](drag-and-camera.md). Keeping both meanings in distinct fields means
neither callback has to overload one pair of coordinates with two conflicting interpretations.

## Hit Testing Performance

Hit testing uses spatial indexing, giving O(1) lookup performance regardless of scene complexity. This means tap and drag callbacks respond quickly even in scenes with hundreds of shapes.

See [Scene Config reference](../reference/scene-config.md) for the complete `GestureConfig` API.

## Disabling Gestures

Gestures are disabled by default. You can also explicitly pass `GestureConfig.Disabled` to make intent clear:

```kotlin
IsometricScene(
    config = SceneConfig(gestures = GestureConfig.Disabled)
) { ... }
```

## Tile Grid Tap Routing

`TileGrid` provides its own tap routing mechanism separate from `GestureConfig.onTap`. Passing
an `onTileClick` callback to `TileGrid` enables automatic screen-to-tile conversion — no
`GestureConfig` is needed on `IsometricScene`.

```kotlin
IsometricScene(modifier = Modifier.fillMaxSize()) {
    TileGrid(
        width = 10,
        height = 10,
        onTileClick = { coord ->
            // TileCoordinate — no coordinate math required
            selectedTile = coord
        }
    ) { coord ->
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(200, 200, 200))
    }
}
```

This differs from `GestureConfig.onTap` in two ways:

- **Delivers a `TileCoordinate`**, not raw screen coordinates or a hit-tested node.
- **Scoped to grid bounds** — taps outside the grid's `width × height` area are silently ignored.

### Combining with Drag Gestures

Tile tap routing and `GestureConfig` drag callbacks coexist without conflict:

```kotlin
val camera = remember { CameraState() }

IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(
        cameraState = camera,
        gestures = GestureConfig(
            onDrag = { event -> event.delta?.let { camera.pan(it.dx, it.dy) } }
        )
    )
) {
    TileGrid(
        width = 10,
        height = 10,
        onTileClick = { coord -> selectedTile = coord }
    ) { coord ->
        Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(200, 200, 200))
    }
}
```

> **Caution**
>
`GestureConfig.onTap` and `TileGrid`'s `onTileClick` both receive every tap — `onTap` fires
first, then `onTileClick`. If both are active, you will handle the same tap twice. Use one
or the other.

For elevated terrain where the default z = 0 assumption is incorrect, use `GestureConfig.onTap`
with `IsometricEngine.screenToTile()` directly and omit `onTileClick`. See
[Tile Grid — Tap Accuracy with Elevation](tile-grid.md#tap-accuracy-with-elevation), or the
[Hit-Testing Escape Hatches](hit-testing-escape-hatches.md) guide for the low-level path.

## Gesture Limitations

### Hover fires only for mouse and stylus

`Modifier.hoverable` and the `PointerEventType.Enter`/`Exit` events fire **only** for mouse and
stylus input. A touchscreen finger never generates hover events, so a hover affordance shows nothing
under touch — an emulator in touch mode will not trigger it either. Verify hover on a real mouse or
stylus target. (A runnable hover recipe lives in the sample app's hover tab.)

### Trackpad Scale/Pan events are not handled (Compose 1.11+)

Compose Foundation 1.11.0 (April 2026) added trackpad-native `ScaleStart`/`ScaleChange`/`ScaleEnd`
and `PanStart`/`PanMove`/`PanEnd` pointer events, distinct from the touch gestures that
`detectTransformGestures` / `detectDragGestures` recognize. `Modifier.transformable` consumes them
automatically; custom detectors do not. **Isometric does not handle these trackpad events** — the
pinch and drag-to-pan recipes respond to touch, not to trackpad-native gestures. This is noted for
apps targeting desktop or foldable form factors.
