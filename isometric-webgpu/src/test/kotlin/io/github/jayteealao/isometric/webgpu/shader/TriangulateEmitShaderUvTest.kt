package io.github.jayteealao.isometric.webgpu.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the UV-transform section of [TriangulateEmitShader.WGSL]:
 *
 * - `sceneUvRegions` binding uses `mat3x2<f32>` (the composed affine matrix)
 * - UV coordinates are computed by multiplying the matrix against `vec3(baseUV, 1.0)`
 * - Old per-component atlas variables (`uvSc`, `uvOff`) have been removed
 */
class TriangulateEmitShaderUvTest {

    /**
     * Binding 5 (`sceneUvRegions`) must be declared as `array<mat3x2<f32>>`.
     * The previous declaration used `array<vec4<f32>>` which cannot represent
     * a full affine transform.
     */
    @Test
    fun `binding 5 uses mat3x2 type`() {
        assertTrue(
            TriangulateEmitShader.WGSL.contains("array<mat3x2<f32>>"),
            "sceneUvRegions binding must be declared as array<mat3x2<f32>>"
        )
    }

    /**
     * UV application must use matrix multiplication: `uvMatrix * vec3<f32>(…, 1.0)`.
     * The `1.0` homogeneous coordinate is what makes the translation column work.
     */
    @Test
    fun `uv application uses matrix multiply`() {
        val wgsl = TriangulateEmitShader.WGSL
        assertTrue(
            wgsl.contains("uvMatrix") && wgsl.contains("vec3<f32>") && wgsl.contains("1.0"),
            "WGSL must multiply uvMatrix against a homogeneous vec3(baseUV, 1.0)"
        )
        // The multiply operator must appear between the matrix and the vec3
        assertTrue(
            wgsl.contains("uvMatrix *"),
            "uvMatrix must be used as the left operand of a multiply"
        )
    }

    /**
     * Legacy per-component variables `uvSc` and `uvOff` must be absent.
     * Their presence would indicate the old `vec4` atlas-scale+offset approach
     * is still in use instead of the composed `mat3x2` path.
     */
    @Test
    fun `old atlas var names uvSc and uvOff are absent`() {
        val wgsl = TriangulateEmitShader.WGSL
        assertFalse(wgsl.contains("uvSc"),  "Legacy variable 'uvSc' must be removed")
        assertFalse(wgsl.contains("uvOff"), "Legacy variable 'uvOff' must be removed")
    }
}
