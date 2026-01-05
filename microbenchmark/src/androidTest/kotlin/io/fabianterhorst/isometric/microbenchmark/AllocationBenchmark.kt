package io.fabianterhorst.isometric.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.fabianterhorst.isometric.IsometricEngine
import io.fabianterhorst.isometric.RenderOptions
import io.fabianterhorst.isometric.benchmark.shared.DeterministicSceneGenerator
import io.fabianterhorst.isometric.benchmark.shared.SceneConfig
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Allocation-focused benchmarks for the isometric rendering engine.
 *
 * These tests measure the allocation behavior of the rendering engine,
 * particularly focusing on the impact of the PreparedSceneCache optimization.
 *
 * The PreparedSceneCache is designed to eliminate allocations when rendering
 * static scenes by caching the prepared scene and returning the same instance
 * on subsequent frames.
 *
 * Tests are run in release mode with allocation tracking enabled.
 */
@RunWith(AndroidJUnit4::class)
class AllocationBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    /**
     * Benchmark allocations when PreparedSceneCache is disabled.
     *
     * Without caching, each call to prepare() will allocate:
     * - A new PreparedScene object
     * - A new list of RenderCommands
     * - Transformation matrices and intermediate data structures
     *
     * Expected: High allocation rate, proportional to scene complexity
     */
    @Test
    fun allocations_prepareScene_noCaching() {
        // Generate a 100-object scene
        val config = SceneConfig(objectCount = 100)
        val scene = DeterministicSceneGenerator.generate(config)

        // Create engine and add all shapes
        val engine = IsometricEngine()
        scene.forEach { item ->
            engine.add(item.shape, item.color)
        }

        // Render options with caching disabled
        val options = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enablePreparedSceneCache = false,  // Caching DISABLED
            enableDrawWithCache = false,
            enableBroadPhaseSort = false,
            enableSpatialIndex = false
        )

        // Benchmark allocation behavior
        // Each iteration should allocate a new PreparedScene
        var sceneVersion = 0
        benchmarkRule.measureRepeated {
            // Use same sceneVersion to simulate static scene rendering
            engine.prepare(sceneVersion, 800, 600, options)
        }
    }

    /**
     * Benchmark allocations when PreparedSceneCache is enabled.
     *
     * With caching enabled, the first call to prepare() allocates normally,
     * but subsequent calls with the same sceneVersion should return the cached
     * instance with zero allocations.
     *
     * Expected: Near-zero allocations after the first frame (cache hit)
     */
    @Test
    fun allocations_prepareScene_withCaching() {
        // Generate a 100-object scene
        val config = SceneConfig(objectCount = 100)
        val scene = DeterministicSceneGenerator.generate(config)

        // Create engine and add all shapes
        val engine = IsometricEngine()
        scene.forEach { item ->
            engine.add(item.shape, item.color)
        }

        // Render options with caching enabled
        val options = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enablePreparedSceneCache = true,  // Caching ENABLED
            enableDrawWithCache = false,
            enableBroadPhaseSort = false,
            enableSpatialIndex = false
        )

        // Benchmark allocation behavior
        // First call will allocate and cache, subsequent calls should hit cache
        val sceneVersion = 1  // Keep constant to ensure cache hits
        benchmarkRule.measureRepeated {
            // Use same sceneVersion to simulate static scene rendering
            // This should hit the cache after the first iteration
            engine.prepare(sceneVersion, 800, 600, options)
        }
    }

    /**
     * Benchmark allocations with cache but changing scene version.
     *
     * When sceneVersion changes, the cache is invalidated and a new
     * PreparedScene must be allocated. This simulates dynamic scenes
     * where content changes every frame.
     *
     * Expected: High allocation rate, similar to no caching
     */
    @Test
    fun allocations_prepareScene_cacheMisses() {
        // Generate a 100-object scene
        val config = SceneConfig(objectCount = 100)
        val scene = DeterministicSceneGenerator.generate(config)

        // Create engine and add all shapes
        val engine = IsometricEngine()
        scene.forEach { item ->
            engine.add(item.shape, item.color)
        }

        // Render options with caching enabled
        val options = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enablePreparedSceneCache = true,  // Caching ENABLED
            enableDrawWithCache = false,
            enableBroadPhaseSort = false,
            enableSpatialIndex = false
        )

        // Benchmark allocation behavior with changing scene version
        // Each iteration uses a different sceneVersion, forcing cache miss
        var sceneVersion = 0
        benchmarkRule.measureRepeated {
            // Increment sceneVersion to force cache miss
            sceneVersion++
            engine.prepare(sceneVersion, 800, 600, options)
        }
    }

    /**
     * Benchmark allocations for spatial index construction.
     *
     * When spatial index is enabled, the engine allocates:
     * - A SpatialIndex grid structure
     * - Cell lists for indexing render commands
     *
     * Expected: Additional allocations for index construction
     */
    @Test
    fun allocations_spatialIndex_construction() {
        // Generate a 100-object scene
        val config = SceneConfig(objectCount = 100)
        val scene = DeterministicSceneGenerator.generate(config)

        // Create engine and add all shapes
        val engine = IsometricEngine()
        scene.forEach { item ->
            engine.add(item.shape, item.color)
        }

        // Render options with spatial index enabled
        val options = RenderOptions(
            enableDepthSorting = true,
            enableBackfaceCulling = true,
            enableBoundsChecking = true,
            enablePreparedSceneCache = false,  // Disable to focus on spatial index
            enableDrawWithCache = false,
            enableBroadPhaseSort = false,
            enableSpatialIndex = true  // Spatial index ENABLED
        )

        // Benchmark allocation behavior
        var sceneVersion = 0
        benchmarkRule.measureRepeated {
            // Each iteration builds a new spatial index
            engine.prepare(sceneVersion, 800, 600, options)
        }
    }
}
