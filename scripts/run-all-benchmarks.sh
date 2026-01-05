#!/bin/bash
# Run all benchmark suites and generate unified report

set -e

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
OUTPUT_DIR="benchmark-results-$TIMESTAMP"

echo "============================================"
echo "  Isometric Benchmark Suite"
echo "  Output: $OUTPUT_DIR"
echo "============================================"
echo ""

echo "Creating output directory..."
mkdir -p "$OUTPUT_DIR"/{micro,macro,custom}

echo ""
echo "=== Running Microbenchmarks ==="
./gradlew :microbenchmark:connectedBenchmarkAndroidTest
adb pull /sdcard/Download/androidx.benchmark.results "$OUTPUT_DIR/micro/" 2>/dev/null || true

echo ""
echo "=== Running Macrobenchmarks ==="
./gradlew :benchmarkapp:installBenchmark
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
adb pull /sdcard/Download/androidx.benchmark.results "$OUTPUT_DIR/macro/" 2>/dev/null || true

echo ""
echo "=== Running Custom Benchmarks ==="
echo "Note: Custom benchmarks run a quick smoke test (4 scenarios)"
./gradlew :isometric-benchmark:installDebug

# Clear previous results
adb shell rm -f /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark_results.csv 2>/dev/null || true

# Run benchmark with default config (smoke test)
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity
echo "Waiting 25 seconds for benchmark to complete..."
sleep 25

# Pull results from correct location (use cat method for Android 11+ compatibility)
DEVICE_PATH="/storage/emulated/0/Android/data/io.fabianterhorst.isometric.benchmark/files/benchmark_results.csv"
if ! adb pull "$DEVICE_PATH" "$OUTPUT_DIR/custom/" 2>/dev/null; then
    echo "Standard pull failed, using cat method..."
    adb shell "cat '$DEVICE_PATH'" > "$OUTPUT_DIR/custom/benchmark_results.csv" 2>/dev/null || {
        echo "Warning: Could not retrieve custom benchmark results"
        echo "Extracting from logcat as fallback..."
        adb logcat -d | grep "BenchmarkActivity.*Results:" > "$OUTPUT_DIR/custom/benchmark_logcat.txt" || true
    }
fi

echo ""
echo "============================================"
echo "  All benchmarks complete!"
echo "  Results in: $OUTPUT_DIR"
echo ""
echo "  Next steps:"
echo "  1. Review JSON results in $OUTPUT_DIR/micro/ and $OUTPUT_DIR/macro/"
echo "  2. Review custom CSV in $OUTPUT_DIR/custom/benchmark_results.csv"
echo "  3. For comprehensive custom benchmarks, run: ./run-baseline-benchmarks.sh"
echo "  4. Generate unified report (TODO: report generation task)"
echo "============================================"
echo ""
echo "Note: Microbenchmarks may fail on emulators. This is expected."
echo "      Use a physical device for accurate microbenchmark results."
