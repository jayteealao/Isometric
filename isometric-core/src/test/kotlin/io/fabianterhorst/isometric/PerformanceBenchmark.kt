package io.fabianterhorst.isometric

import io.fabianterhorst.isometric.shapes.Prism
import org.junit.Test
import java.io.File
import kotlin.system.measureNanoTime

/**
 * JVM-based performance benchmark - runs without any device/emulator
 *
 * Measures prepare() performance (transformation + depth sorting) across
 * different scene sizes and optimization configurations.
 */
class PerformanceBenchmark {

    data class BenchmarkResult(
        val name: String,
        val sceneSize: Int,
        val scenario: String,
        val enablePreparedCache: Boolean,
        val avgPrepareMs: Double,
        val minPrepareMs: Double,
        val maxPrepareMs: Double,
        val p50PrepareMs: Double,
        val p95PrepareMs: Double,
        val stdDevPrepareMs: Double,
        val iterations: Int
    )

    @Test
    fun runComprehensiveBenchmark() {
        println("=".repeat(80))
        println("JVM Performance Benchmark - No Device Required")
        println("=".repeat(80))
        println()

        val results = mutableListOf<BenchmarkResult>()
        val sceneSizes = listOf(100, 500, 1000, 2000, 5000)
        val scenarios = listOf("STATIC", "FULL_MUTATION")
        val cacheConfigs = listOf(
            "baseline" to RenderOptions(enablePreparedSceneCache = false),
            "preparedcache" to RenderOptions(enablePreparedSceneCache = true)
        )

        for (size in sceneSizes) {
            for (scenario in scenarios) {
                for ((configName, renderOptions) in cacheConfigs) {
                    val result = benchmarkConfiguration(
                        name = "${configName}_${scenario.lowercase()}_${size}",
                        sceneSize = size,
                        scenario = scenario,
                        renderOptions = renderOptions,
                        iterations = if (size > 1000) 10 else 50  // Fewer iterations for large scenes
                    )
                    results.add(result)

                    println("[${results.size}/${sceneSizes.size * scenarios.size * cacheConfigs.size}] " +
                            "${result.name}: ${result.avgPrepareMs}ms ± ${result.stdDevPrepareMs}ms")
                }
            }
        }

        // Export to CSV
        exportResultsToCsv(results)

        println()
        println("=".repeat(80))
        println("Benchmark Complete! Results saved to: isometric-core/benchmark-results-jvm.csv")
        println("=".repeat(80))
    }

    private fun benchmarkConfiguration(
        name: String,
        sceneSize: Int,
        scenario: String,
        renderOptions: RenderOptions,
        iterations: Int
    ): BenchmarkResult {
        val engine = IsometricEngine()
        val times = mutableListOf<Double>()

        // Populate engine with test scene
        populateScene(engine, sceneSize)

        val width = 1080
        val height = 2400

        // Warmup (10 iterations to let JIT compile)
        repeat(10) {
            val version = if (scenario == "FULL_MUTATION") it else 0
            engine.prepare(version, width, height, renderOptions)
        }

        // Measurement
        repeat(iterations) { iteration ->
            // For FULL_MUTATION, increment version to invalidate cache
            val version = if (scenario == "FULL_MUTATION") iteration else 0

            val timeNanos = measureNanoTime {
                engine.prepare(version, width, height, renderOptions)
            }
            times.add(timeNanos / 1_000_000.0) // Convert to milliseconds
        }

        // Calculate statistics
        times.sort()
        val avg = times.average()
        val min = times.minOrNull() ?: 0.0
        val max = times.maxOrNull() ?: 0.0
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 0.95).toInt()]
        val variance = times.map { (it - avg) * (it - avg) }.average()
        val stdDev = kotlin.math.sqrt(variance)

        return BenchmarkResult(
            name = name,
            sceneSize = sceneSize,
            scenario = scenario,
            enablePreparedCache = renderOptions.enablePreparedSceneCache,
            avgPrepareMs = avg,
            minPrepareMs = min,
            maxPrepareMs = max,
            p50PrepareMs = p50,
            p95PrepareMs = p95,
            stdDevPrepareMs = stdDev,
            iterations = iterations
        )
    }

    private fun populateScene(engine: IsometricEngine, size: Int) {
        val gridSize = kotlin.math.sqrt(size.toDouble()).toInt()

        for (x in 0 until gridSize) {
            for (y in 0 until gridSize) {
                if (x * gridSize + y >= size) break

                val shape = Prism(
                    origin = Point(x.toDouble(), y.toDouble(), 0.0),
                    dx = 1.0,
                    dy = 1.0,
                    dz = 1.0
                )

                val color = IsoColor(
                    r = (x * 255.0 / gridSize),
                    g = (y * 255.0 / gridSize),
                    b = 128.0
                )

                engine.add(shape, color)
            }
        }
    }

    private fun exportResultsToCsv(results: List<BenchmarkResult>) {
        val csvFile = File("isometric-core/benchmark-results-jvm.csv")
        csvFile.parentFile?.mkdirs()

        val header = "name,sceneSize,scenario,enablePreparedCache,avgPrepareMs,minPrepareMs,maxPrepareMs,p50PrepareMs,p95PrepareMs,stdDevPrepareMs,iterations"
        val rows = results.map { r ->
            "${r.name},${r.sceneSize},${r.scenario},${r.enablePreparedCache}," +
            "${r.avgPrepareMs},${r.minPrepareMs},${r.maxPrepareMs}," +
            "${r.p50PrepareMs},${r.p95PrepareMs},${r.stdDevPrepareMs},${r.iterations}"
        }

        csvFile.writeText(header + "\n" + rows.joinToString("\n") + "\n")
    }
}
