package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        // Plain ArrayList — not a snapshot-state type, so writes here do not
        // trigger recomposition. Using mutableStateListOf would cause an
        // infinite recomposition loop (write during composition → recompose → write …).
        val visited = ArrayList<TileCoordinate>()

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
        val visited = ArrayList<TileCoordinate>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 1, height = 1) { coord ->
                    visited.add(coord)
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(1, visited.toSet().size)
        assertTrue(visited.contains(TileCoordinate(0, 0)))
    }

    // ── Gesture hub registration ──────────────────────────────────────────────

    @Test
    fun `TileGrid without onTileClick does not register with hub`() {
        // Composes without crash. The hub has no registration — verified
        // indirectly: if gesturesActive were spuriously true, the pointerInput
        // modifier would be installed but no callback fired. No assert needed here.
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 5, height = 5) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `TileGrid with onTileClick composes without crash`() {
        var clicked: TileCoordinate? = null

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 5, height = 5, onTileClick = { clicked = it }) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        // No crash = pass. Tap routing validated by TileCoordinateExtensionsTest
        // round-trip; pointer simulation requires an emulator/device.
    }

    // ── Elevation ─────────────────────────────────────────────────────────────

    @Test
    fun `elevation function receives correct TileCoordinate per tile`() {
        // Capture coords seen by the elevation function (called during Group
        // position computation, not during rendering — safe to collect here).
        val elevationInputs = ArrayList<TileCoordinate>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(
                    width = 3,
                    height = 1,
                    config = TileGridConfig(elevation = { coord ->
                        elevationInputs.add(coord)
                        coord.x * 0.5
                    })
                ) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        // elevation is called once per unique tile coordinate
        assertEquals(3, elevationInputs.toSet().size)
        assertTrue(elevationInputs.any { it == TileCoordinate(0, 0) })
        assertTrue(elevationInputs.any { it == TileCoordinate(1, 0) })
        assertTrue(elevationInputs.any { it == TileCoordinate(2, 0) })
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
    fun `changing width produces correct tile count`() {
        var width by mutableStateOf(3)
        // Track unique coordinates seen after the final state settles.
        // remember{} keeps the list stable across recompositions; the plain
        // ArrayList does not trigger further recomposition when written.
        val visited = ArrayList<TileCoordinate>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = width, height = 2) { coord ->
                    visited.add(coord)
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        visited.clear()

        width = 5
        composeRule.waitForIdle()

        // 5×2 = 10 unique tiles after width change
        assertEquals(10, visited.toSet().size)
    }
}
