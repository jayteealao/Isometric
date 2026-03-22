package io.github.jayteealao.isometric.sample

import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ScrollableTabRow
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import io.github.jayteealao.isometric.ComputeBackend
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.ForEach
import io.github.jayteealao.isometric.compose.runtime.GestureConfig
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.SceneConfig
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.webgpu.WebGpu
import io.github.jayteealao.isometric.webgpu.WebGpuComputeBackend
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val WEBGPU_SAMPLE_TAG = "WebGpuSample"

class WebGpuSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                text = { Text("Smoke") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Dense Grid") }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Animated Towers") }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> WebGpuSmokeSample()
                1 -> WebGpuDenseGridSample()
                2 -> WebGpuAnimatedTowersSample()
            }
        }
    }
}

@Composable
private fun WebGpuExplainerCard(
) {
    val webGpuBackend = remember { ComputeBackend.WebGpu as WebGpuComputeBackend }
    val backendStatus by webGpuBackend.status.collectAsState()
    val statusColor = when (backendStatus.status) {
        WebGpuComputeBackend.Status.Uninitialized -> Color(0xFF616161)
        WebGpuComputeBackend.Status.Initializing -> Color(0xFFEF6C00)
        WebGpuComputeBackend.Status.Ready -> Color(0xFF2E7D32)
        WebGpuComputeBackend.Status.Failed -> Color(0xFFC62828)
    }
    val statusDetail = when (backendStatus.status) {
        WebGpuComputeBackend.Status.Ready -> "GPU sort active"
        else -> backendStatus.detail ?: "Waiting for activity"
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
            .padding(16.dp)
            .heightIn(min = 212.dp),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "WebGPU Compute Sort Sample",
                style = MaterialTheme.typography.h6
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Frontend: Compose Canvas",
                style = MaterialTheme.typography.body2
            )
            Text(
                text = "Compute backend: ComputeBackend.WebGpu",
                style = MaterialTheme.typography.body2
            )
            Text(
                text = "Purpose: prove the current Canvas renderer can use WebGPU depth sorting without the WS12 renderer.",
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Backend status: ${backendStatus.status}",
                style = MaterialTheme.typography.body2,
                color = statusColor,
            )
            Text(
                text = "Detail: $statusDetail",
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Depth-key count: $depthKeyLabel",
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusPill(label = "Canvas", color = Color(0xFF1565C0))
                StatusPill(label = "WebGPU Sort", color = Color(0xFF2E7D32))
                StatusPill(label = "No Native Canvas", color = Color(0xFF6A1B9A))
                StatusPill(
                    label = backendStatus.status.name,
                    color = statusColor,
                )
            }
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
private fun WebGpuAnimatedTowersSample() {
    val phase = rememberPhaseAnimation(speedRadiansPerSecond = 3.4)

    WebGpuGridScene(
        stage = SmokeStage(
            name = "Animated",
            gridWidth = 7,
            gridHeight = 7,
            spacing = 1.15,
            animated = true,
        ),
        phase = phase,
        includeCylinder = false,
    )
}

@Composable
private fun WebGpuGridScene(
    stage: SmokeStage,
    phase: Double,
    includeCylinder: Boolean = true,
) {
    IsometricScene(
        modifier = Modifier.fillMaxSize(),
        config = SceneConfig(
            renderOptions = RenderOptions.Default.copy(enableBroadPhaseSort = true),
            computeBackend = ComputeBackend.WebGpu,
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
                    color = IsoColor(
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
                    color = IsoColor(245.0, 245.0, 255.0)
                )
            }
        }

        if (includeCylinder) {
            Shape(
                geometry = Cylinder(
                    position = Point(0.2, -0.2, 0.0),
                    radius = 1.45,
                    height = 2.4,
                    vertices = 28
                ),
                color = IsoColor(250.0, 170.0, 70.0)
            )
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
