package io.github.jayteealao.isometric.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.StackAxis
import io.github.jayteealao.isometric.TileGridConfig
import io.github.jayteealao.isometric.compose.runtime.Group
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.compose.runtime.Stack
import io.github.jayteealao.isometric.compose.runtime.TileGrid
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid

/**
 * Demonstrates all three [StackAxis] values.
 *
 * Three sub-scenes:
 * 1. Vertical Z-axis tower — the hero scenario
 * 2. Horizontal X-axis row
 * 3. Depth Y-axis row of pyramids
 */
@Composable
fun StackExample() {
    val floorColors = listOf(
        IsoColor(33.0, 150.0, 243.0),   // blue  — ground floor
        IsoColor(76.0, 175.0, 80.0),    // green — middle floor
        IsoColor(255.0, 193.0, 7.0),    // amber — upper floor
        IsoColor(244.0, 67.0, 54.0)     // red   — roof
    )

    IsometricScene(modifier = Modifier.fillMaxSize()) {

        // 1. Vertical tower — 4 floors stacked along Z (hero scenario)
        Group(position = Point(1.0, 1.0, 0.0)) {
            Stack(count = 4, axis = StackAxis.Z, gap = 1.0) { floor ->
                Shape(geometry = Prism(), material = floorColors[floor])
            }
        }

        // 2. Horizontal row — 4 prisms along X, spaced 1.5 world units
        Group(position = Point(-1.0, 4.0, 0.0)) {
            Stack(count = 4, axis = StackAxis.X, gap = 1.5) { _ ->
                Shape(geometry = Prism(), material = IsoColor(120.0, 144.0, 156.0))
            }
        }

        // 3. Depth row — 3 pyramids along Y, spaced 1.5 world units
        Group(position = Point(4.0, 0.0, 0.0)) {
            Stack(count = 3, axis = StackAxis.Y, gap = 1.5) { _ ->
                Shape(geometry = Pyramid(), material = IsoColor(156.0, 39.0, 176.0))
            }
        }
    }
}

/**
 * Demonstrates [Stack] nested inside [TileGrid] — each tile carries a tower
 * whose height varies by grid position.
 */
@Composable
fun GridStackExample() {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        TileGrid(
            width = 3,
            height = 3,
            config = TileGridConfig(originOffset = Point(0.0, 0.0, 0.0))
        ) { coord ->
            val towerHeight = coord.x + coord.y + 1
            Stack(count = towerHeight, axis = StackAxis.Z, gap = 0.5) { _ ->
                Shape(geometry = Prism(height = 0.4), material = IsoColor(0.0, 188.0, 212.0))
            }
        }
    }
}
