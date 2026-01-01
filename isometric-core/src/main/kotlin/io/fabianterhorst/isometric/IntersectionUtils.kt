package io.fabianterhorst.isometric

/**
 * Utility functions for geometric intersection and point-in-polygon tests
 */
object IntersectionUtils {

    /**
     * Check if a point is close to any edge of a polygon (within radius)
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
     * Ray casting algorithm to check if a point is inside a polygon
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
     * Check if two polygons have any intersection (edges cross or one contains the other)
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

    private fun min(a: Double, b: Double) = if (a < b) a else b
    private fun max(a: Double, b: Double) = if (a > b) a else b
}
