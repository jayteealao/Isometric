package io.github.jayteealao.isometric.webgpu.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.ByteOrder

class GpuDepthSorterFallbackTest {
    @Test
    fun `nextPowerOfTwo returns same value for powers of two`() {
        assertEquals(64, GpuDepthSorter.nextPowerOfTwo(64))
    }

    @Test
    fun `nextPowerOfTwo rounds up non power of two values`() {
        assertEquals(128, GpuDepthSorter.nextPowerOfTwo(65))
        assertEquals(256, GpuDepthSorter.nextPowerOfTwo(255))
    }

    @Test
    fun `packSortKeys uses 16 byte stride with zero padding`() {
        val packed = GpuDepthSorter.packSortKeys(floatArrayOf(4.5f, -2.0f), paddedCount = 4)
            .order(ByteOrder.nativeOrder())

        assertEquals(64, packed.capacity())

        packed.position(0)
        assertEquals(4.5f, packed.getFloat())
        assertEquals(0, packed.getInt())
        assertEquals(0, packed.getInt())
        assertEquals(0, packed.getInt())

        packed.position(16)
        assertEquals(-2.0f, packed.getFloat())
        assertEquals(1, packed.getInt())
        assertEquals(0, packed.getInt())
        assertEquals(0, packed.getInt())

        packed.position(32)
        assertEquals(Float.NEGATIVE_INFINITY, packed.getFloat())
        assertEquals(-1, packed.getInt())
    }

    @Test
    fun `extractSortedIndices preserves all non sentinel entries for non power of two counts`() {
        val resultData = GpuDepthSorter.packSortKeys(floatArrayOf(9.0f, 7.0f, 5.0f), paddedCount = 4)
            .order(ByteOrder.nativeOrder())
        val sorted = GpuDepthSorter.extractSortedIndices(resultData, count = 3, paddedCount = 4)

        assertEquals(listOf(0, 1, 2), sorted.toList())
    }
}
