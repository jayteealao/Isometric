package io.fabianterhorst.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
