package io.github.jayteealao.isometric.compose.runtime.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.PreparedScene
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.StrokeStyle

/**
 * Pluggable rendering surface for [IsometricScene].
 *
 * Implementations are internal to the rendering pipeline. Users select a rendering
 * strategy via [RenderMode][io.github.jayteealao.isometric.compose.runtime.RenderMode],
 * which is resolved to the appropriate [RenderBackend] implementation internally.
 *
 * ## Ownership contract
 *
 * - [IsometricScene] owns the node tree, prepare lifecycle, and hit testing.
 * - Backends receive an immutable [PreparedScene] snapshot and are responsible only for drawing.
 * - Backends must NEVER access the mutable scene tree.
 * - Hit testing is handled by [IsometricScene] via its existing pointer-input block —
 *   backends do not participate in hit testing.
 */
interface RenderBackend {
    /**
     * Emit the Compose tree for this backend's rendering surface.
     *
     * Called inside [IsometricScene]'s composition. Implementations are responsible for
     * their own surface lifecycle, frame loop, and invalidation strategy.
     *
     * @param preparedScene Immutable scene snapshot, updated on the main thread after each
     *   dirty→prepare cycle. May be `null` before the first prepare completes.
     * @param renderContext The current render context (viewport transform, light direction, etc.).
     * @param modifier Applied to the outermost composable emitted by this backend.
     *   Passed through as-is — backends must NOT append `.fillMaxSize()`.
     * @param strokeStyle Stroke rendering strategy. GPU backends may ignore this in Phase 2
     *   (all faces are filled; stroke is a Canvas-only concept for now).
     */
    @Composable
    fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    )
}
