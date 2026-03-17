package io.github.jayteealao.isometric.compose

import androidx.compose.ui.graphics.Color
import io.github.jayteealao.isometric.IsoColor

/**
 * Converts this Compose [Color] to an [IsoColor].
 *
 * Maps each channel from the 0-1 float range to the 0-255 double range
 * used by [IsoColor].
 */
fun Color.toIsoColor(): IsoColor = IsoColor(
    r = (red * 255).toDouble(),
    g = (green * 255).toDouble(),
    b = (blue * 255).toDouble(),
    a = (alpha * 255).toDouble()
)

/**
 * Converts this [IsoColor] to a Compose [Color].
 *
 * Maps each channel from the 0-255 double range to the 0-1 float range
 * used by Compose [Color].
 */
fun IsoColor.toComposeColor(): Color = Color(
    red = (r / 255.0).toFloat(),
    green = (g / 255.0).toFloat(),
    blue = (b / 255.0).toFloat(),
    alpha = (a / 255.0).toFloat()
)
