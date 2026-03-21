package io.github.jayteealao.isometric.compose.runtime.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle
import io.github.jayteealao.isometric.compose.toComposeColor
import io.github.jayteealao.isometric.compose.toComposePath

/**
 * Canvas-based [RenderBackend] implementation.
 *
 * Draws the [PreparedScene] using Compose's `DrawScope.drawPath()`. This is the default
 * backend and is always available on all platforms.
 *
 * This backend is a pure drawing surface:
 * - No node tree access — the backend never touches the mutable scene graph.
 * - No dirty tracking — [IsometricScene] owns that.
 * - No renderer instance — draws directly from the immutable [PreparedScene].
 * - `modifier` is passed through as-is — respects caller sizing.
 */
internal class CanvasRenderBackend : RenderBackend {

    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    ) {
        Canvas(modifier = modifier) {
            val scene = preparedScene.value ?: return@Canvas
            renderPreparedScene(scene, strokeStyle)
        }
    }

    /**
     * Draw all commands from the prepared scene.
     *
     * The stroke color and Stroke instance are computed once per frame, not per command.
     */
    private fun DrawScope.renderPreparedScene(scene: PreparedScene, strokeStyle: StrokeStyle) {
        val strokeComposeColor = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> strokeStyle.color.toComposeColor()
            is StrokeStyle.FillAndStroke -> strokeStyle.color.toComposeColor()
        }
        val strokeDrawStyle = when (strokeStyle) {
            is StrokeStyle.FillOnly -> null
            is StrokeStyle.Stroke -> Stroke(width = strokeStyle.width)
            is StrokeStyle.FillAndStroke -> Stroke(width = strokeStyle.width)
        }

        for (command in scene.commands) {
            val path = command.toComposePath()
            val color = command.color.toComposeColor()

            when (strokeStyle) {
                is StrokeStyle.FillOnly -> {
                    drawPath(path, color, style = Fill)
                }
                is StrokeStyle.Stroke -> {
                    drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                }
                is StrokeStyle.FillAndStroke -> {
                    drawPath(path, color, style = Fill)
                    drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
                }
            }
        }
    }

    override fun toString(): String = "RenderBackend.Canvas"
}
