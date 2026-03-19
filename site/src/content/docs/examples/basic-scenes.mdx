---
title: Basic Scenes
description: Copy-paste recipes for common isometric scenes
sidebar:
  order: 1
---

### Simple Cube

A single colored cube at the origin.

```kotlin
@Composable
fun SimpleCube() {
    IsometricScene {
        Shape(
            geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

### Building with Platform

A building on a flat platform with a pyramid roof.

```kotlin
@Composable
fun Building() {
    IsometricScene {
        // Platform
        Shape(
            geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 0.5),
            color = IsoColor(200, 200, 200)
        )
        // Building
        Shape(
            geometry = Prism(Point(0.5, 0.5, 0.5), 3.0, 3.0, 3.0),
            color = IsoColor(33, 150, 243)
        )
        // Roof
        Shape(
            geometry = Pyramid(Point(0.5, 0.5, 3.5), 3.0, 3.0, 1.5),
            color = IsoColor(160, 60, 50)
        )
    }
}
```

### Grid of Shapes

A 3x3 grid of cubes with varying colors.

```kotlin
@Composable
fun ShapeGrid() {
    IsometricScene {
        ForEach((0 until 3).toList()) { x ->
            ForEach((0 until 3).toList()) { y ->
                Shape(
                    geometry = Prism(Point(x * 2.0, y * 2.0, 0.0)),
                    color = IsoColor(
                        (50 + x * 70).coerceAtMost(255),
                        (50 + y * 70).coerceAtMost(255),
                        150
                    )
                )
            }
        }
    }
}
```

### Staircase with Landing

A staircase shape with a landing platform at the top.

```kotlin
@Composable
fun StaircaseScene() {
    IsometricScene {
        Shape(
            geometry = Stairs(Point.ORIGIN, 10),
            color = IsoColor(33, 150, 243)
        )
        // Landing platform at the top
        Shape(
            geometry = Prism(Point(0.0, 1.0, 1.0), 1.0, 2.0, 0.1),
            color = IsoColor(50, 160, 60)
        )
    }
}
```

### Hierarchical Scene with Groups

Using Group to organize related shapes with shared transforms.

```kotlin
@Composable
fun GroupedScene() {
    IsometricScene {
        Group(position = Point(0.0, 0.0, 0.0)) {
            // Base
            Shape(
                geometry = Prism(Point.ORIGIN, 3.0, 3.0, 0.5),
                color = IsoColor(100, 100, 100)
            )
            // Column
            Shape(
                geometry = Cylinder(Point(1.0, 1.0, 0.5), 0.3, 2.0),
                color = IsoColor(180, 180, 0)
            )
            // Cap
            Shape(
                geometry = Prism(Point(0.5, 0.5, 2.5), 2.0, 2.0, 0.3),
                color = IsoColor(100, 100, 100)
            )
        }
    }
}
```
