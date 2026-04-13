package io.github.jayteealao.isometric.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shader.Shape as MaterialShape

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

@Composable
private fun TexturedDemoScreen() {
    var renderMode by remember { mutableStateOf<RenderMode>(RenderMode.Canvas()) }

    val tileMaterial = remember {
        perFace {
            top = texturedBitmap(TextureAssets.grassTop)
            sides = texturedBitmap(TextureAssets.dirtSide)
            default = texturedBitmap(TextureAssets.dirtSide)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Info card + render mode toggle
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Textured Materials", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "4\u00d74 prism grid \u2014 grass top, dirt sides (perFace material)",
                    style = MaterialTheme.typography.body2,
                )
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
            TexturedPrismGridScene(renderMode = renderMode, tileMaterial = tileMaterial)
        }
    }
}

@Composable
private fun TexturedPrismGridScene(
    renderMode: RenderMode,
    tileMaterial: io.github.jayteealao.isometric.shader.IsometricMaterial,
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
                    MaterialShape(
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
                        material = tileMaterial,
                    )
                }
            }
        }
    }
}

@Composable
private fun TogglePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        backgroundColor = if (selected) MaterialTheme.colors.primary.copy(alpha = 0.18f) else Color.Transparent,
        elevation = 0.dp,
        modifier = Modifier
            .padding(top = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
            style = MaterialTheme.typography.body2,
        )
    }
}
