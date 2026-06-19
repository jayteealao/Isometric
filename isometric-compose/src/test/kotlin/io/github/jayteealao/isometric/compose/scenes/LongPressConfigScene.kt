@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Static geometry of `InteractionSamplesActivity.LongPressConfigSample`: a ground slab and a
 * single blue hold-target prism. The configurable long-press timeout
 * ([io.github.jayteealao.isometric.compose.runtime.GestureConfig.longPressTimeoutMs]) is a
 * timing property with no visual footprint, so this Paparazzi baseline simply pins the
 * scene's render — the timeout behaviour itself is covered device-free in
 * `NodeCallbacksInteractionTest`.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `LongPressConfigSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.LongPressConfigScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(2.0, 2.0, 0.1), 2.0, 2.0, 1.5), color = IsoColor.BLUE)
}
