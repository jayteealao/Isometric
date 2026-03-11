package io.fabianterhorst.isometric.benchmark

import kotlin.math.sqrt

/**
 * Snapshot of computed metrics for a benchmark iteration.
 */
data class FrameMetrics(
    val prepareTimeMs: StatSummary,
    val drawTimeMs: StatSummary,
    val frameTimeMs: StatSummary,
    val hitTestTimeMs: StatSummary,
    val cacheHits: Long,
    val cacheMisses: Long,
    val cacheHitRate: Double,
    val observedMutationRate: Double,
    val allocatedMB: Double,
    val gcInvocations: Long,
    val frameCount: Int,
    val warmupFrames: Int
)

/**
 * Statistical summary of a metric distribution.
 */
data class StatSummary(
    val mean: Double,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val min: Double,
    val max: Double,
    val stdDev: Double,
    val cv: Double
)

/**
 * Collects per-frame timing metrics for benchmark measurement.
 *
 * Uses pre-allocated [LongArray]s to avoid GC pressure during measurement.
 * All calls are expected on the main thread (documented, not enforced).
 *
 * Two-path API:
 * - Hook path: [recordPrepareTime], [recordDrawTime], [recordCacheHit], [recordCacheMiss]
 *   — called by [BenchmarkHooksImpl] during the draw pass
 * - Orchestrator path: [recordFrameTime], [recordHitTestTime], [advanceFrame]
 *   — called by the orchestrator after each vsync
 *
 * @param maxFrames Maximum number of frames to record (pre-allocated array size)
 */
class MetricsCollector(private val maxFrames: Int) {

    private val prepareTimes = LongArray(maxFrames)
    private val drawTimes = LongArray(maxFrames)
    private val frameTimes = LongArray(maxFrames)
    private val hitTestTimes = LongArray(maxFrames)
    private val mutationCounts = IntArray(maxFrames)

    private var frameIndex = 0
    var cacheHits: Long = 0
        private set
    var cacheMisses: Long = 0
        private set

    /** Warmup frame count, set by orchestrator after warmup completes */
    var warmupFrames: Int = 0

    /** Record prepare duration in nanoseconds for the current frame */
    fun recordPrepareTime(nanos: Long) {
        if (frameIndex < maxFrames) {
            prepareTimes[frameIndex] = nanos
        }
    }

    /** Record draw duration in nanoseconds for the current frame */
    fun recordDrawTime(nanos: Long) {
        if (frameIndex < maxFrames) {
            drawTimes[frameIndex] = nanos
        }
    }

    /** Record frame duration in nanoseconds (vsync interval) */
    fun recordFrameTime(nanos: Long) {
        if (frameIndex < maxFrames) {
            frameTimes[frameIndex] = nanos
        }
    }

    /** Record hit test duration in nanoseconds for the current frame */
    fun recordHitTestTime(nanos: Long) {
        if (frameIndex < maxFrames) {
            hitTestTimes[frameIndex] = nanos
        }
    }

    fun recordCacheHit() { cacheHits++ }
    fun recordCacheMiss() { cacheMisses++ }

    /** Record actual mutation count for the current frame */
    fun recordMutationCount(count: Int) {
        if (frameIndex < maxFrames) {
            mutationCounts[frameIndex] = count
        }
    }

    /**
     * Compute the observed mean mutation rate across all recorded frames.
     *
     * @param sceneSize Number of items in the scene
     * @return Mean mutation count / sceneSize, or 0.0 if no frames recorded
     */
    fun observedMutationRate(sceneSize: Int): Double {
        if (frameIndex == 0 || sceneSize == 0) return 0.0
        val totalMutations = mutationCounts.take(frameIndex).sum()
        return totalMutations.toDouble() / (frameIndex * sceneSize)
    }

    /** Advance to the next frame. Call after all per-frame recordings are done. */
    fun advanceFrame() {
        if (frameIndex < maxFrames) {
            frameIndex++
        }
    }

    /** Current number of recorded frames */
    val recordedFrames: Int get() = frameIndex

    /** Reset all metrics for a new iteration */
    fun reset() {
        frameIndex = 0
        cacheHits = 0
        cacheMisses = 0
        warmupFrames = 0
        // Arrays are overwritten by index, no need to clear
    }

    /**
     * Compute a snapshot of all collected metrics.
     *
     * @param sceneSize Number of items in the scene (for mutation rate calculation)
     * @return [FrameMetrics] with statistical summaries for each metric
     */
    fun snapshot(sceneSize: Int = 0): FrameMetrics {
        val count = frameIndex
        val totalCacheOps = cacheHits + cacheMisses

        // Memory metrics (best-effort on Android)
        val runtime = Runtime.getRuntime()
        val allocatedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

        // GC invocations (best-effort — android.os.Debug provides GC count)
        val gcInvocations = try {
            android.os.Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }

        return FrameMetrics(
            prepareTimeMs = computeStats(prepareTimes, count),
            drawTimeMs = computeStats(drawTimes, count),
            frameTimeMs = computeStats(frameTimes, count),
            hitTestTimeMs = computeStats(hitTestTimes, count),
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheHitRate = if (totalCacheOps > 0) cacheHits.toDouble() / totalCacheOps else 0.0,
            observedMutationRate = observedMutationRate(sceneSize),
            allocatedMB = allocatedMB,
            gcInvocations = gcInvocations,
            frameCount = count,
            warmupFrames = warmupFrames
        )
    }

    /**
     * Get the raw per-frame timing arrays (for JSON export).
     * Returns copies truncated to [recordedFrames].
     */
    fun rawTimings(): RawTimings {
        val count = frameIndex
        return RawTimings(
            prepareTimes = prepareTimes.copyOf(count),
            drawTimes = drawTimes.copyOf(count),
            frameTimes = frameTimes.copyOf(count),
            hitTestTimes = hitTestTimes.copyOf(count)
        )
    }

    /**
     * Compute the coefficient of variation over the last [windowSize] frame times.
     * Used by adaptive warmup to detect stability.
     *
     * @return CV as a fraction (e.g., 0.05 = 5%), or [Double.MAX_VALUE] if not enough data
     */
    fun recentFrameTimeCV(windowSize: Int = 20): Double {
        if (frameIndex < windowSize) return Double.MAX_VALUE
        val start = frameIndex - windowSize
        val window = LongArray(windowSize) { frameTimes[start + it] }
        val mean = window.average()
        if (mean == 0.0) return Double.MAX_VALUE
        val variance = window.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance) / mean
    }

    private fun computeStats(data: LongArray, count: Int): StatSummary {
        if (count == 0) return StatSummary(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val sorted = data.copyOf(count).also { it.sort() }
        val msValues = DoubleArray(count) { sorted[it] / 1_000_000.0 }

        val mean = msValues.average()
        val p50 = msValues[percentileIndex(count, 50)]
        val p95 = msValues[percentileIndex(count, 95)]
        val p99 = msValues[percentileIndex(count, 99)]
        val min = msValues.first()
        val max = msValues.last()
        val variance = msValues.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)
        val cv = if (mean != 0.0) stdDev / mean else 0.0

        return StatSummary(mean, p50, p95, p99, min, max, stdDev, cv)
    }

    private fun percentileIndex(count: Int, percentile: Int): Int {
        return ((count - 1) * percentile / 100).coerceIn(0, count - 1)
    }
}

/**
 * Raw per-frame timing arrays for detailed JSON export.
 */
data class RawTimings(
    val prepareTimes: LongArray,
    val drawTimes: LongArray,
    val frameTimes: LongArray,
    val hitTestTimes: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTimings) return false
        return prepareTimes.contentEquals(other.prepareTimes) &&
                drawTimes.contentEquals(other.drawTimes) &&
                frameTimes.contentEquals(other.frameTimes) &&
                hitTestTimes.contentEquals(other.hitTestTimes)
    }

    override fun hashCode(): Int {
        var result = prepareTimes.contentHashCode()
        result = 31 * result + drawTimes.contentHashCode()
        result = 31 * result + frameTimes.contentHashCode()
        result = 31 * result + hitTestTimes.contentHashCode()
        return result
    }
}
