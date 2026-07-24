package io.github.jayteealao.isometric

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Allocation microbench: asserts that [DepthSorter.sort] does not allocate
 * [IntersectionUtils.EdgeEquations2D] objects during the pairwise overlap loop.
 *
 * With the precompute optimization in place each face's edge equations are built
 * exactly once at projection time, stored on [DepthSorter.TransformedItem], and
 * reused across all pairwise comparisons.  This test is the permanent CI regression
 * guard for AC1: if the sort path ever rebuilds EdgeEquations2D per pair the
 * per-call allocation will spike well above the assertion threshold.
 *
 * Measurement goes through [AllocationProbe] ([com.sun.management.ThreadMXBean],
 * JDK 7+ HotSpot / OpenJDK).  When the API is unavailable or unsupported the test
 * fails closed rather than silently passing — a no-op guard would let a regression
 * slip by unnoticed.
 */
class DepthSorterAllocationTest {

    private val defaultAngle = PI / 6.0

    /**
     * Builds [n] [DepthSorter.TransformedItem] objects whose 2D projections all map to
     * the same unit screen square [0..1]×[0..1].  This maximises pairwise overlap:
     * every pair passes the AABB check and fires the full edge-crossing path — the
     * worst-case scenario for the optimization.
     *
     * Pre-builds [IntersectionUtils.EdgeEquations2D] once and stores it on every item
     * (all polygons are identical, so one instance suffices).
     */
    private fun buildOverlappingItems(n: Int): List<DepthSorter.TransformedItem> {
        val screenPoints = listOf(
            Point2D(0.0, 0.0),
            Point2D(1.0, 0.0),
            Point2D(1.0, 1.0),
            Point2D(0.0, 1.0),
        )
        // Pre-build once — this is exactly what the optimization does.
        val edges = IntersectionUtils.EdgeEquations2D.of(screenPoints)

        return (0 until n).map { i ->
            // Distinct z levels so DepthSorter.sort's depth pre-sort is stable.
            val z = i.toDouble() * 0.1
            val path = Path(
                Point(0.0, 0.0, z),
                Point(1.0, 0.0, z),
                Point(1.0, 1.0, z),
                Point(0.0, 1.0, z),
            )
            val sceneItem = SceneGraph.SceneItem(
                path = path,
                baseColor = IsoColor.BLUE,
                originalShape = null,
                id = "alloc_item_$i",
            )
            DepthSorter.TransformedItem(sceneItem, screenPoints, IsoColor.BLUE, edges)
        }
    }

    @Test
    fun `sort allocates no EdgeEquations2D on warm pairwise path`() {
        val items = buildOverlappingItems(10)
        // Disable broad-phase so all C(10,2)=45 pairs are checked — dense overlap
        // ensures the full edge-crossing path fires on every pair.
        val options = RenderOptions.Default.copy(enableBroadPhaseSort = false)

        // Allocation measurement — fail closed via the shared probe: the threshold must
        // be enforced, never silently skipped, even on a JVM that cannot measure.
        // Warm-up (runs before the measured probe call) lets the JIT compile the hot path
        // before measuring.
        val measureIterations = 20
        repeat(5) { DepthSorter.sort(items, options, defaultAngle) }
        val totalBytes = AllocationProbe.measureBytes {
            repeat(measureIterations) { DepthSorter.sort(items, options, defaultAngle) }
        }
        val perCallBytes = totalBytes.toDouble() / measureIterations

        // Threshold rationale:
        //   Each EdgeEquations2D for a 4-vertex polygon ≈ 192 bytes
        //   (3 DoubleArray(4) × ~48 bytes each + ~48 bytes for the wrapper object).
        //   Old code: 2 per pair × 45 pairs × 192 = ~17,280 bytes extra per call.
        //   Sort's own graph structures (IntArrays, ArrayList growth, Timsort) ≈ 13,000 bytes/call.
        //   New code total: ~13,000 bytes/call.
        //   Old code total: ~30,000 bytes/call.
        //   Threshold 22,000 bytes sits between the two:
        //     optimized (~13,000) < 22,000 ✓ PASS
        //     broken    (~30,000) > 22,000 ✓ FAIL
        val thresholdPerCall = 22_000.0

        println(
            "DepthSorterAllocationTest: N=10 overlapping faces, 45 pairs, " +
                "$measureIterations warm calls — " +
                "total=${totalBytes}B  per-call=${perCallBytes.toLong()}B  " +
                "threshold=${thresholdPerCall.toLong()}B",
        )

        // Lower-bound canary: this block sorts 10 overlapping faces 20× and provably allocates
        // (IntArrays, ArrayList growth, Timsort). A reading at/near zero means measurement is not
        // actually happening — a JVM that reports support but returns a stuck constant, so
        // delta == 0 — or the fixture stopped exercising sort; either way the `< threshold`
        // assertion below would pass vacuously. Floor 2,000 sits far under the ~13,000 B/call
        // optimized baseline and far above zero/noise.
        assertTrue(
            perCallBytes > 2_000.0,
            "Sort allocated only ${perCallBytes.toLong()} bytes/call — expected > 2000. A near-zero " +
                "reading means thread-allocation measurement is not working (stuck/constant reading) " +
                "or the fixture no longer exercises DepthSorter.sort; the upper-bound assertion below " +
                "would then pass without measuring anything.",
        )

        assertTrue(
            perCallBytes < thresholdPerCall,
            "Sort allocated ${perCallBytes.toLong()} bytes/call on the warm pairwise path — " +
                "expected < ${thresholdPerCall.toLong()} bytes/call (sort overhead ~13,000; " +
                "would be ~30,000 if EdgeEquations2D were re-allocated per pair). " +
                "Check that checkDepthDependency uses the pre-built overload from " +
                "TransformedItem.edgeEquations2D.",
        )
    }
}
