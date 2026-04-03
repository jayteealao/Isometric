package io.github.jayteealao.isometric.benchmark

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.WindowManager
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
 * adb shell am start -W -n io.github.jayteealao.isometric.benchmark/.BenchmarkActivity \
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Accept runner-controlled timestamp for consistent run directory across scenarios
        val runTimestamp = intent?.getStringExtra("runTimestamp")
        if (runTimestamp != null) {
            ResultsExporter.setRunTimestamp(runTimestamp)
        }

        val config = parseConfig()
        Log.i(TAG, "Starting benchmark: ${config.name}")

        setContent {
            BenchmarkScreen(
                config = config,
                onComplete = { result ->
                    Log.i(TAG, "Benchmark complete: ${config.name}")

                    // Export results with per-iteration raw timings
                    ResultsExporter.export(
                        this@BenchmarkActivity,
                        config,
                        result.iterations,
                        result.iterationRawTimings
                    )

                    // Validate — write to the same timestamped run directory
                    val validationResults = HarnessValidator.validate(
                        config, result.iterations, result.runtimeFlags
                    )
                    val runDir = ResultsExporter.getRunDir(this@BenchmarkActivity)
                    val allPassed = HarnessValidator.writeValidationLog(runDir, config, validationResults)

                    // Write a file-based result marker for the shell runner.
                    // am start -W does not reliably surface setResult() values across
                    // Android versions, so the runner checks this file instead.
                    val resultFile = java.io.File(runDir, "${config.name}.result")
                    resultFile.writeText(if (allPassed) "PASS" else "FAIL")
                    Log.i(TAG, "Result marker: ${resultFile.absolutePath} -> ${if (allPassed) "PASS" else "FAIL"}")

                    setResult(if (allPassed) Activity.RESULT_OK else Activity.RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    private fun parseConfig(): BenchmarkConfig {
        val configJson = intent?.getStringExtra("configBase64")?.let(::decodeConfigBase64)
            ?: intent?.getStringExtra("config")
            ?: return defaultConfig()

        return try {
            val json = JSONObject(configJson)

            val scenario = Scenario(
                sceneSize = json.optInt("sceneSize", 10),
                mutationRate = json.optDouble("mutationRate", 0.0),
                interactionPattern = InteractionPattern.valueOf(
                    json.optString("interactionPattern", "NONE")
                )
            )

            // Flags must be explicit — default to ALL_OFF when omitted to avoid
            // hidden optimizations. The runner always passes flags explicitly.
            val flags = if (json.has("flags")) {
                val fj = json.getJSONObject("flags")
                BenchmarkFlags(
                    enablePathCaching = fj.optBoolean("enablePathCaching", false),
                    enableSpatialIndex = fj.optBoolean("enableSpatialIndex", false),
                    enablePreparedSceneCache = fj.optBoolean("enablePreparedSceneCache", false),
                    enableNativeCanvas = fj.optBoolean("enableNativeCanvas", false),
                    enableBroadPhaseSort = fj.optBoolean("enableBroadPhaseSort", false),
                    renderModeId = fj.optString("renderMode", RenderModeId.CANVAS_CPU),
                )
            } else {
                Log.w(TAG, "No flags specified in config — defaulting to ALL_OFF (baseline)")
                BenchmarkFlags.ALL_OFF
            }

            BenchmarkConfig(
                scenario = scenario,
                flags = flags,
                name = json.optString("name", ""),
                iterations = json.optInt("iterations", 3),
                measurementFrames = json.optInt("measurementFrames", 500),
                warmupMinFrames = json.optInt("warmupMinFrames", 30),
                warmupMaxFrames = json.optInt("warmupMaxFrames", 500),
                warmupMaxSeconds = json.optInt("warmupMaxSeconds", 30),
                cooldownSeconds = json.optInt("cooldownSeconds", 2)
            ).let { if (it.name.isEmpty()) it.copy(name = BenchmarkConfig(it.scenario, it.flags).name) else it }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config, using default", e)
            defaultConfig()
        }
    }

    private fun decodeConfigBase64(encoded: String): String {
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val padded = normalized.padEnd(((normalized.length + 3) / 4) * 4, '=')
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun defaultConfig() = BenchmarkConfig(
        scenario = Scenario(
            sceneSize = 10,
            mutationRate = 0.0,
            interactionPattern = InteractionPattern.NONE
        ),
        flags = BenchmarkFlags.ALL_OFF,
        name = "default_N10_all_off"
    )
}
