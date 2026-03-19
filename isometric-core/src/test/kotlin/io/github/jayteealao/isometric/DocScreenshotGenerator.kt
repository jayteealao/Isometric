package io.github.jayteealao.isometric

import io.github.jayteealao.isometric.shapes.*
import org.junit.Test
import java.io.File
import kotlin.math.PI

// Generates PNG screenshots for documentation.
// Run with: ./gradlew :isometric-core:test --tests "*.DocScreenshotGenerator"
// Output: docs/assets/screenshots/
class DocScreenshotGenerator {

    private val outputDir = File("../docs/assets/screenshots")

    // Colors
    private val BLUE = IsoColor(50.0, 60.0, 160.0)
    private val BLUE_GHOST = IsoColor(50.0, 60.0, 160.0, 80.0)
    private val GREEN = IsoColor(50.0, 160.0, 60.0)
    private val RED = IsoColor(160.0, 60.0, 50.0)
    private val TEAL = IsoColor(0.0, 180.0, 180.0)
    private val YELLOW = IsoColor(180.0, 180.0, 0.0)
    private val LIGHT_GREEN = IsoColor(40.0, 180.0, 40.0)
    private val PURPLE = IsoColor(180.0, 0.0, 180.0)
    private val MATERIAL_BLUE = IsoColor(33.0, 150.0, 243.0)
    private val GRAY = IsoColor(50.0, 50.0, 50.0)

    @Test
    fun generateAll() {
        outputDir.mkdirs()
        simpleCube()
        multipleShapes()
        complexScene()
        grid()
        pathExample()
        translateExample()
        scaleExample()
        rotateZExample()
        extrudeBefore()
        extrudeAfter()
        shapeCylinder()
        shapeKnot()
        shapeOctahedron()
        shapePrism()
        shapePyramid()
        shapeStairs()
    }

    // --- Scene screenshots ---

    @Test
    fun simpleCube() {
        AwtRenderer.renderToPng(400, 300, File(outputDir, "simple-cube.png")) {
            add(Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0), MATERIAL_BLUE)
        }
    }

    @Test
    fun multipleShapes() {
        AwtRenderer.renderToPng(680, 540, File(outputDir, "multiple-shapes.png")) {
            add(Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0), GREEN)
            add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), PURPLE)
            add(Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), MATERIAL_BLUE)
        }
    }

    @Test
    fun complexScene() {
        AwtRenderer.renderToPng(820, 680, File(outputDir, "complex-scene.png")) {
            add(Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), MATERIAL_BLUE)
            add(Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), MATERIAL_BLUE)
            add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), MATERIAL_BLUE)
            add(Stairs(Point(-1.0, 0.0, 0.0), 10), MATERIAL_BLUE)
            add(
                Stairs(Point(0.0, 3.0, 1.0), 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
                MATERIAL_BLUE
            )
            add(Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), MATERIAL_BLUE)
            add(Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), MATERIAL_BLUE)
            add(
                Stairs(Point(2.0, 0.0, 2.0), 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
                MATERIAL_BLUE
            )
            add(Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), YELLOW)
            add(Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), PURPLE)
            add(Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), TEAL)
            add(Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), LIGHT_GREEN)
            add(Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), GRAY)
            add(Octahedron(Point(3.0, 2.0, 3.2)), TEAL)
        }
    }

    @Test
    fun grid() {
        AwtRenderer.renderToPng(680, 540, File(outputDir, "grid.png")) {
            for (x in 0 until 10) {
                add(
                    Path(
                        listOf(
                            Point(x.toDouble(), 0.0, 0.0),
                            Point(x.toDouble(), 10.0, 0.0),
                            Point(x.toDouble(), 0.0, 0.0)
                        )
                    ),
                    GREEN
                )
            }
            for (y in 0 until 10) {
                add(
                    Path(
                        listOf(
                            Point(0.0, y.toDouble(), 0.0),
                            Point(10.0, y.toDouble(), 0.0),
                            Point(0.0, y.toDouble(), 0.0)
                        )
                    ),
                    GREEN
                )
            }
            add(Prism(Point.ORIGIN), MATERIAL_BLUE)
            add(
                Path(
                    listOf(
                        Point.ORIGIN,
                        Point(0.0, 0.0, 10.0),
                        Point.ORIGIN
                    )
                ),
                RED
            )
        }
    }

    // --- Operation screenshots (before/after pairs) ---

    @Test
    fun pathExample() {
        AwtRenderer.renderToPng(500, 400, File(outputDir, "path.png")) {
            add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), BLUE)
            add(
                Path(
                    listOf(
                        Point(0.5, 0.5, 1.0),
                        Point(2.5, 0.5, 1.0),
                        Point(2.5, 2.5, 1.0),
                        Point(0.5, 2.5, 1.0)
                    )
                ),
                GREEN
            )
        }
    }

    @Test
    fun translateExample() {
        // Single image: blue cube at origin, red cube at translated position
        AwtRenderer.renderToPng(520, 420, File(outputDir, "translate.png")) {
            val cube = Prism(Point.ORIGIN)
            // Original position
            add(cube, BLUE)
            // Translated copy — offset in x, y, and z so all 3 axes of movement are visible
            add(cube.translate(2.0, 1.0, 1.5), RED)
        }
    }

    @Test
    fun scaleExample() {
        // Single image: blue unit cube sitting on top of the red scaled slab
        // so the size difference is immediately obvious in the same viewport
        AwtRenderer.renderToPng(560, 440, File(outputDir, "scale.png")) {
            val cube = Prism(Point.ORIGIN)
            // Scaled version: 3x wider, 3x deeper, half height — red slab
            add(cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5), RED)
            // Original unit cube sitting on top of the slab for direct comparison
            add(Prism(Point(1.0, 1.0, 0.5)), BLUE)
        }
    }

    @Test
    fun rotateZExample() {
        AwtRenderer.renderToPng(520, 420, File(outputDir, "rotate-z.png")) {
            val cube = Prism(Point.ORIGIN, 3.0, 3.0, 1.0)
            add(cube, RED)
            add(
                cube.rotateZ(Point(1.5, 1.5, 0.0), PI / 12).translate(0.0, 0.0, 1.1),
                BLUE
            )
        }
    }

    @Test
    fun extrudeBefore() {
        AwtRenderer.renderToPng(500, 400, File(outputDir, "extrude-before.png")) {
            add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), BLUE)
            add(
                Path(
                    listOf(
                        Point(0.2, 0.2, 1.0),
                        Point(2.8, 0.2, 1.0),
                        Point(2.8, 2.8, 1.0)
                    )
                ),
                RED
            )
        }
    }

    @Test
    fun extrudeAfter() {
        AwtRenderer.renderToPng(500, 400, File(outputDir, "extrude-after.png")) {
            add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), BLUE)
            add(
                Shape.extrude(
                    Path(
                        listOf(
                            Point(0.2, 0.2, 1.0),
                            Point(2.8, 0.2, 1.0),
                            Point(2.8, 2.8, 1.0)
                        )
                    ),
                    1.0
                ),
                RED
            )
        }
    }

    // --- Shape catalog ---

    @Test
    fun shapeCylinder() {
        AwtRenderer.renderToPng(360, 320, File(outputDir, "shape-cylinder.png")) {
            add(Cylinder(Point.ORIGIN, 1.0, 2.0, 30), BLUE)
        }
    }

    @OptIn(ExperimentalIsometricApi::class)
    @Test
    fun shapeKnot() {
        AwtRenderer.renderToPng(360, 320, File(outputDir, "shape-knot.png"), scale = 180.0) {
            add(Knot(Point(0.0, 0.0, 0.0)), GREEN)
        }
    }

    @Test
    fun shapeOctahedron() {
        AwtRenderer.renderToPng(360, 320, File(outputDir, "shape-octahedron.png"), scale = 180.0) {
            add(Octahedron(Point(0.0, 0.0, 0.0)), RED)
        }
    }

    @Test
    fun shapePrism() {
        AwtRenderer.renderToPng(360, 320, File(outputDir, "shape-prism.png")) {
            add(Prism(Point.ORIGIN, 2.0, 2.0, 2.0), YELLOW)
        }
    }

    @Test
    fun shapePyramid() {
        AwtRenderer.renderToPng(360, 320, File(outputDir, "shape-pyramid.png")) {
            add(Pyramid(Point.ORIGIN, 2.0, 2.0, 2.0), TEAL)
        }
    }

    @Test
    fun shapeStairs() {
        AwtRenderer.renderToPng(360, 320, File(outputDir, "shape-stairs.png"), scale = 140.0) {
            add(Stairs(Point.ORIGIN, 10), LIGHT_GREEN)
        }
    }
}
