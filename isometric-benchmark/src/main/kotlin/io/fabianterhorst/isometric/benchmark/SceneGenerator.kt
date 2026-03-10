package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import kotlin.random.Random

/**
 * Deterministic scene generator for benchmark scenarios.
 *
 * Uses a seeded [Random] instance to produce reproducible scenes.
 * Shapes are laid out in a grid pattern with configurable spacing.
 */
object SceneGenerator {

    private val COLORS = listOf(
        IsoColor(33.0, 150.0, 243.0),   // Blue
        IsoColor(244.0, 67.0, 54.0),    // Red
        IsoColor(76.0, 175.0, 80.0),    // Green
        IsoColor(255.0, 152.0, 0.0),    // Orange
        IsoColor(156.0, 39.0, 176.0),   // Purple
        IsoColor(0.0, 188.0, 212.0),    // Cyan
        IsoColor(255.0, 235.0, 59.0),   // Yellow
        IsoColor(121.0, 85.0, 72.0)     // Brown
    )

    /**
     * Generate a deterministic scene with [count] shapes.
     *
     * Shapes are arranged in a grid layout. Even-indexed items are Prisms,
     * odd-indexed items are Pyramids. Colors cycle through a fixed palette.
     *
     * @param count Number of shapes to generate
     * @param seed Random seed for reproducibility (default: 42)
     * @param spacing Grid spacing between shapes (default: 2.0)
     * @return List of generated items in grid order
     */
    fun generate(count: Int, seed: Long = 42L, spacing: Double = 2.0): List<GeneratedItem> {
        val random = Random(seed)
        val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt()
        val items = mutableListOf<GeneratedItem>()

        for (i in 0 until count) {
            val row = i / cols
            val col = i % cols
            val position = Point(col * spacing, row * spacing, 0.0)

            // Alternate between Prism and Pyramid
            val dx = 0.8 + random.nextDouble() * 0.4  // 0.8 to 1.2
            val dy = 0.8 + random.nextDouble() * 0.4
            val dz = 0.5 + random.nextDouble() * 1.0  // 0.5 to 1.5

            val shape = if (i % 2 == 0) {
                Prism(Point.ORIGIN, dx, dy, dz)
            } else {
                Pyramid(Point.ORIGIN, dx, dy, dz)
            }

            val color = COLORS[random.nextInt(COLORS.size)]

            items.add(
                GeneratedItem(
                    id = "item_$i",
                    shape = shape,
                    color = color,
                    position = position
                )
            )
        }

        return items
    }
}
