package io.fabianterhorst.isometric.benchmark

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.json.JSONObject

/**
 * Entry point for benchmark runs.
 *
 * Accepts configuration via intent extras:
 * - `config`: JSON string encoding a [BenchmarkConfig]
 *
 * Example ADB launch:
 * ```
 * adb shell am start -W -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
 *   --es config '{"sceneSize":50,"mutationRate":0.1,"interactionPattern":"NONE"}'
 * ```
 *
 * If no config is provided, runs a default N=10 baseline scenario.
 * Locks orientation to landscape for consistent viewport dimensions.
 */
class BenchmarkActivity : ComponentActivity() {

    companion object {
        private const val TAG = "IsoBenchmark"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        val config = parseConfig()
        Log.i(TAG, "Starting benchmark: ${config.name}")

        if (config.flags.enableBroadPhaseSort) {
            Log.w(TAG, "enableBroadPhaseSort is set but not yet implemented in the engine (Phase 3)")
        }

        val collector = MetricsCollector(config.measurementFrames)

        setContent {
            BenchmarkScreen(
                config = config,
                onComplete = { iterations ->
                    Log.i(TAG, "Benchmark complete: ${config.name}")

                    // Export results
                    ResultsExporter.export(this@BenchmarkActivity, config, iterations, collector)

                    // Validate
                    val validationResults = HarnessValidator.validate(config, iterations)
                    val outputDir = ResultsExporter.getOutputDir(this@BenchmarkActivity)
                    val allPassed = HarnessValidator.writeValidationLog(outputDir, config, validationResults)

                    setResult(if (allPassed) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    private fun parseConfig(): BenchmarkConfig {
        val configJson = intent?.getStringExtra("config") ?: return defaultConfig()

        return try {
            val json = JSONObject(configJson)

            val scenario = Scenario(
                sceneSize = json.optInt("sceneSize", 10),
                mutationRate = json.optDouble("mutationRate", 0.0),
                interactionPattern = InteractionPattern.valueOf(
                    json.optString("interactionPattern", "NONE")
                )
            )

            val flags = if (json.has("flags")) {
                val fj = json.getJSONObject("flags")
                BenchmarkFlags(
                    enablePathCaching = fj.optBoolean("enablePathCaching", true),
                    enableSpatialIndex = fj.optBoolean("enableSpatialIndex", true),
                    enablePreparedSceneCache = fj.optBoolean("enablePreparedSceneCache", true),
                    enableNativeCanvas = fj.optBoolean("enableNativeCanvas", false),
                    enableBroadPhaseSort = fj.optBoolean("enableBroadPhaseSort", false)
                )
            } else {
                BenchmarkFlags.ALL_ON
            }

            BenchmarkConfig(
                scenario = scenario,
                flags = flags,
                name = json.optString("name", ""),
                iterations = json.optInt("iterations", 3),
                measurementFrames = json.optInt("measurementFrames", 500)
            ).let { if (it.name.isEmpty()) it.copy(name = BenchmarkConfig(it.scenario, it.flags).name) else it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config, using default", e)
            defaultConfig()
        }
    }

    private fun defaultConfig() = BenchmarkConfig(
        scenario = Scenario(
            sceneSize = 10,
            mutationRate = 0.0,
            interactionPattern = InteractionPattern.NONE
        ),
        flags = BenchmarkFlags.ALL_ON,
        name = "default_N10_baseline"
    )
}
