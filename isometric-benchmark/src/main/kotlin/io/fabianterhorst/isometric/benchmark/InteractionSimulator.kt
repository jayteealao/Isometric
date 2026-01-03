package io.fabianterhorst.isometric.benchmark

import androidx.compose.ui.geometry.Offset
import kotlin.random.Random

class InteractionSimulator(
    private val pattern: InteractionPattern,
    private val width: Int,
    private val height: Int,
    seed: Long = 67890L
) {
    // Pre-generate all tap points for reproducibility
    private val tapPoints: List<Offset?> = run {
        val random = Random(seed)
        (0 until 1000).map { frameIndex ->
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

    fun getHitTestPoint(frameIndex: Int): Offset? {
        return tapPoints.getOrNull(frameIndex)
    }
}
