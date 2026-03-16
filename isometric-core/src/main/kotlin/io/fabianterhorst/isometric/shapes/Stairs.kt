package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * A staircase shape whose steps are distributed within a 1x1x1 bounding box.
 *
 * Each step occupies `1/stepCount` of the total depth (y-axis) and height (z-axis),
 * while the full width (x-axis) is always 1 unit. Use [translate] and [Shape.scale]
 * to reposition or resize the staircase.
 *
 * @param position The origin corner of the staircase bounding box (default [Point.ORIGIN])
 * @param stepCount The number of steps (must be at least 1)
 */
class Stairs @JvmOverloads constructor(
    val position: Point = Point.ORIGIN,
    val stepCount: Int
) : Shape(createPaths(position, stepCount)) {
    init {
        require(stepCount >= 1) { "Stairs needs at least 1 step, got $stepCount" }
    }

    override fun translate(dx: Double, dy: Double, dz: Double): Stairs =
        Stairs(position.translate(dx, dy, dz), stepCount)

    companion object {
        private fun createPaths(position: Point, stepCount: Int): List<Path> {
            val paths = mutableListOf<Path>()
            val zigzagPoints = mutableListOf<Point>()

            zigzagPoints.add(position)

            for (i in 0 until stepCount) {
                val stepCorner = position.translate(0.0, i / stepCount.toDouble(), (i + 1) / stepCount.toDouble())

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

            zigzagPoints.add(position.translate(0.0, 1.0, 0.0))

            // Zigzag side face
            paths.add(Path(zigzagPoints))
            paths.add(Path(zigzagPoints.reversed()).translate(1.0, 0.0, 0.0))

            return paths
        }
    }
}
