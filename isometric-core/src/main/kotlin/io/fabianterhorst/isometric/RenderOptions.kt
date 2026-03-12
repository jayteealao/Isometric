package io.fabianterhorst.isometric

/**
 * Configuration options for rendering
 *
 * @property enableDepthSorting Enable complex intersection-based depth sorting (slower but correct)
 * @property enableBackfaceCulling Remove back-facing polygons (improves performance)
 * @property enableBoundsChecking Remove polygons outside viewport bounds (improves performance)
 */
data class RenderOptions(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val enableBroadPhaseSort: Boolean = false,
    val broadPhaseCellSize: Double = DEFAULT_BROAD_PHASE_CELL_SIZE
) {
    init {
        require(broadPhaseCellSize > 0.0) { "broadPhaseCellSize must be > 0" }
    }

    companion object {
        const val DEFAULT_BROAD_PHASE_CELL_SIZE: Double = 100.0

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
            enableBroadPhaseSort = false
        )

        /**
         * Quality mode: all features enabled, prioritize correctness over speed
         */
        val Quality = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = false,  // Show all faces
            enableBoundsChecking = false,    // Render everything
            enableBroadPhaseSort = false
        )
    }
}
