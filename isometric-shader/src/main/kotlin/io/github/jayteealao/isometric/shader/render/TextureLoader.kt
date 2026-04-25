package io.github.jayteealao.isometric.shader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.jayteealao.isometric.shader.TextureSource
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.sqrt

private const val TAG = "IsometricShader"

/** Maximum decoded bitmap size in bytes (64 MiB). Bitmaps larger than this are downsampled. */
private const val MAX_DECODED_BYTES = 64L * 1024L * 1024L

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
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, opts)
        val sampleSize = computeSampleSize(opts.outWidth, opts.outHeight, resId.toString())
            ?: return null
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeResource(context.resources, resId, decodeOpts)
    } catch (e: IOException) {
        Log.w(TAG, "Failed to load texture resource: Resource(id=$resId)", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM loading texture resource: Resource(id=$resId)", e)
        null
    }

    private fun loadAsset(path: String): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
        val sampleSize = computeSampleSize(opts.outWidth, opts.outHeight, "<asset>")
            ?: return null
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    } catch (e: IOException) {
        Log.w(TAG, "Failed to load texture asset: Asset(path=<redacted>)", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM loading texture asset: Asset(path=<redacted>)", e)
        null
    }

    private fun computeSampleSize(w: Int, h: Int, label: String): Int? =
        decideSampleSize(w, h, MAX_DECODED_BYTES).also { result ->
            when {
                result == null -> Log.w(TAG, "CT-SEC-2: texture $label ($w×$h) exceeds " +
                    "$MAX_DECODED_BYTES bytes even at maximum inSampleSize — skipping load")
                result > 1 -> {
                    val estimatedBytes = w.toLong() * h.toLong() * 4L
                    val sampledBytes = (w.toLong() / result) * (h.toLong() / result) * 4L
                    Log.d(TAG, "CT-SEC-2: texture $label ($w×$h) downsampled with " +
                        "inSampleSize=$result (estimated $estimatedBytes → ~$sampledBytes bytes)")
                }
            }
        }
}

/**
 * Compute the [android.graphics.BitmapFactory.Options.inSampleSize] needed to keep a decoded
 * bitmap within [maxBytes].
 *
 * Extracted as a package-internal pure function for JVM-unit testability (CT-SEC-2 / Step 6C).
 * The caller ([DefaultTextureLoader.computeSampleSize]) wraps this with Android-side logging.
 *
 * Returns `1` for images whose unsampled size is already within [maxBytes].
 * Returns a power-of-two >= 2 when downsampling is needed.
 * Returns `null` when no power-of-two sample size can bring the image within [maxBytes]
 * (pathological case — the caller should skip the load).
 *
 * @param w Image width in pixels (must be > 0; returns `1` for non-positive values).
 * @param h Image height in pixels (must be > 0; returns `1` for non-positive values).
 * @param maxBytes Maximum decoded byte size.
 */
internal fun decideSampleSize(w: Int, h: Int, maxBytes: Long): Int? {
    if (w <= 0 || h <= 0) return 1
    val estimatedBytes = w.toLong() * h.toLong() * 4L
    if (estimatedBytes <= maxBytes) return 1
    // Compute minimum inSampleSize such that (w/s)*(h/s)*4 <= maxBytes.
    // inSampleSize must be a power of two; use ceil(sqrt(ratio)) then round up to next PoT.
    val ratio = estimatedBytes.toDouble() / maxBytes.toDouble()
    val rawSampleSize = ceil(sqrt(ratio)).toInt().let { s ->
        var p = 1; while (p < s) p = p shl 1; p
    }
    // Verify the chosen sample size actually fits (guards against integer-division rounding).
    val sampledBytes = (w.toLong() / rawSampleSize) * (h.toLong() / rawSampleSize) * 4L
    return if (sampledBytes > maxBytes) null else rawSampleSize
}
