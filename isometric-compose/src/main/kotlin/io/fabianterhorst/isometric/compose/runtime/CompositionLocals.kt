package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.SceneProjector

/**
 * CompositionLocal for providing default color to shapes
 */
val LocalDefaultColor = compositionLocalOf {
    IsoColor(33.0, 150.0, 243.0) // Default blue
}

/**
 * CompositionLocal for providing light direction to the scene
 */
val LocalLightDirection = compositionLocalOf {
    SceneProjector.DEFAULT_LIGHT_DIRECTION.normalize()
}

/**
 * CompositionLocal for providing render options
 */
val LocalRenderOptions = compositionLocalOf {
    RenderOptions.Default
}

val LocalStrokeStyle = compositionLocalOf<StrokeStyle> {
    StrokeStyle.FillAndStroke()
}

/**
 * CompositionLocal for providing a color palette
 *
 * Marked as @Immutable to prevent unnecessary recomposition when instances don't change
 */
@Immutable
class ColorPalette(
    val primary: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val secondary: IsoColor = IsoColor(255.0, 100.0, 0.0),
    val accent: IsoColor = IsoColor(0.0, 200.0, 100.0),
    val background: IsoColor = IsoColor(245.0, 245.0, 245.0),
    val surface: IsoColor = IsoColor(255.0, 255.0, 255.0),
    val error: IsoColor = IsoColor(244.0, 67.0, 54.0)
) {
    fun copy(
        primary: IsoColor = this.primary,
        secondary: IsoColor = this.secondary,
        accent: IsoColor = this.accent,
        background: IsoColor = this.background,
        surface: IsoColor = this.surface,
        error: IsoColor = this.error
    ): ColorPalette = ColorPalette(primary, secondary, accent, background, surface, error)

    override fun equals(other: Any?): Boolean =
        other is ColorPalette &&
            primary == other.primary &&
            secondary == other.secondary &&
            accent == other.accent &&
            background == other.background &&
            surface == other.surface &&
            error == other.error

    override fun hashCode(): Int {
        var result = primary.hashCode()
        result = 31 * result + secondary.hashCode()
        result = 31 * result + accent.hashCode()
        result = 31 * result + background.hashCode()
        result = 31 * result + surface.hashCode()
        result = 31 * result + error.hashCode()
        return result
    }

    override fun toString(): String =
        "ColorPalette(primary=$primary, secondary=$secondary, accent=$accent, background=$background, surface=$surface, error=$error)"
}

val LocalColorPalette = compositionLocalOf {
    ColorPalette()
}

/**
 * CompositionLocal for providing benchmark hooks to the renderer.
 *
 * Uses [staticCompositionLocalOf] because hooks are set once per benchmark run
 * and rarely change, avoiding unnecessary recomposition tracking.
 * Defaults to null (no instrumentation) in production.
 */
val LocalBenchmarkHooks = staticCompositionLocalOf<RenderBenchmarkHooks?> { null }
