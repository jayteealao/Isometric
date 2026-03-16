package io.fabianterhorst.isometric

/**
 * Hit testing: find the frontmost render command at a screen coordinate.
 *
 * Uses the projected polygon points directly for point-in-polygon testing
 * with optional touch radius expansion.
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
            // Use projected points directly — they are already in correct winding order
            val points = command.points.map { Point(it.x, it.y, 0.0) }

            val isInside = if (touchRadius > 0.0) {
                IntersectionUtils.isPointCloseToPoly(
                    points, x, y, touchRadius
                ) || IntersectionUtils.isPointInPoly(
                    points, x, y
                )
            } else {
                IntersectionUtils.isPointInPoly(
                    points, x, y
                )
            }

            if (isInside) {
                return command
            }
        }

        return null
    }
}
