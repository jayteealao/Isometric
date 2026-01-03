package io.fabianterhorst.isometric.benchmark

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Helper to run benchmark scenarios sequentially
 *
 * Usage from adb:
 * adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner \
 *   --es scenario smoke
 */
class ManualBenchmarkRunner : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scenarioType = intent.getStringExtra("scenario") ?: "smoke"
        val scenarios = when (scenarioType) {
            "smoke" -> BaselineScenarios.getSmokeTest()
            "all" -> BaselineScenarios.getAll()
            else -> {
                Log.e(TAG, "Unknown scenario type: $scenarioType")
                finish()
                return
            }
        }

        Log.i(TAG, "Running ${scenarios.size} scenarios")

        lifecycleScope.launch {
            scenarios.forEach { config ->
                Log.i(TAG, "Starting: ${config.name}")
                runScenario(config)
                delay(5000) // Cooldown between scenarios
            }
            Log.i(TAG, "All scenarios complete")
            finish()
        }
    }

    private suspend fun runScenario(config: BenchmarkConfig) {
        val intent = Intent(this, BenchmarkActivity::class.java).apply {
            putExtra("config", config.toBundle())
        }
        startActivity(intent)

        // Wait for benchmark to complete (rough estimate: warmup 8s + measurement 8s + overhead 2s)
        delay(20000)
    }

    companion object {
        private const val TAG = "ManualBenchmarkRunner"
    }
}
