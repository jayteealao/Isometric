package io.github.jayteealao.isometric.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.PreparedScene

/**
 * Renderer that converts platform-agnostic RenderCommands to Android Canvas drawing.
 *
 * ## Paint and Path reuse
 *
 * A single [Paint] and a single [Path] are allocated before the render loop and
 * reused across all faces. The Path is reset via [Path.reset] at the start of each
 * face; all Paint fields (color, style, strokeWidth) are set explicitly per face so
 * state does not leak between iterations. This eliminates the per-face allocation
 * storm that previously created one Paint and one Path object for every face in the
 * scene.
 */
object AndroidCanvasRenderer {

    /**
     * Render a prepared scene using Android Canvas.
     *
     * @param canvas The canvas to draw onto.
     * @param scene The prepared scene to render.
     * @param strokeStyle Controls how faces are painted. Defaults to [StrokeStyle.FillAndStroke].
     */
    fun renderIsometric(
        canvas: Canvas,
        scene: PreparedScene,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke()
    ) {
        // One Path and one Paint reused across all faces (M5 — no per-face allocation).
        val androidPath = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (command in scene.commands) {
            // Build the path for this face, reusing the pre-allocated instance.
            androidPath.reset()
            if (command.points.isEmpty()) continue
            androidPath.moveTo(command.points[0].x.toFloat(), command.points[0].y.toFloat())
            for (i in 1 until command.points.size) {
                androidPath.lineTo(command.points[i].x.toFloat(), command.points[i].y.toFloat())
            }
            androidPath.close()

            // Set the face fill color and apply stroke style (L8).
            val faceColor = android.graphics.Color.argb(
                command.color.a.toInt().coerceIn(0, 255),
                command.color.r.toInt().coerceIn(0, 255),
                command.color.g.toInt().coerceIn(0, 255),
                command.color.b.toInt().coerceIn(0, 255)
            )

            when (strokeStyle) {
                is StrokeStyle.FillOnly -> {
                    paint.style = Paint.Style.FILL
                    paint.color = faceColor
                    paint.strokeWidth = 0f
                    canvas.drawPath(androidPath, paint)
                }
                is StrokeStyle.Stroke -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = strokeStyle.width
                    paint.color = toAndroidColor(strokeStyle.color)
                    canvas.drawPath(androidPath, paint)
                }
                is StrokeStyle.FillAndStroke -> {
                    // Draw fill first, then stroke on top so the stroke is not clipped.
                    paint.style = Paint.Style.FILL
                    paint.color = faceColor
                    paint.strokeWidth = 0f
                    canvas.drawPath(androidPath, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = strokeStyle.width
                    paint.color = toAndroidColor(strokeStyle.color)
                    canvas.drawPath(androidPath, paint)
                }
            }
        }
    }

    private fun toAndroidColor(color: IsoColor): Int =
        android.graphics.Color.argb(
            color.a.toInt().coerceIn(0, 255),
            color.r.toInt().coerceIn(0, 255),
            color.g.toInt().coerceIn(0, 255),
            color.b.toInt().coerceIn(0, 255)
        )
}
