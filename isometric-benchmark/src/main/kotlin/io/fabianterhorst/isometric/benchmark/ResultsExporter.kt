package io.fabianterhorst.isometric.benchmark

import android.content.Context
import android.util.Log
import java.io.File

object ResultsExporter {
    fun export(context: Context, results: BenchmarkResults) {
        try {
            val outputDir = context.getExternalFilesDir(null)
            val outputFile = File(outputDir, results.config.outputFile)

            // Append to file (create with header if doesn't exist)
            if (!outputFile.exists()) {
                outputFile.writeText(BenchmarkResults.csvHeader() + "\n")
            }

            outputFile.appendText(results.toCsv() + "\n")

            Log.i(TAG, "Results exported to: ${outputFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export results", e)
        }
    }

    private const val TAG = "ResultsExporter"
}
