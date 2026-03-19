package io.github.jayteealao.isometric.benchmark

import android.util.Log
import io.github.jayteealao.isometric.compose.runtime.RuntimeFlagSnapshot
import java.io.File

/**
 * Validates benchmark harness results for correctness and consistency.
 *
 * Five validation checks:
 * 1. **Cache validation**: If cache enabled AND mutationRate=0, hit rate > 95%
 * 2. **Flag validation**: Flags in output match flags in config
 * 3. **Consistency**: CV < 15% across iterations (warning only, not a hard failure)
 * 4. **Sanity**: avgFrameMs < 20ms for N=10 all-flags-disabled baseline only
 * 5. **Mutation validation**: Observed mutation rate ≈ configured rate ±2%
 *
 * Self-test scenarios are defined as companion constants and must pass
 * before any benchmark matrix run proceeds.
 */
object HarnessValidator {

    private const val TAG = "IsoBenchmark"

    /** Self-test: cache validation (N=10, cache ON, no mutations, 100 frames, 1 iteration) */
    val SELFTEST_CACHE = BenchmarkConfig(
        scenario = Scenario(
            sceneSize = 10,
            mutationRate = 0.0,
            interactionPattern = InteractionPattern.NONE
        ),
        flags = BenchmarkFlags.ALL_ON,
        name = "selftest_cache",
        iterations = 1,
        measurementFrames = 100,
        warmupMinFrames = 10,
        warmupMaxFrames = 50,
        warmupMaxSeconds = 10
    )

    /**
     * Self-test: sanity check (N=10, all flags OFF, mutationRate=0.10, 100 frames, 1 iteration).
     *
     * Uses mutationRate=0.10 to exercise the mutation path. All flags OFF means
     * `enablePreparedSceneCache = false` → `forceRebuild = true`, so every frame
     * does a full rebuild (100% cache miss). Validates that even a full-rebuild
     * path on a 10-shape scene stays under 20ms.
     */
    val SELFTEST_SANITY = BenchmarkConfig(
        scenario = Scenario(
            sceneSize = 10,
            mutationRate = 0.10,
            interactionPattern = InteractionPattern.NONE
        ),
        flags = BenchmarkFlags.ALL_OFF,
        name = "selftest_sanity",
        iterations = 1,
        measurementFrames = 100,
        warmupMinFrames = 10,
        warmupMaxFrames = 50,
        warmupMaxSeconds = 10
    )

    /**
     * Result of a single validation check.
     */
    data class ValidationResult(
        val check: String,
        val passed: Boolean,
        val message: String
    )

    /**
     * Run all validation checks on benchmark results.
     *
     * @param config The benchmark config used
     * @param iterations Per-iteration metrics
     * @param runtimeFlags Actual runtime flag snapshot from IsometricScene (null if unavailable)
     * @return List of validation results (one per check)
     */
    fun validate(
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>,
        runtimeFlags: RuntimeFlagSnapshot? = null
    ): List<ValidationResult> {
        val results = mutableListOf<ValidationResult>()

        results.add(validateCache(config, iterations))
        results.add(validateFlags(config, runtimeFlags))
        results.add(validateConsistency(iterations))
        results.add(validateSanity(config, iterations))
        results.add(validateMutation(config, iterations))

        return results
    }

    /**
     * Write validation results to a log file.
     *
     * @return true if all checks passed
     */
    fun writeValidationLog(
        outputDir: File,
        config: BenchmarkConfig,
        results: List<ValidationResult>
    ): Boolean {
        val file = File(outputDir, "${config.name}_validation.log")
        val sb = StringBuilder()

        sb.appendLine("Validation Report: ${config.name}")
        sb.appendLine("=".repeat(60))

        var allPassed = true
        results.forEach { result ->
            val status = if (result.passed) "PASS" else "FAIL"
            if (!result.passed) allPassed = false
            sb.appendLine("[$status] ${result.check}: ${result.message}")
        }

        sb.appendLine("=".repeat(60))
        sb.appendLine(if (allPassed) "OVERALL: PASS" else "OVERALL: FAIL")

        file.writeText(sb.toString())
        Log.i(TAG, "Validation: ${file.absolutePath}")

        // Also log to logcat
        results.forEach { result ->
            val status = if (result.passed) "PASS" else "FAIL"
            val msg = "[$status] ${result.check}: ${result.message}"
            if (result.passed) Log.i(TAG, msg) else Log.w(TAG, msg)
        }

        return allPassed
    }

    // --- Individual checks ---

    private fun validateCache(config: BenchmarkConfig, iterations: List<FrameMetrics>): ValidationResult {
        if (!config.flags.enablePreparedSceneCache || config.scenario.mutationRate > 0.0) {
            return ValidationResult("cache", true, "Skipped (cache disabled or mutations active)")
        }

        val avgHitRate = iterations.map { it.cacheHitRate }.average()
        val passed = avgHitRate > 0.95

        return ValidationResult(
            "cache",
            passed,
            "Cache hit rate: ${"%.1f".format(avgHitRate * 100)}% (threshold: >95%)"
        )
    }

    private fun validateFlags(config: BenchmarkConfig, runtimeFlags: RuntimeFlagSnapshot?): ValidationResult {
        if (runtimeFlags == null) {
            return ValidationResult("flags", false, "No runtime flag snapshot available")
        }

        val mismatches = mutableListOf<String>()
        val flags = config.flags

        if (runtimeFlags.enablePathCaching != flags.enablePathCaching) {
            mismatches.add("enablePathCaching: config=${flags.enablePathCaching}, runtime=${runtimeFlags.enablePathCaching}")
        }
        if (runtimeFlags.enableSpatialIndex != flags.enableSpatialIndex) {
            mismatches.add("enableSpatialIndex: config=${flags.enableSpatialIndex}, runtime=${runtimeFlags.enableSpatialIndex}")
        }
        if (runtimeFlags.enableBroadPhaseSort != flags.enableBroadPhaseSort) {
            mismatches.add("enableBroadPhaseSort: config=${flags.enableBroadPhaseSort}, runtime=${runtimeFlags.enableBroadPhaseSort}")
        }
        // forceRebuild should be the inverse of enablePreparedSceneCache
        val expectedForceRebuild = !flags.enablePreparedSceneCache
        if (runtimeFlags.forceRebuild != expectedForceRebuild) {
            mismatches.add("forceRebuild: expected=$expectedForceRebuild, runtime=${runtimeFlags.forceRebuild}")
        }
        if (runtimeFlags.useNativeCanvas != flags.enableNativeCanvas) {
            mismatches.add("useNativeCanvas: config=${flags.enableNativeCanvas}, runtime=${runtimeFlags.useNativeCanvas}")
        }

        return if (mismatches.isEmpty()) {
            ValidationResult("flags", true, "All runtime flags match config")
        } else {
            ValidationResult("flags", false, "Flag mismatches: ${mismatches.joinToString("; ")}")
        }
    }

    private fun validateConsistency(iterations: List<FrameMetrics>): ValidationResult {
        if (iterations.size < 2) {
            return ValidationResult("consistency", true, "Skipped (single iteration)")
        }

        val frameMeans = iterations.map { it.frameTimeMs.mean }
        val mean = frameMeans.average()
        if (mean == 0.0) {
            return ValidationResult("consistency", true, "Skipped (zero frame time)")
        }

        val variance = frameMeans.map { (it - mean) * (it - mean) }.average()
        val stdDev = kotlin.math.sqrt(variance)
        val cv = stdDev / mean

        // Per plan: CV > 15% is a warning for investigation, not a hard failure.
        // High CV indicates thermal throttling, GC pressure, or background processes.
        val message = "Frame time CV across iterations: ${"%.1f".format(cv * 100)}% (threshold: <15%)"
        return if (cv >= 0.15) {
            ValidationResult("consistency", true, "WARNING: $message — flag for investigation")
        } else {
            ValidationResult("consistency", true, message)
        }
    }

    private fun validateSanity(config: BenchmarkConfig, iterations: List<FrameMetrics>): ValidationResult {
        // Per plan: sanity check only applies to the N=10, all-flags-disabled baseline.
        // It guards against fundamentally broken harness/device, not general performance.
        if (config.scenario.sceneSize != 10 || config.flags != BenchmarkFlags.ALL_OFF) {
            return ValidationResult("sanity", true, "Skipped (not N=10 baseline)")
        }

        val avgFrameMs = iterations.map { it.frameTimeMs.mean }.average()
        val passed = avgFrameMs < 20.0

        return ValidationResult(
            "sanity",
            passed,
            "Avg frame time: ${"%.2f".format(avgFrameMs)}ms for N=10 baseline (threshold: <20ms)"
        )
    }

    /**
     * Mutation validation: verify observed mutation rate matches configured rate ±2%.
     *
     * Uses actual mutation counts recorded per frame by the orchestrator from
     * MutationSimulator.mutate() results. Validates that mean(mutationCount) / sceneSize
     * is within ±2% of the configured mutation rate.
     */
    private fun validateMutation(config: BenchmarkConfig, iterations: List<FrameMetrics>): ValidationResult {
        if (config.scenario.mutationRate == 0.0) {
            return ValidationResult("mutation", true, "Skipped (no mutations)")
        }

        val observedRates = iterations.map { it.observedMutationRate }
        val avgObservedRate = observedRates.average()
        val expectedRate = config.scenario.mutationRate
        val tolerance = 0.02  // ±2% per plan
        val diff = kotlin.math.abs(avgObservedRate - expectedRate)
        val passed = diff <= tolerance

        return ValidationResult(
            "mutation",
            passed,
            "Observed mutation rate: ${"%.1f".format(avgObservedRate * 100)}% " +
                    "(expected: ${"%.0f".format(expectedRate * 100)}%, " +
                    "tolerance: ±${"%.0f".format(tolerance * 100)}%, " +
                    "diff: ${"%.1f".format(diff * 100)}%)"
        )
    }
}
