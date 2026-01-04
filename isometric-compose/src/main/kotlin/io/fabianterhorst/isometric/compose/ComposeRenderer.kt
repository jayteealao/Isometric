package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawWithCache
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point2D
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions

/**
 * Cached draw command containing pre-built paths and colors
 */
private data class CachedDrawCommand(
    val fillPath: androidx.compose.ui.graphics.Path,
    val fillColor: androidx.compose.ui.graphics.Color,
    val strokePath: androidx.compose.ui.graphics.Path,
    val strokeColor: androidx.compose.ui.graphics.Color
)

/**
 * Renderer that converts platform-agnostic RenderCommands to Compose drawing
 */
object ComposeRenderer {

    /**
     * Render a prepared scene using Compose DrawScope with optional draw command caching
     */
    fun DrawScope.renderIsometric(scene: PreparedScene, options: RenderOptions) {
        if (options.enableDrawWithCache) {
            // Use cache with PreparedScene reference as key
            drawWithCache {
                val cachedCommands = scene.commands.map { command ->
                    CachedDrawCommand(
                        fillPath = command.toComposePath(),
                        fillColor = command.color.toComposeColor(),
                        strokePath = command.toComposePath(),
                        strokeColor = Color.Black.copy(alpha = 0.2f)
                    )
                }

                onDrawBehind {
                    cachedCommands.forEach { cached ->
                        drawPath(cached.fillPath, cached.fillColor)
                        drawPath(cached.strokePath, cached.strokeColor, style = Stroke(width = 1f))
                    }
                }
            }
        } else {
            // Original non-cached path (preserve existing behavior when cache disabled)
            scene.commands.forEach { command ->
                val path = command.toComposePath()
                val fillColor = command.color.toComposeColor()

                drawPath(path, fillColor)
                drawPath(path, Color.Black.copy(alpha = 0.2f), style = Stroke(width = 1f))
            }
        }
    }

    /**
     * Convert RenderCommand to Compose Path
     */
    private fun RenderCommand.toComposePath(): Path {
        return Path().apply {
            if (points.isEmpty()) return@apply

            moveTo(points[0].x.toFloat(), points[0].y.toFloat())
            for (i in 1 until points.size) {
                lineTo(points[i].x.toFloat(), points[i].y.toFloat())
            }
            close()
        }
    }

    /**
     * Convert IsoColor to Compose Color
     */
    fun IsoColor.toComposeColor(): Color {
        return Color(
            red = (r.toFloat() / 255f).coerceIn(0f, 1f),
            green = (g.toFloat() / 255f).coerceIn(0f, 1f),
            blue = (b.toFloat() / 255f).coerceIn(0f, 1f),
            alpha = (a.toFloat() / 255f).coerceIn(0f, 1f)
        )
    }

    /**
     * Convert Compose Color to IsoColor
     */
    fun Color.toIsoColor(): IsoColor {
        return IsoColor(
            r = (red * 255).toDouble(),
            g = (green * 255).toDouble(),
            b = (blue * 255).toDouble(),
            a = (alpha * 255).toDouble()
        )
    }
}
