#!/bin/bash
# Run all Runtime API Maestro tests
# Usage: bash .maestro/run-all.sh

set -e

PACKAGE="io.fabianterhorst.isometric.sample"
ACTIVITY="$PACKAGE/.RuntimeApiActivity"
MAESTRO_DIR="$(dirname "$0")"
SCREENSHOTS="$MAESTRO_DIR/screenshots"

export MAESTRO_CLI_NO_ANALYTICS=1
export MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

# Ensure screenshots directory exists
mkdir -p "$SCREENSHOTS"

echo "=== Launching RuntimeApiActivity ==="
adb shell am start -n "$ACTIVITY"
sleep 2

echo ""
echo "=== Test 1: Simple shapes render ==="
maestro test "$MAESTRO_DIR/runtime-api-simple.yaml" && echo "PASSED" || echo "FAILED"

echo ""
echo "=== Test 2: Animation updates ==="
adb shell am start -n "$ACTIVITY"
sleep 1
maestro test "$MAESTRO_DIR/runtime-api-animation.yaml" && echo "PASSED" || echo "FAILED"

echo ""
echo "=== Test 3: Hierarchy rotation ==="
adb shell am start -n "$ACTIVITY"
sleep 1
maestro test "$MAESTRO_DIR/runtime-api-hierarchy.yaml" && echo "PASSED" || echo "FAILED"

echo ""
echo "=== Test 4: Conditional toggles ==="
adb shell am start -n "$ACTIVITY"
sleep 1
maestro test "$MAESTRO_DIR/runtime-api-conditional.yaml" && echo "PASSED" || echo "FAILED"

echo ""
echo "=== Test 5: Performance grid ==="
adb shell am start -n "$ACTIVITY"
sleep 1
maestro test "$MAESTRO_DIR/runtime-api-performance.yaml" && echo "PASSED" || echo "FAILED"

echo ""
echo "=== All tests complete ==="
echo "Screenshots saved to: $SCREENSHOTS/"
ls -la "$SCREENSHOTS/"
