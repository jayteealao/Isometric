@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import io.github.jayteealao.isometric.*
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.compose.runtime.Path as IsoPath
import io.github.jayteealao.isometric.compose.scenes.AlphaSampleScene
import io.github.jayteealao.isometric.compose.scenes.CameraControlScene
import io.github.jayteealao.isometric.compose.scenes.DoubleTapScene
import io.github.jayteealao.isometric.compose.scenes.DragLifecycleScene
import io.github.jayteealao.isometric.compose.scenes.DragNodeScene
import io.github.jayteealao.isometric.compose.scenes.ElevatedTileScene
import io.github.jayteealao.isometric.compose.scenes.HoverRecipeScene
import io.github.jayteealao.isometric.compose.scenes.LongPressConfigScene
import io.github.jayteealao.isometric.compose.scenes.LongPressGridScene
import io.github.jayteealao.isometric.compose.scenes.NodeIdRowScene
import io.github.jayteealao.isometric.compose.scenes.OccludedPickScene
import io.github.jayteealao.isometric.compose.scenes.OnClickRowScene
import io.github.jayteealao.isometric.compose.scenes.PerNodeCallbackScene
import io.github.jayteealao.isometric.compose.scenes.PinchZoomRecipeScene
import io.github.jayteealao.isometric.shapes.*
import kotlin.math.PI
import org.junit.Rule
import org.junit.Test

/**
 * Paparazzi snapshot tests for isometric scene rendering.
 * Ported from legacy IsometricCanvas tests to use the runtime IsometricScene API.
 *
 * Each scene is composed to a settled node tree by [IsometricSnapshotHarness] *before* the snapshot
 * is taken, then drawn by [IsometricSnapshotCanvas]. This is deliberate: `IsometricScene` populates
 * its node tree from a `DisposableEffect` that fires after the first frame commits, which is exactly
 * the frame Paparazzi captures — so snapshotting `IsometricScene` directly records blank frames that
 * guard nothing. See [IsometricSnapshotHarness] for the full rationale.
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

    /**
     * Build [content] into a settled [io.github.jayteealao.isometric.compose.runtime.GroupNode] and
     * snapshot it through the production renderer. Replaces the old
     * `paparazzi.snapshot { Box { IsometricScene { content } } }` shape so the captured frame
     * contains real geometry rather than `IsometricScene`'s deferred (blank) first frame.
     */
    private fun snapshotScene(
        width: Dp,
        height: Dp,
        content: @Composable IsometricScope.() -> Unit
    ) {
        val root = IsometricSnapshotHarness.renderToGroupNode(content)
        paparazzi.snapshot {
            Box(modifier = Modifier.size(width, height)) {
                IsometricSnapshotCanvas(root)
            }
        }
    }

    @Test
    fun sampleOne() {
        snapshotScene(680.dp, 220.dp) {
            Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), color = MATERIAL_BLUE)
        }
    }

    @Test
    fun sampleTwo() {
        snapshotScene(680.dp, 540.dp) {
            Shape(geometry = Prism(Point(0.0, 0.0, 0.0), 4.0, 4.0, 2.0), color = GREEN)
            Shape(geometry = Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), color = PURPLE)
            Shape(geometry = Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), color = MATERIAL_BLUE)
        }
    }

    @Test
    fun sampleThree() {
        snapshotScene(820.dp, 680.dp) {
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

    @Test
    fun grid() {
        snapshotScene(680.dp, 540.dp) {
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

    @Test
    fun path() {
        snapshotScene(680.dp, 440.dp) {
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

    @Test
    fun translate() {
        snapshotScene(680.dp, 440.dp) {
            val cube = Prism(Point(0.0, 0.0, 0.0))
            Shape(geometry = cube, color = RED)
            Shape(geometry = cube.translate(0.0, 0.0, 1.1), color = BLUE)
            Shape(geometry = cube.translate(0.0, 0.0, 2.2), color = RED)
        }
    }

    @Test
    fun scale() {
        snapshotScene(680.dp, 440.dp) {
            val cube = Prism(Point.ORIGIN)
            Shape(geometry = cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5), color = RED)
            Shape(
                geometry = cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5).translate(0.0, 0.0, 0.6),
                color = BLUE
            )
        }
    }

    @Test
    fun rotateZ() {
        snapshotScene(680.dp, 440.dp) {
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

    @Test
    fun extrude() {
        snapshotScene(680.dp, 440.dp) {
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

    @Test
    fun cylinder() {
        snapshotScene(680.dp, 440.dp) {
            Shape(geometry = Cylinder(Point(1.0, 1.0, 1.0), 0.5, 2.0, 30), color = BLUE)
        }
    }

    @Test
    fun knot() {
        snapshotScene(680.dp, 440.dp) {
            Shape(geometry = Knot(Point(1.0, 1.0, 1.0)), color = GREEN)
        }
    }

    @Test
    fun octahedron() {
        snapshotScene(680.dp, 440.dp) {
            Shape(geometry = Octahedron(Point(1.0, 1.0, 1.0)), color = RED)
        }
    }

    @Test
    fun prism() {
        snapshotScene(680.dp, 440.dp) {
            Shape(geometry = Prism(Point(1.0, 1.0, 1.0)), color = YELLOW)
        }
    }

    @Test
    fun pyramid() {
        snapshotScene(680.dp, 440.dp) {
            Shape(geometry = Pyramid(Point(1.0, 1.0, 1.0)), color = TEAL)
        }
    }

    @Test
    fun stairs() {
        snapshotScene(680.dp, 440.dp) {
            Shape(geometry = Stairs(Point(1.0, 1.0, 1.0), 10), color = LIGHT_GREEN)
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
        snapshotScene(800.dp, 600.dp) {
            NodeIdRowScene()
        }
    }

    @Test
    fun onClickRowScene() {
        // 5 unit cubes in a row with the 4th selected (height=2, yellow).
        snapshotScene(800.dp, 600.dp) {
            OnClickRowScene(selectedIndex = 3)
        }
    }

    @Test
    fun longPressGridScene() {
        // 3x3 grid default state — primary marker for the over-aggressive-edge
        // regression. Pre-screen-overlap-gate: back-right cube renders with
        // only its top face visible. With the gate: all cubes render with all
        // expected faces.
        snapshotScene(800.dp, 600.dp) {
            LongPressGridScene()
        }
    }

    @Test
    fun alphaSampleScene() {
        // Mixed geometry: prism + cylinder + pyramid + 3 small prisms in a row.
        snapshotScene(800.dp, 600.dp) {
            AlphaSampleScene()
        }
    }

    @Test
    fun dragLifecycleScene() {
        // Floor slab + two contrasting prisms — the DragLifecycleSample geometry.
        snapshotScene(800.dp, 600.dp) {
            DragLifecycleScene()
        }
    }

    @Test
    fun dragNodeScene() {
        // Ground slab + five named prisms in a cross — the DragNodeSample hero geometry,
        // default (no-selection) state.
        snapshotScene(800.dp, 600.dp) {
            DragNodeScene()
        }
    }

    @Test
    fun longPressConfigScene() {
        // Ground + single blue hold-target prism — the LongPressConfigSample geometry.
        // The configurable long-press timeout has no visual footprint; this pins the render.
        snapshotScene(800.dp, 600.dp) {
            LongPressConfigScene()
        }
    }

    @Test
    fun doubleTapScene() {
        // Ground + single orange target prism — the DoubleTapSample geometry.
        snapshotScene(800.dp, 600.dp) {
            DoubleTapScene()
        }
    }

    @Test
    fun perNodeCallbackScene() {
        // Path tile + Batch prisms + CustomNode tile — the PerNodeCallbackSample geometry,
        // exercising all three hittable node types in one render.
        snapshotScene(800.dp, 600.dp) {
            PerNodeCallbackScene()
        }
    }

    @Test
    fun cameraControlScene() {
        // Ground slab + prism/pyramid/cylinder — the CameraControlSample geometry, default
        // camera (pan = 0, zoom = 1). Pan/zoom/reset have no static footprint; this pins the render.
        snapshotScene(800.dp, 600.dp) {
            CameraControlScene()
        }
    }

    @Test
    fun pinchZoomRecipeScene() {
        // Ground slab + one landmark prism — the PinchZoomRecipeSample geometry, un-zoomed
        // baseline. The pinch → zoomBy mapping is a gesture with no static footprint.
        snapshotScene(800.dp, 600.dp) {
            PinchZoomRecipeScene()
        }
    }

    @Test
    fun hoverRecipeScene() {
        // Ground slab + orange target prism — the HoverRecipeSample geometry, not-hovered
        // baseline. Hover tint fires only for mouse/stylus, so this captures the un-hovered render.
        snapshotScene(800.dp, 600.dp) {
            HoverRecipeScene()
        }
    }

    @Test
    fun occludedPickScene() {
        // Two overlapping prisms — the OccludedPickSample geometry. Pins the occlusion render so a
        // regression in depth-sort or culling that broke the front/back overlap would change pixels.
        snapshotScene(800.dp, 600.dp) {
            OccludedPickScene()
        }
    }

    @Test
    fun elevatedTileScene() {
        // Ground plane + a tile raised to z=2 — the ElevatedTileSample geometry. Pins the elevated
        // render so a projection change that shifted the raised tile would be caught.
        snapshotScene(800.dp, 600.dp) {
            ElevatedTileScene()
        }
    }
}
