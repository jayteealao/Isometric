package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression net for the resolution-dependent depth-sort bug.
 *
 * Root cause: [DepthSorter.buildBroadPhaseCandidatePairs] used [hashMapOf] for the
 * broad-phase grid. HashMap iteration order is not specified and can differ between
 * JVM instances or between different viewport sizes (because different origins map
 * shape projections to different grid cell keys, changing hash-collision patterns).
 * Kahn's algorithm in the topological sort is sensitive to which edges arrive first
 * when multiple nodes compete for the same queue slot, so the broad-phase sort could
 * produce a result that disagreed with the full (non-broad-phase) reference sort.
 *
 * Fix: sort the accumulated pair list into canonical (ascending) order before
 * returning it, making the Kahn-algorithm input viewport-independent for a fixed
 * set of candidate pairs.
 *
 * Each test projects the 14-shape monument scene at a specific viewport size and
 * verifies that the broad-phase sort produces the same face ordering as the full
 * reference sort at that same viewport. This is the invariant the fix provides:
 * `broadPhaseSort(scene, viewport) == fullSort(scene, viewport)` for every viewport.
 *
 * Note: different viewport sizes project shapes to different pixel positions, so the
 * face ordering can legitimately differ between viewport sizes — that is not a bug.
 * The bug was that the broad-phase sort WITHIN a given viewport could disagree with
 * the full sort due to non-deterministic HashMap iteration.
 *
 * All projections use no-culling render options so that viewport-size-dependent
 * visibility differences cannot mask depth-sort order differences.
 */
class ViewportOrderInvarianceTest {

    /** Reference options: full sort, no broad-phase. No culling so all faces appear. */
    private val referenceOptions = RenderOptions(
        enableDepthSorting = true,
        enableBackfaceCulling = false,
        enableBoundsChecking = false,
        enableBroadPhaseSort = false
    )

    /** Test options: broad-phase enabled, same culling settings. */
    private val broadPhaseOptions = RenderOptions(
        enableDepthSorting = true,
        enableBackfaceCulling = false,
        enableBoundsChecking = false,
        enableBroadPhaseSort = true
    )

    /**
     * Builds the 14-shape monument scene into a fresh engine.
     * Matches the scene in DocScreenshotGenerator.complexScene().
     */
    private fun buildMonumentScene(): IsometricEngine {
        val blue = IsoColor(33.0, 150.0, 243.0)
        val yellow = IsoColor(180.0, 180.0, 0.0)
        val purple = IsoColor(180.0, 0.0, 180.0)
        val teal = IsoColor(0.0, 180.0, 180.0)
        val lightGreen = IsoColor(40.0, 180.0, 40.0)
        val gray = IsoColor(50.0, 50.0, 50.0)

        val engine = IsometricEngine()
        engine.add(Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), blue)
        engine.add(Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), blue)
        engine.add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), blue)
        engine.add(Stairs(Point(-1.0, 0.0, 0.0), 10), blue)
        engine.add(Stairs(Point(0.0, 3.0, 1.0), 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2), blue)
        engine.add(Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), blue)
        engine.add(Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), blue)
        engine.add(Stairs(Point(2.0, 0.0, 2.0), 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2), blue)
        engine.add(Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), yellow)
        engine.add(Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), purple)
        engine.add(Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), teal)
        engine.add(Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), lightGreen)
        engine.add(Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), gray)
        engine.add(Octahedron(Point(3.0, 2.0, 3.2)), teal)
        return engine
    }

    /**
     * At documentation screenshot resolution: broad-phase must match full sort.
     * This is the canonical viewport where the scene was originally authored.
     */
    @Test
    fun `broad-phase matches full sort at doc screenshot resolution 820 x 680`() {
        val reference = buildMonumentScene().projectScene(820, 680, referenceOptions).commands.map { it.commandId }
        val broadPhase = buildMonumentScene().projectScene(820, 680, broadPhaseOptions).commands.map { it.commandId }
        assertEquals(reference, broadPhase,
            "broad-phase sort at 820×680 disagrees with full sort at 820×680")
    }

    /**
     * At phone portrait resolution: broad-phase must match full sort.
     * This viewport was identified as having different HashMap iteration order
     * than the doc resolution, causing the original bug.
     */
    @Test
    fun `broad-phase matches full sort at phone portrait resolution 1280 x 2856`() {
        val reference = buildMonumentScene().projectScene(1280, 2856, referenceOptions).commands.map { it.commandId }
        val broadPhase = buildMonumentScene().projectScene(1280, 2856, broadPhaseOptions).commands.map { it.commandId }
        assertEquals(reference, broadPhase,
            "broad-phase sort at 1280×2856 disagrees with full sort at 1280×2856")
    }

    /**
     * At phone landscape resolution: broad-phase must match full sort.
     */
    @Test
    fun `broad-phase matches full sort at phone landscape resolution 2856 x 1280`() {
        val reference = buildMonumentScene().projectScene(2856, 1280, referenceOptions).commands.map { it.commandId }
        val broadPhase = buildMonumentScene().projectScene(2856, 1280, broadPhaseOptions).commands.map { it.commandId }
        assertEquals(reference, broadPhase,
            "broad-phase sort at 2856×1280 disagrees with full sort at 2856×1280")
    }

    /**
     * At a small square viewport: broad-phase must match full sort.
     */
    @Test
    fun `broad-phase matches full sort at small square viewport 400 x 400`() {
        val reference = buildMonumentScene().projectScene(400, 400, referenceOptions).commands.map { it.commandId }
        val broadPhase = buildMonumentScene().projectScene(400, 400, broadPhaseOptions).commands.map { it.commandId }
        assertEquals(reference, broadPhase,
            "broad-phase sort at 400×400 disagrees with full sort at 400×400")
    }

    /**
     * Command count is stable between broad-phase and full sort at every tested viewport.
     */
    @Test
    fun `command count matches between broad-phase and full sort across all viewports`() {
        val viewports = listOf(820 to 680, 1280 to 2856, 2856 to 1280, 400 to 400)
        for ((w, h) in viewports) {
            val refCount = buildMonumentScene().projectScene(w, h, referenceOptions).commands.size
            val bpCount  = buildMonumentScene().projectScene(w, h, broadPhaseOptions).commands.size
            assertEquals(refCount, bpCount,
                "Command count mismatch at ${w}x${h}: full=$refCount broad-phase=$bpCount")
        }
    }
}
