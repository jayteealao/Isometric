---
title: Camera & Viewport
description: Pan and zoom with CameraState
sidebar:
  order: 5
---

## CameraState

`CameraState` controls the viewport pan and zoom. It is backed by Compose snapshot state, so changes automatically trigger recomposition.

```kotlin
val camera = remember { CameraState() }

IsometricScene(
    config = SceneConfig(cameraState = camera)
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

`CameraState` accepts optional initial values for `panX`, `panY`, and `zoom`. All pan values are in **screen-space pixels**, not world units. See [Scene Config reference](../reference/scene-config.md) for the full parameter table.

```kotlin
val camera = remember { CameraState(panX = 100.0, panY = -50.0, zoom = 1.5) }
```

> **Tip**
>
For runnable recipes — built-in drag-to-pan, pinch-to-zoom, `reset()`, the full drag lifecycle, and
the tap-to-select-then-drag hero — see the [Drag & Camera how-to](drag-and-camera.md).

## Methods

### pan(deltaX, deltaY)

Shifts the viewport by the given pixel amounts:

```kotlin
camera.pan(50.0, -30.0) // move right 50px, up 30px
```

### zoomBy(factor)

Multiplies the current zoom level. Values greater than 1 zoom in, values less than 1 zoom out:

```kotlin
camera.zoomBy(1.1)  // 10% zoom in
camera.zoomBy(0.9)  // 10% zoom out
```

### reset()

Restores the camera to its initial state (`panX = 0`, `panY = 0`, `zoom = 1`):

```kotlin
camera.reset()
```

## Drag-to-Pan

Providing a `CameraState` is all you need for drag-to-pan: when `cameraState` is set and no
`GestureConfig.onDrag` is supplied, the scene's built-in pointer handler pans the camera on drag.

```kotlin
val camera = remember { CameraState() }

IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(cameraState = camera)   // built-in drag-to-pan
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

> **Caution**
>
Don't also attach an external `Modifier.pointerInput { detectDragGestures { ... } }` that calls
`camera.pan(...)`. The scene already installs its own pointer handler, so a second one pans twice
per drag. To customize panning, supply `GestureConfig.onDrag` instead and call `camera.pan(...)`
there — that replaces the built-in behavior rather than competing with it.

```kotlin
val camera = remember { CameraState() }

val gestures = remember {
    GestureConfig(
        // onDrag carries a per-event delta; accumulate it (scaled by zoom) onto the camera.
        onDrag = { event -> event.delta?.let { camera.pan(it.dx / camera.zoom, it.dy / camera.zoom) } }
    )
}

IsometricScene(
    config = SceneConfig(cameraState = camera, gestures = gestures)
) {
    Shape(geometry = Prism(Point.ORIGIN))
}
```

## Animated Camera

Smoothly animate the camera to a target position using Compose animation:

```kotlin
@Composable
fun AnimatedCameraScene() {
    val camera = remember { CameraState() }
    var targetPanX by remember { mutableDoubleStateOf(0.0) }
    val animatedPanX by animateDoubleAsState(targetPanX)

    LaunchedEffect(animatedPanX) {
        camera.panX = animatedPanX
    }

    Column {
        Button(onClick = { targetPanX += 100.0 }) { Text("Pan Right") }
        IsometricScene(
            config = SceneConfig(cameraState = camera)
        ) {
            Shape(geometry = Prism(Point.ORIGIN))
        }
    }
}
```
