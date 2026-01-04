# PreparedScene Cache Performance Verification Guide

## Overview

This document describes how to verify the performance impact of the PreparedScene cache implementation that was cherry-picked from the `preparedscene-cache` branch.

## What Was Cherry-Picked

The following 7 commits were cherry-picked to the `benchmark-harness` worktree:

1. **9174ab6** - Task 1: Add cache storage fields to IsometricEngine
2. **7d3e3dd** - Task 2: Add sceneVersion parameter to prepare()
3. **8109246** - Task 2 fix: Update all prepare() call sites for sceneVersion
4. **c56acbd** - Task 3: Extract prepareSceneInternal() method
5. **162b9ab** - Task 4: Implement PreparedScene cache hit/miss logic
6. **5f09c99** - Task 5: Wire sceneVersion to IsometricCanvas
7. **ccaf1f2** - Task 6: Add comprehensive cache behavior tests

**Status:** ✅ All commits cherry-picked successfully (no conflicts)

## Cache Implementation Summary

### How It Works

1. **Scene Version Tracking:** Each scene is assigned a version number (Long)
2. **Cache Storage:** IsometricEngine stores the last prepared scene and its version
3. **Cache Hit Logic:** If sceneVersion matches cached version, reuse PreparedScene
4. **Cache Miss Logic:** If sceneVersion differs, recompute PreparedScene
5. **Canvas Integration:** IsometricSceneState exposes sceneVersion (incremented on mutations)

### Key Components Modified

- `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`
  - Added `cachedPreparedScene` and `cachedSceneVersion` fields
  - Modified `prepare()` to accept `sceneVersion: Long?`
  - Added cache hit/miss logic in `prepare()`

- `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/compose/IsometricCanvas.kt`
  - Wired `sceneState.sceneVersion` to `engine.prepare()`

- `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`
  - Comprehensive test coverage for cache behavior

## Performance Verification Process

### Prerequisites

1. **Java Development Kit:** Ensure Java is properly configured
2. **Android SDK:** Required for building and deploying
3. **Connected Device/Emulator:** For running benchmarks
4. **ADB Access:** Command-line access to device

### Step 1: Build the Benchmark APK

```bash
cd /c/Users/jayte/Documents/dev/Isometric/.worktrees/benchmark-harness
./gradlew :isometric-benchmark:assembleRelease
```

**Note:** If you encounter Java PATH issues, ensure `JAVA_HOME` is set correctly.

### Step 2: Run Automated Benchmark Script

```bash
./run-cache-benchmark.sh
```

This script will:
- Build the release APK
- Install it on the connected device
- Run STATIC scenario (cache benefit expected)
- Run MUTATION scenario (no regression expected)
- Save results to `./benchmark-results/`

### Step 3: Manual Verification (Alternative)

If the automated script fails, run manually:

#### Install APK
```bash
adb install -r isometric-benchmark/build/outputs/apk/release/isometric-benchmark-release.apk
```

#### Run STATIC Scenario
```bash
adb logcat -c
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
  --es "SCENARIO" "STATIC" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"

# Wait ~20 seconds, then pull logs
adb logcat -d | grep "BenchmarkOrchestrator\|PreparedScene" > static-results.log
```

#### Run MUTATION Scenario
```bash
adb logcat -c
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
  --es "SCENARIO" "MUTATION" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"

# Wait ~20 seconds, then pull logs
adb logcat -d | grep "BenchmarkOrchestrator\|PreparedScene" > mutation-results.log
```

### Step 4: Analyze Results

Open the log files and look for:

#### Cache Hit/Miss Messages
```
PreparedScene: Cache miss - scene version changed (0 -> 1)
PreparedScene: Cache hit - reusing prepared scene (version 1)
```

#### Frame Time Statistics
```
BenchmarkOrchestrator: === Benchmark Results ===
BenchmarkOrchestrator: Average frame time: X.XX ms
BenchmarkOrchestrator: Min frame time: X.XX ms
BenchmarkOrchestrator: Max frame time: X.XX ms
```

### Step 5: Document Results

Fill out the template in `cache-performance-results.md` with:
- Measured frame times for each scenario
- Cache hit/miss rates
- Performance improvement calculations
- Validation checklist

## Expected Results

### STATIC Scenario (Same Scene Rebuilt)

- **Cache Hit Rate:** >95% (should see "Cache hit" in logs)
- **Performance Improvement:** 20-50% faster frame times
- **Behavior:** First frame is cache miss, subsequent frames are cache hits

**Why:** Same sceneVersion → cache reuse → skip expensive prepare() work

### MUTATION Scenario (Scene Changes Every Frame)

- **Cache Hit Rate:** <5% (should see "Cache miss" in logs)
- **Performance Impact:** <5% delta (within measurement noise)
- **Behavior:** Every frame is a cache miss (sceneVersion increments)

**Why:** Different sceneVersion → cache invalidation → normal prepare() path

## Success Criteria

✅ **Cache Working Correctly:**
- STATIC shows high hit rate and performance gain
- MUTATION shows low hit rate and no regression
- No crashes or memory issues

✅ **Ready to Merge:**
- Tests pass
- Performance validated
- Documentation complete

❌ **Issues Found:**
- Document in cache-performance-results.md
- Debug and fix before merging

## Troubleshooting

### Java PATH Issues

If build fails with Java errors:
```bash
# Set JAVA_HOME (example for Windows)
export JAVA_HOME="C:/Program Files/Java/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
```

### ADB Not Found

If adb command fails:
```bash
# Ensure Android SDK platform-tools is in PATH
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
```

### Device Not Connected

```bash
# Check connected devices
adb devices

# If none, connect device or start emulator
```

### Benchmark Doesn't Start

Check logcat for errors:
```bash
adb logcat | grep "AndroidRuntime\|BenchmarkActivity"
```

### Logs Are Empty

Increase wait time or check if benchmark completed:
```bash
# Check if activity is still running
adb shell dumpsys activity activities | grep "BenchmarkActivity"
```

## Next Steps After Verification

1. **Review Results:** Ensure cache meets performance goals
2. **Document Findings:** Complete cache-performance-results.md
3. **Run Tests:** Verify unit tests pass
4. **Prepare for Merge:** If validated, prepare to merge cache to main branch
5. **Baseline Comparison:** Optionally compare against pre-cache baseline

## Files Created

- **run-cache-benchmark.sh** - Automated benchmark execution script
- **cache-performance-results.md** - Results documentation template
- **CACHE-VERIFICATION.md** - This guide

## References

- **Original Branch:** `preparedscene-cache`
- **Target Branch:** `benchmark-harness`
- **Cherry-Picked Commits:** 9174ab6, 7d3e3dd, 8109246, c56acbd, 162b9ab, 5f09c99, ccaf1f2
- **Related Tests:** `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`

---

**Last Updated:** 2026-01-03
**Prepared By:** Claude Code Assistant
