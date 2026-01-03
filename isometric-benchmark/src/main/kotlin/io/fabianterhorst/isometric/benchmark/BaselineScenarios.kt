package io.fabianterhorst.isometric.benchmark

object BaselineScenarios {
    /**
     * All 32 baseline scenarios (4 sizes × 2 scene types × 2 interaction patterns × 1 optimization level)
     *
     * Focused subset for initial baseline:
     * - Scene sizes: 10, 100, 500, 1000
     * - Scene types: STATIC, FULL_MUTATION (extremes)
     * - Interactions: NONE, CONTINUOUS (extremes)
     */
    fun getAll(): List<BenchmarkConfig> = buildList {
        val sizes = listOf(10, 100, 500, 1000)
        val scenarios = listOf(Scenario.STATIC, Scenario.FULL_MUTATION)
        val interactions = listOf(InteractionPattern.NONE, InteractionPattern.CONTINUOUS)

        for (size in sizes) {
            for (scenario in scenarios) {
                for (interaction in interactions) {
                    add(
                        BenchmarkConfig(
                            name = "baseline_${scenario.name.lowercase()}_${size}_${interaction.name.lowercase()}",
                            sceneSize = size,
                            scenario = scenario,
                            interactionPattern = interaction,
                            flags = OptimizationFlags.BASELINE,
                            outputFile = "baseline_results.csv"
                        )
                    )
                }
            }
        }
    }

    /**
     * Quick smoke test - just 4 scenarios
     */
    fun getSmokeTest(): List<BenchmarkConfig> = listOf(
        BenchmarkConfig(
            name = "smoke_static_10_none",
            sceneSize = 10,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        ),
        BenchmarkConfig(
            name = "smoke_static_100_none",
            sceneSize = 100,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        ),
        BenchmarkConfig(
            name = "smoke_mutation_100_none",
            sceneSize = 100,
            scenario = Scenario.FULL_MUTATION,
            interactionPattern = InteractionPattern.NONE,
            flags = OptimizationFlags.BASELINE
        ),
        BenchmarkConfig(
            name = "smoke_static_100_continuous",
            sceneSize = 100,
            scenario = Scenario.STATIC,
            interactionPattern = InteractionPattern.CONTINUOUS,
            flags = OptimizationFlags.BASELINE
        )
    )
}
