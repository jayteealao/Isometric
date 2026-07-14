@file:OptIn(ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Prism
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Allocation microbench for the no-sort render path.
 *
 * When [RenderOptions.enableDepthSorting] is `false`, [DepthSorter.sort] is skipped
 * and each [DepthSorter.TransformedItem]'s edge equations are never read.  Building
 * [IntersectionUtils.EdgeEquations2D] for every projected face on this path is pure
 * waste — a wrapper plus three [DoubleArray]s allocated per visible face that nothing
 * consumes.  This test is the permanent CI guard that the projection step skips the
 * edge-equation build when depth sorting is disabled.
 *
 * Measurement uses [com.sun.management.ThreadMXBean.getThreadAllocatedBytes] (JDK 7+
 * HotSpot / OpenJDK).  When the API is absent the byte assertion is skipped and the
 * test degrades to a no-op pass so CI on exotic JVMs is not broken.
 */
class NoSortEdgeEquationAllocationTest {

    @Test
    fun `no-sort path skips EdgeEquations2D build`() {
        val engine = IsometricEngine()
        // 2×2 Prism grid → 24 face paths; after back-face and shared-interior culling a
        // stable set of visible faces remain, each of which is projected every frame.
        for (col in 0 until 2) {
            for (row in 0 until 2) {
                engine.add(
                    Prism(Point(col.toDouble(), row.toDouble(), 0.0)),
                    IsoColor.BLUE
                )
            }
        }

        // Depth sorting OFF — this is the escape hatch the edge-equation build must respect.
        // Back-face culling is also disabled so every one of the 24 faces is projected
        // (and would build edge equations on the unfixed path), giving a large, stable
        // allocation gap between the broken and fixed builds rather than depending on how
        // many faces survive culling.
        val options = RenderOptions.Default.copy(
            enableDepthSorting = false,
            enableBackfaceCulling = false,
        )

        // Warm-up: let the JIT compile the hot path.
        repeat(5) { engine.projectScene(800, 600, options) }

        val threadMxBean = ManagementFactory.getThreadMXBean()
        val sunBean = threadMxBean as? com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id

        val measureIterations = 20
        val beforeBytes = sunBean?.getThreadAllocatedBytes(threadId) ?: -1L
        repeat(measureIterations) { engine.projectScene(800, 600, options) }
        val afterBytes = sunBean?.getThreadAllocatedBytes(threadId) ?: -1L

        if (beforeBytes < 0 || afterBytes < 0) {
            println(
                "NoSortEdgeEquationAllocationTest: com.sun.management.ThreadMXBean unavailable" +
                    " — byte assertion skipped."
            )
            return
        }

        val totalBytes = afterBytes - beforeBytes
        val perCallBytes = totalBytes.toDouble() / measureIterations

        // Threshold rationale (measured on JDK 17 HotSpot):
        //   All 24 faces are projected (back-face culling disabled).  On the unfixed path
        //   each builds an EdgeEquations2D: a wrapper + three DoubleArray(4) ≈ 176 B/face,
        //   ≈ 24 × 176 ≈ 4,200 B/call of pure waste.
        //     broken build (edgeEq per face) ≈ 25,700 B/call
        //     fixed  build (edgeEq skipped)  ≈ 21,500 B/call
        //   Threshold 23,500 B sits between the two:
        //     fixed  (~21,500) < 23,500 ✓ PASS
        //     broken (~25,700) > 23,500 ✓ FAIL
        //   If this becomes flaky due to JVM/scene overhead changes, re-run with verbose
        //   output to read the measured value and re-calibrate.
        val thresholdPerCall = 23_500.0

        println(
            "NoSortEdgeEquationAllocationTest: N=24 faces (2×2 Prism grid), " +
                "$measureIterations no-sort calls — " +
                "total=${totalBytes}B  per-call=${perCallBytes.toLong()}B  " +
                "threshold=${thresholdPerCall.toLong()}B"
        )

        assertTrue(
            perCallBytes < thresholdPerCall,
            "no-sort projectScene allocated ${perCallBytes.toLong()} bytes/call on the " +
                "depth-sorting-disabled path — expected < ${thresholdPerCall.toLong()} bytes/call. " +
                "If high, verify projectAndCull skips IntersectionUtils.EdgeEquations2D.of " +
                "when renderOptions.enableDepthSorting is false."
        )
    }
}
