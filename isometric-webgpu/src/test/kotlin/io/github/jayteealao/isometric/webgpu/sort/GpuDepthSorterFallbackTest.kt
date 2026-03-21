package io.github.jayteealao.isometric.webgpu.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpuDepthSorterFallbackTest {

    @Test
    fun `cpuFallbackSort returns descending depth order`() {
        val keys = floatArrayOf(1.0f, 5.0f, 3.0f, 2.0f, 4.0f)
        val sorted = GpuDepthSorter.cpuFallbackSort(keys)

        // Back-to-front: highest depth first
        assertEquals(listOf(1, 4, 2, 3, 0), sorted.toList())
    }

    @Test
    fun `cpuFallbackSort handles empty array`() {
        val sorted = GpuDepthSorter.cpuFallbackSort(floatArrayOf())
        assertTrue(sorted.isEmpty())
    }

    @Test
    fun `cpuFallbackSort handles single element`() {
        val sorted = GpuDepthSorter.cpuFallbackSort(floatArrayOf(42.0f))
        assertEquals(listOf(0), sorted.toList())
    }

    @Test
    fun `cpuFallbackSort handles equal keys`() {
        val sorted = GpuDepthSorter.cpuFallbackSort(floatArrayOf(1.0f, 1.0f, 1.0f))
        assertEquals(3, sorted.size)
        assertEquals(setOf(0, 1, 2), sorted.toSet())
    }
}
