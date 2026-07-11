package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A regular octahedron (8-faced polyhedron) centered in a unit cube.
 *
 * All six vertices lie on a sphere of radius 0.5 about the cube center, giving twelve
 * equal edges. Because the shape stands on a vertex, its footprint is ~0.707 world units
 * across the x- and y-axes and a full 1.0 unit tall on z — it deliberately does not fill
 * the cube in x/y. Use [translate] and [Shape.scale] to reposition or resize it.
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

            // Pull the equatorial square in from the cube corners (radius √2/2) to radius 0.5,
            // matching the poles — this is what makes the octahedron regular (all edges equal).
            val scale = sqrt(2.0) / 2.0
            return paths.map { it.scale(center, scale, scale, 1.0) }
        }
    }
}
