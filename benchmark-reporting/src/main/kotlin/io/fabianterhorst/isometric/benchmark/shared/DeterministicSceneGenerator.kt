package io.fabianterhorst.isometric.benchmark.shared

import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point
import io.fabianterhorst.isometric.Shape
import io.fabianterhorst.isometric.shapes.Cylinder
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import kotlin.random.Random

/**
 * Configuration for scene generation
 */
data class SceneConfig(
    val objectCount: Int,
    val seed: Long = 12345L,
    val density: OverlapDensity = OverlapDensity.MEDIUM,
    val shapeDistribution: ShapeDistribution = ShapeDistribution.MIXED
)

/**
 * Defines the spatial density of objects in the scene
 */
enum class OverlapDensity(val spreadFactor: Double) {
    SPARSE(40.0),      // Low overlap
    MEDIUM(20.0),      // Moderate overlap
    DENSE(10.0),       // High overlap (stress depth sorting)
    EXTREME(5.0)       // Very high overlap
}

/**
 * Defines the distribution of shape types in the scene
 */
enum class ShapeDistribution {
    MIXED,           // Equal distribution of shapes
    PRISM_HEAVY,     // 70% prisms, 15% pyramids, 15% cylinders
    CYLINDER_HEAVY,  // 70% cylinders
    UNIFORM_CUBES    // All same-size prisms
}

/**
 * Represents a single item in the scene
 */
data class SceneItem(
    val shape: Shape,
    val color: IsoColor,
    var position: Point  // Mutable for mutations
)

/**
 * Defines how the scene mutates over time
 */
enum class MutationType {
    STATIC,         // No changes
    SMALL_DELTA,    // 1-5 objects move per frame
    FULL_MUTATION   // All objects change per frame
}

/**
 * Defines user interaction patterns
 */
enum class InputPattern {
    NONE,        // No interaction
    OCCASIONAL,  // 1 tap/sec (every 60 frames)
    CONTINUOUS,  // Every frame
    HOVER        // Every 2 frames
}

/**
 * Deterministic scene generator for benchmarking
 *
 * Generates reproducible scenes based on configuration and seed values.
 * All randomness is seeded to ensure identical outputs across runs.
 */
object DeterministicSceneGenerator {

    /**
     * Generate a deterministic scene based on the provided configuration
     *
     * @param config Scene configuration including object count, seed, density, and distribution
     * @return List of scene items with shapes, colors, and positions
     */
    fun generate(config: SceneConfig): List<SceneItem> {
        val random = Random(config.seed)
        val items = mutableListOf<SceneItem>()

        for (i in 0 until config.objectCount) {
            // Generate position based on density spread factor
            val x = random.nextDouble(-config.density.spreadFactor, config.density.spreadFactor)
            val y = random.nextDouble(-config.density.spreadFactor, config.density.spreadFactor)
            val z = random.nextDouble(0.0, 5.0)

            val position = Point(x, y, z)
            val shape = createShape(i, position, config.shapeDistribution, random)
            val color = createColor(random)

            items.add(SceneItem(shape, color, position))
        }

        return items
    }

    /**
     * Create a shape based on index, position, distribution, and random generator
     *
     * @param index Index of the object being created
     * @param position Position for the shape
     * @param distribution Shape distribution pattern
     * @param random Seeded random generator
     * @return Generated shape
     */
    private fun createShape(
        index: Int,
        position: Point,
        distribution: ShapeDistribution,
        random: Random
    ): Shape {
        return when (distribution) {
            ShapeDistribution.MIXED -> {
                // Equal distribution: cycle through shape types
                when (index % 3) {
                    0 -> Prism(position, 1.0, 1.0, 1.0)
                    1 -> Pyramid(position, 1.0, 1.0, 1.0)
                    else -> Cylinder(position, 0.5, 20, 2.0)
                }
            }

            ShapeDistribution.PRISM_HEAVY -> {
                // 70% prisms, 15% pyramids, 15% cylinders
                val roll = random.nextDouble()
                when {
                    roll < 0.70 -> Prism(position, 1.0, 1.0, 1.0)
                    roll < 0.85 -> Pyramid(position, 1.0, 1.0, 1.0)
                    else -> Cylinder(position, 0.5, 20, 2.0)
                }
            }

            ShapeDistribution.CYLINDER_HEAVY -> {
                // 70% cylinders, 30% split between others
                val roll = random.nextDouble()
                when {
                    roll < 0.70 -> Cylinder(position, 0.5, 20, 2.0)
                    roll < 0.85 -> Prism(position, 1.0, 1.0, 1.0)
                    else -> Pyramid(position, 1.0, 1.0, 1.0)
                }
            }

            ShapeDistribution.UNIFORM_CUBES -> {
                // All same-size prisms
                Prism(position, 1.0, 1.0, 1.0)
            }
        }
    }

    /**
     * Create a random color
     *
     * @param random Seeded random generator
     * @return Generated color with RGB values between 50 and 255
     */
    private fun createColor(random: Random): IsoColor {
        val r = random.nextDouble(50.0, 255.0)
        val g = random.nextDouble(50.0, 255.0)
        val b = random.nextDouble(50.0, 255.0)
        return IsoColor(r, g, b)
    }

    /**
     * Mutate a scene based on mutation type
     *
     * @param scene List of scene items to mutate
     * @param mutationType Type of mutation to apply
     * @param frameIndex Current frame number (used for deterministic selection)
     * @param seed Random seed for mutation randomness
     */
    fun mutateScene(
        scene: List<SceneItem>,
        mutationType: MutationType,
        frameIndex: Int,
        seed: Long = 67890L
    ) {
        when (mutationType) {
            MutationType.STATIC -> {
                // No changes
                return
            }

            MutationType.SMALL_DELTA -> {
                // Mutate 1-5 objects per frame (minimum 5% of scene)
                val mutationCount = minOf(5, maxOf(1, scene.size / 20))
                val random = Random(seed + frameIndex)

                repeat(mutationCount) {
                    val item = scene[random.nextInt(scene.size)]
                    mutatePosition(item, random)
                }
            }

            MutationType.FULL_MUTATION -> {
                // Mutate all objects
                val random = Random(seed + frameIndex)
                scene.forEach { item ->
                    mutatePosition(item, random)
                }
            }
        }
    }

    /**
     * Apply a small random delta to an item's position
     *
     * @param item Scene item to mutate
     * @param random Seeded random generator
     */
    private fun mutatePosition(item: SceneItem, random: Random) {
        val deltaX = random.nextDouble(-0.1, 0.1)
        val deltaY = random.nextDouble(-0.1, 0.1)
        val deltaZ = random.nextDouble(-0.1, 0.1)

        item.position = Point(
            item.position.x + deltaX,
            item.position.y + deltaY,
            item.position.z + deltaZ
        )
    }

    /**
     * Determine if interaction should occur on this frame based on pattern
     *
     * @param frameIndex Current frame number
     * @param pattern Input pattern to check against
     * @return True if interaction should occur on this frame
     */
    fun shouldInteract(frameIndex: Int, pattern: InputPattern): Boolean {
        return when (pattern) {
            InputPattern.NONE -> false
            InputPattern.OCCASIONAL -> frameIndex % 60 == 0  // 1 tap/sec at 60fps
            InputPattern.CONTINUOUS -> true                   // Every frame
            InputPattern.HOVER -> frameIndex % 2 == 0         // Every 2 frames
        }
    }
}
