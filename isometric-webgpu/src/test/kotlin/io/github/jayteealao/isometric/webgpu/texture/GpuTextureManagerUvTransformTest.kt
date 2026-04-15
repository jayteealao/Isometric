package io.github.jayteealao.isometric.webgpu.texture

import io.github.jayteealao.isometric.shader.TextureTransform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.Test
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
}
