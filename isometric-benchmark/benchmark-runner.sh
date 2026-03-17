#!/usr/bin/env bash
#
# Benchmark Runner — Iterates the 24-scenario matrix with self-tests.
#
# Usage:
#   ./isometric-benchmark/benchmark-runner.sh [--skip-selftests] [--sizes "10 50"] [--label NAME]
#     [--enable-spatial-index] [--enable-prepared-scene-cache]
#     [--enable-path-caching] [--enable-native-canvas] [--enable-broad-phase-sort]
#
# Prerequisites:
#   - Android device connected via ADB
#   - Benchmark APK installed:
#     ./gradlew :isometric-benchmark:assembleDebug
#     adb install isometric-benchmark/build/outputs/apk/debug/isometric-benchmark-debug.apk
#   - Windows host note: prefer Git Bash for this script. WSL bash failed to execute
#     adb.exe correctly on this machine, which caused launch failures despite the app
#     and device being healthy.

set -euo pipefail

PACKAGE="io.fabianterhorst.isometric.benchmark"
ACTIVITY="${PACKAGE}/.BenchmarkActivity"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
DEVICE_RESULTS="/sdcard/Android/data/${PACKAGE}/files/benchmark-results"
ENABLE_SPATIAL_INDEX=false
ENABLE_PREPARED_SCENE_CACHE=false
ENABLE_PATH_CACHING=false
ENABLE_NATIVE_CANVAS=false
ENABLE_BROAD_PHASE_SORT=false
LABEL=""

# Plan-specified defaults: sizes 10,50,100,200; mutation rates 0.10,0.50
SIZES=(10 50 100 200)
MUTATION_RATES=(0.10 0.50)
INTERACTION_PATTERNS=(NONE OCCASIONAL CONTINUOUS)
SKIP_SELFTESTS=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --skip-selftests) SKIP_SELFTESTS=true; shift ;;
        --sizes) IFS=' ' read -ra SIZES <<< "$2"; shift 2 ;;
        --enable-spatial-index) ENABLE_SPATIAL_INDEX=true; shift ;;
        --enable-prepared-scene-cache) ENABLE_PREPARED_SCENE_CACHE=true; shift ;;
        --enable-path-caching) ENABLE_PATH_CACHING=true; shift ;;
        --enable-native-canvas) ENABLE_NATIVE_CANVAS=true; shift ;;
        --enable-broad-phase-sort) ENABLE_BROAD_PHASE_SORT=true; shift ;;
        --label) LABEL="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

RUN_ID="${TIMESTAMP}"
if [ -n "$LABEL" ]; then
    RUN_ID="${TIMESTAMP}-${LABEL}"
fi
RESULTS_DIR="benchmark-results/${RUN_ID}"

echo "=== Isometric Benchmark Runner ==="
echo "Timestamp: ${RUN_ID}"
echo "Sizes: ${SIZES[*]}"
echo "Mutation rates: ${MUTATION_RATES[*]}"
echo "Interaction patterns: ${INTERACTION_PATTERNS[*]}"
echo "enableSpatialIndex: ${ENABLE_SPATIAL_INDEX}"
echo "enablePreparedSceneCache: ${ENABLE_PREPARED_SCENE_CACHE}"
echo "enablePathCaching: ${ENABLE_PATH_CACHING}"
echo "enableNativeCanvas: ${ENABLE_NATIVE_CANVAS}"
echo "enableBroadPhaseSort: ${ENABLE_BROAD_PHASE_SORT}"
echo ""

# Create local results directory
mkdir -p "$RESULTS_DIR"

RESULT_POLL_INTERVAL=2   # seconds between polls
# Allow enough headroom for the heaviest baseline workload on real devices.
# s200_m50_continuous exceeded 5 minutes in practice while still completing successfully.
RESULT_POLL_TIMEOUT=600  # 10 minutes max wait per scenario

resolve_adb() {
    if command -v adb >/dev/null 2>&1; then
        echo "adb"
        return 0
    fi
    if command -v adb.exe >/dev/null 2>&1; then
        echo "adb.exe"
        return 0
    fi
    return 1
}

ADB_BIN="$(resolve_adb)" || {
    echo "ERROR: Could not find adb or adb.exe on PATH."
    exit 1
}

adb_cmd() {
    if [ -n "${MSYSTEM:-}" ]; then
        MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB_BIN" "$@"
    else
        "$ADB_BIN" "$@"
    fi
}

run_scenario() {
    local config_json="$1"
    local config_base64
    local name="$2"
    local result_marker="${DEVICE_RESULTS}/${RUN_ID}/${name}.result"

    config_base64=$(printf '%s' "$config_json" | base64 | tr -d '\r\n' | tr '+/' '-_' | tr -d '=')

    echo "[$(date +%H:%M:%S)] Running: $name"

    # Launch the activity. Use URL-safe base64 for config payload transport so the
    # intent extra survives Windows host quoting without needing a remote shell string.
    if ! adb_cmd shell am start -W -n "$ACTIVITY" \
        --es configBase64 "$config_base64" \
        --es runTimestamp "$RUN_ID" > /dev/null 2>&1; then
        echo "[$(date +%H:%M:%S)] FAILED: $name (launch command failed)"
        return 1
    fi

    # Poll for the file-based result marker written by BenchmarkActivity on completion.
    # This is the reliable completion contract — it works regardless of whether
    # am start -W blocked until finish() or returned early.
    local elapsed=0
    local result_content=""

    while [ "$elapsed" -lt "$RESULT_POLL_TIMEOUT" ]; do
        result_content=$(adb_cmd shell cat "$result_marker" 2>/dev/null | tr -d '\r') || result_content=""

        if [ "$result_content" = "PASS" ]; then
            echo "[$(date +%H:%M:%S)] Completed: $name"
            return 0
        elif [ "$result_content" = "FAIL" ]; then
            echo "[$(date +%H:%M:%S)] FAILED: $name (validation failed)"
            return 1
        fi

        sleep "$RESULT_POLL_INTERVAL"
        elapsed=$((elapsed + RESULT_POLL_INTERVAL))
    done

    echo "[$(date +%H:%M:%S)] FAILED: $name (timed out after ${RESULT_POLL_TIMEOUT}s — no result marker)"
    return 1
}

# --- Self-tests ---
if [ "$SKIP_SELFTESTS" = false ]; then
    echo "--- Running self-tests ---"
    echo "Self-tests must pass before the benchmark matrix proceeds."
    echo ""

    SELFTEST_FAILED=false

    # S1: selftest_cache — N=10, cache ON (ALL_ON), mutationRate=0.0, 100 frames, 1 iteration
    if ! run_scenario \
        '{"sceneSize":10,"mutationRate":0.0,"interactionPattern":"NONE","name":"selftest_cache","iterations":1,"measurementFrames":100,"flags":{"enablePathCaching":true,"enableSpatialIndex":true,"enablePreparedSceneCache":true,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}' \
        "selftest_cache"; then
        echo "ABORT: selftest_cache FAILED — cache hit rate check did not pass."
        SELFTEST_FAILED=true
    fi

    # S2: selftest_sanity — N=10, all flags OFF, mutationRate=0.10, 100 frames, 1 iteration
    if ! run_scenario \
        '{"sceneSize":10,"mutationRate":0.10,"interactionPattern":"NONE","name":"selftest_sanity","iterations":1,"measurementFrames":100,"flags":{"enablePathCaching":false,"enableSpatialIndex":false,"enablePreparedSceneCache":false,"enableNativeCanvas":false,"enableBroadPhaseSort":false}}' \
        "selftest_sanity"; then
        echo "ABORT: selftest_sanity FAILED — baseline frame time check did not pass."
        SELFTEST_FAILED=true
    fi

    if [ "$SELFTEST_FAILED" = true ]; then
        echo ""
        echo "=== ABORT: Self-test failed. Fix harness before benchmarking. ==="
        echo "Pull self-test results for diagnostics:"
        echo "  ${ADB_BIN} pull ${DEVICE_RESULTS}/ ${RESULTS_DIR}/"
        adb_cmd pull "${DEVICE_RESULTS}/" "${RESULTS_DIR}/" 2>/dev/null || true
        exit 1
    fi

    echo ""
    echo "--- Self-tests passed. Starting benchmark matrix ---"
    echo ""
fi

# --- Main benchmark matrix ---
total=$(( ${#SIZES[@]} * ${#MUTATION_RATES[@]} * ${#INTERACTION_PATTERNS[@]} ))
current=0
failed=0

echo "--- Running $total scenarios ---"

for size in "${SIZES[@]}"; do
    for rate in "${MUTATION_RATES[@]}"; do
        for pattern in "${INTERACTION_PATTERNS[@]}"; do
            current=$((current + 1))

            # Build scenario name matching plan convention: s{size}_m{rate}_${pattern}
            mut_pct=$((10#${rate/./}))
            name="s${size}_m${mut_pct}_$(echo "$pattern" | tr '[:upper:]' '[:lower:]')"

            echo ""
            echo "[$current/$total] $name"

            config_json="{\"sceneSize\":${size},\"mutationRate\":${rate},\"interactionPattern\":\"${pattern}\",\"name\":\"${name}\",\"iterations\":3,\"measurementFrames\":500,\"flags\":{\"enablePathCaching\":${ENABLE_PATH_CACHING},\"enableSpatialIndex\":${ENABLE_SPATIAL_INDEX},\"enablePreparedSceneCache\":${ENABLE_PREPARED_SCENE_CACHE},\"enableNativeCanvas\":${ENABLE_NATIVE_CANVAS},\"enableBroadPhaseSort\":${ENABLE_BROAD_PHASE_SORT}}}"

            if ! run_scenario "$config_json" "$name"; then
                failed=$((failed + 1))
                echo "WARNING: $name failed validation (continuing with remaining scenarios)"
            fi
        done
    done
done

echo ""
echo "--- Pulling results from device ---"
adb_cmd pull "${DEVICE_RESULTS}/" "${RESULTS_DIR}/" 2>/dev/null || echo "Warning: Could not pull results (check device path)"

echo ""
echo "=== Benchmark run complete ==="
echo "Results: $(pwd)/$RESULTS_DIR/"
echo "Scenarios run: $total"
echo "Scenarios failed: $failed"

# List result files
if [ -d "$RESULTS_DIR" ]; then
    echo ""
    echo "Files:"
    find "$RESULTS_DIR" -type f \( -name "*.csv" -o -name "*.json" -o -name "*.log" -o -name "*.result" \) 2>/dev/null | sort
fi

if [ "$failed" -gt 0 ]; then
    echo ""
    echo "WARNING: $failed scenario(s) failed validation. Check validation logs."
    exit 1
fi
