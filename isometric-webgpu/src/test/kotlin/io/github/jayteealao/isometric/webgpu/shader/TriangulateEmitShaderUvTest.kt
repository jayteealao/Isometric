package io.github.jayteealao.isometric.webgpu.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// IsometricFragmentShader is in the same package — no import needed

/**
 * Verifies the UV-transform section of [TriangulateEmitShader.WGSL] and
 * [IsometricFragmentShader.WGSL]:
 *
 * - `sceneUvRegions` binding uses `array<UvRegion>` (user transform + atlas region)
 * - Compute shader applies user matrix per-vertex WITHOUT `fract()` — raw UV is emitted
 * - Fragment shader applies `fract(in.uv) * in.atlasRegion.xy + in.atlasRegion.zw`
 *   per-fragment so tiling wraps correctly (per-vertex fract collapses UV=1.0 corners)
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
     * The compute shader must apply the user matrix per-vertex but must NOT apply `fract()`.
     * `fract()` cannot be applied per-vertex because `fract(1.0) == 0.0` collapses all face
     * corners (where baseUV = 1.0) to UV = 0.0, making the entire face sample one texel.
     */
    @Test
    fun `compute shader applies user matrix without fract`() {
        val wgsl = TriangulateEmitShader.WGSL
        assertTrue(
            wgsl.contains("uvRegion.userMatrix") && wgsl.contains("vec3<f32>") && wgsl.contains("1.0"),
            "WGSL must multiply uvRegion.userMatrix against a homogeneous vec3(baseUV, 1.0)"
        )
        assertFalse(
            wgsl.contains("fract(uvRegion.userMatrix"),
            "Compute shader must NOT wrap userMatrix in fract() — fract must happen per-fragment"
        )
        assertTrue(
            wgsl.contains("uvRegion.atlasScale") && wgsl.contains("uvRegion.atlasOffset"),
            "Compute shader must read atlasScale and atlasOffset to emit them as vertex attributes"
        )
    }

    /**
     * The fragment shader must apply `fract()` per-fragment to the interpolated UV, then
     * apply the atlas region. This is the correct place because the GPU rasterizer linearly
     * interpolates the pre-fract UV before the wrap, preserving correct tiling across faces.
     */
    @Test
    fun `fragment shader applies per-fragment fract and atlas mapping`() {
        val wgsl = IsometricFragmentShader.WGSL
        assertTrue(
            wgsl.contains("fract(in.uv)"),
            "Fragment shader must apply fract() to the interpolated UV per-fragment"
        )
        assertTrue(
            wgsl.contains("in.atlasRegion.xy") && wgsl.contains("in.atlasRegion.zw"),
            "Fragment shader must apply atlasRegion scale (xy) and offset (zw) after fract()"
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
