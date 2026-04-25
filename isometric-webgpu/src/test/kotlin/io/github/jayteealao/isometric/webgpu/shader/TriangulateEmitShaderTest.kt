package io.github.jayteealao.isometric.webgpu.shader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Structural regression tests for [TriangulateEmitShader.WGSL] after the
 * `webgpu-ngon-faces` rewrite.
 *
 * Two complementary classes:
 * - Absence checks catch residue of the pre-rewrite shader (flat `transformedRaw`
 *   binding, fixed 3×vec4 UV stride, unrolled 4-triangle emit).
 * - Presence checks anchor the new shape: typed `transformed` struct binding,
 *   indirect `uvFaceTable` lookup, dynamic triangle-count emit loop.
 *
 * These are surrogate checks for AC6 (WGSL static validation) — real WGSL
 * compilation still happens at `GpuContext` init time on device.
 */
class TriangulateEmitShaderTest {

    @Test
    fun `wgsl no longer contains diagnostic dot test markers`() {
        assertFalse(TriangulateEmitShader.WGSL.contains("DISCRIMINATING TEST"))
        assertFalse(TriangulateEmitShader.WGSL.contains("Write dot at s0 position"))
        assertFalse(TriangulateEmitShader.WGSL.contains("forceUse"))
    }

    /**
     * Transformed face data is now bound as a typed struct array, not a flat vec4 array.
     * The old `transformedRaw: array<vec4<f32>>` pattern and its `ri + N` offset math
     * must be absent so new call sites can't accidentally re-introduce the byte-offset
     * indirection.
     */
    @Test
    fun `binding 0 uses TransformedFace struct not flat vec4`() {
        val wgsl = TriangulateEmitShader.WGSL

        val binding0TypedStruct = Regex(
            """@binding\(0\)[^;]*\barray<TransformedFace>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        assertNotNull(
            binding0TypedStruct.find(wgsl),
            "binding(0) must be declared as array<TransformedFace>"
        )

        assertFalse(
            wgsl.contains("transformedRaw"),
            "Legacy flat-vec4 binding name 'transformedRaw' must be removed"
        )
    }

    /**
     * Per-vertex UV lookup uses the offset+count table (binding 7) and the flat pool
     * (binding 6). The old fixed 3×vec4 per-face layout (`sceneUvCoords[uvBase + N]`)
     * must be absent.
     */
    @Test
    fun `uv fetch uses offset+count indirection`() {
        val wgsl = TriangulateEmitShader.WGSL

        val uvPoolBinding = Regex(
            """@binding\(6\)[^;]*\barray<vec2<f32>>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        assertNotNull(
            uvPoolBinding.find(wgsl),
            "binding(6) must be array<vec2<f32>> (flat UV pool)"
        )

        val uvTableBinding = Regex(
            """@binding\(7\)[^;]*\barray<vec2<u32>>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        assertNotNull(
            uvTableBinding.find(wgsl),
            "binding(7) must be array<vec2<u32>> (UV offset+count table)"
        )

        // The indirect lookup — anchored to the actual let assignment that opens the
        // UV fetch block so a comment mentioning the pattern doesn't accidentally pass.
        val tableLookup = Regex("""let\s+\w+\s*=\s*uvFaceTable\[key\.originalIndex\]""")
        assertNotNull(
            tableLookup.find(wgsl),
            "WGSL must fetch uvFaceTable[key.originalIndex] per-face"
        )

        // Absence: the legacy fixed-stride pattern is gone.
        assertFalse(
            wgsl.contains("sceneUvCoords"),
            "Legacy binding name 'sceneUvCoords' must be removed"
        )
        val legacyFixedUvFetch = Regex("""uvBase\s*=\s*key\.originalIndex\s*\*\s*3u""")
        assertFalse(
            legacyFixedUvFetch.containsMatchIn(wgsl),
            "Legacy fixed-stride UV fetch (originalIndex * 3u) must be removed"
        )
    }

    /**
     * Triangle emit is now ear-clip triangulation, not a fan-from-`s[0]` loop.
     * Anchors:
     * - Active-set linked list arrays (`nextIdx`, `prevIdx`) declared as
     *   `array<u32, 24>` on stack.
     * - Per-face winding detection via signed area (so the convex test isn't
     *   hardcoded to a specific NDC orientation).
     * - The legacy fan loop signature (`for (var t: u32 = 0u; t < triCount`)
     *   must be absent; that pattern was the I-02 BLOCKER for non-convex faces.
     *
     * Earlier unrolled markers (pre-Commit B) must remain absent.
     */
    @Test
    fun `triangle emit uses ear-clipping not fan-from-s0`() {
        val wgsl = TriangulateEmitShader.WGSL

        // Active-set linked list — required for ear-clipping.
        val nextIdxDecl = Regex("""nextIdx\s*:\s*array<u32\s*,\s*24>""")
        assertNotNull(
            nextIdxDecl.find(wgsl),
            "Ear-clipping requires `nextIdx: array<u32, 24>` on-stack linked list"
        )
        val prevIdxDecl = Regex("""prevIdx\s*:\s*array<u32\s*,\s*24>""")
        assertNotNull(
            prevIdxDecl.find(wgsl),
            "Ear-clipping requires `prevIdx: array<u32, 24>` on-stack linked list"
        )

        // Per-face winding detection via signed area — must not hardcode CCW/CW.
        val signedAreaDecl = Regex("""var\s+signedArea2\s*:\s*f32""")
        assertNotNull(
            signedAreaDecl.find(wgsl),
            "Convex test must derive sign from per-face polygon signed area"
        )
        val desiredSignDecl = Regex("""let\s+desiredSign\s*:\s*f32""")
        assertNotNull(
            desiredSignDecl.find(wgsl),
            "`desiredSign` constant must encode this face's winding for the ear test"
        )

        // Legacy fan loop must be gone — that was the I-02 BLOCKER.
        val legacyFanLoop = Regex(
            """for\s*\(\s*var\s+t\s*:\s*u32\s*=\s*0u\s*;\s*t\s*<\s*triCount"""
        )
        assertFalse(
            legacyFanLoop.containsMatchIn(wgsl),
            "Fan-from-s[0] loop `for (var t: u32 = 0u; t < triCount ...)` must be " +
                "removed — it broke non-convex polygons (I-02)"
        )

        // Pre-Commit-B unrolled markers stay gone.
        assertFalse(
            wgsl.contains("Triangle 3: (s0, s4, s5)"),
            "Unrolled 'Triangle 3' comment must be removed"
        )
        assertFalse(
            wgsl.contains("if (triCount >= 4u)"),
            "Unrolled triCount >= 4u branch must be removed"
        )
    }

    /**
     * The TransformedFace struct now exposes `s: array<vec2<f32>, 24>` rather than
     * six named vec2 fields (`s0..s5`).
     */
    @Test
    fun `transformed face exposes 24-slot vertex array`() {
        val wgsl = TriangulateEmitShader.WGSL

        val arrayField = Regex("""s\s*:\s*array<vec2<f32>\s*,\s*24>""")
        assertNotNull(
            arrayField.find(wgsl),
            "TransformedFace must declare `s: array<vec2<f32>, 24>`"
        )

        // Old named fields gone (s0..s5 as top-level struct members — dotted access
        // inside the pre-rewrite function body).
        assertFalse(
            wgsl.contains("s5: vec2<f32>"),
            "Legacy named field 's5: vec2<f32>' must be removed from TransformedFace"
        )
    }

    /**
     * MAX_VERTICES_PER_FACE constant in the Kotlin surface matches the per-face slot
     * stride the WGSL uses for fixed-stride output allocation.
     */
    @Test
    fun `max vertices per face is 66 for 22 fan triangles`() {
        assertTrue(TriangulateEmitShader.MAX_TRIANGLES_PER_FACE == 22)
        assertTrue(TriangulateEmitShader.MAX_VERTICES_PER_FACE == 66)
    }
}
