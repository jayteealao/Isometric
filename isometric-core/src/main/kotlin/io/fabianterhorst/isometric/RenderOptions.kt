package io.fabianterhorst.isometric

/**
 * Configuration options for rendering
 *
 * @property enableDepthSorting Enable complex intersection-based depth sorting (slower but correct)
 * @property enableBackfaceCulling Remove back-facing polygons (improves performance)
 * @property enableBoundsChecking Remove polygons outside viewport bounds (improves performance)
 * @property enablePreparedSceneCache Cache prepared scenes to avoid redundant transformation (improves performance for static scenes)
 */
data class RenderOptions(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val enablePreparedSceneCache: Boolean = true
) {
    companion object {
        /**
         * Default options: all optimizations enabled
         */
        val Default = RenderOptions()

        /**
         * Performance mode: disable depth sorting for speed
         * Use when shapes don't overlap or depth order doesn't matter
         */
        val Performance = RenderOptions(
            enableDepthSorting = false,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enablePreparedSceneCache = true
        )

        /**
         * Quality mode: all features enabled, prioritize correctness over speed
         */
        val Quality = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = false,  // Show all faces
            enableBoundsChecking = false,    // Render everything
            enablePreparedSceneCache = true
        )
    }
}
