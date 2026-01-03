package io.fabianterhorst.isometric.benchmark

class MetricsCollector {
    // Pre-allocated arrays for zero allocation during measurement
    private val frameTimes = LongArray(500)
    private var frameCount = 0
    private var currentFrameStart = 0L

    fun onFrameStart(frameTimeNanos: Long) {
        currentFrameStart = frameTimeNanos
    }

    fun onFrameEnd(frameTimeNanos: Long) {
        val duration = frameTimeNanos - currentFrameStart
        if (frameCount < frameTimes.size) {
            frameTimes[frameCount] = duration
            frameCount++
        }
    }

    fun reset() {
        frameCount = 0
    }

    fun computeResults(config: BenchmarkConfig): BenchmarkResults {
        // Convert to milliseconds and sort
        val frameTimesMs = frameTimes.take(frameCount)
            .map { it / 1_000_000.0 }
            .sorted()

        return BenchmarkResults(
            config = config,
            frameCount = frameCount,
            avgFrameTime = frameTimesMs.average(),
            p50FrameTime = frameTimesMs[frameCount / 2],
            p95FrameTime = frameTimesMs[(frameCount * 0.95).toInt()],
            p99FrameTime = frameTimesMs[(frameCount * 0.99).toInt()],
            minFrameTime = frameTimesMs.first(),
            maxFrameTime = frameTimesMs.last()
        )
    }
}
