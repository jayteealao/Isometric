package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import kotlin.math.PI

/**
 * A pyramid shape
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
