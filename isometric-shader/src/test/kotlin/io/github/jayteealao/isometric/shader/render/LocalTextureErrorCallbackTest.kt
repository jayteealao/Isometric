package io.github.jayteealao.isometric.shader.render

import io.github.jayteealao.isometric.shader.TextureSource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * T-07: Unit tests for the [TexturedCanvasDrawHook] constructor-parameter [onTextureLoadError]
 * callback, backed by Robolectric so Android graphics types are available.
 *
 * Covers:
 *  - Callback wiring: non-null [onTextureLoadError] is invoked when the loader returns null.
 *  - Null safety: null [onTextureLoadError] does not throw when the loader fails.
 *  - Not invoked on success: callback is not fired when the loader returns a valid Bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalTextureErrorCallbackTest {

    // ── T-07: constructor-param onTextureLoadError is invoked on loader failure ─

    @Test
    fun `nonNull_onTextureLoadError_invokedWhenLoaderReturnsNull`() {
        val source = TextureSource.Asset("textures/tile.png")
        val captured = mutableListOf<TextureSource>()

        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { null },
            onTextureLoadError = { captured.add(it) },
        )

        hook.resolveToCache(source)

        assertEquals(1, captured.size, "onTextureLoadError must be called exactly once on failure")
        assertSame(source, captured[0], "onTextureLoadError must receive the failing TextureSource")
    }

    @Test
    fun `null_onTextureLoadError_doesNotThrowWhenLoaderFails`() {
        // Passing null for onTextureLoadError is the zero-configuration default path.
        // Verifies that the hook short-circuits the optional callback safely.
        val source = TextureSource.Resource(42)
        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { null },
            onTextureLoadError = null,
        )

        // Must complete without NullPointerException or UnsatisfiedLinkError.
        val result = hook.resolveToCache(source)

        // The fallback is the checkerboard; width/height are 16 each.
        assertEquals(16, result.bitmap.width, "Fallback checkerboard must be 16 px wide")
        assertEquals(16, result.bitmap.height, "Fallback checkerboard must be 16 px tall")
    }

    @Test
    fun `onTextureLoadError_notInvokedWhenLoaderSucceeds`() {
        // The callback must NOT fire when the loader returns a non-null Bitmap.
        val source = TextureSource.Resource(1)
        val captured = mutableListOf<TextureSource>()
        val fakeBitmap = createCheckerboardBitmap()

        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { fakeBitmap },
            onTextureLoadError = { captured.add(it) },
        )

        hook.resolveToCache(source)

        assertEquals(0, captured.size, "onTextureLoadError must not fire when loader succeeds")
    }

    @Test
    fun `onTextureLoadError_invokedRepeatedly_onRepeatedFailures`() {
        // Failed loads are not cached; every call retries the loader and fires the callback.
        val source = TextureSource.Asset("textures/missing.png")
        var loadCount = 0
        val captured = mutableListOf<TextureSource>()

        val hook = TexturedCanvasDrawHook(
            cache = TextureCache(maxSize = 4),
            loader = TextureLoader { loadCount++; null },
            onTextureLoadError = { captured.add(it) },
        )

        hook.resolveToCache(source)
        hook.resolveToCache(source)

        assertEquals(2, loadCount, "Loader must be retried on each frame after failure")
        assertEquals(2, captured.size, "onTextureLoadError must fire once per failed load attempt")
    }
}
