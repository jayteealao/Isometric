@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * The 5-shape row from `InteractionSamplesActivity.OnClickSample`, extracted
 * as a reusable test factory. Each shape sits at `Point(i * 1.5, 0.0, 0.1)`
 * with width=depth=1.0. When [selectedIndex] is non-null, that shape's height
 * bumps from 1.0 to 2.0 and its color becomes [IsoColor.YELLOW] — replicating
 * the OnClickSample's tap-to-select state.
 *
 * Pins the regression-free render of the row-layout shared-edge case so future
 * depth-sort algorithm changes can re-verify it visually.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` ::
 * `OnClickSample`. If that sample's positions or dimensions change, update
 * here to match.
 */
@Composable
fun IsometricScope.OnClickRowScene(selectedIndex: Int? = null) {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 10.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    val palette = listOf(IsoColor.RED, IsoColor.GREEN, IsoColor.BLUE, IsoColor.ORANGE, IsoColor.PURPLE)
    palette.forEachIndexed { i, color ->
        val isSelected = selectedIndex == i
        Shape(
            geometry = Prism(
                position = Point(i * 1.5, 0.0, 0.1),
                width = 1.0, depth = 1.0,
                height = if (isSelected) 2.0 else 1.0
            ),
            color = if (isSelected) IsoColor.YELLOW else color
        )
    }
}
