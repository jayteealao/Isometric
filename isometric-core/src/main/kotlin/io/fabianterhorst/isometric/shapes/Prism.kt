package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * A rectangular prism (box) shape
 */
class Prism(
    val position: Point = Point.ORIGIN,
    val width: Double = 1.0,
    val depth: Double = 1.0,
    val height: Double = 1.0
) : Shape(createPaths(position, width, depth, height)) {
    init {
        require(width > 0.0) { "Prism width must be positive, got $width" }
        require(depth > 0.0) { "Prism depth must be positive, got $depth" }
        require(height > 0.0) { "Prism height must be positive, got $height" }
    }

    override fun translate(dx: Double, dy: Double, dz: Double): Prism =
        Prism(position.translate(dx, dy, dz), width, depth, height)

    companion object {
        private fun createPaths(position: Point, width: Double, depth: Double, height: Double): List<Path> {
            val paths = mutableListOf<Path>()

            /* Squares parallel to the x-axis */
            val face1 = Path(
                position,
                Point(position.x + width, position.y, position.z),
                Point(position.x + width, position.y, position.z + height),
                Point(position.x, position.y, position.z + height)
            )

            /* Push this face and its opposite */
            paths.add(face1)
            paths.add(face1.reverse().translate(0.0, depth, 0.0))

            /* Square parallel to the y-axis */
            val face2 = Path(
                position,
                Point(position.x, position.y, position.z + height),
                Point(position.x, position.y + depth, position.z + height),
                Point(position.x, position.y + depth, position.z)
            )
            paths.add(face2)
            paths.add(face2.reverse().translate(width, 0.0, 0.0))

            /* Square parallel to the xy-plane */
            val face3 = Path(
                position,
                Point(position.x + width, position.y, position.z),
                Point(position.x + width, position.y + depth, position.z),
                Point(position.x, position.y + depth, position.z)
            )
            /* This surface is oriented backwards, so we need to reverse the points */
            paths.add(face3.reverse())
            paths.add(face3.translate(0.0, 0.0, height))

            return paths
        }
    }
}
