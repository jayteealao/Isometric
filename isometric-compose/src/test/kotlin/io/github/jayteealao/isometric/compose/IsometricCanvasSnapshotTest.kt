@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import io.github.jayteealao.isometric.*
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.compose.runtime.Path as IsoPath
import io.github.jayteealao.isometric.compose.runtime.Group
import io.github.jayteealao.isometric.shapes.*
import kotlin.math.PI
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi snapshot tests for isometric scene rendering.
 * Ported from legacy IsometricCanvas tests to use the runtime IsometricScene API.
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
                IsometricScene {
                    Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), material = MATERIAL_BLUE)
                }
            }
        }
    }

    @Test
    fun sampleTwo() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 540.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0), material = GREEN)
                    Shape(geometry = Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), material = PURPLE)
                    Shape(geometry = Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), material = MATERIAL_BLUE)
                }
            }
        }
    }

    @Test
    fun sampleThree() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(820.dp, 680.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), material = MATERIAL_BLUE)
                    Shape(geometry = Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), material = MATERIAL_BLUE)
                    Shape(geometry = Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), material = MATERIAL_BLUE)
                    Shape(geometry = Stairs(Point(-1.0, 0.0, 0.0), 10), material = MATERIAL_BLUE)
                    Shape(
                        geometry = Stairs(Point(0.0, 3.0, 1.0), 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
                        material = MATERIAL_BLUE
                    )
                    Shape(geometry = Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), material = MATERIAL_BLUE)
                    Shape(geometry = Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), material = MATERIAL_BLUE)
                    Shape(
                        geometry = Stairs(Point(2.0, 0.0, 2.0), 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
                        material = MATERIAL_BLUE
                    )
                    Shape(geometry = Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), material = YELLOW)
                    Shape(geometry = Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), material = PURPLE)
                    Shape(geometry = Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), material = TEAL)
                    Shape(geometry = Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), material = LIGHT_GREEN)
                    Shape(geometry = Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), material = GRAY)
                    Shape(
                        geometry = Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
                        material = TEAL
                    )
                }
            }
        }
    }

    @Test
    fun grid() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 540.dp)) {
                IsometricScene {
                    // Vertical grid lines
                    for (x in 0 until 10) {
                        IsoPath(
                            path = Path(
                                listOf(
                                    Point(x.toDouble(), 0.0, 0.0),
                                    Point(x.toDouble(), 10.0, 0.0),
                                    Point(x.toDouble(), 0.0, 0.0)
                                )
                            ),
                            color = GREEN
                        )
                    }
                    // Horizontal grid lines
                    for (y in 0 until 10) {
                        IsoPath(
                            path = Path(
                                listOf(
                                    Point(0.0, y.toDouble(), 0.0),
                                    Point(10.0, y.toDouble(), 0.0),
                                    Point(0.0, y.toDouble(), 0.0)
                                )
                            ),
                            color = GREEN
                        )
                    }
                    Shape(geometry = Prism(Point.ORIGIN), material = MATERIAL_BLUE)
                    IsoPath(
                        path = Path(
                            listOf(
                                Point.ORIGIN,
                                Point(0.0, 0.0, 10.0),
                                Point.ORIGIN
                            )
                        ),
                        color = RED
                    )
                }
            }
        }
    }

    @Test
    fun path() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point.ORIGIN, 3.0, 3.0, 1.0), material = BLUE)
                    IsoPath(
                        path = Path(
                            listOf(
                                Point(1.0, 1.0, 1.0),
                                Point(2.0, 1.0, 1.0),
                                Point(2.0, 2.0, 1.0),
                                Point(1.0, 2.0, 1.0)
                            )
                        ),
                        color = GREEN
                    )
                }
            }
        }
    }

    @Test
    fun translate() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    val cube = Prism(Point(0.0, 0.0, 0.0))
                    Shape(geometry = cube, material = RED)
                    Shape(geometry = cube.translate(0.0, 0.0, 1.1), material = BLUE)
                    Shape(geometry = cube.translate(0.0, 0.0, 2.2), material = RED)
                }
            }
        }
    }

    @Test
    fun scale() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    val cube = Prism(Point.ORIGIN)
                    Shape(geometry = cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5), material = RED)
                    Shape(
                        geometry = cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5).translate(0.0, 0.0, 0.6),
                        material = BLUE
                    )
                }
            }
        }
    }

    @Test
    fun rotateZ() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    val cube = Prism(Point.ORIGIN, 3.0, 3.0, 1.0)
                    Shape(geometry = cube, material = RED)
                    Shape(
                        geometry = cube
                            /* (1.5, 1.5) is the center of the prism */
                            .rotateZ(Point(1.5, 1.5, 0.0), PI / 12)
                            .translate(0.0, 0.0, 1.1),
                        material = BLUE
                    )
                }
            }
        }
    }

    @Test
    fun extrude() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point.ORIGIN, 3.0, 3.0, 1.0), material = BLUE)
                    Shape(
                        geometry = io.github.jayteealao.isometric.Shape.extrude(
                            Path(
                                listOf(
                                    Point(1.0, 1.0, 1.0),
                                    Point(2.0, 1.0, 1.0),
                                    Point(2.0, 3.0, 1.0)
                                )
                            ),
                            0.3
                        ),
                        material = RED
                    )
                }
            }
        }
    }

    @Test
    fun cylinder() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    // vertices = 20 (default); the uv-generation-cylinder slice caps at 24
                    // for RenderCommand.faceVertexCount validator compatibility.
                    Shape(geometry = Cylinder(Point(1.0, 1.0, 1.0), 0.5, 2.0, 20), material = BLUE)
                }
            }
        }
    }

    @Test
    fun knot() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Knot(Point(1.0, 1.0, 1.0)), material = GREEN)
                }
            }
        }
    }

    @Test
    fun octahedron() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Octahedron(Point(1.0, 1.0, 1.0)), material = RED)
                }
            }
        }
    }

    @Test
    fun prism() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point(1.0, 1.0, 1.0)), material = YELLOW)
                }
            }
        }
    }

    @Test
    fun pyramid() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Pyramid(Point(1.0, 1.0, 1.0)), material = TEAL)
                }
            }
        }
    }

    @Test
    fun stairs() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Stairs(Point(1.0, 1.0, 1.0), 10), material = LIGHT_GREEN)
                }
            }
        }
    }
}
