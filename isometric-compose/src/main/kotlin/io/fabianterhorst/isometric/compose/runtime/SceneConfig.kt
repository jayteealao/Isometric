package io.fabianterhorst.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine.Companion.DEFAULT_LIGHT_DIRECTION
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.Vector

@Immutable
open class SceneConfig(
    val renderOptions: RenderOptions = RenderOptions.Default,
    val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    val defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val colorPalette: ColorPalette = ColorPalette(),
    val strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    val gestures: GestureConfig = GestureConfig.Disabled,
    val useNativeCanvas: Boolean = false
) {
    override fun equals(other: Any?): Boolean =
        other != null &&
            other.javaClass == javaClass &&
            other is SceneConfig &&
            renderOptions == other.renderOptions &&
            lightDirection == other.lightDirection &&
            defaultColor == other.defaultColor &&
            colorPalette == other.colorPalette &&
            strokeStyle == other.strokeStyle &&
            gestures == other.gestures &&
            useNativeCanvas == other.useNativeCanvas

    override fun hashCode(): Int {
        var result = renderOptions.hashCode()
        result = 31 * result + lightDirection.hashCode()
        result = 31 * result + defaultColor.hashCode()
        result = 31 * result + colorPalette.hashCode()
        result = 31 * result + strokeStyle.hashCode()
        result = 31 * result + gestures.hashCode()
        result = 31 * result + useNativeCanvas.hashCode()
        return result
    }

    override fun toString(): String =
        "SceneConfig(renderOptions=$renderOptions, lightDirection=$lightDirection, defaultColor=$defaultColor, strokeStyle=$strokeStyle, gestures=$gestures, useNativeCanvas=$useNativeCanvas)"
}
