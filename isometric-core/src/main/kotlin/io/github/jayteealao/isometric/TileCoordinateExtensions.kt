package io.github.jayteealao.isometric

import kotlin.math.floor

/**
 * Converts a continuous world [Point] to the [TileCoordinate] of the tile
 * cell that contains it.
 *
 * Uses floor division so that points within [tileSize] of a grid line map
 * to the correct cell, including negative coordinates.
 *
 * @param tileSize World units per tile side (default 1.0, must be positive)
 * @param originOffset World position of the grid's (0,0) corner (default [Point.ORIGIN])
 */
fun Point.toTileCoordinate(
    tileSize: Double = 1.0,
    originOffset: Point = Point.ORIGIN
): TileCoordinate {
    require(tileSize.isFinite()) { "tileSize must be finite, got $tileSize" }
    require(tileSize > 0.0) { "tileSize must be positive, got $tileSize" }
    return TileCoordinate(
        x = floor((x - originOffset.x) / tileSize).toInt(),
        y = floor((y - originOffset.y) / tileSize).toInt()
    )
}

/**
 * Returns the [TileCoordinate] of the tile at the given screen position on
 * the specified z-plane.
 *
 * Chains [screenToWorld] with [toTileCoordinate]. The [elevation] parameter
 * specifies which horizontal z-plane to intersect during inverse projection —
 * for flat tile grids this should be 0.0 (the default). For elevated terrain,
 * pass the surface height of the expected tile layer.
 *
 * For complex terrain where elevation varies per tile, use [screenToWorld]
 * directly and call [toTileCoordinate] with the appropriate elevation after
 * determining the layer through other means.
 *
 * @param screenX Screen x-coordinate of the tap (px)
 * @param screenY Screen y-coordinate of the tap (px)
 * @param viewportWidth Width of the scene viewport (px)
 * @param viewportHeight Height of the scene viewport (px)
 * @param tileSize World units per tile side (default 1.0)
 * @param elevation Z-plane to intersect during inverse projection (default 0.0)
 * @param originOffset World position of the grid's (0,0) corner (default [Point.ORIGIN])
 */
fun IsometricEngine.screenToTile(
    screenX: Double,
    screenY: Double,
    viewportWidth: Int,
    viewportHeight: Int,
    tileSize: Double = 1.0,
    elevation: Double = 0.0,
    originOffset: Point = Point.ORIGIN
): TileCoordinate {
    val world = screenToWorld(
        screenPoint = Point2D(screenX, screenY),
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
        z = elevation
    )
    return world.toTileCoordinate(tileSize = tileSize, originOffset = originOffset)
}
