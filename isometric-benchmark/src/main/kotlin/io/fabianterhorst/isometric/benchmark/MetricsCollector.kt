package io.fabianterhorst.isometric.benchmark

class MetricsCollector {
    // Pre-allocated arrays for zero allocation during measurement
    private val frameTimes = LongArray(500)
    private val frameTimesMs = DoubleArray(500)
    private var frameCount = 0

    fun recordFrameDuration(durationNanos: Long) {
        require(durationNanos >= 0) {
            "Negative frame duration: ${durationNanos}ns"
        }
        require(frameCount < frameTimes.size) {
            "Frame buffer overflow! Attempted to record frame ${frameCount + 1} but buffer size is ${frameTimes.size}"
        }
        frameTimes[frameCount] = durationNanos
        frameCount++
    }

    fun reset() {
        frameCount = 0
    }

    fun computeResults(config: BenchmarkConfig): BenchmarkResults {
        require(frameCount > 0) {
            "Cannot compute results with zero frames. Did the benchmark run properly?"
        }

        // Convert in-place (zero allocation)
        for (i in 0 until frameCount) {
            frameTimesMs[i] = frameTimes[i] / 1_000_000.0
        }

        // Sort in-place
        frameTimesMs.sort(0, frameCount)

        // Calculate average
        var sum = 0.0
        for (i in 0 until frameCount) {
            sum += frameTimesMs[i]
        }
        val avg = sum / frameCount

        return BenchmarkResults(
            config = config,
            frameCount = frameCount,
            avgFrameTime = avg,
            p50FrameTime = calculatePercentile(0.50),
            p95FrameTime = calculatePercentile(0.95),
            p99FrameTime = calculatePercentile(0.99),
            minFrameTime = frameTimesMs[0],
            maxFrameTime = frameTimesMs[frameCount - 1]
        )
    }

    private fun calculatePercentile(percentile: Double): Double {
        if (frameCount == 1) return frameTimesMs[0]

        val rank = percentile * (frameCount - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(frameCount - 1)
        val fraction = rank - lowerIndex

        return frameTimesMs[lowerIndex] + fraction * (frameTimesMs[upperIndex] - frameTimesMs[lowerIndex])
    }
}
