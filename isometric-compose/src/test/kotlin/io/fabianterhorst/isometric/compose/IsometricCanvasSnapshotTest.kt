package io.fabianterhorst.isometric.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import io.fabianterhorst.isometric.*
import io.fabianterhorst.isometric.shapes.*
import kotlin.math.PI
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi snapshot tests for IsometricCanvas.
 * Ported from legacy Facebook screenshot tests in :lib module.
 */
class IsometricCanvasSnapshotTest {

    @get:Rule
    val paparazzi = Paparazzi()

    // Colors used in tests
    private val BLUE = IsoColor(50.0, 60.0, 160.0)
    private val GREEN = IsoColor(50.0, 160.0, 60.0)
    private val RED = IsoColor(160.0, 60.0, 50.0)
    private val TEAL = IsoColor(0.0, 180.0, 180.0)
    private val YELLOW = IsoColor(180.0, 180.0, 0.0)
    private val LIGHT_GREEN = IsoColor(40.0, 180.0, 40.0)
    private val PURPLE = IsoColor(180.0, 0.0, 180.0)
    private val MATERIAL_BLUE = IsoColor(33.0, 150.0, 243.0)
    private val GRAY = IsoColor(50.0, 50.0, 50.0)

    @Test
    fun sampleOne() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 220.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Prism(Point(0.0, 0.0, 0.0)), MATERIAL_BLUE)
                }
            }
        }
    }

    @Test
    fun sampleTwo() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 540.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0), GREEN)
                    add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), PURPLE)
                    add(Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), MATERIAL_BLUE)
                }
            }
        }
    }

    @Test
    fun sampleThree() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(820.dp, 680.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
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
                    add(
                        Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
                        TEAL
                    )
                }
            }
        }
    }

    @Test
    fun grid() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 540.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    // Vertical grid lines
                    for (x in 0 until 10) {
                        add(
                            Path(
                                arrayOf(
                                    Point(x.toDouble(), 0.0, 0.0),
                                    Point(x.toDouble(), 10.0, 0.0),
                                    Point(x.toDouble(), 0.0, 0.0)
                                )
                            ),
                            GREEN
                        )
                    }
                    // Horizontal grid lines
                    for (y in 0 until 10) {
                        add(
                            Path(
                                arrayOf(
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
                            arrayOf(
                                Point.ORIGIN,
                                Point(0.0, 0.0, 10.0),
                                Point.ORIGIN
                            )
                        ),
                        RED
                    )
                }
            }
        }
    }

    @Test
    fun path() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), BLUE)
                    add(
                        Path(
                            arrayOf(
                                Point(1.0, 1.0, 1.0),
                                Point(2.0, 1.0, 1.0),
                                Point(2.0, 2.0, 1.0),
                                Point(1.0, 2.0, 1.0)
                            )
                        ),
                        GREEN
                    )
                }
            }
        }
    }

    @Test
    fun translate() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    val cube = Prism(Point(0.0, 0.0, 0.0))
                    add(cube, RED)
                    add(cube.translate(0.0, 0.0, 1.1), BLUE)
                    add(cube.translate(0.0, 0.0, 2.2), RED)
                }
            }
        }
    }

    @Test
    fun scale() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    val cube = Prism(Point.ORIGIN)
                    add(cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5), RED)
                    add(
                        cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5).translate(0.0, 0.0, 0.6),
                        BLUE
                    )
                }
            }
        }
    }

    @Test
    fun rotateZ() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    val cube = Prism(Point.ORIGIN, 3.0, 3.0, 1.0)
                    add(cube, RED)
                    add(
                        cube
                            /* (1.5, 1.5) is the center of the prism */
                            .rotateZ(Point(1.5, 1.5, 0.0), PI / 12)
                            .translate(0.0, 0.0, 1.1),
                        BLUE
                    )
                }
            }
        }
    }

    @Test
    fun extrude() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Prism(Point.ORIGIN, 3.0, 3.0, 1.0), BLUE)
                    add(
                        Shape.extrude(
                            Path(
                                arrayOf(
                                    Point(1.0, 1.0, 1.0),
                                    Point(2.0, 1.0, 1.0),
                                    Point(2.0, 3.0, 1.0)
                                )
                            ),
                            0.3
                        ),
                        RED
                    )
                }
            }
        }
    }

    @Test
    fun cylinder() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Cylinder(Point(1.0, 1.0, 1.0), 0.5, 30, 2.0), BLUE)
                }
            }
        }
    }

    @Test
    fun knot() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Knot(Point(1.0, 1.0, 1.0)), GREEN)
                }
            }
        }
    }

    @Test
    fun octahedron() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Octahedron(Point(1.0, 1.0, 1.0)), RED)
                }
            }
        }
    }

    @Test
    fun prism() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Prism(Point(1.0, 1.0, 1.0)), YELLOW)
                }
            }
        }
    }

    @Test
    fun pyramid() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Pyramid(Point(1.0, 1.0, 1.0)), TEAL)
                }
            }
        }
    }

    @Test
    fun stairs() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                val state = rememberIsometricSceneState()
                IsometricCanvas(state = state) {
                    add(Stairs(Point(1.0, 1.0, 1.0), 10), LIGHT_GREEN)
                }
            }
        }
    }
}
