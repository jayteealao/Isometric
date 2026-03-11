package io.fabianterhorst.isometric.benchmark

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SceneGeneratorTest {

    @Test
    fun `same seed produces identical output`() {
        val first = SceneGenerator.generate(50)
        val second = SceneGenerator.generate(50)

        assertThat(first.size).isEqualTo(second.size)
        for (i in first.indices) {
            assertThat(first[i].id).isEqualTo(second[i].id)
            assertThat(first[i].color).isEqualTo(second[i].color)
            assertThat(first[i].position).isEqualTo(second[i].position)
        }
    }

    @Test
    fun `generates requested number of items`() {
        for (count in listOf(1, 10, 50, 100, 200)) {
            val items = SceneGenerator.generate(count)
            assertThat(items).hasSize(count)
        }
    }

    @Test
    fun `ids follow gen_index pattern`() {
        val items = SceneGenerator.generate(10)
        for (i in items.indices) {
            assertThat(items[i].id).isEqualTo("gen_$i")
        }
    }

    @Test
    fun `all items have non-null shapes and valid colors`() {
        val items = SceneGenerator.generate(50)
        for (item in items) {
            assertThat(item.shape).isNotNull()
            assertThat(item.color.r.toInt()).isIn(0..255)
            assertThat(item.color.g.toInt()).isIn(0..255)
            assertThat(item.color.b.toInt()).isIn(0..255)
        }
    }

    @Test
    fun `empty scene returns empty list`() {
        val items = SceneGenerator.generate(0)
        assertThat(items).isEmpty()
    }
}
