package io.github.jayteealao.isometric

/**
 * Configuration for a [TileGrid][io.github.jayteealao.isometric.compose.runtime.TileGrid] composable.
 *
 * All fields have safe defaults: a 1-unit tile size, grid origin at world
 * origin, and a flat ground plane (no per-tile elevation). Pass a
 * [TileGridConfig] only when customizing these values.
 *
 * @param tileSize World units per tile side. Must be positive and finite. Default 1.0.
 * @param originOffset World position of the grid's (0, 0) corner. Default [Point.ORIGIN].
 * @param elevation Optional per-tile elevation function. Returns the z-coordinate of the
 *   tile surface for the given [TileCoordinate]. When null, all tiles sit at
 *   [originOffset].z. Use for terrain height maps.
 *
 * **Note:** [elevation] is excluded from [equals] and [hashCode]. Two configs with the
 * same [tileSize] and [originOffset] compare equal regardless of their elevation functions.
 * This is intentional — lambda literals do not have stable identity across recompositions,
 * so including them would cause spurious inequality and unnecessary gesture-hub
 * re-registration on every recomposition.
 */
class TileGridConfig(
    val tileSize: Double = 1.0,
    val originOffset: Point = Point.ORIGIN,
    val elevation: ((TileCoordinate) -> Double)? = null
) {
    init {
        require(tileSize.isFinite()) { "tileSize must be finite, got $tileSize" }
        require(tileSize > 0.0) { "tileSize must be positive, got $tileSize" }
    }

    // elevation is a function — excluded from equals/hashCode (function identity
    // is unstable across recompositions when expressed as a lambda literal).
    override fun equals(other: Any?): Boolean =
        other is TileGridConfig &&
            tileSize == other.tileSize &&
            originOffset == other.originOffset

    override fun hashCode(): Int {
        var result = tileSize.hashCode()
        result = 31 * result + originOffset.hashCode()
        return result
    }

    override fun toString(): String =
        "TileGridConfig(tileSize=$tileSize, originOffset=$originOffset, " +
            "elevation=${if (elevation != null) "<function>" else "null"})"
}
