package io.github.jayteealao.isometric.benchmark

import io.github.jayteealao.isometric.compose.runtime.RenderMode

/**
 * Render mode identifiers for benchmark configuration.
 *
 * These string constants are used in JSON configs and CSV output.
 * They map to [RenderMode] sealed class instances at runtime.
 */
object RenderModeId {
    const val CANVAS_CPU = "canvas_cpu"
    const val CANVAS_WEBGPU = "canvas_webgpu"
    const val WEBGPU = "webgpu"

    /** All valid render mode identifiers. */
    val ALL = listOf(CANVAS_CPU, CANVAS_WEBGPU, WEBGPU)

    fun toRenderMode(id: String): RenderMode = when (id) {
        CANVAS_CPU -> RenderMode.Canvas()
        CANVAS_WEBGPU -> RenderMode.Canvas(compute = RenderMode.Canvas.Compute.WebGpu)
        WEBGPU -> RenderMode.WebGpu()
        else -> throw IllegalArgumentException("Unknown renderMode: $id. Valid: $ALL")
    }

    fun fromRenderMode(mode: RenderMode): String = when (mode) {
        is RenderMode.Canvas -> when (mode.compute) {
            RenderMode.Canvas.Compute.Cpu -> CANVAS_CPU
            RenderMode.Canvas.Compute.WebGpu -> CANVAS_WEBGPU
        }
        is RenderMode.WebGpu -> WEBGPU
    }
}

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
 * @property renderModeId Render mode identifier — one of [RenderModeId.CANVAS_CPU],
 *   [RenderModeId.CANVAS_WEBGPU], or [RenderModeId.WEBGPU].
 */
data class BenchmarkFlags(
    val enablePathCaching: Boolean = false,
    val enableSpatialIndex: Boolean = false,
    val enablePreparedSceneCache: Boolean = false,
    val enableNativeCanvas: Boolean = false,
    val enableBroadPhaseSort: Boolean = false,
    val renderModeId: String = RenderModeId.CANVAS_CPU,
) {
    /** Resolved [RenderMode] instance for this configuration. */
    val renderMode: RenderMode get() = RenderModeId.toRenderMode(renderModeId)

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
            "enableBroadPhaseSort",
            "renderMode"
        )
    }

    /** Returns flag values as a list matching [FLAG_NAMES] order */
    fun toValueList(): List<String> = listOf(
        enablePathCaching.toString(),
        enableSpatialIndex.toString(),
        enablePreparedSceneCache.toString(),
        enableNativeCanvas.toString(),
        enableBroadPhaseSort.toString(),
        renderModeId
    )
}
