package io.github.jayteealao.isometric.compose.runtime

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.TileGridConfig
import org.junit.Test
import kotlin.math.PI

/**
 * AC-22 — TileGrid tap dispatch works with a standard [IsometricEngine] projector.
 *
 * Verifies that [TileGestureHub.dispatch] correctly converts an engine-space screen
 * coordinate to a tile coordinate and fires [onTileClick] with the right value.
 * This covers the L5 finding: the dispatch path already uses [IsometricEngine]
 * directly; these tests confirm the end-to-end wiring is correct.
 *
 * Device-free — uses the same callback/state pattern as the other gesture tests.
 */
class TileGestureHubTest {

    private val defaultEngine = IsometricEngine(angle = PI / 6, scale = 70.0)
    private val viewportW = 800
    private val viewportH = 600

    /**
     * AC-22 — Tap at the screen position that corresponds to tile (0,0) fires
     * [onTileClick] with [TileCoordinate](0, 0).
     */
    @Test
    fun `AC-22 dispatch fires onTileClick for a tap on a known tile`() {
        val hub = TileGestureHub()
        val config = TileGridConfig(tileSize = 1.0, originOffset = Point.ORIGIN)

        val fired = mutableListOf<TileCoordinate>()
        hub.register(TileGestureRegistration(
            config = config,
            gridWidth = 10,
            gridHeight = 10,
            onTileClick = { fired.add(it) }
        ))

        // Compute the screen position for the center of world tile (0,0):
        // tile (0,0) center is at world (0.5, 0.5, 0).
        val worldCenter = Point(0.5, 0.5, 0.0)
        val screen = defaultEngine.worldToScreen(worldCenter, viewportW, viewportH)

        hub.dispatch(
            tapX = screen.x,
            tapY = screen.y,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            engine = defaultEngine
        )

        assertThat(fired).hasSize(1)
        // The tap at world (0.5, 0.5) maps to tile (0, 0) with tileSize=1.
        assertThat(fired[0].x).isEqualTo(0)
        assertThat(fired[0].y).isEqualTo(0)
    }

    /**
     * AC-22 — Tap at tile (2, 3) fires [onTileClick] with the correct coordinate.
     */
    @Test
    fun `AC-22 dispatch fires correct tile coordinate for tile 2 3`() {
        val hub = TileGestureHub()
        val config = TileGridConfig(tileSize = 1.0, originOffset = Point.ORIGIN)

        val fired = mutableListOf<TileCoordinate>()
        hub.register(TileGestureRegistration(
            config = config,
            gridWidth = 10,
            gridHeight = 10,
            onTileClick = { fired.add(it) }
        ))

        // Tile (2,3) center is at world (2.5, 3.5, 0).
        val worldCenter = Point(2.5, 3.5, 0.0)
        val screen = defaultEngine.worldToScreen(worldCenter, viewportW, viewportH)

        hub.dispatch(
            tapX = screen.x,
            tapY = screen.y,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            engine = defaultEngine
        )

        assertThat(fired).hasSize(1)
        assertThat(fired[0].x).isEqualTo(2)
        assertThat(fired[0].y).isEqualTo(3)
    }

    /**
     * Out-of-bounds taps do NOT fire [onTileClick].
     */
    @Test
    fun `dispatch does not fire when tap is outside grid bounds`() {
        val hub = TileGestureHub()
        val config = TileGridConfig(tileSize = 1.0, originOffset = Point.ORIGIN)

        val fired = mutableListOf<TileCoordinate>()
        hub.register(TileGestureRegistration(
            config = config,
            gridWidth = 3,
            gridHeight = 3,
            onTileClick = { fired.add(it) }
        ))

        // World position far outside the 3×3 grid.
        val worldFar = Point(100.0, 100.0, 0.0)
        val screen = defaultEngine.worldToScreen(worldFar, viewportW, viewportH)

        hub.dispatch(
            tapX = screen.x,
            tapY = screen.y,
            viewportWidth = viewportW,
            viewportHeight = viewportH,
            engine = defaultEngine
        )

        assertThat(fired).isEmpty()
    }

    /**
     * [TileGestureHub.hasHandlers] is true after registration and false after
     * the returned unregister function is called.
     */
    @Test
    fun `hasHandlers reflects registration state`() {
        val hub = TileGestureHub()
        assertThat(hub.hasHandlers).isFalse()

        val config = TileGridConfig()
        val unregister = hub.register(TileGestureRegistration(
            config = config,
            gridWidth = 5,
            gridHeight = 5,
            onTileClick = {}
        ))

        assertThat(hub.hasHandlers).isTrue()
        unregister()
        assertThat(hub.hasHandlers).isFalse()
    }
}
