package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Result of an interaction simulation for a single frame.
 *
 * @property tapX Screen-space X coordinate for the tap
 * @property tapY Screen-space Y coordinate for the tap
 * @property expectedHit Whether a shape is expected at this position
 */
data class InteractionResult(
    val tapX: Double,
    val tapY: Double,
    val expectedHit: Boolean
)

/**
 * Pre-generates tap points by duplicating IsometricEngine's projection math.
 *
 * The projection formula is duplicated from [IsometricEngine.translatePoint]
 * (which is private): angle=PI/6, scale=70.0, originX=width/2, originY=height*0.9.
 *
 * The transformation matrix is:
 * ```
 * X screen = originX + point.x * scale * cos(angle) + point.y * scale * cos(PI - angle)
 * Y screen = originY - point.x * scale * sin(angle) - point.y * scale * sin(PI - angle) - point.z * scale
 * ```
 */
object InteractionSimulator {

    private const val ANGLE = PI / 6  // 30 degrees
    private const val SCALE = 70.0

    // Pre-computed transformation coefficients
    private val TX0 = SCALE * cos(ANGLE)
    private val TY0 = SCALE * sin(ANGLE)
    private val TX1 = SCALE * cos(PI - ANGLE)
    private val TY1 = SCALE * sin(PI - ANGLE)

    /**
     * Determine whether a tap should occur this frame based on the interaction pattern.
     *
     * @param frameIndex Current frame number
     * @param pattern The interaction pattern
     * @param items Current scene items (used to compute tap targets)
     * @param viewportWidth Viewport width in pixels
     * @param viewportHeight Viewport height in pixels
     * @return Interaction result if a tap should occur, null otherwise
     */
    fun nextTap(
        frameIndex: Int,
        pattern: InteractionPattern,
        items: List<GeneratedItem>,
        viewportWidth: Int,
        viewportHeight: Int
    ): InteractionResult? {
        if (items.isEmpty()) return null

        val shouldTap = when (pattern) {
            InteractionPattern.NONE -> false
            InteractionPattern.OCCASIONAL -> frameIndex % 60 == 0
            InteractionPattern.CONTINUOUS -> true
        }

        if (!shouldTap) return null

        // Pick a target item deterministically
        val targetIndex = frameIndex % items.size
        val targetItem = items[targetIndex]

        // Project the item's center to screen space
        val center3D = targetItem.position
        val screenPoint = translatePoint(
            center3D,
            viewportWidth / 2.0,
            viewportHeight * 0.9
        )

        return InteractionResult(
            tapX = screenPoint.first,
            tapY = screenPoint.second,
            expectedHit = true
        )
    }

    /**
     * Duplicates IsometricEngine.translatePoint() projection math.
     *
     * @return Pair of (screenX, screenY)
     */
    private fun translatePoint(point: Point, originX: Double, originY: Double): Pair<Double, Double> {
        val screenX = originX + point.x * TX0 + point.y * TX1
        val screenY = originY - point.x * TY0 - point.y * TY1 - (point.z * SCALE)
        return screenX to screenY
    }
}
