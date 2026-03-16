package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.IsoColor

/**
 * Describes how shape faces are painted in an isometric scene.
 *
 * Three variants are available:
 * - [FillOnly] -- fills faces with color but draws no outline.
 * - [Stroke] -- draws only the outline (no fill).
 * - [FillAndStroke] -- fills faces and draws an outline on top (the default).
 *
 * @see SceneConfig.strokeStyle
 */
@Immutable
sealed class StrokeStyle {
    companion object {
        /** Default stroke color: black at ~10 % opacity (`alpha = 25`). */
        val DefaultStrokeColor: IsoColor = IsoColor(0.0, 0.0, 0.0, 25.0)
    }

    /** Fills shape faces with color only; no outline is drawn. */
    data object FillOnly : StrokeStyle()

    /**
     * Draws only the outline of shape faces without filling them.
     *
     * @param width Stroke width in pixels. Must be positive.
     * @param color Stroke [IsoColor]. Defaults to [DefaultStrokeColor].
     */
    data class Stroke(
        val width: Float = 1f,
        val color: IsoColor = DefaultStrokeColor
    ) : StrokeStyle() {
        init {
            require(width > 0f) { "Stroke width must be positive, got $width" }
        }
    }

    /**
     * Fills shape faces with color and draws an outline on top.
     *
     * This is the default [StrokeStyle] used by [SceneConfig].
     *
     * @param width Stroke width in pixels. Must be positive.
     * @param color Stroke [IsoColor]. Defaults to [DefaultStrokeColor].
     */
    data class FillAndStroke(
        val width: Float = 1f,
        val color: IsoColor = DefaultStrokeColor
    ) : StrokeStyle() {
        init {
            require(width > 0f) { "Stroke width must be positive, got $width" }
        }
    }
}
