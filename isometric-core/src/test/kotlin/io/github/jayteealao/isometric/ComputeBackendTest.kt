package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComputeBackendTest {

    @Test
    fun `Cpu backend is not async`() {
        assertFalse(ComputeBackend.Cpu.isAsync)
    }

    @Test
    fun `Cpu backend toString`() {
        assertEquals("ComputeBackend.Cpu", ComputeBackend.Cpu.toString())
    }

    @Test
    fun `SortingComputeBackend defaults to async`() {
        val backend = object : SortingComputeBackend {
            override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray =
                depthKeys.indices.toList().toIntArray()
        }
        assertTrue(backend.isAsync)
    }
}
