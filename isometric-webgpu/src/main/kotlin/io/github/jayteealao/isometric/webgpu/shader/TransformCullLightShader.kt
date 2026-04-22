package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader for the fused Transform + Cull + Light pass.
 *
 * One GPU thread per face. Each invocation:
 * 1. Projects all 3D vertices to 2D screen space via the isometric projection matrix.
 * 2. Back-face culls: discards faces whose signed-area in screen space is positive.
 * 3. Frustum culls: discards faces whose AABB lies entirely outside the viewport.
 * 4. Computes a depth sort key (`x + y − 2z` averaged over 3D vertices).
 * 5. Computes diffuse lighting using the pre-computed face normal from [FaceData].
 * 6. **Always** writes the result to `transformed[i]` (thread's own index) with
 *    `visible = 1u` if the face passed culling, `visible = 0u` otherwise.
 *
 * ## Why no atomicAdd compaction?
 *
 * The original design used `let slot = atomicAdd(&visibleCount, 1u); transformed[slot] = result`
 * to compact visible faces. This scatter write — combined with M5's scatter read via sort-key
 * `originalIndex` — triggers an Adreno 750 GPU hang (TDR / `VK_ERROR_DEVICE_LOST`) because the
 * Adreno driver does not correctly honour compute→compute memory barriers within a single
 * `VkCommandBuffer` when both passes access the same buffer via non-sequential addresses.
 *
 * Writing to `transformed[i]` (each thread writes its own slot) is sequential and safe.
 * The `visible` field lets downstream passes filter culled entries without an atomic counter.
 *
 * ## Struct layouts
 *
 * All structs must be kept in sync with their Kotlin counterparts:
 * - `FaceData` ← [SceneDataPacker] / [SceneDataLayout] (448 bytes, stride 448)
 * - `TransformedFace` ← [SceneDataLayout.TRANSFORMED_FACE_BYTES] (240 bytes, stride 240)
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
        // 448 bytes per face.  Matches SceneDataPacker byte-for-byte.
        //
        //  offset  size  field
        //    0-383 384   v: array<vec3<f32>, 24>  (element stride 16)
        //  384-387   4   vertexCount (u32)
        //  388-399  12   _f0..f2 padding (3 × u32) → 16-byte align for next vec4
        //  400-415  16   baseColor (vec4<f32>, RGBA in [0,1])
        //  416-427  12   normal (vec3<f32>, pre-normalised)
        //  428-431   4   textureIndex (u32; 0xFFFFFFFF = no texture)
        //  432-435   4   faceIndex (u32)
        //  436-447  12   _p0..p2 padding (3 × u32) → struct stride = 448
        struct FaceData {
            v:           array<vec3<f32>, 24>,
            vertexCount: u32,
            _f0: u32, _f1: u32, _f2: u32,
            baseColor:   vec4<f32>,
            normal:      vec3<f32>,
            textureIndex: u32,
            faceIndex:   u32,
            _p0: u32, _p1: u32, _p2: u32,
        }

        // ── TransformedFace ───────────────────────────────────────────────────────
        // 240 bytes per face.  Matches SceneDataLayout.TRANSFORMED_FACE_BYTES.
        //
        //  offset  size  field
        //    0-191 192   s: array<vec2<f32>, 24>  (element stride 8)
        //  192-195   4   vertexCount (u32)
        //  196-207  12   _p0..p2 padding (3 × u32) → 16-byte align for litColor
        //  208-223  16   litColor (vec4<f32>, RGBA in [0,1])
        //  224-227   4   depthKey (f32)
        //  228-231   4   faceIndex (u32)
        //  232-235   4   visible (u32)   ← 1 = passed cull tests, 0 = culled
        //  236-239   4   _p4 (u32)
        struct TransformedFace {
            s: array<vec2<f32>, 24>,
            vertexCount: u32,
            _p0: u32,
            _p1: u32,
            _p2: u32,
            litColor: vec4<f32>,
            depthKey:  f32,
            faceIndex: u32,
            visible:   u32,
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

        @group(0) @binding(0) var<storage, read>       scene:       array<FaceData>;
        @group(0) @binding(1) var<storage, read_write>  transformed: array<TransformedFace>;
        @group(0) @binding(2) var<uniform>              uniforms:    SceneUniforms;

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

            // ── 1. Project all vertices + compute AABB + depth accumulator ───
            // Single pass: project, fold into min/max for frustum AABB, and sum
            // (x + y − 2z) for the centroid depth key. All three are accumulated
            // in one loop so vertices beyond vc are never touched.
            var screen: array<vec2<f32>, 24>;
            var minX: f32 =  1e30;
            var maxX: f32 = -1e30;
            var minY: f32 =  1e30;
            var maxY: f32 = -1e30;
            var depthSum: f32 = 0.0;

            for (var k: u32 = 0u; k < vc; k = k + 1u) {
                let p  = face.v[k];
                let sp = projectPoint(p);
                screen[k] = sp;
                minX = min(minX, sp.x);
                maxX = max(maxX, sp.x);
                minY = min(minY, sp.y);
                maxY = max(maxY, sp.y);
                depthSum = depthSum + (p.x + p.y - 2.0 * p.z);
            }

            // ── 2. Back-face cull ─────────────────────────────────────────────
            // Signed area of the first triangle (s0, s1, s2) in screen space.
            // Matches IsometricProjection.cullPath: area > 0 → back-facing → cull.
            let s0 = screen[0];
            let s1 = screen[1];
            let s2 = screen[2];
            let area = s0.x * s1.y + s1.x * s2.y + s2.x * s0.y
                     - s1.x * s0.y - s2.x * s1.y - s0.x * s2.y;
            let backFaced = area > 0.0;

            // ── 3. Frustum cull (AABB vs viewport) ───────────────────────────
            let vw = f32(uniforms.viewport.x);
            let vh = f32(uniforms.viewport.y);
            let frustumCulled = maxX < 0.0 || minX > vw || maxY < 0.0 || minY > vh;

            let culled = backFaced || frustumCulled;

            // ── 4. Depth sort key ─────────────────────────────────────────────
            let depthKey = depthSum / f32(vc);

            // ── 5. Diffuse lighting ───────────────────────────────────────────
            let lightDir   = uniforms.lightDirAndDiff.xyz;
            let colorDiff  = uniforms.lightDirAndDiff.w;
            let brightness = max(dot(face.normal, lightDir), 0.0);
            let tinted     = face.baseColor.rgb * uniforms.lightColor.rgb;
            let litRgb     = clamp(tinted + brightness * colorDiff,
                                   vec3<f32>(0.0), vec3<f32>(1.0));
            let litColor   = vec4<f32>(litRgb, face.baseColor.a);

            // ── 6. Sequential write ───────────────────────────────────────────
            // Each thread writes transformed[i] (its own slot) — no scatter, no
            // atomic, safe on Adreno. Unused slots beyond vc retain zero (default
            // init of array<vec2<f32>,24> on the stack).
            var result: TransformedFace;
            for (var k: u32 = 0u; k < 24u; k = k + 1u) {
                result.s[k] = select(vec2<f32>(0.0), screen[k], k < vc);
            }
            result.vertexCount = vc;
            result._p0         = 0u;
            result._p1         = 0u;
            result._p2         = 0u;
            result.litColor    = litColor;
            result.depthKey    = depthKey;
            result.faceIndex   = face.faceIndex;
            result.visible     = select(0u, 1u, !culled);
            result._p4         = 0u;
            transformed[i]     = result;
        }
    """.trimIndent()
}
