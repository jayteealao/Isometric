package io.github.jayteealao.isometric.shader.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
 * [CompositionLocal] that carries the active `onTextureLoadError` callback from
 * [ProvideTextureRendering] down to the WebGPU render backend.
 *
 * **Canonical provider:** [ProvideTextureRendering] sets this local automatically — do not
 * provide it directly unless you are implementing a custom render backend.
 *
 * **Default:** `null` (no error reporting; texture failures are silently logged via `Log.w`).
 *
 * **Threading:** The callback is always dispatched to the main thread by the backends that
 * read this local, regardless of which thread the failure is detected on.
 *
 * **Recomposition warning:** This local uses [staticCompositionLocalOf], which triggers
 * full-subtree recomposition whenever the provided value changes reference identity.
 * Always pass a stable callback reference — hoist the lambda outside the composition or
 * wrap it in `remember` — to avoid silently invalidating the entire scene subtree on each
 * recomposition.
 */
val LocalTextureErrorCallback: ProvidableCompositionLocal<((TextureSource) -> Unit)?> =
    staticCompositionLocalOf { null }

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
 * **Nesting:** If `ProvideTextureRendering` is nested, the innermost provider wins — it
 * completely replaces the outer provider for the subtree it wraps. There is no merging
 * of loaders or caches between nested providers.
 *
 * @param cacheConfig LRU cache configuration. Controls max in-memory texture count.
 * @param loader Custom texture loader. Override to intercept or transform bitmaps at load
 *   time. When `null`, the default Android resource/asset loader is used.
 * @param onTextureLoadError Called when a texture fails to load. Receives the [TextureSource]
 *   that failed. Use for analytics, user-visible error feedback, or retry logic.
 *   Always dispatched to the **main thread**, even when the failure is detected on a GPU
 *   thread. On WebGPU atlas overflow, the callback fires once per source in the failing
 *   batch — not just for the single source that caused the capacity constraint.
 *
 *   **Privacy caveat:** Do not log or transmit the [TextureSource] verbatim.
 *   [TextureSource.Asset.path] exposes the full internal asset path. Extract only the
 *   source kind for analytics: `source::class.simpleName`.
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
    val hook = remember(context, cacheConfig, loader, onTextureLoadError) {
        val cache = TextureCache(cacheConfig.maxSize)
        val effectiveLoader = loader ?: defaultTextureLoader(context.applicationContext)
        TexturedCanvasDrawHook(cache, effectiveLoader, onTextureLoadError)
    }
    CompositionLocalProvider(
        LocalMaterialDrawHook provides hook,
        LocalTextureErrorCallback provides onTextureLoadError,
    ) {
        content()
    }
}
