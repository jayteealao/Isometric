package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TileGridConfigTest {

    // ── Construction / defaults ───────────────────────────────────────────────

    @Test
    fun `default config has tileSize 1 and origin at world origin`() {
        val config = TileGridConfig()
        assertEquals(1.0, config.tileSize)
        assertEquals(Point.ORIGIN, config.originOffset)
        assertNull(config.elevation)
    }

    @Test
    fun `custom tileSize is stored`() {
        val config = TileGridConfig(tileSize = 2.0)
        assertEquals(2.0, config.tileSize)
    }

    @Test
    fun `custom originOffset is stored`() {
        val offset = Point(-5.0, -5.0, 0.0)
        val config = TileGridConfig(originOffset = offset)
        assertEquals(offset, config.originOffset)
    }

    @Test
    fun `elevation function is stored when provided`() {
        val fn: (TileCoordinate) -> Double = { it.x * 0.5 }
        val config = TileGridConfig(elevation = fn)
        assertNotNull(config.elevation)
        assertEquals(1.5, config.elevation!!.invoke(TileCoordinate(3, 0)))
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    fun `tileSize zero throws`() {
        assertFailsWith<IllegalArgumentException> {
            TileGridConfig(tileSize = 0.0)
        }
    }

    @Test
    fun `tileSize negative throws`() {
        assertFailsWith<IllegalArgumentException> {
            TileGridConfig(tileSize = -1.0)
        }
    }

    @Test
    fun `tileSize positive infinity throws`() {
        // isFinite check fires before positive check on infinity
        assertFailsWith<IllegalArgumentException> {
            TileGridConfig(tileSize = Double.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `tileSize NaN throws`() {
        assertFailsWith<IllegalArgumentException> {
            TileGridConfig(tileSize = Double.NaN)
        }
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Test
    fun `configs with same tileSize and originOffset are equal`() {
        assertEquals(TileGridConfig(tileSize = 1.0), TileGridConfig(tileSize = 1.0))
    }

    @Test
    fun `configs with different tileSize are not equal`() {
        assertNotEquals(TileGridConfig(tileSize = 1.0), TileGridConfig(tileSize = 2.0))
    }

    @Test
    fun `configs with different originOffset are not equal`() {
        assertNotEquals(
            TileGridConfig(originOffset = Point.ORIGIN),
            TileGridConfig(originOffset = Point(1.0, 0.0, 0.0))
        )
    }

    @Test
    fun `elevation lambda is excluded from equals`() {
        // Two configs with different elevation functions are equal by design —
        // function identity is unstable across recompositions.
        val a = TileGridConfig(elevation = { 1.0 })
        val b = TileGridConfig(elevation = { 2.0 })
        assertEquals(a, b)
    }

    @Test
    fun `equal configs have equal hashCode`() {
        val a = TileGridConfig(tileSize = 2.0, originOffset = Point(1.0, 0.0, 0.0))
        val b = TileGridConfig(tileSize = 2.0, originOffset = Point(1.0, 0.0, 0.0))
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    fun `toString includes tileSize and originOffset`() {
        val config = TileGridConfig(tileSize = 2.0)
        assertTrue(config.toString().contains("tileSize=2.0"))
    }

    @Test
    fun `toString shows function marker when elevation provided`() {
        val config = TileGridConfig(elevation = { 0.0 })
        assertTrue(config.toString().contains("<function>"))
    }

    @Test
    fun `toString shows null when elevation absent`() {
        val config = TileGridConfig()
        assertTrue(config.toString().contains("null"))
    }
}
