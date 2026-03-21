package io.github.jayteealao.isometric.compose.runtime.sort

import com.google.common.truth.Truth.assertThat
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.Point
import org.junit.Test

class CpuDepthSortStrategyTest {

    private val sorter = CpuDepthSortStrategy()

    @Test
    fun `sorts faces back-to-front by centroid depth`() {
        // Face A: z=0, depth = avg(x+y-2z) = avg(0+0+1+0+1+1+0+1) / 4 = 1.0
        val faceA = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0),
            Point(0.0, 1.0, 0.0)
        )

        // Face B: z=5, depth = avg((0+0-10)+(1+0-10)+(1+1-10)+(0+1-10)) / 4 = -9.0
        val faceB = Path(
            Point(0.0, 0.0, 5.0),
            Point(1.0, 0.0, 5.0),
            Point(1.0, 1.0, 5.0),
            Point(0.0, 1.0, 5.0)
        )

        val paths = mutableListOf(faceB, faceA) // B before A (wrong order)
        sorter.sort(paths)

        // After sort: higher depth (further) first = faceA (depth=1.0) before faceB (depth=-9.0)
        assertThat(paths[0]).isSameInstanceAs(faceA)
        assertThat(paths[1]).isSameInstanceAs(faceB)
    }

    @Test
    fun `empty list does not throw`() {
        val paths = mutableListOf<Path>()
        sorter.sort(paths)
        assertThat(paths).isEmpty()
    }

    @Test
    fun `single face list is unchanged`() {
        val face = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0)
        )
        val paths = mutableListOf(face)
        sorter.sort(paths)
        assertThat(paths).hasSize(1)
        assertThat(paths[0]).isSameInstanceAs(face)
    }
}
