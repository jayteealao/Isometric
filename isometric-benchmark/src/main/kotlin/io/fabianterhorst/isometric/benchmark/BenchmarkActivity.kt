package io.fabianterhorst.isometric.benchmark

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.compose.IsometricCanvas
import io.fabianterhorst.isometric.compose.rememberIsometricSceneState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BenchmarkActivity : ComponentActivity() {
    private lateinit var orchestrator: BenchmarkOrchestrator
    private lateinit var config: BenchmarkConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Parse config or use default
        config = try {
            BenchmarkConfig.fromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Using default config", e)
            createDefaultConfig()
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        orchestrator = BenchmarkOrchestrator(
            activity = this,
            config = config,
            onComplete = { results ->
                Log.i(TAG, "Results: ${results.toCsv()}")
                ResultsExporter.export(this, results)
                finish()
            }
        )

        setContent {
            BenchmarkScreen(orchestrator)
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            delay(1000) // Let UI settle
            orchestrator.start()
        }
    }

    private fun createDefaultConfig(): BenchmarkConfig {
        return BenchmarkConfig(
            name = "baseline_static_100_noInteraction",
            sceneSize = 100,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        )
    }

    companion object {
        private const val TAG = "BenchmarkActivity"
    }
}

@Composable
fun BenchmarkScreen(orchestrator: BenchmarkOrchestrator) {
    val config = orchestrator.config
    val frameTick by orchestrator.frameTickFlow.collectAsState()

    // Generate deterministic scene
    val baseScene = remember(config.sceneSize) {
        SceneGenerator.generate(
            size = config.sceneSize,
            seed = 12345L,
            density = 0.5f
        )
    }

    // Create scene state
    val sceneState = rememberIsometricSceneState()

    // Apply scene mutations based on scenario
    LaunchedEffect(frameTick) {
        when (config.scenario) {
            Scenario.STATIC -> {
                // No mutations
            }
            Scenario.INCREMENTAL_1 -> {
                SceneGenerator.mutateScene(baseScene, 0.01f, frameTick)
            }
            Scenario.INCREMENTAL_10 -> {
                SceneGenerator.mutateScene(baseScene, 0.10f, frameTick)
            }
            Scenario.FULL_MUTATION -> {
                SceneGenerator.mutateScene(baseScene, 1.0f, frameTick)
            }
        }

        // Rebuild scene
        sceneState.clear()
        baseScene.forEach { item ->
            sceneState.add(item.shape, item.color)
        }
    }

    // Simulate interaction
    val simulator = remember {
        InteractionSimulator(
            pattern = config.interactionPattern,
            width = 1080,
            height = 1920,
            seed = 67890L
        )
    }

    LaunchedEffect(frameTick) {
        simulator.getHitTestPoint(frameTick)?.let { point ->
            // Hit test will happen in next task
            // For now, just simulate the interaction by touching the scene
        }
    }

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier.fillMaxSize(),
        renderOptions = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = false  // Baseline: render ALL objects for worst-case measurement
        )
    ) {
        // Scene already built in LaunchedEffect
    }
}
