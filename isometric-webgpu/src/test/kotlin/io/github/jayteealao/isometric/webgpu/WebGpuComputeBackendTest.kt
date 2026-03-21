package io.github.jayteealao.isometric.webgpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebGpuComputeBackendTest {

    @Test
    fun `WebGpuComputeBackend is async`() {
        val backend = WebGpuComputeBackend()
        assertTrue(backend.isAsync)
    }

    @Test
    fun `WebGpuComputeBackend toString`() {
        assertEquals("ComputeBackend.WebGpu", WebGpuComputeBackend().toString())
    }
}
