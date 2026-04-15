package io.github.jayteealao.isometric.webgpu.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the UV-transform section of [TriangulateEmitShader.WGSL]:
 *
 * - `sceneUvRegions` binding uses `array<UvRegion>` (user transform + atlas region)
 * - UV coordinates use the two-step `fract(userMatrix × vec3) × atlasScale + atlasOffset`
 * - Old per-component atlas variables (`uvSc`, `uvOff`) have been removed
 */
class TriangulateEmitShaderUvTest {

    /**
     * Binding 5 (`sceneUvRegions`) must be declared as `array<UvRegion>`.
     * The previous declaration used `array<mat3x2<f32>>` which composed atlas into the user
     * transform, breaking tiling by causing UV values to wrap to the atlas origin.
     */
    @Test
    fun `binding 5 uses UvRegion struct`() {
        val wgsl = TriangulateEmitShader.WGSL
        assertTrue(
            wgsl.contains("struct UvRegion"),
            "WGSL must define a UvRegion struct"
        )
        assertTrue(
            wgsl.contains("array<UvRegion>"),
            "sceneUvRegions binding must be declared as array<UvRegion>"
        )
    }

    /**
     * UV application must use the two-step transform:
     * `fract(uvRegion.userMatrix * vec3<f32>(…, 1.0)) * uvRegion.atlasScale + uvRegion.atlasOffset`
     * The `fract()` wraps tiling UV values back to [0,1) before the atlas sub-region mapping.
     */
    @Test
    fun `uv application uses fract and matrix multiply`() {
        val wgsl = TriangulateEmitShader.WGSL
        assertTrue(
            wgsl.contains("uvRegion.userMatrix") && wgsl.contains("vec3<f32>") && wgsl.contains("1.0"),
            "WGSL must multiply uvRegion.userMatrix against a homogeneous vec3(baseUV, 1.0)"
        )
        assertTrue(
            wgsl.contains("fract(uvRegion.userMatrix *"),
            "userMatrix multiply must be wrapped in fract() to support tiling"
        )
        assertTrue(
            wgsl.contains("uvRegion.atlasScale") && wgsl.contains("uvRegion.atlasOffset"),
            "WGSL must apply atlasScale and atlasOffset after fract()"
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
