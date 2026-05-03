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
import io.github.jayteealao.isometric.compose.scenes.AlphaSampleScene
import io.github.jayteealao.isometric.compose.scenes.LongPressGridScene
import io.github.jayteealao.isometric.compose.scenes.NodeIdRowScene
import io.github.jayteealao.isometric.compose.scenes.OnClickRowScene
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
                    Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), color = MATERIAL_BLUE)
                }
            }
        }
    }

    @Test
    fun sampleTwo() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 540.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0), color = GREEN)
                    Shape(geometry = Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), color = PURPLE)
                    Shape(geometry = Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), color = MATERIAL_BLUE)
                }
            }
        }
    }

    @Test
    fun sampleThree() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(820.dp, 680.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point(1.0, -1.0, 0.0), 4.0, 5.0, 2.0), color = MATERIAL_BLUE)
                    Shape(geometry = Prism(Point(0.0, 0.0, 0.0), 1.0, 4.0, 1.0), color = MATERIAL_BLUE)
                    Shape(geometry = Prism(Point(-1.0, 1.0, 0.0), 1.0, 3.0, 1.0), color = MATERIAL_BLUE)
                    Shape(geometry = Stairs(Point(-1.0, 0.0, 0.0), 10), color = MATERIAL_BLUE)
                    Shape(
                        geometry = Stairs(Point(0.0, 3.0, 1.0), 10).rotateZ(Point(0.5, 3.5, 1.0), -PI / 2),
                        color = MATERIAL_BLUE
                    )
                    Shape(geometry = Prism(Point(3.0, 0.0, 2.0), 2.0, 4.0, 1.0), color = MATERIAL_BLUE)
                    Shape(geometry = Prism(Point(2.0, 1.0, 2.0), 1.0, 3.0, 1.0), color = MATERIAL_BLUE)
                    Shape(
                        geometry = Stairs(Point(2.0, 0.0, 2.0), 10).rotateZ(Point(2.5, 0.5, 0.0), -PI / 2),
                        color = MATERIAL_BLUE
                    )
                    Shape(geometry = Pyramid(Point(2.0, 3.0, 3.0)).scale(Point(2.0, 4.0, 3.0), 0.5), color = YELLOW)
                    Shape(geometry = Pyramid(Point(4.0, 3.0, 3.0)).scale(Point(5.0, 4.0, 3.0), 0.5), color = PURPLE)
                    Shape(geometry = Pyramid(Point(4.0, 1.0, 3.0)).scale(Point(5.0, 1.0, 3.0), 0.5), color = TEAL)
                    Shape(geometry = Pyramid(Point(2.0, 1.0, 3.0)).scale(Point(2.0, 1.0, 3.0), 0.5), color = LIGHT_GREEN)
                    Shape(geometry = Prism(Point(3.0, 2.0, 3.0), 1.0, 1.0, 0.2), color = GRAY)
                    Shape(
                        geometry = Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(Point(3.5, 2.5, 0.0), 0.0),
                        color = TEAL
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
                    Shape(geometry = Prism(Point.ORIGIN), color = MATERIAL_BLUE)
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
                    Shape(geometry = Prism(Point.ORIGIN, 3.0, 3.0, 1.0), color = BLUE)
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
                    Shape(geometry = cube, color = RED)
                    Shape(geometry = cube.translate(0.0, 0.0, 1.1), color = BLUE)
                    Shape(geometry = cube.translate(0.0, 0.0, 2.2), color = RED)
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
                    Shape(geometry = cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5), color = RED)
                    Shape(
                        geometry = cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5).translate(0.0, 0.0, 0.6),
                        color = BLUE
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
                    Shape(geometry = cube, color = RED)
                    Shape(
                        geometry = cube
                            /* (1.5, 1.5) is the center of the prism */
                            .rotateZ(Point(1.5, 1.5, 0.0), PI / 12)
                            .translate(0.0, 0.0, 1.1),
                        color = BLUE
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
                    Shape(geometry = Prism(Point.ORIGIN, 3.0, 3.0, 1.0), color = BLUE)
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
                        color = RED
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
                    Shape(geometry = Cylinder(Point(1.0, 1.0, 1.0), 0.5, 2.0, 30), color = BLUE)
                }
            }
        }
    }

    @Test
    fun knot() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Knot(Point(1.0, 1.0, 1.0)), color = GREEN)
                }
            }
        }
    }

    @Test
    fun octahedron() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Octahedron(Point(1.0, 1.0, 1.0)), color = RED)
                }
            }
        }
    }

    @Test
    fun prism() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Prism(Point(1.0, 1.0, 1.0)), color = YELLOW)
                }
            }
        }
    }

    @Test
    fun pyramid() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Pyramid(Point(1.0, 1.0, 1.0)), color = TEAL)
                }
            }
        }
    }

    @Test
    fun stairs() {
        paparazzi.snapshot {
            Box(modifier = Modifier.size(680.dp, 440.dp)) {
                IsometricScene {
                    Shape(geometry = Stairs(Point(1.0, 1.0, 1.0), 10), color = LIGHT_GREEN)
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // depth-sort regression baselines.
    //
    // Four scene-specific captures pinning the depth-sort algorithm against
    // two bug classes that occur in the InteractionSamples:
    //
    //   1. Row-layout shared-edge case (NodeIdSample): the canonical
    //      shared-edge ordering bug at the boundary between adjacent
    //      different-height prisms.
    //   2. Row-layout with one shape elevated (OnClickSample): tests dynamic
    //      height-change adjacency.
    //   3. 3x3 grid (LongPressSample): primary marker for the
    //      over-aggressive-edge regression that the screen-overlap gate
    //      (`IntersectionUtils.hasInteriorIntersection`) prevents.
    //   4. Mixed geometry (AlphaSample): same regression class as (3) with
    //      a heterogenous shape mix (prism + cylinder + pyramid + small prisms).
    // ----------------------------------------------------------------------

    @Test
    fun nodeIdRowScene() {
        // 4 buildings in a row at varying heights — the NodeIdSample case.
        paparazzi.snapshot {
            Box(modifier = Modifier.size(800.dp, 600.dp)) {
                IsometricScene {
                    NodeIdRowScene()
                }
            }
        }
    }

    @Test
    fun onClickRowScene() {
        // 5 unit cubes in a row with the 4th selected (height=2, yellow).
        paparazzi.snapshot {
            Box(modifier = Modifier.size(800.dp, 600.dp)) {
                IsometricScene {
                    OnClickRowScene(selectedIndex = 3)
                }
            }
        }
    }

    @Test
    fun longPressGridScene() {
        // 3x3 grid default state — primary marker for the over-aggressive-edge
        // regression. Pre-screen-overlap-gate: back-right cube renders with
        // only its top face visible. With the gate: all cubes render with all
        // expected faces.
        paparazzi.snapshot {
            Box(modifier = Modifier.size(800.dp, 600.dp)) {
                IsometricScene {
                    LongPressGridScene()
                }
            }
        }
    }

    @Test
    fun alphaSampleScene() {
        // Mixed geometry: prism + cylinder + pyramid + 3 small prisms in a row.
        paparazzi.snapshot {
            Box(modifier = Modifier.size(800.dp, 600.dp)) {
                IsometricScene {
                    AlphaSampleScene()
                }
            }
        }
    }
}
