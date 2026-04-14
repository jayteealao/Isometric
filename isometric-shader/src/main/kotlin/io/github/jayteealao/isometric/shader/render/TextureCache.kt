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
 */
internal class TextureCache(val maxSize: Int = 20) {

    init {
        require(maxSize > 0) { "maxSize must be positive, got $maxSize" }
    }

    private val cache = object : LinkedHashMap<TextureSource, CachedTexture>(
        maxSize + 1, 0.75f, /* accessOrder= */ true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<TextureSource, CachedTexture>): Boolean =
            size > maxSize
    }

    fun get(source: TextureSource): CachedTexture? = cache[source]

    fun put(source: TextureSource, bitmap: Bitmap): CachedTexture {
        val entry = CachedTexture(bitmap)
        cache[source] = entry
        return entry
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size
}
