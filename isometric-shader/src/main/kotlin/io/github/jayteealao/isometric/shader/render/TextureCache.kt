package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Cached texture entry holding both the decoded [Bitmap] and its pre-built [BitmapShader].
 *
 * The shader is created once and reused across frames; only the local matrix changes
 * per draw call.
 */
internal data class CachedTexture(val bitmap: Bitmap, val shader: BitmapShader)

/**
 * LRU cache mapping [TextureSource] keys to [CachedTexture] entries.
 *
 * Uses `LinkedHashMap` with `accessOrder = true` for O(1) LRU eviction.
 * Each entry holds both the decoded [Bitmap] and a pre-built [BitmapShader]
 * with `CLAMP/CLAMP` tile mode (faces map exactly to texture bounds via UV coords).
 *
 * Thread safety: intended for main-thread use only. Canvas draw path runs on the
 * main thread so no concurrent access is expected.
 *
 * @param maxSize Maximum number of textures to keep in memory. Default: 20
 *   (covers typical isometric tile sets).
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
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val entry = CachedTexture(bitmap, shader)
        cache[source] = entry
        return entry
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size
}
