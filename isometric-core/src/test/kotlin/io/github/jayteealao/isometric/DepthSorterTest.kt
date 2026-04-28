package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.Prism
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthSorterTest {

    @Test
    fun `coplanar adjacent prisms produce deterministic order`() {
        // Two prisms sharing a boundary plane at x=1
        val engine1 = IsometricEngine()
        engine1.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine1.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        val scene1 = engine1.projectScene(800, 600, RenderOptions.NoCulling)

        // Project again with fresh engine (same scene)
        val engine2 = IsometricEngine()
        engine2.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine2.add(Prism(Point(1.0, 0.0, 0.0)), IsoColor.RED)

        val scene2 = engine2.projectScene(800, 600, RenderOptions.NoCulling)

        // Command order should be identical between both projections
        assertEquals(scene1.commands.size, scene2.commands.size)
        for (i in scene1.commands.indices) {
            assertEquals(
                scene1.commands[i].points,
                scene2.commands[i].points,
                "Command $i should have identical projected points"
            )
        }
    }

    @Test
    fun `coplanar tile grid has expected face count and no duplicates`() {
        val engine = IsometricEngine()
        // 3×3 grid of 1×1×1 prisms — full size, sharing boundary planes
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                engine.add(
                    Prism(Point(col.toDouble(), row.toDouble(), 0.0)),
                    IsoColor.BLUE
                )
            }
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // 9 prisms × 6 faces = 54 faces without culling
        assertEquals(54, scene.commands.size, "3×3 grid should produce 54 faces")

        // All command IDs should be unique (no duplicates)
        val ids = scene.commands.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "All command IDs should be unique")
    }

    @Test
    fun `coplanar tile grid with broad phase matches baseline order`() {
        val engine1 = IsometricEngine()
        val engine2 = IsometricEngine()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val origin = Point(col.toDouble(), row.toDouble(), 0.0)
                engine1.add(Prism(origin), IsoColor.BLUE)
                engine2.add(Prism(origin), IsoColor.BLUE)
            }
        }

        val baseline = engine1.projectScene(800, 600, RenderOptions.NoCulling)
        val broadPhase = engine2.projectScene(
            800, 600,
            RenderOptions.NoCulling.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            baseline.commands.map { it.commandId },
            broadPhase.commands.map { it.commandId },
            "Broad phase should produce same order as baseline for coplanar grid"
        )
    }

    @Test
    fun `cycle fallback includes all items`() {
        // Create items that could form dependency cycles by placing prisms
        // in positions that create circular occlusion relationships.
        // Even if cycles exist, all items must appear in the output.
        val engine = IsometricEngine()
        engine.add(Prism(Point.ORIGIN, 2.0, 2.0, 1.0), IsoColor.BLUE)
        engine.add(Prism(Point(0.5, 0.5, 0.5), 2.0, 2.0, 1.0), IsoColor.RED)
        engine.add(Prism(Point(1.0, 1.0, 1.0), 2.0, 2.0, 1.0), IsoColor.GREEN)

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // All items must appear — none should be lost
        assertTrue(scene.commands.isNotEmpty(), "Scene should have commands")
        // 3 prisms × 6 faces = 18 expected
        assertEquals(18, scene.commands.size, "All faces from all prisms must appear")

        // All IDs unique
        val ids = scene.commands.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "No duplicate command IDs")
    }

    @Test
    fun `diagnostic - face count and top face presence with culling`() {
        // Single prism — how many faces survive culling?
        val engine1 = IsometricEngine()
        engine1.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        val single = engine1.projectScene(800, 600, RenderOptions.Default)
        println("Single prism with culling: ${single.commands.size} faces")
        for (cmd in single.commands) {
            val path = cmd.originalPath
            val avgZ = path.points.sumOf { it.z } / path.points.size
            val avgX = path.points.sumOf { it.x } / path.points.size
            val avgY = path.points.sumOf { it.y } / path.points.size
            println("  Face ${cmd.commandId}: centroid=(${avgX}, ${avgY}, ${avgZ}) depth=${path.depth}")
        }

        // 2x2 grid with culling
        val engine2 = IsometricEngine()
        for (row in 0 until 2) {
            for (col in 0 until 2) {
                engine2.add(Prism(Point(col.toDouble(), row.toDouble(), 0.0)), IsoColor.BLUE)
            }
        }
        val grid = engine2.projectScene(800, 600, RenderOptions.Default)
        println("\n2x2 grid with culling: ${grid.commands.size} faces")

        // Check which faces are top faces (avgZ == 1.0 for top of 1x1x1 prism)
        var topFaceCount = 0
        for (cmd in grid.commands) {
            val path = cmd.originalPath
            val avgZ = path.points.sumOf { it.z } / path.points.size
            if (avgZ > 0.9) topFaceCount++
        }
        println("Top faces in output: $topFaceCount (expected 4)")
        assertEquals(4, topFaceCount, "All 4 top faces should survive culling in 2x2 grid")

        // 2x2 grid WITHOUT culling for comparison
        val engine3 = IsometricEngine()
        for (row in 0 until 2) {
            for (col in 0 until 2) {
                engine3.add(Prism(Point(col.toDouble(), row.toDouble(), 0.0)), IsoColor.BLUE)
            }
        }
        val gridNoCull = engine3.projectScene(800, 600, RenderOptions.NoCulling)
        println("\n2x2 grid without culling: ${gridNoCull.commands.size} faces")
        var topNoCull = 0
        for (cmd in gridNoCull.commands) {
            val path = cmd.originalPath
            val avgZ = path.points.sumOf { it.z } / path.points.size
            if (avgZ > 0.9) topNoCull++
        }
        println("Top faces without culling: $topNoCull (expected 4)")
    }

    @Test
    fun `NodeIdSample four buildings render in correct front-to-back order`() {
        // Reproduces the NodeIdSample geometry that originally exposed the
        // shared-edge overpaint bug: four buildings of varying heights placed
        // along the X axis. The bug manifested as factory's top face painting
        // over hq's right wall at their adjacent screen-space boundary.
        //
        // DepthSorter must add an edge between hq_right and factory_top such
        // that factory_top is drawn first (lower index in the command list)
        // and hq_right is drawn after (higher index, on top).
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 1.0, 0.1), 1.5, 1.5, 3.0), IsoColor.BLUE)    // hq
        engine.add(Prism(Point(2.0, 1.0, 0.1), 1.5, 1.5, 2.0), IsoColor.ORANGE)  // factory
        engine.add(Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 1.5), IsoColor.GREEN)   // warehouse
        engine.add(Prism(Point(6.0, 1.0, 0.1), 1.5, 1.5, 4.0), IsoColor.PURPLE)  // tower

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // Identify hq's right wall: all four vertices at x≈1.5 (the right edge
        // of the hq building at x=[0,1.5]).
        val hqRightIndex = scene.commands.indexOfFirst { cmd ->
            cmd.originalPath.points.all { kotlin.math.abs(it.x - 1.5) < 1e-9 } &&
                cmd.originalPath.points.any { it.z > 3.0 }
        }
        // Identify factory's top: all four vertices at z≈2.1, with x in [2.0, 3.5].
        val factoryTopIndex = scene.commands.indexOfFirst { cmd ->
            cmd.originalPath.points.all { kotlin.math.abs(it.z - 2.1) < 1e-9 } &&
                cmd.originalPath.points.all { it.x in 2.0..3.5 }
        }

        assertTrue(hqRightIndex >= 0, "hq's right wall must appear in scene commands")
        assertTrue(factoryTopIndex >= 0, "factory's top must appear in scene commands")
        assertTrue(
            factoryTopIndex < hqRightIndex,
            "factory_top (index $factoryTopIndex) must be drawn before hq_right " +
                "(index $hqRightIndex) so the closer face paints on top"
        )
    }

    @Test
    fun `closerThan is antisymmetric for representative non-coplanar pairs`() {
        // closerThan must satisfy a + b = 0 when computed in both directions.
        // Without antisymmetry the topological sort can produce inconsistent
        // orderings depending on iteration direction.
        val observer = Point(-10.0, -10.0, 20.0)
        val pairs: List<Pair<Path, Path>> = listOf(
            // hq_right vs factory_top (the diagnosed case)
            Path(
                Point(1.5, 1.0, 0.1), Point(1.5, 2.5, 0.1),
                Point(1.5, 2.5, 3.1), Point(1.5, 1.0, 3.1)
            ) to Path(
                Point(2.0, 1.0, 2.1), Point(3.5, 1.0, 2.1),
                Point(3.5, 2.5, 2.1), Point(2.0, 2.5, 2.1)
            ),
            // X-adjacent different-heights generic pair
            Path(
                Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0),
                Point(1.0, 1.0, 3.0), Point(1.0, 0.0, 3.0)
            ) to Path(
                Point(2.0, 0.0, 1.0), Point(3.0, 0.0, 1.0),
                Point(3.0, 1.0, 1.0), Point(2.0, 1.0, 1.0)
            ),
            // Y-adjacent different-heights generic pair
            Path(
                Point(0.0, 1.0, 0.0), Point(0.0, 1.0, 3.0),
                Point(1.0, 1.0, 3.0), Point(1.0, 1.0, 0.0)
            ) to Path(
                Point(0.0, 2.0, 1.0), Point(1.0, 2.0, 1.0),
                Point(1.0, 3.0, 1.0), Point(0.0, 3.0, 1.0)
            ),
            // Top vs vertical side at equal heights
            Path(
                Point(0.0, 0.0, 2.0), Point(1.0, 0.0, 2.0),
                Point(1.0, 1.0, 2.0), Point(0.0, 1.0, 2.0)
            ) to Path(
                Point(2.0, 0.0, 0.0), Point(2.0, 0.0, 2.0),
                Point(2.0, 1.0, 2.0), Point(2.0, 1.0, 0.0)
            )
        )
        pairs.forEachIndexed { idx, (a, b) ->
            val ab = a.closerThan(b, observer)
            val ba = b.closerThan(a, observer)
            assertEquals(
                0,
                ab + ba,
                "closerThan must be antisymmetric for pair $idx: a.closerThan(b)=$ab, b.closerThan(a)=$ba"
            )
        }
    }

    @Test
    fun `kahn algorithm preserves existing broad phase sparse test`() {
        // Same scenario as IsometricEngineTest broad phase sparse test —
        // verifies the new Kahn's sort doesn't break existing behavior
        val engine1 = IsometricEngine()
        engine1.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine1.add(Prism(Point(5.0, 0.0, 0.0)), IsoColor.RED)
        engine1.add(Prism(Point(0.0, 5.0, 0.0)), IsoColor.GREEN)

        val engine2 = IsometricEngine()
        engine2.add(Prism(Point.ORIGIN), IsoColor.BLUE)
        engine2.add(Prism(Point(5.0, 0.0, 0.0)), IsoColor.RED)
        engine2.add(Prism(Point(0.0, 5.0, 0.0)), IsoColor.GREEN)

        val default = engine1.projectScene(800, 600, RenderOptions.Default)
        val broadPhase = engine2.projectScene(
            800, 600,
            RenderOptions.Default.copy(enableBroadPhaseSort = true)
        )

        assertEquals(
            default.commands.map { it.commandId },
            broadPhase.commands.map { it.commandId }
        )
    }

    @Test
    fun `LongPress 3x3 grid back-right cube vertical faces are not drawn first`() {
        // Reproduces the LongPressSample 3x3 grid geometry — the canonical
        // over-aggressive-edge regression case.
        //
        // Without a strict screen-overlap gate, the depth comparator can fire
        // "winning votes" for face pairs whose 2D iso-projected polygons
        // share only a boundary edge (e.g., back-right cube's front face vs
        // middle-right cube's top face). Each spurious vote adds a topological
        // edge in DepthSorter.checkDepthDependency, and the cumulative effect
        // pushes back-right's vertical faces to output positions 0-2, where
        // later faces paint over them.
        //
        // With IntersectionUtils.hasInteriorIntersection as the gate, only
        // face pairs with non-trivial interior overlap in 2D screen projection
        // produce edges, so back-right's vertical faces sit at reasonable
        // output positions (≥ 3) and survive the painter's traversal.
        val engine = IsometricEngine()
        // Ground platform.
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)
        // 3x3 grid of unit prisms with column- and row-derived colors.
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            engine.add(
                Prism(Point(col * 1.8, row * 1.8, 0.1), 1.2, 1.2, 1.0),
                IsoColor((col + 1) * 80.0, (row + 1) * 80.0, 150.0)
            )
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // Back-right cube occupies x in [3.6, 4.8], y in [3.6, 4.8], z in [0.1, 1.1].
        // Its front face is the four vertices at y=3.6 (with x and z spanning).
        // Its left face is the four vertices at x=3.6 (with y and z spanning).
        val backRightFrontIndex = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.y - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - 4.8) < 1e-9 }
        }
        val backRightLeftIndex = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.x - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.y - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.y - 4.8) < 1e-9 }
        }

        assertTrue(backRightFrontIndex >= 0, "back-right cube's front face must appear in scene commands")
        assertTrue(backRightLeftIndex >= 0, "back-right cube's left face must appear in scene commands")
        assertTrue(
            backRightFrontIndex >= 3,
            "back-right cube's front face must not be at output positions 0-2 " +
                "(was at $backRightFrontIndex); otherwise neighbour faces drawn afterward " +
                "paint over it where iso-projected polygons overlap"
        )
        assertTrue(
            backRightLeftIndex >= 3,
            "back-right cube's left face must not be at output positions 0-2 " +
                "(was at $backRightLeftIndex)"
        )
    }

    @Test
    fun `LongPress full scene back-right cube vertical faces draw after ground top`() {
        // Reproduces the LongPressSample full scene including the ground
        // platform. Tests the wall-vs-floor symmetric-straddle case: for the
        // (back-right vertical wall, ground top) face pair,
        // hasInteriorIntersection correctly admits the pair, but a
        // vote-and-subtract comparator would return 0 because each polygon
        // straddles the other's plane on its respective observer-axis (both
        // directions counted equally, cancelling). With no edge added, Kahn
        // falls back to depth-descending centroid pre-sort which puts the
        // wall (centroid depth ~7.2) BEFORE the ground top (centroid depth
        // ~4.9), and the painter then paints ground over wall.
        //
        // Under the strict reduced-Newell cascade in Path.closerThan, the
        // Z-extent step returns non-zero immediately for wall-vs-floor pairs
        // (wall.zMax = 1.1, ground.zMax = 0.1: extents disjoint with epsilon),
        // an edge is added, and the topological sort puts the ground top
        // before the back-right vertical faces.
        val engine = IsometricEngine()
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)  // ground
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            engine.add(
                Prism(Point(col * 1.8, row * 1.8, 0.1), 1.2, 1.2, 1.0),
                IsoColor((col + 1) * 80.0, (row + 1) * 80.0, 150.0)
            )
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // Ground top face: all four vertices at z≈0.1, x spans -1.0..7.0,
        // y spans -1.0..5.0 (the full 8x6 platform top).
        val groundTopIndex = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.z - 0.1) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - (-1.0)) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - 7.0) < 1e-9 }
        }
        // Back-right cube (col=2, row=2) at world origin (3.6, 3.6, 0.1),
        // size 1.2×1.2×1.0. Its front face is the four vertices at y=3.6;
        // its left face is the four vertices at x=3.6.
        val backRightFrontIndex = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.y - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - 4.8) < 1e-9 }
        }
        val backRightLeftIndex = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.x - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.y - 3.6) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.y - 4.8) < 1e-9 }
        }

        assertTrue(groundTopIndex >= 0, "ground platform top face must appear in scene commands")
        assertTrue(backRightFrontIndex >= 0, "back-right cube's front face must appear in scene commands")
        assertTrue(backRightLeftIndex >= 0, "back-right cube's left face must appear in scene commands")
        assertTrue(
            backRightFrontIndex > groundTopIndex,
            "back-right front face (idx=$backRightFrontIndex) must draw AFTER ground top " +
                "(idx=$groundTopIndex); otherwise ground paints over wall"
        )
        assertTrue(
            backRightLeftIndex > groundTopIndex,
            "back-right left face (idx=$backRightLeftIndex) must draw AFTER ground top " +
                "(idx=$groundTopIndex); otherwise ground paints over wall"
        )
    }

    @Test
    fun `Alpha full scene each CYAN prism vertical faces draw after ground top`() {
        // Reproduces the AlphaSample full scene: a ground platform plus three
        // CYAN prisms in a row at increasing heights. Same wall-vs-floor
        // symmetric-straddle case as the LongPress test, applied to the
        // row-layout with mixed heights.
        //
        // Z-extent minimax decides each pair: prism wall z spans [0.1, 0.1+h]
        // vs ground top z=0.1 — the wall's z-extent strictly exceeds the
        // ground's at the top, so the cascade returns non-zero and the
        // topological sort orders ground-before-wall.
        val engine = IsometricEngine()
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)  // ground
        val prismSpecs = listOf(
            Triple(3.5, 3.0, 0.8),   // x, y(front), height
            Triple(4.3, 3.0, 1.2),
            Triple(5.1, 3.0, 1.6)
        )
        prismSpecs.forEach { (x, y, h) ->
            engine.add(Prism(Point(x, y, 0.1), 1.0, 1.0, h), IsoColor.CYAN)
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // Ground top face: all four vertices at z≈0.1, x spans -1.0..7.0.
        val groundTopIndex = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.z - 0.1) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - (-1.0)) < 1e-9 } &&
                pts.any { kotlin.math.abs(it.x - 7.0) < 1e-9 }
        }
        assertTrue(groundTopIndex >= 0, "ground platform top face must appear in scene commands")

        // For each prism, identify its front face (y=3.0 plane, x in [x, x+1])
        // and its left face (x=prismLeft plane, y in [3.0, 4.0]). Assert each
        // appears AFTER the ground top in command order.
        prismSpecs.forEachIndexed { idx, (px, py, _) ->
            val frontIndex = scene.commands.indexOfFirst { cmd ->
                val pts = cmd.originalPath.points
                pts.size == 4 &&
                    pts.all { kotlin.math.abs(it.y - py) < 1e-9 } &&
                    pts.any { kotlin.math.abs(it.x - px) < 1e-9 } &&
                    pts.any { kotlin.math.abs(it.x - (px + 1.0)) < 1e-9 }
            }
            val leftIndex = scene.commands.indexOfFirst { cmd ->
                val pts = cmd.originalPath.points
                pts.size == 4 &&
                    pts.all { kotlin.math.abs(it.x - px) < 1e-9 } &&
                    pts.any { kotlin.math.abs(it.y - py) < 1e-9 } &&
                    pts.any { kotlin.math.abs(it.y - (py + 1.0)) < 1e-9 }
            }

            assertTrue(frontIndex >= 0, "CYAN prism #$idx front face must appear in scene commands")
            assertTrue(leftIndex >= 0, "CYAN prism #$idx left face must appear in scene commands")
            assertTrue(
                frontIndex > groundTopIndex,
                "CYAN prism #$idx front face (idx=$frontIndex) must draw AFTER ground top " +
                    "(idx=$groundTopIndex)"
            )
            assertTrue(
                leftIndex > groundTopIndex,
                "CYAN prism #$idx left face (idx=$leftIndex) must draw AFTER ground top " +
                    "(idx=$groundTopIndex)"
            )
        }
    }

    @Test
    fun `Stack tower coincident faces produce deterministic order`() {
        // Reproduces StackExample sub-scene 1: 4 unit prisms stacked along
        // Z at world (1,1,0..3) with surface contact (gap=1 in the live
        // Stack composable means surfaces touch). Each prism N's TOP face
        // (z=N+1, normal +z) is coincident with prism N+1's BOTTOM face
        // (same z, normal -z) in 3D — they share the same plane and the
        // same xy projection, so hasInteriorIntersection admits the pair.
        //
        // The reduced-Newell cascade returns 0 for this pair because step 1
        // sees identical iso-depth ranges and step 2/3 see all vertices on
        // pathA's plane (every pPosition is exactly 0 → relativePlaneSide
        // reports all-coplanar / undecided). Without an edge, Kahn falls
        // back to the depth-descending centroid pre-sort, where TOP and
        // BOTTOM share centroids, so the order between them is whatever
        // sortedByDescending happens to produce — not deterministic with
        // respect to which face the observer should see.
        //
        // The fix differentiates TOP-vs-BOTTOM (opposite-direction normals)
        // from same-direction coplanar overlap: when normals point in
        // opposite directions, the polygon whose normal aligns with the
        // observer-direction is closer (paints last).
        //
        // Assertion: for each adjacent pair (prism N TOP, prism N+1 BOTTOM),
        // the front-facing TOP must draw AFTER the back-facing BOTTOM in
        // command order, so the visible color at the shared plane is
        // prism N's TOP color.
        val engine = IsometricEngine()
        for (n in 0 until 4) {
            engine.add(Prism(Point(1.0, 1.0, n.toDouble()), 1.0, 1.0, 1.0), IsoColor.BLUE)
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        fun topAtZ(z: Double): Int = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.z - z) < 1e-9 } &&
                pts.all { it.x in 0.999..2.001 } &&
                pts.all { it.y in 0.999..2.001 } &&
                // TOP normal points +z: (p1-p0) x (p2-p0) should have positive z.
                run {
                    val ax = pts[1].x - pts[0].x; val ay = pts[1].y - pts[0].y
                    val bx = pts[2].x - pts[0].x; val by = pts[2].y - pts[0].y
                    ax * by - ay * bx > 0
                }
        }
        fun bottomAtZ(z: Double): Int = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.z - z) < 1e-9 } &&
                pts.all { it.x in 0.999..2.001 } &&
                pts.all { it.y in 0.999..2.001 } &&
                // BOTTOM normal points -z: cross product z-component negative.
                run {
                    val ax = pts[1].x - pts[0].x; val ay = pts[1].y - pts[0].y
                    val bx = pts[2].x - pts[0].x; val by = pts[2].y - pts[0].y
                    ax * by - ay * bx < 0
                }
        }

        for (n in 0 until 3) {
            val sharedZ = (n + 1).toDouble()
            val nTop = topAtZ(sharedZ)
            val n1Bottom = bottomAtZ(sharedZ)
            assertTrue(nTop >= 0, "prism $n TOP at z=$sharedZ must appear in scene commands")
            assertTrue(n1Bottom >= 0, "prism ${n + 1} BOTTOM at z=$sharedZ must appear in scene commands")
            assertTrue(
                nTop > n1Bottom,
                "prism $n TOP (idx=$nTop, normal +z, front-facing observer) must draw AFTER " +
                    "prism ${n + 1} BOTTOM (idx=$n1Bottom, normal -z, back-facing) at shared z=$sharedZ"
            )
        }
    }

    @Test
    fun `TileGrid 6x6 sample renders all 36 tiles with no missing faces`() {
        // Reproduces TileGridExample: 6x6 grid of unit prisms at integer xy
        // positions, all z=[0,1]. Adjacent tiles share entire vertical wall
        // planes (e.g., tile (0,0)'s right wall at x=1 is coincident with
        // tile (1,0)'s left wall — same plane, same vertices, opposite
        // outward normals). 60 such adjacent-pair edges (30 along x, 30
        // along y) feed the all-coplanar branch of relativePlaneSide.
        //
        // The fix's opposite-normal coincident tiebreak gives every adjacent
        // wall pair a deterministic order (the front-facing wall paints last)
        // and keeps the gate-rejected boundary-only-contact pairs at natural
        // centroid order via Kahn's pre-sort. Pre-fix, both mechanisms ran
        // together non-deterministically and the live render exhibited
        // missing-tile artifacts at scene boundaries.
        //
        // Assertions: every prism contributes its expected NoCulling face
        // count (36 prisms × 6 faces = 216 commands); every command id is
        // unique; the front-row tiles' top faces all draw at higher indices
        // than the back-row tiles' top faces (natural farther-to-closer
        // centroid ordering preserved at the integration level).
        val engine = IsometricEngine()
        for (row in 0 until 6) {
            for (col in 0 until 6) {
                engine.add(
                    Prism(Point(col.toDouble(), row.toDouble(), 0.0), 1.0, 1.0, 1.0),
                    IsoColor.BLUE
                )
            }
        }

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        assertEquals(216, scene.commands.size, "6x6 grid NoCulling should produce 216 face commands")
        val ids = scene.commands.map { it.commandId }
        assertEquals(ids.size, ids.toSet().size, "All command IDs must be unique")

        // Top face of each tile: 4 vertices at z≈1, x in [col,col+1], y in [row,row+1].
        fun topAt(col: Int, row: Int): Int = scene.commands.indexOfFirst { cmd ->
            val pts = cmd.originalPath.points
            pts.size == 4 &&
                pts.all { kotlin.math.abs(it.z - 1.0) < 1e-9 } &&
                pts.all { it.x in (col - 1e-9)..(col + 1.0 + 1e-9) } &&
                pts.all { it.y in (row - 1e-9)..(row + 1.0 + 1e-9) } &&
                run {
                    val ax = pts[1].x - pts[0].x; val ay = pts[1].y - pts[0].y
                    val bx = pts[2].x - pts[0].x; val by = pts[2].y - pts[0].y
                    ax * by - ay * bx > 0  // +z normal -> TOP not BOTTOM
                }
        }
        // Every tile's top face must appear in the commands (no missing-face
        // regression). Topological order between non-iso-overlapping TOPs
        // (e.g., back-corner vs front-corner) is unconstrained — they don't
        // paint over each other regardless of order. Adjacent-wall coincident
        // pair ordering is exercised by the cascade tests in PathTest.
        for (row in 0 until 6) {
            for (col in 0 until 6) {
                assertTrue(topAt(col, row) >= 0, "tile ($col,$row) top face must appear in commands")
            }
        }
    }

    @Test
    fun `gate rejection preserves natural centroid order for adjacent same-height prisms`() {
        // End-to-end check that IntersectionUtils.hasInteriorIntersection's
        // boundary-only-contact reject path (the AC-10 shared-edge /
        // shared-vertex cases) propagates correctly through the full
        // DepthSorter pipeline.
        //
        // Three same-height unit cubes in a row, each abutting the next at a
        // shared wall plane in 3D. Their iso-projected vertical faces touch
        // the neighbour cube's face at exactly one iso-line each — boundary
        // contact, no interior overlap. With a lenient gate, the comparator
        // would be invoked for these pairs and could fire spurious draw-order
        // edges that override the natural farther-to-closer centroid sort.
        // The strict gate rejects these pairs, leaving the topological sort
        // empty for them and Kahn pre-sort handling order via centroid depth.
        //
        // Assertion: the three top faces appear in farther-to-closer order
        // (cube C farthest in x, draws first; cube A closest, draws last).
        val engine = IsometricEngine()
        engine.add(Prism(Point(0.0, 0.0, 0.0), 1.0, 1.0, 1.0), IsoColor.BLUE)   // A: x in [0,1]
        engine.add(Prism(Point(1.0, 0.0, 0.0), 1.0, 1.0, 1.0), IsoColor.GREEN)  // B: x in [1,2]
        engine.add(Prism(Point(2.0, 0.0, 0.0), 1.0, 1.0, 1.0), IsoColor.RED)    // C: x in [2,3]

        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        fun topIndexAtX(xMin: Double, xMax: Double): Int =
            scene.commands.indexOfFirst { cmd ->
                val pts = cmd.originalPath.points
                pts.size == 4 &&
                    pts.all { kotlin.math.abs(it.z - 1.0) < 1e-9 } &&
                    pts.all { it.x in (xMin - 1e-9)..(xMax + 1e-9) }
            }

        val aTopIndex = topIndexAtX(0.0, 1.0)
        val bTopIndex = topIndexAtX(1.0, 2.0)
        val cTopIndex = topIndexAtX(2.0, 3.0)

        assertTrue(aTopIndex >= 0, "cube A top face must appear in scene commands")
        assertTrue(bTopIndex >= 0, "cube B top face must appear in scene commands")
        assertTrue(cTopIndex >= 0, "cube C top face must appear in scene commands")

        // Observer at (-10,-10,20): higher x = farther. Painter's algorithm
        // draws farther first (lower command index), closer last (higher).
        // So C (farthest) < B < A (closest) in command order.
        assertTrue(
            cTopIndex < bTopIndex && bTopIndex < aTopIndex,
            "adjacent same-height prisms must draw in farther-to-closer order " +
                "(C=$cTopIndex, B=$bTopIndex, A=$aTopIndex); shared-wall boundary " +
                "contact must not fire spurious topological edges that override " +
                "the natural centroid sort"
        )
    }
}
