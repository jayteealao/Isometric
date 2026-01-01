package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape

/**
 * A knot shape
 * Note: needs depth sorting fix as per original TODO
 */
class Knot(origin: Point) : Shape(createPaths(origin)) {

    companion object {
        private fun createPaths(origin: Point): List<Path> {
            val allPaths = mutableListOf<Path>()

            // Add prisms
            allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
            allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
            allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)

            // Add custom paths
            allPaths.add(
                Path(
                    Point(0.0, 0.0, 2.0),
                    Point(0.0, 0.0, 1.0),
                    Point(1.0, 0.0, 1.0),
                    Point(1.0, 0.0, 2.0)
                )
            )
            allPaths.add(
                Path(
                    Point(0.0, 0.0, 2.0),
                    Point(0.0, 1.0, 2.0),
                    Point(0.0, 1.0, 1.0),
                    Point(0.0, 0.0, 1.0)
                )
            )

            // Scale and translate all paths
            val scaledPaths = allPaths.map { it.scale(Point.ORIGIN, 1.0 / 5.0) }
            val translatedPaths = scaledPaths.map { it.translate(-0.1, 0.15, 0.4) }
            val finalPaths = translatedPaths.map { it.translate(origin.x, origin.y, origin.z) }

            return finalPaths
        }
    }
}
