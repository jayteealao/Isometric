package io.github.jayteealao.isometric.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jayteealao.isometric.ExperimentalIsometricApi
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.ForEach
import io.github.jayteealao.isometric.compose.runtime.GestureConfig
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.RenderMode
import io.github.jayteealao.isometric.compose.runtime.SceneConfig
import io.github.jayteealao.isometric.shader.cylinderPerFace
import io.github.jayteealao.isometric.shader.octahedronPerFace
import io.github.jayteealao.isometric.shader.prismPerFace
import io.github.jayteealao.isometric.shader.pyramidPerFace
import io.github.jayteealao.isometric.shader.render.ProvideTextureRendering
import io.github.jayteealao.isometric.shader.texturedBitmap
import io.github.jayteealao.isometric.shader.TextureTransform
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Knot
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Stairs
import io.github.jayteealao.isometric.shader.Shape

class TexturedDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background,
                ) {
                    TexturedDemoScreen()
                }
            }
        }
    }
}

private enum class ShapeTab(val label: String, val description: String) {
    Prism(
        label = "Prism",
        description = "Left 2 cols: IDENTITY \u2014 Right 2 cols: tiling(2\u00d72 top, 1\u00d72 sides)",
    ),
    Octahedron(
        label = "Octahedron",
        description = "Left: single grass texture across all 8 faces \u2014 Right: PerFace with 8 distinct face colors",
    ),
    Pyramid(
        label = "Pyramid",
        description = "Left: grass texture on 4 laterals + dirt base \u2014 Right: PerFace with 4 distinct lateral colors + gray base",
    ),
    Cylinder(
        label = "Cylinder",
        description = "Left: brick (12-vert) \u2014 Mid: PerFace red/blue/brick (12-vert) \u2014 Right: brick (24-vert, demonstrates webgpu-ngon-faces cap fix)",
    ),
    Stairs(
        label = "Stairs",
        description = "Left: tread/riser/side per-face textures (stepCount=5) \u2014 Right: red/green/blue per-face palette",
    ),
    Knot(
        label = "Knot",
        description = "Left: brick on all 20 faces (18 sub-prism + 2 custom quads) \u2014 Right: grass texture; depth-sort artifacts are pre-existing",
    ),
}

@Composable
private fun TexturedDemoScreen() {
    val renderModeSaver = Saver<RenderMode, String>(
        save = { mode ->
            when (mode) {
                is RenderMode.Canvas -> when (mode.compute) {
                    RenderMode.Canvas.Compute.Cpu -> "Canvas.Cpu"
                    RenderMode.Canvas.Compute.WebGpu -> "Canvas.WebGpu"
                }
                is RenderMode.WebGpu -> "WebGpu"
                else -> "Canvas.Cpu"
            }
        },
        restore = { s ->
            when (s) {
                "Canvas.WebGpu" -> RenderMode.Canvas(compute = RenderMode.Canvas.Compute.WebGpu)
                "WebGpu" -> RenderMode.WebGpu()
                else -> RenderMode.Canvas()
            }
        },
    )
    val shapeTabSaver = Saver<ShapeTab, String>(
        save = { it.name },
        restore = { ShapeTab.valueOf(it) },
    )
    var renderMode by rememberSaveable(stateSaver = renderModeSaver) {
        mutableStateOf<RenderMode>(RenderMode.Canvas())
    }
    var shapeTab by rememberSaveable(stateSaver = shapeTabSaver) {
        mutableStateOf(ShapeTab.Prism)
    }

    val tileMaterial = remember {
        prismPerFace {
            top = texturedBitmap(TextureAssets.grassTop)
            sides = texturedBitmap(TextureAssets.dirtSide)
            default = texturedBitmap(TextureAssets.dirtSide)
        }
    }

    // Tiling 2×2 on top, tiling 1×2 on sides — exercises TextureTransform path (AC1 / AC4)
    val tilingMaterial = remember {
        prismPerFace {
            top = texturedBitmap(TextureAssets.grassTop, transform = TextureTransform.tiling(2f, 2f))
            sides = texturedBitmap(TextureAssets.dirtSide, transform = TextureTransform.tiling(1f, 2f))
            default = texturedBitmap(TextureAssets.dirtSide, transform = TextureTransform.tiling(1f, 2f))
        }
    }

    val octahedronTextured = remember { texturedBitmap(TextureAssets.grassTop) }
    val octahedronPerFaceMat = remember {
        octahedronPerFace {
            face(OctahedronFace.UPPER_0, IsoColor(220, 50, 50))
            face(OctahedronFace.UPPER_1, IsoColor(50, 180, 50))
            face(OctahedronFace.UPPER_2, IsoColor(50, 80, 220))
            face(OctahedronFace.UPPER_3, IsoColor(220, 200, 50))
            face(OctahedronFace.LOWER_0, IsoColor(220, 120, 220))
            face(OctahedronFace.LOWER_1, IsoColor(120, 220, 220))
            face(OctahedronFace.LOWER_2, IsoColor(220, 180, 120))
            face(OctahedronFace.LOWER_3, IsoColor(120, 180, 220))
            default = IsoColor(150, 150, 150)
        }
    }

    val pyramidTextured = remember {
        pyramidPerFace {
            allLaterals(texturedBitmap(TextureAssets.grassTop))
            base = texturedBitmap(TextureAssets.dirtSide)
        }
    }
    val pyramidPerFaceMat = remember {
        pyramidPerFace {
            lateral(0, IsoColor(220, 50, 50))
            lateral(1, IsoColor(50, 180, 50))
            lateral(2, IsoColor(50, 80, 220))
            lateral(3, IsoColor(220, 200, 50))
            base = IsoColor(150, 150, 150)
            default = IsoColor(100, 100, 100)
        }
    }

    val cylinderTextured = remember { texturedBitmap(TextureAssets.brick) }
    val cylinderPerFaceMat = remember {
        cylinderPerFace {
            top = IsoColor(220, 50, 50)
            bottom = IsoColor(50, 80, 220)
            side = texturedBitmap(TextureAssets.brick)
            default = IsoColor(150, 150, 150)
        }
    }

    val stairsTexturedMat = remember {
        IsometricMaterial.PerFace.Stairs(
            tread = texturedBitmap(TextureAssets.grassTop),
            riser = texturedBitmap(TextureAssets.brick),
            side = texturedBitmap(TextureAssets.dirtSide),
            default = texturedBitmap(TextureAssets.dirtSide),
        )
    }
    val stairsPerFaceMat = remember {
        IsometricMaterial.PerFace.Stairs(
            tread = IsoColor(220, 50, 50),
            riser = IsoColor(50, 180, 50),
            side = IsoColor(50, 80, 220),
            default = IsoColor(150, 150, 150),
        )
    }

    val knotBrickMaterial = remember { texturedBitmap(TextureAssets.brick) }
    val knotGrassMaterial = remember { texturedBitmap(TextureAssets.grassTop) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Shape tabs — matches the ScrollableTabRow pattern used by ComposeActivity
        // and RuntimeApiActivity.
        ScrollableTabRow(
            selectedTabIndex = shapeTab.ordinal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (tab in ShapeTab.entries) {
                Tab(
                    selected = shapeTab == tab,
                    onClick = { shapeTab = tab },
                    text = { Text(tab.label) },
                )
            }
        }

        // Info card + render-mode pills
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Textured Materials", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = shapeTab.description, style = MaterialTheme.typography.body2)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Render mode", style = MaterialTheme.typography.caption)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TogglePill(
                        label = "Canvas",
                        selected = renderMode is RenderMode.Canvas
                            && (renderMode as RenderMode.Canvas).compute == RenderMode.Canvas.Compute.Cpu,
                        onClick = { renderMode = RenderMode.Canvas() },
                    )
                    TogglePill(
                        label = "Canvas + GPU Sort",
                        selected = renderMode is RenderMode.Canvas
                            && (renderMode as RenderMode.Canvas).compute == RenderMode.Canvas.Compute.WebGpu,
                        onClick = { renderMode = RenderMode.Canvas(compute = RenderMode.Canvas.Compute.WebGpu) },
                    )
                    TogglePill(
                        label = "Full WebGPU",
                        selected = renderMode is RenderMode.WebGpu,
                        onClick = { renderMode = RenderMode.WebGpu() },
                    )
                }
            }
        }

        // Scene
        Box(modifier = Modifier.weight(1f)) {
            when (shapeTab) {
                ShapeTab.Prism -> TexturedPrismGridScene(
                    renderMode = renderMode,
                    tileMaterial = tileMaterial,
                    tilingMaterial = tilingMaterial,
                )
                ShapeTab.Octahedron -> TexturedOctahedronScene(
                    renderMode = renderMode,
                    texturedMaterial = octahedronTextured,
                    perFaceMaterial = octahedronPerFaceMat,
                )
                ShapeTab.Pyramid -> TexturedPyramidScene(
                    renderMode = renderMode,
                    texturedMaterial = pyramidTextured,
                    perFaceMaterial = pyramidPerFaceMat,
                )
                ShapeTab.Cylinder -> TexturedCylinderScene(
                    renderMode = renderMode,
                    texturedMaterial = cylinderTextured,
                    perFaceMaterial = cylinderPerFaceMat,
                )
                ShapeTab.Stairs -> TexturedStairsScene(
                    renderMode = renderMode,
                    texturedMaterial = stairsTexturedMat,
                    perFaceMaterial = stairsPerFaceMat,
                )
                ShapeTab.Knot -> TexturedKnotScene(
                    renderMode = renderMode,
                    leftMaterial = knotBrickMaterial,
                    rightMaterial = knotGrassMaterial,
                )
            }
        }
    }
}

/**
 * Shared wrapper used by every demo scene in [TexturedDemoScreen].
 *
 * Encapsulates the repeated [ProvideTextureRendering] + [IsometricScene] boilerplate so that
 * individual scene composables only describe their shapes. The [SceneConfig] is fixed to the
 * demo-appropriate defaults (broad-phase sort enabled, no native canvas, gestures disabled);
 * only [renderMode] is forwarded from the parent screen.
 */
@Composable
private fun TexturedIsometricScene(
    renderMode: RenderMode,
    content: @Composable IsometricScope.() -> Unit,
) {
    ProvideTextureRendering {
        IsometricScene(
            modifier = Modifier.fillMaxSize(),
            config = SceneConfig(
                renderOptions = RenderOptions.Default.copy(enableBroadPhaseSort = true),
                renderMode = renderMode,
                useNativeCanvas = false,
                gestures = GestureConfig.Disabled,
            ),
            content = content,
        )
    }
}

@Composable
private fun TexturedPrismGridScene(
    renderMode: RenderMode,
    tileMaterial: IsometricMaterial,
    tilingMaterial: IsometricMaterial,
) {
    TexturedIsometricScene(renderMode = renderMode) {
        ForEach((0 until 4).toList()) { col ->
            ForEach((0 until 4).toList()) { row ->
                // Columns 0–1: IDENTITY transform (AC5 baseline)
                // Columns 2–3: tiling(2×2 top, 1×2 sides) (AC1 / AC4 exercise)
                val material = if (col < 2) tileMaterial else tilingMaterial
                Shape(
                    geometry = Prism(
                        position = Point(
                            (col - 1.5) * 1.05,
                            (row - 1.5) * 1.05,
                            0.0,
                        ),
                        width = 1.0,
                        depth = 1.0,
                        height = 1.0,
                    ),
                    material = material,
                )
            }
        }
    }
}

@Composable
private fun TexturedOctahedronScene(
    renderMode: RenderMode,
    texturedMaterial: IsometricMaterial,
    perFaceMaterial: IsometricMaterial,
) {
    TexturedIsometricScene(renderMode = renderMode) {
        // Two Octahedrons side by side; scale = 3.0 so each fills a substantial
        // portion of the viewport instead of shrinking down to match the whole-
        // scene auto-framing of small unit shapes.
        Shape(
            geometry = Octahedron(Point(-2.0, 0.0, 0.0)),
            material = texturedMaterial,
            scale = 3.0,
        )
        Shape(
            geometry = Octahedron(Point(2.0, 0.0, 0.0)),
            material = perFaceMaterial,
            scale = 3.0,
        )
    }
}

@Composable
private fun TexturedPyramidScene(
    renderMode: RenderMode,
    texturedMaterial: IsometricMaterial,
    perFaceMaterial: IsometricMaterial,
) {
    TexturedIsometricScene(renderMode = renderMode) {
        Shape(
            geometry = Pyramid(Point(-1.5, 0.0, 0.0)),
            material = texturedMaterial,
            scale = 3.0,
        )
        Shape(
            geometry = Pyramid(Point(1.5, 0.0, 0.0)),
            material = perFaceMaterial,
            scale = 3.0,
        )
    }
}

@Composable
private fun TexturedCylinderScene(
    renderMode: RenderMode,
    texturedMaterial: IsometricMaterial,
    perFaceMaterial: IsometricMaterial,
) {
    TexturedIsometricScene(renderMode = renderMode) {
        // Left: textured barrel at 12 verts. Pre-webgpu-ngon-faces this rendered with a
        // partial-wedge cap in Full WebGPU because GpuUvCoordsBuffer's 48-byte stride
        // truncated UV indices 6..N-1 to (0,0).
        Shape(
            geometry = Cylinder(
                position = Point(-2.5, 0.0, 0.0),
                radius = 0.5,
                height = 1.5,
                vertices = 12,
            ),
            material = texturedMaterial,
            scale = 2.0,
        )
        // Mid: per-face colors at 12 verts exercising resolveForFace dispatch via CylinderFace.
        Shape(
            geometry = Cylinder(
                position = Point(0.0, 0.0, 0.0),
                radius = 0.5,
                height = 1.5,
                vertices = 12,
            ),
            material = perFaceMaterial,
            scale = 2.0,
        )
        // Right: 24-vertex cylinder. Validates AC1 of webgpu-ngon-faces — the cap should
        // render as a complete brick disk in Full WebGPU, pixel-equivalent to Canvas.
        Shape(
            geometry = Cylinder(
                position = Point(2.5, 0.0, 0.0),
                radius = 0.5,
                height = 1.5,
                vertices = 24,
            ),
            material = texturedMaterial,
            scale = 2.0,
        )
    }
}

@Composable
private fun TexturedStairsScene(
    renderMode: RenderMode,
    texturedMaterial: IsometricMaterial,
    perFaceMaterial: IsometricMaterial,
) {
    TexturedIsometricScene(renderMode = renderMode) {
        // Left: stepCount=5 staircase with grass treads, brick risers, dirt sides.
        // The 12-vertex zigzag side face validates AC2 of webgpu-ngon-faces — should
        // render with full UV pattern (no truncation, no I-2 triangulation slash).
        Shape(
            geometry = Stairs(Point(-1.5, 0.0, 0.0), stepCount = 5),
            material = texturedMaterial,
            scale = 2.0,
        )
        // Right: per-face palette exercises StairsFace dispatch.
        Shape(
            geometry = Stairs(Point(1.5, 0.0, 0.0), stepCount = 5),
            material = perFaceMaterial,
            scale = 2.0,
        )
    }
}

@OptIn(ExperimentalIsometricApi::class)
@Composable
private fun TexturedKnotScene(
    renderMode: RenderMode,
    leftMaterial: IsometricMaterial,
    rightMaterial: IsometricMaterial,
) {
    TexturedIsometricScene(renderMode = renderMode) {
        // Left: brick texture across all 20 Knot faces — exercises the full
        // bag-of-primitives delegation (forPrismFace x18 + quadBboxUvs x2).
        Shape(
            geometry = Knot(Point(-1.5, 0.0, 0.0)),
            material = leftMaterial,
            scale = 3.0,
        )
        // Right: grass texture for visual contrast on the same geometry.
        Shape(
            geometry = Knot(Point(1.5, 0.0, 0.0)),
            material = rightMaterial,
            scale = 3.0,
        )
    }
}
