package io.github.jayteealao.isometric.view

import io.github.jayteealao.isometric.IsoColor

/**
 * Describes how shape faces are painted in an isometric scene rendered by [IsometricView].
 *
 * Three variants are available:
 * - [FillOnly] — fills faces with color but draws no outline. Eliminates the 1 px
 *   fringe that [FillAndStroke] produces on thin faces (e.g. `Prism(height = 0.2)`).
 * - [Stroke] — draws only the outline without filling the face.
 * - [FillAndStroke] — fills faces and draws an outline on top. This is the default.
 *
 * These semantics mirror the `StrokeStyle` type in the Compose rendering module.
 * A parallel type is used here to keep `isometric-android-view` self-contained
 * and independent of the Compose module.
 *
 * @see IsometricView.setStrokeStyle
 */
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
     * This is the default [StrokeStyle] used by [IsometricView].
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
