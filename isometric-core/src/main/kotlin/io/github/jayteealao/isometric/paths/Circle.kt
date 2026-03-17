package io.github.jayteealao.isometric.paths

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A circular [Path] approximated by a regular polygon with [vertices] sides.
 *
 * Higher [vertices] values produce smoother circles at the cost of more geometry.
 * A minimum of 3 vertices is required; 20 (the default) is visually smooth for
 * most use cases.
 *
 * @param origin Center point of the circle in world space.
 * @param radius Radius of the circle in world units. Must be positive.
 * @param vertices Number of sides in the polygon approximation. Must be >= 3.
 */
class Circle(
    origin: Point,
    radius: Double = 1.0,
    vertices: Int = 20
) : Path(createCirclePoints(origin, radius, vertices)) {
    companion object {
        private fun createCirclePoints(origin: Point, radius: Double, vertices: Int): List<Point> {
            require(radius > 0.0) { "Circle radius must be positive, got $radius" }
            require(vertices >= 3) { "Circle needs at least 3 vertices, got $vertices" }

            val points = mutableListOf<Point>()
            for (i in 0 until vertices) {
                points.add(
                    Point(
                        (radius * cos(i * 2 * PI / vertices)) + origin.x,
                        (radius * sin(i * 2 * PI / vertices)) + origin.y,
                        origin.z
                    )
                )
            }
            return points
        }
    }
}
