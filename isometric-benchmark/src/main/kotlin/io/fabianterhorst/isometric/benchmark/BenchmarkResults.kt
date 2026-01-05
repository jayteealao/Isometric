package io.fabianterhorst.isometric.benchmark

data class BenchmarkResults(
    val config: BenchmarkConfig,
    val frameCount: Int,

    // Vsync timing (frame intervals)
    val avgVsyncMs: Double,
    val p50VsyncMs: Double,
    val p95VsyncMs: Double,
    val p99VsyncMs: Double,
    val minVsyncMs: Double,
    val maxVsyncMs: Double,

    // Prepare timing (transformation + sorting)
    val avgPrepareMs: Double,
    val p50PrepareMs: Double,
    val p95PrepareMs: Double,
    val p99PrepareMs: Double,
    val minPrepareMs: Double,
    val maxPrepareMs: Double,

    // Draw timing (rendering)
    val avgDrawMs: Double,
    val p50DrawMs: Double,
    val p95DrawMs: Double,
    val p99DrawMs: Double,
    val minDrawMs: Double,
    val maxDrawMs: Double,

    // Aggregation metadata
    val stdDevVsyncMs: Double = 0.0,     // Standard deviation across runs
    val stdDevPrepareMs: Double = 0.0,
    val stdDevDrawMs: Double = 0.0,
    val numberOfRuns: Int = 1,

    // Resource metrics
    val allocatedMB: Double = 0.0,       // Heap allocated in MB
    val gcInvocations: Int = 0,          // Number of GC invocations
    val cacheHits: Long = 0,             // PreparedScene cache hits
    val cacheMisses: Long = 0,           // PreparedScene cache misses
    val drawCalls: Long = 0              // Total draw calls
) {
    fun toCsv(): String {
        val cacheHitRate = if (cacheHits + cacheMisses > 0) {
            (cacheHits.toDouble() / (cacheHits + cacheMisses) * 100)
        } else 0.0

        return "${config.name},${config.sceneSize},${config.scenario}," +
               "${config.interactionPattern}," +
               // Vsync timing
               "$avgVsyncMs,$p50VsyncMs,$p95VsyncMs,$p99VsyncMs,$minVsyncMs,$maxVsyncMs," +
               // Prepare timing
               "$avgPrepareMs,$p50PrepareMs,$p95PrepareMs,$p99PrepareMs,$minPrepareMs,$maxPrepareMs," +
               // Draw timing
               "$avgDrawMs,$p50DrawMs,$p95DrawMs,$p99DrawMs,$minDrawMs,$maxDrawMs," +
               // Metadata
               "$stdDevVsyncMs,$stdDevPrepareMs,$stdDevDrawMs,$numberOfRuns," +
               // Resources
               "$allocatedMB,$gcInvocations," +
               "$cacheHits,$cacheMisses,${"%.2f".format(cacheHitRate)},$drawCalls"
    }

    companion object {
        fun csvHeader(): String {
            return "name,sceneSize,scenario,interaction," +
                   // Vsync timing
                   "avgVsyncMs,p50VsyncMs,p95VsyncMs,p99VsyncMs,minVsyncMs,maxVsyncMs," +
                   // Prepare timing
                   "avgPrepareMs,p50PrepareMs,p95PrepareMs,p99PrepareMs,minPrepareMs,maxPrepareMs," +
                   // Draw timing
                   "avgDrawMs,p50DrawMs,p95DrawMs,p99DrawMs,minDrawMs,maxDrawMs," +
                   // Metadata
                   "stdDevVsyncMs,stdDevPrepareMs,stdDevDrawMs,numberOfRuns," +
                   // Resources
                   "allocatedMB,gcInvocations," +
                   "cacheHits,cacheMisses,cacheHitRate%,drawCalls"
        }
    }
}
