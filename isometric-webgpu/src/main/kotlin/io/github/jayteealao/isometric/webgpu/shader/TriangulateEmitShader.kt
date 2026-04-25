package io.github.jayteealao.isometric.webgpu.shader

import io.github.jayteealao.isometric.webgpu.triangulation.RenderCommandTriangulator

/**
 * WGSL compute shader for the M5 triangulate-and-emit pass.
 *
 * Reads the M4 sorted [SortKey] array (back-to-front order) and for each visible
 * face triangulates the projected screen-space polygon into a flat `array<u32>`
 * vertex buffer consumed by the render pass. One GPU thread per sorted entry.
 *
 * ## Algorithm
 *
 * 1. Each thread reads `sortedKeys[i]`. Threads are assigned a **fixed stride** slot
 *    in the vertex buffer: `base = i * MAX_VERTICES_PER_FACE`.
 * 2. Sentinel entries (`originalIndex == 0xFFFFFFFF`) and culled faces write
 *    [MAX_VERTICES_PER_FACE] degenerate (zero-area) vertices so the render pass
 *    draws the full `paddedCount × MAX_VERTICES_PER_FACE` vertex range without gaps.
 * 3. Visible entries fetch `transformed[originalIndex]`, ear-clip-triangulate up to
 *    `triCount = vc−2` triangles into the slot, and fill the remaining
 *    `MAX_VERTICES_PER_FACE − emitted×3` vertex positions with degenerates.
 * 4. **Ear-clipping** (post `webgpu-ngon-faces` I-02 fix): classic O(n²) algorithm
 *    over a doubly-linked list of activeCount vertices (`nextIdx`/`prevIdx`). Each
 *    iteration finds an "ear" — a vertex whose triangle (prev, current, next) is
 *    convex w.r.t. the polygon's signed-area-derived winding AND empty of other
 *    activeCount vertices — emits it, and unlinks the ear vertex. Convex polygons hit
 *    an ear at the first vertex every iteration (O(n) inner emptiness check) and
 *    so degrade gracefully to fan performance; only non-convex faces (Stairs
 *    zigzag at stepCount ≥ 3) pay the full O(n²) cost.
 * 5. Screen-space to NDC: `ndcX = (sx / viewportWidth) * 2 - 1`,
 *    `ndcY = 1 - (sy / viewportHeight) * 2`. Matches [RenderCommandTriangulator] exactly.
 *    The y-flip can invert per-face winding in NDC, so the convex test uses a
 *    `desiredSign` derived from the per-face polygon signed area rather than a
 *    hardcoded CCW/CW assumption.
 *
 * ## UV coordinates (variable-stride offset+length indirection)
 *
 * Per-vertex UVs use a two-binding indirection:
 * - `uvFaceTable` (binding 7) — `array<vec2<u32>>`; `table[originalIndex] = (offsetPairs, vertCount)`.
 * - `uvPool` (binding 6) — flat `array<vec2<f32>>` holding every face's UV pairs concatenated.
 *
 * Per-face fetch: `let entry = uvFaceTable[originalIndex]; uv[k] = uvPool[entry.x + k]`
 * for `k in 0..entry.y - 1`. This replaces the pre-`webgpu-ngon-faces` fixed 48-byte slot
 * (max 6 UV pairs) and lets any face with `faceVertexCount <= 24` render full UVs.
 *
 * The user transform is applied per-vertex (`rawUV = userMatrix * vec3(baseUV, 1.0)`)
 * without `fract()`. The atlas region from `sceneUvRegions` (binding 5) is emitted as a
 * flat `vec4<f32>` vertex attribute; the fragment shader applies
 * `atlasUV = fract(rawUV) * atlasScale + atlasOffset` per-fragment. Applying `fract()`
 * per-fragment (rather than per-vertex) is required for correct tiling: per-vertex fract
 * collapses all corners with UV=1.0 to UV=0.0, making the entire face sample one texel.
 * Faces without UV data receive default quad UVs `(0,0)(1,0)(1,1)(0,1)` (4 pairs) from
 * the CPU-side packer fallback.
 *
 * ## Why fixed stride (no atomicAdd)?
 *
 * `atomicAdd(&vertexCursor, vertCount)` with ~290 concurrent threads contending on a
 * single GPU-side atomic causes a GPU watchdog (TDR) on Adreno 750 (Snapdragon 8 Gen 3),
 * resulting in `VK_ERROR_DEVICE_LOST`. The fixed-stride approach eliminates all atomics
 * from M5 — each thread writes to a deterministic, non-overlapping range.
 *
 * The draw call always submits `paddedCount × MAX_VERTICES_PER_FACE` vertices.
 * Degenerate triangles (three vertices at the same position) produce no visible pixels
 * and are discarded by the rasterizer at negligible cost.
 *
 * ## Ear-clip emit (post `webgpu-ngon-faces` I-02 fix)
 *
 * The emit path runs ear-clipping over up to 24 vertices, producing up to 22
 * triangles per face. Per iteration: scan up to `activeCount` vertices for an ear,
 * emit `(prev, ear, next)` in the polygon's natural winding, and remove the ear
 * vertex from the linked list. Slots after `emitted * 3` are filled with
 * degenerates. The pre-fix fan-from-`s[0]` approach was a special case that
 * silently broke on non-convex polygons (Stairs zigzag side at stepCount ≥ 3,
 * which produced a smooth diagonal slope instead of the stepped silhouette).
 *
 * ## Vertex buffer layout
 *
 * Vertices are written as a flat `array<u32>` (14 × u32 = 56 bytes per vertex) to
 * avoid WGSL storage-buffer alignment constraints that would otherwise insert padding
 * between `position: vec2<f32>` (offset 0) and `color: vec4<f32>` (offset 8), since
 * `vec4` requires 16-byte alignment in WGSL structs but the render pipeline expects
 * it at offset 8.
 *
 * ```
 *  u32 offset  bytes  field
 *       0-1      8    position      vec2<f32>  (vertex attribute location 0)
 *       2-5     16    color         vec4<f32>  (vertex attribute location 1)
 *       6-7      8    rawUV         vec2<f32>  (vertex attribute location 2, pre-atlas UV)
 *      8-11     16    atlasRegion   vec4<f32>  (vertex attribute location 3, flat: scaleU,scaleV,offsetU,offsetV)
 *        12      4    textureIndex  u32        (vertex attribute location 4)
 *        13      4    _padding      u32
 * ```
 *
 * ## Bindings
 *
 * ```wgsl
 * @group(0) @binding(0) var<storage, read>       transformed:      array<TransformedFace>
 * @group(0) @binding(1) var<storage, read>        sortedKeys:       array<SortKey>
 * @group(0) @binding(2) var<storage, read_write>  vertices:         array<u32>
 * @group(0) @binding(3) var<uniform>              params:           EmitUniforms
 * @group(0) @binding(4) var<storage, read>        sceneTexIndices:  array<u32>
 * @group(0) @binding(5) var<storage, read>        sceneUvRegions:   array<UvRegion>
 * @group(0) @binding(6) var<storage, read>        uvPool:           array<vec2<f32>>
 * @group(0) @binding(7) var<storage, read>        uvFaceTable:      array<vec2<u32>>
 * ```
 *
 * ## Struct compatibility
 *
 * `TransformedFace` here **must** match `TransformCullLightShader.WGSL` byte-for-byte.
 * `SortKey` here **must** match `GPUBitonicSortShader.WGSL` byte-for-byte.
 */
internal object TriangulateEmitShader {

    /** Compute shader entry point name. */
    const val ENTRY_POINT = "triangulateEmit"

    /** Threads per workgroup. */
    const val WORKGROUP_SIZE = 256

    /**
     * Bytes per vertex in the output buffer.
     * Delegates to [RenderCommandTriangulator.BYTES_PER_VERTEX] — the single authoritative
     * source also used by [GpuRenderPipeline]'s vertex buffer stride declaration.
     */
    const val BYTES_PER_VERTEX = RenderCommandTriangulator.BYTES_PER_VERTEX

    /**
     * Maximum triangles producible from a single face.
     *
     * Post `webgpu-ngon-faces`: a 24-vertex face produces at most
     * `vertexCount - 2 = 22` triangles via ear-clipping. Used to size the output
     * vertex buffer.
     */
    const val MAX_TRIANGLES_PER_FACE = 22

    /** Maximum vertices a single face can emit into the vertex buffer. */
    const val MAX_VERTICES_PER_FACE = MAX_TRIANGLES_PER_FACE * 3  // = 66

    val WGSL: String = """
        // ── TransformedFace ───────────────────────────────────────────────────────
        // 240 bytes per face.  Must match TransformCullLightShader.WGSL exactly.
        //
        //  offset  size  field
        //    0-191 192   s: array<vec2<f32>, 24>   (element stride 8)
        //  192-195   4   vertexCount (u32)
        //  196-207  12   _p0..p2 padding (3 × u32)
        //  208-223  16   litColor (vec4<f32>)
        //  224-227   4   depthKey (f32)
        //  228-231   4   faceIndex (u32)
        //  232-235   4   visible (u32)   ← 1 = passed cull, 0 = culled
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

        // ── SortKey ───────────────────────────────────────────────────────────────
        // 16 bytes per entry.  Must match GPUBitonicSortShader.WGSL exactly.
        struct SortKey {
            depth:         f32,
            originalIndex: u32,
            pad0:          u32,
            pad1:          u32,
        }

        // ── EmitUniforms ──────────────────────────────────────────────────────────
        // Rebuilt in GpuTriangulateEmitPipeline.ensureBuffers on any change.
        struct EmitUniforms {
            viewportWidth:  u32,
            viewportHeight: u32,
            paddedCount:    u32,
            _pad:           u32,
        }

        // ── UvRegion ──────────────────────────────────────────────────────────────
        // 40 bytes per entry: user transform (mat3x2 = 24 bytes) + atlas region (2×vec2 = 16 bytes).
        // CPU layout: [col0.x, col0.y, col1.x, col1.y, col2.x, col2.y, scaleU, scaleV, offsetU, offsetV]
        // Apply as: atlasUV = fract(userMatrix * vec3(baseUV, 1.0)) * atlasScale + atlasOffset
        // SYNC: struct size must equal SceneDataLayout.UV_REGION_STRIDE (40 bytes) and UvRegionPacker.pack() (10 floats)
        struct UvRegion {
            userMatrix  : mat3x2<f32>,
            atlasScale  : vec2<f32>,
            atlasOffset : vec2<f32>,
        }

        // TransformedFace is 240 bytes; bind as a typed struct array so the shader can
        // read variable-length `s: array<vec2<f32>, 24>` directly without byte-offset
        // arithmetic across the flat vec4 payload.
        @group(0) @binding(0) var<storage, read>       transformed: array<TransformedFace>;
        @group(0) @binding(1) var<storage, read>        sortedKeys:  array<SortKey>;
        // Flat u32 array: 14 u32 per vertex (56 bytes), written via bitcast<u32>(f32) to
        // preserve the render pipeline's vertex layout without WGSL struct-alignment gaps.
        @group(0) @binding(2) var<storage, read_write>  vertices:    array<u32>;
        @group(0) @binding(3) var<uniform>              params:      EmitUniforms;
        // Compact per-face texture index buffer, indexed by originalIndex.
        @group(0) @binding(4) var<storage, read>        sceneTexIndices: array<u32>;
        // Compact per-face UV region buffer, indexed by originalIndex.
        // Each UvRegion stores the user TextureTransform and atlas sub-region separately.
        // Apply as: fract(userMatrix * vec3(baseUV, 1.0)) * atlasScale + atlasOffset
        @group(0) @binding(5) var<storage, read>        sceneUvRegions: array<UvRegion>;
        // Flat UV pool: concatenated per-face UV pairs, indexed via uvFaceTable entries.
        @group(0) @binding(6) var<storage, read>        uvPool:      array<vec2<f32>>;
        // Per-face offset+count table: entry[i] = (offsetPairs, vertCount) for commands[i].
        @group(0) @binding(7) var<storage, read>        uvFaceTable: array<vec2<u32>>;

        // Writes one vertex at flat u32 offset [base] (base must be a multiple of 14).
        // Vertex layout (56 bytes, 14 × u32, matches GpuRenderPipeline vertex attributes):
        //   u32[0-1]  position     vec2<f32>  (location 0, offset  0)
        //   u32[2-5]  color        vec4<f32>  (location 1, offset  8)
        //   u32[6-7]  rawUV        vec2<f32>  (location 2, offset 24) — pre-atlas UV after user matrix
        //   u32[8-11] atlasRegion  vec4<f32>  (location 3, offset 32) — flat: scaleU,scaleV,offsetU,offsetV
        //   u32[12]   textureIndex u32        (location 4, offset 48)
        //   u32[13]   _padding     u32
        fn writeVertex(base: u32, x: f32, y: f32, r: f32, g: f32, b: f32, a: f32,
                       u: f32, v: f32, asU: f32, asV: f32, aoU: f32, aoV: f32, texIdx: u32) {
            vertices[base + 0u]  = bitcast<u32>(x);
            vertices[base + 1u]  = bitcast<u32>(y);
            vertices[base + 2u]  = bitcast<u32>(r);
            vertices[base + 3u]  = bitcast<u32>(g);
            vertices[base + 4u]  = bitcast<u32>(b);
            vertices[base + 5u]  = bitcast<u32>(a);
            vertices[base + 6u]  = bitcast<u32>(u);
            vertices[base + 7u]  = bitcast<u32>(v);
            vertices[base + 8u]  = bitcast<u32>(asU);
            vertices[base + 9u]  = bitcast<u32>(asV);
            vertices[base + 10u] = bitcast<u32>(aoU);
            vertices[base + 11u] = bitcast<u32>(aoV);
            vertices[base + 12u] = texIdx;
            vertices[base + 13u] = 0u;
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn triangulateEmit(@builtin(global_invocation_id) gid: vec3<u32>) {
            let i = gid.x;
            if (i >= params.paddedCount) { return; }

            // Fixed-stride slot: each sort entry owns exactly MAX_VERTICES_PER_FACE=66
            // vertex positions (22 triangles × 3 verts). No atomicAdd — no contention,
            // no Adreno TDR.
            let base = i * ${MAX_VERTICES_PER_FACE}u;
            let key = sortedKeys[i];
            if (key.originalIndex == 0xFFFFFFFFu) {
                for (var j = 0u; j < ${MAX_VERTICES_PER_FACE}u; j = j + 1u) {
                    writeVertex((base + j) * 14u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
                }
                return;
            }

            let wF = f32(params.viewportWidth);
            let hF = f32(params.viewportHeight);

            // Typed struct read — simpler than flat vec4 offset math now that the struct
            // grew from 96 bytes (6 vec4s) to 240 bytes (15 vec4s).
            let face = transformed[key.originalIndex];
            let vertexCount = face.vertexCount;
            if (face.visible == 0u || vertexCount < 3u) {
                for (var j = 0u; j < ${MAX_VERTICES_PER_FACE}u; j = j + 1u) {
                    writeVertex((base + j) * 14u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
                }
                return;
            }

            // Per-face texture index and UV region from compact buffers
            let texIdx   = sceneTexIndices[key.originalIndex];
            let uvRegion = sceneUvRegions[key.originalIndex];
            let asU = uvRegion.atlasScale.x;
            let asV = uvRegion.atlasScale.y;
            let aoU = uvRegion.atlasOffset.x;
            let aoV = uvRegion.atlasOffset.y;

            let r = face.litColor.x;
            let g = face.litColor.y;
            let b = face.litColor.z;
            let a = face.litColor.w;

            // ── Indirect UV lookup (offset + count table) ─────────────────────
            // entry.x = offsetPairs into flat uvPool; entry.y = vertCount for this face.
            // vertCount may differ from face.vertexCount only for the uvCoords==null
            // fallback (always 4). In that case we repeat default-quad UVs; geometry
            // still triangulates per vertexCount.
            let uvEntry      = uvFaceTable[key.originalIndex];
            let uvBaseOffset = uvEntry.x;
            let uvCount      = uvEntry.y;
            // H-1: safeUvCount guards against uvCount==0, which would cause a
            // division-by-zero in the modulo below. In practice uvCount==0 is
            // unreachable: UvFaceTablePacker.effectiveVertCount returns at least 4
            // (the default-quad fallback path). The max(…,1u) costs nothing on the
            // GPU hot-path and prevents a shader hard-fault if the invariant is ever
            // violated (e.g. by a future packer change that skips the fallback path).
            let safeUvCount  = max(uvCount, 1u);

            // Precompute NDC + transformed UV per vertex (0..vertexCount-1).
            // Stack arrays are default-zero-initialized; slots beyond vertexCount
            // are never read during the triangle loop below.
            var ndc: array<vec2<f32>, 24>;
            var uvs: array<vec2<f32>, 24>;
            for (var k: u32 = 0u; k < vertexCount; k = k + 1u) {
                let sp = face.s[k];
                ndc[k] = vec2<f32>((sp.x / wF) * 2.0 - 1.0, 1.0 - (sp.y / hF) * 2.0);
                // If the face used the default-quad fallback (uvCount == 4 and
                // vertexCount may be larger), clamp lookup so we always cycle within
                // the 4 fallback pairs rather than reading past the pool entry.
                let uvIdx = select(k, k % safeUvCount, safeUvCount == 4u && vertexCount > 4u);
                let pair = uvPool[uvBaseOffset + uvIdx];
                let h = uvRegion.userMatrix * vec3<f32>(pair.x, pair.y, 1.0);
                uvs[k] = vec2<f32>(h.x, h.y);
            }

            // ── Ear-clip triangulation (non-convex-safe, for-loop form) ───────
            // Replaces the prior fan-from-s[0] which was correct only for convex
            // polygons. Stairs zigzag side faces (stepCount >= 3) are non-convex
            // and need a triangulation that respects the polygon silhouette.
            //
            // Classic ear-clipping over a doubly-linked list of activeCount vertices
            // (`nextIdx`/`prevIdx`). Bounded `for` loops in all three positions
            // (outer / scan / point-in-triangle) so Tint emits structured SPIR-V
            // control flow with no ambiguity. Convex polygons (cylinder caps,
            // knot quads, prism quads) find an ear at the first scan position
            // every iteration, so they pay only the O(n) inner emptiness check;
            // only non-convex faces incur the full O(n²) cost. Worst-case bound:
            // 24 outer × 24 scan × 24 inner = 13824 ops per face.
            let triCount = vertexCount - 2u;

            // Polygon signed area determines NDC-space winding. The y-flip in the
            // projection above can invert per-face winding, so we don't hardcode
            // CCW/CW; we test convexity against this face's actual orientation.
            var signedArea2: f32 = 0.0;
            for (var sk: u32 = 0u; sk < vertexCount; sk = sk + 1u) {
                let skNext = select(sk + 1u, 0u, sk + 1u == vertexCount);
                let pa = ndc[sk];
                let pb = ndc[skNext];
                signedArea2 = signedArea2 + (pa.x * pb.y - pb.x * pa.y);
            }
            let desiredSign: f32 = select(-1.0, 1.0, signedArea2 > 0.0);

            // Initialize circular doubly-linked list of activeCount vertices.
            var nextIdx: array<u32, 24>;
            var prevIdx: array<u32, 24>;
            for (var ik: u32 = 0u; ik < vertexCount; ik = ik + 1u) {
                nextIdx[ik] = select(ik + 1u, 0u, ik + 1u == vertexCount);
                prevIdx[ik] = select(ik - 1u, vertexCount - 1u, ik == 0u);
            }

            var emitted: u32 = 0u;
            var current: u32 = 0u;
            var activeCount: u32 = vertexCount;
            var done: bool = false;

            // Outer for-loop: at most triCount = vertexCount-2 ears to emit.
            for (var emitIter: u32 = 0u; emitIter < triCount; emitIter = emitIter + 1u) {
                if (done) { break; }

                // Scan up to `activeCount` vertices to find an ear starting at `current`.
                var foundEar: bool = false;
                var earIdx: u32 = 0u;
                var scanIdx: u32 = current;

                for (var scanCount: u32 = 0u; scanCount < vertexCount; scanCount = scanCount + 1u) {
                    if (scanCount >= activeCount) { break; }

                    let pIdx0 = prevIdx[scanIdx];
                    let nIdx0 = nextIdx[scanIdx];
                    let A = ndc[pIdx0];
                    let B = ndc[scanIdx];
                    let C = ndc[nIdx0];

                    // Convex test in the polygon's winding (sign of (B-A) × (C-A)).
                    let cross = (B.x - A.x) * (C.y - A.y) - (B.y - A.y) * (C.x - A.x);
                    var isEar: bool = (cross * desiredSign) > 0.0;

                    // Emptiness test: no other activeCount vertex inside (A, B, C).
                    // Walk the linked list from nextIdx[nIdx0] until we reach pIdx0.
                    if (isEar) {
                        var pitIdx: u32 = nextIdx[nIdx0];
                        for (var pitStep: u32 = 0u; pitStep < vertexCount; pitStep = pitStep + 1u) {
                            if (pitIdx == pIdx0) { break; }
                            let P = ndc[pitIdx];
                            let s1 = (P.x - B.x) * (A.y - B.y) - (A.x - B.x) * (P.y - B.y);
                            let s2 = (P.x - C.x) * (B.y - C.y) - (B.x - C.x) * (P.y - C.y);
                            let s3 = (P.x - A.x) * (C.y - A.y) - (C.x - A.x) * (P.y - A.y);
                            let hasNeg: bool = (s1 < 0.0) || (s2 < 0.0) || (s3 < 0.0);
                            let hasPos: bool = (s1 > 0.0) || (s2 > 0.0) || (s3 > 0.0);
                            if (!(hasNeg && hasPos)) {
                                isEar = false;
                                break;
                            }
                            pitIdx = nextIdx[pitIdx];
                        }
                    }

                    if (isEar) {
                        foundEar = true;
                        earIdx = scanIdx;
                        break;
                    }

                    scanIdx = nextIdx[scanIdx];
                }

                if (!foundEar) {
                    done = true;
                    continue;
                }

                let pIdx = prevIdx[earIdx];
                let nIdx = nextIdx[earIdx];

                // Emit triangle (pIdx, earIdx, nIdx) in the polygon's natural winding.
                let outBase = (base + emitted * 3u) * 14u;
                let pNdc = ndc[pIdx];   let pUv = uvs[pIdx];
                let eNdc = ndc[earIdx]; let eUv = uvs[earIdx];
                let nNdc = ndc[nIdx];   let nUv = uvs[nIdx];
                writeVertex(outBase + 0u  * 14u, pNdc.x, pNdc.y, r, g, b, a, pUv.x, pUv.y, asU, asV, aoU, aoV, texIdx);
                writeVertex(outBase + 1u  * 14u, eNdc.x, eNdc.y, r, g, b, a, eUv.x, eUv.y, asU, asV, aoU, aoV, texIdx);
                writeVertex(outBase + 2u  * 14u, nNdc.x, nNdc.y, r, g, b, a, nUv.x, nUv.y, asU, asV, aoU, aoV, texIdx);
                emitted = emitted + 1u;

                // Remove `earIdx` from the activeCount list and advance to its successor.
                nextIdx[pIdx] = nIdx;
                prevIdx[nIdx] = pIdx;
                activeCount = activeCount - 1u;
                current = nIdx;
            }

            // Fill remaining slots (from emitted*3u onwards) with degenerates.
            // Using `emitted` rather than `triCount` keeps the buffer clean if
            // ear-search bailed early on a degenerate/self-intersecting polygon
            // (should not happen for UvGenerator outputs).
            let firstDegen = emitted * 3u;
            for (var j = firstDegen; j < ${MAX_VERTICES_PER_FACE}u; j = j + 1u) {
                writeVertex((base + j) * 14u, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0xFFFFFFFFu);
            }
        }
    """.trimIndent()
}
