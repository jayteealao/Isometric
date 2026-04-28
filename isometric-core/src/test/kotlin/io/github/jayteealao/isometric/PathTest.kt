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
    // closerThan — depth-sort painter's-algorithm regression suite.
    //
    // closerThan implements a strict reduced-Newell cascade. Each test
    // below targets a specific cascade step:
    //
    //   - Step 1: iso-depth (Z) extent minimax. Disjoint depth ranges
    //     produce a definitive sign.
    //   - Step 2: plane-side forward test. All vertices same-side as
    //     observer → self closer; all opposite → self farther.
    //   - Step 3: plane-side reverse test. Same idea with roles swapped.
    //   - Step 4 (deferred): polygon split. Genuine ambiguity returns 0.
    //
    // Newell's classical screen-x and screen-y extent steps are intentionally
    // omitted because DepthSorter's hasInteriorIntersection gate already
    // rejects screen-disjoint pairs upstream of the comparator.
    // ---------------------------------------------------------------------

    private val observer = Point(-10.0, -10.0, 20.0)

    @Test
    fun `closerThan returns nonzero for hq-right vs factory-top shared-edge case`() {
        // The original shared-edge bug. hq right side is the vertical wall
        // at x=1.5 spanning y=[1.0,2.5] and z=[0.1,3.1]. Factory top is the
        // horizontal face at z=2.1 spanning x=[2.0,3.5] and y=[1.0,2.5].
        //
        // Resolved by plane-side forward test (cascade step 2). hqRight's
        // plane is x=1.5; observer at x=-10 sits on the x<1.5 (negative)
        // side; factoryTop's vertices are all at x>=2.0, on the x>1.5
        // (positive) side — opposite from observer. Per the strict
        // all-on-same-side rule, "all opposite from observer" means self
        // is FARTHER than pathA → closerThan returns positive. Equivalently:
        // hqRight straddles between observer and factoryTop's half-space,
        // so hqRight is the closer face that paints on top of factoryTop
        // where they share the screen-x boundary.
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
        //
        // Resolved by plane-side forward test (cascade step 2). A_right's
        // plane is x=1; observer at x=-10 sits on the negative side;
        // B_top's vertices are all at x in [2,3], opposite from observer.
        // relativePlaneSide returns +1 (self farther) → B_top is farther
        // than A_right → A_right paints on top of B_top where their screen
        // extents could overlap.
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
        //
        // Resolved by plane-side forward test (cascade step 2), symmetric
        // to the X-adjacent case along the Y axis. A_back's plane is y=1;
        // B_top's vertices are all at y in [2,3], opposite from observer
        // (observer at y=-10). relativePlaneSide returns +1 → B_top is
        // farther than A_back.
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
        //
        // Resolved by iso-depth (Z) extent minimax (cascade step 1). A_top
        // is the horizontal face at z=2 spanning x=[0,1], y=[0,1] — its
        // iso-depth values (x*cos+y*sin-2z) range over a band entirely
        // ABOVE B_left's depth range because B_left extends down to z=0
        // (giving large positive depth contributions) at x=2. Concretely
        // A_top.depthMax (-2.634) is strictly less than B_left.depthMin
        // (-2.268), so step 1 fires with pathA=A_top entirely closer
        // (smaller depth = closer per the cascade's convention) →
        // self=B_left is farther → closerThan returns +1.
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
        // B's top (z=2) vs A's right wall (x=1, z=[0,4]).
        //
        // Resolved by plane-side forward test (cascade step 2). A_right's
        // plane is x=1; observer at x=-10 sits on the negative side;
        // B_top's vertices are all at x in [3,4], opposite from observer.
        // relativePlaneSide returns +1 (self farther) → B_top is farther.
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
    fun `closerThan returns zero for coplanar overlapping faces`() {
        // Two coincident top faces in the same z=1 plane with identical xy
        // extents. This is the genuine-ambiguity case the cascade cannot
        // resolve without polygon splitting: the Z extent is identical
        // (step 1 continues), and both plane-side tests return 0 (every
        // vertex is strictly on the plane → no decision). Step 4
        // (polygon split) is deferred, so the cascade returns 0 and
        // DepthSorter's append-on-cycle fallback handles any resulting
        // ambiguity.
        val aTop = Path(
            Point(0.0, 0.0, 1.0), Point(1.0, 0.0, 1.0),
            Point(1.0, 1.0, 1.0), Point(0.0, 1.0, 1.0)
        )
        val bTop = Path(
            Point(0.0, 0.0, 1.0), Point(1.0, 0.0, 1.0),
            Point(1.0, 1.0, 1.0), Point(0.0, 1.0, 1.0)
        )
        val result = aTop.closerThan(bTop, observer)
        assertEquals(
            0,
            result,
            "Coplanar overlapping faces must produce 0 (genuine ambiguity, polygon-split deferred)"
        )
    }

    @Test
    fun `closerThan resolves coplanar non-overlapping via Z-extent minimax`() {
        // Two top faces in the same world-z=1 plane but separated in world-x
        // by a unit gap. World-z is identical but the iso-depth function
        // depth(angle) = x*cos+y*sin-2z mixes x and y into the depth
        // scalar. With cos(PI/6) ≈ 0.866, the x-disjoint pair
        // (a.x=[0,1] vs b.x=[2,3]) produces disjoint iso-depth ranges
        // (a≈[-2,-0.634] vs b≈[-0.268,1.098]), so cascade step 1 fires
        // immediately. The sign reflects which polygon has smaller (closer)
        // depth: aTop has smaller depth → aTop is closer → self=aTop is
        // closer → closerThan returns negative.
        val aTop = Path(
            Point(0.0, 0.0, 1.0), Point(1.0, 0.0, 1.0),
            Point(1.0, 1.0, 1.0), Point(0.0, 1.0, 1.0)
        )
        val bTop = Path(
            Point(2.0, 0.0, 1.0), Point(3.0, 0.0, 1.0),
            Point(3.0, 1.0, 1.0), Point(2.0, 1.0, 1.0)
        )
        val result = aTop.closerThan(bTop, observer)
        assertTrue(
            result < 0,
            "Coplanar non-overlapping faces must resolve via Z-extent minimax with " +
                "self=aTop closer (smaller iso-depth) → negative; got $result"
        )
    }

    @Test
    fun `closerThan resolves wall-vs-floor straddle via plane-side test`() {
        // The cmpPath=0 wall-vs-floor symmetric-straddle regression class.
        // The vertical wall at x=1 spans z=[0,1]; the floor (ground top) at
        // z=0 strictly contains the wall's xy projection.
        //
        // Pre-Newell permissive vote-and-subtract:
        //   wall.countCloserThan(floor): 2 of 4 wall vertices have z>0 →
        //     observer-side → returns 1.
        //   floor.countCloserThan(wall): wall's plane is x=1; 2 of 4 floor
        //     vertices are at x<1 (observer side, observer at x=-10) →
        //     returns 1.
        //   closerThan = 1 - 1 = 0 → no edge → centroid pre-sort →
        //   wall painted over by floor → the bug.
        //
        // Under Newell, the iso-depth Z-extents overlap heavily (wall's
        // z=1 vertices have lower depth than floor's far corner; floor's
        // far corner has higher depth than wall's near corner), so step 1
        // does not decide. The plane-side forward test (step 5) decides:
        // floor's plane is z=0; the observer at z=20 is on the +z side; all
        // wall vertices have z>=0 (two on the plane, two strictly on the
        // observer side). Newell's strict all-on-same-side rule treats this
        // as "self all on observer side" → self is closer than pathA →
        // closerThan returns negative.
        //
        // Sign convention: closerThan(this, pathA) returns NEGATIVE when
        // this is closer than pathA (so DepthSorter's `cmpPath < 0` branch
        // adds an edge ensuring this draws AFTER pathA — the closer face
        // paints on top). Wall closer than floor → wall paints on top →
        // result < 0.
        val wall = Path(
            Point(1.0, 0.5, 0.0), Point(1.0, 1.5, 0.0),
            Point(1.0, 1.5, 1.0), Point(1.0, 0.5, 1.0)
        )
        val floor = Path(
            Point(0.0, 0.0, 0.0), Point(2.0, 0.0, 0.0),
            Point(2.0, 2.0, 0.0), Point(0.0, 2.0, 0.0)
        )
        val result = wall.closerThan(floor, observer)
        assertTrue(
            result < 0,
            "wall-vs-floor straddle must return negative via Newell plane-side test " +
                "(wall is closer than the floor it sits on, so it paints on top); got $result"
        )
    }
}
