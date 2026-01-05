# Benchmark Execution Scripts

This directory contains scripts for running the comprehensive Isometric benchmarking suite on local Android devices.

## Prerequisites

Before running benchmarks:

1. **Physical Android device connected via USB** (recommended for accurate results)
   - OR Android emulator running (results will be less representative)

2. **ADB accessible in PATH**
   ```bash
   adb version  # Should show ADB version
   ```

3. **USB debugging enabled** on device

4. **Device unlocked** during benchmark execution (screen must be ON)

5. **Connected device verification:**
   ```bash
   adb devices  # Should show your device
   ```

## Running Benchmarks

### Microbenchmarks (Engine-only, isolated)

Tests isolated engine operations (prepareScene, hit testing, allocations):

```bash
./scripts/run-microbenchmarks.sh
```

**What it measures:**
- `prepareScene()` time across different object counts
- Hit testing latency (linear vs spatial index)
- Memory allocations (with/without caching)
- Individual optimization impact

**Duration:** ~5-10 minutes

### Macrobenchmarks (End-to-end frame timing)

Tests real-world frame rendering performance:

```bash
./scripts/run-macrobenchmarks.sh
```

**What it measures:**
- Frame timing (median, P95, P99)
- Jank detection and counting
- User-perceived performance
- Optimization combinations

**Duration:** ~15-20 minutes

### All Benchmarks + Report

Runs complete benchmark suite:

```bash
./scripts/run-all-benchmarks.sh
```

**Includes:**
- All microbenchmarks
- All macrobenchmarks
- Custom framework benchmarks (isometric-benchmark module)

**Duration:** ~30-40 minutes

## Output Locations

After running benchmarks, results are saved to:

- **Microbenchmarks:** `./benchmark-results/micro/`
- **Macrobenchmarks:** `./benchmark-results/macro/`
- **Custom benchmarks:** `./benchmark-results/custom/`
- **Unified runs:** `./benchmark-results-YYYYMMDD-HHMMSS/`

## Interpreting Results

### Microbenchmark Results

JSON files containing per-operation timing:

- **prepareScene tests:** 3D→2D transformation + depth sorting time
  - Baseline: ~X ms for 100 objects
  - With caching: should approach 0ms on cache hits

- **hitTest tests:** Hit testing latency
  - Linear: O(n) complexity
  - Spatial index: O(k) where k << n

- **allocation tests:** Memory allocation counts
  - Without caching: high allocations per frame
  - With caching: near-zero after warmup

**Success criteria:**
- PreparedSceneCache should reduce prepare time to ~0ms for static scenes
- SpatialIndex should reduce hit testing time significantly for large scenes
- BroadPhaseSort should reduce sorting time for dense overlapping scenes

### Macrobenchmark Results

Frame timing metrics in JSON format:

- **Frame times:**
  - Target: P95 < 16ms for 60fps
  - P99 < 20ms acceptable for most scenarios
  - Median should be well below 16ms

- **Jank metrics:**
  - Minimize jank count
  - Jank rate should be < 5% for good user experience

**Success criteria:**
- All optimizations enabled should achieve best frame times
- Static scenes should hit 60fps consistently
- Mutation scenarios should remain smooth (P95 < 16ms)

### Custom Benchmark Results

Detailed CSV with three-tier metrics:

- **Vsync timing:** Frame interval measurements
- **Prepare timing:** Engine preparation phase
- **Draw timing:** Compose rendering phase

Plus resource metrics:
- Cache hit rates
- GC invocations
- Draw call counts

## Tips for Reliable Results

### 1. Device Preparation
- Close all background apps
- Ensure device is not thermally throttled (cool down between runs)
- Keep screen ON and unlocked
- Disable battery saver mode
- Use consistent device settings across runs

### 2. Multiple Runs
For production benchmarks, run multiple times and average:

```bash
# Run 3 times and compare results
./scripts/run-all-benchmarks.sh
# Wait for device to cool
sleep 300
./scripts/run-all-benchmarks.sh
# Wait for device to cool
sleep 300
./scripts/run-all-benchmarks.sh
```

### 3. Baseline Comparison
Always compare optimized results against baseline:
- Baseline = all optimization flags OFF
- Compare each optimization individually
- Measure combined effect

### 4. Statistical Significance
- Jetpack Benchmark runs multiple iterations automatically
- Look for consistent improvements across iterations
- Small differences (<10%) may not be statistically significant

## Troubleshooting

### "No device found"
```bash
adb devices
# If empty, check USB connection and enable USB debugging
```

### "Benchmark timed out"
- Device may be too slow
- Increase timeout in benchmark code
- Use fewer objects in test scenarios

### "Results not found"
- Benchmarks may have failed
- Check logcat: `adb logcat | grep Benchmark`
- Verify benchmark APKs installed: `adb shell pm list packages | grep isometric`

### "Out of memory"
- Reduce object counts in test scenarios
- Close background apps
- Restart device

## Advanced Usage

### Run Specific Test

```bash
# Specific microbenchmark
./gradlew :microbenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.fabianterhorst.isometric.microbenchmark.IsometricEngineBenchmark#prepareScene_100objects_baseline

# Specific macrobenchmark
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.fabianterhorst.isometric.macrobenchmark.IsometricMacrobenchmark#frameTiming_100objects_static_baseline
```

### Generate Reports

After collecting results, use the reporting infrastructure:

```kotlin
// TODO: Add report generation Gradle task
// Example:
// ./gradlew :benchmark-reporting:generateReport \
//   -PinputDir=./benchmark-results-TIMESTAMP \
//   -PoutputDir=./reports
```

## Next Steps

After benchmarks complete:

1. **Review JSON results** for detailed metrics
2. **Compare baseline vs optimized** scenarios
3. **Identify performance bottlenecks**
4. **Generate markdown reports** for stakeholders
5. **Track regression** by saving results for comparison

## Questions?

For issues or questions about benchmarking:
- Check logs: `adb logcat`
- Review benchmark source code in `microbenchmark/` and `macrobenchmark/` modules
- Consult Jetpack Benchmark documentation: https://developer.android.com/studio/profile/benchmark
