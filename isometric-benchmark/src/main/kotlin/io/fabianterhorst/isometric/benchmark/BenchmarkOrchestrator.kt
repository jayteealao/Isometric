package io.fabianterhorst.isometric.benchmark

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BenchmarkOrchestrator(
    private val activity: Activity,
    val config: BenchmarkConfig,
    private val onComplete: (BenchmarkResults) -> Unit
) {
    private val metrics = MetricsCollector()
    private val framePacer = FramePacer()
    var phase = Phase.IDLE
        private set

    enum class Phase {
        IDLE,
        GC_AND_WARMUP,
        COOLDOWN,
        MEASUREMENT,
        COMPLETE
    }

    private val _frameTickFlow = MutableStateFlow(0)
    val frameTickFlow: StateFlow<Int> = _frameTickFlow.asStateFlow()

    suspend fun start() {
        Log.d(TAG, "Starting benchmark: ${config.name}")

        // Phase 1: GC and Warmup (500 frames)
        phase = Phase.GC_AND_WARMUP
        forceGarbageCollection()
        delay(2000)
        Log.d(TAG, "Warmup: 500 frames")
        runFrames(count = 500, measure = false)

        // Phase 2: Cooldown
        phase = Phase.COOLDOWN
        Log.d(TAG, "Cooldown: 2 seconds")
        delay(2000)
        forceGarbageCollection()
        delay(1000)

        // Phase 3: Measurement (500 frames)
        phase = Phase.MEASUREMENT
        Log.d(TAG, "Measurement: 500 frames")
        metrics.reset()
        runFrames(count = 500, measure = true)

        // Phase 4: Complete
        phase = Phase.COMPLETE
        val results = metrics.computeResults(config)
        Log.d(TAG, "Benchmark complete: avg=${results.avgFrameTime}ms")
        onComplete(results)
    }

    private suspend fun runFrames(count: Int, measure: Boolean) {
        var previousFrameTime = 0L

        repeat(count) { frameIndex ->
            framePacer.awaitNextFrame { frameTimeNanos ->
                // Measure time between consecutive frame callbacks
                // This includes composition + layout + draw time
                if (measure && frameIndex > 0) {
                    val frameDuration = frameTimeNanos - previousFrameTime
                    metrics.recordFrameDuration(frameDuration)
                }

                previousFrameTime = frameTimeNanos

                // Trigger recomposition for NEXT frame
                _frameTickFlow.value = frameIndex
            }
        }
    }

    private fun forceGarbageCollection() {
        repeat(3) {
            System.gc()
            System.runFinalization()
        }
    }

    companion object {
        private const val TAG = "BenchmarkOrchestrator"
    }
}
