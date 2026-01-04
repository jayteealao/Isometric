package io.fabianterhorst.isometric.benchmark

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BenchmarkOrchestrator(
    private val activity: Activity,
    val config: BenchmarkConfig,
    private val onComplete: (BenchmarkResults) -> Unit
) {
    private val metrics = MetricsCollector()
    private val framePacer = FramePacer()
    var phase = Phase.IDLE
        private set

    enum class Phase {
        IDLE,
        GC_AND_WARMUP,
        COOLDOWN,
        MEASUREMENT,
        COMPLETE
    }

    private val _frameTickFlow = MutableStateFlow(0)
    val frameTickFlow: StateFlow<Int> = _frameTickFlow.asStateFlow()

    suspend fun start() {
        Log.d(TAG, "Starting benchmark: ${config.name} (${config.numberOfRuns} runs)")

        // Collect results from multiple runs
        val allResults = mutableListOf<BenchmarkResults>()

        repeat(config.numberOfRuns) { runIndex ->
            Log.d(TAG, "Run ${runIndex + 1}/${config.numberOfRuns}")
            val singleRunResults = runSingleBenchmark()
            allResults.add(singleRunResults)

            // Brief pause between runs (except after last run)
            if (runIndex < config.numberOfRuns - 1) {
                delay(1000)
            }
        }

        // Aggregate results
        phase = Phase.COMPLETE
        val aggregatedResults = aggregateResults(allResults)
        Log.d(TAG, "Benchmark complete: avg=${aggregatedResults.avgFrameTime}ms ± ${aggregatedResults.stdDevFrameTime}ms (${config.numberOfRuns} runs)")
        onComplete(aggregatedResults)
    }

    private suspend fun runSingleBenchmark(): BenchmarkResults {
        // Phase 1: GC and Warmup (500 frames)
        phase = Phase.GC_AND_WARMUP
        forceGarbageCollection()
        delay(2000)
        Log.d(TAG, "Warmup: 500 frames")
        runFrames(count = 500, measure = false)

        // Phase 2: Cooldown
        phase = Phase.COOLDOWN
        Log.d(TAG, "Cooldown: 2 seconds")
        delay(2000)
        forceGarbageCollection()
        delay(1000)

        // Phase 3: Measurement (500 frames)
        phase = Phase.MEASUREMENT
        Log.d(TAG, "Measurement: 500 frames")
        metrics.reset()
        runFrames(count = 500, measure = true)

        // Return results from this single run
        return metrics.computeResults(config)
    }

    private fun aggregateResults(results: List<BenchmarkResults>): BenchmarkResults {
        require(results.isNotEmpty()) { "Cannot aggregate zero results" }

        if (results.size == 1) {
            // Single run - no aggregation needed
            return results[0].copy(numberOfRuns = 1)
        }

        // Calculate mean of each metric across all runs
        val avgFrameTimes = results.map { it.avgFrameTime }
        val meanAvgFrameTime = avgFrameTimes.average()

        // Calculate standard deviation of average frame times
        val variance = avgFrameTimes.map { (it - meanAvgFrameTime) * (it - meanAvgFrameTime) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        // For percentiles, take the mean across all runs
        val meanP50 = results.map { it.p50FrameTime }.average()
        val meanP95 = results.map { it.p95FrameTime }.average()
        val meanP99 = results.map { it.p99FrameTime }.average()

        // For min/max, take the absolute min/max across all runs
        val absoluteMin = results.minOf { it.minFrameTime }
        val absoluteMax = results.maxOf { it.maxFrameTime }

        return BenchmarkResults(
            config = config,
            frameCount = results[0].frameCount,
            avgFrameTime = meanAvgFrameTime,
            p50FrameTime = meanP50,
            p95FrameTime = meanP95,
            p99FrameTime = meanP99,
            minFrameTime = absoluteMin,
            maxFrameTime = absoluteMax,
            stdDevFrameTime = stdDev,
            numberOfRuns = results.size
        )
    }

    private suspend fun runFrames(count: Int, measure: Boolean) {
        var previousFrameTime = 0L

        repeat(count) { frameIndex ->
            framePacer.awaitNextFrame { frameTimeNanos ->
                // Measure time between consecutive frame callbacks
                // This includes composition + layout + draw time
                if (measure && frameIndex > 0) {
                    val frameDuration = frameTimeNanos - previousFrameTime
                    metrics.recordFrameDuration(frameDuration)
                }

                previousFrameTime = frameTimeNanos

                // Trigger recomposition for NEXT frame
                _frameTickFlow.value = frameIndex
            }
        }
    }

    private fun forceGarbageCollection() {
        repeat(3) {
            System.gc()
            System.runFinalization()
        }
    }

    companion object {
        private const val TAG = "BenchmarkOrchestrator"
    }
}
