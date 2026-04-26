package io.github.jayteealao.isometric.webgpu.pipeline

/**
 * Layout constants for the `FaceData` and `TransformedFace` GPU storage buffer structs.
 *
 * These values must match the WGSL struct definitions in `TransformCullLightShader` exactly.
 * Any change here requires a matching change to the shader.
 *
 * ## FaceData memory layout (448 bytes per face)
 *
 * Post `webgpu-ngon-faces`: supports up to [MAX_FACE_VERTICES] = 24 vertices per face.
 * WGSL's `array<vec3<f32>, 24>` element stride is 16 (vec3 pads to vec4 in storage arrays),
 * so the vertex block occupies exactly `24 × 16 = 384` bytes.
 *
 * ```
 *  offset  size  field
 *    0    384   v: array<vec3<f32>, 24>  (element stride 16, xyz packed at +0/+4/+8, +12 pad)
 *  384      4   vertexCount (u32)
 *  388     12   _f0, _f1, _f2  (padding to 16-byte align for vec4 that follows)
 *  400     16   baseColor  (vec4<f32>, RGBA in [0,1])
 *  416     12   normal     (vec3<f32>)
 *  428      4   textureIndex (u32; NO_TEXTURE = 0xFFFFFFFF)
 *  432      4   faceIndex   (u32)
 *  436     12   _pad        (3 × u32 — struct stride multiple of 16)
 * 448  →  448
 * ```
 *
 * ## TransformedFace memory layout (240 bytes per face)
 *
 * `s: array<vec2<f32>, 24>` packs tightly at 8-byte stride → 192 bytes for screen coords.
 *
 * ```
 *  offset  size  field
 *    0    192   s: array<vec2<f32>, 24>  (element stride 8)
 *  192      4   vertexCount (u32)
 *  196     12   _p0, _p1, _p2 (pad to 16-byte align for vec4)
 *  208     16   litColor (vec4<f32>)
 *  224      4   depthKey (f32)
 *  228      4   faceIndex (u32)
 *  232      4   visible (u32)  ← 1 = passed cull, 0 = culled
 *  236      4   _p4 (u32)
 * 240  →  240
 * ```
 */
internal object SceneDataLayout {
    /**
     * Maximum vertices a single face can carry.
     *
     * Matches `RenderCommand.faceVertexCount in 3..24` validator bound. Shared by
     * [FaceData] / [TransformedFace] struct sizes, the packer loop, and the WGSL
     * shader's per-face vertex array lengths.
     */
    const val MAX_FACE_VERTICES = 24

    /** Bytes per FaceData struct in the GPU scene-data storage buffer. */
    const val FACE_DATA_BYTES = 448

    /** Bytes per TransformedFace struct in the GPU intermediate buffer. */
    const val TRANSFORMED_FACE_BYTES = 240

    /**
     * Bytes per entry in the per-face UV offset+count table (binding 7).
     *
     * One `vec2<u32>` per face: `(offsetPairs: u32, vertCount: u32)`.
     * - `offsetPairs` — index into the flat UV pool (in vec2-units, not bytes).
     * - `vertCount`   — number of UV pairs for this face (1..[MAX_FACE_VERTICES], or 4
     *   for the default-quad fallback).
     */
    const val UV_TABLE_STRIDE = 8

    /** Bytes per UV pool entry (binding 6) — one `vec2<f32>` = 8 bytes. */
    const val UV_POOL_STRIDE = 8

    /**
     * Bytes per entry in the per-face UV region buffer.
     *
     * Each entry is one `UvRegion` WGSL struct:
     * - `userMatrix : mat3x2<f32>` — user [TextureTransform] without atlas composition (24 bytes)
     * - `atlasScale : vec2<f32>`   — atlas sub-region UV scale (8 bytes)
     * - `atlasOffset : vec2<f32>`  — atlas sub-region UV offset (8 bytes)
     *
     * Total = 40 bytes, alignOf = 8.
     *
     * The GPU shader applies `fract(userMatrix × vec3(baseUV,1))` before the atlas mapping,
     * so tiling works correctly within atlas sub-regions.
     */
    const val UV_REGION_STRIDE = 40

    /**
     * Sentinel value for [FaceData.textureIndex] when no texture is bound.
     * Equals `0xFFFFFFFF` as an unsigned 32-bit integer, stored as a signed Kotlin [Int].
     */
    const val NO_TEXTURE: Int = -1 // 0xFFFFFFFF interpreted as u32 in WGSL
}
