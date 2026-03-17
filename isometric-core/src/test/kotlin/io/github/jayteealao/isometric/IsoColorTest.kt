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
}
