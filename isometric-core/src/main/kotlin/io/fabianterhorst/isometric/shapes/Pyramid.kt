package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import kotlin.math.PI

/**
 * A pyramid shape
 */
class Pyramid(
    origin: Point,
    dx: Double = 1.0,
    dy: Double = 1.0,
    dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz)) {

    constructor(origin: Point) : this(origin, 1.0, 1.0, 1.0)

    companion object {
        private fun createPaths(origin: Point, dx: Double, dy: Double, dz: Double): List<Path> {
            val paths = mutableListOf<Path>()
            val center = origin.translate(dx / 2.0, dy / 2.0, 0.0)

            /* Path parallel to the x-axis */
            val face1 = Path(
                origin,
                Point(origin.x + dx, origin.y, origin.z),
                Point(origin.x + dx / 2.0, origin.y + dy / 2.0, origin.z + dz)
            )

            /* Push the face, and its opposite face, by rotating around the Z-axis */
            paths.add(face1)
            paths.add(face1.rotateZ(center, PI))

            /* Path parallel to the y-axis */
            val face2 = Path(
                origin,
                Point(origin.x + dx / 2.0, origin.y + dy / 2.0, origin.z + dz),
                Point(origin.x, origin.y + dy, origin.z)
            )
            paths.add(face2)
            paths.add(face2.rotateZ(center, PI))

            return paths
        }
    }
}
