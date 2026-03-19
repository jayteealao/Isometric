package io.github.jayteealao.isometric

/**
 * Configuration options for rendering
 *
 * @property enableDepthSorting Enable complex intersection-based depth sorting (slower but correct)
 * @property enableBackfaceCulling Remove back-facing polygons (improves performance)
 * @property enableBoundsChecking Remove polygons outside viewport bounds (improves performance)
 * @property enableBroadPhaseSort Enable broad-phase spatial bucketing to prune depth-sort candidate pairs (faster for large scenes)
 * @property broadPhaseCellSize Cell size in pixels for the broad-phase grid. Smaller values increase precision but use more memory.
 */
class RenderOptions @JvmOverloads constructor(
    val enableDepthSorting: Boolean = true,
    val enableBackfaceCulling: Boolean = true,
    val enableBoundsChecking: Boolean = true,
    val enableBroadPhaseSort: Boolean = true,
    val broadPhaseCellSize: Double = DEFAULT_BROAD_PHASE_CELL_SIZE
) {
    init {
        require(broadPhaseCellSize.isFinite() && broadPhaseCellSize > 0.0) {
            "broadPhaseCellSize must be positive and finite, got $broadPhaseCellSize"
        }
    }

    fun copy(
        enableDepthSorting: Boolean = this.enableDepthSorting,
        enableBackfaceCulling: Boolean = this.enableBackfaceCulling,
        enableBoundsChecking: Boolean = this.enableBoundsChecking,
        enableBroadPhaseSort: Boolean = this.enableBroadPhaseSort,
        broadPhaseCellSize: Double = this.broadPhaseCellSize
    ): RenderOptions = RenderOptions(
        enableDepthSorting,
        enableBackfaceCulling,
        enableBoundsChecking,
        enableBroadPhaseSort,
        broadPhaseCellSize
    )

    override fun equals(other: Any?): Boolean =
        other is RenderOptions &&
            enableDepthSorting == other.enableDepthSorting &&
            enableBackfaceCulling == other.enableBackfaceCulling &&
            enableBoundsChecking == other.enableBoundsChecking &&
            enableBroadPhaseSort == other.enableBroadPhaseSort &&
            broadPhaseCellSize == other.broadPhaseCellSize

    override fun hashCode(): Int {
        var result = enableDepthSorting.hashCode()
        result = 31 * result + enableBackfaceCulling.hashCode()
        result = 31 * result + enableBoundsChecking.hashCode()
        result = 31 * result + enableBroadPhaseSort.hashCode()
        result = 31 * result + broadPhaseCellSize.hashCode()
        return result
    }

    override fun toString(): String =
        "RenderOptions(enableDepthSorting=$enableDepthSorting, enableBackfaceCulling=$enableBackfaceCulling, enableBoundsChecking=$enableBoundsChecking, enableBroadPhaseSort=$enableBroadPhaseSort, broadPhaseCellSize=$broadPhaseCellSize)"

    companion object {
        const val DEFAULT_BROAD_PHASE_CELL_SIZE: Double = 100.0

        /**
         * Default options: all optimizations enabled
         */
        @JvmField val Default = RenderOptions()

        /**
         * Disable depth sorting while keeping culling and bounds checking enabled.
         * Use when shapes don't overlap or depth order doesn't matter.
         */
        @JvmField val NoDepthSorting = RenderOptions(
            enableDepthSorting = false,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enableBroadPhaseSort = false
        )

        /**
         * Disable backface culling and viewport bounds checking while keeping depth sorting on.
         */
        @JvmField val NoCulling = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = false,  // Show all faces
            enableBoundsChecking = false,    // Render everything
            enableBroadPhaseSort = true
        )
    }
}
