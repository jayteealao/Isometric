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
     * This variant is **lenient at the boundary**: shared edges, shared vertices, and
     * collinear overlap all return `true`. For strict interior overlap (which excludes
     * boundary-only contact) use [hasInteriorIntersection].
     *
     * @param pointsA Vertices of the first polygon (projected 2D points).
     * @param pointsB Vertices of the second polygon (projected 2D points).
     * @return `true` if the polygons overlap (including boundary contact).
     */
    fun hasIntersection(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        if (pointsA.isEmpty() || pointsB.isEmpty()) return false
        if (!aabbsOverlap(pointsA, pointsB)) return false

        val edgesA = EdgeEquations.of(pointsA)
        val edgesB = EdgeEquations.of(pointsB)

        if (edgesCrossStrictly(pointsA, edgesA, pointsB, edgesB)) return true

        // Boundary-inclusive fallback: a vertex strictly inside OR within
        // [EDGE_BAND] of any boundary edge of the other polygon counts as overlap.
        // This keeps shared-edge / shared-vertex pairs intersecting per the public
        // contract documented above.
        for (i in pointsA.indices) {
            val p = pointsA[i]
            if (isPointInPoly(pointsB, p.x, p.y) ||
                isPointCloseToPoly(pointsB, p.x, p.y, EDGE_BAND)
            ) return true
        }
        for (i in pointsB.indices) {
            val p = pointsB[i]
            if (isPointInPoly(pointsA, p.x, p.y) ||
                isPointCloseToPoly(pointsA, p.x, p.y, EDGE_BAND)
            ) return true
        }
        return false
    }

    /**
     * Tests whether two convex polygons share a non-trivial *interior* overlap area.
     *
     * Stricter than [hasIntersection]: returns `false` when the polygons share only
     * a boundary edge or vertex with zero interior overlap. Used by
     * [DepthSorter.checkDepthDependency] to gate topological-edge insertion — face
     * pairs whose iso-projected polygons only touch at a boundary cannot paint over
     * each other regardless of [Path.closerThan]'s verdict, so adding a draw-order
     * edge for them produces spurious dependencies that can push unrelated faces to
     * extreme positions in the topological order.
     *
     * Algorithm:
     * 1. Quick AABB rejection (shared with [hasIntersection]).
     * 2. Strict edge-crossing test — if any edge of A crosses strictly through the
     *    interior of any edge of B (and vice versa), the polygons must
     *    interior-overlap. The strict-inequality threshold ([SAT_CROSS_THRESHOLD])
     *    guarantees that collinear or touching edges are NOT counted as crossings.
     * 3. Strict-inside fallback — if any vertex of one polygon is strictly inside
     *    the other (interior, not within an [EDGE_BAND] of any boundary edge), the
     *    polygons interior-overlap. Catches the strict-containment case where no
     *    edges cross. Boundary vertices fail the band check, so shared-vertex /
     *    shared-edge cases correctly return `false`.
     *
     * @param pointsA Vertices of the first polygon (projected 2D points).
     * @param pointsB Vertices of the second polygon (projected 2D points).
     * @return `true` only if the polygons share a non-trivial interior overlap.
     */
    fun hasInteriorIntersection(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        if (pointsA.isEmpty() || pointsB.isEmpty()) return false
        if (!aabbsOverlap(pointsA, pointsB)) return false

        val edgesA = EdgeEquations.of(pointsA)
        val edgesB = EdgeEquations.of(pointsB)

        if (edgesCrossStrictly(pointsA, edgesA, pointsB, edgesB)) return true

        // Strict-inside fallback: at least one vertex of one polygon must be
        // strictly inside the other (interior, not on or near any boundary edge).
        // This catches the strict-containment case while correctly rejecting
        // shared-vertex and shared-edge cases (where vertices fall on the
        // boundary band).
        for (i in pointsA.indices) {
            val p = pointsA[i]
            if (isPointInPoly(pointsB, p.x, p.y) &&
                !isPointCloseToPoly(pointsB, p.x, p.y, EDGE_BAND)
            ) {
                return true
            }
        }
        for (i in pointsB.indices) {
            val p = pointsB[i]
            if (isPointInPoly(pointsA, p.x, p.y) &&
                !isPointCloseToPoly(pointsA, p.x, p.y, EDGE_BAND)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Quick axis-aligned bounding-box overlap test. Lenient at the boundary
     * (touching AABBs return `true`) so that downstream tests can decide whether
     * a true interior overlap exists.
     */
    private fun aabbsOverlap(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        var aMinX = pointsA[0].x; var aMinY = pointsA[0].y
        var aMaxX = aMinX; var aMaxY = aMinY
        for (i in pointsA.indices) {
            val p = pointsA[i]
            if (p.x < aMinX) aMinX = p.x else if (p.x > aMaxX) aMaxX = p.x
            if (p.y < aMinY) aMinY = p.y else if (p.y > aMaxY) aMaxY = p.y
        }
        var bMinX = pointsB[0].x; var bMinY = pointsB[0].y
        var bMaxX = bMinX; var bMaxY = bMinY
        for (i in pointsB.indices) {
            val p = pointsB[i]
            if (p.x < bMinX) bMinX = p.x else if (p.x > bMaxX) bMaxX = p.x
            if (p.y < bMinY) bMinY = p.y else if (p.y > bMaxY) bMaxY = p.y
        }
        if (aMaxX < bMinX || bMaxX < aMinX) return false
        if (aMaxY < bMinY || bMaxY < aMinY) return false
        return true
    }

    /**
     * Strict SAT edge-crossing test: does any edge of A cross strictly through the
     * interior of any edge of B (and vice versa)? Boundary touches and collinear
     * overlaps are intentionally NOT counted as crossings — both endpoints of one
     * segment must be on strictly opposite sides of the other segment's line.
     */
    private fun edgesCrossStrictly(
        pointsA: List<Point>, edgesA: EdgeEquations,
        pointsB: List<Point>, edgesB: EdgeEquations,
    ): Boolean {
        val n = edgesA.size
        val m = edgesB.size
        for (i in 0 until n) {
            val ai0 = pointsA[i]
            val ai1 = pointsA[(i + 1) % n]
            val dxA = edgesA.deltaX[i]; val dyA = edgesA.deltaY[i]; val rAi = edgesA.r[i]
            for (j in 0 until m) {
                val dxB = edgesB.deltaX[j]; val dyB = edgesB.deltaY[j]; val rBj = edgesB.r[j]
                if (dxA * dyB == dyA * dxB) continue   // parallel — handled by inside-test fallback.
                val bj0 = pointsB[j]
                val bj1 = pointsB[(j + 1) % m]
                val side1a = dyA * bj0.x - dxA * bj0.y + rAi
                val side1b = dyA * bj1.x - dxA * bj1.y + rAi
                val side2a = dyB * ai0.x - dxB * ai0.y + rBj
                val side2b = dyB * ai1.x - dxB * ai1.y + rBj
                if (side1a * side1b < SAT_CROSS_THRESHOLD &&
                    side2a * side2b < SAT_CROSS_THRESHOLD
                ) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Pre-computed edge equations for a closed polygon. Each edge i runs from
     * `points[i]` to `points[(i + 1) % n]`. Stored as parallel `DoubleArray`s of
     * size `n` so callers can read `deltaX[i]`, `deltaY[i]`, `r[i]` without
     * unboxing or per-edge allocation.
     */
    private class EdgeEquations(
        val deltaX: DoubleArray,
        val deltaY: DoubleArray,
        val r: DoubleArray,
        val size: Int,
    ) {
        companion object {
            fun of(points: List<Point>): EdgeEquations {
                val n = points.size
                val dx = DoubleArray(n)
                val dy = DoubleArray(n)
                val r = DoubleArray(n)
                for (i in 0 until n) {
                    val p = points[i]
                    val q = points[(i + 1) % n]
                    dx[i] = q.x - p.x
                    dy[i] = q.y - p.y
                    // Edge line equation: deltaY * x - deltaX * y + r = 0.
                    r[i] = dx[i] * p.y - dy[i] * p.x
                }
                return EdgeEquations(dx, dy, r, n)
            }
        }
    }

    /** Distance from a point to a polygon edge below this band counts as on-boundary. */
    private const val EDGE_BAND: Double = 1e-6

    /**
     * Strict-crossing threshold for the SAT edge-overlap test. Both `side*` products
     * must be **less than** this small negative value (i.e. clearly opposite signs)
     * for a crossing to count. Tighter than [EDGE_BAND] because the side products
     * are quadratic in coordinate magnitude.
     */
    private const val SAT_CROSS_THRESHOLD: Double = -1e-9
}
