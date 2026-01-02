package io.fabianterhorst.isometric

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Represents a point in 3D space with transformation operations.
 */
data class Point(
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
     * The depth of a point in the isometric plane
     * z is weighted slightly to accommodate |_ arrangements
     */
    fun depth(): Double {
        return x + y - 2 * z
    }
}
