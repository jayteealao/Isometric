@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid

/**
 * Static geometry of `InteractionSamplesActivity.CameraControlSample`: a ground slab plus three
 * landmark shapes (prism, pyramid, cylinder). Pan / zoom / reset are camera-state operations with
 * no effect on the un-transformed scene, so this Paparazzi baseline pins the default render
 * (pan = 0, zoom = 1); the [io.github.jayteealao.isometric.compose.runtime.CameraState] contract
 * the Camera tab drives is covered device-free in `CameraStateInteractionTest`.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `CameraControlSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.CameraControlScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))
    Shape(geometry = Pyramid(Point(2.0, 0.0, 0.0)), color = IsoColor(255.0, 100.0, 0.0))
    Shape(geometry = Cylinder(Point(-2.0, 0.0, 0.0), 0.5, 2.0, 20), color = IsoColor(0.0, 200.0, 100.0))
}
