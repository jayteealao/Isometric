package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.Point2D
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand

/**
 * Renderer that converts platform-agnostic RenderCommands to Compose drawing
 */
object ComposeRenderer {

    /**
     * Render a prepared scene using Compose DrawScope
     */
    fun DrawScope.renderIsometric(scene: PreparedScene, strokeWidth: Float = 1f, drawStroke: Boolean = true) {
        for (command in scene.commands) {
            val path = command.toComposePath()
            val color = command.color.toComposeColor()

            // Draw fill
            drawPath(path, color, style = Fill)

            // Optionally draw stroke
            if (drawStroke) {
                drawPath(path, Color.Black.copy(alpha = 0.1f), style = Stroke(width = strokeWidth))
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
            red = (r / 255f).coerceIn(0f, 1f),
            green = (g / 255f).coerceIn(0f, 1f),
            blue = (b / 255f).coerceIn(0f, 1f),
            alpha = (a / 255f).coerceIn(0f, 1f)
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
