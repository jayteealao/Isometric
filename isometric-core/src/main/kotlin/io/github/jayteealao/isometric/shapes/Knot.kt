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
     */
    @ExperimentalIsometricApi
    val sourcePrisms: List<Prism> = listOf(
        Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
        Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
        Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
    )

    override fun translate(dx: Double, dy: Double, dz: Double): Knot =
        Knot(position.translate(dx, dy, dz))

    companion object {
        // If you change the Prism constants below, update `sourcePrisms` above to
        // match — UV generation for Knot relies on the two being in lock-step.
        private fun createPaths(position: Point): List<Path> {
            val allPaths = mutableListOf<Path>()

            // Add prisms
            allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
            allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
            allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)

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
