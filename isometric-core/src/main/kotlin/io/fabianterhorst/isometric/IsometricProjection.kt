package io.fabianterhorst.isometric

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure projection logic: 3D points to 2D screen coordinates,
 * lighting, back-face culling, and bounds checking.
 *
 * Stateless after construction — all methods are pure functions of their arguments
 * plus the immutable configuration provided at construction time.
 */
internal class IsometricProjection(
    angle: Double,
    private val scale: Double,
    private val colorDifference: Double,
    private val lightColor: IsoColor
) {
    private val transformation: Array<DoubleArray> = arrayOf(
        doubleArrayOf(scale * cos(angle), scale * sin(angle)),
        doubleArrayOf(scale * cos(PI - angle), scale * sin(PI - angle))
    )

    /**
     * Project a 3D point to 2D screen coordinates.
     *
     * X rides along the angle extended from the origin.
     * Y rides perpendicular to this angle (in isometric view: PI - angle).
     * Z affects the y coordinate of the drawn point.
     */
    fun translatePoint(point: Point, originX: Double, originY: Double): Point2D {
        return Point2D(
            originX + point.x * transformation[0][0] + point.y * transformation[1][0],
            originY - point.x * transformation[0][1] - point.y * transformation[1][1] - (point.z * scale)
        )
    }

    /**
     * Apply lighting to a color based on the path's surface normal.
     *
     * Computes the surface normal via cross product of two edges,
     * then dots with the light direction to determine brightness.
     */
    fun transformColor(path: Path, color: IsoColor, lightDirection: Vector): IsoColor {
        if (path.points.size < 3) return color

        val edge1 = Vector.fromTwoPoints(path.points[1], path.points[0])
        val edge2 = Vector.fromTwoPoints(path.points[2], path.points[1])

        val normal = (edge1 cross edge2).normalize()
        val brightness = normal dot lightDirection

        return color.lighten(brightness * colorDifference, lightColor)
    }

    /**
     * Back-face culling test.
     * Returns true if the path should be culled (is facing away from the viewer).
     */
    fun cullPath(transformedPoints: List<Point2D>): Boolean {
        if (transformedPoints.size < 3) return false

        val a = transformedPoints[0].x * transformedPoints[1].y
        val b = transformedPoints[1].x * transformedPoints[2].y
        val c = transformedPoints[2].x * transformedPoints[0].y

        val d = transformedPoints[1].x * transformedPoints[0].y
        val e = transformedPoints[2].x * transformedPoints[1].y
        val f = transformedPoints[0].x * transformedPoints[2].y

        val z = a + b + c - d - e - f
        return z > 0
    }

    /**
     * Check if any point of the item is within the drawing bounds.
     */
    fun itemInDrawingBounds(transformedPoints: List<Point2D>, width: Int, height: Int): Boolean {
        for (point in transformedPoints) {
            if (point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height) {
                return true
            }
        }
        return false
    }
}
