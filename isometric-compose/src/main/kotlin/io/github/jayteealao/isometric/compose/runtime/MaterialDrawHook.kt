package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jayteealao.isometric.RenderCommand

/**
 * Hook for material-aware rendering in the native Canvas draw path.
 *
 * When a [RenderCommand] carries a non-null [RenderCommand.material], the renderer
 * delegates to this hook before falling back to flat-color drawing. The hook performs
 * the fill draw (e.g., textured `BitmapShader`) and returns `true`, or returns `false`
 * to let the renderer use its default flat-color fill.
 *
 * Implementations live in downstream modules (e.g., `isometric-shader`) that can
 * interpret the specific [io.github.jayteealao.isometric.MaterialData] subtype.
 * This keeps `isometric-compose` free of material-system dependencies.
 *
 * Stroke is always applied by the renderer after the fill, regardless of this hook's
 * return value.
 */
fun interface MaterialDrawHook {
    /**
     * Draw a material-aware render command.
     *
     * @param nativeCanvas The Android native canvas to draw on
     * @param command The render command with material and UV data
     * @param nativePath The pre-built native path for the command's polygon
     * @return `true` if the fill was drawn (hook handled it), `false` to fall back to flat color
     */
    fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean
}

/**
 * CompositionLocal for providing a [MaterialDrawHook] to the rendering pipeline.
 *
 * When `null` (default), all commands render as flat color — the current behavior.
 * The `isometric-shader` module's `ProvideTextureRendering` composable sets this
 * to enable textured Canvas rendering.
 *
 * Uses [staticCompositionLocalOf] because the hook is set once per scene and
 * rarely changes, avoiding unnecessary recomposition tracking.
 */
val LocalMaterialDrawHook = staticCompositionLocalOf<MaterialDrawHook?> { null }
