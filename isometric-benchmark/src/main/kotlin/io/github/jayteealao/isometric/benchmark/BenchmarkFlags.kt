package io.github.jayteealao.isometric.benchmark

/**
 * Per-optimization flags that control which rendering optimizations are active
 * during a benchmark run. Each flag maps to a specific optimization in the
 * isometric rendering pipeline.
 *
 * @property enablePathCaching Path object caching in IsometricRenderer (reduces GC pressure)
 * @property enableSpatialIndex Spatial grid for O(k) hit testing
 * @property enablePreparedSceneCache PreparedScene caching across frames (skips engine.projectScene() when scene is unchanged)
 * @property enableNativeCanvas Android-native canvas rendering (2x faster, Android-only)
 * @property enableBroadPhaseSort Broad-phase depth sorting optimization in IsometricEngine.sortPaths()
 */
data class BenchmarkFlags(
    val enablePathCaching: Boolean = false,
    val enableSpatialIndex: Boolean = false,
    val enablePreparedSceneCache: Boolean = false,
    val enableNativeCanvas: Boolean = false,
    val enableBroadPhaseSort: Boolean = false
) {
    companion object {
        /** All optimizations enabled (production defaults) */
        val ALL_ON = BenchmarkFlags(
            enablePathCaching = true,
            enableSpatialIndex = true,
            enablePreparedSceneCache = true,
            enableNativeCanvas = false,
            enableBroadPhaseSort = false
        )

        /** All optimizations disabled (baseline measurement) */
        val ALL_OFF = BenchmarkFlags(
            enablePathCaching = false,
            enableSpatialIndex = false,
            enablePreparedSceneCache = false,
            enableNativeCanvas = false,
            enableBroadPhaseSort = false
        )

        /** Flag names for CSV header generation */
        val FLAG_NAMES = listOf(
            "enablePathCaching",
            "enableSpatialIndex",
            "enablePreparedSceneCache",
            "enableNativeCanvas",
            "enableBroadPhaseSort"
        )
    }

    /** Returns flag values as a list matching [FLAG_NAMES] order */
    fun toValueList(): List<Boolean> = listOf(
        enablePathCaching,
        enableSpatialIndex,
        enablePreparedSceneCache,
        enableNativeCanvas,
        enableBroadPhaseSort
    )
}
