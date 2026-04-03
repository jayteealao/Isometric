package io.github.jayteealao.isometric.benchmark

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.RenderOptions
import io.github.jayteealao.isometric.compose.runtime.AdvancedSceneConfig
import io.github.jayteealao.isometric.compose.runtime.ForEach
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.LocalBenchmarkHooks
import io.github.jayteealao.isometric.compose.runtime.RuntimeFlagSnapshot
import io.github.jayteealao.isometric.compose.runtime.Shape

/**
 * Result of a benchmark run, bundling per-iteration metrics with raw timing
 * arrays and the runtime flag snapshot for validation.
 */
data class BenchmarkResult(
    val iterations: List<FrameMetrics>,
    val iterationRawTimings: List<RawTimings>,
    val runtimeFlags: RuntimeFlagSnapshot?
)

/**
 * Composable that renders the benchmark scene and drives the orchestrator.
 *
 * Owns the single [MetricsCollector] instance that both the [BenchmarkHooksImpl]
 * and [BenchmarkOrchestrator] write to. Per-iteration raw timings are captured
 * by the orchestrator and included in [BenchmarkResult].
 *
 * @param config The benchmark configuration
 * @param onComplete Called when the benchmark run is finished, with per-iteration results
 */
@Composable
fun BenchmarkScreen(
    config: BenchmarkConfig,
    onComplete: (BenchmarkResult) -> Unit
) {
    val items = remember { mutableStateListOf<GeneratedItem>() }
    val flags = config.flags

    // Stable engine — must be remembered so IsometricScene's remember(config.engine)
    // key doesn't change every recomposition, which would recreate the renderer and
    // clear the PreparedScene cache on every frame.
    val engine = remember { IsometricEngine() }

    // Single collector instance — shared by hooks and orchestrator
    val collector = remember { MetricsCollector(config.measurementFrames) }
    val benchmarkHooks = remember(collector) { BenchmarkHooksImpl(collector) }

    // Runtime flag snapshot — populated by IsometricScene's onFlagsReady callback
    var runtimeFlagSnapshot: RuntimeFlagSnapshot? = remember { null }
    var frameVersion by remember { mutableStateOf(0L) }

    // Create orchestrator with the same collector
    val orchestrator = remember(config, collector, benchmarkHooks) {
        BenchmarkOrchestrator(config, collector) { benchmarkHooks.drawPassCount }
    }

    // Generate initial scene
    LaunchedEffect(config.scenario) {
        val generated = SceneGenerator.generate(config.scenario.sceneSize)
        items.clear()
        items.addAll(generated)
        Log.i("IsoBenchmark", "Scene generated: ${items.size} items")
    }

    // Run orchestrator loop
    LaunchedEffect(orchestrator, items.size) {
        if (items.isEmpty()) return@LaunchedEffect

        orchestrator.run(
            items = items,
            onMutate = { mutations ->
                for (mutation in mutations) {
                    items[mutation.index] = mutation.newItem
                }
            },
            onResetScene = {
                val generated = SceneGenerator.generate(config.scenario.sceneSize)
                items.clear()
                items.addAll(generated)
            },
            onFrame = { frameVersion++ },
            onComplete = { iterations ->
                onComplete(BenchmarkResult(iterations, orchestrator.iterationRawTimings.toList(), runtimeFlagSnapshot))
            }
        )
    }

    // Render the scene with benchmark hooks injected
    CompositionLocalProvider(LocalBenchmarkHooks provides benchmarkHooks) {
        IsometricScene(
            modifier = Modifier.fillMaxSize(),
            config = AdvancedSceneConfig(
                engine = engine,
                renderOptions = RenderOptions.Default.copy(
                    enableBroadPhaseSort = flags.enableBroadPhaseSort
                ),
                gestures = io.github.jayteealao.isometric.compose.runtime.GestureConfig.Disabled,
                useNativeCanvas = flags.enableNativeCanvas,
                enablePathCaching = flags.enablePathCaching,
                enableSpatialIndex = flags.enableSpatialIndex,
                forceRebuild = !flags.enablePreparedSceneCache,
                frameVersion = frameVersion,
                renderMode = flags.renderMode,
                onHitTestReady = { hitTest -> orchestrator.hitTestFn = hitTest },
                onFlagsReady = { snapshot ->
                    runtimeFlagSnapshot = snapshot
                    orchestrator.viewportWidth = snapshot.canvasWidth
                    orchestrator.viewportHeight = snapshot.canvasHeight
                }
            )
        ) {
            ForEach(items, key = { it.id }) { item ->
                Shape(geometry = item.shape, color = item.color, position = item.position)
            }
        }
    }
}
