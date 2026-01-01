package io.fabianterhorst.isometric

import kotlin.math.sqrt

/**
 * Represents a 3D vector with common vector operations
 */
data class Vector(
    val i: Double,
    val j: Double,
    val k: Double
) {
    companion object {
        /**
         * Create a vector from two points
         */
        fun fromTwoPoints(p1: Point, p2: Point): Vector {
            return Vector(p2.x - p1.x, p2.y - p1.y, p2.z - p1.z)
        }

        /**
         * Cross product of two vectors
         */
        fun crossProduct(v1: Vector, v2: Vector): Vector {
            val i = v1.j * v2.k - v2.j * v1.k
            val j = -1 * (v1.i * v2.k - v2.i * v1.k)
            val k = v1.i * v2.j - v2.i * v1.j
            return Vector(i, j, k)
        }

        /**
         * Dot product of two vectors
         */
        fun dotProduct(v1: Vector, v2: Vector): Double {
            return v1.i * v2.i + v1.j * v2.j + v1.k * v2.k
        }
    }

    /**
     * Calculate the magnitude (length) of this vector
     */
    fun magnitude(): Double {
        return sqrt(i * i + j * j + k * k)
    }

    /**
     * Normalize this vector to unit length
     * Returns zero vector if magnitude is 0
     */
    fun normalize(): Vector {
        val mag = magnitude()
        if (mag == 0.0) {
            return Vector(0.0, 0.0, 0.0)
        }
        return Vector(i / mag, j / mag, k / mag)
    }
}
