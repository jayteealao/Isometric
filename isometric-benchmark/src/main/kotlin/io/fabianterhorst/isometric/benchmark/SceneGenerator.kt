package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.shapes.Cylinder
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import kotlin.random.Random

data class SceneItem(
    val shape: Shape,
    val color: IsoColor,
    var position: Point  // Mutable for incremental updates
)

object SceneGenerator {
    /**
     * Generate deterministic scene with fixed seed
     *
     * @param size Number of objects (10, 100, 500, 1000)
     * @param seed Fixed seed for reproducibility
     * @param density Scene density (0.0=sparse, 1.0=dense)
     */
    fun generate(
        size: Int,
        seed: Long = 12345L,
        density: Float = 0.5f
    ): List<SceneItem> {
        val random = Random(seed)
        val spread = 20.0 / density  // Higher density = smaller spread

        return (0 until size).map { i ->
            val x = random.nextDouble(-spread, spread)
            val y = random.nextDouble(-spread, spread)
            val z = random.nextDouble(0.0, 5.0)
            val position = Point(x, y, z)

            val shape = when (i % 3) {
                0 -> Prism(position)
                1 -> Pyramid(position)
                else -> Cylinder(position, radius = 0.5, vertices = 20, height = 2.0)
            }

            val color = IsoColor(
                r = random.nextDouble(50.0, 255.0),
                g = random.nextDouble(50.0, 255.0),
                b = random.nextDouble(50.0, 255.0)
            )

            SceneItem(shape, color, position)
        }
    }

    /**
     * Mutate a percentage of scene items (for incremental update tests)
     */
    fun mutateScene(
        scene: List<SceneItem>,
        mutationRate: Float,  // 0.01 = 1%, 0.10 = 10%
        frameIndex: Int,
        seed: Long = 67890L
    ) {
        val random = Random(seed + frameIndex)
        val itemsToMutate = (scene.size * mutationRate).toInt()

        repeat(itemsToMutate) {
            val index = random.nextInt(scene.size)
            val item = scene[index]

            // Slightly offset position
            item.position = Point(
                item.position.x + random.nextDouble(-0.1, 0.1),
                item.position.y + random.nextDouble(-0.1, 0.1),
                item.position.z + random.nextDouble(-0.1, 0.1)
            )
        }
    }
}
