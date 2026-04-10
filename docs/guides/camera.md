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

## Drag-to-Pan Example

Connect `CameraState` to pointer input for interactive panning:

```kotlin
val camera = remember { CameraState() }

IsometricScene(
    modifier = Modifier.pointerInput(Unit) {
        detectDragGestures { _, dragAmount ->
            camera.pan(dragAmount.x.toDouble(), dragAmount.y.toDouble())
        }
    },
    config = SceneConfig(cameraState = camera)
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
