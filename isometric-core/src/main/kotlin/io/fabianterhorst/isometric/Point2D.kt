package io.fabianterhorst.isometric

/**
 * A 2D point in screen space (after isometric projection).
 *
 * Provides arithmetic operators for common screen-space math such as
 * midpoint computation, offset calculations, and distance checks.
 */
data class Point2D(
    val x: Double,
    val y: Double
) {
    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Double): Point2D = Point2D(x * scalar, y * scalar)
    operator fun div(scalar: Double): Point2D = Point2D(x / scalar, y / scalar)
    operator fun unaryMinus(): Point2D = Point2D(-x, -y)

    fun distanceTo(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun distanceToSquared(other: Point2D): Double {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }

    companion object {
        val ZERO = Point2D(0.0, 0.0)
    }
}
