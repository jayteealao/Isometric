package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.TileGridConfig

/**
 * Renders an isometric tile grid and routes tap events to tile coordinates.
 *
 * Tiles are arranged in a [width] × [height] grid. Each tile is rendered by
 * the [content] lambda, which receives the [TileCoordinate] of the tile being
 * drawn. Content is rendered in the coordinate space of the tile — a [Shape]
 * with the default position renders at the tile's world origin without any
 * additional positioning.
 *
 * When [onTileClick] is provided, taps on the scene are automatically converted
 * from screen coordinates to tile coordinates. Only taps within the grid bounds
 * invoke the callback. No [GestureConfig] on the enclosing [IsometricScene] is
 * required — gesture handling is enabled automatically.
 *
 * ```kotlin
 * IsometricScene(modifier = Modifier.fillMaxSize()) {
 *     TileGrid(
 *         width = 10,
 *         height = 10,
 *         onTileClick = { coord -> selectedTile = coord }
 *     ) { coord ->
 *         Shape(
 *             geometry = Prism(),
 *             color = if (coord == selectedTile) IsoColor.BLUE else IsoColor.GRAY
 *         )
 *     }
 * }
 * ```
 *
 * **Elevation and tap accuracy**: [onTileClick] assumes a flat ground plane
 * (z = 0) for hit-testing. For elevated terrain, tap routing may miss tiles
 * whose visual surface is above z = 0. Use the escape hatch: configure
 * [GestureConfig.onTap] on the scene and call [IsometricEngine.screenToTile][io.github.jayteealao.isometric.screenToTile]
 * with the appropriate elevation using [LocalIsometricEngine].
 *
 * @param width Number of tile columns (must be ≥ 1)
 * @param height Number of tile rows (must be ≥ 1)
 * @param config Grid configuration: tile size, world origin, optional elevation function.
 *   Defaults to a 1-unit flat grid at world origin.
 * @param onTileClick Called when the user taps a tile within this grid's bounds.
 *   Null by default (no tap handling).
 * @param content Composable lambda for each tile. Receives the tile's [TileCoordinate].
 *   Rendered in the tile's local coordinate space — the tile origin is at (0, 0, 0)
 *   relative to the group.
 * @see Stack
 * @see Group
 */
@IsometricComposable
@Composable
fun IsometricScope.TileGrid(
    width: Int,
    height: Int,
    config: TileGridConfig = TileGridConfig(),
    onTileClick: ((TileCoordinate) -> Unit)? = null,
    content: @Composable IsometricScope.(TileCoordinate) -> Unit
) {
    require(width >= 1) { "TileGrid width must be at least 1, got $width" }
    require(height >= 1) { "TileGrid height must be at least 1, got $height" }

    // Build the full coordinate list once per grid dimension change.
    // Config changes do not affect the coordinate list — only tile positions.
    val coordinates = remember(width, height) {
        (0 until width).flatMap { x -> (0 until height).map { y -> TileCoordinate(x, y) } }
    }

    // Render each tile as a Group positioned at its world coordinate.
    // Content inside the Group uses the tile origin as its local (0,0,0).
    ForEach(items = coordinates, key = { it }) { coord ->
        val elevation = config.elevation?.invoke(coord) ?: config.originOffset.z
        val tilePosition = Point(
            x = config.originOffset.x + coord.x * config.tileSize,
            y = config.originOffset.y + coord.y * config.tileSize,
            z = elevation
        )
        Group(position = tilePosition) {
            content(coord)
        }
    }

    // Register a tap handler with the hub for automatic screen → tile routing.
    // rememberUpdatedState ensures the registration always calls the latest
    // onTileClick without needing to re-register on every recomposition.
    val hub = LocalTileGestureHub.current
    val currentOnTileClick by rememberUpdatedState(onTileClick)

    if (hub != null && onTileClick != null) {
        DisposableEffect(hub, config, width, height) {
            val registration = TileGestureRegistration(
                config = config,
                gridWidth = width,
                gridHeight = height,
                onTileClick = { coord -> currentOnTileClick?.invoke(coord) }
            )
            val unregister = hub.register(registration)
            onDispose { unregister() }
        }
    }
}
