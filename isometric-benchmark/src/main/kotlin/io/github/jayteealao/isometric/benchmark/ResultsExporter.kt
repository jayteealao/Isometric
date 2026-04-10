package io.github.jayteealao.isometric.benchmark

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports benchmark results to CSV and JSON files.
 *
 * Output structure:
 * ```
 * benchmark-results/<timestamp>/
 * ├── summary.csv              — one row per scenario (append mode across scenarios)
 * ├── <scenario-name>.json     — full per-frame data for all iterations
 * └── <scenario-name>_validation.log
 * ```
 *
 * The summary.csv uses append mode: each scenario appends its rows.
 * Device metadata columns are included for cross-device comparison.
 */
object ResultsExporter {

    private const val TAG = "IsoBenchmark"

    /** Timestamp for the current benchmark run, shared across all scenarios */
    private var runTimestamp: String? = null

    /**
     * Set the run timestamp externally (e.g., from the shell runner via intent extra).
     * This ensures all scenarios in a run share the same output directory,
     * even if the app process is recreated between scenarios.
     */
    fun setRunTimestamp(timestamp: String) {
        runTimestamp = timestamp
    }

    /**
     * Get or create the timestamped run directory for this benchmark session.
     */
    fun getRunDir(context: Context): File {
        if (runTimestamp == null) {
            runTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }
        val baseDir = getOutputDir(context)
        val runDir = File(baseDir, runTimestamp!!)
        runDir.mkdirs()
        return runDir
    }

    /**
     * Export results for a completed benchmark scenario.
     *
     * Appends to summary.csv (creating header if first scenario) and writes
     * a per-scenario JSON with full per-frame timing arrays for all iterations.
     *
     * @param context Android context for file access
     * @param config The benchmark configuration
     * @param iterations Per-iteration metrics snapshots
     * @param iterationRawTimings Per-iteration raw timing arrays (one per iteration)
     */
    fun export(
        context: Context,
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>,
        iterationRawTimings: List<RawTimings>
    ) {
        val runDir = getRunDir(context)

        exportSummaryCsv(runDir, config, iterations)
        exportJson(runDir, config, iterations, iterationRawTimings)

        Log.i(TAG, "Results exported to: ${runDir.absolutePath}")
    }

    /**
     * Append one row to summary.csv per scenario (aggregated across iterations).
     * Creates header on first write.
     */
    private fun exportSummaryCsv(
        runDir: File,
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>
    ) {
        val file = File(runDir, "summary.csv")
        val needsHeader = !file.exists() || file.length() == 0L

        val sb = StringBuilder()
        if (needsHeader) {
            sb.appendLine(buildCsvHeader())
        }

        val aggregated = aggregateIterations(iterations)
        sb.appendLine(buildCsvRow(config, aggregated))

        file.appendText(sb.toString())
        Log.i(TAG, "CSV: ${file.absolutePath}")
    }

    /**
     * Aggregate per-iteration metrics into a single scenario-level summary.
     * - StatSummary fields: mean across iterations (except min→min-of-mins, max→max-of-maxes)
     * - Cache hits/misses: summed across iterations
     * - cacheHitRate: recomputed from summed hits/misses
     * - observedMutationRate: mean across iterations
     * - allocatedMB: max across iterations
     * - gcInvocations: max across iterations
     * - frameCount/warmupFrames: from first iteration
     */
    private fun aggregateIterations(iterations: List<FrameMetrics>): FrameMetrics {
        if (iterations.size == 1) return iterations.first()

        fun aggregateStats(selector: (FrameMetrics) -> StatSummary): StatSummary {
            val stats = iterations.map(selector)
            return StatSummary(
                mean = stats.map { it.mean }.average(),
                p50 = stats.map { it.p50 }.average(),
                p95 = stats.map { it.p95 }.average(),
                p99 = stats.map { it.p99 }.average(),
                min = stats.minOf { it.min },
                max = stats.maxOf { it.max },
                stdDev = stats.map { it.stdDev }.average(),
                cv = stats.map { it.cv }.average()
            )
        }

        val totalHits = iterations.sumOf { it.cacheHits }
        val totalMisses = iterations.sumOf { it.cacheMisses }
        val totalCacheOps = totalHits + totalMisses

        return FrameMetrics(
            prepareTimeMs = aggregateStats { it.prepareTimeMs },
            drawTimeMs = aggregateStats { it.drawTimeMs },
            frameTimeMs = aggregateStats { it.frameTimeMs },
            hitTestTimeMs = aggregateStats { it.hitTestTimeMs },
            cacheHits = totalHits,
            cacheMisses = totalMisses,
            cacheHitRate = if (totalCacheOps > 0) totalHits.toDouble() / totalCacheOps else 0.0,
            observedMutationRate = iterations.map { it.observedMutationRate }.average(),
            allocatedMB = iterations.maxOf { it.allocatedMB },
            gcInvocations = iterations.maxOf { it.gcInvocations },
            frameCount = iterations.first().frameCount,
            warmupFrames = iterations.first().warmupFrames,
            gpuComputeTimeMs = aggregateStats { it.gpuComputeTimeMs },
            gpuRenderTimeMs = aggregateStats { it.gpuRenderTimeMs },
            gpuTimestampsAvailable = iterations.all { it.gpuTimestampsAvailable },
        )
    }

    private fun buildCsvHeader(): String {
        val columns = mutableListOf(
            "scenarioName",
            "sceneSize",
            "mutationRate",
            "interactionPattern"
        )
        columns.addAll(BenchmarkFlags.FLAG_NAMES)
        columns.addAll(listOf(
            "avgPrepareMs",
            "p50PrepareMs",
            "p95PrepareMs",
            "p99PrepareMs",
            "minPrepareMs",
            "maxPrepareMs",
            "stdDevPrepareMs",
            "avgDrawMs",
            "p50DrawMs",
            "p95DrawMs",
            "p99DrawMs",
            "minDrawMs",
            "maxDrawMs",
            "stdDevDrawMs",
            "avgFrameMs",
            "p50FrameMs",
            "p95FrameMs",
            "p99FrameMs",
            "minFrameMs",
            "maxFrameMs",
            "stdDevFrameMs",
            "avgHitTestMs",
            "p95HitTestMs",
            "cacheHits",
            "cacheMisses",
            "cacheHitRate",
            "observedMutationRate",
            "warmupFrames",
            "measurementFrames",
            "iterations",
            "allocatedMB",
            "gcInvocations",
            "deviceModel",
            "androidVersion",
            "isEmulator",
            "timestamp",
            "gpuComputeMs",
            "gpuRenderMs",
            "gpuTotalMs",
            "gpuTimestampsAvailable"
        ))
        return columns.joinToString(",")
    }

    private fun buildCsvRow(config: BenchmarkConfig, metrics: FrameMetrics): String {
        val values = mutableListOf<String>()

        values.add(config.name)
        values.add(config.scenario.sceneSize.toString())
        values.add(config.scenario.mutationRate.toString())
        values.add(config.scenario.interactionPattern.name)

        // Flags
        config.flags.toValueList().forEach { values.add(it) }

        // Prepare metrics
        values.add("%.4f".format(metrics.prepareTimeMs.mean))
        values.add("%.4f".format(metrics.prepareTimeMs.p50))
        values.add("%.4f".format(metrics.prepareTimeMs.p95))
        values.add("%.4f".format(metrics.prepareTimeMs.p99))
        values.add("%.4f".format(metrics.prepareTimeMs.min))
        values.add("%.4f".format(metrics.prepareTimeMs.max))
        values.add("%.4f".format(metrics.prepareTimeMs.stdDev))

        // Draw metrics
        values.add("%.4f".format(metrics.drawTimeMs.mean))
        values.add("%.4f".format(metrics.drawTimeMs.p50))
        values.add("%.4f".format(metrics.drawTimeMs.p95))
        values.add("%.4f".format(metrics.drawTimeMs.p99))
        values.add("%.4f".format(metrics.drawTimeMs.min))
        values.add("%.4f".format(metrics.drawTimeMs.max))
        values.add("%.4f".format(metrics.drawTimeMs.stdDev))

        // Frame metrics
        values.add("%.4f".format(metrics.frameTimeMs.mean))
        values.add("%.4f".format(metrics.frameTimeMs.p50))
        values.add("%.4f".format(metrics.frameTimeMs.p95))
        values.add("%.4f".format(metrics.frameTimeMs.p99))
        values.add("%.4f".format(metrics.frameTimeMs.min))
        values.add("%.4f".format(metrics.frameTimeMs.max))
        values.add("%.4f".format(metrics.frameTimeMs.stdDev))

        // Hit test
        values.add("%.4f".format(metrics.hitTestTimeMs.mean))
        values.add("%.4f".format(metrics.hitTestTimeMs.p95))

        // Cache
        values.add(metrics.cacheHits.toString())
        values.add(metrics.cacheMisses.toString())
        values.add("%.4f".format(metrics.cacheHitRate))
        values.add("%.4f".format(metrics.observedMutationRate))

        // Run metadata
        values.add(metrics.warmupFrames.toString())
        values.add(metrics.frameCount.toString())
        values.add(config.iterations.toString())
        values.add("%.2f".format(metrics.allocatedMB))
        values.add(metrics.gcInvocations.toString())

        // Device metadata
        values.add("\"${Build.MODEL}\"")
        values.add("${Build.VERSION.SDK_INT}")
        values.add(isEmulator().toString())
        values.add(runTimestamp ?: "unknown")

        // GPU timestamp columns (appended for backward compatibility)
        values.add("%.4f".format(metrics.gpuComputeTimeMs.mean))
        values.add("%.4f".format(metrics.gpuRenderTimeMs.mean))
        values.add("%.4f".format(metrics.gpuComputeTimeMs.mean + metrics.gpuRenderTimeMs.mean))
        values.add(metrics.gpuTimestampsAvailable.toString())

        return values.joinToString(",")
    }

    /**
     * Export JSON with config, device info, per-iteration summaries, and raw per-frame timings.
     *
     * Each iteration includes its own raw timing arrays captured before collector reset,
     * so multi-iteration runs preserve full per-frame data for every iteration.
     */
    private fun exportJson(
        runDir: File,
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>,
        iterationRawTimings: List<RawTimings>
    ) {
        val file = File(runDir, "${config.name}.json")

        val json = JSONObject().apply {
            put("name", config.name)
            put("timestamp", runTimestamp ?: "unknown")

            put("scenario", JSONObject().apply {
                put("sceneSize", config.scenario.sceneSize)
                put("mutationRate", config.scenario.mutationRate)
                put("interactionPattern", config.scenario.interactionPattern.name)
            })
            put("flags", JSONObject().apply {
                put("enablePathCaching", config.flags.enablePathCaching)
                put("enableSpatialIndex", config.flags.enableSpatialIndex)
                put("enablePreparedSceneCache", config.flags.enablePreparedSceneCache)
                put("enableNativeCanvas", config.flags.enableNativeCanvas)
                put("enableBroadPhaseSort", config.flags.enableBroadPhaseSort)
                put("renderMode", config.flags.renderModeId)
            })
            put("config", JSONObject().apply {
                put("iterations", config.iterations)
                put("measurementFrames", config.measurementFrames)
                put("warmupMinFrames", config.warmupMinFrames)
                put("warmupMaxFrames", config.warmupMaxFrames)
            })
            put("device", JSONObject().apply {
                put("model", Build.MODEL)
                put("androidVersion", Build.VERSION.SDK_INT)
                put("isEmulator", isEmulator())
            })

            // Per-iteration summaries with raw per-frame timing arrays
            val iterArray = JSONArray()
            iterations.forEachIndexed { index, metrics ->
                iterArray.put(JSONObject().apply {
                    put("prepare", metricsToJson(metrics.prepareTimeMs))
                    put("draw", metricsToJson(metrics.drawTimeMs))
                    put("frame", metricsToJson(metrics.frameTimeMs))
                    put("hitTest", metricsToJson(metrics.hitTestTimeMs))
                    put("cacheHitRate", metrics.cacheHitRate)
                    put("cacheHits", metrics.cacheHits)
                    put("cacheMisses", metrics.cacheMisses)
                    put("observedMutationRate", metrics.observedMutationRate)
                    put("warmupFrames", metrics.warmupFrames)
                    put("measurementFrames", metrics.frameCount)
                    put("allocatedMB", metrics.allocatedMB)
                    put("gcInvocations", metrics.gcInvocations)

                    // GPU timestamp summary
                    put("gpuTimestamps", JSONObject().apply {
                        put("available", metrics.gpuTimestampsAvailable)
                        put("compute", metricsToJson(metrics.gpuComputeTimeMs))
                        put("render", metricsToJson(metrics.gpuRenderTimeMs))
                    })

                    // Raw per-frame timings for this iteration
                    if (index < iterationRawTimings.size) {
                        val raw = iterationRawTimings[index]
                        put("rawTimings", JSONObject().apply {
                            put("prepareTimes", longArrayToJsonArray(raw.prepareTimes))
                            put("drawTimes", longArrayToJsonArray(raw.drawTimes))
                            put("frameTimes", longArrayToJsonArray(raw.frameTimes))
                            put("hitTestTimes", longArrayToJsonArray(raw.hitTestTimes))
                            put("gpuComputeTimes", longArrayToJsonArray(raw.gpuComputeTimes))
                            put("gpuRenderTimes", longArrayToJsonArray(raw.gpuRenderTimes))
                        })
                    }
                })
            }
            put("iterations", iterArray)
        }

        file.writeText(json.toString(2))
        Log.i(TAG, "JSON: ${file.absolutePath}")
    }

    private fun metricsToJson(stats: StatSummary): JSONObject {
        return JSONObject().apply {
            put("mean", stats.mean)
            put("p50", stats.p50)
            put("p95", stats.p95)
            put("p99", stats.p99)
            put("min", stats.min)
            put("max", stats.max)
            put("stdDev", stats.stdDev)
            put("cv", stats.cv)
        }
    }

    private fun longArrayToJsonArray(arr: LongArray): JSONArray {
        val jsonArr = JSONArray()
        for (v in arr) jsonArr.put(v)
        return jsonArr
    }

    fun getOutputDir(context: Context): File {
        return context.getExternalFilesDir("benchmark-results")
            ?: File(context.filesDir, "benchmark-results")
    }

    /**
     * Best-effort emulator detection.
     */
    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.PRODUCT.contains("sdk") ||
                Build.PRODUCT.contains("emulator")
    }
}
