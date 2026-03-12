package io.fabianterhorst.isometric.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BenchmarkHooksImplTest {

    @Test
    fun `cache hook events are forwarded to MetricsCollector`() {
        val collector = MetricsCollector(10)
        val hooks = BenchmarkHooksImpl(collector)

        hooks.onCacheMiss()
        hooks.onCacheHit()
        hooks.onCacheHit()

        val snapshot = collector.snapshot()
        assertThat(snapshot.cacheMisses).isEqualTo(1)
        assertThat(snapshot.cacheHits).isEqualTo(2)
        assertThat(snapshot.cacheHitRate).isWithin(0.0001).of(2.0 / 3.0)
    }
}
