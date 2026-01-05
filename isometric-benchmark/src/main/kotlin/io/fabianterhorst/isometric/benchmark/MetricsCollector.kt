package io.fabianterhorst.isometric.benchmark

import android.os.Debug

class MetricsCollector {
    // Pre-allocated arrays for three timing metrics
    private val vsyncTimes = LongArray(500)
    private val prepareTimes = LongArray(500)
    private val drawTimes = LongArray(500)
    private var frameCount = 0

    // Working arrays for percentile calculations
    private val vsyncTimesMs = DoubleArray(500)
    private val prepareTimesMs = DoubleArray(500)
    private val drawTimesMs = DoubleArray(500)

    // Memory and GC tracking
    private var startHeapBytes: Long = 0
    private var endHeapBytes: Long = 0
    private var startGcCount: String = "0"
    private var endGcCount: String = "0"
    private val runtime = Runtime.getRuntime()

    fun recordFrameMetrics(vsyncNanos: Long, prepareNanos: Long, drawNanos: Long) {
        require(frameCount < vsyncTimes.size) {
            "Frame buffer overflow! Attempted to record frame ${frameCount + 1} but buffer size is ${vsyncTimes.size}"
        }
        vsyncTimes[frameCount] = vsyncNanos
        prepareTimes[frameCount] = prepareNanos
        drawTimes[frameCount] = drawNanos
        frameCount++
    }

    fun reset() {
        frameCount = 0
    }

    fun startMemoryTracking() {
        // Force GC before measurement for more accurate baseline
        System.gc()
        Thread.sleep(100)  // Let GC complete

        startHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        startGcCount = Debug.getRuntimeStat("art.gc.gc-count") ?: "0"
    }

    fun endMemoryTracking() {
        endHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        endGcCount = Debug.getRuntimeStat("art.gc.gc-count") ?: "0"
    }

    private fun parseGcCount(countStr: String): Long {
        return try {
            countStr.toLong()
        } catch (e: NumberFormatException) {
            0L
        }
    }

    fun computeResults(config: BenchmarkConfig): BenchmarkResults {
        require(frameCount > 0) {
            "Cannot compute results with zero frames. Did the benchmark run properly?"
        }

        // Convert all three metrics to milliseconds
        for (i in 0 until frameCount) {
            vsyncTimesMs[i] = vsyncTimes[i] / 1_000_000.0
            prepareTimesMs[i] = prepareTimes[i] / 1_000_000.0
            drawTimesMs[i] = drawTimes[i] / 1_000_000.0
        }

        // Sort all three arrays for percentile calculations
        vsyncTimesMs.sort(0, frameCount)
        prepareTimesMs.sort(0, frameCount)
        drawTimesMs.sort(0, frameCount)

        // Calculate averages
        val avgVsync = vsyncTimesMs.average(0, frameCount)
        val avgPrepare = prepareTimesMs.average(0, frameCount)
        val avgDraw = drawTimesMs.average(0, frameCount)

        // Calculate memory metrics
        val allocatedBytes = endHeapBytes - startHeapBytes
        val allocatedMB = allocatedBytes / (1024.0 * 1024.0)
        val gcInvocations = parseGcCount(endGcCount) - parseGcCount(startGcCount)

        return BenchmarkResults(
            config = config,
            frameCount = frameCount,
            avgVsyncMs = avgVsync,
            avgPrepareMs = avgPrepare,
            avgDrawMs = avgDraw,
            p50VsyncMs = calculatePercentile(vsyncTimesMs, 0.50),
            p50PrepareMs = calculatePercentile(prepareTimesMs, 0.50),
            p50DrawMs = calculatePercentile(drawTimesMs, 0.50),
            p95VsyncMs = calculatePercentile(vsyncTimesMs, 0.95),
            p95PrepareMs = calculatePercentile(prepareTimesMs, 0.95),
            p95DrawMs = calculatePercentile(drawTimesMs, 0.95),
            p99VsyncMs = calculatePercentile(vsyncTimesMs, 0.99),
            p99PrepareMs = calculatePercentile(prepareTimesMs, 0.99),
            p99DrawMs = calculatePercentile(drawTimesMs, 0.99),
            minVsyncMs = vsyncTimesMs[0],
            minPrepareMs = prepareTimesMs[0],
            minDrawMs = drawTimesMs[0],
            maxVsyncMs = vsyncTimesMs[frameCount - 1],
            maxPrepareMs = prepareTimesMs[frameCount - 1],
            maxDrawMs = drawTimesMs[frameCount - 1],
            allocatedMB = allocatedMB,
            gcInvocations = gcInvocations.toInt()
        )
    }

    private fun DoubleArray.average(fromIndex: Int, toIndex: Int): Double {
        var sum = 0.0
        for (i in fromIndex until toIndex) {
            sum += this[i]
        }
        return sum / (toIndex - fromIndex)
    }

    private fun calculatePercentile(sortedArray: DoubleArray, percentile: Double): Double {
        if (frameCount == 1) return sortedArray[0]

        val rank = percentile * (frameCount - 1)
        val lowerIndex = rank.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(frameCount - 1)
        val fraction = rank - lowerIndex

        return sortedArray[lowerIndex] + fraction * (sortedArray[upperIndex] - sortedArray[lowerIndex])
    }
}
