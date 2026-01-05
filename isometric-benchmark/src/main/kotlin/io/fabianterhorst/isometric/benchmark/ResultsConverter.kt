package io.fabianterhorst.isometric.benchmark

import io.fabianterhorst.isometric.benchmark.shared.*

/**
 * Converts custom benchmark results to the unified reporting format
 */
object ResultsConverter {

    fun toCustomBenchmarkResult(result: BenchmarkResults): CustomBenchmarkResult {
        return CustomBenchmarkResult(
            name = result.config.name,
            vsyncTiming = TimingStats(
                avgMs = result.avgVsyncMs,
                p95Ms = result.p95VsyncMs,
                p99Ms = result.p99VsyncMs,
                stdDevMs = result.stdDevVsyncMs
            ),
            prepareTiming = TimingStats(
                avgMs = result.avgPrepareMs,
                p95Ms = result.p95PrepareMs,
                p99Ms = result.p99PrepareMs,
                stdDevMs = result.stdDevPrepareMs
            ),
            drawTiming = TimingStats(
                avgMs = result.avgDrawMs,
                p95Ms = result.p95DrawMs,
                p99Ms = result.p99DrawMs,
                stdDevMs = result.stdDevDrawMs
            ),
            cacheHitRate = if (result.cacheHits + result.cacheMisses > 0) {
                (result.cacheHits.toDouble() / (result.cacheHits + result.cacheMisses) * 100)
            } else 0.0
        )
    }
}
