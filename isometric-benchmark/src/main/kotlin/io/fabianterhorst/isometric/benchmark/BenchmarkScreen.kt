package io.fabianterhorst.isometric.benchmark

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.compose.runtime.ForEach
import io.fabianterhorst.isometric.compose.runtime.IsometricScene
import io.fabianterhorst.isometric.compose.runtime.IsometricScope
import io.fabianterhorst.isometric.compose.runtime.LocalBenchmarkHooks
import io.fabianterhorst.isometric.compose.runtime.Shape

/**
 * Composable that renders the benchmark scene and drives the orchestrator.
 *
 * The scene is populated via [SceneGenerator] and mutated per-frame by the
 * orchestrator using [MutationSimulator]. Hit-test interactions are injected
 * by [InteractionSimulator].
 *
 * @param config The benchmark configuration
 * @param onComplete Called when the benchmark run is finished
 */
@Composable
fun BenchmarkScreen(
    config: BenchmarkConfig,
    onComplete: (List<FrameMetrics>) -> Unit
) {
    val items = remember { mutableStateListOf<GeneratedItem>() }
    val flags = config.flags

    // Create metrics collector and hooks
    val collector = remember { MetricsCollector(config.measurementFrames) }
    val benchmarkHooks = remember(collector) { BenchmarkHooksImpl(collector) }

    // Create orchestrator
    val orchestrator = remember(config) {
        BenchmarkOrchestrator(config, collector)
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
            onComplete = onComplete
        )
    }

    // Render the scene with benchmark hooks injected
    CompositionLocalProvider(LocalBenchmarkHooks provides benchmarkHooks) {
        IsometricScene(
            modifier = Modifier.fillMaxSize(),
            enablePathCaching = flags.enablePathCaching,
            enableSpatialIndex = flags.enableSpatialIndex,
            useNativeCanvas = flags.enableNativeCanvas,
            forceRebuild = !flags.enablePreparedSceneCache,
            renderOptions = RenderOptions.Default,
            enableGestures = false,
            onTap = { _, _, _ -> /* interaction responses handled by orchestrator */ }
        ) {
            ForEach(items, key = { it.id }) { item ->
                Shape(shape = item.shape, color = item.color, position = item.position)
            }
        }
    }
}
