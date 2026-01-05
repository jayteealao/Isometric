package io.fabianterhorst.isometric.benchmarkapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.metrics.performance.JankStats
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.benchmark.shared.DeterministicSceneGenerator
import io.fabianterhorst.isometric.benchmark.shared.InputPattern
import io.fabianterhorst.isometric.benchmark.shared.MutationType
import io.fabianterhorst.isometric.benchmark.shared.OverlapDensity
import io.fabianterhorst.isometric.benchmark.shared.SceneConfig
import io.fabianterhorst.isometric.compose.IsometricCanvas
import io.fabianterhorst.isometric.compose.rememberIsometricSceneState
import kotlinx.coroutines.delay

class BenchmarkScenarioActivity : ComponentActivity() {
    private lateinit var jankStats: JankStats

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize JankStats with callback that logs frame data
        jankStats = JankStats.createAndTrack(window) { frameData ->
            Log.d(
                "JankStats",
                "Frame: isJank=${frameData.isJank}, " +
                    "frameDurationNanos=${frameData.frameDurationUiNanos}, " +
                    "states=${frameData.states}"
            )
        }

        // Parse intent extras for scenario configuration
        val objectCount = intent.getIntExtra("objectCount", 100)
        val densityStr = intent.getStringExtra("density") ?: "MEDIUM"
        val mutationTypeStr = intent.getStringExtra("mutationType") ?: "STATIC"
        val inputPatternStr = intent.getStringExtra("inputPattern") ?: "NONE"
        val enablePreparedCache = intent.getBooleanExtra("enablePreparedCache", false)
        val enableDrawCache = intent.getBooleanExtra("enableDrawCache", false)
        val enableBroadPhase = intent.getBooleanExtra("enableBroadPhase", false)
        val enableSpatialIndex = intent.getBooleanExtra("enableSpatialIndex", false)

        // Convert strings to enums
        val density = OverlapDensity.valueOf(densityStr)
        val mutationType = MutationType.valueOf(mutationTypeStr)
        val inputPattern = InputPattern.valueOf(inputPatternStr)

        // Create RenderOptions from the parsed flags
        val renderOptions = RenderOptions(
            enablePreparedCache = enablePreparedCache,
            enableDrawCache = enableDrawCache,
            enableBroadPhase = enableBroadPhase,
            enableSpatialIndex = enableSpatialIndex
        )

        // Use setContent with MaterialTheme to display BenchmarkScenario composable
        setContent {
            MaterialTheme {
                BenchmarkScenario(
                    objectCount = objectCount,
                    density = density,
                    mutationType = mutationType,
                    inputPattern = inputPattern,
                    renderOptions = renderOptions,
                    jankStats = jankStats
                )
            }
        }
    }

    override fun onDestroy() {
        // Disable JankStats tracking
        jankStats.isTrackingEnabled = false
        super.onDestroy()
    }
}

@Composable
fun BenchmarkScenario(
    objectCount: Int,
    density: OverlapDensity,
    mutationType: MutationType,
    inputPattern: InputPattern,
    renderOptions: RenderOptions,
    jankStats: JankStats
) {
    // Use rememberIsometricSceneState() for scene state
    val sceneState = rememberIsometricSceneState()

    // Track frameIndex with mutableStateOf
    var frameIndex by mutableIntStateOf(0)

    // Generate deterministic scene using DeterministicSceneGenerator.generate()
    val sceneItems = DeterministicSceneGenerator.generate(
        SceneConfig(
            objectCount = objectCount,
            density = density,
            mutationType = mutationType,
            inputPattern = inputPattern
        )
    )

    // Add JankStats state label with LaunchedEffect(frameIndex) - include config info
    LaunchedEffect(frameIndex) {
        jankStats.jankHeuristicMultiplier = 1.0f
        jankStats.state.putState(
            "BenchmarkScenario",
            "frame=$frameIndex," +
                "objectCount=$objectCount," +
                "density=$density," +
                "mutationType=$mutationType," +
                "inputPattern=$inputPattern," +
                "cache=${renderOptions.enablePreparedCache}," +
                "draw=${renderOptions.enableDrawCache}," +
                "broadPhase=${renderOptions.enableBroadPhase}," +
                "spatialIndex=${renderOptions.enableSpatialIndex}"
        )
    }

    // Implement mutation loop with LaunchedEffect(mutationType)
    LaunchedEffect(mutationType) {
        if (mutationType != MutationType.STATIC) {
            while (true) {
                delay(16) // ~60 FPS

                // Mutate scene items
                DeterministicSceneGenerator.mutateScene(sceneItems, mutationType)

                // Increment sceneState version
                sceneState.incrementVersion()

                // Increment frameIndex
                frameIndex++
            }
        }
    }

    // Render using IsometricCanvas
    IsometricCanvas(
        sceneState = sceneState,
        renderOptions = renderOptions,
        onItemClick = { item ->
            // Simulate hit testing
            Log.d("BenchmarkScenario", "Item clicked: $item")
        }
    ) {
        // Add all sceneItems using forEach
        sceneItems.forEach { item ->
            addItem(item)
        }
    }
}
