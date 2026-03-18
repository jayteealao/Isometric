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
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.StackAxis
import io.github.jayteealao.isometric.shapes.Prism
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StackTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── Validation ────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `count zero throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 0) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `count negative throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = -1) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gap zero throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, gap = 0.0) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gap NaN throws at composition time with finite message`() {
        // isFinite check fires before != 0.0 check
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, gap = Double.NaN) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gap positive infinity throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, gap = Double.POSITIVE_INFINITY) { }
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gap negative infinity throws at composition time`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, gap = Double.NEGATIVE_INFINITY) { }
            }
        }
    }

    // ── Content invocation count ──────────────────────────────────────────────

    @Test
    fun `content is called exactly count times`() {
        val indices = mutableStateListOf<Int>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 5) { index ->
                    indices.add(index)
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(5, indices.size)
    }

    @Test
    fun `indices are 0-based and sequential`() {
        val indices = mutableStateListOf<Int>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 4) { index ->
                    indices.add(index)
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(listOf(0, 1, 2, 3), indices.sorted())
    }

    @Test
    fun `count 1 renders exactly one child with index 0`() {
        val indices = mutableStateListOf<Int>()

        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 1) { index ->
                    indices.add(index)
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(1, indices.size)
        assertEquals(0, indices[0])
    }

    // ── Axis / gap ────────────────────────────────────────────────────────────

    @Test
    fun `Z axis stack composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, axis = StackAxis.Z, gap = 1.0) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `X axis stack composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, axis = StackAxis.X, gap = 2.0) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `Y axis stack composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, axis = StackAxis.Y, gap = 1.5) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `negative gap composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, axis = StackAxis.Z, gap = -1.0) { _ ->
                    Shape(geometry = Prism())
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ── Dynamic count ─────────────────────────────────────────────────────────

    @Test
    fun `increasing count adds children`() {
        var count by mutableStateOf(2)
        val invocations = mutableStateListOf<Int>()

        composeRule.setContent {
            invocations.clear()
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = count) { index ->
                    invocations.add(index)
                    Shape(geometry = Prism())
                }
            }
        }

        composeRule.waitForIdle()
        val firstCount = invocations.size
        assertEquals(2, firstCount)

        count = 5
        composeRule.waitForIdle()
        assertEquals(5, invocations.size)
    }

    // ── Nesting ───────────────────────────────────────────────────────────────

    @Test
    fun `nested stacks compose without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Stack(count = 3, axis = StackAxis.X, gap = 2.0) { _ ->
                    Stack(count = 2, axis = StackAxis.Z, gap = 1.0) { _ ->
                        Shape(geometry = Prism())
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `Stack inside TileGrid content block composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                TileGrid(width = 3, height = 3) { coord ->
                    val towerHeight = coord.x + coord.y + 1
                    Stack(count = towerHeight, axis = StackAxis.Z, gap = 0.5) { _ ->
                        Shape(geometry = Prism())
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `Stack inside Group composes without crash`() {
        composeRule.setContent {
            IsometricScene(modifier = Modifier.fillMaxSize()) {
                Group(position = Point(2.0, 3.0, 0.0)) {
                    Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { _ ->
                        Shape(geometry = Prism())
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
