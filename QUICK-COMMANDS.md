# Quick Reference Commands

## Navigation

```bash
# Switch to benchmark-harness worktree
cd /c/Users/jayte/Documents/dev/Isometric/.worktrees/benchmark-harness
```

## Build Commands

```bash
# Build release APK
./gradlew :isometric-benchmark:assembleRelease

# Build debug APK
./gradlew :isometric-benchmark:assembleDebug

# Run tests
./gradlew :isometric-core:test

# Clean build
./gradlew clean
```

## Device/Emulator Commands

```bash
# List connected devices
adb devices

# Install benchmark APK
adb install -r isometric-benchmark/build/outputs/apk/release/isometric-benchmark-release.apk

# Uninstall app
adb uninstall io.fabianterhorst.isometric.benchmark

# Check if app is installed
adb shell pm list packages | grep isometric
```

## Benchmark Execution

### STATIC Scenario (Cache Hit Expected)
```bash
# Clear logs
adb logcat -c

# Start benchmark
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
  --es "SCENARIO" "STATIC" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"

# Wait for completion (~20 seconds)
sleep 20

# Pull results
adb logcat -d | grep "BenchmarkOrchestrator\|PreparedScene" > static-results.log
```

### MUTATION Scenario (Cache Miss Expected)
```bash
# Clear logs
adb logcat -c

# Start benchmark
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
  --es "SCENARIO" "MUTATION" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"

# Wait for completion
sleep 20

# Pull results
adb logcat -d | grep "BenchmarkOrchestrator\|PreparedScene" > mutation-results.log
```

## Automated Benchmark

```bash
# Run full benchmark suite (both scenarios)
./run-cache-benchmark.sh

# Results saved to:
./benchmark-results/static-YYYYMMDD-HHMMSS.log
./benchmark-results/mutation-YYYYMMDD-HHMMSS.log
```

## Log Analysis

```bash
# View cache hit/miss messages
grep "Cache" static-results.log

# View frame time statistics
grep "frame time" static-results.log

# View full benchmark summary
grep "Benchmark Results" -A 10 static-results.log

# Count cache hits vs misses
grep -c "Cache hit" static-results.log
grep -c "Cache miss" static-results.log
```

## Git Operations

```bash
# View cherry-picked commits
git log --oneline -10

# View cache-related changes
git log --oneline --grep="cache"

# Show specific commit
git show 4c96ffe  # Latest cache commit

# Check current branch status
git status
```

## Testing

```bash
# Run cache tests specifically
./gradlew :isometric-core:test --tests PreparedSceneCacheTest

# Run all tests with output
./gradlew :isometric-core:test --info
```

## Troubleshooting

```bash
# View full logcat
adb logcat

# View logcat with filtering
adb logcat | grep "Isometric\|Benchmark"

# Check app is running
adb shell dumpsys activity activities | grep "BenchmarkActivity"

# Force stop app
adb shell am force-stop io.fabianterhorst.isometric.benchmark

# Clear app data
adb shell pm clear io.fabianterhorst.isometric.benchmark

# Get device info
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

## Performance Analysis

```bash
# Extract average frame times from logs
grep "Average frame time:" static-results.log
grep "Average frame time:" mutation-results.log

# Calculate cache hit rate
# Hits / (Hits + Misses) * 100
```

## File Locations

```
benchmark-harness/
├── run-cache-benchmark.sh              # Automated benchmark script
├── cache-performance-results.md        # Results template
├── CACHE-VERIFICATION.md               # Verification guide
├── QUICK-COMMANDS.md                   # This file
├── benchmark-results/                  # Results output directory
├── isometric-core/
│   ├── src/main/kotlin/io/fabianterhorst/isometric/
│   │   └── IsometricEngine.kt          # Cache implementation
│   └── src/test/kotlin/io/fabianterhorst/isometric/
│       └── PreparedSceneCacheTest.kt   # Cache tests
└── isometric-benchmark/
    └── src/main/kotlin/io/fabianterhorst/isometric/benchmark/
        └── BenchmarkActivity.kt        # Benchmark runner
```

## Expected Results Summary

### STATIC Scenario
- Cache hit rate: >95%
- Performance improvement: 20-50% faster
- Log pattern: 1 miss, then all hits

### MUTATION Scenario
- Cache hit rate: <5%
- Performance delta: <5% (no regression)
- Log pattern: all misses

## Java Setup (If Needed)

```bash
# Set JAVA_HOME (Windows/Git Bash)
export JAVA_HOME="C:/Program Files/Java/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"

# Verify Java
java -version
```

## Next Steps After Benchmarking

1. Review log files
2. Fill out cache-performance-results.md
3. Verify success criteria met
4. Commit changes if ready
5. Prepare for merge to main branch
