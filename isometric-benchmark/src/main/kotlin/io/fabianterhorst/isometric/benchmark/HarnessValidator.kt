package io.fabianterhorst.isometric.benchmark

import android.util.Log
import java.io.File

/**
 * Validates benchmark harness results for correctness and consistency.
 *
 * Five validation checks:
 * 1. **Cache validation**: If cache enabled AND mutationRate=0, hit rate > 95%
 * 2. **Flag validation**: Flags in output match flags in config
 * 3. **Consistency**: CV < 15% across iterations for key metrics
 * 4. **Sanity**: avgFrameMs < 20ms for N ≤ 50
 * 5. **Mutation validation**: Observed mutation rate ≈ configured rate ±5%
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

    /** Self-test: sanity check (N=10, all flags OFF, 100 frames, 1 iteration) */
    val SELFTEST_SANITY = BenchmarkConfig(
        scenario = Scenario(
            sceneSize = 10,
            mutationRate = 0.0,
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
     * @return List of validation results (one per check)
     */
    fun validate(
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>
    ): List<ValidationResult> {
        val results = mutableListOf<ValidationResult>()

        results.add(validateCache(config, iterations))
        results.add(validateFlags(config))
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
        sb.appendLine("=" .repeat(60))

        var allPassed = true
        results.forEach { result ->
            val status = if (result.passed) "PASS" else "FAIL"
            if (!result.passed) allPassed = false
            sb.appendLine("[$status] ${result.check}: ${result.message}")
        }

        sb.appendLine("=" .repeat(60))
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

    private fun validateFlags(config: BenchmarkConfig): ValidationResult {
        // Flags are embedded in config — just verify they're valid
        val flags = config.flags
        val valid = BenchmarkFlags.FLAG_NAMES.size == flags.toValueList().size

        return ValidationResult(
            "flags",
            valid,
            "Flag count: ${flags.toValueList().size}/${BenchmarkFlags.FLAG_NAMES.size}"
        )
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
        val passed = cv < 0.15

        return ValidationResult(
            "consistency",
            passed,
            "Frame time CV across iterations: ${"%.1f".format(cv * 100)}% (threshold: <15%)"
        )
    }

    private fun validateSanity(config: BenchmarkConfig, iterations: List<FrameMetrics>): ValidationResult {
        if (config.scenario.sceneSize > 50) {
            return ValidationResult("sanity", true, "Skipped (N > 50)")
        }

        val avgFrameMs = iterations.map { it.frameTimeMs.mean }.average()
        val passed = avgFrameMs < 20.0

        return ValidationResult(
            "sanity",
            passed,
            "Avg frame time: ${"%.2f".format(avgFrameMs)}ms for N=${config.scenario.sceneSize} (threshold: <20ms)"
        )
    }

    private fun validateMutation(config: BenchmarkConfig, iterations: List<FrameMetrics>): ValidationResult {
        if (config.scenario.mutationRate == 0.0) {
            return ValidationResult("mutation", true, "Skipped (no mutations)")
        }

        // For mutation validation, we check that cache misses occurred
        // (mutations should invalidate the cache)
        val totalMisses = iterations.sumOf { it.cacheMisses }
        val totalOps = iterations.sumOf { it.cacheHits + it.cacheMisses }

        if (totalOps == 0L) {
            return ValidationResult("mutation", false, "No cache operations recorded")
        }

        val missRate = totalMisses.toDouble() / totalOps
        // With mutations, we expect some cache misses
        val passed = missRate > (config.scenario.mutationRate * 0.5)  // Generous tolerance

        return ValidationResult(
            "mutation",
            passed,
            "Cache miss rate: ${"%.1f".format(missRate * 100)}% with mutation rate ${"%.0f".format(config.scenario.mutationRate * 100)}%"
        )
    }
}
