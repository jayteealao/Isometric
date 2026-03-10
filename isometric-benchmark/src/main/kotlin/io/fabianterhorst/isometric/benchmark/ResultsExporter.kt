package io.fabianterhorst.isometric.benchmark

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports benchmark results to CSV and JSON files.
 *
 * - **CSV**: One row per iteration with summary statistics
 * - **JSON**: Full per-frame timing arrays for detailed analysis
 *
 * Output directory: `context.getExternalFilesDir("benchmark-results")`
 * Fallback: `context.filesDir/benchmark-results` if external storage unavailable.
 */
object ResultsExporter {

    private const val TAG = "IsoBenchmark"

    /**
     * Export results for a completed benchmark run.
     *
     * @param context Android context for file access
     * @param config The benchmark configuration
     * @param iterations Per-iteration metrics snapshots
     * @param collector The collector (for raw timings on last iteration)
     */
    fun export(
        context: Context,
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>,
        collector: MetricsCollector
    ) {
        val outputDir = getOutputDir(context)
        outputDir.mkdirs()

        val baseName = config.name
        exportCsv(outputDir, baseName, config, iterations)
        exportJson(outputDir, baseName, config, iterations, collector)

        Log.i(TAG, "Results exported to: ${outputDir.absolutePath}/$baseName.*")
    }

    private fun exportCsv(
        outputDir: File,
        baseName: String,
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>
    ) {
        val file = File(outputDir, "$baseName.csv")
        val sb = StringBuilder()

        // Header
        sb.appendLine(buildCsvHeader())

        // Data rows
        iterations.forEachIndexed { index, metrics ->
            sb.appendLine(buildCsvRow(config, index + 1, metrics))
        }

        file.writeText(sb.toString())
        Log.i(TAG, "CSV: ${file.absolutePath}")
    }

    private fun buildCsvHeader(): String {
        val columns = mutableListOf(
            "scenarioName",
            "iteration",
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
            "avgDrawMs",
            "p50DrawMs",
            "p95DrawMs",
            "p99DrawMs",
            "avgFrameMs",
            "p50FrameMs",
            "p95FrameMs",
            "p99FrameMs",
            "avgHitTestMs",
            "cacheHitRate",
            "warmupFrames",
            "measurementFrames",
            "allocatedMB",
            "gcInvocations"
        ))
        return columns.joinToString(",")
    }

    private fun buildCsvRow(config: BenchmarkConfig, iteration: Int, metrics: FrameMetrics): String {
        val values = mutableListOf<String>()

        values.add(config.name)
        values.add(iteration.toString())
        values.add(config.scenario.sceneSize.toString())
        values.add(config.scenario.mutationRate.toString())
        values.add(config.scenario.interactionPattern.name)

        // Flags
        config.flags.toValueList().forEach { values.add(it.toString()) }

        // Metrics
        values.add("%.4f".format(metrics.prepareTimeMs.mean))
        values.add("%.4f".format(metrics.prepareTimeMs.p50))
        values.add("%.4f".format(metrics.prepareTimeMs.p95))
        values.add("%.4f".format(metrics.prepareTimeMs.p99))
        values.add("%.4f".format(metrics.drawTimeMs.mean))
        values.add("%.4f".format(metrics.drawTimeMs.p50))
        values.add("%.4f".format(metrics.drawTimeMs.p95))
        values.add("%.4f".format(metrics.drawTimeMs.p99))
        values.add("%.4f".format(metrics.frameTimeMs.mean))
        values.add("%.4f".format(metrics.frameTimeMs.p50))
        values.add("%.4f".format(metrics.frameTimeMs.p95))
        values.add("%.4f".format(metrics.frameTimeMs.p99))
        values.add("%.4f".format(metrics.hitTestTimeMs.mean))
        values.add("%.4f".format(metrics.cacheHitRate))
        values.add(metrics.warmupFrames.toString())
        values.add(metrics.frameCount.toString())
        values.add("%.2f".format(metrics.allocatedMB))
        values.add(metrics.gcInvocations.toString())

        return values.joinToString(",")
    }

    private fun exportJson(
        outputDir: File,
        baseName: String,
        config: BenchmarkConfig,
        iterations: List<FrameMetrics>,
        collector: MetricsCollector
    ) {
        val file = File(outputDir, "$baseName.json")

        val json = JSONObject().apply {
            put("name", config.name)
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
            })
            put("config", JSONObject().apply {
                put("iterations", config.iterations)
                put("measurementFrames", config.measurementFrames)
                put("warmupMinFrames", config.warmupMinFrames)
                put("warmupMaxFrames", config.warmupMaxFrames)
            })

            // Per-iteration summaries
            val iterArray = JSONArray()
            iterations.forEach { metrics ->
                iterArray.put(JSONObject().apply {
                    put("prepare", metricsToJson(metrics.prepareTimeMs))
                    put("draw", metricsToJson(metrics.drawTimeMs))
                    put("frame", metricsToJson(metrics.frameTimeMs))
                    put("hitTest", metricsToJson(metrics.hitTestTimeMs))
                    put("cacheHitRate", metrics.cacheHitRate)
                    put("cacheHits", metrics.cacheHits)
                    put("cacheMisses", metrics.cacheMisses)
                    put("warmupFrames", metrics.warmupFrames)
                    put("measurementFrames", metrics.frameCount)
                    put("allocatedMB", metrics.allocatedMB)
                    put("gcInvocations", metrics.gcInvocations)
                })
            }
            put("iterations", iterArray)

            // Raw per-frame timings from last iteration
            val raw = collector.rawTimings()
            put("rawTimings", JSONObject().apply {
                put("prepareTimes", longArrayToJsonArray(raw.prepareTimes))
                put("drawTimes", longArrayToJsonArray(raw.drawTimes))
                put("frameTimes", longArrayToJsonArray(raw.frameTimes))
                put("hitTestTimes", longArrayToJsonArray(raw.hitTestTimes))
            })
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
}
