package io.github.jayteealao.isometric.shader.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.jayteealao.isometric.compose.runtime.LocalMaterialDrawHook
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Configuration for the texture LRU cache used by [ProvideTextureRendering].
 *
 * @param maxSize Maximum number of distinct [TextureSource]s to keep decoded in memory.
 *   When the cache is full, the least-recently-used entry is evicted synchronously.
 *   On a cache miss the texture is decoded synchronously on the first draw frame that
 *   needs it.
 *
 *   **Sizing guidance:** count distinct [TextureSource] keys your scene uses. 20 covers
 *   most isometric tile sets (e.g., a 4×4 grid of 3 distinct textures uses 3 slots).
 *   Increase for large tile sets with many unique textures.
 */
data class TextureCacheConfig(val maxSize: Int = 20) {
    init {
        require(maxSize > 0) { "maxSize must be positive, got $maxSize" }
    }
}

/**
 * Enables textured Canvas rendering for any `IsometricScene` in [content].
 *
 * Installs a [TexturedCanvasDrawHook] that intercepts material-aware `RenderCommand`s
 * and draws them with `BitmapShader` + affine matrix mapping. Flat-color commands are
 * unaffected.
 *
 * **Scoping rules:** Install one `ProvideTextureRendering` per composable subtree.
 * A provider does not share its cache with sibling or parent providers — nest providers
 * only if subtrees intentionally need independent caches. A single top-level provider
 * covering all scenes is the typical usage:
 *
 * ```kotlin
 * ProvideTextureRendering {
 *     IsometricScene {
 *         Shape(Prism(origin), material = texturedResource(R.drawable.brick))
 *     }
 * }
 * ```
 *
 * **Custom loader:**
 * ```kotlin
 * ProvideTextureRendering(loader = TextureLoader { source ->
 *     myImageLoader.loadSync(source)
 * }) { ... }
 * ```
 *
 * **Error callback:**
 * ```kotlin
 * ProvideTextureRendering(onTextureLoadError = { source ->
 *     analytics.logEvent("texture_load_failed", source.toString())
 * }) { ... }
 * ```
 *
 * @param cacheConfig LRU cache configuration. Controls max in-memory texture count.
 * @param loader Custom texture loader. Override to intercept or transform bitmaps at load
 *   time. When `null`, the default Android resource/asset loader is used.
 * @param onTextureLoadError Called when a texture fails to load. Receives the [TextureSource]
 *   that failed. Use for analytics, user-visible error feedback, or retry logic.
 * @param content The composable tree containing `IsometricScene`(s).
 */
@Composable
fun ProvideTextureRendering(
    cacheConfig: TextureCacheConfig = TextureCacheConfig(),
    loader: TextureLoader? = null,
    onTextureLoadError: ((TextureSource) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentLoader by rememberUpdatedState(loader)
    val currentOnError by rememberUpdatedState(onTextureLoadError)
    val hook = remember(context, cacheConfig) {
        val cache = TextureCache(cacheConfig.maxSize)
        val effectiveLoader = currentLoader ?: defaultTextureLoader(context.applicationContext)
        TexturedCanvasDrawHook(cache, effectiveLoader, currentOnError)
    }
    CompositionLocalProvider(LocalMaterialDrawHook provides hook) {
        content()
    }
}
