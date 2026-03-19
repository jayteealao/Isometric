package io.github.jayteealao.isometric

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileCoordinateExtensionsTest {

    // ── Point.toTileCoordinate ────────────────────────────────────────────────

    @Test
    fun `positive coordinates map to correct tile`() {
        assertEquals(TileCoordinate(3, 5), Point(3.7, 5.2, 0.0).toTileCoordinate())
    }

    @Test
    fun `point exactly on grid line belongs to tile at that boundary`() {
        // Point(3.0, 5.0) is on the boundary — floor(3.0/1.0) = 3
        assertEquals(TileCoordinate(3, 5), Point(3.0, 5.0, 0.0).toTileCoordinate())
    }

    @Test
    fun `negative coordinate uses floor not truncation`() {
        // floor(-0.3 / 1.0) = floor(-0.3) = -1, not 0
        assertEquals(TileCoordinate(-1, 0), Point(-0.3, 0.0, 0.0).toTileCoordinate())
    }

    @Test
    fun `negative coordinate floor for both axes`() {
        assertEquals(TileCoordinate(-1, -1), Point(-0.1, -0.9, 0.0).toTileCoordinate())
    }

    @Test
    fun `non-unit tileSize divides correctly`() {
        // Point(4.0, 6.0) with tileSize=2.0 → floor(4/2)=2, floor(6/2)=3
        assertEquals(TileCoordinate(2, 3), Point(4.0, 6.0, 0.0).toTileCoordinate(tileSize = 2.0))
    }

    @Test
    fun `non-zero originOffset shifts origin`() {
        // Point(3.7, 5.2) with originOffset=(1,1) → floor((3.7-1)/1)=2, floor((5.2-1)/1)=4
        assertEquals(
            TileCoordinate(2, 4),
            Point(3.7, 5.2, 0.0).toTileCoordinate(originOffset = Point(1.0, 1.0, 0.0))
        )
    }

    @Test
    fun `z coordinate of point is ignored`() {
        // z does not affect tile coordinate — only x and y matter
        assertEquals(
            Point(3.0, 5.0, 0.0).toTileCoordinate(),
            Point(3.0, 5.0, 99.0).toTileCoordinate()
        )
    }

    @Test
    fun `tileSize zero throws`() {
        assertFailsWith<IllegalArgumentException> {
            Point.ORIGIN.toTileCoordinate(tileSize = 0.0)
        }
    }

    @Test
    fun `tileSize negative throws`() {
        assertFailsWith<IllegalArgumentException> {
            Point.ORIGIN.toTileCoordinate(tileSize = -1.0)
        }
    }

    // ── IsometricEngine.screenToTile round-trip ───────────────────────────────

    @Test
    fun `screenToTile round-trip for 10x10 grid at default engine parameters`() {
        val engine = IsometricEngine()
        val viewportWidth = 800
        val viewportHeight = 600

        // Project the *centre* of each tile (x+0.5, y+0.5) rather than the corner.
        // Projecting the exact corner can drift by ~1e-10 after the round-trip and
        // land in the wrong tile when floor() is applied. The centre is robust.
        for (tx in 0 until 10) {
            for (ty in 0 until 10) {
                val tileCenter = Point(tx + 0.5, ty + 0.5, 0.0)
                val screen = engine.worldToScreen(tileCenter, viewportWidth, viewportHeight)

                val result = engine.screenToTile(
                    screenX = screen.x,
                    screenY = screen.y,
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight
                )

                assertEquals(
                    TileCoordinate(tx, ty), result,
                    "Round-trip failed for tile ($tx, $ty)"
                )
            }
        }
    }

    @Test
    fun `screenToTile with custom tileSize`() {
        val engine = IsometricEngine()
        // Centre of tile (2,3) with tileSize=2 is world (5.0, 7.0)
        val tileCenter = Point(5.0, 7.0, 0.0)
        val screen = engine.worldToScreen(tileCenter, 800, 600)

        val result = engine.screenToTile(
            screenX = screen.x,
            screenY = screen.y,
            viewportWidth = 800,
            viewportHeight = 600,
            tileSize = 2.0
        )

        assertEquals(TileCoordinate(2, 3), result)
    }

    @Test
    fun `screenToTile with originOffset`() {
        val engine = IsometricEngine()
        // Centre of tile (0,0) when grid origin is at world (-5,-5)
        // → world position (-4.5, -4.5, 0.0)
        val tileCenter = Point(-4.5, -4.5, 0.0)
        val screen = engine.worldToScreen(tileCenter, 800, 600)

        val result = engine.screenToTile(
            screenX = screen.x,
            screenY = screen.y,
            viewportWidth = 800,
            viewportHeight = 600,
            originOffset = Point(-5.0, -5.0, 0.0)
        )

        assertEquals(TileCoordinate(0, 0), result)
    }
}
