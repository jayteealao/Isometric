@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism

/**
 * The scene from `InteractionSamplesActivity.DragLifecycleSample`, extracted as a
 * reusable test factory: a flat floor slab and two contrasting prisms the user drags
 * across to exercise the `onDragStart` → `onDrag` → `onDragEnd` lifecycle.
 *
 * Use inside an `IsometricScene { DragLifecycleScene() }` block.
 *
 * Geometry kept in sync with `app/.../InteractionSamplesActivity.kt` ::
 * `DragLifecycleSample`. If the sample's geometry changes, update here to match.
 */
@Composable
fun IsometricScope.DragLifecycleScene() {
    Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
    Shape(geometry = Prism(Point(1.0, 1.0, 0.1), 1.5, 1.5, 1.5), color = IsoColor.BLUE)
    Shape(geometry = Prism(Point(4.0, 2.0, 0.1), 1.5, 1.5, 2.5), color = IsoColor.ORANGE)
}
