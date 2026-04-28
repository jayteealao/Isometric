@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * The 3x3 grid from `InteractionSamplesActivity.LongPressSample`, extracted
 * as a reusable test factory. Nine unit prisms at `Point(col * 1.8, row * 1.8, 0.1)`
 * for `col, row ∈ {0, 1, 2}`, each width=depth=1.2 and height=1.0, with
 * column- and row-derived colors.
 *
 * This factory's **default static state** (no shape locked) is the canonical
 * marker for the over-aggressive-edge regression in the depth-sort pipeline.
 * Without [IntersectionUtils.hasInteriorIntersection] gating edge insertion in
 * [DepthSorter.checkDepthDependency], the back-right cube renders with only
 * its top face visible because boundary-only-contact face pairs add spurious
 * topological edges that pin the cube's vertical sides too early in the draw
 * order. The strict screen-overlap gate restores correct rendering.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` ::
 * `LongPressSample`. If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.LongPressGridScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    for (i in 0 until 9) {
        val row = i / 3
        val col = i % 3
        Shape(
            geometry = Prism(Point(col * 1.8, row * 1.8, 0.1), 1.2, 1.2, 1.0),
            color = IsoColor((col + 1) * 80.0, (row + 1) * 80.0, 150.0)
        )
    }
}
