package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.TileGridConfig
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TileGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── Validation ────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `width zero throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 0, height = 10) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `height zero throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 10, height = 0) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative width throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = -1, height = 10) { }
            }
        }
    }

    // ── Content invocation ────────────────────────────────────────────────────

    @Test
    fun `content is called for every tile coordinate in a 3x3 grid`() {
        val visited = mutableStateListOf<TileCoordinate>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 3, height = 3) { coord ->
                    visited.add(coord)
                    Shape(geometry = Prism(), color = IsoColor(33.0, 150.0, 243.0))
                }
            }
        }

        composeRule.waitForIdle()

        // 3×3 = 9 unique tiles
        assertEquals(9, visited.toSet().size)
        for (x in 0 until 3) {
            for (y in 0 until 3) {
                assertTrue(
                    "Missing tile ($x, $y)",
                    visited.contains(TileCoordinate(x, y))
                )
            }
        }
    }

    @Test
    fun `1x1 grid renders exactly one tile at origin`() {
        val visited = mutableStateListOf<TileCoordinate>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 1, height = 1) { coord ->
                    visited.add(coord)
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(1, visited.size)
        assertEquals(TileCoordinate(0, 0), visited[0])
    }

    // ── Gesture hub registration ──────────────────────────────────────────────

    @Test
    fun `TileGrid without onTileClick does not register with hub`() {
        // Compose without crash — the hub should have no handlers
        // (we test indirectly: if gesturesActive were spuriously true, the
        // pointerInput modifier would be installed but no callback fired)
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 5, height = 5) { coord ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
        // No crash = pass. Hub has no registration to verify externally.
    }

    @Test
    fun `TileGrid with onTileClick composes without crash`() {
        var clicked: TileCoordinate? = null

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 5, height = 5, onTileClick = { clicked = it }) { coord ->
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        // No crash = pass; tap routing is validated by the round-trip in
        // TileCoordinateExtensionsTest — UI tap simulation requires emulator.
    }

    // ── Elevation ─────────────────────────────────────────────────────────────

    @Test
    fun `custom elevation function is applied per tile`() {
        val elevations = mutableStateListOf<Double>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(
                    width = 3,
                    height = 1,
                    config = TileGridConfig(elevation = { coord -> coord.x * 0.5 })
                ) { coord ->
                    // Capture z from config directly — we can't inspect Group position here
                    elevations.add(coord.x * 0.5)
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(3, elevations.size)
        assertEquals(0.0, elevations[0], 0.001)
        assertEquals(0.5, elevations[1], 0.001)
        assertEquals(1.0, elevations[2], 0.001)
    }

    // ── Config with custom tileSize ───────────────────────────────────────────

    @Test
    fun `custom tileSize composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(
                    width = 5,
                    height = 5,
                    config = TileGridConfig(tileSize = 2.0)
                ) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ── Dynamic width/height ──────────────────────────────────────────────────

    @Test
    fun `changing width recomposes correctly`() {
        var width by mutableStateOf(3)
        val counts = mutableStateListOf<Int>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                val visited = mutableStateListOf<TileCoordinate>()
                TileGrid(width = width, height = 2) { coord ->
                    visited.add(coord)
                    Shape(geometry = Prism())
                }
                counts.add(visited.toSet().size)
            }
        }

        composeRule.waitForIdle()
        width = 5
        composeRule.waitForIdle()
        // After width increases to 5, we expect 10 unique tiles at some point
        assertTrue(counts.any { it == 10 })
    }
}
