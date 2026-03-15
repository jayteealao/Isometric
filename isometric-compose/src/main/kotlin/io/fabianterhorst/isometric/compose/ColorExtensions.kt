package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import io.fabianterhorst.isometric.IsoColor

fun Color.toIsoColor(): IsoColor = IsoColor(
    r = (red * 255).toDouble(),
    g = (green * 255).toDouble(),
    b = (blue * 255).toDouble(),
    a = (alpha * 255).toDouble()
)

fun IsoColor.toComposeColor(): Color = Color(
    red = (r / 255.0).toFloat(),
    green = (g / 255.0).toFloat(),
    blue = (b / 255.0).toFloat(),
    alpha = (a / 255.0).toFloat()
)
