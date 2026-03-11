package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import kotlin.math.abs
import kotlin.random.Random

/**
 * Result of a mutation operation.
 *
 * @property index The index in the items list to replace
 * @property newItem The replacement item (same id, different shape/color/position)
 */
data class MutationResult(
    val index: Int,
    val newItem: GeneratedItem
)

/**
 * Pure-function mutation simulator for benchmark scenes.
 *
 * Returns [MutationResult]s describing what to change — the **caller**
 * (typically BenchmarkScreen) applies mutations to the SnapshotStateList.
 * This keeps the simulator free of Compose dependencies and testable in isolation.
 *
 * Per-item selection: each item is independently selected with probability
 * equal to [mutationRate], producing Bernoulli trials where actual count
 * varies per frame. Seed: `67890 + frameIndex`.
 */
object MutationSimulator {

    private const val SEED = 67890L

    /**
     * Determine which items should be mutated this frame.
     *
     * Each item is independently selected with `random.nextDouble() < mutationRate`.
     *
     * @param items Current scene items
     * @param mutationRate Probability per item (0.0 to 1.0)
     * @param frameIndex Current frame number (used for deterministic seeding)
     * @return List of mutations to apply. Empty if mutationRate is 0.0.
     */
    fun mutate(
        items: List<GeneratedItem>,
        mutationRate: Double,
        frameIndex: Int
    ): List<MutationResult> {
        if (mutationRate <= 0.0 || items.isEmpty()) return emptyList()

        val random = Random(SEED + frameIndex)
        val results = mutableListOf<MutationResult>()

        for (index in items.indices) {
            if (random.nextDouble() >= mutationRate) continue

            val existing = items[index]

            // New height: 0.5–2.0 (same range as generation).
            // Preserve the existing shape type (Prism/Pyramid) — mutations should
            // only change height and color, not alter face count or rendering cost.
            val height = 0.5 + random.nextDouble() * 1.5
            val newShape = if (existing.shape is Prism) {
                Prism(Point.ORIGIN, 1.0, 1.0, height)
            } else {
                Pyramid(Point.ORIGIN, 1.0, 1.0, height)
            }

            // 30% chance of color shift (rotate hue ±30°)
            val newColor = if (random.nextDouble() < 0.3) {
                shiftColor(existing.color, random)
            } else {
                existing.color
            }

            results.add(
                MutationResult(
                    index = index,
                    newItem = existing.copy(
                        shape = newShape,
                        color = newColor
                    )
                )
            )
        }

        return results
    }

    /**
     * Shift a color's hue by ±30° via RGB→HSV→RGB round-trip.
     */
    private fun shiftColor(color: IsoColor, random: Random): IsoColor {
        val r = color.r / 255.0
        val g = color.g / 255.0
        val b = color.b / 255.0

        // RGB → HSV
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        var hue = when {
            delta == 0.0 -> 0.0
            max == r -> 60.0 * (((g - b) / delta) % 6.0)
            max == g -> 60.0 * (((b - r) / delta) + 2.0)
            else -> 60.0 * (((r - g) / delta) + 4.0)
        }
        if (hue < 0) hue += 360.0

        val saturation = if (max == 0.0) 0.0 else delta / max
        val value = max

        // Shift hue by ±30°
        val shift = random.nextDouble() * 60.0 - 30.0
        val newHue = (hue + shift + 360.0) % 360.0

        // HSV → RGB (reuse SceneGenerator's formula logic)
        val c = value * saturation
        val x = c * (1.0 - abs((newHue / 60.0) % 2.0 - 1.0))
        val m = value - c

        val (r1, g1, b1) = when {
            newHue < 60 -> Triple(c, x, 0.0)
            newHue < 120 -> Triple(x, c, 0.0)
            newHue < 180 -> Triple(0.0, c, x)
            newHue < 240 -> Triple(0.0, x, c)
            newHue < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }

        return IsoColor((r1 + m) * 255.0, (g1 + m) * 255.0, (b1 + m) * 255.0)
    }
}
