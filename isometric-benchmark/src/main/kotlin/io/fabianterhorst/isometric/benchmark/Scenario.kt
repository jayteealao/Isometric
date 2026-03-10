package io.fabianterhorst.isometric.benchmark

/**
 * Defines the interaction pattern used during benchmark measurement.
 *
 * @property NONE No tap interactions — pure rendering benchmark
 * @property OCCASIONAL Tap every 60 frames — simulates casual user interaction
 * @property CONTINUOUS Tap every frame — stress-tests hit testing pipeline
 */
enum class InteractionPattern {
    NONE,
    OCCASIONAL,
    CONTINUOUS
}

/**
 * Defines the scene characteristics for a benchmark run.
 *
 * @property sceneSize Number of shapes in the scene (e.g., 10, 50, 200, 1000)
 * @property mutationRate Fraction of shapes mutated per frame (0.0 = static, 0.1 = 10%)
 * @property interactionPattern How often hit-test taps are injected
 */
data class Scenario(
    val sceneSize: Int,
    val mutationRate: Double,
    val interactionPattern: InteractionPattern
)

/**
 * Complete benchmark configuration combining a scenario with optimization flags
 * and run parameters.
 *
 * @property scenario The scene characteristics
 * @property flags Which rendering optimizations are enabled
 * @property name Human-readable name for results identification
 * @property iterations Number of measurement iterations (default: 3)
 * @property measurementFrames Number of frames per measurement iteration (default: 500)
 * @property warmupMinFrames Minimum warmup frames before stability check (default: 30)
 * @property warmupMaxFrames Maximum warmup frames before giving up on stability (default: 500)
 * @property warmupMaxSeconds Maximum warmup duration in seconds (default: 30)
 * @property cooldownSeconds Cooldown duration between iterations in seconds (default: 2)
 */
data class BenchmarkConfig(
    val scenario: Scenario,
    val flags: BenchmarkFlags = BenchmarkFlags.ALL_ON,
    val name: String = buildDefaultName(scenario, flags),
    val iterations: Int = 3,
    val measurementFrames: Int = 500,
    val warmupMinFrames: Int = 30,
    val warmupMaxFrames: Int = 500,
    val warmupMaxSeconds: Int = 30,
    val cooldownSeconds: Int = 2
) {
    companion object {
        private fun buildDefaultName(scenario: Scenario, flags: BenchmarkFlags): String {
            val size = "N${scenario.sceneSize}"
            val mutation = if (scenario.mutationRate > 0.0) "mut${(scenario.mutationRate * 100).toInt()}" else "static"
            val interaction = scenario.interactionPattern.name.lowercase()
            val flagSuffix = if (flags == BenchmarkFlags.ALL_ON) "allOn" else "custom"
            return "${size}_${mutation}_${interaction}_$flagSuffix"
        }
    }
}
