package io.github.jayteealao.isometric.webgpu.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

        // Matches "struct UvRegion {" — the token must open a struct declaration, not appear in a
        // comment or an unrelated identifier. \s* allows any whitespace between the keyword and name.
        val uvRegionStructDecl = Regex("""^\s*struct\s+UvRegion\s*\{""", RegexOption.MULTILINE)
        assertNotNull(
            uvRegionStructDecl.find(wgsl),
            "WGSL must define a UvRegion struct declaration (struct UvRegion {)"
        )

        // Matches the binding(5) storage declaration containing array<UvRegion> on the same
        // logical line. DOT_MATCHES_ALL lets [^;]* skip any whitespace between @binding(5) and the
        // type token, but the semicolon sentinel stops it from spanning multiple declarations.
        val binding5AsUvRegionArray = Regex(
            """@binding\(5\)[^;]*\barray<UvRegion>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        assertNotNull(
            binding5AsUvRegionArray.find(wgsl),
            "sceneUvRegions (@binding(5)) must be declared as array<UvRegion>"
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

        // Matches the matrix-multiply expression "uvRegion.userMatrix * vec3<f32>(..., 1.0)"
        // inside a let assignment. This anchors the check to the actual multiplication site
        // rather than matching any occurrence of "vec3<f32>" or "1.0" in the shader.
        val userMatrixMultiply = Regex(
            """uvRegion\.userMatrix\s*\*\s*vec3<f32>\([^)]*,\s*1\.0\)"""
        )
        assertNotNull(
            userMatrixMultiply.find(wgsl),
            "WGSL must multiply uvRegion.userMatrix against a homogeneous vec3(baseUV, 1.0)"
        )

        // Absence check: fract must NOT be applied to the user-matrix result in the compute shader.
        // A bare contains() is intentional here — for an absence check, a false positive (the token
        // appears only in a comment) merely makes the test stricter, not incorrect.
        assertFalse(
            wgsl.contains("fract(uvRegion.userMatrix"),
            "Compute shader must NOT wrap userMatrix in fract() — fract must happen per-fragment"
        )

        // Matches the atlas scale and offset field reads inside a let assignment inside the
        // @compute entry point function body (after the opening brace of triangulateEmit).
        // Anchoring to "uvRegion.atlasScale.x" and "uvRegion.atlasOffset.x" is sufficient
        // because these dotted field paths are only valid inside the function body.
        val atlasScaleRead  = Regex("""uvRegion\.atlasScale\s*\.""")
        val atlasOffsetRead = Regex("""uvRegion\.atlasOffset\s*\.""")
        assertNotNull(
            atlasScaleRead.find(wgsl),
            "Compute shader must read atlasScale fields to emit them as vertex attributes"
        )
        assertNotNull(
            atlasOffsetRead.find(wgsl),
            "Compute shader must read atlasOffset fields to emit them as vertex attributes"
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

        // Matches the combined atlas-UV expression that must appear in the @fragment function body:
        //   fract(in.uv) * in.atlasRegion.xy + in.atlasRegion.zw
        // Normalised-whitespace regex so reformatting the expression doesn't break the test.
        val fractAtlasExpr = Regex(
            """fract\(\s*in\.uv\s*\)\s*\*\s*in\.atlasRegion\.xy\s*\+\s*in\.atlasRegion\.zw"""
        )
        assertNotNull(
            fractAtlasExpr.find(wgsl),
            "Fragment shader must compute 'fract(in.uv) * in.atlasRegion.xy + in.atlasRegion.zw'"
        )
    }

    /**
     * Legacy per-component variables `uvSc` and `uvOff` must be absent.
     * Their presence would indicate the old `vec4` atlas-scale+offset approach
     * is still in use instead of the UvRegion struct path.
     *
     * Bare contains() is intentional for absence checks: a false positive (the name appears
     * only in a comment) makes the test stricter rather than incorrect. If these names ever
     * re-appear — even in a comment — the test will surface the regression for review.
     */
    @Test
    fun `old atlas var names uvSc and uvOff are absent`() {
        val wgsl = TriangulateEmitShader.WGSL
        assertFalse(wgsl.contains("uvSc"),  "Legacy variable 'uvSc' must be removed")
        assertFalse(wgsl.contains("uvOff"), "Legacy variable 'uvOff' must be removed")
    }
}
