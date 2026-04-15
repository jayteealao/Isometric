package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.shader.TextureTransform
import io.github.jayteealao.isometric.webgpu.pipeline.SceneDataLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [UvRegionPacker.pack] — the `UvRegion` entry written into
 * the `sceneUvRegions` GPU buffer (40 bytes per face).
 *
 * Read order (10 floats):
 *   floats[0..1] = userMatrix col0 (u-axis)
 *   floats[2..3] = userMatrix col1 (v-axis)
 *   floats[4..5] = userMatrix col2 (translation)
 *   floats[6..7] = atlasScale  (U, V)
 *   floats[8..9] = atlasOffset (U, V)
 *
 * The user matrix contains only the [TextureTransform] — atlas region is stored
 * separately so the WGSL can apply `fract()` between the two steps.
 *
 * These tests run entirely on the JVM — no Android context or GPU device required.
 */
class GpuTextureManagerUvTransformTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeBuffer(): ByteBuffer =
        ByteBuffer.allocateDirect(40).order(ByteOrder.nativeOrder())

    private fun readFloats(buf: ByteBuffer): FloatArray {
        buf.rewind()
        return FloatArray(10) { buf.float }
    }

    private fun assertApprox(
        expected: Float,
        actual: Float,
        eps: Float = 1e-5f,
        label: String = "",
    ) {
        assertTrue(
            abs(expected - actual) <= eps,
            "$label: expected $expected but was $actual (delta ${abs(expected - actual)})"
        )
    }

    private fun assertFloats(
        expected: FloatArray,
        actual: FloatArray,
        eps: Float = 1e-5f,
    ) {
        val names = arrayOf(
            "col0.x", "col0.y", "col1.x", "col1.y", "col2.x", "col2.y",
            "atlasScaleU", "atlasScaleV", "atlasOffsetU", "atlasOffsetV",
        )
        for (i in expected.indices) {
            assertApprox(expected[i], actual[i], eps, names[i])
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * IDENTITY transform with a non-trivial atlas region.
     * Expected: identity user matrix (no scale/rotation/offset), atlas region separate.
     *
     * Atlas: scaleU=0.5, scaleV=0.5, offsetU=0.1, offsetV=0.2
     * Expected layout (10 floats):
     *   userMatrix cols: (1,0) (0,1) (0,0)   ← identity
     *   atlasScale/Offset: (0.5, 0.5, 0.1, 0.2)
     */
    @Test
    fun `identity writes identity user matrix and separate atlas region`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 0.5f,
            atlasScaleV  = 0.5f,
            atlasOffsetU = 0.1f,
            atlasOffsetV = 0.2f,
            transform    = TextureTransform.IDENTITY,
        )
        assertFloats(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f, 0.5f, 0.5f, 0.1f, 0.2f), readFloats(buf))
    }

    /**
     * Tiling 2×3 (scaleU=2, scaleV=3) with full-atlas region (scale=(1,1), offset=(0,0)).
     * Rotation=0 so cos=1, sin=0. Center-based pivot:
     *   tx = 0.5*(1 - 2*1 - 0) + 0 = 0.5*(−1) = −0.5
     *   ty = 0.5*(1 - 0 - 3*1) + 0 = 0.5*(−2) = −1.0
     * Expected user matrix + atlas region (10 floats):
     *   col0 = (2.0, 0.0)   col1 = (0.0, 3.0)   col2 = (−0.5, −1.0)
     *   atlasScale=(1,1)   atlasOffset=(0,0)
     */
    @Test
    fun `tiling 2x3 scales matrix correctly`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform.tiling(2f, 3f),
        )
        assertFloats(floatArrayOf(2f, 0f, 0f, 3f, -0.5f, -1.0f, 1f, 1f, 0f, 0f), readFloats(buf))
    }

    /**
     * 90-degree rotation around UV center (0.5, 0.5) with identity atlas.
     * cos(90°)≈0, sin(90°)≈1; scale=1.
     *   uc0 = (cos, sin) = (0, 1)
     *   uc1 = (−sin, cos) = (−1, 0)
     *   tx = 0.5*(1 − 0 − (−1)) + 0 = 0.5*2 = 1.0
     *   ty = 0.5*(1 − 1 − 0) + 0 = 0.5*(0) = 0.0
     * Expected user matrix + atlas region (10 floats):
     *   col0 = (0.0, 1.0)   col1 = (−1.0, 0.0)   col2 = (1.0, 0.0)
     *   atlasScale=(1,1)   atlasOffset=(0,0)
     */
    @Test
    fun `rotated 90 degrees produces correct matrix`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform.rotated(90f),
        )
        assertFloats(
            floatArrayOf(0f, 1f, -1f, 0f, 1f, 0f, 1f, 1f, 0f, 0f),
            readFloats(buf),
            eps = 1e-4f,
        )
    }

    /**
     * No rotation, no scale, offsetU=0.5 with identity atlas.
     * tx = 0.5*(1 − 1 − 0) + 0.5 = 0 + 0.5 = 0.5
     * ty = 0.5*(1 − 0 − 1) + 0   = 0 + 0   = 0.0
     * Expected user matrix + atlas region (10 floats):
     *   col0 = (1.0, 0.0)   col1 = (0.0, 1.0)   col2 = (0.5, 0.0)
     *   atlasScale=(1,1)   atlasOffset=(0,0)
     */
    @Test
    fun `offset shifts translation column`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform.offset(0.5f, 0f),
        )
        assertFloats(floatArrayOf(1f, 0f, 0f, 1f, 0.5f, 0f, 1f, 1f, 0f, 0f), readFloats(buf))
    }

    /**
     * Simulate the PerFace pattern: top face uses tiling(2,2), side face uses IDENTITY.
     * Both use full-atlas region. Pack each independently and verify the matrices differ.
     *
     * Top (tiling 2×2):  col0.x = 2.0,  col2.x = −0.5
     * Side (IDENTITY):   col0.x = 1.0,  col2.x = 0.0
     */
    @Test
    fun `per-face tiling top vs identity side produce distinct matrices`() {
        val topBuf = makeBuffer()
        UvRegionPacker.pack(
            buf          = topBuf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform.tiling(2f, 2f),
        )

        val sideBuf = makeBuffer()
        UvRegionPacker.pack(
            buf          = sideBuf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform.IDENTITY,
        )

        val top  = readFloats(topBuf)
        val side = readFloats(sideBuf)

        // col0.x: top = 2.0 (tiling scale), side = 1.0 (identity)
        assertApprox(2f, top[0],  label = "top col0.x")
        assertApprox(1f, side[0], label = "side col0.x")

        // col2.x: top = −0.5 (center-based correction), side = 0.0
        assertApprox(-0.5f, top[4],  label = "top col2.x")
        assertApprox(0f,    side[4], label = "side col2.x")
    }

    /**
     * Mirror (negative scaleU) flips the u-axis coefficient sign.
     * scaleU=-1f, scaleV=1f, rotation=0, offset=(0,0), full-atlas region.
     *
     * cosA=1, sinA=0:
     *   uc0x = su * cosA = -1 * 1 = -1.0   ← must be negative
     *   uc0y = su * sinA = -1 * 0 =  0.0
     *   uc1x = -sv * sinA = -1 * 0 =  0.0
     *   uc1y =  sv * cosA =  1 * 1 =  1.0  ← v-axis must stay positive
     *   tx = 0.5*(1 - (-1) - 0) + 0 = 1.0
     *   ty = 0.5*(1 - 0 - 1) + 0   = 0.0
     * Expected user matrix + atlas region (10 floats):
     *   col0 = (-1.0, 0.0)   col1 = (0.0, 1.0)   col2 = (1.0, 0.0)
     *   atlasScale=(1,1)   atlasOffset=(0,0)
     *
     * A bug such as using abs(su) instead of su in the matrix computation would
     * produce col0.x = +1.0 and be caught here.
     */
    @Test
    fun `mirror scale negates u coefficient`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform(scaleU = -1f, scaleV = 1f),
        )
        assertFloats(floatArrayOf(-1f, 0f, 0f, 1f, 1f, 0f, 1f, 1f, 0f, 0f), readFloats(buf))
    }

    /**
     * Combined tiling (2×2), 45-degree rotation, and offset (0.1, 0.2) with identity atlas.
     *
     * This test exercises the interaction between all three transform parameters at once.
     * The tx/ty translation column couples scale, rotation, and offset together:
     *   tx = 0.5*(1 - uc0x - uc1x) + du
     *   ty = 0.5*(1 - uc0y - uc1y) + dv
     * A bug such as applying the pivot correction before vs. after combining scale and rotation
     * would pass single-parameter tests but fail here.
     *
     * Pre-computed expected values (su=2, sv=2, rotationDegrees=45, du=0.1, dv=0.2):
     *   cosA = cos(π/4) = √2/2 ≈ 0.70710678
     *   sinA = sin(π/4) = √2/2 ≈ 0.70710678
     *   uc0x = 2 * cosA ≈  1.41421356
     *   uc0y = 2 * sinA ≈  1.41421356
     *   uc1x = -2 * sinA ≈ -1.41421356
     *   uc1y =  2 * cosA ≈  1.41421356
     *   tx = 0.5*(1 - 1.41421356 - (-1.41421356)) + 0.1 = 0.5*(1.0) + 0.1 = 0.6
     *   ty = 0.5*(1 - 1.41421356 - 1.41421356) + 0.2  = 0.5*(-1.82842712) + 0.2 ≈ -0.71421356
     * Expected user matrix + atlas region (10 floats):
     *   col0 = (1.41421356, 1.41421356)
     *   col1 = (-1.41421356, 1.41421356)
     *   col2 = (0.6, -0.71421356)
     *   atlasScale=(1,1)   atlasOffset=(0,0)
     */
    @Test
    fun `combined tiling rotation offset produces correct matrix`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform(
                scaleU          = 2f,
                scaleV          = 2f,
                rotationDegrees = 45f,
                offsetU         = 0.1f,
                offsetV         = 0.2f,
            ),
        )
        assertFloats(
            floatArrayOf(
                1.41421356f,  1.41421356f,   // col0 (u-axis)
                -1.41421356f, 1.41421356f,   // col1 (v-axis)
                0.6f,         -0.71421356f,  // col2 (translation)
                1f, 1f, 0f, 0f,              // atlasScale, atlasOffset
            ),
            readFloats(buf),
            eps = 1e-5f,
        )
    }

    /**
     * Large tiling (100×100) with full-atlas region verifies that the matrix coefficients
     * remain finite and hold the correct values even at extreme scale factors.
     *
     * This acts as a regression guard against accidentally moving fract() back to the
     * vertex shader (or removing it entirely): if that happened the solid-black artifact
     * would have no failing test without this case.
     *
     * rotation=0, offset=(0,0), full-atlas region:
     *   cosA=1, sinA=0
     *   uc0x =  su * cosA =  100 * 1 = 100f
     *   uc0y =  su * sinA =  100 * 0 =   0f
     *   uc1x = -sv * sinA = -100 * 0 =   0f
     *   uc1y =  sv * cosA =  100 * 1 = 100f
     *   tx = 0.5*(1 - 100 - 0) + 0 = 0.5*(-99) = -49.5f
     *   ty = 0.5*(1 -   0 - 100) + 0 = 0.5*(-99) = -49.5f
     * Expected user matrix + atlas region (10 floats):
     *   col0 = (100.0, 0.0)   col1 = (0.0, 100.0)   col2 = (-49.5, -49.5)
     *   atlasScale=(1,1)   atlasOffset=(0,0)
     */
    @Test
    fun `large tiling 100x produces finite matrix`() {
        val buf = makeBuffer()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 1f,
            atlasScaleV  = 1f,
            atlasOffsetU = 0f,
            atlasOffsetV = 0f,
            transform    = TextureTransform.tiling(100f, 100f),
        )
        val floats = readFloats(buf)
        // Verify all 6 user-matrix coefficients are finite before asserting exact values
        for (i in 0..5) {
            assertTrue(floats[i].isFinite(), "floats[$i] must be finite but was ${floats[i]}")
        }
        assertFloats(
            floatArrayOf(
                100f,   0f,      // col0: u-axis (scale, no rotation)
                0f,   100f,      // col1: v-axis (scale, no rotation)
                -49.5f, -49.5f, // col2: center-based pivot correction
                1f, 1f, 0f, 0f, // atlasScale, atlasOffset
            ),
            floats,
        )
    }

    /**
     * Stride guard: pack() must write exactly [SceneDataLayout.UV_REGION_STRIDE] bytes.
     *
     * This assertion catches any drift between the number of putFloat() calls in
     * [UvRegionPacker.pack] and the declared [SceneDataLayout.UV_REGION_STRIDE] constant
     * (currently 40 bytes / 10 floats). If either side changes without the other,
     * the GPU will silently read corrupted UvRegion data — this test surfaces that
     * at compile/test time instead.
     */
    @Test
    fun `pack writes exactly UV_REGION_STRIDE bytes`() {
        val buf = ByteBuffer.allocateDirect(SceneDataLayout.UV_REGION_STRIDE)
            .order(ByteOrder.nativeOrder())
        val before = buf.position()
        UvRegionPacker.pack(
            buf          = buf,
            atlasScaleU  = 0.5f,
            atlasScaleV  = 0.5f,
            atlasOffsetU = 0.1f,
            atlasOffsetV = 0.2f,
            transform    = TextureTransform.IDENTITY,
        )
        assertEquals(
            SceneDataLayout.UV_REGION_STRIDE,
            buf.position() - before,
            "UvRegionPacker.pack() must write exactly SceneDataLayout.UV_REGION_STRIDE bytes"
        )
    }
}
