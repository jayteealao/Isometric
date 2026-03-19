package io.github.jayteealao.isometric.benchmark

import android.util.Log
import io.github.jayteealao.isometric.compose.runtime.IsometricNode
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
 * - Injects hit-test taps via [InteractionSimulator] calling [renderer.hitTest] directly
 * - Records vsync frame times via [FramePacer]
 * - Advances [MetricsCollector]
 *
 * @param config Benchmark configuration
 * @param collector Metrics collector for recording timings
 */
class BenchmarkOrchestrator(
    private val config: BenchmarkConfig,
    private val collector: MetricsCollector,
    private val getDrawPassCount: () -> Long
) {
    companion object {
        private const val TAG = "IsoBenchmark"
        private const val STABILITY_CV_THRESHOLD = 0.10  // 10%
        private const val STABILITY_WINDOW = 20
    }

    /**
     * Hit-test function provided by BenchmarkScreen via IsometricScene's onHitTestReady.
     * Set before the measurement loop starts. Calls renderer.hitTest() directly,
     * bypassing Compose pointer input overhead.
     */
    var hitTestFn: ((x: Double, y: Double) -> IsometricNode?)? = null

    /** Actual viewport dimensions from IsometricScene (updated via onFlagsReady) */
    var viewportWidth: Int = 1920
    var viewportHeight: Int = 1080

    /** Per-iteration raw timings captured before collector.reset() */
    val iterationRawTimings = mutableListOf<RawTimings>()

    /**
     * Run the full benchmark lifecycle.
     *
     * Must be called from the main thread (uses Choreographer).
     *
     * @param items Current scene items (read-only for interaction simulation)
     * @param onMutate Callback to apply mutations to the scene's SnapshotStateList
     * @param onResetScene Callback to restore the scene to its deterministic initial state
     * @param onComplete Called with per-iteration metrics when all iterations complete
     */
    suspend fun run(
        items: List<GeneratedItem>,
        onMutate: (List<MutationResult>) -> Unit,
        onResetScene: () -> Unit,
        onFrame: () -> Unit,
        onComplete: (List<FrameMetrics>) -> Unit
    ) {
        val framePacer = FramePacer()
        val iterationResults = mutableListOf<FrameMetrics>()
        iterationRawTimings.clear()

        Log.i(TAG, "=== Benchmark: ${config.name} ===")
        Log.i(TAG, "Scene: N=${config.scenario.sceneSize}, mutation=${config.scenario.mutationRate}, " +
                "interaction=${config.scenario.interactionPattern}")
        Log.i(TAG, "Config: ${config.iterations} iterations × ${config.measurementFrames} frames")

        // Phase 1: Warmup
        val warmupFrames = runWarmup(framePacer, items, onMutate, onFrame)
        Log.i(TAG, "Warmup complete: $warmupFrames frames")

        // Phase 2-3: Measurement iterations
        for (iteration in 1..config.iterations) {
            // Cooldown between iterations
            if (iteration > 1) {
                Log.i(TAG, "Cooldown: ${config.cooldownSeconds}s")
                @Suppress("BlockingMethodInNonBlockingContext")
                System.gc()
                delay(config.cooldownSeconds * 1000L)
            }

            // Reset scene to deterministic initial state before each iteration
            // so iterations are statistically independent
            onResetScene()

            Log.i(TAG, "Iteration $iteration/${config.iterations}: measuring ${config.measurementFrames} frames")
            collector.reset()
            collector.warmupFrames = warmupFrames

            runMeasurement(framePacer, items, onMutate, onFrame)

            val metrics = collector.snapshot(sceneSize = config.scenario.sceneSize)
            iterationRawTimings.add(collector.rawTimings())
            iterationResults.add(metrics)

            Log.i(TAG, "  prepare: ${formatMs(metrics.prepareTimeMs.mean)}ms (p95=${formatMs(metrics.prepareTimeMs.p95)}ms)")
            Log.i(TAG, "  draw: ${formatMs(metrics.drawTimeMs.mean)}ms (p95=${formatMs(metrics.drawTimeMs.p95)}ms)")
            Log.i(TAG, "  frame: ${formatMs(metrics.frameTimeMs.mean)}ms (p95=${formatMs(metrics.frameTimeMs.p95)}ms)")
            Log.i(TAG, "  hitTest: ${formatMs(metrics.hitTestTimeMs.mean)}ms (p95=${formatMs(metrics.hitTestTimeMs.p95)}ms)")
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
        onMutate: (List<MutationResult>) -> Unit,
        onFrame: () -> Unit
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

            val drawCountBeforeFrame = getDrawPassCount()
            framePacer.awaitNextFrame { frameTimeNanos ->
                val elapsed = frameTimeNanos - lastFrameNanos
                lastFrameNanos = frameTimeNanos

                // Apply mutations during warmup too
                applyFrameWork(frameCount, items, onMutate)
                onFrame()

                warmupCollector.recordFrameTime(elapsed)
            }

            awaitNextDraw(framePacer, drawCountBeforeFrame)
            warmupCollector.advanceFrame()

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
        onMutate: (List<MutationResult>) -> Unit,
        onFrame: () -> Unit
    ) {
        var lastFrameNanos = System.nanoTime()

        for (frame in 0 until config.measurementFrames) {
            val drawCountBeforeFrame = getDrawPassCount()
            framePacer.awaitNextFrame { frameTimeNanos ->
                val elapsed = frameTimeNanos - lastFrameNanos
                lastFrameNanos = frameTimeNanos

                collector.recordFrameTime(elapsed)

                // Apply per-frame work (mutations + hit testing)
                applyFrameWork(frame, items, onMutate)
                onFrame()
            }

            awaitNextDraw(framePacer, drawCountBeforeFrame)
            collector.advanceFrame()
        }
    }

    /**
     * Wait until a draw pass completes after the current frame's work invalidated the scene.
     * This keeps measurement aligned to actual rendered frames instead of only Choreographer callbacks.
     */
    private suspend fun awaitNextDraw(framePacer: FramePacer, previousDrawPassCount: Long) {
        while (getDrawPassCount() <= previousDrawPassCount) {
            framePacer.awaitNextFrame { }
        }
    }

    /**
     * Per-frame work: mutations and interaction simulation with real hit testing.
     */
    private fun applyFrameWork(
        frameIndex: Int,
        items: List<GeneratedItem>,
        onMutate: (List<MutationResult>) -> Unit
    ) {
        // Apply mutations and record count for validation
        val mutations = MutationSimulator.mutate(
            items = items,
            mutationRate = config.scenario.mutationRate,
            frameIndex = frameIndex
        )
        collector.recordMutationCount(mutations.size)
        if (mutations.isNotEmpty()) {
            onMutate(mutations)
        }

        // Interaction simulation — call renderer.hitTest() directly
        val interaction = InteractionSimulator.nextTap(
            frameIndex = frameIndex,
            pattern = config.scenario.interactionPattern,
            items = items,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight
        )

        if (interaction != null) {
            val fn = hitTestFn
            if (fn != null) {
                val hitTestStart = System.nanoTime()
                val hitNode = fn(interaction.tapX, interaction.tapY)
                val hitTestEnd = System.nanoTime()
                collector.recordHitTestTime(hitTestEnd - hitTestStart)

                // Lightweight validation: if we expected a hit, log misses
                if (interaction.expectedHit && hitNode == null) {
                    Log.d(TAG, "Hit-test miss at (${interaction.tapX}, ${interaction.tapY}) frame $frameIndex")
                }
            }
        }
    }

    private fun formatMs(value: Double): String = "%.2f".format(value)
    private fun formatPercent(value: Double): String = "%.1f%%".format(value * 100)
}
