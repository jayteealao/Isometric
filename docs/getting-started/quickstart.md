---
title: Quick Start
description: Build your first isometric scene in 5 minutes
sidebar:
  order: 2
---

This guide walks you through creating your first isometric scene with Isometric and Jetpack Compose.

## Step 1: Add the Dependency

Make sure you have Isometric set up in your project. See the [Installation](installation.md) guide for details.

## Step 2: Create Your First Scene

The main entry point is `IsometricScene`. Inside it, you place `Shape` composables with a geometry and a color. Here is the simplest possible scene — a single prism (box):

```kotlin
@Composable
fun MyIsometricScene() {
    IsometricScene {
        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0)),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

> **Note**
>
Isometric uses `IsoColor` for colors, not `androidx.compose.ui.graphics.Color`. `IsoColor` takes numeric values (0-255).

## Step 3: Add More Shapes

A `Prism` accepts a position and optional width, length, and height parameters. Stack several shapes to build up a scene:

```kotlin
@Composable
fun MyIsometricScene() {
    IsometricScene {
        Shape(
            geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0),
            color = IsoColor(50, 160, 60)
        )
        Shape(
            geometry = Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0),
            color = IsoColor(180, 0, 180)
        )
        Shape(
            geometry = Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

The library handles depth sorting automatically, so shapes closer to the viewer are drawn on top.

## Step 4: Use Group for Transforms

`Group` lets you position a collection of shapes together. All children inherit the group's position offset:

```kotlin
@Composable
fun MyIsometricScene() {
    IsometricScene {
        Group(position = Point(0.0, 0.0, 0.0)) {
            Shape(geometry = Prism(Point.ORIGIN, 3.0, 3.0, 1.0))
            Shape(
                geometry = Prism(Point(1.0, 1.0, 1.0)),
                color = IsoColor(160, 60, 50)
            )
        }
    }
}
```

## Step 5: Add Interaction

You can respond to tap events on the scene using `SceneConfig` and `GestureConfig`. The tap event tells you which node was hit:

```kotlin
@Composable
fun InteractiveScene() {
    var selected by remember { mutableStateOf<String?>(null) }

    IsometricScene(
        config = SceneConfig(
            gestures = GestureConfig(
                onTap = { event ->
                    selected = event.node?.nodeId
                }
            )
        )
    ) {
        Shape(
            geometry = Prism(Point.ORIGIN, 2.0, 2.0, 1.0),
            color = if (selected != null) IsoColor.RED else IsoColor.BLUE
        )
    }
}
```

## Next Steps

Now that you have a working scene, explore further:

- **[Coordinate System](coordinate-system.md)** — understand how isometric coordinates map to the screen.
- **[Shapes](../guides/shapes.md)** — learn about all available geometries: Prism, Pyramid, Cylinder, Octahedron, Stairs, and Knot.
- **[Animation](../guides/animation.md)** — bring your scenes to life with animated transforms and transitions.
