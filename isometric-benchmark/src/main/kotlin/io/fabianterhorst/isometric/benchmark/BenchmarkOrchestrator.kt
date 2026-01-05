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

    // Reference to the IsometricEngine for cache stats (set externally)
    var engine: io.fabianterhorst.isometric.IsometricEngine? = null

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
        Log.d(TAG, "Benchmark complete: vsync=${aggregatedResults.avgVsyncMs}ms ± ${aggregatedResults.stdDevVsyncMs}ms, prepare=${aggregatedResults.avgPrepareMs}ms, draw=${aggregatedResults.avgDrawMs}ms (${config.numberOfRuns} runs)")
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

        // Reset cache and draw call stats
        engine?.resetCacheStats()
        io.fabianterhorst.isometric.compose.ComposeRenderer.resetDrawCallCount()

        // Start memory tracking
        metrics.startMemoryTracking()

        // Run measurement
        runFrames(count = 500, measure = true)

        // End memory tracking
        metrics.endMemoryTracking()

        // Capture cache and draw call stats
        val cacheHits = engine?.cacheHits ?: 0
        val cacheMisses = engine?.cacheMisses ?: 0
        val drawCalls = io.fabianterhorst.isometric.compose.ComposeRenderer.drawCallCount

        Log.d(TAG, "Cache stats: $cacheHits hits, $cacheMisses misses")
        Log.d(TAG, "Draw calls: $drawCalls")

        // Return results from this single run
        return metrics.computeResults(config).copy(
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            drawCalls = drawCalls
        )
    }

    private fun aggregateResults(results: List<BenchmarkResults>): BenchmarkResults {
        require(results.isNotEmpty()) { "Cannot aggregate zero results" }

        if (results.size == 1) {
            // Single run - no aggregation needed
            return results[0].copy(numberOfRuns = 1)
        }

        // Vsync timing aggregation
        val avgVsyncTimes = results.map { it.avgVsyncMs }
        val meanAvgVsync = avgVsyncTimes.average()
        val varianceVsync = avgVsyncTimes.map { (it - meanAvgVsync) * (it - meanAvgVsync) }.average()
        val stdDevVsync = kotlin.math.sqrt(varianceVsync)

        // Prepare timing aggregation
        val avgPrepareTimes = results.map { it.avgPrepareMs }
        val meanAvgPrepare = avgPrepareTimes.average()
        val variancePrepare = avgPrepareTimes.map { (it - meanAvgPrepare) * (it - meanAvgPrepare) }.average()
        val stdDevPrepare = kotlin.math.sqrt(variancePrepare)

        // Draw timing aggregation
        val avgDrawTimes = results.map { it.avgDrawMs }
        val meanAvgDraw = avgDrawTimes.average()
        val varianceDraw = avgDrawTimes.map { (it - meanAvgDraw) * (it - meanAvgDraw) }.average()
        val stdDevDraw = kotlin.math.sqrt(varianceDraw)

        // Average memory and cache metrics
        val meanAllocatedMB = results.map { it.allocatedMB }.average()
        val meanGcInvocations = results.map { it.gcInvocations }.average().toInt()
        val totalCacheHits = results.sumOf { it.cacheHits }
        val totalCacheMisses = results.sumOf { it.cacheMisses }
        val meanDrawCalls = results.map { it.drawCalls }.average().toLong()

        return BenchmarkResults(
            config = config,
            frameCount = results[0].frameCount,
            // Vsync timing
            avgVsyncMs = meanAvgVsync,
            p50VsyncMs = results.map { it.p50VsyncMs }.average(),
            p95VsyncMs = results.map { it.p95VsyncMs }.average(),
            p99VsyncMs = results.map { it.p99VsyncMs }.average(),
            minVsyncMs = results.minOf { it.minVsyncMs },
            maxVsyncMs = results.maxOf { it.maxVsyncMs },
            // Prepare timing
            avgPrepareMs = meanAvgPrepare,
            p50PrepareMs = results.map { it.p50PrepareMs }.average(),
            p95PrepareMs = results.map { it.p95PrepareMs }.average(),
            p99PrepareMs = results.map { it.p99PrepareMs }.average(),
            minPrepareMs = results.minOf { it.minPrepareMs },
            maxPrepareMs = results.maxOf { it.maxPrepareMs },
            // Draw timing
            avgDrawMs = meanAvgDraw,
            p50DrawMs = results.map { it.p50DrawMs }.average(),
            p95DrawMs = results.map { it.p95DrawMs }.average(),
            p99DrawMs = results.map { it.p99DrawMs }.average(),
            minDrawMs = results.minOf { it.minDrawMs },
            maxDrawMs = results.maxOf { it.maxDrawMs },
            // Metadata
            stdDevVsyncMs = stdDevVsync,
            stdDevPrepareMs = stdDevPrepare,
            stdDevDrawMs = stdDevDraw,
            numberOfRuns = results.size,
            // Resources
            allocatedMB = meanAllocatedMB,
            gcInvocations = meanGcInvocations,
            cacheHits = totalCacheHits,
            cacheMisses = totalCacheMisses,
            drawCalls = meanDrawCalls
        )
    }

    private suspend fun runFrames(count: Int, measure: Boolean) {
        var previousFrameTime = 0L

        repeat(count) { frameIndex ->
            framePacer.awaitNextFrame { frameTimeNanos ->
                // Trigger recomposition for rendering
                _frameTickFlow.value = frameIndex

                // Capture all timing metrics after rendering completes
                if (measure && frameIndex > 0) {
                    // 1. Vsync interval (time between frames)
                    val vsyncInterval = frameTimeNanos - previousFrameTime

                    // 2. Prepare time (transformation + sorting)
                    val prepareTime = engine?.lastPrepareTimeNanos ?: 0L

                    // 3. Draw time (rendering)
                    val drawTime = io.fabianterhorst.isometric.compose.ComposeRenderer.lastDrawTimeNanos

                    // Record all three metrics
                    metrics.recordFrameMetrics(vsyncInterval, prepareTime, drawTime)
                }

                previousFrameTime = frameTimeNanos
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
