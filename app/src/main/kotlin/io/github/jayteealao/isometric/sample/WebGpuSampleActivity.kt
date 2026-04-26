package io.github.jayteealao.isometric.sample

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.ForEach
import io.github.jayteealao.isometric.compose.runtime.GestureConfig
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.RenderMode
import io.github.jayteealao.isometric.compose.runtime.SceneConfig
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.webgpu.WebGpuComputeBackend
import io.github.jayteealao.isometric.webgpu.WebGpuProviderImpl
import io.github.jayteealao.isometric.shader.Shape as MaterialShape
import io.github.jayteealao.isometric.shader.render.ProvideTextureRendering
import io.github.jayteealao.isometric.shader.texturedBitmap
import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val WEBGPU_SAMPLE_TAG = "WebGpuSample"

class WebGpuSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on so automated adb test launches don't hit the display-off timeout
        // (which invalidates the Vulkan surface and fires VK_ERROR_DEVICE_LOST).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colors.background
                ) {
                    WebGpuSamplesScreen()
                }
            }
        }
    }
}

@Composable
private fun WebGpuSamplesScreen() {
    var selectedTab by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        WebGpuExplainerCard()

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Animated Towers") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Smoke") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Dense Grid") }
            )
            Tab(
                selected = selectedTab == 3,
                onClick = { selectedTab = 3 },
                text = { Text("Textured") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> AnimatedTowersBackendSample()
                1 -> WebGpuSmokeSample()
                2 -> WebGpuDenseGridSample()
                3 -> WebGpuTexturedSample()
            }
        }
    }
}

@Composable
private fun WebGpuExplainerCard(
) {
    val webGpuBackend = remember { WebGpuProviderImpl.computeBackend }
    val backendStatus by webGpuBackend.status.collectAsState()
    val statusColor = when (backendStatus.status) {
        WebGpuComputeBackend.Status.Uninitialized -> Color(0xFF616161)
        WebGpuComputeBackend.Status.Initializing -> Color(0xFFEF6C00)
        WebGpuComputeBackend.Status.Ready -> Color(0xFF2E7D32)
        WebGpuComputeBackend.Status.Failed -> Color(0xFFC62828)
    }
    val depthKeyLabel = backendStatus.depthKeyCount?.toString() ?: "n/a"

    LaunchedEffect(backendStatus) {
        Log.d(
            WEBGPU_SAMPLE_TAG,
            "WebGPU backend status=${backendStatus.status} detail=${backendStatus.detail} depthKeyCount=${backendStatus.depthKeyCount}"
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "WebGPU",
                style = MaterialTheme.typography.subtitle1
            )
            Spacer(modifier = Modifier.weight(1f))
            StatusPill(label = backendStatus.status.name, color = statusColor)
            StatusPill(label = "keys $depthKeyLabel", color = Color(0xFF455A64))
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Card(backgroundColor = color.copy(alpha = 0.15f), elevation = 0.dp) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = color,
            style = MaterialTheme.typography.caption
        )
    }
}

@Composable
private fun WebGpuSmokeSample() {
    var stageIndex by remember { mutableStateOf(0) }
    val animationPhase = rememberPhaseAnimation(speedRadiansPerSecond = 3.2)

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            stageIndex = (stageIndex + 1) % 3
        }
    }

    val stage = when (stageIndex) {
        0 -> SmokeStage(
            name = "Small",
            gridWidth = 4,
            gridHeight = 4,
            spacing = 1.2,
            animated = false,
        )
        1 -> SmokeStage(
            name = "Medium",
            gridWidth = 8,
            gridHeight = 8,
            spacing = 1.0,
            animated = false,
        )
        else -> SmokeStage(
            name = "Large",
            gridWidth = 12,
            gridHeight = 12,
            spacing = 0.9,
            animated = true,
        )
    }

    LaunchedEffect(stage.name) {
        Log.d(
            WEBGPU_SAMPLE_TAG,
            "Smoke stage=${stage.name} grid=${stage.gridWidth}x${stage.gridHeight} animated=${stage.animated}"
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Smoke stage: ${stage.name}", style = MaterialTheme.typography.subtitle1)
                Text(
                    text = "Depth keys increase from ${stage.gridWidth * stage.gridHeight} to ${stage.gridWidth * stage.gridHeight + 1}",
                    style = MaterialTheme.typography.body2
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            WebGpuGridScene(
                stage = stage,
                phase = if (stage.animated) animationPhase else 0.0,
            )
        }
    }
}

@Composable
private fun WebGpuDenseGridSample() {
    WebGpuGridScene(
        stage = SmokeStage(
            name = "Dense",
            gridWidth = 10,
            gridHeight = 10,
            spacing = 0.95,
            animated = false,
        ),
        phase = 0.0,
    )
}

@Composable
private fun AnimatedTowersBackendSample() {
    var renderMode by remember { mutableStateOf<RenderMode>(RenderMode.WebGpu()) }
    val phase = rememberPhaseAnimation(speedRadiansPerSecond = 3.4)

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Animated Towers", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Render mode",
                    style = MaterialTheme.typography.caption
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TogglePill(
                        label = "Canvas",
                        selected = renderMode is RenderMode.Canvas && (renderMode as RenderMode.Canvas).compute == RenderMode.Canvas.Compute.Cpu,
                        onClick = { renderMode = RenderMode.Canvas() }
                    )
                    TogglePill(
                        label = "Canvas + GPU Sort",
                        selected = renderMode is RenderMode.Canvas && (renderMode as RenderMode.Canvas).compute == RenderMode.Canvas.Compute.WebGpu,
                        onClick = { renderMode = RenderMode.Canvas(compute = RenderMode.Canvas.Compute.WebGpu) }
                    )
                    TogglePill(
                        label = "WebGPU",
                        selected = renderMode is RenderMode.WebGpu,
                        onClick = { renderMode = RenderMode.WebGpu() }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Current: $renderMode",
                    style = MaterialTheme.typography.body2
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            AnimatedTowersScene(
                phase = phase,
                renderMode = renderMode,
            )
        }
    }
}

@Composable
private fun AnimatedTowersScene(
    phase: Double,
    renderMode: RenderMode,
) {
    val stage = SmokeStage(
        name = "Animated",
        gridWidth = 7,
        gridHeight = 7,
        spacing = 1.15,
        animated = true,
    )

    WebGpuGridScene(
        stage = stage,
        phase = phase,
        includeCylinder = false,
        renderMode = renderMode,
    )
}

@Composable
private fun WebGpuGridScene(
    stage: SmokeStage,
    phase: Double,
    includeCylinder: Boolean = true,
    renderMode: RenderMode = RenderMode.Canvas(),
) {
    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = SceneConfig(
            renderOptions = RenderOptions.Default.copy(enableBroadPhaseSort = true),
            renderMode = renderMode,
            useNativeCanvas = false,
            gestures = GestureConfig.Disabled,
        )
    ) {
        ForEach((0 until stage.gridWidth).toList()) { x ->
            ForEach((0 until stage.gridHeight).toList()) { y ->
                val centerOffsetX = x - stage.gridWidth / 2.0
                val centerOffsetY = y - stage.gridHeight / 2.0
                val wave = sin(phase + x * 0.55 + y * 0.45)
                val baseHeight =
                    if (stage.animated) {
                        0.7 + (wave + 1.0) * 1.2
                    } else {
                        0.6 + ((stage.gridWidth - kotlin.math.abs(centerOffsetX) - kotlin.math.abs(centerOffsetY))
                            .coerceAtLeast(1.0) * 0.18)
                    }
                val towerX = centerOffsetX * stage.spacing
                val towerY = centerOffsetY * stage.spacing

                Shape(
                    geometry = Prism(
                        position = Point(towerX, towerY, 0.0),
                        width = 1.0,
                        depth = 1.0,
                        height = baseHeight
                    ),
                    material = IsoColor(
                        clampRgb(60.0 + x * 14.0),
                        clampRgb(80.0 + y * 16.0),
                        clampRgb(220.0 - y * 9.0)
                    )
                )

                Shape(
                    geometry = Prism(
                        position = Point(towerX + 0.16, towerY + 0.16, baseHeight),
                        width = 0.68,
                        depth = 0.68,
                        height = 0.22
                    ),
                    material = IsoColor(245.0, 245.0, 255.0)
                )
            }
        }
    }
}

@Composable
private fun WebGpuTexturedSample() {
    var renderMode by remember { mutableStateOf<RenderMode>(RenderMode.WebGpu()) }

    // R-21: This sample-app checkerboard is intentionally duplicated from
    // GpuTextureStore's 1×1-white fallback. The sample uses a visible magenta/black
    // checkerboard so missing or loading textures are visually obvious during testing;
    // production rendering uses the white-pixel fallback to avoid distracting visual
    // artifacts in released builds.
    val checkerboard = remember {
        val size = 16
        val cellSize = 8
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val isMagenta = ((x / cellSize) + (y / cellSize)) % 2 == 0
                pixels[y * size + x] = if (isMagenta) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    // R-20: Recycle the checkerboard bitmap when this composable leaves the composition
    // so the pixel memory is returned to the system without waiting for GC.
    DisposableEffect(Unit) {
        onDispose { checkerboard.recycle() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "Textured Prisms", style = MaterialTheme.typography.subtitle1)
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Render mode", style = MaterialTheme.typography.caption)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TogglePill(
                        label = "Canvas",
                        selected = renderMode is RenderMode.Canvas && (renderMode as RenderMode.Canvas).compute == RenderMode.Canvas.Compute.Cpu,
                        onClick = { renderMode = RenderMode.Canvas() }
                    )
                    TogglePill(
                        label = "Canvas + GPU Sort",
                        selected = renderMode is RenderMode.Canvas && (renderMode as RenderMode.Canvas).compute == RenderMode.Canvas.Compute.WebGpu,
                        onClick = { renderMode = RenderMode.Canvas(compute = RenderMode.Canvas.Compute.WebGpu) }
                    )
                    TogglePill(
                        label = "Full WebGPU",
                        selected = renderMode is RenderMode.WebGpu,
                        onClick = { renderMode = RenderMode.WebGpu() }
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            ProvideTextureRendering {
                IsometricScene(
                    modifier = Modifier.fillMaxSize(),
                    config = SceneConfig(
                        renderMode = renderMode,
                        gestures = GestureConfig.Disabled,
                    )
                ) {
                    // Textured prism (checkerboard)
                    MaterialShape(
                        geometry = Prism(position = Point(0.0, 0.0, 0.0)),
                        material = texturedBitmap(checkerboard),
                    )
                    // Flat-color prism (backward compat — should stay blue)
                    Shape(
                        geometry = Prism(position = Point(2.0, 0.0, 0.0)),
                        material = IsoColor(33.0, 150.0, 243.0),
                    )
                    // Another textured prism (cache reuse)
                    MaterialShape(
                        geometry = Prism(position = Point(4.0, 0.0, 0.0)),
                        material = texturedBitmap(checkerboard),
                    )
                }
            }
        }
    }
}

private data class SmokeStage(
    val name: String,
    val gridWidth: Int,
    val gridHeight: Int,
    val spacing: Double,
    val animated: Boolean,
)

@Composable
private fun rememberPhaseAnimation(speedRadiansPerSecond: Double): Double {
    var phase by remember { mutableStateOf(0.0) }

    LaunchedEffect(speedRadiansPerSecond) {
        var startTimeNanos = Long.MIN_VALUE
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (startTimeNanos == Long.MIN_VALUE) {
                    startTimeNanos = frameTimeNanos
                }
                val elapsedSeconds = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0
                phase = elapsedSeconds * speedRadiansPerSecond
            }
        }
    }

    return phase
}

private fun clampRgb(value: Double): Double = min(255.0, max(0.0, value))
