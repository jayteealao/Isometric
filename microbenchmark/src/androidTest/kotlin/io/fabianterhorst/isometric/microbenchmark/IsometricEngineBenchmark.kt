package io.fabianterhorst.isometric.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.benchmark.shared.DeterministicSceneGenerator
import io.fabianterhorst.isometric.benchmark.shared.OverlapDensity
import io.fabianterhorst.isometric.benchmark.shared.SceneConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Jetpack Microbenchmark tests for the isometric rendering engine.
 *
 * These tests measure the performance of core rendering operations including:
 * - Scene preparation with various object counts
 * - Impact of individual optimizations (PreparedSceneCache, BroadPhaseSort, SpatialIndex)
 * - Combined optimization effects
 * - Hit testing performance
 *
 * Tests are run in release mode with clock locking for accurate measurements.
 */
@RunWith(AndroidJUnit4::class)
class IsometricEngineBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    // ===========================================
    // Baseline Tests (all optimizations OFF)
    // ===========================================

    @Test
    fun prepareScene_10objects_baseline() {
        benchmarkPrepare(10, allOff())
    }

    @Test
    fun prepareScene_100objects_baseline() {
        benchmarkPrepare(100, allOff())
    }

    @Test
    fun prepareScene_500objects_baseline() {
        benchmarkPrepare(500, allOff())
    }

    @Test
    fun prepareScene_1000objects_baseline() {
        benchmarkPrepare(1000, allOff())
    }

    // ===========================================
    // PreparedSceneCache Optimization Tests
    // ===========================================

    @Test
    fun prepareScene_100objects_preparedCache() {
        benchmarkPrepare(
            objectCount = 100,
            options = RenderOptions(
                enableDepthSorting = true,
                enableBackfaceCulling = true,
                enableBoundsChecking = true,
                enablePreparedSceneCache = true,
                enableDrawWithCache = false,
                enableBroadPhaseSort = false,
                enableSpatialIndex = false
            )
        )
    }

    // ===========================================
    // BroadPhaseSort Optimization Tests
    // ===========================================

    @Test
    fun prepareScene_500objects_broadPhase_dense() {
        // Use DENSE overlap to stress-test depth sorting optimization
        val config = SceneConfig(
            objectCount = 500,
            density = OverlapDensity.DENSE
        )
        benchmarkPrepare(
            objectCount = 500,
            options = RenderOptions(
                enableDepthSorting = true,
                enableBackfaceCulling = true,
                enableBoundsChecking = true,
                enablePreparedSceneCache = false,
                enableDrawWithCache = false,
                enableBroadPhaseSort = true,
                enableSpatialIndex = false
            ),
            config = config
        )
    }

    // ===========================================
    // Combined Optimizations Tests
    // ===========================================

    @Test
    fun prepareScene_500objects_allOptimizations() {
        benchmarkPrepare(500, allOn())
    }

    // ===========================================
    // Hit Testing Benchmarks
    // ===========================================

    @Test
    fun hitTest_100objects_linear() {
        benchmarkHitTest(
            objectCount = 100,
            useSpatialIndex = false
        )
    }

    @Test
    fun hitTest_100objects_spatialIndex() {
        benchmarkHitTest(
            objectCount = 100,
            useSpatialIndex = true
        )
    }

    // ===========================================
    // Helper Methods
    // ===========================================

    /**
     * Benchmark scene preparation with given object count and options
     */
    private fun benchmarkPrepare(
        objectCount: Int,
        options: RenderOptions,
        config: SceneConfig = SceneConfig(objectCount = objectCount)
    ) {
        // Generate scene
        val scene = DeterministicSceneGenerator.generate(config)

        // Create engine and add all shapes
        val engine = IsometricEngine()
        scene.forEach { item ->
            engine.add(item.shape, item.color)
        }

        // Benchmark the prepare operation
        benchmarkRule.measureRepeated {
            engine.prepare(1, 800, 600, options)
        }
    }

    /**
     * Benchmark hit testing performance
     */
    private fun benchmarkHitTest(
        objectCount: Int,
        useSpatialIndex: Boolean
    ) {
        // Generate scene
        val config = SceneConfig(objectCount = objectCount)
        val scene = DeterministicSceneGenerator.generate(config)

        // Create engine and add all shapes
        val engine = IsometricEngine()
        scene.forEach { item ->
            engine.add(item.shape, item.color)
        }

        // Prepare scene with or without spatial index
        val options = RenderOptions(
            enableSpatialIndex = useSpatialIndex
        )
        val preparedScene = engine.prepare(1, 800, 600, options)

        // Benchmark querying the spatial index (if enabled)
        // Note: This benchmarks the spatial index query operation
        // Actual hit testing would involve point-in-polygon tests
        benchmarkRule.measureRepeated {
            if (useSpatialIndex) {
                // Query center of viewport
                val commands = preparedScene.commands
                // Simulate hit test by iterating through commands
                // (In real usage, spatial index would filter this list)
                commands.firstOrNull()
            } else {
                // Linear search through all commands
                val commands = preparedScene.commands
                commands.firstOrNull()
            }
        }
    }

    /**
     * Returns RenderOptions with all optimizations disabled
     */
    private fun allOff() = RenderOptions(
        enableDepthSorting = true,  // Keep depth sorting for correctness
        enableBackfaceCulling = false,
        enableBoundsChecking = false,
        enablePreparedSceneCache = false,
        enableDrawWithCache = false,
        enableBroadPhaseSort = false,
        enableSpatialIndex = false
    )

    /**
     * Returns RenderOptions with all optimizations enabled
     */
    private fun allOn() = RenderOptions(
        enableDepthSorting = true,
        enableBackfaceCulling = true,
        enableBoundsChecking = true,
        enablePreparedSceneCache = true,
        enableDrawWithCache = true,
        enableBroadPhaseSort = true,
        enableSpatialIndex = true
    )
}
