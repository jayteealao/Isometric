package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * A rectangular prism (box) shape
 */
class Prism(
    origin: Point,
    dx: Double = 1.0,
    dy: Double = 1.0,
    dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz)) {

    constructor(origin: Point) : this(origin, 1.0, 1.0, 1.0)

    companion object {
        private fun createPaths(origin: Point, dx: Double, dy: Double, dz: Double): List<Path> {
            val paths = mutableListOf<Path>()

            /* Squares parallel to the x-axis */
            val face1 = Path(
                origin,
                Point(origin.x + dx, origin.y, origin.z),
                Point(origin.x + dx, origin.y, origin.z + dz),
                Point(origin.x, origin.y, origin.z + dz)
            )

            /* Push this face and its opposite */
            paths.add(face1)
            paths.add(face1.reverse().translate(0.0, dy, 0.0))

            /* Square parallel to the y-axis */
            val face2 = Path(
                origin,
                Point(origin.x, origin.y, origin.z + dz),
                Point(origin.x, origin.y + dy, origin.z + dz),
                Point(origin.x, origin.y + dy, origin.z)
            )
            paths.add(face2)
            paths.add(face2.reverse().translate(dx, 0.0, 0.0))

            /* Square parallel to the xy-plane */
            val face3 = Path(
                origin,
                Point(origin.x + dx, origin.y, origin.z),
                Point(origin.x + dx, origin.y + dy, origin.z),
                Point(origin.x, origin.y + dy, origin.z)
            )
            /* This surface is oriented backwards, so we need to reverse the points */
            paths.add(face3.reverse())
            paths.add(face3.translate(0.0, 0.0, dz))

            return paths
        }
    }
}
