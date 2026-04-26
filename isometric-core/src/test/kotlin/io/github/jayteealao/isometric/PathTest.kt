package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PathTest {

    @Test
    fun `reverse reverses point order`() {
        val path = Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0))
        val reversed = path.reverse()
        assertEquals(path.points[0], reversed.points[2])
        assertEquals(path.points[2], reversed.points[0])
    }

    @Test
    fun `translate moves all points`() {
        val path = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0)
        )
        val translated = path.translate(1.0, 2.0, 3.0)
        assertEquals(Point(1.0, 2.0, 3.0), translated.points[0])
        assertEquals(Point(2.0, 2.0, 3.0), translated.points[1])
    }

    @Test
    fun `depth calculates average`() {
        val path = Path(
            Point(0.0, 0.0, 0.0),
            Point(2.0, 2.0, 0.0),
            Point(0.0, 2.0, 0.0)
        )
        val expectedDepth = (0.0 + 4.0 + 2.0) / 3.0
        assertEquals(expectedDepth, path.depth, 0.0001)
    }

    @Test
    fun `path requires at least three points`() {
        assertFailsWith<IllegalArgumentException> {
            Path(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0))
        }
    }

    // ---------------------------------------------------------------------
    // closerThan / countCloserThan — depth-sort shared-edge regression suite
    //
    // These tests cover the WS10 NodeIdSample bug: an integer-division
    // collapse inside countCloserThan returned 0 for adjacent prism faces
    // where 2 of 4 vertices were on the observer side of the other plane.
    // DepthSorter then added no edge, the back-to-front pre-sort decided,
    // and a farther face painted over a closer one (factory top over hq's
    // right wall). The fix replaces the integer division with a permissive
    // sign-preserving threshold.
    // ---------------------------------------------------------------------

    private val observer = Point(-10.0, -10.0, 20.0)

    @Test
    fun `closerThan returns nonzero for hq-right vs factory-top shared-edge case`() {
        // Reproduces the WS10 NodeIdSample bug. hq right side is the vertical
        // wall at x=1.5 spanning y=[1.0,2.5] and z=[0.1,3.1]. Factory top is
        // the horizontal face at z=2.1 spanning x=[2.0,3.5] and y=[1.0,2.5].
        // Two of hq_right's four vertices are on the observer side of
        // factory_top's plane (z > 2.1); none of factory_top's vertices are
        // on the observer side of hq_right's plane (factory is entirely at
        // x >= 2.0, beyond x=1.5). Pre-fix, integer division 2/4 = 0
        // collapsed the signal. Post-fix, the comparator returns +1.
        val hqRight = Path(
            Point(1.5, 1.0, 0.1), Point(1.5, 2.5, 0.1),
            Point(1.5, 2.5, 3.1), Point(1.5, 1.0, 3.1)
        )
        val factoryTop = Path(
            Point(2.0, 1.0, 2.1), Point(3.5, 1.0, 2.1),
            Point(3.5, 2.5, 2.1), Point(2.0, 2.5, 2.1)
        )
        val result = factoryTop.closerThan(hqRight, observer)
        assertTrue(
            result > 0,
            "closerThan must return positive for factory_top vs hq_right (factory_top is the farther face); got $result"
        )
    }

    @Test
    fun `closerThan resolves X-adjacent neighbours with different heights`() {
        // Tall prism A at x=[0,1], h=3. Short prism B at x=[2,3], h=1. Gap 1.
        // A's right wall (x=1, z=[0,3]) vs B's top (z=1, x=[2,3]).
        // A_right has 2 of 4 vertices on observer side of B_top's z=1 plane
        // (the z=3 pair). Pre-fix: 2/4 = 0 (bug). Post-fix: 1.
        // B_top has 0 of 4 vertices on observer side of A_right's x=1 plane
        // (all at x>=2, observer at x=-10). Returns 0.
        // closerThan(B_top, A_right) = 1 - 0 = 1.
        val aRight = Path(
            Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0),
            Point(1.0, 1.0, 3.0), Point(1.0, 0.0, 3.0)
        )
        val bTop = Path(
            Point(2.0, 0.0, 1.0), Point(3.0, 0.0, 1.0),
            Point(3.0, 1.0, 1.0), Point(2.0, 1.0, 1.0)
        )
        val result = bTop.closerThan(aRight, observer)
        assertTrue(result > 0, "X-adjacent different-heights case must return positive; got $result")
    }

    @Test
    fun `closerThan resolves Y-adjacent neighbours with different heights`() {
        // Symmetric to the X-adjacent case along the Y axis. Tall A at y=[0,1] h=3,
        // short B at y=[2,3] h=1. A's back wall (y=1) vs B's top (z=1).
        val aBack = Path(
            Point(0.0, 1.0, 0.0), Point(0.0, 1.0, 3.0),
            Point(1.0, 1.0, 3.0), Point(1.0, 1.0, 0.0)
        )
        val bTop = Path(
            Point(0.0, 2.0, 1.0), Point(1.0, 2.0, 1.0),
            Point(1.0, 3.0, 1.0), Point(0.0, 3.0, 1.0)
        )
        val result = bTop.closerThan(aBack, observer)
        assertTrue(result > 0, "Y-adjacent different-heights case must return positive; got $result")
    }

    @Test
    fun `closerThan resolves top vs vertical side at equal heights`() {
        // Two prisms of equal height h=2 with an X gap. A at x=[0,1], B at x=[2,3].
        // A's top (z=2) and B's left wall (x=2, z=[0,2]).
        // A_top: 4 vertices at x in [0,1], all observer-side of B_left's x=2 plane → 4 → 1 post-fix.
        // B_left: 4 vertices at z in {0,2}; the z=2 pair is on A_top's plane (coplanar
        // within epsilon, not counted), the z=0 pair is below → 0.
        // closerThan(B_left, A_top) = 1 - 0 = 1.
        val aTop = Path(
            Point(0.0, 0.0, 2.0), Point(1.0, 0.0, 2.0),
            Point(1.0, 1.0, 2.0), Point(0.0, 1.0, 2.0)
        )
        val bLeft = Path(
            Point(2.0, 0.0, 0.0), Point(2.0, 0.0, 2.0),
            Point(2.0, 1.0, 2.0), Point(2.0, 1.0, 0.0)
        )
        val result = bLeft.closerThan(aTop, observer)
        assertTrue(result > 0, "Equal-height top vs side case must return positive; got $result")
    }

    @Test
    fun `closerThan resolves diagonally offset adjacency`() {
        // Tall prism A at (0,0,0) h=4, short stub B at (3,3,0) w=1 d=1 h=2.
        // Tests that a single vertex on the observer side is enough (1/4 case).
        // B's top (z=2) vs A's right wall (x=1, z=[0,4]).
        // A_right: 2 of 4 vertices above z=2 → 1 post-fix.
        // B_top: 0 of 4 vertices on observer side of x=1 plane (all x>=3) → 0.
        // closerThan(B_top, A_right) = 1 - 0 = 1.
        val aRight = Path(
            Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0),
            Point(1.0, 1.0, 4.0), Point(1.0, 0.0, 4.0)
        )
        val bTop = Path(
            Point(3.0, 3.0, 2.0), Point(4.0, 3.0, 2.0),
            Point(4.0, 4.0, 2.0), Point(3.0, 4.0, 2.0)
        )
        val result = bTop.closerThan(aRight, observer)
        assertTrue(result > 0, "Diagonally offset case must return positive; got $result")
    }

    @Test
    fun `closerThan returns zero for genuinely coplanar non-overlapping faces`() {
        // Two top faces in the same z=1 plane but separated in x. This is the
        // genuine-tie negative control: the fix must not over-correct and turn
        // every coplanar pair into a non-zero ordering signal. DepthSorter
        // intentionally falls back to pre-sort for these.
        val aTop = Path(
            Point(0.0, 0.0, 1.0), Point(1.0, 0.0, 1.0),
            Point(1.0, 1.0, 1.0), Point(0.0, 1.0, 1.0)
        )
        val bTop = Path(
            Point(2.0, 0.0, 1.0), Point(3.0, 0.0, 1.0),
            Point(3.0, 1.0, 1.0), Point(2.0, 1.0, 1.0)
        )
        val result = aTop.closerThan(bTop, observer)
        assertEquals(0, result, "Coplanar non-overlapping faces must produce a tie (no ordering signal)")
    }
}
