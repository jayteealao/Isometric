package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * An octahedron shape (8-faced polyhedron)
 */
class Octahedron(origin: Point) : Shape(createPaths(origin)) {

    companion object {
        private fun createPaths(origin: Point): List<Path> {
            val center = origin.translate(0.5, 0.5, 0.5)
            val upperTriangle = Path(
                origin.translate(0.0, 0.0, 0.5),
                origin.translate(0.5, 0.5, 1.0),
                origin.translate(0.0, 1.0, 0.5)
            )
            val lowerTriangle = Path(
                origin.translate(0.0, 0.0, 0.5),
                origin.translate(0.0, 1.0, 0.5),
                origin.translate(0.5, 0.5, 0.0)
            )

            val paths = mutableListOf<Path>()
            for (i in 0 until 4) {
                paths.add(upperTriangle.rotateZ(center, i * PI / 2.0))
                paths.add(lowerTriangle.rotateZ(center, i * PI / 2.0))
            }

            // Scale all paths
            val scale = sqrt(2.0) / 2.0
            return paths.map { it.scale(center, scale, scale, 1.0) }
        }
    }
}
