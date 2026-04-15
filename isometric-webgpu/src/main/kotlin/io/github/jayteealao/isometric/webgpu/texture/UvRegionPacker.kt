package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.shader.TextureTransform
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Packs a composed `mat3x2<f32>` (user [TextureTransform] × atlas region) into a
 * [ByteBuffer] for the M5 emit shader's `sceneUvRegions` binding.
 *
 * ## Matrix layout (column-major, matching WGSL storage)
 *
 * Six `Float` values are written in this order:
 * ```
 * col0.x, col0.y,   ← first basis vector (u-axis, scaled and rotated)
 * col1.x, col1.y,   ← second basis vector (v-axis, scaled and rotated)
 * col2.x, col2.y    ← translation (atlas offset + user offset, center-based)
 * ```
 *
 * ## Application in WGSL
 *
 * ```wgsl
 * let uv = uvMatrix * vec3<f32>(baseU, baseV, 1.0);
 * ```
 *
 * ## Extraction rationale
 *
 * Extracted from [GpuTextureManager] so the math can be unit-tested on the JVM
 * without any Android context or GPU dependencies.
 */
internal object UvRegionPacker {

    /**
     * Write 6 floats (24 bytes) into [buf] representing the composed affine matrix.
     *
     * For [TextureTransform.IDENTITY] a fast path skips all trig and writes the
     * diagonal atlas-only matrix directly.
     *
     * @param buf         CPU staging buffer positioned at the start of the 24-byte slot.
     * @param atlasScaleU Atlas sub-region U scale (width fraction of the atlas).
     * @param atlasScaleV Atlas sub-region V scale (height fraction of the atlas).
     * @param atlasOffsetU Atlas sub-region U origin offset.
     * @param atlasOffsetV Atlas sub-region V origin offset.
     * @param transform   User-defined [TextureTransform] to compose with the atlas region.
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
            // Fast path: diagonal scale + atlas offset, no trig
            buf.putFloat(atlasScaleU); buf.putFloat(0f)
            buf.putFloat(0f);          buf.putFloat(atlasScaleV)
            buf.putFloat(atlasOffsetU); buf.putFloat(atlasOffsetV)
            return
        }

        // Full path: compose user transform (rotation around UV center (0.5, 0.5)) with atlas.
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

        // Compose: atlas * userTransform → combined mat3x2 (column-major)
        buf.putFloat(atlasScaleU * uc0x); buf.putFloat(atlasScaleV * uc0y)              // col0
        buf.putFloat(atlasScaleU * uc1x); buf.putFloat(atlasScaleV * uc1y)              // col1
        buf.putFloat(atlasScaleU * tx + atlasOffsetU); buf.putFloat(atlasScaleV * ty + atlasOffsetV) // col2
    }
}
