package io.github.jayteealao.isometric.benchmark

import io.github.jayteealao.isometric.compose.runtime.RenderBenchmarkHooks

/**
 * Implements [RenderBenchmarkHooks] and bridges renderer callbacks to [MetricsCollector].
 *
 * Uses [System.nanoTime] to measure prepare/draw durations. All callbacks fire
 * on the main thread within the Compose draw pass — no synchronization needed.
 *
 * @param collector The metrics collector to record timings into
 */
class BenchmarkHooksImpl(
    private val collector: MetricsCollector
) : RenderBenchmarkHooks {

    private var prepareStartNanos: Long = 0
    private var drawStartNanos: Long = 0
    var drawPassCount: Long = 0
        private set

    override fun onPrepareStart() {
        prepareStartNanos = System.nanoTime()
    }

    override fun onPrepareEnd() {
        val elapsed = System.nanoTime() - prepareStartNanos
        collector.recordPrepareTime(elapsed)
    }

    override fun onDrawStart() {
        drawStartNanos = System.nanoTime()
    }

    override fun onDrawEnd() {
        val elapsed = System.nanoTime() - drawStartNanos
        collector.recordDrawTime(elapsed)
        drawPassCount++
    }

    override fun onCacheHit() {
        collector.recordCacheHit()
    }

    override fun onCacheMiss() {
        collector.recordCacheMiss()
    }
}
