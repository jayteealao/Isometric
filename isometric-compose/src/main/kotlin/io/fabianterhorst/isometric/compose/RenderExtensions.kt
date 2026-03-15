package io.fabianterhorst.isometric.compose

import androidx.compose.ui.graphics.Path
import io.fabianterhorst.isometric.RenderCommand

/**
 * Convert a [RenderCommand] to a Compose [Path] for drawing.
 *
 * Shared conversion used by both the cached and uncached rendering paths
 * in [io.fabianterhorst.isometric.compose.runtime.IsometricRenderer].
 */
fun RenderCommand.toComposePath(): Path {
    return Path().apply {
        if (points.isEmpty()) return@apply

        moveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (i in 1 until points.size) {
            lineTo(points[i].x.toFloat(), points[i].y.toFloat())
        }
        close()
    }
}
