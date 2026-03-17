package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * An octahedron (8-faced polyhedron) inscribed in a unit cube.
 *
 * The octahedron has a fixed size of approximately 1 world unit across each axis.
 * Use [translate] and [Shape.scale] to reposition or resize it.
 *
 * @param position The origin corner of the bounding cube (default [Point.ORIGIN])
 */
class Octahedron(val position: Point = Point.ORIGIN) : Shape(createPaths(position)) {

    override fun translate(dx: Double, dy: Double, dz: Double): Octahedron =
        Octahedron(position.translate(dx, dy, dz))

    companion object {
        private fun createPaths(position: Point): List<Path> {
            val center = position.translate(0.5, 0.5, 0.5)
            val upperTriangle = Path(
                position.translate(0.0, 0.0, 0.5),
                position.translate(0.5, 0.5, 1.0),
                position.translate(0.0, 1.0, 0.5)
            )
            val lowerTriangle = Path(
                position.translate(0.0, 0.0, 0.5),
                position.translate(0.0, 1.0, 0.5),
                position.translate(0.5, 0.5, 0.0)
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
