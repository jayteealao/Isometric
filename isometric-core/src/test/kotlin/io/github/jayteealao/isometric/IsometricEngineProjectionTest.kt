package io.github.jayteealao.isometric

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsometricEngineProjectionTest {

    // --- Step 3: Parameterized depth ---

    @Test
    fun `depth parameterized uses cos and sin of angle`() {
        // depth(angle) = x*cos(angle) + y*sin(angle) - 2*z
        val point = Point(1.0, 0.0, 0.0)
        assertEquals(cos(PI / 4), point.depth(PI / 4), 0.0001)
    }

    @Test
    fun `depth parameterized at PI div 6 uses correct trig`() {
        // The no-arg depth() uses the simplified formula x + y - 2z,
        // while depth(PI/6) uses cos(30°)*x + sin(30°)*y - 2z.
        // They produce different values but preserve relative ordering.
        val point = Point(1.0, 1.0, 0.0)
        val parameterized = point.depth(PI / 6) // cos(30°) + sin(30°) ≈ 1.366
        val simplified = point.depth()           // 1 + 1 = 2.0
        assertTrue(parameterized < simplified,
            "Parameterized depth should differ from simplified formula")
    }

    @Test
    fun `depth parameterized preserves relative ordering`() {
        // Two points — the one with higher x+y should be deeper regardless of angle
        val p1 = Point(1.0, 1.0, 0.0)
        val p2 = Point(3.0, 3.0, 0.0)
        assertTrue(p2.depth(PI / 6) > p1.depth(PI / 6))
        assertTrue(p2.depth(PI / 4) > p1.depth(PI / 4))
        assertTrue(p2.depth(PI / 3) > p1.depth(PI / 3))
    }

    @Test
    fun `depth with custom angle differs from different angle`() {
        val point = Point(2.0, 1.0, 0.5)
        val depthA = point.depth(PI / 6)
        val depthB = point.depth(PI / 4)
        assertTrue(abs(depthA - depthB) > 0.001,
            "Different angles should produce different depths")
    }

    @Test
    fun `path depth function uses parameterized point depth`() {
        val path = Path(
            Point(1.0, 0.0, 0.0),
            Point(0.0, 1.0, 0.0),
            Point(0.0, 0.0, 1.0)
        )
        val customAngle = PI / 4
        val expectedDepth = path.points.sumOf { it.depth(customAngle) } / path.points.size
        assertEquals(expectedDepth, path.depth(customAngle), 0.0001)
    }

    // --- Step 4: Projection API ---

    @Test
    fun `worldToScreen projects origin to center`() {
        val engine = IsometricEngine()
        val screen = engine.worldToScreen(Point.ORIGIN, 800, 600)
        assertEquals(400.0, screen.x, 0.0001) // center X
        assertEquals(540.0, screen.y, 0.0001) // 90% down Y
    }

    @Test
    fun `screenToWorld inverts worldToScreen at z0`() {
        val engine = IsometricEngine()
        val original = Point(3.0, 2.0, 0.0)
        val screen = engine.worldToScreen(original, 800, 600)
        val roundTripped = engine.screenToWorld(screen, 800, 600, z = 0.0)

        assertEquals(original.x, roundTripped.x, 0.0001)
        assertEquals(original.y, roundTripped.y, 0.0001)
        assertEquals(0.0, roundTripped.z, 0.0001)
    }

    @Test
    fun `screenToWorld inverts worldToScreen at nonzero z`() {
        val engine = IsometricEngine()
        val original = Point(1.5, -2.5, 3.0)
        val screen = engine.worldToScreen(original, 1000, 800)
        val roundTripped = engine.screenToWorld(screen, 1000, 800, z = 3.0)

        assertEquals(original.x, roundTripped.x, 0.0001)
        assertEquals(original.y, roundTripped.y, 0.0001)
        assertEquals(original.z, roundTripped.z, 0.0001)
    }

    @Test
    fun `screenToWorld of origin screen pos returns origin`() {
        val engine = IsometricEngine()
        val originScreen = Point2D(400.0, 540.0) // center of 800x600 viewport
        val world = engine.screenToWorld(originScreen, 800, 600)

        assertEquals(0.0, world.x, 0.0001)
        assertEquals(0.0, world.y, 0.0001)
        assertEquals(0.0, world.z, 0.0001)
    }

    @Test
    fun `projection round-trip with custom angle`() {
        val engine = IsometricEngine(angle = PI / 4)
        val original = Point(5.0, 3.0, 0.0)
        val screen = engine.worldToScreen(original, 600, 400)
        val roundTripped = engine.screenToWorld(screen, 600, 400)

        assertEquals(original.x, roundTripped.x, 0.0001)
        assertEquals(original.y, roundTripped.y, 0.0001)
    }

    @Test
    fun `projection round-trip with custom scale`() {
        val engine = IsometricEngine(scale = 120.0)
        val original = Point(-1.0, 2.5, 1.0)
        val screen = engine.worldToScreen(original, 500, 500)
        val roundTripped = engine.screenToWorld(screen, 500, 500, z = 1.0)

        assertEquals(original.x, roundTripped.x, 0.0001)
        assertEquals(original.y, roundTripped.y, 0.0001)
    }

    // --- Step 5: Mutable engine params ---

    @Test
    fun `angle can be changed after construction`() {
        val engine = IsometricEngine()
        val originalScreen = engine.worldToScreen(Point(1.0, 0.0, 0.0), 800, 600)

        engine.angle = PI / 4
        val newScreen = engine.worldToScreen(Point(1.0, 0.0, 0.0), 800, 600)

        // Different angle should produce different screen position
        assertTrue(abs(originalScreen.x - newScreen.x) > 0.001 ||
                   abs(originalScreen.y - newScreen.y) > 0.001,
            "Changing angle should change projection")
    }

    @Test
    fun `scale can be changed after construction`() {
        val engine = IsometricEngine()
        val originalScreen = engine.worldToScreen(Point(1.0, 1.0, 0.0), 800, 600)

        engine.scale = 140.0
        val newScreen = engine.worldToScreen(Point(1.0, 1.0, 0.0), 800, 600)

        // Doubling scale should move the point further from center
        assertTrue(abs(originalScreen.x - newScreen.x) > 0.001 ||
                   abs(originalScreen.y - newScreen.y) > 0.001,
            "Changing scale should change projection")
    }

    @Test
    fun `mutable engine round-trip still works after angle change`() {
        val engine = IsometricEngine()
        engine.angle = PI / 3

        val original = Point(2.0, 3.0, 0.0)
        val screen = engine.worldToScreen(original, 800, 600)
        val roundTripped = engine.screenToWorld(screen, 800, 600)

        assertEquals(original.x, roundTripped.x, 0.0001)
        assertEquals(original.y, roundTripped.y, 0.0001)
    }

    @Test
    fun `mutable engine round-trip still works after scale change`() {
        val engine = IsometricEngine()
        engine.scale = 50.0

        val original = Point(-1.0, 4.0, 0.0)
        val screen = engine.worldToScreen(original, 800, 600)
        val roundTripped = engine.screenToWorld(screen, 800, 600)

        assertEquals(original.x, roundTripped.x, 0.0001)
        assertEquals(original.y, roundTripped.y, 0.0001)
    }

    @Test
    fun `setting scale to negative throws`() {
        val engine = IsometricEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.scale = -1.0
        }
    }

    @Test
    fun `setting scale to zero throws`() {
        val engine = IsometricEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.scale = 0.0
        }
    }

    @Test
    fun `setting angle to NaN throws`() {
        val engine = IsometricEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.angle = Double.NaN
        }
    }

    @Test
    fun `setting scale to Infinity throws`() {
        val engine = IsometricEngine()
        assertFailsWith<IllegalArgumentException> {
            engine.scale = Double.POSITIVE_INFINITY
        }
    }

    @Test
    fun `existing engine tests still pass with mutable params`() {
        // Construct and use engine — same as before mutability was added
        val engine = IsometricEngine()
        engine.add(io.github.jayteealao.isometric.shapes.Prism(Point.ORIGIN), IsoColor.BLUE)
        val scene = engine.projectScene(800, 600, RenderOptions.Default)
        assertTrue(scene.commands.isNotEmpty())
    }

    // --- projectionVersion ---

    @Test
    fun `projectionVersion starts at zero`() {
        val engine = IsometricEngine()
        assertEquals(0L, engine.projectionVersion)
    }

    @Test
    fun `projectionVersion increments on angle change`() {
        val engine = IsometricEngine()
        val v0 = engine.projectionVersion
        engine.angle = PI / 4
        assertTrue(engine.projectionVersion > v0,
            "projectionVersion should increment when angle changes")
    }

    @Test
    fun `projectionVersion increments on scale change`() {
        val engine = IsometricEngine()
        val v0 = engine.projectionVersion
        engine.scale = 140.0
        assertTrue(engine.projectionVersion > v0,
            "projectionVersion should increment when scale changes")
    }

    @Test
    fun `projectionVersion increments each mutation`() {
        val engine = IsometricEngine()
        engine.angle = PI / 4
        val v1 = engine.projectionVersion
        engine.scale = 50.0
        val v2 = engine.projectionVersion
        engine.angle = PI / 3
        val v3 = engine.projectionVersion

        assertTrue(v1 < v2 && v2 < v3,
            "projectionVersion should be strictly increasing: $v1, $v2, $v3")
    }

    @Test
    fun `SceneProjector default projectionVersion is zero`() {
        val projector = object : SceneProjector {
            override fun add(shape: Shape, color: IsoColor) {}
            override fun add(path: Path, color: IsoColor, originalShape: Shape?, id: String?, ownerNodeId: String?, material: MaterialData?, uvCoords: FloatArray?, faceType: io.github.jayteealao.isometric.shapes.PrismFace?) {}
            override fun clear() {}
            override fun projectScene(width: Int, height: Int, renderOptions: RenderOptions, lightDirection: Vector): PreparedScene =
                PreparedScene(emptyList(), width, height,
                    ProjectionParams(1.0, 0.5, -1.0, 0.5, 20.0, 0.5, IsoColor.WHITE), lightDirection)
            override fun findItemAt(preparedScene: PreparedScene, x: Double, y: Double, order: HitOrder, touchRadius: Double): RenderCommand? = null
        }
        assertEquals(0L, projector.projectionVersion)
    }

    // --- screenToWorld edge cases ---

    @Test
    fun `screenToWorld throws for near-degenerate angle`() {
        // angle = 0 makes sin(0) = 0 and sin(PI - 0) = 0, collapsing the Y axis
        val engine = IsometricEngine(angle = 0.0)
        assertFailsWith<IllegalArgumentException> {
            engine.screenToWorld(Point2D(400.0, 300.0), 800, 600)
        }
    }
}
