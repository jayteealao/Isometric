#!/bin/bash
# Run Jetpack Macrobenchmarks on connected device

set -e

echo "============================================"
echo "  Isometric Macrobenchmarks"
echo "============================================"
echo ""

echo "Building target app and macrobenchmark module..."
./gradlew :benchmarkapp:installBenchmark

echo ""
echo "Running macrobenchmarks (this will take several minutes)..."
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest

echo ""
echo "Pulling results from device..."
mkdir -p ./benchmark-results/macro
adb pull /sdcard/Download/androidx.benchmark.results ./benchmark-results/macro/ 2>/dev/null || echo "No results found on device (this is normal if benchmarks haven't completed)"

echo ""
echo "============================================"
echo "  Macrobenchmarks complete!"
echo "  Results in: ./benchmark-results/macro/"
echo "============================================"
