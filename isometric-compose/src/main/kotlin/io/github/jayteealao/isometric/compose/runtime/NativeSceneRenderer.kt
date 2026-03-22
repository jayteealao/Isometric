package io.github.jayteealao.isometric.compose.runtime

import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.RenderCommand

/**
 * Android-native rendering backend using `android.graphics.Canvas` directly.
 * Provides ~2x faster rendering compared to Compose DrawScope on Android.
 *
 * TODO(KMP): Move to androidMain source set.
 */
internal class NativeSceneRenderer {
    // Lazy to avoid UnsatisfiedLinkError when constructing on non-Android JVM (e.g. unit tests)
    private val fillPaint by lazy {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    private val strokePaint by lazy {
        Paint().apply {
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }

    /**
     * Render a prepared scene using native Android canvas.
     *
     * @param scene The projected and sorted scene to render
     * @param strokeStyle How to stroke/fill each polygon
     * @param onRenderError Optional callback for per-command render errors
     */
    fun DrawScope.renderNative(
        scene: PreparedScene,
        strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
        onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null
    ) {
        drawIntoCanvas { canvas ->
            val strokeAndroidColor = when (strokeStyle) {
                is StrokeStyle.FillOnly -> null
                is StrokeStyle.Stroke -> strokeStyle.color.toAndroidColor()
                is StrokeStyle.FillAndStroke -> strokeStyle.color.toAndroidColor()
            }
            val strokeWidth = when (strokeStyle) {
                is StrokeStyle.FillOnly -> null
                is StrokeStyle.Stroke -> strokeStyle.width
                is StrokeStyle.FillAndStroke -> strokeStyle.width
            }

            scene.commands.forEach { command ->
                try {
                    val nativePath = command.toNativePath()

                    when (strokeStyle) {
                        is StrokeStyle.FillOnly -> {
                            fillPaint.color = command.color.toAndroidColor()
                            canvas.nativeCanvas.drawPath(nativePath, fillPaint)
                        }
                        is StrokeStyle.Stroke -> {
                            strokePaint.strokeWidth = strokeWidth!!
                            strokePaint.color = strokeAndroidColor!!
                            canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                        }
                        is StrokeStyle.FillAndStroke -> {
                            fillPaint.color = command.color.toAndroidColor()
                            canvas.nativeCanvas.drawPath(nativePath, fillPaint)
                            strokePaint.strokeWidth = strokeWidth!!
                            strokePaint.color = strokeAndroidColor!!
                            canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                        }
                    }
                } catch (e: Exception) {
                    onRenderError?.invoke(command.commandId, e)
                }
            }
        }
    }
}

/**
 * Convert a [RenderCommand] to an Android native [android.graphics.Path].
 * TODO(KMP): Move to androidMain source set.
 */
internal fun RenderCommand.toNativePath(): android.graphics.Path {
    return android.graphics.Path().apply {
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
 * Convert an [io.github.jayteealao.isometric.IsoColor] to an Android color int.
 * TODO(KMP): Move to androidMain source set.
 */
internal fun io.github.jayteealao.isometric.IsoColor.toAndroidColor(): Int {
    return android.graphics.Color.argb(
        a.toInt().coerceIn(0, 255),
        r.toInt().coerceIn(0, 255),
        g.toInt().coerceIn(0, 255),
        b.toInt().coerceIn(0, 255)
    )
}
