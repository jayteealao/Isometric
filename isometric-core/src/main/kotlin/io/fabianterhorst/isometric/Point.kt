package io.fabianterhorst.isometric

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Represents a point in 3D space with transformation operations.
 */
class Point(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
) {
    companion object {
        @JvmField
        val ORIGIN = Point(0.0, 0.0, 0.0)

        /**
         * Distance between two points
         */
        fun distance(p1: Point, p2: Point): Double {
            return sqrt(distance2(p1, p2))
        }

        /**
         * Squared distance between two points (faster, avoids sqrt)
         */
        fun distance2(p1: Point, p2: Point): Double {
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dz = p2.z - p1.z
            return dx * dx + dy * dy + dz * dz
        }

        /**
         * Squared distance between a point p and a line segment vw
         */
        fun distanceToSegmentSquared(p: Point, v: Point, w: Point): Double {
            val l2 = distance2(v, w)
            if (l2 == 0.0) return distance2(p, v)

            val t = ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2
            if (t < 0) return distance2(p, v)
            if (t > 1) return distance2(p, w)

            return distance2(p, Point(v.x + t * (w.x - v.x), v.y + t * (w.y - v.y)))
        }

        /**
         * Distance between a point p and a line segment vw
         */
        fun distanceToSegment(p: Point, v: Point, w: Point): Double {
            return sqrt(distanceToSegmentSquared(p, v, w))
        }
    }

    /**
     * Translate a point by the given deltas
     */
    fun translate(dx: Double, dy: Double, dz: Double): Point {
        return Point(x + dx, y + dy, z + dz)
    }

    operator fun plus(other: Point): Point = Point(x + other.x, y + other.y, z + other.z)

    operator fun plus(vector: Vector): Point = Point(x + vector.x, y + vector.y, z + vector.z)

    operator fun minus(other: Point): Vector = Vector(x - other.x, y - other.y, z - other.z)

    operator fun minus(vector: Vector): Point = Point(x - vector.x, y - vector.y, z - vector.z)

    operator fun times(scalar: Double): Point = Point(x * scalar, y * scalar, z * scalar)

    operator fun unaryMinus(): Point = Point(-x, -y, -z)

    /**
     * Scale a point about a given origin
     */
    fun scale(origin: Point, dx: Double, dy: Double, dz: Double): Point {
        return Point(
            (x - origin.x) * dx + origin.x,
            (y - origin.y) * dy + origin.y,
            (z - origin.z) * dz + origin.z
        )
    }

    fun scale(origin: Point, dx: Double): Point {
        return scale(origin, dx, dx, dx)
    }

    fun scale(origin: Point, dx: Double, dy: Double): Point {
        return scale(origin, dx, dy, 1.0)
    }

    /**
     * Rotate about origin on the X axis
     */
    fun rotateX(origin: Point, angle: Double): Point {
        val pY = y - origin.y
        val pZ = z - origin.z
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val newZ = pZ * cosAngle - pY * sinAngle
        val newY = pZ * sinAngle + pY * cosAngle
        return Point(x, newY + origin.y, newZ + origin.z)
    }

    /**
     * Rotate about origin on the Y axis
     */
    fun rotateY(origin: Point, angle: Double): Point {
        val pX = x - origin.x
        val pZ = z - origin.z
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val newX = pX * cosAngle - pZ * sinAngle
        val newZ = pX * sinAngle + pZ * cosAngle
        return Point(newX + origin.x, y, newZ + origin.z)
    }

    /**
     * Rotate about origin on the Z axis
     */
    fun rotateZ(origin: Point, angle: Double): Point {
        val pX = x - origin.x
        val pY = y - origin.y
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val newX = pX * cosAngle - pY * sinAngle
        val newY = pX * sinAngle + pY * cosAngle
        return Point(newX + origin.x, newY + origin.y, z)
    }

    /**
     * Simplified depth formula using equal weighting for x and y.
     * This is a heuristic that provides correct relative ordering for standard
     * isometric scenes. For exact depth at arbitrary projection angles,
     * use [depth] with an angle parameter.
     *
     * Note: This is NOT equivalent to `depth(PI/6)` — the parameterized
     * version uses cos/sin weighting while this uses uniform weighting.
     */
    fun depth(): Double {
        return x + y - 2 * z
    }

    /**
     * The depth of a point in the isometric plane for an arbitrary engine angle.
     * The formula weights x/y by cos/sin of the projection angle and z by a
     * factor that preserves correct depth ordering for the given viewing angle.
     *
     * @param angle The isometric projection angle in radians (e.g., PI / 6 for 30°)
     */
    fun depth(angle: Double): Double {
        val cosA = cos(angle)
        val sinA = sin(angle)
        return x * cosA + y * sinA - 2 * z
    }

    override fun equals(other: Any?): Boolean =
        other is Point && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString(): String = "Point(x=$x, y=$y, z=$z)"
}
