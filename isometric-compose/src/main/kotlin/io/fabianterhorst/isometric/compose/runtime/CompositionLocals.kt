package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

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
    Vector(0.0, 1.0, 1.0).normalize()
}

/**
 * CompositionLocal for providing render options
 */
val LocalRenderOptions = compositionLocalOf {
    RenderOptions.Default
}

/**
 * CompositionLocal for providing default stroke width
 */
val LocalStrokeWidth = compositionLocalOf {
    1f
}

/**
 * CompositionLocal for enabling/disabling stroke drawing
 */
val LocalDrawStroke = compositionLocalOf {
    true
}

/**
 * CompositionLocal for providing a color palette
 *
 * Marked as @Immutable to prevent unnecessary recomposition when instances don't change
 */
@Immutable
data class ColorPalette(
    val primary: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val secondary: IsoColor = IsoColor(255.0, 100.0, 0.0),
    val accent: IsoColor = IsoColor(0.0, 200.0, 100.0),
    val background: IsoColor = IsoColor(245.0, 245.0, 245.0),
    val surface: IsoColor = IsoColor(255.0, 255.0, 255.0),
    val error: IsoColor = IsoColor(244.0, 67.0, 54.0)
)

val LocalColorPalette = compositionLocalOf {
    ColorPalette()
}

/**
 * CompositionLocal for animation speed multiplier
 */
val LocalAnimationSpeed = compositionLocalOf {
    1.0
}
