package io.github.jayteealao.isometric

import kotlin.math.sqrt

/**
 * A three-dimensional vector used for directions, normals, and light calculations.
 *
 * Unlike [Point], a [Vector] represents a direction and magnitude rather than a
 * position in space. The subtraction `Point - Point` yields a [Vector], and
 * `Point + Vector` yields a [Point].
 *
 * @param x X component of the vector.
 * @param y Y component of the vector.
 * @param z Z component of the vector.
 */
class Vector(
    val x: Double,
    val y: Double,
    val z: Double
) {
    companion object {
        /** Creates a vector from [p1] to [p2] (i.e., p2 - p1). */
        fun fromTwoPoints(p1: Point, p2: Point): Vector {
            return Vector(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z)
        }

        /** Returns the cross product of [v1] and [v2]. */
        fun crossProduct(v1: Vector, v2: Vector): Vector {
            val cx = v1.y * v2.z - v2.y * v1.z
            val cy = -1 * (v1.x * v2.z - v2.x * v1.z)
            val cz = v1.x * v2.y - v2.x * v1.y
            return Vector(cx, cy, cz)
        }

        /** Returns the dot product of [v1] and [v2]. */
        fun dotProduct(v1: Vector, v2: Vector): Double {
            return v1.x * v2.x + v1.y * v2.y + v1.z * v2.z
        }
    }

    /** Returns the length (magnitude) of this vector: sqrt(x^2 + y^2 + z^2). */
    fun magnitude(): Double {
        return sqrt(x * x + y * y + z * z)
    }

    /** Returns a unit vector in the same direction. Throws if magnitude is zero. */
    fun normalize(): Vector {
        val mag = magnitude()
        if (mag == 0.0) {
            return Vector(0.0, 0.0, 0.0)
        }
        return Vector(x / mag, y / mag, z / mag)
    }

    /** Returns the component-wise sum of this vector and [other]. */
    operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y, z + other.z)

    /** Returns the component-wise difference of this vector and [other]. */
    operator fun minus(other: Vector): Vector = Vector(x - other.x, y - other.y, z - other.z)

    /** Returns this vector scaled by [scalar]. */
    operator fun times(scalar: Double): Vector = Vector(x * scalar, y * scalar, z * scalar)

    /** Returns this vector with all components negated. */
    operator fun unaryMinus(): Vector = Vector(-x, -y, -z)

    /** Returns the cross product of this vector and [other]. */
    infix fun cross(other: Vector): Vector = Vector(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    /** Returns the dot product of this vector and [other]. */
    infix fun dot(other: Vector): Double = x * other.x + y * other.y + z * other.z

    override fun equals(other: Any?): Boolean =
        other is Vector && x == other.x && y == other.y && z == other.z

    override fun hashCode(): Int {
        var result = x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + z.hashCode()
        return result
    }

    override fun toString(): String = "Vector(x=$x, y=$y, z=$z)"
}
