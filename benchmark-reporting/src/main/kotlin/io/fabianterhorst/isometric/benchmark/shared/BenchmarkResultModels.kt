package io.fabianterhorst.isometric.benchmark.shared

import kotlinx.serialization.Serializable

/**
 * Main container for all benchmark results from a single test run.
 */
@Serializable
data class BenchmarkReport(
    val timestamp: Long,
    val device: DeviceInfo,
    val microbenchmarks: List<MicrobenchmarkResult> = emptyList(),
    val macrobenchmarks: List<MacrobenchmarkResult> = emptyList(),
    val customBenchmarks: List<CustomBenchmarkResult> = emptyList()
)

/**
 * Information about the device where benchmarks were executed.
 */
@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: Int,
    val cpuAbi: String
)

/**
 * Results from a single microbenchmark run.
 * Microbenchmarks measure isolated operation performance.
 */
@Serializable
data class MicrobenchmarkResult(
    val name: String,
    val objectCount: Int,
    val optimizationFlags: Map<String, Boolean>,
    val medianNanos: Long,
    val minNanos: Long,
    val maxNanos: Long,
    val allocationCount: Long? = null
)

/**
 * Results from a single macrobenchmark run.
 * Macrobenchmarks measure end-to-end frame rendering performance.
 */
@Serializable
data class MacrobenchmarkResult(
    val name: String,
    val objectCount: Int,
    val mutationType: String,
    val inputPattern: String,
    val optimizationFlags: Map<String, Boolean>,
    val frameTiming: FrameTimingMetrics,
    val jankMetrics: JankMetrics? = null
)

/**
 * Frame timing statistics from a benchmark run.
 */
@Serializable
data class FrameTimingMetrics(
    val medianMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val minMs: Double,
    val maxMs: Double
)

/**
 * Jank detection metrics for frame rendering.
 */
@Serializable
data class JankMetrics(
    val jankCount: Int,
    val jankRate: Double
)

/**
 * Results from custom benchmark runs that measure detailed rendering pipeline metrics.
 */
@Serializable
data class CustomBenchmarkResult(
    val name: String,
    val vsyncTiming: TimingStats,
    val prepareTiming: TimingStats,
    val drawTiming: TimingStats,
    val cacheHitRate: Double
)

/**
 * Statistical timing measurements for a specific operation.
 */
@Serializable
data class TimingStats(
    val avgMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val stdDevMs: Double
)
