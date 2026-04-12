package io.github.jayteealao.isometric.shader.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.jayteealao.isometric.compose.runtime.LocalMaterialDrawHook

/**
 * Enables textured Canvas rendering for any `IsometricScene` in [content].
 *
 * Installs a [TexturedCanvasDrawHook] that intercepts material-aware `RenderCommand`s
 * and draws them with `BitmapShader` + affine matrix mapping. Flat-color commands are
 * unaffected.
 *
 * Usage:
 * ```kotlin
 * ProvideTextureRendering {
 *     IsometricScene {
 *         Shape(Prism(origin), material = textured(R.drawable.brick))
 *     }
 * }
 * ```
 *
 * @param maxCacheSize Maximum number of textures to keep in the LRU cache. Default: 20.
 * @param content The composable tree containing `IsometricScene`(s).
 */
@Composable
fun ProvideTextureRendering(
    maxCacheSize: Int = 20,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val hook = remember(context, maxCacheSize) {
        val cache = TextureCache(maxCacheSize)
        val loader = TextureLoader(context.applicationContext)
        TexturedCanvasDrawHook(cache, loader)
    }
    CompositionLocalProvider(LocalMaterialDrawHook provides hook) {
        content()
    }
}
