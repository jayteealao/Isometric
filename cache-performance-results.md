# PreparedScene Cache Performance Results

**Date:** [YYYY-MM-DD]
**Device:** [Device Model/Name]
**Android Version:** [Version]
**Benchmark Version:** [Commit SHA]

## Executive Summary

[Brief overview of cache performance impact - to be filled after testing]

---

## Test Configuration

- **Warmup Frames:** 60
- **Measurement Frames:** 300
- **Build Type:** Release
- **Scenarios Tested:** STATIC, MUTATION

---

## Results

### Scenario 1: STATIC (Same Scene Rebuilt Repeatedly)

**Expected Behavior:** High cache hit rate, significant performance improvement

#### Baseline (No Cache)
- **Average Frame Time:** [X.XX ms]
- **Min Frame Time:** [X.XX ms]
- **Max Frame Time:** [X.XX ms]
- **Standard Deviation:** [X.XX ms]
- **Cache Hit Rate:** N/A (no cache)

#### With Cache
- **Average Frame Time:** [X.XX ms]
- **Min Frame Time:** [X.XX ms]
- **Max Frame Time:** [X.XX ms]
- **Standard Deviation:** [X.XX ms]
- **Cache Hit Rate:** [XX%]
- **Cache Hits:** [XXX]
- **Cache Misses:** [XXX]

#### Performance Improvement
- **Frame Time Reduction:** [X.XX ms → X.XX ms]
- **Improvement:** [XX%]
- **Speedup:** [Xx faster]

---

### Scenario 2: MUTATION (Scene Changes Every Frame)

**Expected Behavior:** Low/zero cache hit rate, no performance regression

#### Baseline (No Cache)
- **Average Frame Time:** [X.XX ms]
- **Min Frame Time:** [X.XX ms]
- **Max Frame Time:** [X.XX ms]
- **Standard Deviation:** [X.XX ms]
- **Cache Hit Rate:** N/A (no cache)

#### With Cache
- **Average Frame Time:** [X.XX ms]
- **Min Frame Time:** [X.XX ms]
- **Max Frame Time:** [X.XX ms]
- **Standard Deviation:** [X.XX ms]
- **Cache Hit Rate:** [XX%]
- **Cache Hits:** [XXX]
- **Cache Misses:** [XXX]

#### Performance Impact
- **Frame Time Change:** [X.XX ms → X.XX ms]
- **Delta:** [±X.XX ms]
- **Impact:** [Negligible / Within measurement noise]

---

## Analysis

### Cache Effectiveness

**STATIC Scenario:**
- [ ] Cache hit rate > 95%
- [ ] Frame time improvement > 20%
- [ ] Consistent performance across measurement window
- [ ] No memory issues or crashes

**MUTATION Scenario:**
- [ ] Cache hit rate < 5%
- [ ] Frame time delta < 5%
- [ ] No performance regression
- [ ] Cache invalidation working correctly

### Cache Hit/Miss Breakdown

```
Example from logs:
PreparedScene: Cache miss - scene version changed (0 -> 1)
PreparedScene: Cache hit - reusing prepared scene (version 1)
PreparedScene: Cache hit - reusing prepared scene (version 1)
...
```

[Paste relevant log snippets showing cache behavior]

### Memory Impact

- **Baseline Memory Usage:** [XXX MB]
- **With Cache Memory Usage:** [XXX MB]
- **Delta:** [±XX MB]

[Note: Memory analysis requires Android Studio profiler or separate memory benchmark]

---

## Validation Checklist

- [ ] STATIC scenario shows clear cache benefit (>20% improvement)
- [ ] MUTATION scenario shows no regression (<5% delta)
- [ ] Cache hit/miss logging is working correctly
- [ ] Scene version tracking is accurate
- [ ] No crashes or memory leaks observed
- [ ] Performance is stable across measurement window

---

## Conclusions

### Key Findings

1. **Cache Effectiveness:** [Summary of static scenario results]
2. **Overhead Assessment:** [Summary of mutation scenario results]
3. **Correctness:** [Cache invalidation behavior]

### Recommendations

- [ ] Ready to merge to main branch
- [ ] Needs further optimization
- [ ] Issues found (describe below)

### Issues/Concerns

[List any problems encountered during testing]

---

## Raw Data

### STATIC Scenario Log Extract
```
[Paste key sections from static-TIMESTAMP.log]
```

### MUTATION Scenario Log Extract
```
[Paste key sections from mutation-TIMESTAMP.log]
```

---

## Appendix

### Test Environment Details

**Build Configuration:**
```
./gradlew :isometric-benchmark:assembleRelease
```

**Device Information:**
```
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
```

**Benchmark Command:**
```
adb shell am start -n io.fabianterhorst.isometric.benchmark/.BenchmarkActivity \
  --es "SCENARIO" "STATIC" \
  --ei "WARMUP_FRAMES" "60" \
  --ei "MEASUREMENT_FRAMES" "300"
```

### Analysis Tools Used

- [List any scripts or tools used to analyze results]
- [e.g., Excel, Python scripts, gnuplot, etc.]

---

**Tested by:** [Your Name]
**Review Status:** [ ] Pending / [ ] Approved / [ ] Needs Work
