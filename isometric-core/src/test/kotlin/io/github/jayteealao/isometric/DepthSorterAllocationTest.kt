package io.github.jayteealao.isometric

import java.lang.management.ManagementFactory
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
 * Measurement uses [com.sun.management.ThreadMXBean.getThreadAllocatedBytes] (JDK
 * 7+ HotSpot / OpenJDK).  When the API is absent (non-HotSpot JVM), the byte
 * assertion is skipped and the test degrades to a no-op pass so CI on exotic JVMs
 * is not broken.
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
            Point2D(0.0, 0.0), Point2D(1.0, 0.0),
            Point2D(1.0, 1.0), Point2D(0.0, 1.0),
        )
        // Pre-build once — this is exactly what the optimization does.
        val edges = IntersectionUtils.EdgeEquations2D.of(screenPoints)

        return (0 until n).map { i ->
            // Distinct z levels so DepthSorter.sort's depth pre-sort is stable.
            val z = i.toDouble() * 0.1
            val path = Path(
                Point(0.0, 0.0, z), Point(1.0, 0.0, z),
                Point(1.0, 1.0, z), Point(0.0, 1.0, z),
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

        // Warm-up: allow the JIT to compile the hot path before measuring.
        repeat(5) { DepthSorter.sort(items, options, defaultAngle) }

        // Allocation measurement.
        val threadMxBean = ManagementFactory.getThreadMXBean()
        val sunBean = threadMxBean as? com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id

        val measureIterations = 20
        val beforeBytes = sunBean?.getThreadAllocatedBytes(threadId) ?: -1L
        repeat(measureIterations) { DepthSorter.sort(items, options, defaultAngle) }
        val afterBytes = sunBean?.getThreadAllocatedBytes(threadId) ?: -1L

        if (beforeBytes < 0 || afterBytes < 0) {
            // com.sun.management not available on this JVM vendor — degrade gracefully.
            println(
                "DepthSorterAllocationTest: com.sun.management.ThreadMXBean unavailable" +
                    " — byte assertion skipped."
            )
            return
        }

        val totalBytes = afterBytes - beforeBytes
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
                "threshold=${thresholdPerCall.toLong()}B"
        )

        assertTrue(
            perCallBytes < thresholdPerCall,
            "Sort allocated ${perCallBytes.toLong()} bytes/call on the warm pairwise path — " +
                "expected < ${thresholdPerCall.toLong()} bytes/call (sort overhead ~13,000; " +
                "would be ~30,000 if EdgeEquations2D were re-allocated per pair). " +
                "Check that checkDepthDependency uses the pre-built overload from " +
                "TransformedItem.edgeEquations2D."
        )
    }
}
