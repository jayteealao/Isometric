package io.fabianterhorst.isometric.benchmark

import com.google.common.truth.Truth.assertThat
import io.fabianterhorst.isometric.shapes.Prism
import io.fabianterhorst.isometric.shapes.Pyramid
import org.junit.Test
import kotlin.math.abs

class MutationSimulatorTest {

    private val items = SceneGenerator.generate(100)

    @Test
    fun `zero mutation rate produces no mutations`() {
        val result = MutationSimulator.mutate(items, 0.0, frameIndex = 0)
        assertThat(result).isEmpty()
    }

    @Test
    fun `observed mutation rate matches configured rate within 2 percent`() {
        // Run many frames and check that the average observed rate ≈ configured rate
        val configuredRate = 0.10
        val totalFrames = 1000
        var totalMutations = 0

        for (frame in 0 until totalFrames) {
            totalMutations += MutationSimulator.mutate(items, configuredRate, frame).size
        }

        val observedRate = totalMutations.toDouble() / (totalFrames * items.size)
        assertThat(abs(observedRate - configuredRate)).isLessThan(0.02)
    }

    @Test
    fun `high mutation rate matches configured rate within 2 percent`() {
        val configuredRate = 0.50
        val totalFrames = 500
        var totalMutations = 0

        for (frame in 0 until totalFrames) {
            totalMutations += MutationSimulator.mutate(items, configuredRate, frame).size
        }

        val observedRate = totalMutations.toDouble() / (totalFrames * items.size)
        assertThat(abs(observedRate - configuredRate)).isLessThan(0.02)
    }

    @Test
    fun `mutations are deterministic for same frame index`() {
        val first = MutationSimulator.mutate(items, 0.5, frameIndex = 42)
        val second = MutationSimulator.mutate(items, 0.5, frameIndex = 42)

        assertThat(first.size).isEqualTo(second.size)
        for (i in first.indices) {
            assertThat(first[i].index).isEqualTo(second[i].index)
            assertThat(first[i].newItem.id).isEqualTo(second[i].newItem.id)
        }
    }

    @Test
    fun `mutations preserve shape type`() {
        val mutations = MutationSimulator.mutate(items, 1.0, frameIndex = 0)

        for (mutation in mutations) {
            val original = items[mutation.index]
            val mutated = mutation.newItem
            if (original.shape is Prism) {
                assertThat(mutated.shape).isInstanceOf(Prism::class.java)
            } else {
                assertThat(mutated.shape).isInstanceOf(Pyramid::class.java)
            }
        }
    }

    @Test
    fun `mutations preserve item id and position`() {
        val mutations = MutationSimulator.mutate(items, 0.5, frameIndex = 7)

        for (mutation in mutations) {
            val original = items[mutation.index]
            assertThat(mutation.newItem.id).isEqualTo(original.id)
            assertThat(mutation.newItem.position).isEqualTo(original.position)
        }
    }

    @Test
    fun `empty items list produces no mutations`() {
        val result = MutationSimulator.mutate(emptyList(), 0.5, frameIndex = 0)
        assertThat(result).isEmpty()
    }
}
