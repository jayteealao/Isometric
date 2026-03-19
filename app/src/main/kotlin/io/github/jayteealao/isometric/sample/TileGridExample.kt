package io.github.jayteealao.isometric.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.compose.runtime.TileGrid
import io.github.jayteealao.isometric.shapes.Prism

/**
 * Interactive tile selector: a 10×10 grid where tapping a tile highlights it.
 *
 * Demonstrates the hero scenario for [TileGrid]:
 * - No [GestureConfig] required on the scene — tap handling activates automatically
 * - Tile selection state is plain Compose state, not library-managed
 * - Content lambda receives the [TileCoordinate] and renders based on selection
 */
@Composable
fun TileGridExample() {
    var selectedTile by remember { mutableStateOf<TileCoordinate?>(null) }

    IsometricScene(modifier = Modifier.fillMaxSize()) {
        TileGrid(
            width = 6,
            height = 6,
            onTileClick = { coord -> selectedTile = coord }
        ) { coord ->
            Shape(
                geometry = Prism(width = 0.9, depth = 0.9, height = 0.3),
                color = if (coord == selectedTile) IsoColor(33.0, 150.0, 243.0) else IsoColor(120.0, 144.0, 156.0)
            )
        }
    }
}
