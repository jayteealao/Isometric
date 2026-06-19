@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Static geometry of `InteractionSamplesActivity.DoubleTapSample`: a ground slab and a single
 * orange target prism that carries both `onClick` and `onDoubleClick` in the live sample.
 * Single-tap vs. double-tap routing is a gesture behaviour with no visual footprint, so this
 * Paparazzi baseline pins the render only — the dispatch contract is covered device-free in
 * `NodeCallbacksInteractionTest`.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `DoubleTapSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.DoubleTapScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(2.0, 2.0, 0.1), 2.0, 2.0, 1.5), color = IsoColor.ORANGE)
}
