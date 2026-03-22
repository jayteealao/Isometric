package io.github.jayteealao.isometric.webgpu.sort

import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.ByteBuffer
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
    fun `packSortKeysInto uses 16 byte stride with zero padding`() {
        val paddedCount = 4
        val dest = ByteBuffer.allocateDirect(paddedCount * 16).order(ByteOrder.nativeOrder())
        GpuDepthSorter.packSortKeysInto(floatArrayOf(4.5f, -2.0f), paddedCount, dest)

        assertEquals(64, dest.capacity())

        dest.position(0)
        assertEquals(4.5f, dest.getFloat())
        assertEquals(0, dest.getInt())
        assertEquals(0, dest.getInt())
        assertEquals(0, dest.getInt())

        dest.position(16)
        assertEquals(-2.0f, dest.getFloat())
        assertEquals(1, dest.getInt())
        assertEquals(0, dest.getInt())
        assertEquals(0, dest.getInt())

        dest.position(32)
        assertEquals(Float.NEGATIVE_INFINITY, dest.getFloat())
        assertEquals(-1, dest.getInt())
    }

    @Test
    fun `extractSortedIndices preserves all non sentinel entries for non power of two counts`() {
        val paddedCount = 4
        val resultData = ByteBuffer.allocateDirect(paddedCount * 16).order(ByteOrder.nativeOrder())
        GpuDepthSorter.packSortKeysInto(floatArrayOf(9.0f, 7.0f, 5.0f), paddedCount, resultData)
        val sorted = GpuDepthSorter.extractSortedIndices(resultData, count = 3, paddedCount = 4)

        assertEquals(listOf(0, 1, 2), sorted.toList())
    }
}
