package io.fabianterhorst.isometric.benchmark

data class BenchmarkResults(
    val config: BenchmarkConfig,
    val frameCount: Int,
    val avgFrameTime: Double,
    val p50FrameTime: Double,
    val p95FrameTime: Double,
    val p99FrameTime: Double,
    val minFrameTime: Double,
    val maxFrameTime: Double,
    val stdDevFrameTime: Double = 0.0,  // Standard deviation of average frame times across runs
    val numberOfRuns: Int = 1            // Number of runs completed
) {
    fun toCsv(): String {
        return "${config.name},${config.sceneSize},${config.scenario}," +
               "${config.interactionPattern},$avgFrameTime,$p50FrameTime," +
               "$p95FrameTime,$p99FrameTime,$minFrameTime,$maxFrameTime," +
               "$stdDevFrameTime,$numberOfRuns"
    }

    companion object {
        fun csvHeader(): String {
            return "name,sceneSize,scenario,interaction,avgFrameMs," +
                   "p50FrameMs,p95FrameMs,p99FrameMs,minFrameMs,maxFrameMs," +
                   "stdDevFrameMs,numberOfRuns"
        }
    }
}
