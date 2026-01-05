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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.compose.IsometricCanvas
import io.fabianterhorst.isometric.compose.IsometricSceneState
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

    // Called from BenchmarkScreen to provide engine reference
    fun setEngine(engine: io.fabianterhorst.isometric.IsometricEngine) {
        orchestrator.engine = engine
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
            flags = OptimizationFlags.BASELINE,
            numberOfRuns = 3
        )
    }

    companion object {
        private const val TAG = "BenchmarkActivity"
    }
}

// Stable reference for empty content lambda to prevent unnecessary recompositions
// Without this, a new lambda instance would be created on every recomposition,
// causing IsometricCanvas to invalidate its drawWithCache on every frame
private val emptyContent: io.fabianterhorst.isometric.compose.IsometricScope.() -> Unit = {}

// Stable reference for fillMaxSize modifier to prevent unnecessary recompositions
// Without this, a new modifier instance would be created on every recomposition,
// causing IsometricCanvas to recompose even when nothing changed
private val fillSizeModifier = Modifier.fillMaxSize()

// Stable reference for onItemClick callback to prevent unnecessary recompositions
// Without this, the default lambda {} would be created fresh on every recomposition,
// causing IsometricCanvas to recompose even when nothing changed
private val emptyItemClickHandler: (io.fabianterhorst.isometric.RenderCommand) -> Unit = {}

@Composable
fun BenchmarkScreen(orchestrator: BenchmarkOrchestrator) {
    val config = orchestrator.config
    val frameTick by orchestrator.frameTickFlow.collectAsState()

    // Force observation of frameTick to ensure recomposition on every change
    // Without this, unstable orchestrator parameter interferes with state observation
    Log.d("BenchmarkScreen", "Recomposing with frameTick=$frameTick")

    // Generate deterministic scene - use config.sceneSize as key to preserve across recompositions
    val baseScene = remember(config.sceneSize) {
        SceneGenerator.generate(
            size = config.sceneSize,
            seed = 12345L,
            density = 0.5f
        )
    }

    // Create scene state
    // rememberIsometricSceneState() uses remember internally, so it persists across recompositions
    val sceneState = rememberIsometricSceneState()

    // Provide engine reference to orchestrator for cache stats
    LaunchedEffect(sceneState) {
        orchestrator.engine = sceneState.engine
    }

    // Apply scene mutations based on scenario
    LaunchedEffect(frameTick) {
        // Determine if scene needs rebuilding
        val needsRebuild = when (config.scenario) {
            Scenario.STATIC -> {
                // STATIC: Only rebuild on first frame to establish initial scene
                // All subsequent frames use cached PreparedScene (version unchanged)
                frameTick == 0
            }
            Scenario.INCREMENTAL_1 -> {
                // Mutate 1% of items (only after warmup frame)
                if (frameTick > 0) SceneGenerator.mutateScene(baseScene, 0.01f, frameTick)
                true  // Always rebuild (initial setup + after mutations)
            }
            Scenario.INCREMENTAL_10 -> {
                // Mutate 10% of items (only after warmup frame)
                if (frameTick > 0) SceneGenerator.mutateScene(baseScene, 0.10f, frameTick)
                true
            }
            Scenario.FULL_MUTATION -> {
                // Mutate 100% of items (only after warmup frame)
                if (frameTick > 0) SceneGenerator.mutateScene(baseScene, 1.0f, frameTick)
                true
            }
        }

        // Only rebuild scene when it actually changed
        if (needsRebuild) {
            sceneState.clear()
            baseScene.forEach { item ->
                sceneState.add(item.shape, item.color)
            }
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

    // CRITICAL: Create RenderOptions once and reuse across all frames
    // Cache uses reference equality (===) for performance, so new instances = cache miss
    val renderOptions = remember(config.flags) {
        RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = false,  // Baseline: render ALL objects for worst-case measurement
            enablePreparedSceneCache = config.flags.enablePreparedSceneCache,
            enableDrawWithCache = config.flags.enableDrawWithCache
        )
    }

    // Isolate IsometricCanvas in a separate composable with only stable parameters
    // This prevents recomposition when frameTick changes
    StableIsometricCanvas(
        sceneState = sceneState,
        renderOptions = renderOptions
    )
}

/**
 * Isolated composable with only stable parameters, allowing Compose to skip recomposition
 * when the parent BenchmarkScreen recomposes due to unstable orchestrator parameter.
 */
@Composable
private fun StableIsometricCanvas(
    sceneState: IsometricSceneState,
    renderOptions: RenderOptions
) {
    IsometricCanvas(
        state = sceneState,
        modifier = fillSizeModifier,  // Stable reference prevents unnecessary recompositions
        renderOptions = renderOptions,
        onItemClick = emptyItemClickHandler,  // Stable reference prevents unnecessary recompositions
        content = emptyContent  // Stable reference prevents unnecessary recompositions
    )
}
