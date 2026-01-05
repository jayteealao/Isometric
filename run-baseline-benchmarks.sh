#!/bin/bash
# Comprehensive baseline benchmark runner
# Runs all baseline scenarios and exports results

set -e

PACKAGE_NAME="io.fabianterhorst.isometric.benchmark"
TIMESTAMP=$(date +"%Y%m%d-%H%M%S")
RESULTS_DIR="./benchmark-results"
RESULTS_FILE="benchmark_results.csv"
DEVICE_RESULTS_PATH="/storage/emulated/0/Android/data/${PACKAGE_NAME}/files/${RESULTS_FILE}"

# Benchmark wait time (seconds) - adjust based on scenario complexity
# Small scenes (10): ~10s, Medium (100): ~20s, Large (500): ~40s, XLarge (1000): ~60s
declare -A WAIT_TIMES=(
    [10]=12
    [100]=25
    [500]=45
    [1000]=70
)

echo "============================================"
echo "  Isometric Baseline Benchmark Suite"
echo "  Timestamp: $TIMESTAMP"
echo "============================================"
echo ""

# Create results directory
mkdir -p "$RESULTS_DIR"

echo "Step 1: Building and installing benchmark APK..."
echo "---------------------------------------------------"
./gradlew :isometric-benchmark:assembleDebug
adb install -r isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk

echo ""
echo "Step 2: Clearing previous results..."
echo "--------------------------------------"
adb shell rm -f "$DEVICE_RESULTS_PATH" 2>/dev/null || true

echo ""
echo "Step 3: Running baseline scenarios..."
echo "---------------------------------------"
echo ""

# Define baseline scenarios
# Format: "name|sceneSize|scenario|interaction|enablePreparedCache|enableDrawCache"
scenarios=(
    # SMOKE TEST SCENARIOS (quick validation)
    # Comment these out when running full baseline suite
    # "smoke_static_10_none|10|STATIC|NONE|false|false"
    # "smoke_static_100_none|100|STATIC|NONE|false|false"
    # "smoke_mutation_100_none|100|FULL_MUTATION|NONE|false|false"
    # "smoke_static_100_continuous|100|STATIC|CONTINUOUS|false|false"

    # FULL BASELINE SCENARIOS (comprehensive)
    # 16 scenarios, ~30 minutes
    "baseline_static_10_none|10|STATIC|NONE|false|false"
    "baseline_static_10_continuous|10|STATIC|CONTINUOUS|false|false"
    "baseline_static_100_none|100|STATIC|NONE|false|false"
    "baseline_static_100_continuous|100|STATIC|CONTINUOUS|false|false"
    "baseline_static_500_none|500|STATIC|NONE|false|false"
    "baseline_static_500_continuous|500|STATIC|CONTINUOUS|false|false"
    "baseline_static_1000_none|1000|STATIC|NONE|false|false"
    "baseline_static_1000_continuous|1000|STATIC|CONTINUOUS|false|false"
    "baseline_full_mutation_10_none|10|FULL_MUTATION|NONE|false|false"
    "baseline_full_mutation_10_continuous|10|FULL_MUTATION|CONTINUOUS|false|false"
    "baseline_full_mutation_100_none|100|FULL_MUTATION|NONE|false|false"
    "baseline_full_mutation_100_continuous|100|FULL_MUTATION|CONTINUOUS|false|false"
    "baseline_full_mutation_500_none|500|FULL_MUTATION|NONE|false|false"
    "baseline_full_mutation_500_continuous|500|FULL_MUTATION|CONTINUOUS|false|false"
    "baseline_full_mutation_1000_none|1000|FULL_MUTATION|NONE|false|false"
    "baseline_full_mutation_1000_continuous|1000|FULL_MUTATION|CONTINUOUS|false|false"
)

total=${#scenarios[@]}
current=0

for scenario_def in "${scenarios[@]}"; do
    current=$((current + 1))

    # Parse scenario definition
    IFS='|' read -r name size scenario interaction prepCache drawCache <<< "$scenario_def"

    echo "[$current/$total] Running: $name"
    echo "  Size: $size, Scenario: $scenario, Interaction: $interaction"

    # Launch benchmark with configuration
    adb shell am start -W -n "$PACKAGE_NAME/.BenchmarkActivity" \
        --es "scenario" "$scenario" \
        --es "interaction" "$interaction" \
        --ei "sceneSize" "$size" \
        --ez "enablePreparedSceneCache" "$prepCache" \
        --ez "enableDrawWithCache" "$drawCache" \
        --ei "runs" "3" \
        > /dev/null 2>&1

    # Wait for benchmark to complete based on scene size
    wait_time=${WAIT_TIMES[$size]}
    echo "  Waiting ${wait_time}s for completion..."
    sleep $wait_time

    echo "  ✓ Complete"
    echo ""
done

echo ""
echo "Step 4: Pulling results..."
echo "---------------------------"

# Pull results file (use cat method for Android 11+ scoped storage compatibility)
LOCAL_RESULTS="$RESULTS_DIR/${RESULTS_FILE%.csv}_${TIMESTAMP}.csv"

# Try adb pull first, fallback to cat method if it fails
if ! adb pull "$DEVICE_RESULTS_PATH" "$LOCAL_RESULTS" 2>/dev/null; then
    echo "  Standard pull failed, using cat method..."
    adb shell "cat '$DEVICE_RESULTS_PATH'" > "$LOCAL_RESULTS" 2>/dev/null
fi

if [ -f "$LOCAL_RESULTS" ] && [ -s "$LOCAL_RESULTS" ]; then
    echo "✓ Results saved to: $LOCAL_RESULTS"
    echo ""

    # Show summary
    echo "Results summary:"
    echo "----------------"
    row_count=$(tail -n +2 "$LOCAL_RESULTS" | wc -l)
    echo "Total benchmarks: $row_count"
    echo ""

    # Show sample results (first 3 data rows)
    echo "Sample results (first 3):"
    head -n 4 "$LOCAL_RESULTS" | column -t -s ','
else
    echo "✗ Failed to pull results from device"
    echo "  Tried path: $DEVICE_RESULTS_PATH"
    echo ""
    echo "Checking logcat for results..."
    adb logcat -d | grep -E "BenchmarkActivity.*Results:" | tail -5
    exit 1
fi

echo ""
echo "============================================"
echo "  Benchmark Suite Complete!"
echo "============================================"
echo ""
echo "Results file: $LOCAL_RESULTS"
echo ""
echo "Next steps:"
echo "  1. Analyze results: open $LOCAL_RESULTS in a spreadsheet"
echo "  2. Generate report: python scripts/analyze_benchmarks.py $LOCAL_RESULTS"
echo "  3. Compare with previous runs in $RESULTS_DIR/"
echo ""
