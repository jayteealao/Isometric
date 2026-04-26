package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.PyramidFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UvGeneratorPyramidTest {

    private val unitPyramid = Pyramid(Point.ORIGIN)

    private fun assertUvAt(
        uvs: FloatArray,
        vertex: Int,
        expectedU: Float,
        expectedV: Float,
    ) {
        assertEquals(expectedU, uvs[vertex * 2], absoluteTolerance = 0.0001f)
        assertEquals(expectedV, uvs[vertex * 2 + 1], absoluteTolerance = 0.0001f)
    }

    @Test
    fun `uvCoordProviderForShape returns non-null provider for Pyramid`() {
        val provider = uvCoordProviderForShape(unitPyramid)
        assertNotNull(provider)
        val lateralUvs = provider.provide(unitPyramid, faceIndex = 0)
        assertNotNull(lateralUvs)
        assertEquals(6, lateralUvs.size)
        val baseUvs = provider.provide(unitPyramid, faceIndex = 4)
        assertNotNull(baseUvs)
        assertEquals(8, baseUvs.size)
    }

    @Test
    fun `lateral face 0 returns canonical triangle UVs`() {
        val uvs = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 0)
        assertEquals(6, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 1.0f) // base-left
        assertUvAt(uvs, 1, 1.0f, 1.0f) // base-right
        assertUvAt(uvs, 2, 0.5f, 0.0f) // apex
    }

    @Test
    fun `all 4 lateral faces produce identical canonical UVs`() {
        for (i in 0..3) {
            val uvs = UvGenerator.forPyramidFace(unitPyramid, faceIndex = i)
            assertEquals(6, uvs.size)
            assertUvAt(uvs, 0, 0.0f, 1.0f)
            assertUvAt(uvs, 1, 1.0f, 1.0f)
            assertUvAt(uvs, 2, 0.5f, 0.0f)
        }
    }

    @Test
    fun `lateral UVs return the same shared array on every call`() {
        // After the H-4 perf fix we return the shared LATERAL_CANONICAL_UVS directly
        // (no defensive copy) because neither Canvas nor WebGPU callers mutate the
        // returned array. Pin the shared-identity contract here so a future refactor
        // that re-introduces `.copyOf()` fails CI rather than silently regressing
        // the hot-path allocation (~9.4 KB/frame at 100 pyramids).
        val a = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 0)
        val b = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 1)
        val c = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 3)
        assertTrue(a === b, "lateral 0 and lateral 1 share the canonical array")
        assertTrue(a === c, "lateral 0 and lateral 3 share the canonical array")
    }

    @Test
    fun `base UVs are identity-cached per Pyramid instance`() {
        // H-5 perf fix: the base quad UVs are computed once per Pyramid instance and
        // reused. Same-instance repeat calls return the same FloatArray. Different
        // Pyramid instances get their own fresh array (no stale cache leak).
        val first = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 4)
        val second = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 4)
        assertTrue(first === second, "same Pyramid → same cached FloatArray")

        val other = Pyramid(
            position = Point(5.0, 5.0, 0.0),
            width = 2.0, depth = 2.0, height = 2.0,
        )
        val third = UvGenerator.forPyramidFace(other, faceIndex = 4)
        assertTrue(
            first !== third,
            "different Pyramid instance → fresh FloatArray (cache invalidated)",
        )
    }

    @Test
    fun `base face returns canonical quad UVs for unit pyramid`() {
        // Base quad vertex order is (0,0)→(0,d)→(w,d)→(w,0), wound CCW from below
        // so the outward normal points −z (the correct lighting orientation for a
        // pyramid's bottom face). Per-vertex UVs follow that same order.
        val uvs = UvGenerator.forPyramidFace(unitPyramid, faceIndex = 4)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 0.0f, 1.0f)
        assertUvAt(uvs, 2, 1.0f, 1.0f)
        assertUvAt(uvs, 3, 1.0f, 0.0f)
    }

    @Test
    fun `translated pyramid produces position-invariant UVs for every face`() {
        val translated = Pyramid(Point(5.0, 7.0, 3.0))
        for (i in 0..4) {
            val origin = UvGenerator.forPyramidFace(unitPyramid, i)
            val moved = UvGenerator.forPyramidFace(translated, i)
            assertEquals(origin.toList(), moved.toList())
        }
    }

    @Test
    fun `non-unit pyramid normalizes base UVs by width and depth`() {
        // Width=3, depth=2 → base corners span (0..3, 0..2) in local units. After
        // normalization the UVs should still be the canonical (0,0)-(0,1)-(1,1)-(1,0)
        // regardless of absolute extents, proving the /width, /depth division is
        // applied. Order matches the base quad's CCW-from-below winding.
        val stretched = Pyramid(Point.ORIGIN, width = 3.0, depth = 2.0, height = 4.0)
        val uvs = UvGenerator.forPyramidFace(stretched, faceIndex = 4)
        assertEquals(8, uvs.size)
        assertUvAt(uvs, 0, 0.0f, 0.0f)
        assertUvAt(uvs, 1, 0.0f, 1.0f)
        assertUvAt(uvs, 2, 1.0f, 1.0f)
        assertUvAt(uvs, 3, 1.0f, 0.0f)
    }

    @Test
    fun `forAllPyramidFaces returns 5 arrays with lateral and base sizes`() {
        val all = UvGenerator.forAllPyramidFaces(unitPyramid)
        assertEquals(5, all.size)
        for (i in 0..3) assertEquals(6, all[i].size, "lateral $i should have 6 floats")
        assertEquals(8, all[4].size, "base should have 8 floats")
    }

    @Test
    fun `invalid face index -1 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forPyramidFace(unitPyramid, faceIndex = -1)
        }
    }

    @Test
    fun `invalid face index 5 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forPyramidFace(unitPyramid, faceIndex = 5)
        }
    }

    @Test
    fun `PerFace Pyramid resolves base and laterals (direct resolve)`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val blue = IsoColor.BLUE
        val gray = IsoColor(128, 128, 128, 255)
        val perFace = IsometricMaterial.PerFace.Pyramid(
            base = red,
            laterals = mapOf(
                PyramidFace.LATERAL_0 to green,
                PyramidFace.LATERAL_2 to blue,
            ),
            default = gray,
        )
        assertEquals(red, perFace.resolve(PyramidFace.BASE))
        assertEquals(green, perFace.resolve(PyramidFace.LATERAL_0))
        assertEquals(gray, perFace.resolve(PyramidFace.LATERAL_1))
        assertEquals(blue, perFace.resolve(PyramidFace.LATERAL_2))
        assertEquals(gray, perFace.resolve(PyramidFace.LATERAL_3))
    }

    @Test
    fun `resolveForFace dispatches Pyramid via PyramidFace`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val gray = IsoColor(128, 128, 128, 255)
        val perFace: IsometricMaterial.PerFace = IsometricMaterial.PerFace.Pyramid(
            base = red,
            laterals = mapOf(PyramidFace.LATERAL_1 to green),
            default = gray,
        )
        // Matching BASE resolves.
        assertEquals(red, perFace.resolveForFace(PyramidFace.BASE))
        // Matching lateral resolves.
        assertEquals(green, perFace.resolveForFace(PyramidFace.LATERAL_1))
        // Unmapped lateral falls back to default.
        assertEquals(gray, perFace.resolveForFace(PyramidFace.LATERAL_3))
        // Mismatched FaceIdentifier type (OctahedronFace) falls back to default.
        assertEquals(gray, perFace.resolveForFace(OctahedronFace.UPPER_0))
        // Null faceType falls back to default.
        assertEquals(gray, perFace.resolveForFace(null))
    }

    @Test
    fun `pyramidPerFace DSL builds PerFace Pyramid with base and laterals`() {
        val red = IsoColor.RED
        val green = IsoColor.GREEN
        val gray = IsoColor(128, 128, 128, 255)
        val mat: IsometricMaterial.PerFace.Pyramid = pyramidPerFace {
            base = red
            lateral(0, green)
            lateral(2, green)
            default = gray
        }
        assertEquals(red, mat.resolve(PyramidFace.BASE))
        assertEquals(green, mat.resolve(PyramidFace.LATERAL_0))
        assertEquals(gray, mat.resolve(PyramidFace.LATERAL_1))
        assertEquals(green, mat.resolve(PyramidFace.LATERAL_2))
        assertEquals(gray, mat.resolve(PyramidFace.LATERAL_3))
        assertEquals(gray, mat.default)
    }

    @Test
    fun `pyramidPerFace allLaterals assigns every lateral at once`() {
        val red = IsoColor.RED
        val blue = IsoColor.BLUE
        val gray = IsoColor(128, 128, 128, 255)
        val mat = pyramidPerFace {
            allLaterals(red)
            lateral(2, blue) // per-index override after allLaterals
            default = gray
        }
        assertEquals(red, mat.resolve(PyramidFace.LATERAL_0))
        assertEquals(red, mat.resolve(PyramidFace.LATERAL_1))
        assertEquals(blue, mat.resolve(PyramidFace.LATERAL_2))
        assertEquals(red, mat.resolve(PyramidFace.LATERAL_3))
        // Unset base still falls through to default.
        assertEquals(gray, mat.resolve(PyramidFace.BASE))
    }

    @Test
    fun `pyramidPerFace DSL rejects out-of-range lateral index`() {
        // Delegates to PyramidFace.Lateral's init validation — no duplicate check in the DSL.
        assertFailsWith<IllegalArgumentException> {
            pyramidPerFace { lateral(4, IsoColor.RED) }
        }
        assertFailsWith<IllegalArgumentException> {
            pyramidPerFace { lateral(-1, IsoColor.RED) }
        }
    }
}
