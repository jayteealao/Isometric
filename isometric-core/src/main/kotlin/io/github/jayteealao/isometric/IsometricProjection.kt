package io.github.jayteealao.isometric

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
     * Project a 3D point directly into a flat [DoubleArray] at [offset].
     * Writes x at `dest[offset]` and y at `dest[offset + 1]`.
     * Avoids Point2D object allocation in the hot rendering path.
     */
    fun translatePointInto(point: Point, originX: Double, originY: Double, dest: DoubleArray, offset: Int) {
        dest[offset] = originX + point.x * transformation[0][0] + point.y * transformation[1][0]
        dest[offset + 1] = originY - point.x * transformation[0][1] - point.y * transformation[1][1] - (point.z * scale)
    }

    /**
     * Unproject a 2D screen point back to 3D world coordinates on a given Z plane.
     *
     * Inverts the [translatePoint] transformation by solving the 2x2 linear system
     * formed by the projection matrix.
     *
     * @param screenPoint The 2D screen position
     * @param originX The viewport origin X (typically width / 2.0)
     * @param originY The viewport origin Y (typically height * 0.9)
     * @param z The Z plane to project onto
     * @return The 3D world point on the specified Z plane
     */
    fun screenToWorld(screenPoint: Point2D, originX: Double, originY: Double, z: Double): Point {
        val a = transformation[0][0]
        val b = transformation[1][0]
        val c = transformation[0][1]
        val d = transformation[1][1]

        val rhs1 = screenPoint.x - originX
        val rhs2 = originY - screenPoint.y - z * scale

        val det = a * d - b * c
        require(kotlin.math.abs(det) > 1e-10) { "Near-degenerate projection matrix (det=$det) — cannot reliably invert" }

        val worldX = (rhs1 * d - rhs2 * b) / det
        val worldY = (rhs2 * a - rhs1 * c) / det

        return Point(worldX, worldY, z)
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
     * Back-face culling test on flat packed points [x0, y0, x1, y1, ...].
     * Returns true if the path should be culled (is facing away from the viewer).
     */
    fun cullPath(pts: DoubleArray): Boolean {
        if (pts.size < 6) return false // need at least 3 vertices (6 doubles)

        val x0 = pts[0]; val y0 = pts[1]
        val x1 = pts[2]; val y1 = pts[3]
        val x2 = pts[4]; val y2 = pts[5]

        val z = x0 * y1 + x1 * y2 + x2 * y0 - x1 * y0 - x2 * y1 - x0 * y2
        return z > 0
    }

    /**
     * Check if any point in the flat packed array is within the drawing bounds.
     */
    fun itemInDrawingBounds(pts: DoubleArray, width: Int, height: Int): Boolean {
        var i = 0
        while (i < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x >= 0 && x <= width && y >= 0 && y <= height) {
                return true
            }
            i += 2
        }
        return false
    }
}
