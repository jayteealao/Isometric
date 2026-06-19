@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Static geometry of `InteractionSamplesActivity.HoverRecipeSample` in its not-hovered baseline:
 * a ground slab and the orange hover target. The recipe tints the target on mouse / stylus hover
 * (`Modifier.hoverable` + `collectIsHoveredAsState`), which never fires under touch and has no
 * device-free hook — so this Paparazzi baseline captures the un-hovered render only, and hover
 * accuracy is the sample's one manual (real mouse / stylus) verification path.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` :: `HoverRecipeSample`.
 * If the sample changes, update here to match.
 */
@Composable
fun IsometricScope.HoverRecipeScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 6.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(1.0, 1.0, 0.1)), color = IsoColor.ORANGE)
}
