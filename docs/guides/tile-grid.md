---
title: Tile Grid
description: Render and interact with an isometric tile grid using TileGrid
sidebar:
  order: 3
---

`TileGrid` renders a regular W×H isometric tile grid and routes tap events to discrete tile
coordinates. It handles rendering and pointer routing — tile state and game logic live in
your app.

## Basic Usage

Pass `width`, `height`, and an `onTileClick` callback. No `GestureConfig` on `IsometricScene`
is needed — tap routing activates automatically when `onTileClick` is provided. The content
lambda receives each tile's `TileCoordinate` and renders in that tile's local coordinate space,
so a `Shape` at `Point.ORIGIN` appears at the tile's world position without any manual offset.

```kotlin
@Composable
fun TileGridExample() {
    var selectedTile by remember { mutableStateOf<TileCoordinate?>(null) }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        TileGrid(
            width = 10,
            height = 10,
            onTileClick = { coord -> selectedTile = coord }
        ) { coord ->
            Shape(
                geometry = Prism(Point.ORIGIN),
                color = if (coord == selectedTile) IsoColor(33, 150, 243) else IsoColor(200, 200, 200)
            )
        }
    }
}
```

## TileCoordinate

`TileCoordinate(x: Int, y: Int)` is the discrete cell identity for a tile. Unlike `Point`
(continuous 3D world space) or `Point2D` (continuous 2D screen space), `TileCoordinate` uses
integer column and row indices.

```kotlin
val coord    = TileCoordinate(3, 4)
val right    = coord + TileCoordinate(1, 0)           // TileCoordinate(4, 4)
val above    = coord - TileCoordinate(0, 1)           // TileCoordinate(3, 3)
val inBounds = coord.isWithin(width = 10, height = 10) // true
val worldPos = coord.toPoint(tileSize = 1.0)          // Point(3.0, 4.0, 0.0)
```

Key properties:

- `plus` / `minus` operators — useful for neighbour lookup
- `isWithin(width, height)` — zero-based bounds check anchored at (0, 0)
- `toPoint(tileSize, elevation)` — converts the cell origin to a world `Point`
- `ORIGIN` constant = `TileCoordinate(0, 0)`
- Negative coordinates are valid; grids need not start at world origin
- Safe as a `Map` or `Set` key — `equals`/`hashCode` are implemented

## TileGridConfig

`TileGridConfig` controls tile size, grid placement, and optional per-tile elevation. All
fields have safe defaults, so the bare `TileGridConfig()` produces a unit-size flat grid at
world origin. Pass a config only when customising these values.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `tileSize` | `Double` | `1.0` | World units per tile side. Must be positive and finite. |
| `originOffset` | `Point` | `Point.ORIGIN` | World position of the grid's (0, 0) corner. |
| `elevation` | `((TileCoordinate) -> Double)?` | `null` | Per-tile z-coordinate function. `null` means all tiles sit at `originOffset.z`. |

Terrain height-map example:

```kotlin
val heights = mapOf(
    TileCoordinate(2, 2) to 1.0,
    TileCoordinate(2, 3) to 1.0,
    TileCoordinate(3, 3) to 2.0,
)

TileGrid(
    width = 6,
    height = 6,
    config = TileGridConfig(elevation = { coord -> heights[coord] ?: 0.0 })
) { coord ->
    val h = heights[coord] ?: 0.0
    Shape(geometry = Prism(Point.ORIGIN, height = 1.0 + h), color = IsoColor(100, 160, 80))
}
```

> **Caution**
>
`onTileClick` assumes a flat z = 0 ground plane for hit-testing. For elevated terrain, taps
near tile edges may be attributed to the wrong tile. See
[Tap Accuracy with Elevation](#tap-accuracy-with-elevation) for the escape hatch.

## Tap Accuracy with Elevation

`onTileClick` always inverse-projects taps to the z = 0 plane. When tiles are elevated, this
can misattribute taps near edges. Use `GestureConfig.onTap` with `screenToTile()` directly,
passing the known surface elevation, and omit `onTileClick`:

```kotlin
@Composable
fun ElevatedTileScene(heights: Map<TileCoordinate, Double>) {
    val engine = LocalIsometricEngine.current
    var size by remember { mutableStateOf(IntSize.Zero) }

    IsometricScene(
        modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
        config = SceneConfig(
            gestures = GestureConfig(
                onTap = { event ->
                    val coord = engine.screenToTile(
                        screenX = event.x,
                        screenY = event.y,
                        viewportWidth = size.width,
                        viewportHeight = size.height,
                        elevation = 0.0  // adjust to the known surface z if needed
                    )
                    if (coord.isWithin(10, 10)) { /* handle */ }
                }
            )
        )
    ) {
        TileGrid(
            width = 10,
            height = 10,
            config = TileGridConfig(elevation = { heights[it] ?: 0.0 })
        ) { coord ->
            Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(100, 160, 80))
        }
    }
}
```

Obtain the engine via `LocalIsometricEngine.current`. See
[Engine — Tile Coordinate Helpers](../reference/engine.md#tile-coordinate-helpers) for full
parameter details on `screenToTile`.

## Positioning the Grid

`originOffset` shifts where grid position (0, 0) appears in world space. To centre an N×N
grid at world origin:

```kotlin
TileGrid(
    width = 10,
    height = 10,
    config = TileGridConfig(originOffset = Point(-5.0, -5.0, 0.0))
) { coord ->
    Shape(geometry = Prism(Point.ORIGIN), color = IsoColor(200, 200, 200))
}
```

## See Also

- [Stack guide](stack.md) — arrange shapes in a 1D line along any world axis
- [Gestures — Tile Grid Tap Routing](gestures.md#tile-grid-tap-routing) — combining tile taps with drag gestures
- [Composables reference — TileGrid](../reference/composables.md#tilegrid)
- [Engine reference — screenToTile](../reference/engine.md#tile-coordinate-helpers)
- [Coordinate System — Tile Coordinates](../getting-started/coordinate-system.md#tile-coordinates)
