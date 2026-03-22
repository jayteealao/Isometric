package io.github.jayteealao.isometric.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand

/**
 * Renderer that converts platform-agnostic RenderCommands to Android Canvas drawing
 */
object AndroidCanvasRenderer {

    /**
     * Render a prepared scene using Android Canvas
     */
    fun renderIsometric(canvas: Canvas, scene: PreparedScene) {
        for (command in scene.commands) {
            val path = command.toAndroidPath()
            val paint = command.color.toAndroidPaint()
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Convert RenderCommand to Android Path
     */
    private fun RenderCommand.toAndroidPath(): Path {
        return Path().apply {
            val pts = points
            if (pts.isEmpty()) return@apply

            moveTo(pts[0].toFloat(), pts[1].toFloat())
            var i = 2
            while (i < pts.size) {
                lineTo(pts[i].toFloat(), pts[i + 1].toFloat())
                i += 2
            }
            close()
        }
    }

    /**
     * Convert IsoColor to Android Paint
     */
    private fun IsoColor.toAndroidPaint(): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 1f
            color = android.graphics.Color.argb(
                a.toInt().coerceIn(0, 255),
                r.toInt().coerceIn(0, 255),
                g.toInt().coerceIn(0, 255),
                b.toInt().coerceIn(0, 255)
            )
        }
    }
}
