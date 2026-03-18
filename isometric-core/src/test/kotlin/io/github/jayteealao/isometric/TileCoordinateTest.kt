package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TileCoordinateTest {

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    fun `x and y are stored correctly`() {
        val coord = TileCoordinate(3, 5)
        assertEquals(3, coord.x)
        assertEquals(5, coord.y)
    }

    @Test
    fun `negative coordinates are valid`() {
        val coord = TileCoordinate(-3, -7)
        assertEquals(-3, coord.x)
        assertEquals(-7, coord.y)
    }

    // ── equals / hashCode ─────────────────────────────────────────────────────

    @Test
    fun `equals is reflexive`() {
        val coord = TileCoordinate(3, 5)
        assertEquals(coord, coord)
    }

    @Test
    fun `equals is symmetric`() {
        val a = TileCoordinate(3, 5)
        val b = TileCoordinate(3, 5)
        assertEquals(a, b)
        assertEquals(b, a)
    }

    @Test
    fun `different x produces not equal`() {
        assertNotEquals(TileCoordinate(3, 5), TileCoordinate(4, 5))
    }

    @Test
    fun `different y produces not equal`() {
        assertNotEquals(TileCoordinate(3, 5), TileCoordinate(3, 6))
    }

    @Test
    fun `equal coordinates have equal hashCode`() {
        assertEquals(TileCoordinate(3, 5).hashCode(), TileCoordinate(3, 5).hashCode())
    }

    @Test
    fun `usable as HashMap key`() {
        val map = HashMap<TileCoordinate, String>()
        val key = TileCoordinate(3, 5)
        map[key] = "value"
        assertEquals("value", map[TileCoordinate(3, 5)])
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    fun `toString format is correct`() {
        assertEquals("TileCoordinate(3, 5)", TileCoordinate(3, 5).toString())
    }

    // ── plus / minus ──────────────────────────────────────────────────────────

    @Test
    fun `plus adds coordinates`() {
        val result = TileCoordinate(3, 5) + TileCoordinate(1, 2)
        assertEquals(TileCoordinate(4, 7), result)
    }

    @Test
    fun `minus subtracts coordinates`() {
        val result = TileCoordinate(3, 5) - TileCoordinate(1, 2)
        assertEquals(TileCoordinate(2, 3), result)
    }

    @Test
    fun `plus with negative delta`() {
        val result = TileCoordinate(3, 5) + TileCoordinate(-1, -1)
        assertEquals(TileCoordinate(2, 4), result)
    }

    // ── isWithin ─────────────────────────────────────────────────────────────

    @Test
    fun `isWithin returns true for coord inside bounds`() {
        assertTrue(TileCoordinate(3, 5).isWithin(10, 10))
    }

    @Test
    fun `isWithin returns true for origin`() {
        assertTrue(TileCoordinate(0, 0).isWithin(10, 10))
    }

    @Test
    fun `isWithin returns true at exact upper boundary - 1`() {
        assertTrue(TileCoordinate(9, 9).isWithin(10, 10))
    }

    @Test
    fun `isWithin returns false at exact upper boundary`() {
        assertFalse(TileCoordinate(10, 5).isWithin(10, 10))
        assertFalse(TileCoordinate(5, 10).isWithin(10, 10))
    }

    @Test
    fun `isWithin returns false for negative x`() {
        assertFalse(TileCoordinate(-1, 0).isWithin(10, 10))
    }

    @Test
    fun `isWithin returns false for negative y`() {
        assertFalse(TileCoordinate(0, -1).isWithin(10, 10))
    }

    @Test
    fun `isWithin returns false when both out of bounds`() {
        assertFalse(TileCoordinate(10, 10).isWithin(10, 10))
    }

    // ── toPoint ───────────────────────────────────────────────────────────────

    @Test
    fun `toPoint with default tileSize`() {
        assertEquals(Point(3.0, 5.0, 0.0), TileCoordinate(3, 5).toPoint())
    }

    @Test
    fun `toPoint with custom tileSize`() {
        assertEquals(Point(6.0, 10.0, 0.0), TileCoordinate(3, 5).toPoint(tileSize = 2.0))
    }

    @Test
    fun `toPoint with custom elevation`() {
        assertEquals(Point(3.0, 5.0, 2.5), TileCoordinate(3, 5).toPoint(elevation = 2.5))
    }

    @Test
    fun `toPoint origin is at world origin`() {
        assertEquals(Point(0.0, 0.0, 0.0), TileCoordinate(0, 0).toPoint())
    }

    // ── ORIGIN constant ───────────────────────────────────────────────────────

    @Test
    fun `ORIGIN equals TileCoordinate 0 0`() {
        assertEquals(TileCoordinate(0, 0), TileCoordinate.ORIGIN)
    }
}
