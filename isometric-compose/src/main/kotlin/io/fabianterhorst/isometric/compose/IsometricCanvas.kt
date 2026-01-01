package io.fabianterhorst.isometric.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions

/**
 * Main Isometric Canvas composable for rendering 3D isometric scenes.
 *
 * @param state The scene state (use rememberIsometricSceneState())
 * @param modifier Modifier to apply to the canvas
 * @param renderOptions Rendering configuration options
 * @param strokeWidth Width of outline strokes
 * @param drawStroke Whether to draw outlines around shapes
 * @param onItemClick Callback when a shape is clicked
 * @param content Scene construction lambda
 */
@Composable
fun IsometricCanvas(
    state: IsometricSceneState = rememberIsometricSceneState(),
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
    strokeWidth: Float = 1f,
    drawStroke: Boolean = true,
    onItemClick: (RenderCommand) -> Unit = {},
    content: IsometricScope.() -> Unit
) {
    // Build scene
    val scope = remember(state) { IsometricScopeImpl(state) }
    scope.content()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(state.currentVersion) {
                detectTapGestures { offset ->
                    handleTap(state, offset, renderOptions, onItemClick)
                }
            }
    ) {
        val preparedScene = remember(size, state.currentVersion, renderOptions) {
            state.engine.prepare(
                width = size.width.toInt(),
                height = size.height.toInt(),
                options = renderOptions
            )
        }

        with(ComposeRenderer) {
            renderIsometric(preparedScene, strokeWidth, drawStroke)
        }
    }
}

/**
 * Handle tap gestures for hit testing
 */
private fun handleTap(
    state: IsometricSceneState,
    offset: Offset,
    renderOptions: RenderOptions,
    onItemClick: (RenderCommand) -> Unit
) {
    // Need to prepare scene to get commands for hit testing
    // This is a simplified approach - ideally we'd cache the last prepared scene
    val tempScene = state.engine.prepare(800, 600, renderOptions)
    val hit = state.engine.findItemAt(
        preparedScene = tempScene,
        x = offset.x.toDouble(),
        y = offset.y.toDouble(),
        reverseSort = true,
        useRadius = true,
        radius = 8.0
    )
    hit?.let { onItemClick(it) }
}

/**
 * Scope for building isometric scenes
 */
interface IsometricScope {
    /**
     * Add a shape to the scene
     */
    fun add(shape: io.fabianterhorst.isometric.Shape, color: IsoColor)

    /**
     * Add a path to the scene
     */
    fun add(path: io.fabianterhorst.isometric.Path, color: IsoColor)

    /**
     * Clear all items from the scene
     */
    fun clear()
}

/**
 * Implementation of IsometricScope
 */
private class IsometricScopeImpl(
    private val state: IsometricSceneState
) : IsometricScope {

    override fun add(shape: io.fabianterhorst.isometric.Shape, color: IsoColor) {
        state.add(shape, color)
    }

    override fun add(path: io.fabianterhorst.isometric.Path, color: IsoColor) {
        state.add(path, color)
    }

    override fun clear() {
        state.clear()
    }
}
