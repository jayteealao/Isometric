@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * The 4-building scene from `InteractionSamplesActivity.NodeIdSample`,
 * extracted as a reusable test factory. This is the canonical geometry
 * that exposed the depth-sort shared-edge overpaint bug (factory's top
 * face painting over hq's right wall).
 *
 * Use inside an `IsometricScene { WS10NodeIdScene() }` block.
 */
@Composable
fun IsometricScope.WS10NodeIdScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 10.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(0.0, 1.0, 0.1), 1.5, 1.5, 3.0), color = IsoColor.BLUE)
    Shape(geometry = Prism(Point(2.0, 1.0, 0.1), 1.5, 1.5, 2.0), color = IsoColor.ORANGE)
    Shape(geometry = Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 1.5), color = IsoColor.GREEN)
    Shape(geometry = Prism(Point(6.0, 1.0, 0.1), 1.5, 1.5, 4.0), color = IsoColor.PURPLE)
}
