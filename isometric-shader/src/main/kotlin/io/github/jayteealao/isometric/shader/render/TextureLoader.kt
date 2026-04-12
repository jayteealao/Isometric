package io.github.jayteealao.isometric.shader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Loads a [TextureSource] to a decoded [Bitmap].
 *
 * All load operations are synchronous. A `null` return means the texture could not be
 * loaded (resource not found, decode error, OOM); callers should substitute a fallback.
 *
 * Thread safety: intended for main-thread use only (synchronous I/O acceptable for small
 * isometric tile textures; async preloading is a documented future enhancement).
 *
 * @param context Application context used for resource and asset loading.
 */
internal class TextureLoader(private val context: Context) {

    fun load(source: TextureSource): Bitmap? = when (source) {
        is TextureSource.Resource -> loadResource(source.resId)
        is TextureSource.Asset -> loadAsset(source.path)
        is TextureSource.BitmapSource -> {
            source.ensureNotRecycled()
            source.bitmap
        }
    }

    private fun loadResource(resId: Int): Bitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, resId)
    }.getOrNull()

    private fun loadAsset(path: String): Bitmap? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
