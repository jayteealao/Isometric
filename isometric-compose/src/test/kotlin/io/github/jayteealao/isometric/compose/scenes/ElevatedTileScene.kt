@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/** Node id of the raised tile. */
const val ELEVATED_TILE_ID = "elevated-tile"

/**
 * World z of the raised tile's *surface* — the height a `screenToTile` query must intersect to
 * recover the correct cell. Passing the default `elevation = 0.0` here instead would land on the
 * ground plane and report the wrong tile; that gap is the hazard this fixture pins.
 */
const val ELEVATED_TILE_Z = 2.0

/** World centre of the raised tile's surface, on the `z = `[ELEVATED_TILE_Z] plane. */
val ELEVATED_TILE_CENTER = Point(2.5, 2.5, ELEVATED_TILE_Z)

/** The cell [ELEVATED_TILE_CENTER] falls in at `tileSize = 1.0` (`floor(2.5) = 2`). */
val EXPECTED_ELEVATED_TILE = TileCoordinate(2, 2)

/** Thin floor slab spanning the grid. */
val ELEVATED_GROUND_PRISM = Prism(Point(-1.0, -1.0, 0.0), 8.0, 8.0, 0.1)

/** The raised tile: a unit footprint at `(2, 2)` whose base sits at [ELEVATED_TILE_Z]. */
val ELEVATED_TILE_PRISM = Prism(Point(2.0, 2.0, ELEVATED_TILE_Z), 1.0, 1.0, 0.5)

/**
 * A ground plane plus a single tile raised to `z = `[ELEVATED_TILE_Z]. Mapping a tap on the raised
 * tile back to its cell requires intersecting the inverse projection with the *surface* z-plane,
 * not the ground — the `screenToTile(elevation = ELEVATED_TILE_Z)` escape hatch this fixture
 * demonstrates.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `ElevatedTileSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.ElevatedTileScene() {
    Shape(geometry = ELEVATED_GROUND_PRISM, color = IsoColor.LIGHT_GRAY)
    Shape(geometry = ELEVATED_TILE_PRISM, color = IsoColor.ORANGE, nodeId = ELEVATED_TILE_ID)
}
