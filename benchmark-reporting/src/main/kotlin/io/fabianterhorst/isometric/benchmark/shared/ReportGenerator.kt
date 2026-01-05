package io.fabianterhorst.isometric.benchmark.shared

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Generates benchmark reports in various formats (JSON, CSV, Markdown).
 */
object ReportGenerator {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Generates a JSON report file from benchmark results.
     *
     * @param report The benchmark report to serialize
     * @param outputFile The file to write the JSON output to
     */
    fun generateJson(report: BenchmarkReport, outputFile: File) {
        val jsonString = json.encodeToString(report)
        outputFile.writeText(jsonString)
    }

    /**
     * Generates a CSV report file from benchmark results.
     * Includes all benchmark types in a single CSV with unified schema.
     *
     * @param report The benchmark report to export
     * @param outputFile The file to write the CSV output to
     */
    fun generateCsv(report: BenchmarkReport, outputFile: File) {
        outputFile.bufferedWriter().use { writer ->
            // Write header
            writer.write("type,name,objects,mutation,p95_ms,cache_hit_rate\n")

            // Write microbenchmark results
            report.microbenchmarks.forEach { micro ->
                val p95Ms = "%.3f".format(micro.medianNanos / 1_000_000.0)
                writer.write("microbenchmark,${micro.name},${micro.objectCount},,${p95Ms},\n")
            }

            // Write macrobenchmark results
            report.macrobenchmarks.forEach { macro ->
                val p95Ms = "%.2f".format(macro.frameTiming.p95Ms)
                writer.write("macrobenchmark,${macro.name},${macro.objectCount},${macro.mutationType},${p95Ms},\n")
            }

            // Write custom benchmark results
            report.customBenchmarks.forEach { custom ->
                val p95Ms = "%.2f".format(custom.drawTiming.p95Ms)
                val cacheHitRate = "%.2f".format(custom.cacheHitRate * 100)
                writer.write("custom,${custom.name},,,${p95Ms},${cacheHitRate}\n")
            }
        }
    }

    /**
     * Generates a Markdown report file from benchmark results.
     * Creates a human-readable report with tables and sections.
     *
     * @param report The benchmark report to format
     * @param outputFile The file to write the Markdown output to
     */
    fun generateMarkdown(report: BenchmarkReport, outputFile: File) {
        val markdown = buildString {
            // Title
            appendLine("# Isometric Benchmark Report")
            appendLine()

            // Device info section
            appendLine("## Device Information")
            appendLine()
            appendLine("| Property | Value |")
            appendLine("|----------|-------|")
            appendLine("| Manufacturer | ${report.device.manufacturer} |")
            appendLine("| Model | ${report.device.model} |")
            appendLine("| Android Version | ${report.device.androidVersion} |")
            appendLine("| CPU ABI | ${report.device.cpuAbi} |")
            appendLine("| Timestamp | ${report.timestamp} |")
            appendLine()

            // Microbenchmarks section
            if (report.microbenchmarks.isNotEmpty()) {
                appendLine("## Microbenchmarks")
                appendLine()
                appendLine("| Name | Objects | Median (ms) | Min (ms) | Max (ms) |")
                appendLine("|------|---------|-------------|----------|----------|")

                report.microbenchmarks.forEach { micro ->
                    val medianMs = "%.3f".format(micro.medianNanos / 1_000_000.0)
                    val minMs = "%.3f".format(micro.minNanos / 1_000_000.0)
                    val maxMs = "%.3f".format(micro.maxNanos / 1_000_000.0)
                    appendLine("| ${micro.name} | ${micro.objectCount} | ${medianMs} | ${minMs} | ${maxMs} |")
                }
                appendLine()
            }

            // Macrobenchmarks section
            if (report.macrobenchmarks.isNotEmpty()) {
                appendLine("## Macrobenchmarks")
                appendLine()
                appendLine("| Name | Objects | P95 (ms) | P99 (ms) | Jank Count |")
                appendLine("|------|---------|----------|----------|------------|")

                report.macrobenchmarks.forEach { macro ->
                    val p95Ms = "%.2f".format(macro.frameTiming.p95Ms)
                    val p99Ms = "%.2f".format(macro.frameTiming.p99Ms)
                    val jankCount = macro.jankMetrics?.jankCount?.toString() ?: "N/A"
                    appendLine("| ${macro.name} | ${macro.objectCount} | ${p95Ms} | ${p99Ms} | ${jankCount} |")
                }
                appendLine()
            }

            // Custom benchmarks section
            if (report.customBenchmarks.isNotEmpty()) {
                appendLine("## Custom Benchmarks")
                appendLine()
                appendLine("| Name | VSync Avg (ms) | Prepare P95 (ms) | Draw P95 (ms) | Cache Hit Rate |")
                appendLine("|------|----------------|------------------|---------------|----------------|")

                report.customBenchmarks.forEach { custom ->
                    val vsyncAvg = "%.2f".format(custom.vsyncTiming.avgMs)
                    val prepareP95 = "%.2f".format(custom.prepareTiming.p95Ms)
                    val drawP95 = "%.2f".format(custom.drawTiming.p95Ms)
                    val cacheHitRate = "%.2f%%".format(custom.cacheHitRate * 100)
                    appendLine("| ${custom.name} | ${vsyncAvg} | ${prepareP95} | ${drawP95} | ${cacheHitRate} |")
                }
                appendLine()
            }
        }

        outputFile.writeText(markdown)
    }
}
