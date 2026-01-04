package io.fabianterhorst.isometric.benchmark

import android.content.Intent
import android.os.Bundle

data class BenchmarkConfig(
    val name: String,
    val sceneSize: Int,              // 10, 100, 500, 1000
    val scenario: Scenario,
    val interactionPattern: InteractionPattern,
    val flags: OptimizationFlags,
    val numberOfRuns: Int = 3,       // Number of benchmark runs to average
    val outputFile: String = "benchmark_results.csv"
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("name", name)
        putInt("sceneSize", sceneSize)
        putString("scenario", scenario.name)
        putString("interactionPattern", interactionPattern.name)
        putBundle("flags", flags.toBundle())
        putInt("numberOfRuns", numberOfRuns)
        putString("outputFile", outputFile)
    }

    companion object {
        fun fromIntent(intent: Intent): BenchmarkConfig {
            val bundle = intent.getBundleExtra("config")
                ?: throw IllegalArgumentException("No config bundle in intent")

            return BenchmarkConfig(
                name = bundle.getString("name") ?: "unknown",
                sceneSize = bundle.getInt("sceneSize", -1).also { size ->
                    require(size in setOf(10, 100, 500, 1000)) {
                        "Invalid sceneSize: $size. Must be 10, 100, 500, or 1000"
                    }
                },
                scenario = try {
                    Scenario.valueOf(bundle.getString("scenario") ?: "STATIC")
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid scenario: ${bundle.getString("scenario")}", e)
                },
                interactionPattern = try {
                    InteractionPattern.valueOf(bundle.getString("interactionPattern") ?: "NONE")
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid interactionPattern: ${bundle.getString("interactionPattern")}", e)
                },
                flags = OptimizationFlags.fromBundle(
                    bundle.getBundle("flags") ?: Bundle()
                ),
                numberOfRuns = bundle.getInt("numberOfRuns", 3),
                outputFile = bundle.getString("outputFile") ?: "benchmark_results.csv"
            )
        }
    }
}

enum class Scenario {
    STATIC,
    INCREMENTAL_1,   // 1% mutation per frame
    INCREMENTAL_10,  // 10% mutation per frame
    FULL_MUTATION
}

enum class InteractionPattern {
    NONE,
    OCCASIONAL,   // 1 tap per second
    CONTINUOUS,   // 60 taps per second
    HOVER         // 30 taps per second
}

data class OptimizationFlags(
    val enablePreparedSceneCache: Boolean = false,
    val enableDrawWithCache: Boolean = false,
    val enableBroadPhaseSort: Boolean = false,
    val enableSpatialIndex: Boolean = false
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean("enablePreparedSceneCache", enablePreparedSceneCache)
        putBoolean("enableDrawWithCache", enableDrawWithCache)
        putBoolean("enableBroadPhaseSort", enableBroadPhaseSort)
        putBoolean("enableSpatialIndex", enableSpatialIndex)
    }

    companion object {
        val BASELINE = OptimizationFlags()
        val PHASE_1 = BASELINE.copy(enablePreparedSceneCache = true)
        val PHASE_2 = PHASE_1.copy(enableDrawWithCache = true)
        val PHASE_3 = PHASE_2.copy(enableBroadPhaseSort = true)
        val PHASE_4 = PHASE_3.copy(enableSpatialIndex = true)

        fun fromBundle(bundle: Bundle): OptimizationFlags = OptimizationFlags(
            enablePreparedSceneCache = bundle.getBoolean("enablePreparedSceneCache"),
            enableDrawWithCache = bundle.getBoolean("enableDrawWithCache"),
            enableBroadPhaseSort = bundle.getBoolean("enableBroadPhaseSort"),
            enableSpatialIndex = bundle.getBoolean("enableSpatialIndex")
        )
    }
}
