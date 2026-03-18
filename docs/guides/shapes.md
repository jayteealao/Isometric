---
title: Shapes
description: Built-in shapes, transforms, and custom geometry
sidebar:
  order: 1
---

## Shape Catalog

Isometric ships with six built-in shape geometries. Each constructor takes a `position: Point` as its first argument.

| Shape | Preview | Constructor | Notes |
|-------|---------|-------------|-------|
| Prism | ![Prism](../screenshots/shape-prism.png.md) | `Prism(position, width=1, depth=1, height=1)` | Rectangular box |
| Pyramid | ![Pyramid](../screenshots/shape-pyramid.png.md) | `Pyramid(position, width=1, depth=1, height=1)` | Pyramid |
| Cylinder | ![Cylinder](../screenshots/shape-cylinder.png.md) | `Cylinder(position, radius=1, height=1, vertices=20)` | `vertices` controls smoothness |
| Octahedron | ![Octahedron](../screenshots/shape-octahedron.png.md) | `Octahedron(position)` | Fixed unit size |
| Stairs | ![Stairs](../screenshots/shape-stairs.png.md) | `Stairs(position, stepCount)` | `stepCount` is required |
| Knot | ![Knot](../screenshots/shape-knot.png.md) | `Knot(position)` | `@ExperimentalIsometricApi`, known depth-sorting issues |

## Using the Shape Composable

The `Shape` composable renders a geometry inside an `IsometricScene`:

```kotlin
Shape(
    geometry = Prism(Point.ORIGIN, width = 2.0, depth = 1.0, height = 1.5),
    color = IsoColor(33, 150, 243),
    position = Point(0.0, 0.0, 0.0),
    rotation = 0.0,
    scale = 1.0,
    visible = true
)
```

- **geometry** — one of the built-in shapes or a custom `Shape`
- **color** — an `IsoColor` value (defaults to `LocalDefaultColor`)
- **position** — world-space offset applied after geometry construction
- **rotation** — rotation angle in radians
- **scale** — uniform scale factor
- **visible** — toggle rendering without removing the node from the tree

## Transform Operations

All transforms return a new `Shape` instance (shapes are immutable).

### translate

Moves a shape by the given deltas:

```kotlin
val box = Prism(Point.ORIGIN)
val moved = box.translate(2.0, 0.0, 1.0) // shift right and up
```

### scale

Scales relative to an origin point:

```kotlin
val box = Prism(Point.ORIGIN)
val scaled = box.scale(Point.ORIGIN, 2.0, 1.0, 0.5) // stretch X, compress Z
```

### rotateZ

Rotates around the Z axis (vertical in isometric view). This is the most common rotation. `rotateX` and `rotateY` are also available.

```kotlin
val box = Prism(Point.ORIGIN)
val rotated = box.rotateZ(Point(0.5, 0.5, 0.0), Math.PI / 4) // 45 degrees
```

## Extruding 2D Paths

`Shape.extrude` takes a 2D `Path` and lifts it into a 3D solid by the given height.

**Before extrusion** — a flat triangle path:

![Before extrude](../screenshots/extrude-before.png.md)

**After extrusion** — a triangular prism:

![After extrude](../screenshots/extrude-after.png.md)

```kotlin
val trianglePath = io.fabianterhorst.isometric.Path(
    Point(0.0, 0.0, 0.0),
    Point(2.0, 0.0, 0.0),
    Point(2.0, 2.0, 0.0)
)
val solid = Shape.extrude(trianglePath, height = 1.0)

// Render it
Shape(geometry = solid, color = IsoColor.RED)
```
