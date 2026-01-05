package io.fabianterhorst.isometric

import androidx.compose.runtime.Stable

/**
 * Configuration options for rendering
 *
 * @property enableDepthSorting Enable complex intersection-based depth sorting (slower but correct)
 * @property enableBackfaceCulling Remove back-facing polygons (improves performance)
 * @property enableBoundsChecking Remove polygons outside viewport bounds (improves performance)
 * @property enablePreparedSceneCache Cache prepared scenes to avoid redundant transformation (improves performance for static scenes)
 * @property enableDrawWithCache Cache Compose Path objects to eliminate redundant allocations (improves performance)
 * @property enableBroadPhaseSort Z-order pre-sorting optimization for depth sorting (improves performance)
 * @property enableSpatialIndex Spatial index for accelerated hit testing (improves performance)
 */
@Stable
data class RenderOptions(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val enablePreparedSceneCache: Boolean = true,
    val enableDrawWithCache: Boolean = false,
    val enableBroadPhaseSort: Boolean = false,
    val enableSpatialIndex: Boolean = false
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
            enablePreparedSceneCache = true,
            enableDrawWithCache = true,
            enableBroadPhaseSort = true,
            enableSpatialIndex = true
        )

        /**
         * Quality mode: all features enabled, prioritize correctness over speed
         */
        val Quality = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = false,  // Show all faces
            enableBoundsChecking = false,    // Render everything
            enablePreparedSceneCache = true,
            enableBroadPhaseSort = false,
            enableSpatialIndex = false
        )
    }
}
