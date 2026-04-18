package io.github.jayteealao.isometric.shader.render

import io.github.jayteealao.isometric.IsoColor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM-runnable coverage for the `IsoColor → Android ARGB` conversion used by the
 * `TexturedCanvasDrawHook.drawFlatColor` / `IsoColor` arm added in the
 * `uv-generation-pyramid` slice (I-03 secondary-latent fix).
 *
 * The conversion is extracted into [toAndroidArgbInt] so the color-derivation logic
 * can be covered here on the pure JVM; the whole-hook draw path still requires a
 * live Android runtime (see the `@Ignore`'d [TexturedCanvasDrawHookTest]). Previously
 * the color derivation had NO test coverage at all — a regression reverting the
 * `IsoColor` arm to the pre-fix `else -> false` would silently reintroduce the
 * flat-gray pyramid bug on Canvas with zero test failure.
 */
class TexturedCanvasDrawHookColorTest {

    @Test
    fun `opaque red matches Color argb of 255,255,0,0`() {
        val red = IsoColor(255.0, 0.0, 0.0, 255.0)
        assertEquals(0xFFFF0000.toInt(), red.toAndroidArgbInt())
    }

    @Test
    fun `opaque green matches Color argb of 255,0,255,0`() {
        val green = IsoColor(0.0, 255.0, 0.0, 255.0)
        assertEquals(0xFF00FF00.toInt(), green.toAndroidArgbInt())
    }

    @Test
    fun `opaque blue matches Color argb of 255,0,0,255`() {
        val blue = IsoColor(0.0, 0.0, 255.0, 255.0)
        assertEquals(0xFF0000FF.toInt(), blue.toAndroidArgbInt())
    }

    @Test
    fun `mid-gray sample color packs to 0xFF808080`() {
        // This is the PerFace UNASSIGNED_FACE_DEFAULT — the color that the pre-fix
        // else -> false branch silently painted over every per-face pyramid face.
        // If regression re-adds the else -> false, faces would paint baseColor (this
        // exact value) instead of the resolved per-face color.
        val gray = IsoColor(128.0, 128.0, 128.0, 255.0)
        assertEquals(0xFF808080.toInt(), gray.toAndroidArgbInt())
    }

    @Test
    fun `alpha channel is preserved through conversion`() {
        val halfAlpha = IsoColor(255.0, 0.0, 0.0, 128.0)
        val result = halfAlpha.toAndroidArgbInt()
        // Android's ARGB layout: alpha in bits 24..31.
        assertEquals(128, (result ushr 24) and 0xFF)
    }

    @Test
    fun `all channels at maximum packs to 0xFFFFFFFF`() {
        val white = IsoColor(255.0, 255.0, 255.0, 255.0)
        assertEquals(0xFFFFFFFF.toInt(), white.toAndroidArgbInt())
    }

    @Test
    fun `all channels at zero packs to 0x00000000`() {
        val transparentBlack = IsoColor(0.0, 0.0, 0.0, 0.0)
        assertEquals(0x00000000, transparentBlack.toAndroidArgbInt())
    }
}
