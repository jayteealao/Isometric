package io.github.jayteealao.isometric.shapes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-shape tests for [CylinderFace], [PyramidFace], [StairsFace], and [OctahedronFace]
 * introduced by the `uv-generation-shared-api` slice. Each shape's per-shape test file
 * lives next to it (e.g. [PrismFaceTest]); this file exercises just the shared
 * prerequisite surface.
 */
class ShapeFaceEnumTest {

    // ---------- CylinderFace ----------

    @Test
    fun `CylinderFace maps path index 0 to BOTTOM`() =
        assertEquals(CylinderFace.BOTTOM, CylinderFace.fromPathIndex(0))

    @Test
    fun `CylinderFace maps path index 1 to TOP`() =
        assertEquals(CylinderFace.TOP, CylinderFace.fromPathIndex(1))

    @Test
    fun `CylinderFace maps any index gte 2 to SIDE`() {
        assertEquals(CylinderFace.SIDE, CylinderFace.fromPathIndex(2))
        assertEquals(CylinderFace.SIDE, CylinderFace.fromPathIndex(10))
        assertEquals(CylinderFace.SIDE, CylinderFace.fromPathIndex(1000))
    }

    @Test
    fun `CylinderFace negative index throws`() {
        assertFailsWith<IllegalArgumentException> { CylinderFace.fromPathIndex(-1) }
    }

    // ---------- PyramidFace ----------

    @Test
    fun `PyramidFace maps path indices 0 through 3 to laterals`() {
        assertEquals(PyramidFace.LATERAL_0, PyramidFace.fromPathIndex(0))
        assertEquals(PyramidFace.LATERAL_1, PyramidFace.fromPathIndex(1))
        assertEquals(PyramidFace.LATERAL_2, PyramidFace.fromPathIndex(2))
        assertEquals(PyramidFace.LATERAL_3, PyramidFace.fromPathIndex(3))
    }

    @Test
    fun `PyramidFace maps path index 4 to BASE`() {
        assertEquals(PyramidFace.BASE, PyramidFace.fromPathIndex(4))
    }

    @Test
    fun `PyramidFace out-of-range index throws`() {
        assertFailsWith<IllegalArgumentException> { PyramidFace.fromPathIndex(-1) }
        assertFailsWith<IllegalArgumentException> { PyramidFace.fromPathIndex(5) }
    }

    @Test
    fun `PyramidFace Lateral constructor rejects out-of-range index`() {
        assertFailsWith<IllegalArgumentException> { PyramidFace.Lateral(-1) }
        assertFailsWith<IllegalArgumentException> { PyramidFace.Lateral(4) }
    }

    @Test
    fun `PyramidFace Lateral constants are data-class equal to new instances`() {
        assertEquals(PyramidFace.Lateral(0), PyramidFace.LATERAL_0)
        assertEquals(PyramidFace.Lateral(3), PyramidFace.LATERAL_3)
    }

    // ---------- StairsFace ----------

    @Test
    fun `StairsFace maps even indices below 2 stepCount to RISER`() {
        // stepCount=3 -> total=8 paths; riser/tread pairs at 0..5, sides at 6..7
        assertEquals(StairsFace.RISER, StairsFace.fromPathIndex(0, stepCount = 3))
        assertEquals(StairsFace.RISER, StairsFace.fromPathIndex(2, stepCount = 3))
        assertEquals(StairsFace.RISER, StairsFace.fromPathIndex(4, stepCount = 3))
    }

    @Test
    fun `StairsFace maps odd indices below 2 stepCount to TREAD`() {
        assertEquals(StairsFace.TREAD, StairsFace.fromPathIndex(1, stepCount = 3))
        assertEquals(StairsFace.TREAD, StairsFace.fromPathIndex(3, stepCount = 3))
        assertEquals(StairsFace.TREAD, StairsFace.fromPathIndex(5, stepCount = 3))
    }

    @Test
    fun `StairsFace maps final two indices to SIDE`() {
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(6, stepCount = 3))
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(7, stepCount = 3))
    }

    @Test
    fun `StairsFace out-of-range index throws`() {
        assertFailsWith<IllegalArgumentException> { StairsFace.fromPathIndex(-1, stepCount = 3) }
        assertFailsWith<IllegalArgumentException> { StairsFace.fromPathIndex(8, stepCount = 3) }
    }

    @Test
    fun `StairsFace zero stepCount throws`() {
        assertFailsWith<IllegalArgumentException> { StairsFace.fromPathIndex(0, stepCount = 0) }
    }

    @Test
    fun `StairsFace single-step geometry maps cleanly`() {
        // stepCount=1 -> 4 paths: riser, tread, side, side
        assertEquals(StairsFace.RISER, StairsFace.fromPathIndex(0, stepCount = 1))
        assertEquals(StairsFace.TREAD, StairsFace.fromPathIndex(1, stepCount = 1))
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(2, stepCount = 1))
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(3, stepCount = 1))
    }

    @Test
    fun `StairsFace large stepCount resolves every path index correctly`() {
        // Stress the 2*stepCount + 2 arithmetic for a staircase that exceeds the
        // smaller test's stepCount=3. stepCount=25 gives 52 total paths: risers at
        // even [0..48], treads at odd [1..49], sides at 50 and 51.
        val stepCount = 25
        val totalPaths = 2 * stepCount + 2
        for (i in 0 until totalPaths) {
            val expected = when {
                i >= 2 * stepCount -> StairsFace.SIDE
                i % 2 == 0 -> StairsFace.RISER
                else -> StairsFace.TREAD
            }
            assertEquals(expected, StairsFace.fromPathIndex(i, stepCount = stepCount),
                "path index $i of stepCount=$stepCount")
        }
        // Final sanity spot-checks at exact boundaries.
        assertEquals(StairsFace.RISER, StairsFace.fromPathIndex(48, stepCount = stepCount))
        assertEquals(StairsFace.TREAD, StairsFace.fromPathIndex(49, stepCount = stepCount))
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(50, stepCount = stepCount))
        assertEquals(StairsFace.SIDE, StairsFace.fromPathIndex(51, stepCount = stepCount))
        assertFailsWith<IllegalArgumentException> {
            StairsFace.fromPathIndex(52, stepCount = stepCount)
        }
    }

    // ---------- OctahedronFace ----------

    @Test
    fun `OctahedronFace interleaves upper and lower across four quadrants`() {
        assertEquals(OctahedronFace.UPPER_0, OctahedronFace.fromPathIndex(0))
        assertEquals(OctahedronFace.LOWER_0, OctahedronFace.fromPathIndex(1))
        assertEquals(OctahedronFace.UPPER_1, OctahedronFace.fromPathIndex(2))
        assertEquals(OctahedronFace.LOWER_1, OctahedronFace.fromPathIndex(3))
        assertEquals(OctahedronFace.UPPER_2, OctahedronFace.fromPathIndex(4))
        assertEquals(OctahedronFace.LOWER_2, OctahedronFace.fromPathIndex(5))
        assertEquals(OctahedronFace.UPPER_3, OctahedronFace.fromPathIndex(6))
        assertEquals(OctahedronFace.LOWER_3, OctahedronFace.fromPathIndex(7))
    }

    @Test
    fun `OctahedronFace fromPathIndex covers all entries`() {
        OctahedronFace.entries.forEachIndexed { i, expected ->
            assertEquals(expected, OctahedronFace.fromPathIndex(i))
        }
    }

    @Test
    fun `OctahedronFace out-of-range index throws`() {
        assertFailsWith<IllegalArgumentException> { OctahedronFace.fromPathIndex(-1) }
        assertFailsWith<IllegalArgumentException> { OctahedronFace.fromPathIndex(8) }
    }
}
