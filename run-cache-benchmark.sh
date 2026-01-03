#!/bin/bash

# Performance Verification Script for PreparedScene Cache Implementation
# This script runs benchmarks comparing baseline (no cache) vs cache-enabled performance

set -e

echo "============================================"
echo "PreparedScene Cache Performance Verification"
echo "============================================"
echo ""

# Configuration
PACKAGE_NAME="io.fabianterhorst.isometric.benchmark"
RESULTS_DIR="./benchmark-results"
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")

# Create results directory
mkdir -p "$RESULTS_DIR"

echo "Step 1: Building benchmark APK..."
echo "-----------------------------------"
./gradlew :isometric-benchmark:assembleRelease

echo ""
echo "Step 2: Installing APK on device..."
echo "------------------------------------"
adb install -r isometric-benchmark/build/outputs/apk/release/isometric-benchmark-release.apk

echo ""
echo "Step 3: Running STATIC scenario (should show cache benefit)..."
echo "----------------------------------------------------------------"
echo "This scenario rebuilds the same scene repeatedly."
echo "Cache should provide significant performance improvement."
echo ""

# Clear logcat
adb logcat -c

# Launch activity with STATIC scenario
adb shell am start -n "$PACKAGE_NAME/.BenchmarkActivity" \
  --es "SCENARIO" "STATIC" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"

# Wait for benchmark to complete (assume ~15 seconds)
echo "Waiting for benchmark to complete..."
sleep 20

# Pull results
echo "Pulling STATIC scenario results..."
adb logcat -d | grep "BenchmarkOrchestrator\|PreparedScene" > "$RESULTS_DIR/static-${TIMESTAMP}.log"

echo ""
echo "Step 4: Running MUTATION scenario (should show no regression)..."
echo "-----------------------------------------------------------------"
echo "This scenario changes the scene every frame."
echo "Cache should not hurt performance (always miss)."
echo ""

# Clear logcat
adb logcat -c

# Launch activity with MUTATION scenario
adb shell am start -n "$PACKAGE_NAME/.BenchmarkActivity" \
  --es "SCENARIO" "MUTATION" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"

# Wait for benchmark to complete
echo "Waiting for benchmark to complete..."
sleep 20

# Pull results
echo "Pulling MUTATION scenario results..."
adb logcat -d | grep "BenchmarkOrchestrator\|PreparedScene" > "$RESULTS_DIR/mutation-${TIMESTAMP}.log"

echo ""
echo "============================================"
echo "Benchmark Execution Complete!"
echo "============================================"
echo ""
echo "Results saved to:"
echo "  - $RESULTS_DIR/static-${TIMESTAMP}.log"
echo "  - $RESULTS_DIR/mutation-${TIMESTAMP}.log"
echo ""
echo "Next steps:"
echo "  1. Review the log files for frame time statistics"
echo "  2. Look for 'Cache hit' vs 'Cache miss' messages"
echo "  3. Compare average frame times between scenarios"
echo "  4. Document results in cache-performance-results.md"
echo ""
echo "Expected outcomes:"
echo "  - STATIC: Cache hit rate ~99%, improved frame times"
echo "  - MUTATION: Cache hit rate ~0%, no regression"
echo ""
