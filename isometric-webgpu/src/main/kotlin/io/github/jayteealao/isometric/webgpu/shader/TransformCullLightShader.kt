package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader for the fused Transform + Cull + Light + Compact pass.
 *
 * One GPU thread per face. Each invocation:
 * 1. Projects all 3D vertices to 2D screen space via the isometric projection matrix.
 * 2. Back-face culls: discards faces whose signed-area in screen space is positive.
 * 3. Frustum culls: discards faces whose AABB lies entirely outside the viewport.
 * 4. Computes a depth sort key (`x + y − 2z` averaged over 3D vertices).
 * 5. Computes diffuse lighting using the pre-computed face normal from [FaceData].
 * 6. Writes the result to a compacted output array via [atomicAdd] on `visibleCount`.
 *
 * ## Struct layouts
 *
 * All structs must be kept in sync with their Kotlin counterparts:
 * - `FaceData` ← [SceneDataPacker] / [SceneDataLayout] (128 bytes, stride 128)
 * - `TransformedFace` ← [SceneDataLayout.TRANSFORMED_FACE_BYTES] (96 bytes, stride 96)
 * - `SceneUniforms` ← [GpuSceneUniforms] (96 bytes, 6 × vec4)
 *
 * ## Projection formula
 *
 * Matches [IsometricProjection.translatePointInto]:
 * ```
 * screenX = origin.x + x * projRow0.x + y * projRow0.y
 * screenY = origin.y − x * projRow1.x − y * projRow1.y − z * projRow1.z
 * ```
 * Note: `origin` is a separate `vec4` field — `projRow0.w` and `projRow1.w` are 0,
 * not the origin (unlike the earlier ws13 plan sketch).
 *
 * ## Lighting approximation
 *
 * Uses a simplified RGB-space model rather than the CPU's HSL path:
 * ```
 * brightness = max(dot(normal, lightDir), 0.0)
 * litRgb     = clamp(baseRgb * lightColor + brightness * colorDiff, 0, 1)
 * ```
 * The face normal is pre-computed by [SceneDataPacker] — the shader does not
 * recompute it from vertex positions.
 *
 * ## Bindings
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       scene        : array<FaceData>
 * @group(0) @binding(1) var<storage, read_write>  transformed  : array<TransformedFace>
 * @group(0) @binding(2) var<uniform>              uniforms     : SceneUniforms
 * @group(0) @binding(3) var<storage, read_write>  visibleCount : atomic<u32>
 * ```
 */
internal object TransformCullLightShader {

    /** Compute shader entry point name. */
    const val ENTRY_POINT = "main"

    /**
     * Number of GPU threads per workgroup.
     * Guaranteed by the WebGPU spec (`maxComputeInvocationsPerWorkgroup ≥ 256`).
     * Verified at [GpuContext] creation time (Risk R2).
     */
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        // ── FaceData ──────────────────────────────────────────────────────────────
        // 128 bytes per face.  Matches SceneDataPacker byte-for-byte.
        //
        //  offset  size  field
        //    0-15   16   v0 (vec3<f32>) + _p0 (f32 pad)
        //   16-31   16   v1 + _p1
        //   32-47   16   v2 + _p2
        //   48-63   16   v3 + vertexCount (u32 in v3's pad slot)
        //   64-79   16   baseColor (vec4<f32>, RGBA in [0,1])
        //   80-91   12   normal (vec3<f32>, pre-normalised)
        //   92-95    4   textureIndex (u32; 0xFFFFFFFF = no texture)
        //   96-99    4   faceIndex (u32)
        //  100-127   28  padding (7 × u32)  → struct stride = 128
        struct FaceData {
            v0: vec3<f32>,  _p0: f32,
            v1: vec3<f32>,  _p1: f32,
            v2: vec3<f32>,  _p2: f32,
            v3: vec3<f32>,  vertexCount: u32,
            baseColor:   vec4<f32>,
            normal:      vec3<f32>,
            textureIndex: u32,
            faceIndex:   u32,
            _f0: u32, _f1: u32, _f2: u32, _f3: u32, _f4: u32, _f5: u32, _f6: u32,
        }

        // ── TransformedFace ───────────────────────────────────────────────────────
        // 96 bytes per face.  Matches SceneDataLayout.TRANSFORMED_FACE_BYTES.
        //
        //  offset  size  field
        //    0-47   48   s0–s5 (6 × vec2<f32>; only s0–s3 populated for 4-vertex faces)
        //   48-63   16   vertexCount + _p0–_p2 (4 × u32)
        //                └─ _p0–_p2 are explicit padding so litColor lands at 64 (16-align)
        //   64-79   16   litColor (vec4<f32>, RGBA in [0,1])
        //   80-95   16   depthKey (f32) + faceIndex (u32) + _p3–_p4 (2 × u32)
        struct TransformedFace {
            s0: vec2<f32>,
            s1: vec2<f32>,
            s2: vec2<f32>,
            s3: vec2<f32>,
            s4: vec2<f32>,
            s5: vec2<f32>,
            vertexCount: u32,
            _p0: u32,
            _p1: u32,
            _p2: u32,
            litColor: vec4<f32>,
            depthKey:  f32,
            faceIndex: u32,
            _p3: u32,
            _p4: u32,
        }

        // ── SceneUniforms ─────────────────────────────────────────────────────────
        // 96 bytes (6 × vec4).  Matches GpuSceneUniforms byte-for-byte.
        //
        //   0-15   projRow0:        [t00, t10, 0, 0]
        //  16-31   projRow1:        [t01, t11, scale, 0]
        //  32-47   origin:          [originX, originY, 0, 0]
        //  48-63   lightDirAndDiff: [ldx, ldy, ldz, colorDifference]
        //  64-79   lightColor:      [r/255, g/255, b/255, a/255]
        //  80-95   viewport:        [width(u32), height(u32), faceCount(u32), 0]
        struct SceneUniforms {
            projRow0:        vec4<f32>,
            projRow1:        vec4<f32>,
            origin:          vec4<f32>,
            lightDirAndDiff: vec4<f32>,
            lightColor:      vec4<f32>,
            viewport:        vec4<u32>,
        }

        @group(0) @binding(0) var<storage, read>       scene:        array<FaceData>;
        @group(0) @binding(1) var<storage, read_write>  transformed:  array<TransformedFace>;
        @group(0) @binding(2) var<uniform>              uniforms:     SceneUniforms;
        @group(0) @binding(3) var<storage, read_write>  visibleCount: atomic<u32>;

        // Isometric 3D→2D projection.  Matches IsometricProjection.translatePointInto:
        //   screenX = originX + x * t00 + y * t10
        //   screenY = originY − x * t01 − y * t11 − z * scale
        fn projectPoint(p: vec3<f32>) -> vec2<f32> {
            let sx = uniforms.origin.x
                   + p.x * uniforms.projRow0.x
                   + p.y * uniforms.projRow0.y;
            let sy = uniforms.origin.y
                   - p.x * uniforms.projRow1.x
                   - p.y * uniforms.projRow1.y
                   - p.z * uniforms.projRow1.z;
            return vec2<f32>(sx, sy);
        }

        @compute @workgroup_size(256)
        fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
            let i = gid.x;
            if (i >= uniforms.viewport.z) { return; }

            let face = scene[i];
            let vc   = face.vertexCount;

            // ── 1. Project all vertices ────────────────────────────────────────
            let s0 = projectPoint(face.v0);
            let s1 = projectPoint(face.v1);
            let s2 = projectPoint(face.v2);
            // v3 is only valid when vc >= 4; select avoids projecting a zero-vector
            let s3 = select(vec2<f32>(0.0), projectPoint(face.v3), vc >= 4u);

            // ── 2. Back-face cull ─────────────────────────────────────────────
            // Signed area of the first triangle (s0, s1, s2) in screen space.
            // Matches IsometricProjection.cullPath: area > 0 → back-facing → discard.
            let area = s0.x * s1.y + s1.x * s2.y + s2.x * s0.y
                     - s1.x * s0.y - s2.x * s1.y - s0.x * s2.y;
            if (area > 0.0) { return; }

            // ── 3. Frustum cull (AABB vs viewport) ───────────────────────────
            // Matches IsometricProjection.itemInDrawingBounds: any vertex in bounds
            // → keep.  Inverted here: if AABB entirely outside → discard.
            let vw = f32(uniforms.viewport.x);
            let vh = f32(uniforms.viewport.y);

            var minX = min(s0.x, min(s1.x, s2.x));
            var maxX = max(s0.x, max(s1.x, s2.x));
            var minY = min(s0.y, min(s1.y, s2.y));
            var maxY = max(s0.y, max(s1.y, s2.y));
            if (vc >= 4u) {
                minX = min(minX, s3.x); maxX = max(maxX, s3.x);
                minY = min(minY, s3.y); maxY = max(maxY, s3.y);
            }
            if (maxX < 0.0 || minX > vw || maxY < 0.0 || minY > vh) { return; }

            // ── 4. Depth sort key ─────────────────────────────────────────────
            // Centroid of (x + y − 2z) over all 3D vertices.
            // Matches IsometricEngine.projectSceneAsync depth key formula.
            let d0 = face.v0.x + face.v0.y - 2.0 * face.v0.z;
            let d1 = face.v1.x + face.v1.y - 2.0 * face.v1.z;
            let d2 = face.v2.x + face.v2.y - 2.0 * face.v2.z;
            let d3 = select(0.0, face.v3.x + face.v3.y - 2.0 * face.v3.z, vc >= 4u);
            let depthKey = (d0 + d1 + d2 + d3) / f32(vc);

            // ── 5. Diffuse lighting ───────────────────────────────────────────
            // Normal is pre-computed and normalised by SceneDataPacker — no GPU
            // recomputation needed.  Simplified RGB approximation of the CPU's
            // IsoColor.lighten(brightness * colorDifference, lightColor) path.
            let lightDir   = uniforms.lightDirAndDiff.xyz;
            let colorDiff  = uniforms.lightDirAndDiff.w;
            let brightness = max(dot(face.normal, lightDir), 0.0);
            let tinted     = face.baseColor.rgb * uniforms.lightColor.rgb;
            let litRgb     = clamp(tinted + brightness * colorDiff,
                                   vec3<f32>(0.0), vec3<f32>(1.0));
            let litColor   = vec4<f32>(litRgb, face.baseColor.a);

            // ── 6. Atomic compaction ──────────────────────────────────────────
            // Surviving faces are written contiguously from index 0.
            // Slot is the face's position in the compacted output array.
            let slot = atomicAdd(&visibleCount, 1u);

            var result: TransformedFace;
            result.s0         = s0;
            result.s1         = s1;
            result.s2         = s2;
            result.s3         = s3;
            result.s4         = vec2<f32>(0.0);
            result.s5         = vec2<f32>(0.0);
            result.vertexCount = vc;
            result._p0        = 0u;
            result._p1        = 0u;
            result._p2        = 0u;
            result.litColor   = litColor;
            result.depthKey   = depthKey;
            result.faceIndex  = face.faceIndex;
            result._p3        = 0u;
            result._p4        = 0u;
            transformed[slot] = result;
        }
    """.trimIndent()
}
