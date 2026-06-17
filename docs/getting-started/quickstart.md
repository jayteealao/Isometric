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

A `Prism` accepts a position and optional width, depth, and height parameters. Stack several shapes to build up a scene:

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

> **Tip**
>
For a single shape, attaching `onClick` directly is simpler than a scene-level handler — no
`GestureConfig` and no hit-test lookup:

```kotlin
@Composable
fun InteractiveShape() {
    var selected by remember { mutableStateOf(false) }
    IsometricScene {
        Shape(
            geometry = Prism(Point.ORIGIN, 2.0, 2.0, 1.0),
            color = if (selected) IsoColor.RED else IsoColor.BLUE,
            onClick = { selected = !selected }
        )
    }
}
```

Use scene-level `onTap` for background taps or raw coordinates; use per-node `onClick` when you
just need "this shape was tapped." See [Per-Node Interactions](../guides/interactions.md).

## Next Steps

Now that you have a working scene, explore further:

- **[Coordinate System](coordinate-system.md)** — understand how isometric coordinates map to the screen.
- **[Shapes](../guides/shapes.md)** — learn about all available geometries: Prism, Pyramid, Cylinder, Octahedron, Stairs, and Knot.
- **[Animation](../guides/animation.md)** — bring your scenes to life with animated transforms and transitions.
- **[Per-Node Interactions](../guides/interactions.md)** — attach `onClick`, `onLongClick`, `alpha`, and stable `nodeId`s to individual shapes.
