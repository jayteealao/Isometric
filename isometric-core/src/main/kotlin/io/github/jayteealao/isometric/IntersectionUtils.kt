package io.github.jayteealao.isometric

/**
 * Utility functions for geometric intersection and point-in-polygon tests
 */
object IntersectionUtils {

    /**
     * Tests whether the point ([x], [y]) is within [radius] pixels of the polygon [poly].
     *
     * Combines a point-in-polygon test with an edge-proximity test so that points
     * near the polygon boundary also return `true`.
     *
     * @param poly Polygon vertices (projected 2D points).
     * @param x X coordinate to test.
     * @param y Y coordinate to test.
     * @param radius Maximum distance from the polygon edge to still be considered "close".
     * @return `true` if the point is inside or within [radius] of the polygon.
     */
    fun isPointCloseToPoly(poly: List<Point>, x: Double, y: Double, radius: Double): Boolean {
        val p = Point(x, y, 0.0)

        // Iterate over each line segment
        for (i in poly.indices) {
            val j = (i + 1) % poly.size
            val v = poly[i]
            val w = poly[j]

            val dist = Point.distanceToSegment(p, v, w)

            if (dist < radius) {
                return true
            }
        }

        return false
    }

    /**
     * Tests whether the point ([x], [y]) is inside the polygon [poly] using the
     * ray-casting algorithm.
     *
     * @param poly Polygon vertices (projected 2D points).
     * @param x X coordinate to test.
     * @param y Y coordinate to test.
     * @return `true` if the point is strictly inside the polygon.
     */
    fun isPointInPoly(poly: List<Point>, x: Double, y: Double): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            if (((poly[i].y <= y && y < poly[j].y) || (poly[j].y <= y && y < poly[i].y)) &&
                (x < (poly[j].x - poly[i].x) * (y - poly[i].y) / (poly[j].y - poly[i].y) + poly[i].x)
            ) {
                c = !c
            }
            j = i
        }
        return c
    }

    /**
     * Tests whether two convex polygons overlap using the Separating Axis Theorem (SAT).
     *
     * Used during depth sorting to determine which face pairs need explicit ordering.
     *
     * @param pointsA Vertices of the first polygon (projected 2D points).
     * @param pointsB Vertices of the second polygon (projected 2D points).
     * @return `true` if the polygons overlap.
     */
    fun hasIntersection(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        if (pointsA.isEmpty() || pointsB.isEmpty()) return false

        // First, check bounding boxes for quick rejection
        var AminX = pointsA[0].x
        var AminY = pointsA[0].y
        var AmaxX = AminX
        var AmaxY = AminY
        var BminX = pointsB[0].x
        var BminY = pointsB[0].y
        var BmaxX = BminX
        var BmaxY = BminY

        for (point in pointsA) {
            AminX = min(AminX, point.x)
            AminY = min(AminY, point.y)
            AmaxX = max(AmaxX, point.x)
            AmaxY = max(AmaxY, point.y)
        }

        for (point in pointsB) {
            BminX = min(BminX, point.x)
            BminY = min(BminY, point.y)
            BmaxX = max(BmaxX, point.x)
            BmaxY = max(BmaxY, point.y)
        }

        // Check if bounding boxes overlap
        if (!(((AminX <= BminX && BminX <= AmaxX) || (BminX <= AminX && AminX <= BmaxX)) &&
                    ((AminY <= BminY && BminY <= AmaxY) || (BminY <= AminY && AminY <= BmaxY)))
        ) {
            return false
        }

        // Bounding boxes overlap, now check if edges cross or one polygon contains the other
        val polyA = pointsA + pointsA[0]  // Close the polygon
        val polyB = pointsB + pointsB[0]

        val lengthPolyA = polyA.size
        val lengthPolyB = polyB.size

        // Precompute line equations for each segment
        val deltaAX = DoubleArray(lengthPolyA - 1)
        val deltaAY = DoubleArray(lengthPolyA - 1)
        val rA = DoubleArray(lengthPolyA - 1)

        for (i in 0 until lengthPolyA - 1) {
            val point = polyA[i]
            deltaAX[i] = polyA[i + 1].x - point.x
            deltaAY[i] = polyA[i + 1].y - point.y
            // Equation written as deltaY.x - deltaX.y + r = 0
            rA[i] = deltaAX[i] * point.y - deltaAY[i] * point.x
        }

        val deltaBX = DoubleArray(lengthPolyB - 1)
        val deltaBY = DoubleArray(lengthPolyB - 1)
        val rB = DoubleArray(lengthPolyB - 1)

        for (i in 0 until lengthPolyB - 1) {
            val point = polyB[i]
            deltaBX[i] = polyB[i + 1].x - point.x
            deltaBY[i] = polyB[i + 1].y - point.y
            rB[i] = deltaBX[i] * point.y - deltaBY[i] * point.x
        }

        // Check if any edges cross
        for (i in 0 until lengthPolyA - 1) {
            for (j in 0 until lengthPolyB - 1) {
                if (deltaAX[i] * deltaBY[j] != deltaAY[i] * deltaBX[j]) {
                    // Not colinear, check if segments cross
                    val side1a = deltaAY[i] * polyB[j].x - deltaAX[i] * polyB[j].y + rA[i]
                    val side1b = deltaAY[i] * polyB[j + 1].x - deltaAX[i] * polyB[j + 1].y + rA[i]
                    val side2a = deltaBY[j] * polyA[i].x - deltaBX[j] * polyA[i].y + rB[j]
                    val side2b = deltaBY[j] * polyA[i + 1].x - deltaBX[j] * polyA[i + 1].y + rB[j]

                    if (side1a * side1b < -0.000000001 && side2a * side2b < -0.000000001) {
                        return true
                    }
                }
            }
        }

        // Check if any point of one polygon is inside the other
        for (i in 0 until lengthPolyA - 1) {
            val point = polyA[i]
            if (isPointInPoly(pointsB, point.x, point.y)) {
                return true
            }
        }

        for (i in 0 until lengthPolyB - 1) {
            val point = polyB[i]
            if (isPointInPoly(pointsA, point.x, point.y)) {
                return true
            }
        }

        return false
    }

    /**
     * Tests whether two convex polygons share a non-trivial *interior* overlap area.
     *
     * Stricter than [hasIntersection]: returns `false` when the polygons share only
     * a boundary edge or vertex with zero interior overlap. Used by
     * [DepthSorter.checkDepthDependency] to gate topological-edge insertion —
     * face pairs whose iso-projected polygons only touch at a boundary cannot
     * paint over each other regardless of [Path.closerThan]'s verdict, so adding
     * a draw-order edge for them produces spurious dependencies that can push
     * unrelated faces to extreme positions in the topological order.
     *
     * Algorithm:
     * 1. Quick AABB rejection (same as [hasIntersection]).
     * 2. Strict edge-crossing test — if any edge of A crosses strictly through
     *    the interior of any edge of B (and vice versa), the polygons must
     *    overlap interior. The strict-inequality threshold (`< -1e-9`) inherited
     *    from [hasIntersection] guarantees that collinear or touching edges
     *    are NOT counted as crossings.
     * 3. Strict-inside fallback — if any vertex of one polygon is strictly
     *    inside the other (interior, not within a small `1e-6` band of any
     *    edge), the polygons interior-overlap. Catches the strict-containment
     *    case where no edges cross. Boundary vertices fail the band check, so
     *    shared-vertex / shared-edge cases correctly return `false`.
     *
     * @param pointsA Vertices of the first polygon (projected 2D points).
     * @param pointsB Vertices of the second polygon (projected 2D points).
     * @return `true` only if the polygons share a non-trivial interior overlap.
     */
    fun hasInteriorIntersection(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        if (pointsA.isEmpty() || pointsB.isEmpty()) return false

        // Quick AABB overlap check (lenient: boundary-touching AABBs may still
        // contain polygons that interior-overlap, so we can't reject here on
        // boundary touch alone).
        var AminX = pointsA[0].x
        var AminY = pointsA[0].y
        var AmaxX = AminX
        var AmaxY = AminY
        var BminX = pointsB[0].x
        var BminY = pointsB[0].y
        var BmaxX = BminX
        var BmaxY = BminY

        for (point in pointsA) {
            AminX = min(AminX, point.x)
            AminY = min(AminY, point.y)
            AmaxX = max(AmaxX, point.x)
            AmaxY = max(AmaxY, point.y)
        }
        for (point in pointsB) {
            BminX = min(BminX, point.x)
            BminY = min(BminY, point.y)
            BmaxX = max(BmaxX, point.x)
            BmaxY = max(BmaxY, point.y)
        }

        if (AmaxX < BminX || BmaxX < AminX) return false
        if (AmaxY < BminY || BmaxY < AminY) return false

        val polyA = pointsA + pointsA[0]
        val polyB = pointsB + pointsB[0]
        val lengthPolyA = polyA.size
        val lengthPolyB = polyB.size

        // Precompute line equations for SAT crossing test.
        val deltaAX = DoubleArray(lengthPolyA - 1)
        val deltaAY = DoubleArray(lengthPolyA - 1)
        val rA = DoubleArray(lengthPolyA - 1)
        for (i in 0 until lengthPolyA - 1) {
            val point = polyA[i]
            deltaAX[i] = polyA[i + 1].x - point.x
            deltaAY[i] = polyA[i + 1].y - point.y
            rA[i] = deltaAX[i] * point.y - deltaAY[i] * point.x
        }

        val deltaBX = DoubleArray(lengthPolyB - 1)
        val deltaBY = DoubleArray(lengthPolyB - 1)
        val rB = DoubleArray(lengthPolyB - 1)
        for (i in 0 until lengthPolyB - 1) {
            val point = polyB[i]
            deltaBX[i] = polyB[i + 1].x - point.x
            deltaBY[i] = polyB[i + 1].y - point.y
            rB[i] = deltaBX[i] * point.y - deltaBY[i] * point.x
        }

        // Strict edge-crossing: each segment's endpoints must be on strictly
        // opposite sides of the other segment's line. Boundary touches and
        // collinear overlaps fail this test.
        for (i in 0 until lengthPolyA - 1) {
            for (j in 0 until lengthPolyB - 1) {
                if (deltaAX[i] * deltaBY[j] != deltaAY[i] * deltaBX[j]) {
                    val side1a = deltaAY[i] * polyB[j].x - deltaAX[i] * polyB[j].y + rA[i]
                    val side1b = deltaAY[i] * polyB[j + 1].x - deltaAX[i] * polyB[j + 1].y + rA[i]
                    val side2a = deltaBY[j] * polyA[i].x - deltaBX[j] * polyA[i].y + rB[j]
                    val side2b = deltaBY[j] * polyA[i + 1].x - deltaBX[j] * polyA[i + 1].y + rB[j]

                    if (side1a * side1b < -0.000000001 && side2a * side2b < -0.000000001) {
                        return true
                    }
                }
            }
        }

        // Strict-inside fallback: at least one vertex of one polygon must be
        // strictly inside the other (interior, not on or near any boundary edge).
        // This catches the strict-containment case while correctly rejecting
        // shared-vertex and shared-edge cases (where vertices fall on the
        // boundary band).
        for (i in 0 until lengthPolyA - 1) {
            val p = polyA[i]
            if (isPointInPoly(pointsB, p.x, p.y) &&
                !isPointCloseToPoly(pointsB, p.x, p.y, EDGE_BAND)
            ) {
                return true
            }
        }
        for (i in 0 until lengthPolyB - 1) {
            val p = polyB[i]
            if (isPointInPoly(pointsA, p.x, p.y) &&
                !isPointCloseToPoly(pointsA, p.x, p.y, EDGE_BAND)
            ) {
                return true
            }
        }

        return false
    }

    /** Distance from a point to a polygon edge below this band counts as on-boundary. */
    private const val EDGE_BAND: Double = 1e-6

    private fun min(a: Double, b: Double) = if (a < b) a else b
    private fun max(a: Double, b: Double) = if (a > b) a else b
}
