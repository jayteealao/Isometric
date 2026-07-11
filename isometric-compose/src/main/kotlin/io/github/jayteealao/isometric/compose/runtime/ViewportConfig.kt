package io.github.jayteealao.isometric.compose.runtime

/**
 * Controls how [IsometricScene] positions and scales the scene content within its viewport.
 *
 * Progressive disclosure:
 *
 * - **Simple**: leave [SceneConfig.viewport] as `null` to use the engine's defaults
 *   (`originXFraction = 0.5`, `originYFraction = 0.9`), which reproduces the historic
 *   isometric.js look — scene anchored 90% down the viewport, horizontally centred.
 * - **Convenience**: pass [ViewportConfig.fitContent] to have the engine enlarge or
 *   shrink the scene so it fills the available viewport with optional padding.
 * - **Low-level**: use [IsometricEngine.originXFraction], [IsometricEngine.originYFraction],
 *   and [IsometricEngine.scale] directly on the engine exposed by [AdvancedSceneConfig].
 *
 * @see SceneConfig.viewport
 */
data class ViewportConfig(
    /**
     * When `true`, the engine computes scale and origin each frame so the scene content
     * fills the viewport — both enlarging undersized content and shrinking overflowing
     * content while preserving the scene's aspect ratio.
     */
    val fitContent: Boolean = false,
    /**
     * Uniform inset in pixels applied to all four sides before fitting.
     * Only used when [fitContent] is `true`.
     */
    val padding: Double = 0.0
) {
    companion object {
        /** Fit scene to the full viewport with no padding. */
        val FitContent: ViewportConfig = ViewportConfig(fitContent = true)
    }
}
