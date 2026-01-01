package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * A staircase shape
 */
class Stairs(origin: Point, stepCount: Int) : Shape(createPaths(origin, stepCount)) {

    companion object {
        private fun createPaths(origin: Point, stepCount: Int): List<Path> {
            val paths = mutableListOf<Path>()
            val zigzagPoints = mutableListOf<Point>()

            zigzagPoints.add(origin)

            for (i in 0 until stepCount) {
                val stepCorner = origin.translate(0.0, i / stepCount.toDouble(), (i + 1) / stepCount.toDouble())

                // Vertical face
                paths.add(
                    Path(
                        stepCorner,
                        stepCorner.translate(0.0, 0.0, -1.0 / stepCount),
                        stepCorner.translate(1.0, 0.0, -1.0 / stepCount),
                        stepCorner.translate(1.0, 0.0, 0.0)
                    )
                )
                zigzagPoints.add(stepCorner)

                // Horizontal face
                paths.add(
                    Path(
                        stepCorner,
                        stepCorner.translate(1.0, 0.0, 0.0),
                        stepCorner.translate(1.0, 1.0 / stepCount, 0.0),
                        stepCorner.translate(0.0, 1.0 / stepCount, 0.0)
                    )
                )
                zigzagPoints.add(stepCorner.translate(0.0, 1.0 / stepCount, 0.0))
            }

            zigzagPoints.add(origin.translate(0.0, 1.0, 0.0))

            // Zigzag side face
            paths.add(Path(zigzagPoints))
            paths.add(Path(zigzagPoints.reversed()).translate(1.0, 0.0, 0.0))

            return paths
        }
    }
}
