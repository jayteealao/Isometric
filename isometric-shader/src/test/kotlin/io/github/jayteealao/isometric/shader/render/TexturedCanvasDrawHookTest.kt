package io.github.jayteealao.isometric.shader.render

import io.github.jayteealao.isometric.shader.TextureSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Unit tests for [TexturedCanvasDrawHook].
 *
 * ## Android API note (TST-06)
 * [TexturedCanvasDrawHook.resolveToCache] is the callback-wiring path under test. When the
 * loader returns `null`, the hook:
 *  1. Invokes `onTextureLoadError` with the failed [TextureSource].
 *  2. Returns the checkerboard [CachedTexture] as a per-frame fallback (not cached).
 *
 * The fallback path creates a real [android.graphics.Bitmap] via [createCheckerboardBitmap].
 * This is exercised under **Paparazzi** (app.cash.paparazzi), which provides a working Android
 * framework environment for `src/test/` JVM tests, making `Bitmap.createBitmap` available
 * without a device.
 *
 * If these tests are ever moved to a module that does NOT use Paparazzi, the `Bitmap`
 * creation will fail. In that case, move the tests to `src/androidTest/` and run them
 * as instrumented tests instead.
 */
class TexturedCanvasDrawHookTest {

    // TST-06: onTextureLoadError is invoked when TextureLoader.load() returns null ----------

    @Test
    fun `onTextureLoadError_invokedWithSource_whenLoaderReturnsNull`() {
        val source = TextureSource.Resource(1)
        val capturedSources = mutableListOf<TextureSource>()
        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { null },
            onTextureLoadError = { capturedSources.add(it) },
        )

        hook.resolveToCache(source)

        assertEquals(1, capturedSources.size, "onTextureLoadError must be called exactly once")
        assertSame(source, capturedSources[0], "onTextureLoadError must receive the failed source")
    }

    @Test
    fun `onTextureLoadError_notInvoked_whenLoaderSucceeds`() {
        // When the loader succeeds (returns a non-null Bitmap) the error callback must NOT fire.
        val source = TextureSource.Resource(1)
        val capturedSources = mutableListOf<TextureSource>()

        // createCheckerboardBitmap() produces a real Bitmap; reuse it as a "successful" bitmap
        // so we don't need a Context or resource ID.
        val fakeBitmap = createCheckerboardBitmap()
        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { fakeBitmap },
            onTextureLoadError = { capturedSources.add(it) },
        )

        hook.resolveToCache(source)

        assertEquals(0, capturedSources.size, "onTextureLoadError must NOT be called when load succeeds")
    }

    @Test
    fun `onTextureLoadError_notInvoked_whenCallbackIsNull`() {
        // Passing null for onTextureLoadError must not throw when the loader fails.
        val source = TextureSource.Resource(1)
        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { null },
            onTextureLoadError = null,
        )

        // Should complete without throwing a NullPointerException.
        hook.resolveToCache(source)
    }

    @Test
    fun `onTextureLoadError_invokedOnEachCacheMiss_notOnCacheHit`() {
        // After a successful load the source is cached; subsequent calls must NOT re-invoke the
        // error callback, and must NOT re-invoke the loader.
        val source = TextureSource.Resource(1)
        var loadCallCount = 0
        val errorCallCount = mutableListOf<TextureSource>()
        val fakeBitmap = createCheckerboardBitmap()

        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader {
                loadCallCount++
                fakeBitmap
            },
            onTextureLoadError = { errorCallCount.add(it) },
        )

        // First call: cache miss → loader invoked once, no error.
        hook.resolveToCache(source)
        assertEquals(1, loadCallCount)
        assertEquals(0, errorCallCount.size)

        // Second call: cache hit → loader NOT invoked again, no error.
        hook.resolveToCache(source)
        assertEquals(1, loadCallCount, "Loader must not be called again on cache hit")
        assertEquals(0, errorCallCount.size)
    }

    @Test
    fun `onTextureLoadError_invokedOnEveryFailedLoad_notCached`() {
        // On failure, the fallback checkerboard is returned but NOT stored in the cache.
        // Every subsequent call for the same source must re-invoke the loader (and the callback).
        val source = TextureSource.Resource(1)
        var loadCallCount = 0
        val errorCallCount = mutableListOf<TextureSource>()

        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader {
                loadCallCount++
                null // always fail
            },
            onTextureLoadError = { errorCallCount.add(it) },
        )

        hook.resolveToCache(source)
        assertEquals(1, loadCallCount)
        assertEquals(1, errorCallCount.size)

        // Second call: still a cache miss (failure is not cached) → loader and callback called again.
        hook.resolveToCache(source)
        assertEquals(2, loadCallCount, "Loader must be retried on next frame after failure")
        assertEquals(2, errorCallCount.size, "onTextureLoadError must fire on every failed load")
        assertSame(source, errorCallCount[1])
    }

    @Test
    fun `resolveToCache_returnsCheckerboard_whenLoaderFails`() {
        // The fallback value must be the checkerboard (non-null CachedTexture).
        val source = TextureSource.Resource(1)
        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { null },
        )

        val result = hook.resolveToCache(source)

        // The fallback bitmap must be the canonical 16×16 checkerboard.
        assertEquals(16, result.bitmap.width)
        assertEquals(16, result.bitmap.height)
    }

    @Test
    fun `resolveToCache_nullCallback_returnsCheckerboard_whenLoaderFails`() {
        // Even with no callback, the fallback CachedTexture must be returned correctly.
        val source = TextureSource.Asset("textures/missing.png")
        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { null },
            onTextureLoadError = null,
        )

        val result = hook.resolveToCache(source)

        // Reaching here without NPE verifies null-safety of the optional callback.
        assertEquals(16, result.bitmap.width)
    }
}
