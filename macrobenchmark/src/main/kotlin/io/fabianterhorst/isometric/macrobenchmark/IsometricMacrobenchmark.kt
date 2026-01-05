package io.fabianterhorst.isometric.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkRule
import androidx.benchmark.macro.StartupMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive macrobenchmark test suite for measuring end-to-end frame timing
 * of the Isometric library under various configurations and workloads.
 *
 * These tests measure real-world performance including:
 * - Frame timing (frame duration, jank, missed deadlines)
 * - Impact of different optimization flags
 * - Performance across different object counts and interaction patterns
 * - Mutation and input handling overhead
 */
@RunWith(AndroidJUnit4::class)
class IsometricMacrobenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    // ========================================================================
    // Baseline tests (all optimizations OFF)
    // ========================================================================

    @Test
    fun frameTiming_100objects_static_baseline() {
        benchmarkFrameTiming(
            objectCount = 100,
            mutationType = "NONE",
            inputPattern = "STATIC"
        )
    }

    @Test
    fun frameTiming_500objects_static_baseline() {
        benchmarkFrameTiming(
            objectCount = 500,
            mutationType = "NONE",
            inputPattern = "STATIC"
        )
    }

    @Test
    fun frameTiming_1000objects_static_baseline() {
        benchmarkFrameTiming(
            objectCount = 1000,
            mutationType = "NONE",
            inputPattern = "STATIC"
        )
    }

    // ========================================================================
    // Individual optimization tests
    // ========================================================================

    @Test
    fun frameTiming_100objects_static_preparedCache() {
        benchmarkFrameTiming(
            objectCount = 100,
            mutationType = "NONE",
            inputPattern = "STATIC",
            enablePreparedCache = true
        )
    }

    @Test
    fun frameTiming_100objects_static_drawCache() {
        benchmarkFrameTiming(
            objectCount = 100,
            mutationType = "NONE",
            inputPattern = "STATIC",
            enableDrawCache = true
        )
    }

    @Test
    fun frameTiming_500objects_static_broadPhase() {
        benchmarkFrameTiming(
            objectCount = 500,
            mutationType = "NONE",
            inputPattern = "STATIC",
            enableBroadPhase = true,
            density = "DENSE"
        )
    }

    @Test
    fun frameTiming_100objects_continuousInput_spatialIndex() {
        benchmarkFrameTiming(
            objectCount = 100,
            mutationType = "NONE",
            inputPattern = "CONTINUOUS",
            enableSpatialIndex = true
        )
    }

    // ========================================================================
    // Combined optimizations
    // ========================================================================

    @Test
    fun frameTiming_500objects_static_allOptimizations() {
        benchmarkFrameTiming(
            objectCount = 500,
            mutationType = "NONE",
            inputPattern = "STATIC",
            enablePreparedCache = true,
            enableDrawCache = true,
            enableBroadPhase = true,
            enableSpatialIndex = true
        )
    }

    // ========================================================================
    // Mutation workloads
    // ========================================================================

    @Test
    fun frameTiming_100objects_fullMutation_baseline() {
        benchmarkFrameTiming(
            objectCount = 100,
            mutationType = "FULL_MUTATION",
            inputPattern = "STATIC"
        )
    }

    @Test
    fun frameTiming_100objects_smallDelta_preparedCache() {
        benchmarkFrameTiming(
            objectCount = 100,
            mutationType = "SMALL_DELTA",
            inputPattern = "STATIC",
            enablePreparedCache = true
        )
    }

    // ========================================================================
    // Helper method
    // ========================================================================

    /**
     * Executes a macrobenchmark with the specified configuration.
     *
     * @param objectCount Number of isometric objects to render
     * @param mutationType Type of scene mutations (NONE, SMALL_DELTA, FULL_MUTATION)
     * @param inputPattern User input simulation pattern (STATIC, CONTINUOUS, BURST)
     * @param enablePreparedCache Enable prepared scene caching optimization
     * @param enableDrawCache Enable draw command caching optimization
     * @param enableBroadPhase Enable broad-phase culling optimization
     * @param enableSpatialIndex Enable spatial indexing for hit detection
     * @param density Scene density level (SPARSE, MEDIUM, DENSE)
     */
    private fun benchmarkFrameTiming(
        objectCount: Int,
        mutationType: String,
        inputPattern: String,
        enablePreparedCache: Boolean = false,
        enableDrawCache: Boolean = false,
        enableBroadPhase: Boolean = false,
        enableSpatialIndex: Boolean = false,
        density: String = "MEDIUM"
    ) {
        benchmarkRule.measureRepeated(
            packageName = "io.fabianterhorst.isometric.benchmarkapp",
            metrics = listOf(FrameTimingMetric()),
            iterations = 3,
            startupMode = StartupMode.WARM,
            setupBlock = {
                // Return to home screen to ensure consistent starting state
                pressHome()

                // Create intent with benchmark configuration parameters
                val intent = Intent().apply {
                    action = "android.intent.action.MAIN"
                    putExtra("objectCount", objectCount)
                    putExtra("mutationType", mutationType)
                    putExtra("inputPattern", inputPattern)
                    putExtra("density", density)
                    putExtra("enablePreparedCache", enablePreparedCache)
                    putExtra("enableDrawCache", enableDrawCache)
                    putExtra("enableBroadPhase", enableBroadPhase)
                    putExtra("enableSpatialIndex", enableSpatialIndex)
                }

                // Launch the benchmark app with the configured parameters
                startActivityAndWait(intent)
            }
        ) {
            // Measure frame timing for 5 seconds (~300 frames at 60fps)
            // This allows the app to render and collect sufficient frame timing data
            device.waitForIdle(5000)
        }
    }
}
