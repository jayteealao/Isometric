#!/usr/bin/env bash
#
# Benchmark Runner — Iterates the 24-scenario matrix with self-tests.
#
# Usage:
#   ./isometric-benchmark/benchmark-runner.sh [--skip-selftests] [--sizes "10 50"]
#
# Prerequisites:
#   - Android device connected via ADB
#   - Benchmark APK installed:
#     ./gradlew :isometric-benchmark:assembleDebug
#     adb install isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk

set -euo pipefail

PACKAGE="io.fabianterhorst.isometric.benchmark"
ACTIVITY="${PACKAGE}/.BenchmarkActivity"
RESULTS_DIR="benchmark-results"
DEVICE_RESULTS="/sdcard/Android/data/${PACKAGE}/files/benchmark-results"

# Defaults
SIZES=(10 50 200 1000)
MUTATION_RATES=(0.0 0.1)
INTERACTION_PATTERNS=(NONE OCCASIONAL CONTINUOUS)
SKIP_SELFTESTS=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-selftests) SKIP_SELFTESTS=true; shift ;;
        --sizes) IFS=' ' read -ra SIZES <<< "$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

echo "=== Isometric Benchmark Runner ==="
echo "Sizes: ${SIZES[*]}"
echo "Mutation rates: ${MUTATION_RATES[*]}"
echo "Interaction patterns: ${INTERACTION_PATTERNS[*]}"
echo ""

# Create local results directory
mkdir -p "$RESULTS_DIR"

run_scenario() {
    local config_json="$1"
    local name="$2"

    echo "[$(date +%H:%M:%S)] Running: $name"

    adb shell am start -W -n "$ACTIVITY" \
        --es config "$config_json" \
        > /dev/null 2>&1

    # Small delay to let file writes complete
    sleep 1

    echo "[$(date +%H:%M:%S)] Completed: $name"
}

# --- Self-tests ---
if [ "$SKIP_SELFTESTS" = false ]; then
    echo "--- Running self-tests ---"

    run_scenario \
        '{"sceneSize":10,"mutationRate":0.0,"interactionPattern":"NONE","name":"selftest_cache","iterations":1,"measurementFrames":100}' \
        "selftest_cache"

    run_scenario \
        '{"sceneSize":10,"mutationRate":0.0,"interactionPattern":"NONE","name":"selftest_sanity","iterations":1,"measurementFrames":100,"flags":{"enablePathCaching":false,"enableSpatialIndex":false,"enablePreparedSceneCache":false,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}' \
        "selftest_sanity"

    echo "--- Self-tests complete ---"
    echo ""
fi

# --- Main benchmark matrix ---
total=$(( ${#SIZES[@]} * ${#MUTATION_RATES[@]} * ${#INTERACTION_PATTERNS[@]} ))
current=0

echo "--- Running $total scenarios ---"

for size in "${SIZES[@]}"; do
    for rate in "${MUTATION_RATES[@]}"; do
        for pattern in "${INTERACTION_PATTERNS[@]}"; do
            current=$((current + 1))

            # Build scenario name
            if [ "$rate" = "0.0" ]; then
                mut_label="static"
            else
                mut_pct=$(echo "$rate * 100" | bc | cut -d. -f1)
                mut_label="mut${mut_pct}"
            fi
            name="N${size}_${mut_label}_$(echo "$pattern" | tr '[:upper:]' '[:lower:]')_allOn"

            echo ""
            echo "[$current/$total] $name"

            config_json="{\"sceneSize\":${size},\"mutationRate\":${rate},\"interactionPattern\":\"${pattern}\",\"name\":\"${name}\",\"iterations\":3,\"measurementFrames\":500}"

            run_scenario "$config_json" "$name"
        done
    done
done

echo ""
echo "--- Pulling results from device ---"
adb pull "$DEVICE_RESULTS/" "$RESULTS_DIR/" 2>/dev/null || echo "Warning: Could not pull results (check device path)"

echo ""
echo "=== Benchmark run complete ==="
echo "Results: $(pwd)/$RESULTS_DIR/"
echo "Scenarios run: $total"

# List result files
if [ -d "$RESULTS_DIR" ]; then
    echo ""
    echo "Files:"
    ls -la "$RESULTS_DIR/"*.csv 2>/dev/null || echo "  (no CSV files found)"
fi
