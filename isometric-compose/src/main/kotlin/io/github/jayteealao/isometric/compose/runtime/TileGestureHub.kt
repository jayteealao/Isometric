package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.TileCoordinate
import io.github.jayteealao.isometric.TileGridConfig
import io.github.jayteealao.isometric.screenToTile

/**
 * Internal registration record for a single TileGrid's tap handler.
 *
 * @param config Grid configuration (tile size, origin offset)
 * @param gridWidth Width in tiles — used for bounds-checking after coordinate conversion
 * @param gridHeight Height in tiles — used for bounds-checking after coordinate conversion
 * @param onTileClick Callback invoked when the tap maps to a tile within bounds
 */
internal class TileGestureRegistration(
    val config: TileGridConfig,
    val gridWidth: Int,
    val gridHeight: Int,
    val onTileClick: (TileCoordinate) -> Unit
)

/**
 * Collects tap handlers from TileGrid composables and routes scene-level
 * tap events to the correct handler with coordinate conversion.
 *
 * Created by IsometricScene and provided via [LocalTileGestureHub].
 * TileGrid registers into it using DisposableEffect.
 */
internal class TileGestureHub {

    private val registrations = mutableStateListOf<TileGestureRegistration>()

    /**
     * True when at least one TileGrid has registered a tap handler.
     * Backed by snapshot state — readable as a Compose state observable.
     */
    val hasHandlers: Boolean
        get() = registrations.isNotEmpty()

    /**
     * Registers a tile tap handler. Returns an unregister function intended
     * for use in a [androidx.compose.runtime.DisposableEffect] onDispose block.
     */
    fun register(registration: TileGestureRegistration): () -> Unit {
        registrations.add(registration)
        return { registrations.remove(registration) }
    }

    /**
     * Converts [tapX]/[tapY] screen coordinates to a tile coordinate for each
     * registered handler and invokes the handler if the tile falls within the
     * grid's declared bounds.
     *
     * Uses [TileGridConfig.originOffset].z as the Z-plane for inverse projection so that
     * grids elevated above z = 0 (e.g. raised platforms) produce correct tile coordinates.
     *
     * Called by IsometricScene during tap event processing, after the
     * user-facing [GestureConfig.onTap] callback has already fired.
     *
     * Expects [tapX]/[tapY] to already be corrected for camera pan/zoom — the
     * same coordinates passed to hit testing, not the raw pointer position.
     *
     * @param tapX Engine-space x-coordinate of the tap (px)
     * @param tapY Engine-space y-coordinate of the tap (px)
     * @param viewportWidth Scene canvas width at the time of tap (px)
     * @param viewportHeight Scene canvas height at the time of tap (px)
     * @param engine The scene's projection engine (used for inverse projection)
     */
    fun dispatch(
        tapX: Double,
        tapY: Double,
        viewportWidth: Int,
        viewportHeight: Int,
        engine: IsometricEngine
    ) {
        for (reg in registrations) {
            val tile = engine.screenToTile(
                screenX = tapX,
                screenY = tapY,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                tileSize = reg.config.tileSize,
                elevation = reg.config.originOffset.z,
                originOffset = reg.config.originOffset
            )
            if (tile.isWithin(reg.gridWidth, reg.gridHeight)) {
                reg.onTileClick(tile)
            }
        }
    }
}

/**
 * Provides the [TileGestureHub] created by the nearest enclosing IsometricScene.
 * Null when accessed outside an IsometricScene (e.g., in previews or tests without
 * a scene host).
 */
internal val LocalTileGestureHub = staticCompositionLocalOf<TileGestureHub?> { null }
