package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand
import io.fabianterhorst.isometric.RenderOptions
import java.util.concurrent.atomic.AtomicLong

/**
 * Renderer that converts platform-agnostic RenderCommands to Compose drawing
 */
object ComposeRenderer {

    // Draw call statistics (thread-safe)
    private val drawCallCounter = AtomicLong(0)

    val drawCallCount: Long
        get() = drawCallCounter.get()

    fun resetDrawCallCount() {
        drawCallCounter.set(0)
    }

    // Drawing time tracking (for benchmarking)
    var lastDrawTimeNanos: Long = 0
        internal set

    fun resetDrawTime() {
        lastDrawTimeNanos = 0
    }

    fun incrementDrawCallCount() {
        drawCallCounter.incrementAndGet()
    }

    /**
     * Render a prepared scene using Compose DrawScope
     *
     * Note: For cached rendering (enableDrawWithCache), caching happens at the
     * Canvas modifier level in IsometricCanvas.kt. This function handles the
     * non-cached fallback path.
     *
     * TODO: strokeWidth and drawStroke parameters are currently hardcoded (1f, true).
     * These can be added to RenderOptions in a future enhancement if needed.
     */
    @Suppress("UNUSED_PARAMETER")
    fun DrawScope.renderIsometric(scene: PreparedScene, options: RenderOptions) {
        val drawStart = System.nanoTime()

        // Non-cached rendering: Convert on-demand
        scene.commands.forEach { command ->
            val path = command.toComposePath()
            val fillColor = command.color.toComposeColor()

            drawPath(path, fillColor)
            drawCallCounter.incrementAndGet()
            drawPath(path, Color.Black.copy(alpha = 0.2f), style = Stroke(width = 1f))
            drawCallCounter.incrementAndGet()
        }

        lastDrawTimeNanos = System.nanoTime() - drawStart
    }

    /**
     * Convert RenderCommand to Compose Path
     */
    internal fun RenderCommand.toComposePath(): Path {
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
