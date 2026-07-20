package io.github.jayteealao.isometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Correctness and allocation tests for the precomputed plane normal on [Path].
 *
 * The [Path] class eagerly computes the plane normal (n = (p1−p0) × (p2−p0)) and
 * plane constant d in its init block, storing them as four private `Double` fields
 * (planeNx, planeNy, planeNz, planeD).  [Path.relativePlaneSide] reads these scalars
 * instead of allocating [Vector] objects, eliminating per-call heap pressure in the
 * depth-sort comparator hot path.
 *
 * These tests cover:
 * 1. Degenerate face (collinear first-three vertices) produces a zero-magnitude normal,
 *    matching the pre-optimisation inline behavior of relativePlaneSide.
 * 2. Antisymmetry of closerThan is preserved after the precompute change.
 * 3. Per-call allocation in relativePlaneSide is near zero on the warm path.
 */
class PathAllocationTest {

    private val observer = Point(-10.0, -10.0, 20.0)

    // ---------------------------------------------------------------------------
    // Test 1 — Degenerate face: precomputed normal is (0, 0, 0)
    // ---------------------------------------------------------------------------

    @Test
    fun `degenerate face precomputes zero normal`() {
        // Three collinear points — the cross-product (p1−p0) × (p2−p0) is the zero vector
        // because both difference vectors are parallel. planeNx/Ny/Nz must all be 0.0.
        val degeneratePath = Path(
            Point(0.0, 0.0, 0.0),
            Point(1.0, 0.0, 0.0),
            // collinear with the first two
            Point(2.0, 0.0, 0.0),
        )

        // Access private fields via reflection — the test verifies the precomputed state
        // directly without widening their visibility to internal.
        fun getPlaneField(name: String): Double {
            val f = Path::class.java.getDeclaredField(name)
            f.isAccessible = true
            return f.getDouble(degeneratePath)
        }

        // Use == instead of assertEquals for the zero-magnitude check: the cross-product
        // computation may produce IEEE 754 negative zero (-0.0) for some components
        // (e.g. -(0.0) = -0.0).  In IEEE 754, 0.0 == -0.0 is true, but JUnit's
        // assertEquals(Double, Double) uses Double.compare which treats them as unequal.
        // We only care that the value is zero-magnitude, not its sign bit.
        assertTrue(
            getPlaneField("planeNx") == 0.0,
            "planeNx must be 0.0 for a degenerate (collinear) face; got ${getPlaneField("planeNx")}",
        )
        assertTrue(
            getPlaneField("planeNy") == 0.0,
            "planeNy must be 0.0 for a degenerate (collinear) face; got ${getPlaneField("planeNy")}",
        )
        assertTrue(
            getPlaneField("planeNz") == 0.0,
            "planeNz must be 0.0 for a degenerate (collinear) face; got ${getPlaneField("planeNz")}",
        )
        assertTrue(
            getPlaneField("planeD") == 0.0,
            "planeD must be 0.0 for a degenerate (zero-normal) face; got ${getPlaneField("planeD")}",
        )

        // With a zero normal, observerPosition = 0 − 0 = 0 ≤ EPSILON:
        // relativePlaneSide hits the early-return branch and returns 0 (observer-on-plane /
        // degenerate normal → undefined).  Use a second degenerate face so that both step 2
        // and step 3 use a zero-magnitude normal; a non-degenerate path might resolve the
        // comparison at step 1 (iso-depth minimax) before the plane-side test fires, or at
        // step 3 using its own non-zero plane.
        val degeneratePath2 = Path(
            Point(0.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0),
            // collinear along Y
            Point(0.0, 2.0, 0.0),
        )
        assertEquals(
            0,
            degeneratePath.closerThan(degeneratePath2, observer),
            "closerThan between two degenerate faces must return 0 (both plane-side steps " +
                "hit the observer-on-plane early-return due to zero-magnitude normals)",
        )
    }

    // ---------------------------------------------------------------------------
    // Test 2 — Antisymmetry preserved after precompute
    // ---------------------------------------------------------------------------

    @Test
    fun `closerThan antisymmetry preserved after precompute`() {
        // The canonical hq-right vs factory-top pair from the PathTest golden suite.
        // factoryTop.closerThan(hqRight, observer) > 0 means factoryTop is farther than hqRight.
        // Antisymmetry requires hqRight.closerThan(factoryTop, observer) < 0 (opposite sign).
        val hqRight = Path(
            Point(1.5, 1.0, 0.1),
            Point(1.5, 2.5, 0.1),
            Point(1.5, 2.5, 3.1),
            Point(1.5, 1.0, 3.1),
        )
        val factoryTop = Path(
            Point(2.0, 1.0, 2.1),
            Point(3.5, 1.0, 2.1),
            Point(3.5, 2.5, 2.1),
            Point(2.0, 2.5, 2.1),
        )

        val fwdResult = factoryTop.closerThan(hqRight, observer)
        val revResult = hqRight.closerThan(factoryTop, observer)

        assertTrue(
            fwdResult > 0,
            "factoryTop.closerThan(hqRight, observer) must be > 0 (factoryTop is farther); got $fwdResult",
        )
        assertTrue(
            revResult < 0,
            "hqRight.closerThan(factoryTop, observer) must be < 0 " +
                "(antisymmetry — sign opposite to forward); got $revResult",
        )
    }

    // ---------------------------------------------------------------------------
    // Test 3 — relativePlaneSide allocates no Vector objects on warm path
    // ---------------------------------------------------------------------------

    @Test
    fun `relativePlaneSide allocates no Vector objects on warm path`() {
        // N=10 path pairs whose iso-depth extents overlap, forcing both closerThan step 2
        // (plane-side forward) and step 3 (plane-side reverse) to fire on every call.
        // This exercises relativePlaneSide as aggressively as possible.
        //
        // Each pair is a variant of the hq-right / factory-top canonical pair, shifted
        // along the x-axis so all 10 pairs have the same overlap geometry.
        val pairs: List<Pair<Path, Path>> = (0 until 10).map { i ->
            val dx = i.toDouble() * 0.01 // tiny offset — keeps iso-depth extents overlapping
            val a = Path(
                Point(1.5 + dx, 1.0, 0.1),
                Point(1.5 + dx, 2.5, 0.1),
                Point(1.5 + dx, 2.5, 3.1),
                Point(1.5 + dx, 1.0, 3.1),
            )
            val b = Path(
                Point(2.0 + dx, 1.0, 2.1),
                Point(3.5 + dx, 1.0, 2.1),
                Point(3.5 + dx, 2.5, 2.1),
                Point(2.0 + dx, 2.5, 2.1),
            )
            a to b
        }

        // Allocation measurement — fail closed via the shared probe: the threshold must
        // be enforced, never silently skipped, even on a JVM that cannot measure.
        // Warm-up (runs before the measured probe call) lets the JIT compile the hot path
        // before measuring.
        val measureIterations = 20
        val runPairs = {
            for ((a, b) in pairs) {
                a.closerThan(b, observer)
                b.closerThan(a, observer)
            }
        }
        repeat(5) { runPairs() }
        val totalBytes = AllocationProbe.measureBytes { repeat(measureIterations) { runPairs() } }
        val perCallBytes = totalBytes.toDouble() / measureIterations

        // Threshold rationale:
        //   Each measurement iteration calls closerThan for all 10 pairs × 2 directions
        //   = 20 closerThan calls.  For this fixture step 2 fires (hqRight/factoryTop
        //   iso-depth ranges overlap) and resolves with a definitive sign, so step 3 is
        //   not reached.  That means exactly 1 relativePlaneSide call per closerThan call.
        //
        //   Baseline overhead (optimized path — no Vector allocs):
        //     Iterator objects for the per-points for-loops in closerThan and
        //     relativePlaneSide dominate: ~2,272 B/iteration (measured on JDK 17 HotSpot).
        //
        //   Without the optimization (3 Vector allocs per relativePlaneSide):
        //     Each Vector on JDK 17 HotSpot ≈ 40 bytes (12 B header + 3 × 8 B Double fields)
        //     3 Vectors × 40 B × 20 calls = 2,400 extra bytes per iteration
        //     Total without optimization ≈ 2,272 + 2,400 = ~4,672 B/iteration
        //
        //   Threshold 3,500 bytes/iteration sits cleanly between the two:
        //     optimized (~2,272 B) < 3,500 ✓ PASS
        //     broken    (~4,672 B) > 3,500 ✓ FAIL
        //
        //   If this test becomes flaky, re-run with verbose output to measure the baseline
        //   and adjust the threshold accordingly.
        val thresholdPerCall = 3_500.0

        println(
            "PathAllocationTest: N=10 pairs, 20 closerThan calls/iteration, " +
                "$measureIterations warm iterations — " +
                "total=${totalBytes}B  per-iteration=${perCallBytes.toLong()}B  " +
                "threshold=${thresholdPerCall.toLong()}B",
        )

        assertTrue(
            perCallBytes < thresholdPerCall,
            "relativePlaneSide allocated ${perCallBytes.toLong()} bytes/iteration on the warm path — " +
                "expected < ${thresholdPerCall.toLong()} bytes/iteration " +
                "(baseline list-iterator overhead ~2,272 B; would be ~4,672 B if Vector objects " +
                "were re-allocated per relativePlaneSide call). " +
                "Check that relativePlaneSide reads pathA.planeNx/Ny/Nz/D directly instead of " +
                "calling Vector.fromTwoPoints and Vector.crossProduct.",
        )
    }
}
