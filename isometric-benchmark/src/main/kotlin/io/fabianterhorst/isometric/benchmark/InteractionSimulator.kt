package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
 * Generates both expected-hit taps (projected from known item centers with ±5px jitter)
 * and expected-miss taps (corners of the canvas where no geometry exists).
 */
object InteractionSimulator {

    private const val ANGLE = PI / 6  // 30 degrees
    private const val SCALE = 70.0
    private const val SEED = 11111L
    private const val JITTER_PX = 5.0

    // Shape center offset from item origin. Shapes are Prism/Pyramid with
    // base 1.0×1.0 and height 0.5–2.0. The x/y center is always +0.5 from
    // the origin. For z, 0.5 is a conservative center that lies within all
    // shapes (minimum height is 0.5).
    private const val CENTER_OFFSET_X = 0.5
    private const val CENTER_OFFSET_Y = 0.5
    private const val CENTER_OFFSET_Z = 0.5

    // Pre-computed transformation coefficients
    private val TX0 = SCALE * cos(ANGLE)
    private val TY0 = SCALE * sin(ANGLE)
    private val TX1 = SCALE * cos(PI - ANGLE)
    private val TY1 = SCALE * sin(PI - ANGLE)

    /**
     * Determine whether a tap should occur this frame based on the interaction pattern.
     *
     * Generates a mix of expected-hit and expected-miss taps:
     * - ~80% of taps target known items (with ±5px jitter)
     * - ~20% of taps target empty space (canvas corners)
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

        val random = Random(SEED + frameIndex)

        // 80% hit taps, 20% miss taps
        return if (random.nextDouble() < 0.8) {
            generateHitTap(frameIndex, items, viewportWidth, viewportHeight, random)
        } else {
            generateMissTap(viewportWidth, viewportHeight, random)
        }
    }

    /**
     * Generate a tap targeting a known item with ±5px jitter.
     */
    private fun generateHitTap(
        frameIndex: Int,
        items: List<GeneratedItem>,
        viewportWidth: Int,
        viewportHeight: Int,
        random: Random
    ): InteractionResult {
        val targetIndex = frameIndex % items.size
        val targetItem = items[targetIndex]

        // Project from item center, not origin. Shapes have base 1.0×1.0 and
        // variable height, so center = position + (0.5, 0.5, 0.5).
        val center3D = Point(
            targetItem.position.x + CENTER_OFFSET_X,
            targetItem.position.y + CENTER_OFFSET_Y,
            targetItem.position.z + CENTER_OFFSET_Z
        )
        val screenPoint = translatePoint(
            center3D,
            viewportWidth / 2.0,
            viewportHeight * 0.9
        )

        // Add ±5px jitter to simulate realistic finger taps
        val jitterX = (random.nextDouble() * 2.0 - 1.0) * JITTER_PX
        val jitterY = (random.nextDouble() * 2.0 - 1.0) * JITTER_PX

        return InteractionResult(
            tapX = screenPoint.first + jitterX,
            tapY = screenPoint.second + jitterY,
            expectedHit = true
        )
    }

    /**
     * Generate a tap at empty space (canvas corners) where no geometry is expected.
     */
    private fun generateMissTap(
        viewportWidth: Int,
        viewportHeight: Int,
        random: Random
    ): InteractionResult {
        // Tap in a corner area (10px margin) — geometry is centered, so corners should be empty
        val corners = listOf(
            10.0 to 10.0,                                    // top-left
            viewportWidth - 10.0 to 10.0,                    // top-right
            10.0 to viewportHeight - 10.0,                   // bottom-left
            viewportWidth - 10.0 to viewportHeight - 10.0    // bottom-right
        )
        val corner = corners[random.nextInt(corners.size)]

        return InteractionResult(
            tapX = corner.first,
            tapY = corner.second,
            expectedHit = false
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
