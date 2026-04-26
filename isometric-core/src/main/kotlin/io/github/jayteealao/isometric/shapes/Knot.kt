package io.github.jayteealao.isometric.shapes

import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape

/**
 * A knot shape composed of interlocking prisms and custom faces.
 *
 * **Experimental**: This shape has a known depth-sorting issue where
 * overlapping internal faces may render in incorrect order. Use with
 * caution in scenes that require precise depth accuracy.
 *
 * **Texture support**: `textured()` (via `IsometricMaterial.Textured`) is the only
 * material option for `Knot`. `perFace {}` is not supported — `Knot` has no named
 * face taxonomy, and the shape's depth-sorting bug makes per-face visual results
 * unreliable. If a `PerFace` material is passed to a `Shape(Knot(...), material)`
 * composable, every face resolves to the `PerFace.default` material.
 */
@ExperimentalIsometricApi
class Knot(val position: Point = Point.ORIGIN) : Shape(createPaths(position)) {

    /**
     * The three source [Prism] instances composing the knot's body, in pre-transform
     * (unscaled, untranslated) space. Index 0 → faces 0–5, index 1 → faces 6–11,
     * index 2 → faces 12–17. Faces 18 and 19 are custom quads with no source Prism.
     *
     * Consumed by `UvGenerator.forKnotFace` to delegate UV generation for each
     * sub-prism block to `UvGenerator.forPrismFace` using each Prism's own
     * dimensional extents. The values must stay in sync with the constants in
     * [createPaths]; a regression guard pins this in `UvGeneratorKnotTest`.
     *
     * **Immutability guarantee (U-01):** The backing list is created via [listOf], which
     * returns Java's `Arrays.asList` — a fixed-size, unmodifiable view. Callers must not
     * attempt to mutate the returned list; doing so throws [UnsupportedOperationException].
     * The [Prism] instances themselves are also immutable after construction.
     */
    @ExperimentalIsometricApi
    val sourcePrisms: List<Prism> = listOf(KNOT_PRISM_0, KNOT_PRISM_1, KNOT_PRISM_2)

    override fun translate(dx: Double, dy: Double, dz: Double): Knot =
        Knot(position.translate(dx, dy, dz))

    companion object {
        // Prism geometry constants — shared between `sourcePrisms` and `createPaths`
        // so that UV generation (which reads `sourcePrisms`) and path generation stay
        // in lock-step. Change here; both users update automatically.
        private val KNOT_PRISM_0 = Prism(Point.ORIGIN,           width = 5.0, depth = 1.0, height = 1.0)
        private val KNOT_PRISM_1 = Prism(Point(4.0, 1.0, 0.0),  width = 1.0, depth = 4.0, height = 1.0)
        private val KNOT_PRISM_2 = Prism(Point(4.0, 4.0, -2.0), width = 1.0, depth = 1.0, height = 3.0)

        private fun createPaths(position: Point): List<Path> {
            val allPaths = mutableListOf<Path>()

            // Add prisms
            allPaths.addAll(KNOT_PRISM_0.paths)
            allPaths.addAll(KNOT_PRISM_1.paths)
            allPaths.addAll(KNOT_PRISM_2.paths)

            // Add custom paths
            allPaths.add(
                Path(
                    Point(0.0, 0.0, 2.0),
                    Point(0.0, 0.0, 1.0),
                    Point(1.0, 0.0, 1.0),
                    Point(1.0, 0.0, 2.0)
                )
            )
            allPaths.add(
                Path(
                    Point(0.0, 0.0, 2.0),
                    Point(0.0, 1.0, 2.0),
                    Point(0.0, 1.0, 1.0),
                    Point(0.0, 0.0, 1.0)
                )
            )

            // Scale and translate all paths
            val scaledPaths = allPaths.map { it.scale(Point.ORIGIN, 1.0 / 5.0) }
            val translatedPaths = scaledPaths.map { it.translate(-0.1, 0.15, 0.4) }
            val finalPaths = translatedPaths.map { it.translate(position.x, position.y, position.z) }

            return finalPaths
        }
    }
}
