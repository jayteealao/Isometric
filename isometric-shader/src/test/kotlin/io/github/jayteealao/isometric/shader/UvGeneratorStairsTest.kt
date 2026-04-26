package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shapes.StairsFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UvGeneratorStairsTest {

    private val unitStairs = Stairs(Point.ORIGIN, stepCount = 1)

    private fun assertUvAt(
        uvs: FloatArray,
        vertex: Int,
        expectedU: Float,
        expectedV: Float,
    ) {
        assertEquals(expectedU, uvs[vertex * 2], absoluteTolerance = 0.0001f)
        assertEquals(expectedV, uvs[vertex * 2 + 1], absoluteTolerance = 0.0001f)
    }

    // -- uvCoordProviderForShape ----------------------------------------------

    @Test
    fun `uvCoordProviderForShape returns non-null provider for Stairs`() {
        val stairs = Stairs(Point.ORIGIN, stepCount = 2)
        val provider = uvCoordProviderForShape(stairs)
        assertNotNull(provider)
        val riserUvs = provider.provide(stairs, faceIndex = 0)
        assertNotNull(riserUvs)
        assertEquals(8, riserUvs.size)
        val treadUvs = provider.provide(stairs, faceIndex = 1)
        assertNotNull(treadUvs)
        assertEquals(8, treadUvs.size)
        val sideUvs = provider.provide(stairs, faceIndex = 2 * 2) // 2N = 4
        assertNotNull(sideUvs)
        // 2N + 2 = 6 side vertices for stepCount = 2 → 12 floats
        assertEquals(12, sideUvs.size)
    }

    // -- Riser faces (canonical quad invariant across stepCount) --------------

    @Test
    fun `riser face 0 stepCount 1 produces canonical quad`() {
        val uvs = UvGenerator.forStairsFace(unitStairs, faceIndex = 0)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 1.0f)
        assertUvAt(uvs, 1, 0.0f, 0.0f)
        assertUvAt(uvs, 2, 1.0f, 0.0f)
        assertUvAt(uvs, 3, 1.0f, 1.0f)
    }

    @Test
    fun `every riser tiles identically regardless of step position`() {
        // All risers normalise against the local step height, so index 0, 2, 4, ...
        // (even within 0..2N-1) must produce the same canonical quad.
        for (stepCount in intArrayOf(3, 5, 10)) {
            val stairs = Stairs(Point.ORIGIN, stepCount = stepCount)
            for (step in 0 until stepCount) {
                val uvs = UvGenerator.forStairsFace(stairs, faceIndex = 2 * step)
                assertUvAt(uvs, 0, 0.0f, 1.0f)
                assertUvAt(uvs, 1, 0.0f, 0.0f)
                assertUvAt(uvs, 2, 1.0f, 0.0f)
                assertUvAt(uvs, 3, 1.0f, 1.0f)
            }
        }
    }

    // -- Tread faces (canonical quad invariant across stepCount) --------------

    @Test
    fun `tread face 1 stepCount 1 produces canonical quad`() {
        val uvs = UvGenerator.forStairsFace(unitStairs, faceIndex = 1)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 1.0f, 0.0f)
        assertUvAt(uvs, 2, 1.0f, 1.0f)
        assertUvAt(uvs, 3, 0.0f, 1.0f)
    }

    @Test
    fun `every tread tiles identically regardless of step position`() {
        for (stepCount in intArrayOf(2, 5, 10)) {
            val stairs = Stairs(Point.ORIGIN, stepCount = stepCount)
            for (step in 0 until stepCount) {
                val uvs = UvGenerator.forStairsFace(stairs, faceIndex = 2 * step + 1)
                assertUvAt(uvs, 0, 0.0f, 0.0f)
                assertUvAt(uvs, 1, 1.0f, 0.0f)
                assertUvAt(uvs, 2, 1.0f, 1.0f)
                assertUvAt(uvs, 3, 0.0f, 1.0f)
            }
        }
    }

    // -- Side faces -----------------------------------------------------------

    @Test
    fun `side face stepCount 1 has 4 vertices and canonical mapping`() {
        // zigzag: (0,0,0), (0,0,1), (0,1,1), (0,1,0)
        val leftIndex = 2 * 1
        val uvs = UvGenerator.forStairsFace(unitStairs, faceIndex = leftIndex)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 0.0f, 1.0f)
        assertUvAt(uvs, 2, 1.0f, 1.0f)
        assertUvAt(uvs, 3, 1.0f, 0.0f)
    }

    @Test
    fun `right side is u-mirrored against left side for stepCount 1`() {
        // Risk 2b: Stairs.createPaths emits the right zigzag as the *reversed* left
        // zigzag, then translated +1 along x. After u-mirroring (1 - y) the right
        // side still reads canonical (0,0)-(0,1)-(1,1)-(1,0) per vertex slot because
        // the reversal + mirror cancel, producing a horizontally consistent texture
        // read from outside the staircase.
        val rightIndex = 2 * 1 + 1
        val uvs = UvGenerator.forStairsFace(unitStairs, faceIndex = rightIndex)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 0.0f, 1.0f)
        assertUvAt(uvs, 2, 1.0f, 1.0f)
        assertUvAt(uvs, 3, 1.0f, 0.0f)
    }

    @Test
    fun `side face stepCount 5 has 12 vertices with UVs in the unit square`() {
        val stairs = Stairs(Point.ORIGIN, stepCount = 5)
        val leftUvs = UvGenerator.forStairsFace(stairs, faceIndex = 2 * 5)
        assertEquals(2 * (2 * 5 + 2), leftUvs.size)
        for (value in leftUvs) {
            assertTrue(value in 0.0f..1.0f, "UV value $value out of [0,1]")
        }
        val rightUvs = UvGenerator.forStairsFace(stairs, faceIndex = 2 * 5 + 1)
        assertEquals(leftUvs.size, rightUvs.size)
        for (value in rightUvs) {
            assertTrue(value in 0.0f..1.0f, "right-side UV value $value out of [0,1]")
        }
    }

    @Test
    fun `side face stepCount 10 has 22 vertices`() {
        val stairs = Stairs(Point.ORIGIN, stepCount = 10)
        val uvs = UvGenerator.forStairsFace(stairs, faceIndex = 2 * 10)
        // 2N + 2 vertices × 2 floats = 44
        assertEquals(44, uvs.size)
    }

    // -- StairsFace classification -------------------------------------------

    @Test
    fun `StairsFace fromPathIndex classifies riser tread and side ranges`() {
        val n = 4
        for (i in 0 until 2 * n step 2) {
            assertEquals(StairsFace.RISER, StairsFace.fromPathIndex(i, n))
        }
        for (i in 1 until 2 * n step 2) {
            assertEquals(StairsFace.TREAD, StairsFace.fromPathIndex(i, n))
        }
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(2 * n, n))
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(2 * n + 1, n))
    }

    // -- Error paths ----------------------------------------------------------

    @Test
    fun `invalid face index -1 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forStairsFace(unitStairs, faceIndex = -1)
        }
    }

    @Test
    fun `invalid face index past last side face throws`() {
        val stairs = Stairs(Point.ORIGIN, stepCount = 3)
        val lastValid = stairs.paths.size - 1
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forStairsFace(stairs, faceIndex = lastValid + 1)
        }
    }

    // -- forAllStairsFaces ----------------------------------------------------

    @Test
    fun `forAllStairsFaces returns correct count and sizes across stepCount`() {
        for (stepCount in intArrayOf(1, 2, 5, 10)) {
            val stairs = Stairs(Point.ORIGIN, stepCount = stepCount)
            val all = UvGenerator.forAllStairsFaces(stairs)
            assertEquals(2 * stepCount + 2, all.size)
            for (i in 0 until 2 * stepCount) {
                assertEquals(8, all[i].size, "riser/tread face $i @ stepCount=$stepCount")
            }
            val expectedSideFloats = 2 * (2 * stepCount + 2)
            assertEquals(expectedSideFloats, all[2 * stepCount].size)
            assertEquals(expectedSideFloats, all[2 * stepCount + 1].size)
        }
    }

    // -- T-1: stepCount=2 WebGPU-safe lower boundary -------------------------

    /**
     * T-1: stepCount=2 is the minimum value for which Stairs produces more than one
     * riser/tread pair. The WebGPU atlas allocator must handle this boundary without
     * off-by-one errors (e.g. faceCount = 2*N+2 = 6 for N=2). Verify that
     * forStairsFace produces a sane non-empty FloatArray for every face index.
     */
    @Test
    fun stepCount_2_minimum_is_supported_for_webgpu_atlas() {
        // WebGPU-safe lower boundary: N=2 produces 2*2+2=6 face indices total.
        val stairs = Stairs(Point.ORIGIN, stepCount = 2)
        val totalFaces = stairs.paths.size // should be 6
        assertEquals(6, totalFaces, "stepCount=2 must produce 6 faces")
        for (i in 0 until totalFaces) {
            val uvs = UvGenerator.forStairsFace(stairs, faceIndex = i)
            assertTrue(uvs.isNotEmpty(), "face $i must produce a non-empty FloatArray")
            for (value in uvs) {
                assertTrue(
                    value.isFinite(),
                    "face $i UV[$value] must be finite (no NaN/Infinity)"
                )
                assertTrue(
                    value in 0.0f..1.0f,
                    "face $i UV[$value] must be in [0,1]"
                )
            }
        }
    }

    // -- T-2: side face UV exact formula -------------------------------------

    /**
     * T-2: Instead of just asserting values are in [0,1], compute the EXACT expected
     * UV per vertex from the documented formula and assert pointwise equality.
     *
     * Side face UV formula (from UvGenerator.forStairsFace SIDE branch):
     *   u = (pt.y - pos.y)          for the left zigzag (faceIndex = 2*N)
     *   u = 1.0 - (pt.y - pos.y)   for the right zigzag (faceIndex = 2*N + 1)
     *   v = (pt.z - pos.z)
     */
    @Test
    fun side_face_uvs_match_explicit_formula() {
        val stepCount = 3
        val stairs = Stairs(Point.ORIGIN, stepCount = stepCount)
        val pos = stairs.position
        val n = stepCount
        val leftFaceIndex = 2 * n       // = 6
        val rightFaceIndex = 2 * n + 1  // = 7

        val leftPath = stairs.paths[leftFaceIndex]
        val rightPath = stairs.paths[rightFaceIndex]
        val vertCount = 2 * n + 2

        // Compute expected UVs for the left side using the documented formula.
        val expectedLeft = FloatArray(2 * vertCount)
        for (k in 0 until vertCount) {
            val pt = leftPath.points[k]
            expectedLeft[k * 2]     = (pt.y - pos.y).toFloat()
            expectedLeft[k * 2 + 1] = (pt.z - pos.z).toFloat()
        }

        // Compute expected UVs for the right side.
        val expectedRight = FloatArray(2 * vertCount)
        for (k in 0 until vertCount) {
            val pt = rightPath.points[k]
            expectedRight[k * 2]     = (1.0 - (pt.y - pos.y)).toFloat()
            expectedRight[k * 2 + 1] = (pt.z - pos.z).toFloat()
        }

        val actualLeft = UvGenerator.forStairsFace(stairs, faceIndex = leftFaceIndex)
        val actualRight = UvGenerator.forStairsFace(stairs, faceIndex = rightFaceIndex)

        assertEquals(expectedLeft.size, actualLeft.size, "left side float count mismatch")
        assertEquals(expectedRight.size, actualRight.size, "right side float count mismatch")

        for (i in expectedLeft.indices) {
            assertEquals(
                expectedLeft[i], actualLeft[i], absoluteTolerance = 1e-6f,
                "left side UV[$i] mismatch"
            )
        }
        for (i in expectedRight.indices) {
            assertEquals(
                expectedRight[i], actualRight[i], absoluteTolerance = 1e-6f,
                "right side UV[$i] mismatch"
            )
        }
    }

    // -- Translation invariance ----------------------------------------------

    @Test
    fun `translated stairs produces the same UVs as stairs at origin`() {
        // UVs normalise against `position`, so translating the staircase is a no-op
        // on UV output. Element-wise tolerance accounts for FP catastrophic cancellation
        // when the path points are computed as `(position.y + 1/3) - position.y` for
        // non-zero `position` — within Float tolerance but not binary-identical to the
        // origin path's `0 + 1/3` value.
        val translated = Stairs(Point(5.0, 7.0, 3.0), stepCount = 3)
        val origin = Stairs(Point.ORIGIN, stepCount = 3)
        for (i in 0 until origin.paths.size) {
            val o = UvGenerator.forStairsFace(origin, i)
            val t = UvGenerator.forStairsFace(translated, i)
            assertEquals(o.size, t.size, "face $i size mismatch after translation")
            for (k in o.indices) {
                assertEquals(o[k], t[k], absoluteTolerance = 0.0001f)
            }
        }
    }

    // -- PerFace.Stairs dispatch ---------------------------------------------

    @Test
    fun `resolveForFace dispatches Stairs via StairsFace`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val blue = IsoColor.BLUE
        val gray = IsoColor(128, 128, 128, 255)
        val perFace: IsometricMaterial.PerFace = IsometricMaterial.PerFace.Stairs(
            tread = red,
            riser = green,
            side = blue,
            default = gray,
        )
        assertEquals(green, perFace.resolveForFace(StairsFace.RISER))
        assertEquals(red, perFace.resolveForFace(StairsFace.TREAD))
        assertEquals(blue, perFace.resolveForFace(StairsFace.SIDE))
        // Unset slot falls back to default.
        val partial: IsometricMaterial.PerFace = IsometricMaterial.PerFace.Stairs(
            tread = red,
            default = gray,
        )
        assertEquals(gray, partial.resolveForFace(StairsFace.RISER))
        // Mismatched FaceIdentifier type falls back to default.
        assertEquals(gray, perFace.resolveForFace(OctahedronFace.UPPER_0))
        // Null faceType falls back to default.
        assertEquals(gray, perFace.resolveForFace(null))
    }
}
