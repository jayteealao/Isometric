@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.Batch
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid

/**
 * The mixed-geometry scene from `InteractionSamplesActivity.AlphaSample`,
 * extracted as a reusable test factory. Combines a large prism, a cylinder,
 * a pyramid, and a row of three smaller prisms at varying heights.
 *
 * The alpha values applied in the live sample are intentionally NOT replicated
 * here — alpha is a per-shape render modifier, while the regression we're
 * pinning manifests in the depth sort itself. The geometry alone reproduces
 * the over-aggressive-edge case where some prism pairs share boundary edges
 * in iso-projected screen space.
 *
 * Structurally mirrors the live sample: the row of three CYAN prisms is
 * wrapped in a [Batch] to match how `AlphaSample` groups them. The depth
 * sort sees identical face decomposition either way (a Batch lowers to one
 * SceneItem per shape internally), but keeping the structure parallel means
 * future changes to Batch face decomposition affect the snapshot in lockstep
 * with the live sample.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` ::
 * `AlphaSample`. If the sample's geometry changes, update here to match.
 */
@Composable
fun IsometricScope.AlphaSampleScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.DARK_GRAY)
    Shape(geometry = Prism(Point(0.0, 0.0, 0.1), 2.0, 2.0, 2.0), color = IsoColor.BLUE)
    Shape(
        geometry = Cylinder(Point(3.0, 0.0, 0.1), radius = 0.8, height = 2.5, vertices = 20),
        color = IsoColor.RED
    )
    Shape(geometry = Pyramid(Point(0.0, 3.0, 0.1)), color = IsoColor.GREEN)
    Batch(
        shapes = listOf(
            Prism(position = Point(3.5, 3.0, 0.1), width = 0.6, depth = 0.6, height = 0.8),
            Prism(position = Point(4.3, 3.0, 0.1), width = 0.6, depth = 0.6, height = 1.2),
            Prism(position = Point(5.1, 3.0, 0.1), width = 0.6, depth = 0.6, height = 1.6)
        ),
        color = IsoColor.CYAN
    )
}
