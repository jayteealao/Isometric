package io.github.jayteealao.isometric.benchmark

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic scene generator for benchmark scenarios.
 *
 * Uses a seeded [Random] instance (seed 12345) to produce reproducible scenes.
 * Shapes are laid out in a centered grid with spacing 1.2, per-cell jitter ±0.2,
 * 60/40 Prism/Pyramid ratio, and HSV-based random colors.
 */
object SceneGenerator {

    private const val SEED = 12345L
    private const val SPACING = 1.2
    private const val JITTER = 0.2

    /**
     * Generate a deterministic scene with [count] shapes.
     *
     * - Grid centered around origin with spacing 1.2
     * - 60% Prism / 40% Pyramid via `random.nextDouble() < 0.6`
     * - Height range: 0.5–2.0
     * - Colors: HSV with random hue, saturation 0.6–0.9, value 0.7–1.0
     * - IDs: "gen_${index}"
     *
     * @param count Number of shapes to generate
     * @return List of generated items in grid order
     */
    fun generate(count: Int): List<GeneratedItem> {
        val random = Random(SEED)
        val cols = ceil(sqrt(count.toDouble())).toInt()
        val half = (cols - 1) / 2.0
        val items = mutableListOf<GeneratedItem>()

        for (i in 0 until count) {
            val row = i / cols
            val col = i % cols

            // Centered grid with jitter
            val jitterX = (random.nextDouble() * 2.0 - 1.0) * JITTER
            val jitterY = (random.nextDouble() * 2.0 - 1.0) * JITTER
            val position = Point(
                (col - half) * SPACING + jitterX,
                (row - half) * SPACING + jitterY,
                0.0
            )

            // 60% Prism, 40% Pyramid
            val height = 0.5 + random.nextDouble() * 1.5  // 0.5–2.0
            val shape = if (random.nextDouble() < 0.6) {
                Prism(Point.ORIGIN, 1.0, 1.0, height)
            } else {
                Pyramid(Point.ORIGIN, 1.0, 1.0, height)
            }

            // HSV color: random hue, saturation 0.6–0.9, value 0.7–1.0
            val hue = random.nextDouble() * 360.0
            val saturation = 0.6 + random.nextDouble() * 0.3
            val value = 0.7 + random.nextDouble() * 0.3
            val color = hsvToIsoColor(hue, saturation, value)

            items.add(
                GeneratedItem(
                    id = "gen_$i",
                    shape = shape,
                    color = color,
                    position = position
                )
            )
        }

        return items
    }

    /**
     * Convert HSV to [IsoColor] (RGB 0–255).
     */
    private fun hsvToIsoColor(h: Double, s: Double, v: Double): IsoColor {
        val c = v * s
        val x = c * (1.0 - abs((h / 60.0) % 2.0 - 1.0))
        val m = v - c

        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }

        return IsoColor(
            (r1 + m) * 255.0,
            (g1 + m) * 255.0,
            (b1 + m) * 255.0
        )
    }
}
