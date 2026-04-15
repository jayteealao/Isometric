package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.shader.TextureTransform
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Packs a `UvRegion` entry (user [TextureTransform] + atlas region) into a
 * [ByteBuffer] for the M5 emit shader's `sceneUvRegions` binding.
 *
 * ## Buffer layout (10 floats = 40 bytes, matching the WGSL `UvRegion` struct)
 *
 * ```
 * floats 0–5  userMatrix  mat3x2<f32> (column-major, user transform only — no atlas)
 *               col0.x, col0.y,   ← u-axis (scaled and rotated)
 *               col1.x, col1.y,   ← v-axis (scaled and rotated)
 *               col2.x, col2.y    ← translation (center-based user offset)
 * floats 6–7  atlasScale   vec2<f32>  (U and V scale of the atlas sub-region)
 * floats 8–9  atlasOffset  vec2<f32>  (U and V origin of the atlas sub-region)
 * ```
 *
 * ## Application in WGSL
 *
 * ```wgsl
 * let localUV = uvRegion.userMatrix * vec3<f32>(baseU, baseV, 1.0);
 * let atlasUV = fract(localUV) * uvRegion.atlasScale + uvRegion.atlasOffset;
 * ```
 *
 * `fract()` wraps localUV back into [0,1) before atlas mapping so that tiling
 * (scaleU > 1) wraps within the atlas sub-region rather than bleeding into adjacent
 * tiles or returning to the atlas origin.
 *
 * ## Extraction rationale
 *
 * Extracted from [GpuTextureManager] so the math can be unit-tested on the JVM
 * without any Android context or GPU dependencies.
 */
internal object UvRegionPacker {

    /**
     * Write 10 floats (40 bytes) into [buf]: user transform matrix followed by atlas region.
     *
     * For [TextureTransform.IDENTITY] a fast path skips all trig and writes the identity
     * matrix directly. The atlas region is always written separately, never composed into
     * the user matrix.
     *
     * @param buf         CPU staging buffer positioned at the start of the 40-byte slot.
     * @param atlasScaleU Atlas sub-region U scale (width fraction of the atlas).
     * @param atlasScaleV Atlas sub-region V scale (height fraction of the atlas).
     * @param atlasOffsetU Atlas sub-region U origin offset.
     * @param atlasOffsetV Atlas sub-region V origin offset.
     * @param transform   User-defined [TextureTransform] — written as-is, not composed with atlas.
     */
    fun pack(
        buf: ByteBuffer,
        atlasScaleU: Float,
        atlasScaleV: Float,
        atlasOffsetU: Float,
        atlasOffsetV: Float,
        transform: TextureTransform,
    ) {
        if (transform == TextureTransform.IDENTITY) {
            // Fast path: identity user matrix (no trig), then atlas region
            buf.putFloat(1f); buf.putFloat(0f)   // col0
            buf.putFloat(0f); buf.putFloat(1f)   // col1
            buf.putFloat(0f); buf.putFloat(0f)   // col2
        } else {
            // Full path: user transform only — rotation around UV center (0.5, 0.5).
            // Atlas region is written separately below; never multiplied in here.
            val thetaRad = transform.rotationDegrees * PI.toFloat() / 180f
            val cosA = cos(thetaRad)
            val sinA = sin(thetaRad)
            val su = transform.scaleU
            val sv = transform.scaleV
            val du = transform.offsetU
            val dv = transform.offsetV

            // User transform columns (center-based: rotation pivot = (0.5, 0.5)):
            val uc0x = su * cosA;   val uc0y = su * sinA    // col0
            val uc1x = -sv * sinA;  val uc1y = sv * cosA    // col1
            val tx = 0.5f * (1f - uc0x - uc1x) + du         // col2.x
            val ty = 0.5f * (1f - uc0y - uc1y) + dv         // col2.y

            buf.putFloat(uc0x); buf.putFloat(uc0y)    // col0
            buf.putFloat(uc1x); buf.putFloat(uc1y)    // col1
            buf.putFloat(tx);   buf.putFloat(ty)      // col2
        }

        // Atlas region — always written after the user matrix, never composed into it
        buf.putFloat(atlasScaleU)
        buf.putFloat(atlasScaleV)
        buf.putFloat(atlasOffsetU)
        buf.putFloat(atlasOffsetV)
    }
}
