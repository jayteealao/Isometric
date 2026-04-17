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
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.ForEach
import io.github.jayteealao.isometric.compose.runtime.GestureConfig
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.RenderMode
import io.github.jayteealao.isometric.compose.runtime.SceneConfig
import io.github.jayteealao.isometric.shader.perFace
import io.github.jayteealao.isometric.shader.render.ProvideTextureRendering
import io.github.jayteealao.isometric.shader.texturedBitmap
import io.github.jayteealao.isometric.shader.TextureTransform
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.OctahedronFace
import io.github.jayteealao.isometric.shapes.Prism
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
}

@Composable
private fun TexturedDemoScreen() {
    var renderMode by remember { mutableStateOf<RenderMode>(RenderMode.Canvas()) }
    var shapeTab by remember { mutableStateOf(ShapeTab.Prism) }

    val tileMaterial = remember {
        perFace {
            top = texturedBitmap(TextureAssets.grassTop)
            sides = texturedBitmap(TextureAssets.dirtSide)
            default = texturedBitmap(TextureAssets.dirtSide)
        }
    }

    // Tiling 2×2 on top, tiling 1×2 on sides — exercises TextureTransform path (AC1 / AC4)
    val tilingMaterial = remember {
        perFace {
            top = texturedBitmap(TextureAssets.grassTop, transform = TextureTransform.tiling(2f, 2f))
            sides = texturedBitmap(TextureAssets.dirtSide, transform = TextureTransform.tiling(1f, 2f))
            default = texturedBitmap(TextureAssets.dirtSide, transform = TextureTransform.tiling(1f, 2f))
        }
    }

    val octahedronTextured = remember { texturedBitmap(TextureAssets.grassTop) }
    val octahedronPerFace = remember {
        IsometricMaterial.PerFace.Octahedron(
            byIndex = mapOf(
                OctahedronFace.UPPER_0 to IsoColor(220, 50, 50),
                OctahedronFace.UPPER_1 to IsoColor(50, 180, 50),
                OctahedronFace.UPPER_2 to IsoColor(50, 80, 220),
                OctahedronFace.UPPER_3 to IsoColor(220, 200, 50),
                OctahedronFace.LOWER_0 to IsoColor(220, 120, 220),
                OctahedronFace.LOWER_1 to IsoColor(120, 220, 220),
                OctahedronFace.LOWER_2 to IsoColor(220, 180, 120),
                OctahedronFace.LOWER_3 to IsoColor(120, 180, 220),
            ),
            default = IsoColor(150, 150, 150),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Info card + shape tab + render mode toggle
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
                Text(text = "Shape", style = MaterialTheme.typography.caption)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tab in ShapeTab.entries) {
                        TogglePill(
                            label = tab.label,
                            selected = shapeTab == tab,
                            onClick = { shapeTab = tab },
                        )
                    }
                }
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
                    perFaceMaterial = octahedronPerFace,
                )
            }
        }
    }
}

@Composable
private fun TexturedPrismGridScene(
    renderMode: RenderMode,
    tileMaterial: IsometricMaterial,
    tilingMaterial: IsometricMaterial,
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
        ) {
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
}

@Composable
private fun TexturedOctahedronScene(
    renderMode: RenderMode,
    texturedMaterial: IsometricMaterial,
    perFaceMaterial: IsometricMaterial,
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
        ) {
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
}
