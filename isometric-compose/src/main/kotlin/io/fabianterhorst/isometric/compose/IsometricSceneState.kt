package io.fabianterhorst.isometric.compose

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.Path
import io.fabianterhorst.isometric.Shape

/**
 * State holder for isometric scenes in Compose.
 * Manages the engine and triggers recomposition when the scene changes.
 */
@Stable
class IsometricSceneState internal constructor(
    val engine: IsometricEngine  // Public for benchmark access to cache stats
) {
    // Version counter to trigger recomposition when scene changes
    private var version by mutableIntStateOf(0)

    /**
     * Get the current version (for recomposition keys)
     */
    internal val currentVersion: Int
        get() = version

    /**
     * Add a shape to the scene
     */
    fun add(shape: Shape, color: IsoColor) {
        engine.add(shape, color)
        version++
    }

    /**
     * Add a path to the scene
     */
    fun add(path: Path, color: IsoColor) {
        engine.add(path, color, null)
        version++
    }

    /**
     * Clear all items from the scene
     */
    fun clear() {
        engine.clear()
        version++
    }
}
