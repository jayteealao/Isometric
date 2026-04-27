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
 * regression case for amendment 1: the over-aggressive topological-edge
 * regression in commit `3e811aa` caused the back-right cube to render with
 * only its top face visible. The screen-overlap gate added in this fix
 * (`IntersectionUtils.hasInteriorIntersection`) restores correct rendering.
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
