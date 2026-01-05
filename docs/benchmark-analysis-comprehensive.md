# Comprehensive Benchmark Analysis Report
## Isometric Engine - Scale Performance Investigation

**Date:** January 4, 2026
**Test Matrix:** 18 configurations (3 sizes × 2 scenarios × 3 optimizations)
**Platform:** Android Emulator API 36

---

## Executive Summary

### Critical Findings

1. **❌ NO PERFORMANCE SCALING DETECTED**
   - Performance remains constant at ~19ms regardless of scene size (100 → 500 → 1000 objects)
   - Expected: Linear (O(n)) or quadratic (O(n²)) scaling
   - Actual: Constant time O(1) - **ANOMALOUS**

2. **❌ DrawWithCache SEVERE REGRESSION**
   - 500 objects STATIC: 32.98ms vs 19.77ms baseline (**67% SLOWER**)
   - Only configuration showing significant performance difference
   - Suggests cache overhead exceeding benefits at certain scales

3. **❌ Optimizations Provide 0% Benefit**
   - PreparedScene cache: 0-2% variance (within noise)
   - DrawWithCache: 0% benefit (except regression case)
   - All scenarios show identical performance regardless of optimization

---

## Detailed Results

### By Scene Size

#### 100 Objects
| Scenario | Baseline | PreparedCache | DrawCache | Delta |
|----------|----------|---------------|-----------|-------|
| STATIC | 19.72ms | 19.27ms (-2%) | 18.64ms (-5%) | 1.08ms |
| FULL_MUTATION | 19.43ms | 19.22ms (-1%) | 19.52ms (+0%) | 0.30ms |

**Analysis:** Minimal variance (0-5%), all within measurement noise. No optimization benefit.

#### 500 Objects
| Scenario | Baseline | PreparedCache | DrawCache | Delta |
|----------|----------|---------------|-----------|-------|
| STATIC | 19.77ms | 19.31ms (-2%) | **32.98ms (+67%)** ⚠️ | **13.21ms** |
| FULL_MUTATION | 18.39ms | 19.34ms (+5%) | 19.43ms (+6%) | 1.04ms |

**Analysis:**
- **CRITICAL:** DrawCache shows massive 67% regression in STATIC scenario
- FULL_MUTATION shows normal ~19ms performance
- PreparedCache provides 0% benefit despite 5x scene size

#### 1000 Objects
| Scenario | Baseline | PreparedCache | DrawCache | Delta |
|----------|----------|---------------|-----------|-------|
| STATIC | 19.21ms | 19.53ms (+2%) | 19.25ms (+0%) | 0.32ms |
| FULL_MUTATION | 19.21ms | 19.31ms (+1%) | 19.57ms (+2%) | 0.36ms |

**Analysis:** Identical performance to 100 objects. **NO SCALING OBSERVED.**

### Scalability Analysis

Expected vs Actual performance scaling:

| Scene Size | Expected (O(n²) sorting) | Actual Baseline | Scaling Factor |
|------------|-------------------------|-----------------|----------------|
| 100 | 19ms (baseline) | 19.72ms | 1.0x |
| 500 | **475ms** (25x worse) | 19.77ms | **1.0x** ❌ |
| 1000 | **1900ms** (100x worse) | 19.21ms | **1.0x** ❌ |

**Finding:** Performance shows **O(1) constant time** instead of expected O(n²) quadratic scaling.

### Optimization Effectiveness

| Optimization | 100 Objects | 500 Objects | 1000 Objects | Overall |
|--------------|-------------|-------------|--------------|---------|
| PreparedCache | 0-2% | 0-2% | 0-2% | **No benefit** |
| DrawCache | 0-5% | **-67%** ⚠️ | 0-2% | **Harmful** |

---

## Critical Issues Identified

### Issue 1: Missing Performance Scaling

**Symptom:** Scene with 1000 objects performs identically to 100 objects (~19ms)

**Expected Behavior:**
- Drawing: O(n) - 10x slower for 10x objects (190ms)
- Depth sorting: O(n²) - 100x slower for 10x objects (1900ms)

**Actual Behavior:**
- 100 → 1000 objects: 0% performance change

**Possible Causes:**
1. Viewport culling secretly enabled (only rendering visible objects)
2. Objects not actually being added to scene
3. Frame rate cap (vsync at 60 FPS = 16.67ms minimum)
4. Scene generator not creating correct number of objects
5. Benchmark measuring wrong phase

**Investigation Required:**
- Verify object count in scene (log `items.size` in IsometricEngine)
- Check draw call counts (should scale linearly with objects)
- Verify viewport culling is actually disabled
- Check for vsync limiting

### Issue 2: DrawCache Regression at 500 Objects STATIC

**Symptom:** DrawCache shows 32.98ms vs 19.77ms baseline (67% slower)

**Only affects:**
- 500 objects
- STATIC scenario
- DrawCache optimization

**Does NOT affect:**
- 100 or 1000 objects
- FULL_MUTATION scenario
- PreparedCache optimization

**Possible Causes:**
1. Memory pressure - excessive Path object allocations
2. Cache thrashing - cache invalidation patterns
3. GC pause during measurement
4. DrawWithCache overhead exceeding benefits at this specific scale

**Investigation Required:**
- Check memory allocation metrics (allocatedMB)
- Check GC invocation counts
- Verify cache hit rates
- Profile memory allocations during DrawCache scenario

### Issue 3: PreparedScene Cache Not Working

**Symptom:** PreparedScene cache provides 0% benefit across all scales

**Expected:**
- Cache should save transformation + depth sorting time
- At 500 objects: ~60ms savings (if O(n²) sorting)
- At 1000 objects: ~500ms savings (if O(n²) sorting)

**Actual:**
- 0-2% variance at all scales

**Possible Causes:**
1. Cache always missing (sceneVersion changing every frame)
2. Issue #1 - no sorting happening (constant time regardless)
3. Cache hit but savings unmeasurable due to Issue #1

**Investigation Required:**
- Log cache hit rates from metrics
- Verify sceneVersion behavior in STATIC vs FULL_MUTATION
- Check if prepare() is being called

---

## Metrics Data Gaps

The following metrics were implemented but not captured in results:

1. **Cache Hit Rates** - Not in CSV output
2. **Draw Call Counts** - Not in CSV output
3. **Memory Allocation** - Not in CSV output
4. **GC Invocations** - Not in CSV output

These metrics are CRITICAL for understanding:
- Why performance doesn't scale (draw calls should reveal if objects are rendered)
- Why DrawCache regresses (memory/GC data)
- Why PreparedScene doesn't help (cache hit rates)

**Action Required:** Re-export results with full metrics or extract from logcat.

---

## Recommendations

### Immediate Actions

1. **Investigate Issue #1 (Missing Scaling)**
   - Add logging to verify object counts
   - Log draw call counts per frame
   - Verify no secret culling is happening
   - Check for vsync limiting

2. **Investigate Issue #2 (DrawCache Regression)**
   - Extract memory metrics from detailed results
   - Profile 500 object STATIC scenario
   - Compare memory allocations DrawCache vs baseline

3. **Extract Full Metrics**
   - Pull detailed CSV with cache hits, draw calls, memory, GC
   - Or parse from logcat BenchmarkActivity logs
   - Correlate metrics with performance anomalies

### Next Steps

1. Fix fundamental measurement issues
2. Re-run benchmarks with verified object counts
3. Disable vsync if present
4. Add instrumentation to verify rendering behavior
5. Generate corrected performance report

---

## Appendix: Raw Data

### All Results (Frame Time)

```
100 Objects:
  STATIC baseline: 19.72ms
  STATIC prepared: 19.27ms
  STATIC drawcache: 18.64ms
  MUTATION baseline: 19.43ms
  MUTATION prepared: 19.22ms
  MUTATION drawcache: 19.52ms

500 Objects:
  STATIC baseline: 19.77ms
  STATIC prepared: 19.31ms
  STATIC drawcache: 32.98ms ⚠️
  MUTATION baseline: 18.39ms
  MUTATION prepared: 19.34ms
  MUTATION drawcache: 19.43ms

1000 Objects:
  STATIC baseline: 19.21ms
  STATIC prepared: 19.53ms
  STATIC drawcache: 19.25ms
  MUTATION baseline: 19.21ms
  MUTATION prepared: 19.31ms
  MUTATION drawcache: 19.57ms
```

### Variance Analysis

- Minimum frame time: 18.39ms (500 FULL_MUTATION baseline)
- Maximum frame time: 32.98ms (500 STATIC drawcache) - **OUTLIER**
- Maximum (excluding outlier): 19.77ms
- Range (excluding outlier): 1.38ms (7% variance)
- Range (including outlier): 14.59ms (79% variance)

**Conclusion:** All results cluster around 19ms ± 1ms except for single outlier.

---

## Status

- ✅ Benchmarks completed (18/18 configurations)
- ❌ Results anomalous - investigation required
- ⚠️ Metrics incomplete - full data needed
- 🔍 Root cause analysis in progress
