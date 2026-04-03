package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputeBackendTest {

    @Test
    fun `SortingComputeBackend contract`() {
        val backend = object : SortingComputeBackend {
            override suspend fun sortByDepthKeys(depthKeys: FloatArray): IntArray =
                depthKeys.indices.toList().toIntArray()
        }
        // Just verify the interface can be implemented
        assertEquals(3, kotlinx.coroutines.runBlocking {
            backend.sortByDepthKeys(floatArrayOf(1f, 2f, 3f)).size
        })
    }
}
