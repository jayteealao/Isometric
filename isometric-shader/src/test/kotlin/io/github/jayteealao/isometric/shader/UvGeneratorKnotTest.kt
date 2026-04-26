@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Prism
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for UV generation on [Knot] faces.
 *
 * Knot is a bag-of-primitives composite: 18 sub-prism faces delegated to
 * `UvGenerator.forPrismFace` plus 2 custom quads handled by axis-aligned
 * bounding-box planar projection. Tests pin the delegation contract, the
 * shape of the bbox projection output, and regression-guard the `sourcePrisms`
 * constants against drift from `Knot.createPaths`.
 */
class UvGeneratorKnotTest {

    private val unitKnot = Knot(Point.ORIGIN)

    @Test
    fun `uvCoordProviderForShape returns non-null provider for Knot`() {
        val provider = uvCoordProviderForShape(unitKnot)
        assertNotNull(provider)
        val uvs = provider.provide(unitKnot, faceIndex = 0)
        assertNotNull(uvs)
        assertEquals(8, uvs.size)
    }

    @Test
    fun `all 18 sub-prism faces return 8-float arrays`() {
        for (i in 0..17) {
            val uvs = UvGenerator.forKnotFace(unitKnot, faceIndex = i)
            assertEquals(8, uvs.size, "face $i should return 8 floats")
        }
    }

    @Test
    fun `face 0 delegates to forPrismFace on sourcePrisms 0`() {
        val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 0)
        val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[0], faceIndex = 0)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `face 6 delegates to forPrismFace on sourcePrisms 1`() {
        val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 6)
        val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[1], faceIndex = 0)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `face 12 delegates to forPrismFace on sourcePrisms 2`() {
        val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 12)
        val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[2], faceIndex = 0)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `custom quad 18 returns 8 floats all within 0 to 1`() {
        val uvs = UvGenerator.forKnotFace(unitKnot, faceIndex = 18)
        assertEquals(8, uvs.size)
        for (i in uvs.indices) {
            assertTrue(
                uvs[i] in 0.0f..1.0f,
                "uvs[$i] = ${uvs[i]} is outside [0, 1]",
            )
        }
    }

    @Test
    fun `custom quad 19 returns 8 floats all within 0 to 1`() {
        val uvs = UvGenerator.forKnotFace(unitKnot, faceIndex = 19)
        assertEquals(8, uvs.size)
        for (i in uvs.indices) {
            assertTrue(
                uvs[i] in 0.0f..1.0f,
                "uvs[$i] = ${uvs[i]} is outside [0, 1]",
            )
        }
    }

    // -- U-09: forAllKnotFaces per-element equivalence with forKnotFace ---------

    /**
     * U-09: Instead of just size-checking the output of forAllKnotFaces, iterate
     * every face index and assert that allFaces[i] contentEquals forKnotFace(i).
     * This pins the delegation contract end-to-end.
     */
    @Test
    fun `forAllKnotFaces per element equivalence with forKnotFace`() {
        val all = UvGenerator.forAllKnotFaces(unitKnot)
        assertEquals(20, all.size, "forAllKnotFaces must return exactly 20 arrays")
        for (i in all.indices) {
            val expected = UvGenerator.forKnotFace(unitKnot, faceIndex = i)
            val actual = all[i]
            assertEquals(
                expected.size, actual.size,
                "face $i: size mismatch between forAllKnotFaces and forKnotFace"
            )
            for (k in expected.indices) {
                assertEquals(
                    expected[k], actual[k], absoluteTolerance = 1e-6f,
                    "face $i: UV[$k] mismatch between forAllKnotFaces and forKnotFace"
                )
            }
        }
    }

    @Test
    fun `forAllKnotFaces returns 20 arrays in path order`() {
        val all = UvGenerator.forAllKnotFaces(unitKnot)
        assertEquals(20, all.size)
        for (i in all.indices) {
            assertEquals(8, all[i].size, "face $i should return 8 floats")
        }
    }

    // -- U-11: localFaceIndex 1..5 delegation for each sub-prism ---------------

    /**
     * U-11: Existing tests only check localFaceIndex=0 (faces 0, 6, 12). Add coverage
     * for localFaceIndex 1..5 within a single sub-prism (prism[0], faces 1..5).
     * Each delegation arm `faceIndex % 6` must produce the same UVs as calling
     * forPrismFace on the corresponding sourcePrism with the local face index.
     */
    @Test
    fun `sub prism 0 local face indices 1 through 5 delegate correctly`() {
        for (localFaceIndex in 1..5) {
            val globalFaceIndex = localFaceIndex  // prism[0] occupies global 0..5
            val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = globalFaceIndex)
            val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[0], faceIndex = localFaceIndex)
            assertEquals(
                expected.size, actual.size,
                "prism[0] localFaceIndex=$localFaceIndex: size mismatch"
            )
            for (k in expected.indices) {
                assertEquals(
                    expected[k], actual[k], absoluteTolerance = 1e-6f,
                    "prism[0] localFaceIndex=$localFaceIndex UV[$k] mismatch"
                )
            }
        }
    }

    @Test
    fun `sub prism 1 local face indices 1 through 5 delegate correctly`() {
        for (localFaceIndex in 1..5) {
            val globalFaceIndex = 6 + localFaceIndex  // prism[1] occupies global 6..11
            val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = globalFaceIndex)
            val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[1], faceIndex = localFaceIndex)
            assertEquals(
                expected.size, actual.size,
                "prism[1] localFaceIndex=$localFaceIndex: size mismatch"
            )
            for (k in expected.indices) {
                assertEquals(
                    expected[k], actual[k], absoluteTolerance = 1e-6f,
                    "prism[1] localFaceIndex=$localFaceIndex UV[$k] mismatch"
                )
            }
        }
    }

    @Test
    fun `sub prism 2 local face indices 1 through 5 delegate correctly`() {
        for (localFaceIndex in 1..5) {
            val globalFaceIndex = 12 + localFaceIndex  // prism[2] occupies global 12..17
            val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = globalFaceIndex)
            val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[2], faceIndex = localFaceIndex)
            assertEquals(
                expected.size, actual.size,
                "prism[2] localFaceIndex=$localFaceIndex: size mismatch"
            )
            for (k in expected.indices) {
                assertEquals(
                    expected[k], actual[k], absoluteTolerance = 1e-6f,
                    "prism[2] localFaceIndex=$localFaceIndex UV[$k] mismatch"
                )
            }
        }
    }

    @Test
    fun `negative face index throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forKnotFace(unitKnot, faceIndex = -1)
        }
    }

    @Test
    fun `face index beyond 19 throws`() {
        assertFailsWith<IllegalArgumentException> {
            UvGenerator.forKnotFace(unitKnot, faceIndex = 20)
        }
    }

    @Test
    fun `sourcePrisms dimensions match createPaths constants`() {
        // Regression guard: if anyone edits the Prism constants in
        // Knot.createPaths without updating sourcePrisms (or vice versa),
        // UV generation produces silently incorrect results. Pin the exact
        // dimensions so CI catches the drift.
        val prisms = unitKnot.sourcePrisms
        assertEquals(3, prisms.size, "Knot must expose exactly 3 source prisms")

        assertPrism(prisms[0], Point.ORIGIN, width = 5.0, depth = 1.0, height = 1.0)
        assertPrism(prisms[1], Point(4.0, 1.0, 0.0), width = 1.0, depth = 4.0, height = 1.0)
        assertPrism(prisms[2], Point(4.0, 4.0, -2.0), width = 1.0, depth = 1.0, height = 3.0)
    }

    private fun assertPrism(
        prism: Prism,
        position: Point,
        width: Double,
        depth: Double,
        height: Double,
    ) {
        assertEquals(position, prism.position)
        assertEquals(width, prism.width, absoluteTolerance = 1e-9)
        assertEquals(depth, prism.depth, absoluteTolerance = 1e-9)
        assertEquals(height, prism.height, absoluteTolerance = 1e-9)
    }
}
