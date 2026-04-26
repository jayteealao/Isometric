package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import android.graphics.Shader
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [TextureShaderKey] data-class equality and hashCode behaviour.
 *
 * Runs under Robolectric so that [Bitmap] and [Shader.TileMode] are available without
 * a physical device. Added in Step 12 (G5) of the webgpu-pipeline-cleanup slice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TexturedCanvasDrawHookKeyTest {

    // --- helpers ---------------------------------------------------------------

    /**
     * Creates a minimal non-null [Bitmap] that can be constructed on a live Android
     * runtime without a real Context. Uses the same approach as [createCheckerboardBitmap].
     */
    private fun makeBitmap(): Bitmap =
        Bitmap.createBitmap(intArrayOf(0xFF000000.toInt()), 1, 1, Bitmap.Config.ARGB_8888)

    // --- equality and hashCode -------------------------------------------------

    /**
     * Two [TextureShaderKey]s sharing the SAME [Bitmap] reference AND same [Shader.TileMode]
     * must be equal (data-class structural equality) and have the same hashCode.
     */
    @Test
    fun `sameBitmap_sameTileMode_equal_and_sameHashCode`() {
        val bitmap = makeBitmap()
        val key1 = TextureShaderKey(bitmap, Shader.TileMode.CLAMP)
        val key2 = TextureShaderKey(bitmap, Shader.TileMode.CLAMP)

        assertEquals(key1, key2, "Keys with same bitmap ref + same TileMode must be equal")
        assertEquals(key1.hashCode(), key2.hashCode(), "Equal keys must have the same hashCode")
    }

    /**
     * Two [TextureShaderKey]s sharing the same [Bitmap] reference but with DIFFERENT
     * [Shader.TileMode] values must NOT be equal — a CLAMP shader must not be reused
     * for a REPEAT tile, or texture tiling will silently break.
     */
    @Test
    fun `sameBitmap_differentTileMode_notEqual`() {
        val bitmap = makeBitmap()
        val key1 = TextureShaderKey(bitmap, Shader.TileMode.CLAMP)
        val key2 = TextureShaderKey(bitmap, Shader.TileMode.REPEAT)

        assertNotEquals(key1, key2, "Keys with different TileMode must not be equal")
    }

    /**
     * Two [TextureShaderKey]s with DIFFERENT [Bitmap] references (even if pixel-identical)
     * must NOT be equal. This invariant ensures that when [TextureCache] evicts a bitmap
     * and a new instance is loaded for the same [TextureSource], the shader cache gets a
     * miss and creates a fresh [android.graphics.BitmapShader] backed by the new bitmap —
     * rather than returning a shader backed by the evicted/recycled original.
     */
    @Test
    fun `differentBitmap_sameTileMode_notEqual`() {
        val bitmap1 = makeBitmap()
        val bitmap2 = makeBitmap() // distinct reference, same content
        val key1 = TextureShaderKey(bitmap1, Shader.TileMode.CLAMP)
        val key2 = TextureShaderKey(bitmap2, Shader.TileMode.CLAMP)

        assertNotEquals(key1, key2, "Keys with different Bitmap references must not be equal")
    }
}
