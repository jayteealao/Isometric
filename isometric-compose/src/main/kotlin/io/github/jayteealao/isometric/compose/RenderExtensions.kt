package io.github.jayteealao.isometric.compose

import androidx.compose.ui.graphics.Path
import io.github.jayteealao.isometric.RenderCommand

/**
 * Convert a [RenderCommand] to a Compose [Path] for drawing.
 *
 * Shared conversion used by both the cached and uncached rendering paths
 * in [io.github.jayteealao.isometric.compose.runtime.IsometricRenderer].
 */
fun RenderCommand.toComposePath(): Path {
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
