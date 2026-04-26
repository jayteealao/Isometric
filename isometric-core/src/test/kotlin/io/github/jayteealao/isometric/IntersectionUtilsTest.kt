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
}
