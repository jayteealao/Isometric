package io.github.jayteealao.isometric

/**
 * Discrete 2D tile grid coordinate.
 *
 * Identifies a single cell in an isometric tile grid by its integer column
 * and row. Distinct from [Point] (continuous 3D world space) and [Point2D]
 * (continuous 2D screen space).
 *
 * Negative coordinates are valid — grids are not required to start at (0, 0).
 *
 * @param x Column index (increases right-and-down on screen)
 * @param y Row index (increases left-and-down on screen)
 */
class TileCoordinate(
    val x: Int,
    val y: Int
) {
    /** Returns the coordinate offset by [other]. */
    operator fun plus(other: TileCoordinate): TileCoordinate = TileCoordinate(x + other.x, y + other.y)

    /** Returns the coordinate offset by the negation of [other]. */
    operator fun minus(other: TileCoordinate): TileCoordinate = TileCoordinate(x - other.x, y - other.y)

    /**
     * Returns true if this coordinate falls within a grid of [width] columns
     * and [height] rows anchored at (0, 0).
     *
     * Callers using a non-zero [TileGridConfig.originOffset] are responsible
     * for shifting the check if needed — `isWithin` always tests against the
     * zero-based range `[0, width)` × `[0, height)`.
     */
    fun isWithin(width: Int, height: Int): Boolean = x in 0 until width && y in 0 until height

    /**
     * Converts this tile coordinate to a continuous world [Point] at the
     * grid's (0,0) corner of this cell.
     *
     * @param tileSize World units per tile side (must match the value used in [TileGridConfig])
     * @param elevation World z-coordinate of the tile surface
     */
    fun toPoint(tileSize: Double = 1.0, elevation: Double = 0.0): Point =
        Point(x * tileSize, y * tileSize, elevation)

    override fun equals(other: Any?): Boolean =
        other is TileCoordinate && x == other.x && y == other.y

    override fun hashCode(): Int = 31 * x + y

    override fun toString(): String = "TileCoordinate($x, $y)"

    companion object {
        /** The tile at grid position (0, 0). */
        @JvmField
        val ORIGIN = TileCoordinate(0, 0)
    }
}
