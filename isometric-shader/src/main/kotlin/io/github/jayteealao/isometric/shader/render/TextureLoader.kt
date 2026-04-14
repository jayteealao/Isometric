package io.github.jayteealao.isometric.shader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.jayteealao.isometric.shader.TextureSource
import java.io.IOException

private const val TAG = "IsometricShader"

/**
 * Loads a [TextureSource] to a decoded [Bitmap].
 *
 * All load operations are synchronous. A `null` return means the texture could not be
 * loaded (resource not found, decode error, OOM); callers should substitute a fallback.
 *
 * Implement this interface to intercept or transform bitmaps at load time:
 * ```kotlin
 * ProvideTextureRendering(loader = TextureLoader { source ->
 *     // custom loading logic
 *     myImageLoader.load(source)
 * }) { ... }
 * ```
 *
 * Thread safety: intended for main-thread use only (synchronous I/O acceptable for small
 * isometric tile textures; async preloading is a documented future enhancement).
 */
fun interface TextureLoader {
    /**
     * Loads the given [source] and returns a decoded [Bitmap], or `null` on failure.
     *
     * Implementations may return `null` for recoverable failures such as a missing resource,
     * a decode error, or [OutOfMemoryError]. Unrecoverable programming errors (e.g.
     * [NullPointerException]) are **not** caught and will propagate to the caller.
     *
     * @param source The texture source to load
     * @return [Bitmap] if the texture was loaded successfully, or null if the load failed
     *   (e.g. source not found, I/O error, or out of memory). Callers must handle null by
     *   providing a fallback.
     * @throws IOException if an I/O error occurs while reading the texture data
     */
    fun load(source: TextureSource): Bitmap?
}

/**
 * Constructs the default [TextureLoader] backed by Android resource/asset loading.
 *
 * @param context Application context for resource and asset access.
 */
internal fun defaultTextureLoader(context: Context): TextureLoader = DefaultTextureLoader(context)

private class DefaultTextureLoader(private val context: Context) : TextureLoader {

    override fun load(source: TextureSource): Bitmap? = when (source) {
        is TextureSource.Resource -> loadResource(source.resId)
        is TextureSource.Asset -> loadAsset(source.path)
        is TextureSource.Bitmap -> {
            source.ensureNotRecycled()
            source.bitmap
        }
    }

    private fun loadResource(resId: Int): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, resId)
    } catch (e: IOException) {
        Log.w(TAG, "Failed to load texture resource: Resource(id=$resId)", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM loading texture resource: Resource(id=$resId)", e)
        null
    }

    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: IOException) {
        Log.w(TAG, "Failed to load texture asset: Asset(path=<redacted>)", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM loading texture asset: Asset(path=<redacted>)", e)
        null
    }
}
