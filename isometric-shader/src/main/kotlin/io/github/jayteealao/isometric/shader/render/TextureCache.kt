package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Cached texture entry holding the decoded [Bitmap].
 *
 * [android.graphics.BitmapShader] creation is deferred to the draw hook so that
 * the correct [android.graphics.Shader.TileMode] (CLAMP vs REPEAT) can be chosen
 * based on the [io.github.jayteealao.isometric.shader.TextureTransform] at draw time.
 */
internal data class CachedTexture(val bitmap: Bitmap)

/**
 * LRU cache mapping [TextureSource] keys to [CachedTexture] entries.
 *
 * Uses `LinkedHashMap` with `accessOrder = true` for O(1) LRU eviction.
 *
 * Thread safety: intended for main-thread use only. Canvas draw path runs on the
 * main thread so no concurrent access is expected.
 *
 * **Cache key identity:** Keys are [TextureSource] instances. [TextureSource.Resource]
 * and [TextureSource.Asset] are data classes with structural equality. However,
 * [TextureSource.Bitmap] uses `Bitmap` reference identity (since `Bitmap` does
 * not override `equals`). Callers using `TextureSource.Bitmap` should `remember { }` their
 * bitmaps to avoid creating new cache entries on every recomposition.
 *
 * @param maxSize Maximum number of textures to keep in memory.
 * @param maxBytes Optional maximum total decoded byte size. When non-null, the cache
 *   evicts LRU entries until both the count and byte constraints are satisfied.
 *   Defaults to `null` (no byte cap).
 */
internal class TextureCache(val maxSize: Int = 20, val maxBytes: Long? = null) {

    init {
        require(maxSize > 0) { "maxSize must be > 0, was $maxSize" }
    }

    private val cache = LinkedHashMap<TextureSource, CachedTexture>(
        maxSize + 1, 0.75f, /* accessOrder= */ true
    )

    /** Tracks the total decoded byte size of all cached bitmaps. */
    private var currentBytes: Long = 0L

    /**
     * Look up [source] in the cache. Returns the cached entry if present and the bitmap
     * has not been recycled. If the bitmap is recycled, the stale entry is evicted and
     * `null` is returned so the caller reloads the texture.
     */
    fun get(source: TextureSource): CachedTexture? {
        val entry = cache[source] ?: return null
        if (entry.bitmap.isRecycled) {
            // CT-SEC-4: stale recycled bitmap — evict and signal a cache miss so the
            // caller reloads from the source.
            cache.remove(source)
            currentBytes -= entry.bitmap.byteCount.toLong().coerceAtLeast(0L)
            return null
        }
        return entry
    }

    fun put(source: TextureSource, bitmap: Bitmap): CachedTexture {
        val entry = CachedTexture(bitmap)
        cache[source] = entry
        currentBytes += bitmap.byteCount.toLong()
        evictIfNeeded()
        return entry
    }

    fun clear() {
        cache.clear()
        currentBytes = 0L
    }

    val size: Int get() = cache.size

    /** Evict eldest entries until both count and byte constraints are satisfied. */
    private fun evictIfNeeded() {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val countExceeded = cache.size > maxSize
            val bytesExceeded = maxBytes != null && currentBytes > maxBytes
            if (!countExceeded && !bytesExceeded) break
            val eldest = iterator.next()
            currentBytes -= eldest.value.bitmap.byteCount.toLong().coerceAtLeast(0L)
            iterator.remove()
        }
    }
}
