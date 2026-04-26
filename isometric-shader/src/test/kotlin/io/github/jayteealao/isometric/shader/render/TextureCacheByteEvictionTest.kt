package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import io.github.jayteealao.isometric.shader.TextureSource
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM unit tests for [TextureCache] byte-cap eviction (CT-SEC-3 / maxBytes).
 *
 * Uses [TextureCache.putWithSize] — a test-only internal overload that accepts an explicit
 * byte count — so that a Mockito stub [Bitmap] can be used without a real Android runtime.
 * The eviction logic itself is pure-Kotlin and operates on the stored [CachedTexture.byteCount]
 * field rather than re-calling [Bitmap.byteCount], making these tests fully JVM-runnable.
 */
class TextureCacheByteEvictionTest {

    /** Convenience: put an entry with an explicit byte size using a stub Bitmap. */
    private fun TextureCache.putStub(source: TextureSource, bytes: Long) {
        val stub = mock(Bitmap::class.java)
        putWithSize(source, stub, bytes)
    }

    private fun key(n: Int) = TextureSource.Resource(n)

    // --- count-only cap (regression: existing behaviour must not break) ---

    @Test
    fun `count cap evicts eldest when maxSize exceeded`() {
        val cache = TextureCache(maxSize = 2, maxBytes = null)
        cache.putStub(key(1), 100L)
        cache.putStub(key(2), 200L)
        cache.putStub(key(3), 300L) // triggers eviction of key(1)
        assertEquals(2, cache.size)
    }

    // --- byte cap eviction ---

    @Test
    fun `byte cap not exceeded - no eviction`() {
        val cache = TextureCache(maxSize = 10, maxBytes = 500L)
        cache.putStub(key(1), 200L)
        cache.putStub(key(2), 200L)
        assertEquals(2, cache.size)
        assertEquals(400L, cache.totalBytes)
    }

    @Test
    fun `byte cap exceeded - eldest entry evicted`() {
        val cache = TextureCache(maxSize = 10, maxBytes = 500L)
        cache.putStub(key(1), 300L) // eldest
        cache.putStub(key(2), 300L) // triggers byte eviction (300+300 = 600 > 500)
        assertEquals(1, cache.size)
        // key(1) was eldest; after eviction only key(2) should remain
        assertEquals(300L, cache.totalBytes)
    }

    @Test
    fun `byte cap evicts multiple entries until within limit`() {
        val cache = TextureCache(maxSize = 10, maxBytes = 300L)
        cache.putStub(key(1), 100L) // eldest
        cache.putStub(key(2), 100L)
        cache.putStub(key(3), 100L)
        // Now 300 bytes — exactly at cap, no eviction needed
        assertEquals(3, cache.size)
        assertEquals(300L, cache.totalBytes)

        // Adding one more (200L) pushes to 500 > 300; evict until <=300
        cache.putStub(key(4), 200L)
        // After eviction: key(1)=100 and key(2)=100 must be gone; key(3)=100 + key(4)=200 = 300
        assertEquals(2, cache.size)
        assertEquals(300L, cache.totalBytes)
    }

    @Test
    fun `byte cap exactly at limit - no eviction`() {
        val cache = TextureCache(maxSize = 10, maxBytes = 200L)
        cache.putStub(key(1), 100L)
        cache.putStub(key(2), 100L)
        assertEquals(2, cache.size)
        assertEquals(200L, cache.totalBytes)
    }

    @Test
    fun `byte cap single entry larger than limit - entry evicted`() {
        // Pathological: a single entry larger than maxBytes is evicted immediately after put,
        // leaving the cache empty. This is the correct behaviour — we never leave the cache
        // in a state that permanently violates the byte cap.
        val cache = TextureCache(maxSize = 10, maxBytes = 100L)
        cache.putStub(key(1), 200L)
        assertEquals(0, cache.size)
        assertEquals(0L, cache.totalBytes)
    }

    @Test
    fun `clear resets byte counter to zero`() {
        val cache = TextureCache(maxSize = 10, maxBytes = 1000L)
        cache.putStub(key(1), 300L)
        cache.putStub(key(2), 300L)
        assertEquals(600L, cache.totalBytes)
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.totalBytes)
    }

    @Test
    fun `count cap and byte cap - both enforced`() {
        // maxSize=2, maxBytes=400. Adding a third entry violates count; adding another
        // oversized entry violates bytes. Both constraints must hold simultaneously.
        val cache = TextureCache(maxSize = 2, maxBytes = 400L)
        cache.putStub(key(1), 100L)
        cache.putStub(key(2), 100L)
        cache.putStub(key(3), 100L) // count eviction: removes key(1)
        assertEquals(2, cache.size)
        // Now add a large entry that pushes bytes over 400
        cache.putStub(key(4), 350L) // 100+100+350 > 400 and size > 2 — multiple evictions
        // After eviction the cache must satisfy both constraints
        assert(cache.size <= 2) { "count cap violated: size=${cache.size}" }
        assert(cache.totalBytes <= 400L) { "byte cap violated: totalBytes=${cache.totalBytes}" }
    }
}
