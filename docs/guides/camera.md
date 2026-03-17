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

### Constructor

`CameraState` accepts optional initial values:

```kotlin
val camera = remember { CameraState(panX = 100.0, panY = -50.0, zoom = 1.5) }
```

| Param | Type | Default | Description |
|---|---|---|---|
| `panX` | `Double` | `0.0` | Initial horizontal pan offset (must be finite) |
| `panY` | `Double` | `0.0` | Initial vertical pan offset (must be finite) |
| `zoom` | `Double` | `1.0` | Initial zoom level (must be positive and finite) |

## Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `panX` | `Double` | `0.0` | Horizontal pan offset |
| `panY` | `Double` | `0.0` | Vertical pan offset |
| `zoom` | `Double` | `1.0` | Zoom level |

Pan values are in **screen-space pixels**, not world units.

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
