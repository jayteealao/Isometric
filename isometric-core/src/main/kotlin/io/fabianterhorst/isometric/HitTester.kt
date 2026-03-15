package io.fabianterhorst.isometric

/**
 * Hit testing: find the frontmost render command at a screen coordinate.
 *
 * Builds a convex hull of each command's projected points and performs
 * point-in-polygon testing with optional touch radius expansion.
 */
internal object HitTester {

    /**
     * Find the item at a given screen position.
     *
     * @param preparedScene The projected scene to search
     * @param x Screen x coordinate
     * @param y Screen y coordinate
     * @param order Search order for hit testing
     * @param touchRadius Touch radius in pixels. 0.0 means exact point hit testing.
     * @return The RenderCommand at this position, or null
     */
    fun findItemAt(
        preparedScene: PreparedScene,
        x: Double,
        y: Double,
        order: HitOrder = HitOrder.FRONT_TO_BACK,
        touchRadius: Double = 0.0
    ): RenderCommand? {
        val commandsList = when (order) {
            HitOrder.FRONT_TO_BACK -> preparedScene.commands.reversed()
            HitOrder.BACK_TO_FRONT -> preparedScene.commands
        }

        for (command in commandsList) {
            val hull = buildConvexHull(command.points)
            val hullPoints = hull.map { Point(it.x, it.y, 0.0) }

            val isInside = if (touchRadius > 0.0) {
                IntersectionUtils.isPointCloseToPoly(
                    hullPoints, x, y, touchRadius
                ) || IntersectionUtils.isPointInPoly(
                    hullPoints, x, y
                )
            } else {
                IntersectionUtils.isPointInPoly(
                    hullPoints, x, y
                )
            }

            if (isInside) {
                return command
            }
        }

        return null
    }

    /**
     * Build a convex hull for hit testing.
     * Returns the extreme points (top, bottom, left, right) plus any edge points.
     */
    private fun buildConvexHull(points: List<Point2D>): List<Point2D> {
        if (points.isEmpty()) return emptyList()

        var top: Point2D? = null
        var bottom: Point2D? = null
        var left: Point2D? = null
        var right: Point2D? = null

        for (point in points) {
            if (top == null || point.y > top.y) {
                top = point
            }
            if (bottom == null || point.y < bottom.y) {
                bottom = point
            }
            if (left == null || point.x < left.x) {
                left = point
            }
            if (right == null || point.x > right.x) {
                right = point
            }
        }

        val hull = mutableListOf(left!!, top!!, right!!, bottom!!)

        for (point in points) {
            if (point.x == left.x && point != left) hull.add(point)
            if (point.x == right.x && point != right) hull.add(point)
            if (point.y == top.y && point != top) hull.add(point)
            if (point.y == bottom.y && point != bottom) hull.add(point)
        }

        return hull.distinct()
    }
}
