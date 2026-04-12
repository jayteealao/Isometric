package io.github.jayteealao.isometric.webgpu.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriangulateEmitShaderTest {

    @Test
    fun `wgsl no longer contains diagnostic dot test markers`() {
        assertFalse(TriangulateEmitShader.WGSL.contains("DISCRIMINATING TEST"))
        assertFalse(TriangulateEmitShader.WGSL.contains("Write dot at s0 position"))
        assertFalse(TriangulateEmitShader.WGSL.contains("forceUse"))
    }

    @Test
    fun `wgsl emits up to four fan triangles`() {
        assertTrue(TriangulateEmitShader.WGSL.contains("Triangle 3: (s0, s4, s5)"))
        assertTrue(TriangulateEmitShader.WGSL.contains("triCount >= 4u"))
    }
}
