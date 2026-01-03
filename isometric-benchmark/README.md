# Isometric Benchmark Harness

Production-grade benchmark suite for measuring Isometric rendering performance.

## Quick Start

### Run Smoke Test (4 scenarios, ~2 minutes)

```bash
# Install benchmark app
./gradlew :isometric-benchmark:installDebug

# Run smoke test
adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner --es scenario smoke

# Wait 2 minutes, then retrieve results
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/baseline_results.csv"
```

### Run Full Baseline (32 scenarios, ~15 minutes)

```bash
# Run all baseline scenarios
adb shell am start -n io.fabianterhorst.isometric.benchmark/.ManualBenchmarkRunner --es scenario all

# Wait 15-20 minutes, then retrieve results
adb shell "run-as io.fabianterhorst.isometric.benchmark cat files/baseline_results.csv" > baseline_results.csv
```

## Scenarios

**Smoke Test (4 scenarios):**
- Static scene, 10 objects, no interaction
- Static scene, 100 objects, no interaction
- Full mutation, 100 objects, no interaction
- Static scene, 100 objects, continuous interaction

**Full Baseline (32 scenarios):**
- 4 scene sizes: 10, 100, 500, 1000
- 2 scene types: STATIC, FULL_MUTATION
- 2 interaction patterns: NONE, CONTINUOUS
- All with BASELINE optimization flags (all disabled)

## Results Format

CSV columns:
- `name`: Scenario identifier
- `sceneSize`: Number of objects (10, 100, 500, 1000)
- `scenario`: STATIC or FULL_MUTATION
- `interaction`: NONE or CONTINUOUS
- `avgFrameMs`: Average frame time in milliseconds
- `p50FrameMs`: Median frame time
- `p95FrameMs`: 95th percentile frame time
- `p99FrameMs`: 99th percentile frame time
- `minFrameMs`: Minimum frame time
- `maxFrameMs`: Maximum frame time

## Analysis

**Key Metrics:**
- **Avg frame time < 16ms** → 60fps capable
- **P95 frame time < 20ms** → Acceptable jank levels
- **P99 frame time** → Worst-case performance

**Expected Baseline:**
- N=10: ~2-5ms avg
- N=100: ~8-15ms avg
- N=500: ~40-60ms avg (won't hit 60fps)
- N=1000: ~150-200ms avg (very slow)

## Next Steps

After baseline measurements:
1. Analyze results against predictions in performance investigation plan
2. Identify worst bottlenecks
3. Implement Phase 1 optimization (PreparedScene cache)
4. Re-run benchmarks to measure improvement
