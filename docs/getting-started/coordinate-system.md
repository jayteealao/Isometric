---
title: Coordinate System
description: Understanding isometric coordinates, projection, and depth sorting
sidebar:
  order: 3
---

Isometric rendering maps three-dimensional coordinates onto a flat two-dimensional surface without perspective distortion. This page explains how that mapping works in the Isometric library.

## The Three Axes

In isometric space, every position is described by three axes:

- **X** — runs to the right and down on screen.
- **Y** — runs to the left and down on screen.
- **Z** — runs straight up on screen.

All positions are specified as world-space `Double` values, not pixels. The library converts world coordinates to screen pixels during rendering.

## Projection Angle

The default isometric angle is **30 degrees** (π/6 radians). This is the standard isometric projection used in engineering drawings and most isometric games. At this angle, the X and Y axes are each tilted 30 degrees from horizontal.

## Projection Formulas

The library converts a 3D point `(x, y, z)` to 2D screen coordinates `(screenX, screenY)` using the following formulas:

```
screenX = originX + x · scale · cos(angle) + y · scale · cos(π - angle)
screenY = originY - x · scale · sin(angle) - y · scale · sin(angle) - z · scale
```

Where:

- `originX, originY` is the projection origin on screen (center-bottom of the viewport by default).
- `scale` is the number of pixels per world unit (default: **70**).
- `angle` is the isometric angle (default: **π/6**).

Because `cos(π - angle) = -cos(angle)`, the X formula simplifies to:

```
screenX = originX + (x - y) · scale · cos(angle)
```

And because `sin(π - angle) = sin(angle)`, the Y formula simplifies to:

```
screenY = originY - (x + y) · scale · sin(angle) - z · scale
```

## Origin Placement

The projection origin maps to the **center-bottom** of the viewport. This means `Point(0, 0, 0)` appears at the horizontal center of the canvas, near the bottom. Shapes with positive Z values rise upward from there.

## Scale

The default scale is **70 pixels per world unit**. A `Prism` with width 1.0 occupies roughly 70 pixels along each isometric axis. You can adjust the scale in your scene configuration to zoom in or out.

## Depth Sorting

Isometric scenes have no true perspective, so the library must decide which shapes to draw on top of others. It uses the following depth formula:

```
depth = x + y - 2 · z
```

- **Higher x or y** values push a shape closer to the viewer (drawn on top).
- **Higher z** values push a shape further from the viewer (drawn behind).

This means a shape sitting at ground level in the foreground will correctly overlap a taller shape behind it. The library sorts all shapes by depth automatically before rendering.

## Point.ORIGIN

`Point.ORIGIN` is a convenience constant equal to `Point(0.0, 0.0, 0.0)`. Use it anywhere you need the world origin:

```kotlin
Shape(
    geometry = Prism(Point.ORIGIN, 2.0, 2.0, 1.0),
    color = IsoColor(33, 150, 243)
)
```

## Coordinate Conventions

A few rules to keep in mind:

- **Positions are always in world-space doubles**, not pixel values. The library handles the conversion.
- **Positive Z is up.** A shape at `z = 3.0` appears higher on screen than a shape at `z = 0.0`.
- **X and Y are symmetric.** Swapping a shape's X and Y values mirrors it across the vertical center line.
- When placing shapes relative to each other, think in world units. A prism at `Point(2.0, 0.0, 0.0)` is exactly 2 world units along the X axis from the origin.

## Tile Coordinates

For tile-grid scenarios the library provides a second coordinate type: `TileCoordinate`. Where
`Point` uses continuous `Double` values in 3D world space, `TileCoordinate` uses discrete `Int`
values for grid cells.

```
TileCoordinate(x: Int, y: Int)   — discrete column/row in the tile grid
Point(x, y, z: Double)           — continuous position in 3D world space
```

The relationship is controlled by `TileGridConfig.tileSize` (world units per tile side). A
tile at `TileCoordinate(3, 5)` with `tileSize = 1.0` occupies the world-space rectangle
`[3.0, 4.0) × [5.0, 6.0)` in the XY plane.

`TileCoordinate` axis directions follow the world axes:

- **x** — increases right-and-forward on screen (same as world X).
- **y** — increases left-and-forward on screen (same as world Y).

`TileCoordinate.ORIGIN` is the tile at (0, 0) — the grid's world origin corner.

For more on working with tile grids, see the [Tile Grid guide](../guides/tile-grid.md).
