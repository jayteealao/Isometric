package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import kotlin.math.PI

/**
 * A pyramid shape with a rectangular base tapering to an apex.
 *
 * The base is defined by [position], [width], and [depth], and the apex is centered
 * above the base at the given [height].
 *
 * @param position The minimum-corner origin point of the base (default [Point.ORIGIN])
 * @param width The extent of the base along the x-axis (must be positive, default 1.0)
 * @param depth The extent of the base along the y-axis (must be positive, default 1.0)
 * @param height The height of the apex above the base along the z-axis (must be positive, default 1.0)
 */
class Pyramid @JvmOverloads constructor(
    val position: Point = Point.ORIGIN,
    val width: Double = 1.0,
    val depth: Double = 1.0,
    val height: Double = 1.0
) : Shape(createPaths(position, width, depth, height)) {
    init {
        require(width > 0.0) { "Pyramid width must be positive, got $width" }
        require(depth > 0.0) { "Pyramid depth must be positive, got $depth" }
        require(height > 0.0) { "Pyramid height must be positive, got $height" }
    }

    override fun translate(dx: Double, dy: Double, dz: Double): Pyramid =
        Pyramid(position.translate(dx, dy, dz), width, depth, height)

    companion object {
        private fun createPaths(position: Point, width: Double, depth: Double, height: Double): List<Path> {
            val paths = mutableListOf<Path>()
            val center = position.translate(width / 2.0, depth / 2.0, 0.0)

            /* Path parallel to the x-axis */
            val face1 = Path(
                position,
                Point(position.x + width, position.y, position.z),
                Point(position.x + width / 2.0, position.y + depth / 2.0, position.z + height)
            )

            /* Push the face, and its opposite face, by rotating around the Z-axis */
            paths.add(face1)
            paths.add(face1.rotateZ(center, PI))

            /* Path parallel to the y-axis */
            val face2 = Path(
                position,
                Point(position.x + width / 2.0, position.y + depth / 2.0, position.z + height),
                Point(position.x, position.y + depth, position.z)
            )
            paths.add(face2)
            paths.add(face2.rotateZ(center, PI))

            return paths
        }
    }
}
