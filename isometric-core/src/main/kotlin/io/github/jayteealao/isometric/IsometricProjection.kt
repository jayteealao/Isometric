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
     * Back-face culling test using the full shoelace formula over all vertices.
     *
     * Returns true if the path should be culled (is facing away from the viewer).
     * The sign of the signed area (shoelace sum) determines winding direction:
     * positive sum = counter-clockwise = facing the camera = keep.
     *
     * Using the full polygon instead of the first 3 vertices gives correct winding
     * for concave polygons whose first 3 vertices do not represent the overall shape.
     */
    fun cullPath(transformedPoints: List<Point2D>): Boolean {
        if (transformedPoints.size < 3) return false

        var z = 0.0
        val n = transformedPoints.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            z += transformedPoints[i].x * transformedPoints[j].y
            z -= transformedPoints[j].x * transformedPoints[i].y
        }
        return z > 0
    }

    /**
     * Check if the item overlaps the drawing bounds.
     *
     * Returns true if any vertex is inside the viewport OR if the face's axis-aligned
     * bounding box overlaps the viewport rectangle [0, width] × [0, height].
     *
     * The AABB overlap check is necessary for large faces whose vertices are all outside
     * the viewport but whose face spans over it (e.g. a large floor plane). A vertex-only
     * test would incorrectly cull such faces.
     */
    fun itemInDrawingBounds(transformedPoints: List<Point2D>, width: Int, height: Int): Boolean {
        // Fast path: any vertex inside viewport
        for (point in transformedPoints) {
            if (point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height) {
                return true
            }
        }
        // Slow path: compute face AABB and test overlap with viewport
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (point in transformedPoints) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }
        // AABB overlaps viewport iff neither rectangle is entirely to the right/below/left/above the other
        return maxX >= 0 && minX <= width && maxY >= 0 && minY <= height
    }
}
