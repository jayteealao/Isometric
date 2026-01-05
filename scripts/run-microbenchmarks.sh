#!/bin/bash
# Run Jetpack Microbenchmarks on connected device

set -e

echo "============================================"
echo "  Isometric Microbenchmarks"
echo "============================================"
echo ""

echo "Building microbenchmark module..."
./gradlew :microbenchmark:assembleBenchmark

echo ""
echo "Installing and running benchmarks on device..."
./gradlew :microbenchmark:connectedBenchmarkAndroidTest

echo ""
echo "Pulling results from device..."
mkdir -p ./benchmark-results/micro
adb pull /sdcard/Download/androidx.benchmark.results ./benchmark-results/micro/ 2>/dev/null || echo "No results found on device (this is normal if benchmarks haven't completed)"

echo ""
echo "============================================"
echo "  Microbenchmarks complete!"
echo "  Results in: ./benchmark-results/micro/"
echo "============================================"
