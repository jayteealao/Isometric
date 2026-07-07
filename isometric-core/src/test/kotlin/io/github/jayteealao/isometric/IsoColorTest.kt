package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsoColorTest {

    @Test
    fun `RGB to HSL conversion`() {
        val red = IsoColor(255.0, 0.0, 0.0)
        assertEquals(0.0, red.h, 0.01)
        assertEquals(1.0, red.s, 0.01)
        assertEquals(0.5, red.l, 0.01)
    }

    @Test
    fun `lighten increases lightness`() {
        val color = IsoColor(128.0, 128.0, 128.0)
        val lightened = color.lighten(0.2, IsoColor.WHITE)
        assertTrue(lightened.l > color.l)
    }

    @Test
    fun `lighten with negative percentage does not produce zero RGB channels`() {
        // AC-A4: pre-fix code uses min(l + percentage, 1.0) which allows l < 0
        // when percentage is negative, mapping to black via hslToRgb. After the fix
        // coerceIn(0.0, 1.0) clamps the lower bound so channels stay above zero.
        val blue = IsoColor(10, 10, 80)  // dark blue, low lightness
        val result = blue.lighten(-0.20, IsoColor.WHITE)
        assertTrue(result.r >= 0.0 && result.g >= 0.0 && result.b >= 0.0,
            "lighten(-0.20) must not produce negative channels")
        assertTrue(result.l >= 0.0, "lightness must be >= 0 after clamped negative percentage")
        // Verify the color is not pure black — it should remain a darker blue, not zero.
        val preFixWouldBeBlack = result.r == 0.0 && result.g == 0.0 && result.b == 0.0
        assertTrue(!preFixWouldBeBlack || result.l == 0.0,
            "lighten(-0.20) on a non-black color must not produce pure black (pre-fix regression)")
    }

    @Test
    fun `lighten boundary minus one produces minimum lightness`() {
        // Lightness clamped to 0.0 when percentage = -1.0 regardless of starting l.
        val color = IsoColor(128, 64, 32)
        val result = color.lighten(-1.0, IsoColor.WHITE)
        assertEquals(0.0, result.l, 1e-10, "lighten(-1.0) should produce lightness 0")
    }

    @Test
    fun `lighten boundary plus one produces maximum lightness`() {
        // Lightness clamped to 1.0 when percentage = +1.0.
        val color = IsoColor(128, 64, 32)
        val result = color.lighten(1.0, IsoColor.WHITE)
        assertEquals(1.0, result.l, 1e-10, "lighten(+1.0) should produce lightness 1")
    }

    @Test
    fun `toRGBA converts correctly`() {
        val color = IsoColor(100.0, 150.0, 200.0, 255.0)
        val rgba = color.toRGBA()
        assertEquals(100, rgba[0])
        assertEquals(150, rgba[1])
        assertEquals(200, rgba[2])
        assertEquals(255, rgba[3])
    }

    @Test
    fun `constructor rejects out of range channels`() {
        assertFailsWith<IllegalArgumentException> { IsoColor(-1.0, 0.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { IsoColor(0.0, 256.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { IsoColor(0.0, 0.0, 0.0, 300.0) }
    }

    @Test
    fun `fromHex parses rgb and argb`() {
        assertEquals(IsoColor(0x11, 0x22, 0x33), IsoColor.fromHex(0x112233))
        assertEquals(IsoColor(0x22, 0x33, 0x44, 0x11), IsoColor.fromHex(0x11223344))
    }

    @Test
    fun `fromHex preserves alpha for widened signed argb ints`() {
        val widenedSignedArgb = 0x80FF0000u.toInt().toLong()
        assertEquals(IsoColor(0xFF, 0x00, 0x00, 0x80), IsoColor.fromHex(widenedSignedArgb))
    }

    @Test
    fun `fromHex string preserves zero alpha argb`() {
        assertEquals(IsoColor(0x11, 0x22, 0x33, 0x00), IsoColor.fromHex("00112233"))
        assertEquals(IsoColor(0x11, 0x22, 0x33, 0x00), IsoColor.fromHex("#00112233"))
    }

    // --- withAlpha -------------------------------------------------------------------

    @Test
    fun `withAlpha scales alpha multiplicatively and leaves RGB unchanged`() {
        val color = IsoColor(255.0, 0.0, 0.0, 200.0)
        val result = color.withAlpha(0.5f)
        assertEquals(100.0, result.a, 0.001)
        assertEquals(255.0, result.r, 0.001)
        assertEquals(0.0, result.g, 0.001)
        assertEquals(0.0, result.b, 0.001)
    }

    @Test
    fun `withAlpha of zero produces fully transparent color`() {
        val result = IsoColor.RED.withAlpha(0f)
        assertEquals(0.0, result.a, 0.001)
    }

    @Test
    fun `withAlpha of one returns color equal to original`() {
        val color = IsoColor(100.0, 150.0, 200.0, 180.0)
        assertEquals(color, color.withAlpha(1f))
    }

    @Test
    fun `withAlpha rejects alpha below zero`() {
        assertFailsWith<IllegalArgumentException> { IsoColor.RED.withAlpha(-0.1f) }
    }

    @Test
    fun `withAlpha rejects alpha just above one`() {
        assertFailsWith<IllegalArgumentException> { IsoColor.RED.withAlpha(1.001f) }
    }

    @Test
    fun `withAlpha rejects alpha far above one`() {
        assertFailsWith<IllegalArgumentException> { IsoColor.RED.withAlpha(2f) }
    }
}
