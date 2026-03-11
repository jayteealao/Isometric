package io.fabianterhorst.isometric.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class MetricsCollectorTest {

    @Test
    fun `percentile calculations are correct for known values`() {
        val collector = MetricsCollector(100)

        // Record 100 frames with prepare times 1ms..100ms (in nanos)
        for (i in 1..100) {
            collector.recordPrepareTime(i * 1_000_000L)
            collector.advanceFrame()
        }

        val snapshot = collector.snapshot()

        // p50 = 50th percentile of 1..100 → ~50ms
        assertThat(snapshot.prepareTimeMs.p50).isWithin(1.0).of(50.0)
        // p95 → ~95ms
        assertThat(snapshot.prepareTimeMs.p95).isWithin(1.0).of(95.0)
        // p99 → ~99ms
        assertThat(snapshot.prepareTimeMs.p99).isWithin(1.0).of(99.0)
        // min = 1ms
        assertThat(snapshot.prepareTimeMs.min).isWithin(0.01).of(1.0)
        // max = 100ms
        assertThat(snapshot.prepareTimeMs.max).isWithin(0.01).of(100.0)
        // mean = 50.5ms
        assertThat(snapshot.prepareTimeMs.mean).isWithin(0.1).of(50.5)
    }

    @Test
    fun `cache hit rate is computed correctly`() {
        val collector = MetricsCollector(10)

        repeat(7) { collector.recordCacheHit() }
        repeat(3) { collector.recordCacheMiss() }
        collector.advanceFrame()

        val snapshot = collector.snapshot()
        assertThat(snapshot.cacheHitRate).isWithin(0.001).of(0.7)
        assertThat(snapshot.cacheHits).isEqualTo(7)
        assertThat(snapshot.cacheMisses).isEqualTo(3)
    }

    @Test
    fun `observed mutation rate matches expected`() {
        val collector = MetricsCollector(100)
        val sceneSize = 50

        // Record 100 frames with 5 mutations each → rate = 5/50 = 0.10
        for (i in 0 until 100) {
            collector.recordMutationCount(5)
            collector.advanceFrame()
        }

        val rate = collector.observedMutationRate(sceneSize)
        assertThat(rate).isWithin(0.001).of(0.10)
    }

    @Test
    fun `reset clears all state`() {
        val collector = MetricsCollector(10)

        collector.recordPrepareTime(1_000_000L)
        collector.recordCacheHit()
        collector.recordMutationCount(3)
        collector.advanceFrame()

        assertThat(collector.recordedFrames).isEqualTo(1)
        assertThat(collector.cacheHits).isEqualTo(1)

        collector.reset()

        assertThat(collector.recordedFrames).isEqualTo(0)
        assertThat(collector.cacheHits).isEqualTo(0)
        assertThat(collector.cacheMisses).isEqualTo(0)
    }

    @Test
    fun `rawTimings returns truncated copies`() {
        val collector = MetricsCollector(100)

        for (i in 0 until 5) {
            collector.recordPrepareTime((i + 1) * 1_000_000L)
            collector.recordDrawTime((i + 1) * 500_000L)
            collector.advanceFrame()
        }

        val raw = collector.rawTimings()
        assertThat(raw.prepareTimes).hasLength(5)
        assertThat(raw.drawTimes).hasLength(5)
        assertThat(raw.prepareTimes[0]).isEqualTo(1_000_000L)
        assertThat(raw.prepareTimes[4]).isEqualTo(5_000_000L)
    }

    @Test
    fun `recentFrameTimeCV returns MAX_VALUE when not enough data`() {
        val collector = MetricsCollector(100)

        for (i in 0 until 5) {
            collector.recordFrameTime(16_000_000L)
            collector.advanceFrame()
        }

        // Window size 20, but only 5 frames recorded
        assertThat(collector.recentFrameTimeCV(20)).isEqualTo(Double.MAX_VALUE)
    }

    @Test
    fun `recentFrameTimeCV is low for stable frame times`() {
        val collector = MetricsCollector(100)

        // 30 frames of very stable 16ms frame times
        for (i in 0 until 30) {
            collector.recordFrameTime(16_666_667L) // ~16.67ms
            collector.advanceFrame()
        }

        val cv = collector.recentFrameTimeCV(20)
        assertThat(cv).isLessThan(0.01) // CV < 1% for identical values
    }

    @Test
    fun `empty collector produces zero stats`() {
        val collector = MetricsCollector(10)
        val snapshot = collector.snapshot()

        assertThat(snapshot.prepareTimeMs.mean).isEqualTo(0.0)
        assertThat(snapshot.frameCount).isEqualTo(0)
        assertThat(snapshot.cacheHitRate).isEqualTo(0.0)
    }
}
