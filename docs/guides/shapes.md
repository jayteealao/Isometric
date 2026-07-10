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
| Prism | ![Prism](/screenshots/shape-prism.png) | `Prism(position, width=1, depth=1, height=1)` | Rectangular box |
| Pyramid | ![Pyramid](/screenshots/shape-pyramid.png) | `Pyramid(position, width=1, depth=1, height=1)` | Pyramid with solid base face at z = 0 |
| Cylinder | ![Cylinder](/screenshots/shape-cylinder.png) | `Cylinder(position, radius=1, height=1, vertices=20)` | `vertices` controls smoothness |
| Octahedron | ![Octahedron](/screenshots/shape-octahedron.png) | `Octahedron(position)` | Fixed unit size |
| Stairs | ![Stairs](/screenshots/shape-stairs.png) | `Stairs(position, stepCount)` | `stepCount` is required |
| Knot | ![Knot](/screenshots/shape-knot.png) | `Knot(position)` | `@ExperimentalIsometricApi`, known depth-sorting issues |

> **Note**
>
The `Pyramid` shape is **solid** — it includes a base face at `z = 0`. When the pyramid rests on
a surface (e.g., on top of a `Prism`), the base face is removed by the renderer's back-face
culling (it faces away from the viewer). When floated or rendered with culling disabled, the base
renders as a visible bottom face.

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

`Shape` also carries the per-node interaction and identity props — **alpha** (opacity
multiplier in `0f..1f`), **onClick**, **onLongClick**, **onDoubleClick**, **testTag**, and
**nodeId** — plus **rotationOrigin**/**scaleOrigin** to set the pivot for rotation and
scaling (when left `null`, the shape's natural pivot is used):

```kotlin
Shape(
    geometry = Prism(Point.ORIGIN),
    color = IsoColor.BLUE,
    alpha = 0.6f,
    onClick = { /* handle a tap on this shape */ },
    nodeId = "hero-prism"
)
```

See [Per-Node Interactions](interactions.md) and the
[Composables Reference](../reference/composables.md#shape) for the complete parameter list.

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

> **Note**
>
All three rotate functions — `rotateX`, `rotateY`, and `rotateZ` — use the **right-handed
counter-clockwise convention**: a positive angle rotates counter-clockwise when viewed from the
positive end of the axis toward the origin. This is the standard mathematical convention.

**Migration note:** If your project was built against a version where `rotateX`/`rotateY` rotated
clockwise, negate the angle to preserve the old visual output:

```kotlin
// Before (old CW behavior)
shape.rotateX(origin, angle)

// After (CCW right-handed convention)
shape.rotateX(origin, -angle)  // negate to restore old visual
```

`rotateZ` has always used the CCW convention and is unchanged.

## Extruding 2D Paths

`Shape.extrude` takes a 2D `Path` and lifts it into a 3D solid. See the [Custom Shapes guide](custom-shapes.md) for the full extrusion walkthrough and examples.
