package io.fabianterhorst.isometric.paths

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A circular path (polygon approximation of a circle)
 */
class Circle(
    origin: Point,
    radius: Double = 1.0,
    vertices: Int = 20
) : Path(createCirclePoints(origin, radius, vertices)) {

    companion object {
        private fun createCirclePoints(origin: Point, radius: Double, vertices: Int): List<Point> {
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
