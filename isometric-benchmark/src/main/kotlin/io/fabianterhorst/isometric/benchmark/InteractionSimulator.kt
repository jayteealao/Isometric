package io.fabianterhorst.isometric.benchmark

import androidx.compose.ui.geometry.Offset
import kotlin.random.Random

/**
 * Generates deterministic tap points for hit-testing benchmarks.
 *
 * Pre-generates all tap points during construction to ensure zero allocations
 * during benchmark measurement phase. Uses seeded random generator for reproducibility.
 *
 * @param pattern The interaction pattern to simulate
 * @param width Viewport width in pixels (must be > 0)
 * @param height Viewport height in pixels (must be > 0)
 * @param seed Random seed for reproducible tap point generation (default: 67890L)
 * @param maxFrames Maximum number of frames to pre-generate (default: 1000)
 *
 * Usage:
 * ```
 * val simulator = InteractionSimulator(InteractionPattern.CONTINUOUS, 1080, 1920)
 * for (frame in 0 until 500) {
 *     val tapPoint = simulator.getHitTestPoint(frame)
 *     if (tapPoint != null) {
 *         // Perform hit test at tapPoint
 *     }
 * }
 * ```
 */
class InteractionSimulator(
    private val pattern: InteractionPattern,
    private val width: Int,
    private val height: Int,
    seed: Long = 67890L,
    maxFrames: Int = 1000  // Make configurable with sensible default
) {
    init {
        require(width > 0) { "width must be positive, got $width" }
        require(height > 0) { "height must be positive, got $height" }
    }

    // Pre-generate all tap points for reproducibility
    private val tapPoints: List<Offset?> = run {
        val random = Random(seed)
        (0 until maxFrames).map { frameIndex ->
            when (pattern) {
                InteractionPattern.NONE -> null
                InteractionPattern.OCCASIONAL -> {
                    if (frameIndex % 60 == 0) {
                        Offset(
                            random.nextFloat() * width,
                            random.nextFloat() * height
                        )
                    } else null
                }
                InteractionPattern.CONTINUOUS -> Offset(
                    random.nextFloat() * width,
                    random.nextFloat() * height
                )
                InteractionPattern.HOVER -> {
                    if (frameIndex % 2 == 0) {
                        Offset(
                            random.nextFloat() * width,
                            random.nextFloat() * height
                        )
                    } else null
                }
            }
        }
    }

    /**
     * Returns the tap point for the given frame index, or null if no tap should occur.
     *
     * @param frameIndex The frame number (0-based)
     * @return Tap point offset, or null if frameIndex >= maxFrames or pattern indicates no tap
     */
    fun getHitTestPoint(frameIndex: Int): Offset? {
        return tapPoints.getOrNull(frameIndex)
    }
}
