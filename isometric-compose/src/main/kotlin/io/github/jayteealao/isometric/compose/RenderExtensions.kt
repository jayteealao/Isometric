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

/**
 * Fill an existing [Path] with this command's geometry, resetting it first.
 *
 * Used by the path pool in [io.github.jayteealao.isometric.compose.runtime.IsometricRenderer]
 * to avoid per-frame Path allocation. Compose [Path] wraps native Skia SkPath,
 * making it a heavyweight object (~200+ bytes managed + native allocation) that
 * is expensive for GC to finalize.
 */
fun RenderCommand.fillComposePath(target: Path) {
    target.reset()
    val pts = points
    if (pts.isEmpty()) return

    target.moveTo(pts[0].toFloat(), pts[1].toFloat())
    var i = 2
    while (i < pts.size) {
        target.lineTo(pts[i].toFloat(), pts[i + 1].toFloat())
        i += 2
    }
    target.close()
}
