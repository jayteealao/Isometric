package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
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
 */
object MutationSimulator {

    /**
     * Determine which items should be mutated this frame.
     *
     * @param items Current scene items
     * @param mutationRate Fraction of items to mutate (0.0 to 1.0)
     * @param frameIndex Current frame number (used for deterministic seeding)
     * @return List of mutations to apply. Empty if mutationRate is 0.0.
     */
    fun mutate(
        items: List<GeneratedItem>,
        mutationRate: Double,
        frameIndex: Int
    ): List<MutationResult> {
        if (mutationRate <= 0.0 || items.isEmpty()) return emptyList()

        val random = Random(frameIndex.toLong() * 31 + 7)
        val count = (items.size * mutationRate).toInt().coerceAtLeast(1)
        val indices = items.indices.shuffled(random).take(count)

        return indices.map { index ->
            val existing = items[index]

            // Mutate color
            val newColor = IsoColor(
                r = (existing.color.r + 30.0 + random.nextDouble() * 60.0) % 256.0,
                g = (existing.color.g + 30.0 + random.nextDouble() * 60.0) % 256.0,
                b = (existing.color.b + 30.0 + random.nextDouble() * 60.0) % 256.0
            )

            // Mutate height
            val dz = 0.5 + random.nextDouble() * 1.5
            val newShape = if (frameIndex % 2 == 0) {
                Prism(Point.ORIGIN, 1.0, 1.0, dz)
            } else {
                Pyramid(Point.ORIGIN, 1.0, 1.0, dz)
            }

            MutationResult(
                index = index,
                newItem = existing.copy(
                    shape = newShape,
                    color = newColor
                )
            )
        }
    }
}
