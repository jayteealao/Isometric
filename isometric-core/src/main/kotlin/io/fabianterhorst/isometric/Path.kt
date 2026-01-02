package io.fabianterhorst.isometric

/**
 * Represents a 2D path (polygon face) in 3D space
 */
open class Path(
    val points: List<Point>
) {
    constructor(vararg points: Point) : this(points.toList())

    /**
     * Returns a new path with the points in reverse order
     */
    fun reverse(): Path {
        return Path(points.reversed())
    }

    /**
     * Translate the path by the given deltas
     */
    fun translate(dx: Double, dy: Double, dz: Double): Path {
        return Path(points.map { it.translate(dx, dy, dz) })
    }

    /**
     * Rotate about origin on the X axis
     */
    fun rotateX(origin: Point, angle: Double): Path {
        return Path(points.map { it.rotateX(origin, angle) })
    }

    /**
     * Rotate about origin on the Y axis
     */
    fun rotateY(origin: Point, angle: Double): Path {
        return Path(points.map { it.rotateY(origin, angle) })
    }

    /**
     * Rotate about origin on the Z axis
     */
    fun rotateZ(origin: Point, angle: Double): Path {
        return Path(points.map { it.rotateZ(origin, angle) })
    }

    /**
     * Scale about a given origin
     */
    fun scale(origin: Point, dx: Double, dy: Double, dz: Double): Path {
        return Path(points.map { it.scale(origin, dx, dy, dz) })
    }

    fun scale(origin: Point, dx: Double, dy: Double): Path {
        return Path(points.map { it.scale(origin, dx, dy) })
    }

    fun scale(origin: Point, dx: Double): Path {
        return Path(points.map { it.scale(origin, dx) })
    }

    /**
     * Calculate the average depth of all points in this path
     */
    fun depth(): Double {
        if (points.isEmpty()) return 0.0
        return points.sumOf { it.depth() } / points.size
    }

    /**
     * If pathB ("this") is closer from the observer than pathA, it must be drawn after.
     * It is closer if one of its vertices and the observer are on the same side of the plane defined by pathA.
     */
    fun closerThan(pathA: Path, observer: Point): Int {
        return pathA.countCloserThan(this, observer) - this.countCloserThan(pathA, observer)
    }

    /**
     * Count how many vertices of this path are on the same side of pathA's plane as the observer
     */
    private fun countCloserThan(pathA: Path, observer: Point): Int {
        if (pathA.points.size < 3) return 0

        // The plane containing pathA is defined by the three points A, B, C
        val AB = Vector.fromTwoPoints(pathA.points[0], pathA.points[1])
        val AC = Vector.fromTwoPoints(pathA.points[0], pathA.points[2])
        val n = Vector.crossProduct(AB, AC)

        val OA = Vector.fromTwoPoints(Point.ORIGIN, pathA.points[0])
        val OU = Vector.fromTwoPoints(Point.ORIGIN, observer) // U = user = observer

        // Plane defined by pathA such as ax + by + zc = d
        // Here d = nx*x + ny*y + nz*z = n.OA
        val d = Vector.dotProduct(n, OA)
        val observerPosition = Vector.dotProduct(n, OU) - d

        var result = 0
        var result0 = 0

        for (point in points) {
            val OP = Vector.fromTwoPoints(Point.ORIGIN, point)
            val pPosition = Vector.dotProduct(n, OP) - d

            // Careful with rounding approximations
            if (observerPosition * pPosition >= 0.000000001) {
                result++
            }
            if (observerPosition * pPosition >= -0.000000001 && observerPosition * pPosition < 0.000000001) {
                result0++
            }
        }

        return if (result == 0) {
            0
        } else {
            (result + result0) / points.size
        }
    }
}
