package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Immutable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.SceneProjector.Companion.DEFAULT_LIGHT_DIRECTION
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.Vector

/**
 * Core configuration for an isometric scene.
 *
 * Bundles rendering, lighting, color, stroke, gesture, and camera settings into a
 * single immutable value that drives [IsometricScene] behaviour. Extend with
 * [AdvancedSceneConfig] when hook callbacks or engine-level tuning are needed.
 *
 * @param renderOptions Controls rendering behaviour such as sorting and face culling.
 * @param lightDirection Normalized direction vector for the scene's light source, used
 *   to compute per-face shading.
 * @param defaultColor Fallback [IsoColor] applied to shapes that do not specify their own color.
 * @param colorPalette A [ColorPalette] providing named semantic colors (primary, secondary, etc.)
 *   that shapes can reference for consistent theming.
 * @param strokeStyle Determines how shape outlines are drawn. Defaults to [StrokeStyle.FillAndStroke].
 * @param gestures A [GestureConfig] controlling tap and drag interaction with the scene.
 *   Defaults to [GestureConfig.Disabled].
 * @param useNativeCanvas When `true`, renders directly to the platform's native canvas
 *   instead of the Compose draw scope. May improve performance on some devices.
 * @param cameraState Optional [CameraState] for pan/zoom control. When `null`, the scene
 *   uses a fixed viewport. Equality is checked by reference identity.
 * @param renderMode Determines how the scene is rendered and computed.
 *   Defaults to [RenderMode.Canvas] with CPU sort.
 */
@Immutable
open class SceneConfig(
    val renderOptions: RenderOptions = RenderOptions.Default,
    val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize(),
    val defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    val colorPalette: ColorPalette = ColorPalette(),
    val strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    val gestures: GestureConfig = GestureConfig.Disabled,
    val useNativeCanvas: Boolean = false,
    val cameraState: CameraState? = null,
    val renderMode: RenderMode = RenderMode.Canvas(),
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
            useNativeCanvas == other.useNativeCanvas &&
            cameraState === other.cameraState &&
            renderMode == other.renderMode

    override fun hashCode(): Int {
        var result = renderOptions.hashCode()
        result = 31 * result + lightDirection.hashCode()
        result = 31 * result + defaultColor.hashCode()
        result = 31 * result + colorPalette.hashCode()
        result = 31 * result + strokeStyle.hashCode()
        result = 31 * result + gestures.hashCode()
        result = 31 * result + useNativeCanvas.hashCode()
        result = 31 * result + (cameraState?.let { System.identityHashCode(it) } ?: 0)
        result = 31 * result + renderMode.hashCode()
        return result
    }

    override fun toString(): String =
        "SceneConfig(renderOptions=$renderOptions, lightDirection=$lightDirection, defaultColor=$defaultColor, strokeStyle=$strokeStyle, gestures=$gestures, useNativeCanvas=$useNativeCanvas, cameraState=$cameraState, renderMode=$renderMode)"
}
