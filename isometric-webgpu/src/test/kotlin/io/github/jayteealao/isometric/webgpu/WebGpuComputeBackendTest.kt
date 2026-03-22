package io.github.jayteealao.isometric.webgpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun `StatusSnapshot stores depth key count`() {
        val snapshot = WebGpuComputeBackend.StatusSnapshot(
            status = WebGpuComputeBackend.Status.Ready,
            detail = "Uploaded 295 depth keys",
            depthKeyCount = 295,
        )

        assertEquals(WebGpuComputeBackend.Status.Ready, snapshot.status)
        assertEquals("Uploaded 295 depth keys", snapshot.detail)
        assertEquals(295, snapshot.depthKeyCount)
    }

    @Test
    fun `invalidateContext resets initAttempted and cached gpu state`() {
        val backend = WebGpuComputeBackend()
        setPrivateField(backend, "initAttempted", true)

        val invalidate = backend.javaClass.getDeclaredMethod("invalidateContext")
        invalidate.isAccessible = true
        invalidate.invoke(backend)

        assertFalse(getPrivateBoolean(backend, "initAttempted"))
        assertNull(getPrivateField(backend, "gpuContext"))
        assertNull(getPrivateField(backend, "gpuSorter"))
    }

    private fun setPrivateField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getPrivateField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun getPrivateBoolean(target: Any, name: String): Boolean {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.getBoolean(target)
    }
}
