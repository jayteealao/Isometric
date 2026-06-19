@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Static geometry of `InteractionSamplesActivity.PinchZoomRecipeSample`: a ground slab and one
 * landmark prism. The pinch recipe maps a `detectTransformGestures` scale ratio onto
 * [io.github.jayteealao.isometric.compose.runtime.CameraState.zoomBy] — a gesture-driven camera
 * mutation with no static footprint, so this Paparazzi baseline pins the default (un-zoomed)
 * render only. The zoom-factor → `zoomBy` mapping is covered device-free in
 * `CameraStateInteractionTest`.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `PinchZoomRecipeSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.PinchZoomRecipeScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 6.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(1.0, 1.0, 0.0)), color = IsoColor.BLUE)
}
