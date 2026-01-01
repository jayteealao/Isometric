package io.fabianterhorst.isometric.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.fabianterhorst.isometric.IsoColor
import io.fabianterhorst.isometric.PreparedScene
import io.fabianterhorst.isometric.RenderCommand

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
            if (points.isEmpty()) return@apply

            moveTo(points[0].x.toFloat(), points[0].y.toFloat())
            for (i in 1 until points.size) {
                lineTo(points[i].x.toFloat(), points[i].y.toFloat())
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
