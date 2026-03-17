package io.github.jayteealao.isometric.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InteractionSimulatorTest {

    private val items = SceneGenerator.generate(10)
    private val viewportWidth = 1920
    private val viewportHeight = 1080

    @Test
    fun `NONE pattern never generates taps`() {
        for (frame in 0..100) {
            val result = InteractionSimulator.nextTap(
                frame, InteractionPattern.NONE, items, viewportWidth, viewportHeight
            )
            assertThat(result).isNull()
        }
    }

    @Test
    fun `OCCASIONAL pattern taps every 60 frames`() {
        val taps = (0..300).mapNotNull {
            InteractionSimulator.nextTap(
                it, InteractionPattern.OCCASIONAL, items, viewportWidth, viewportHeight
            )
        }
        // Frames 0, 60, 120, 180, 240, 300 → 6 taps
        assertThat(taps).hasSize(6)
    }

    @Test
    fun `CONTINUOUS pattern taps every frame`() {
        val taps = (0..99).mapNotNull {
            InteractionSimulator.nextTap(
                it, InteractionPattern.CONTINUOUS, items, viewportWidth, viewportHeight
            )
        }
        assertThat(taps).hasSize(100)
    }

    @Test
    fun `all taps are within canvas bounds`() {
        for (frame in 0..200) {
            val result = InteractionSimulator.nextTap(
                frame, InteractionPattern.CONTINUOUS, items, viewportWidth, viewportHeight
            ) ?: continue

            // Allow small negative due to jitter, but should be roughly within viewport
            assertThat(result.tapX).isGreaterThan(-10.0)
            assertThat(result.tapX).isLessThan(viewportWidth + 10.0)
            assertThat(result.tapY).isGreaterThan(-10.0)
            assertThat(result.tapY).isLessThan(viewportHeight + 10.0)
        }
    }

    @Test
    fun `mix of hit and miss taps over many frames`() {
        var hits = 0
        var misses = 0

        for (frame in 0..999) {
            val result = InteractionSimulator.nextTap(
                frame, InteractionPattern.CONTINUOUS, items, viewportWidth, viewportHeight
            ) ?: continue

            if (result.expectedHit) hits++ else misses++
        }

        // Expect roughly 80% hits, 20% misses — allow wide tolerance
        val hitRate = hits.toDouble() / (hits + misses)
        assertThat(hitRate).isGreaterThan(0.6)
        assertThat(hitRate).isLessThan(0.95)
    }

    @Test
    fun `empty items list produces no taps`() {
        val result = InteractionSimulator.nextTap(
            0, InteractionPattern.CONTINUOUS, emptyList(), viewportWidth, viewportHeight
        )
        assertThat(result).isNull()
    }

    @Test
    fun `taps are deterministic for same frame index`() {
        val first = InteractionSimulator.nextTap(
            42, InteractionPattern.CONTINUOUS, items, viewportWidth, viewportHeight
        )
        val second = InteractionSimulator.nextTap(
            42, InteractionPattern.CONTINUOUS, items, viewportWidth, viewportHeight
        )

        assertThat(first).isNotNull()
        assertThat(first!!.tapX).isEqualTo(second!!.tapX)
        assertThat(first.tapY).isEqualTo(second.tapY)
        assertThat(first.expectedHit).isEqualTo(second.expectedHit)
    }
}
