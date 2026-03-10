package io.fabianterhorst.isometric.benchmark

import android.util.Log

/**
 * Orchestrates the benchmark lifecycle: warmup -> cooldown -> measurement (x N iterations) -> complete.
 *
 * Stub implementation for commit 5 — full implementation in commit 6.
 *
 * @param config Benchmark configuration
 * @param collector Metrics collector for recording timings
 */
class BenchmarkOrchestrator(
    private val config: BenchmarkConfig,
    private val collector: MetricsCollector
) {
    /**
     * Run the full benchmark lifecycle.
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
        Log.i("IsoBenchmark", "Orchestrator stub — full implementation in commit 6")
    }
}
