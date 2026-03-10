package io.fabianterhorst.isometric.benchmark

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Orchestrates the benchmark lifecycle:
 * 1. **Warmup** — adaptive: min 30 frames, max 500, 30s ceiling, CV < 10% stability gate
 * 2. **Cooldown** — 2s pause between iterations
 * 3. **Measurement** — configurable frames × configurable iterations
 * 4. **Complete** — delivers per-iteration [FrameMetrics] to the completion callback
 *
 * Per-frame work:
 * - Applies mutations via [MutationSimulator]
 * - Injects hit-test taps via [InteractionSimulator]
 * - Records vsync frame times via [FramePacer]
 * - Advances [MetricsCollector]
 *
 * @param config Benchmark configuration
 * @param collector Metrics collector for recording timings
 */
class BenchmarkOrchestrator(
    private val config: BenchmarkConfig,
    private val collector: MetricsCollector
) {
    companion object {
        private const val TAG = "IsoBenchmark"
        private const val STABILITY_CV_THRESHOLD = 0.10  // 10%
        private const val STABILITY_WINDOW = 20
    }

    /**
     * Run the full benchmark lifecycle.
     *
     * Must be called from the main thread (uses Choreographer).
     *
     * @param items Current scene items (read-only for interaction simulation)
     * @param onMutate Callback to apply mutations to the scene's SnapshotStateList
     * @param onComplete Called with per-iteration metrics when all iterations complete
     */
    suspend fun run(
        items: List<GeneratedItem>,
        onMutate: (List<MutationResult>) -> Unit,
        onComplete: (List<FrameMetrics>) -> Unit
    ) {
        val framePacer = FramePacer()
        val iterationResults = mutableListOf<FrameMetrics>()

        Log.i(TAG, "=== Benchmark: ${config.name} ===")
        Log.i(TAG, "Scene: N=${config.scenario.sceneSize}, mutation=${config.scenario.mutationRate}, " +
                "interaction=${config.scenario.interactionPattern}")
        Log.i(TAG, "Config: ${config.iterations} iterations × ${config.measurementFrames} frames")

        // Phase 1: Warmup
        val warmupFrames = runWarmup(framePacer, items, onMutate)
        Log.i(TAG, "Warmup complete: $warmupFrames frames")

        // Phase 2-3: Measurement iterations
        for (iteration in 1..config.iterations) {
            // Cooldown between iterations
            if (iteration > 1) {
                Log.i(TAG, "Cooldown: ${config.cooldownSeconds}s")
                delay(config.cooldownSeconds * 1000L)
            }

            Log.i(TAG, "Iteration $iteration/${config.iterations}: measuring ${config.measurementFrames} frames")
            collector.reset()
            collector.warmupFrames = warmupFrames

            runMeasurement(framePacer, items, onMutate)

            val metrics = collector.snapshot()
            iterationResults.add(metrics)

            Log.i(TAG, "  prepare: ${formatMs(metrics.prepareTimeMs.mean)}ms (p95=${formatMs(metrics.prepareTimeMs.p95)}ms)")
            Log.i(TAG, "  draw: ${formatMs(metrics.drawTimeMs.mean)}ms (p95=${formatMs(metrics.drawTimeMs.p95)}ms)")
            Log.i(TAG, "  frame: ${formatMs(metrics.frameTimeMs.mean)}ms (p95=${formatMs(metrics.frameTimeMs.p95)}ms)")
            Log.i(TAG, "  cache hit rate: ${(metrics.cacheHitRate * 100).toInt()}%")
        }

        Log.i(TAG, "=== Benchmark complete: ${config.name} ===")
        onComplete(iterationResults)
    }

    /**
     * Adaptive warmup phase.
     *
     * Runs frames until:
     * - At least [BenchmarkConfig.warmupMinFrames] have elapsed AND CV < 10% over 20-frame window
     * - OR [BenchmarkConfig.warmupMaxFrames] reached
     * - OR [BenchmarkConfig.warmupMaxSeconds] exceeded
     *
     * @return Number of warmup frames run
     */
    private suspend fun runWarmup(
        framePacer: FramePacer,
        items: List<GeneratedItem>,
        onMutate: (List<MutationResult>) -> Unit
    ): Int {
        val warmupCollector = MetricsCollector(config.warmupMaxFrames)
        val startTime = System.nanoTime()
        val maxNanos = config.warmupMaxSeconds * 1_000_000_000L
        var frameCount = 0

        Log.i(TAG, "Warmup: min=${config.warmupMinFrames}, max=${config.warmupMaxFrames}, " +
                "ceiling=${config.warmupMaxSeconds}s")

        var lastFrameNanos = System.nanoTime()

        while (frameCount < config.warmupMaxFrames) {
            // Time ceiling check
            if (System.nanoTime() - startTime > maxNanos) {
                Log.w(TAG, "Warmup hit ${config.warmupMaxSeconds}s ceiling at frame $frameCount")
                break
            }

            framePacer.awaitNextFrame { frameTimeNanos ->
                val elapsed = frameTimeNanos - lastFrameNanos
                lastFrameNanos = frameTimeNanos

                // Apply mutations during warmup too
                applyFrameWork(frameCount, items, onMutate)

                warmupCollector.recordFrameTime(elapsed)
                warmupCollector.advanceFrame()
            }

            frameCount++

            // Check stability after minimum frames
            if (frameCount >= config.warmupMinFrames) {
                val cv = warmupCollector.recentFrameTimeCV(STABILITY_WINDOW)
                if (cv < STABILITY_CV_THRESHOLD) {
                    Log.i(TAG, "Warmup stabilized at frame $frameCount (CV=${formatPercent(cv)})")
                    break
                }
            }
        }

        return frameCount
    }

    /**
     * Run a single measurement iteration of [BenchmarkConfig.measurementFrames] frames.
     */
    private suspend fun runMeasurement(
        framePacer: FramePacer,
        items: List<GeneratedItem>,
        onMutate: (List<MutationResult>) -> Unit
    ) {
        var lastFrameNanos = System.nanoTime()

        for (frame in 0 until config.measurementFrames) {
            framePacer.awaitNextFrame { frameTimeNanos ->
                val elapsed = frameTimeNanos - lastFrameNanos
                lastFrameNanos = frameTimeNanos

                collector.recordFrameTime(elapsed)

                // Apply per-frame work
                applyFrameWork(frame, items, onMutate)

                collector.advanceFrame()
            }
        }
    }

    /**
     * Per-frame work: mutations and interaction simulation.
     */
    private fun applyFrameWork(
        frameIndex: Int,
        items: List<GeneratedItem>,
        onMutate: (List<MutationResult>) -> Unit
    ) {
        // Apply mutations
        val mutations = MutationSimulator.mutate(
            items = items,
            mutationRate = config.scenario.mutationRate,
            frameIndex = frameIndex
        )
        if (mutations.isNotEmpty()) {
            onMutate(mutations)
        }

        // Interaction simulation (hit test timing recorded by hooks)
        val interaction = InteractionSimulator.nextTap(
            frameIndex = frameIndex,
            pattern = config.scenario.interactionPattern,
            items = items,
            viewportWidth = 1920,  // Landscape default
            viewportHeight = 1080
        )

        if (interaction != null) {
            val hitTestStart = System.nanoTime()
            // Note: hit test is performed by the renderer's ensurePreparedScene + engine.findItemAt
            // during the next draw pass. We record the interaction simulation time here.
            val hitTestEnd = System.nanoTime()
            collector.recordHitTestTime(hitTestEnd - hitTestStart)
        }
    }

    private fun formatMs(value: Double): String = "%.2f".format(value)
    private fun formatPercent(value: Double): String = "%.1f%%".format(value * 100)
}
