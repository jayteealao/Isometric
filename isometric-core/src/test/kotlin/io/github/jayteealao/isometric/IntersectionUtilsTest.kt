package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Baseline coverage for [IntersectionUtils.hasIntersection]. This predicate
 * gates `DepthSorter.checkDepthDependency`: when it returns false, no edge
 * is added between two faces regardless of [Path.closerThan]. Any future
 * change here ripples directly into depth-sort correctness, so these tests
 * pin the canonical disjoint / overlapping / shared-edge behaviours as
 * regression markers.
 */
class IntersectionUtilsTest {

    @Test
    fun `hasIntersection returns false for fully disjoint polygons`() {
        // Two unit squares well separated along x.
        val a = listOf(
            Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
        )
        val b = listOf(
            Point(5.0, 0.0, 0.0), Point(6.0, 0.0, 0.0),
            Point(6.0, 1.0, 0.0), Point(5.0, 1.0, 0.0)
        )
        assertFalse(
            IntersectionUtils.hasIntersection(a, b),
            "Fully disjoint polygons must not register as intersecting"
        )
    }

    @Test
    fun `hasIntersection returns true for overlapping polygons`() {
        // Two unit squares overlapping in their right half / left half.
        val a = listOf(
            Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
        )
        val b = listOf(
            Point(0.5, 0.5, 0.0), Point(1.5, 0.5, 0.0),
            Point(1.5, 1.5, 0.0), Point(0.5, 1.5, 0.0)
        )
        assertTrue(
            IntersectionUtils.hasIntersection(a, b),
            "Polygons sharing interior must register as intersecting"
        )
    }

    @Test
    fun `hasIntersection returns true when one polygon contains the other`() {
        // 2x2 square fully containing a 1x1 square. The point-in-polygon
        // fallback (after SAT edge-cross) must catch this case so DepthSorter
        // can resolve the contained-face ordering.
        val outer = listOf(
            Point(0.0, 0.0, 0.0), Point(2.0, 0.0, 0.0),
            Point(2.0, 2.0, 0.0), Point(0.0, 2.0, 0.0)
        )
        val inner = listOf(
            Point(0.5, 0.5, 0.0), Point(1.5, 0.5, 0.0),
            Point(1.5, 1.5, 0.0), Point(0.5, 1.5, 0.0)
        )
        assertTrue(
            IntersectionUtils.hasIntersection(outer, inner),
            "Outer polygon containing inner polygon must register as intersecting"
        )
    }

    // ------------------------------------------------------------------
    // hasInteriorIntersection — strict gate used by DepthSorter to reject
    // boundary-only contact (shared edges, shared vertices). The original
    // hasIntersection above is intentionally permissive about such contact;
    // this stricter sibling exists so the depth-sort gate doesn't fire
    // topological edges for face pairs whose iso-projected polygons only
    // touch at a boundary (no painter overpaint can occur there).
    // ------------------------------------------------------------------

    @Test
    fun `hasInteriorIntersection returns false for polygons sharing only an edge`() {
        // Two unit squares meeting along x=1; no interior overlap.
        val a = listOf(
            Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
        )
        val b = listOf(
            Point(1.0, 0.0, 0.0), Point(2.0, 0.0, 0.0),
            Point(2.0, 1.0, 0.0), Point(1.0, 1.0, 0.0)
        )
        assertFalse(
            IntersectionUtils.hasInteriorIntersection(a, b),
            "Polygons sharing only one edge must not register as interior intersecting"
        )
        // Regression marker: confirm the lenient sibling still says "true" so
        // the divergence between the two functions is observable.
        assertTrue(
            IntersectionUtils.hasIntersection(a, b),
            "hasIntersection's existing contract preserved: shared-edge contact " +
                "remains true (callers that want strictness must use hasInteriorIntersection)"
        )
    }

    @Test
    fun `hasInteriorIntersection returns false for polygons sharing only a vertex`() {
        // Two unit squares meeting at the corner (1,1).
        val a = listOf(
            Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
        )
        val b = listOf(
            Point(1.0, 1.0, 0.0), Point(2.0, 1.0, 0.0),
            Point(2.0, 2.0, 0.0), Point(1.0, 2.0, 0.0)
        )
        assertFalse(
            IntersectionUtils.hasInteriorIntersection(a, b),
            "Polygons sharing only one vertex must not register as interior intersecting"
        )
    }

    @Test
    fun `hasInteriorIntersection returns true for genuine interior overlap`() {
        // Two unit squares with a half-overlap quadrant.
        val a = listOf(
            Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
        )
        val b = listOf(
            Point(0.5, 0.5, 0.0), Point(1.5, 0.5, 0.0),
            Point(1.5, 1.5, 0.0), Point(0.5, 1.5, 0.0)
        )
        assertTrue(
            IntersectionUtils.hasInteriorIntersection(a, b),
            "Polygons with non-trivial interior overlap must register as interior intersecting"
        )
    }

    @Test
    fun `hasInteriorIntersection returns false for fully disjoint polygons`() {
        // Sanity check: same input as the hasIntersection-disjoint case.
        val a = listOf(
            Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
            Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
        )
        val b = listOf(
            Point(5.0, 0.0, 0.0), Point(6.0, 0.0, 0.0),
            Point(6.0, 1.0, 0.0), Point(5.0, 1.0, 0.0)
        )
        assertFalse(
            IntersectionUtils.hasInteriorIntersection(a, b),
            "Fully disjoint polygons must not register as interior intersecting"
        )
    }

    @Test
    fun `hasInteriorIntersection returns true when one polygon strictly contains the other`() {
        // The contained polygon's vertices are all strictly inside the outer.
        val outer = listOf(
            Point(0.0, 0.0, 0.0), Point(2.0, 0.0, 0.0),
            Point(2.0, 2.0, 0.0), Point(0.0, 2.0, 0.0)
        )
        val inner = listOf(
            Point(0.5, 0.5, 0.0), Point(1.5, 0.5, 0.0),
            Point(1.5, 1.5, 0.0), Point(0.5, 1.5, 0.0)
        )
        assertTrue(
            IntersectionUtils.hasInteriorIntersection(outer, inner),
            "Strict containment counts as interior intersection"
        )
    }
}
