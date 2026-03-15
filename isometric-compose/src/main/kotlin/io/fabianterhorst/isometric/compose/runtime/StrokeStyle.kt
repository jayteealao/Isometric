package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.IsoColor

@Immutable
sealed class StrokeStyle {
    data object FillOnly : StrokeStyle()

    data class Stroke(
        val width: Float = 1f,
        val color: IsoColor = IsoColor.BLACK
    ) : StrokeStyle() {
        init {
            require(width > 0f) { "Stroke width must be positive, got $width" }
        }
    }

    data class FillAndStroke(
        val width: Float = 1f,
        val color: IsoColor = IsoColor.BLACK
    ) : StrokeStyle() {
        init {
            require(width > 0f) { "Stroke width must be positive, got $width" }
        }
    }
}
