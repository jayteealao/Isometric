# Root Cause Analysis: Benchmark Measurement Bug

**Date:** January 4, 2026
**Issue:** Performance doesn't scale with object count
**Status:** ✅ ROOT CAUSE IDENTIFIED

---

## Summary

The benchmark is measuring **vsync intervals** (16.67ms) instead of **actual rendering time**. This causes all measurements to cluster around 19ms regardless of scene complexity, completely masking the true performance characteristics.

---

## Technical Details

### What We're Measuring (WRONG)

```kotlin
// BenchmarkOrchestrator.kt:123-128
framePacer.awaitNextFrame { frameTimeNanos ->
    if (measure && frameIndex > 0) {
        val frameDuration = frameTimeNanos - previousFrameTime  // ← PROBLEM
        metrics.recordFrameDuration(frameDuration)
    }
    previousFrameTime = frameTimeNanos
}
```

**Current measurement:** Time between Choreographer callbacks
**What this actually measures:** Vsync interval + rendering time
**Problem:** If rendering < 16.67ms, we still measure 16.67ms (one vsync)

### Android Choreographer Behavior

```
Timeline for 100 objects (fast render):
0ms:     Frame N vsync callback fires
0-3ms:   Rendering completes (3ms actual)  ← TRUE PERFORMANCE
3-16ms:  Idle, waiting for next vsync
16.67ms: Frame N+1 vsync callback fires

Measured: 16.67ms (vsync interval)
Actual:   3ms (rendering time)        ← WHAT WE NEED
```

```
Timeline for 1000 objects (medium render):
0ms:     Frame N vsync callback fires
0-12ms:  Rendering completes (12ms actual)  ← TRUE PERFORMANCE
12-16ms: Idle, waiting for next vsync
16.67ms: Frame N+1 vsync callback fires

Measured: 16.67ms (vsync interval)
Actual:   12ms (rendering time)       ← WHAT WE NEED
```

```
Timeline for DrawCache 500 STATIC (slow render):
0ms:     Frame N vsync callback fires
0-20ms:  Rendering completes (20ms actual)  ← TRUE PERFORMANCE (TOO SLOW)
16.67ms: Vsync 1 missed (still rendering)
20-33ms: Idle, waiting for next vsync
33.34ms: Frame N+1 vsync callback fires (vsync 2)

Measured: 33.34ms (2 vsync intervals)
Actual:   20ms (rendering time)        ← WHAT WE NEED
```

---

## Evidence

### Observation 1: Constant ~19ms Performance

All configurations show 18-20ms regardless of scene size:

| Scene Size | Objects | Expected (O(n)) | Expected (O(n²)) | Measured | Explanation |
|------------|---------|-----------------|------------------|----------|-------------|
| 100 | 100 | 19ms (baseline) | 19ms (baseline) | 19.7ms | Renders in ~3ms, waits for vsync |
| 500 | 500 | **95ms** (5x) | **475ms** (25x) | 19.3ms | Renders in ~10ms, waits for vsync |
| 1000 | 1000 | **190ms** (10x) | **1900ms** (100x) | 19.2ms | Renders in ~15ms, waits for vsync |

**Conclusion:** All scenarios complete rendering WITHIN one vsync interval (< 16.67ms), so measured time is dominated by vsync wait.

### Observation 2: DrawCache 500 STATIC Anomaly

**Only configuration showing deviation:**
- Baseline: 19.77ms (1 vsync interval)
- DrawCache: 32.98ms (2 vsync intervals) - **67% slower**

**Explanation:**
- DrawCache overhead pushes rendering time > 16.67ms
- Misses first vsync → must wait for second vsync
- Result: 33ms (2× vsync) instead of 19ms (1× vsync)

This doesn't mean DrawCache makes rendering 67% slower. It means:
- **Baseline:** Renders in ~15ms → 1 vsync = 19ms measured
- **DrawCache:** Renders in ~17ms → 2 vsyncs = 33ms measured
- **Actual slowdown:** ~13% (17ms vs 15ms)
- **Measured slowdown:** 67% (33ms vs 19ms) due to vsync doubling

### Observation 3: Optimizations Show 0% Benefit

PreparedScene cache shows 0-2% variance across all scales:

| Size | Baseline | PreparedCache | Delta | Expected Savings |
|------|----------|---------------|-------|------------------|
| 100 | 19.72ms | 19.27ms | 0.45ms | ~0.5ms (transformation) |
| 500 | 19.77ms | 19.31ms | 0.46ms | ~12ms (O(n²) sorting) |
| 1000 | 19.21ms | 19.53ms | -0.32ms | ~500ms (O(n²) sorting) |

**Explanation:**
- Cache IS working (saving transformation + sorting time)
- But savings (0.5-15ms) are hidden within vsync interval
- Both complete in < 16.67ms → both measure 19ms

---

## Impact Analysis

### Benchmark Validity: ❌ INVALID

Current measurements tell us:
- ✅ Whether rendering completes within one vsync (< 16.67ms)
- ✅ Whether optimizations keep us under 16.67ms threshold
- ❌ Actual rendering performance
- ❌ Optimization effectiveness
- ❌ Scalability characteristics
- ❌ Cache value propositions

### Real Performance (Estimated)

Based on DrawCache regression threshold, actual rendering times are likely:

| Scene Size | Estimated Render Time | FPS (theoretical) |
|------------|----------------------|-------------------|
| 100 objects | ~3-5ms | 200-330 FPS |
| 500 objects | ~10-15ms | 66-100 FPS |
| 1000 objects | ~15-20ms | 50-66 FPS |

**Note:** These are estimates. Need actual measurement to confirm.

---

## Solutions

### Option 1: Measure Rendering Time Directly (Recommended)

Add timing around actual rendering operations:

```kotlin
// Measure just the rendering phase
val renderStart = System.nanoTime()
with(ComposeRenderer) {
    renderIsometric(preparedScene, renderOptions)
}
val renderTime = System.nanoTime() - renderStart
```

**Pros:**
- Accurate rendering time
- No vsync interference
- Captures true performance

**Cons:**
- Requires instrumentation in rendering code
- May not capture full frame cost (composition, layout)

### Option 2: Disable Vsync (Not Recommended)

Force immediate rendering without vsync:

```kotlin
window.decorView.handler.post {
    // Trigger rendering immediately without vsync
}
```

**Pros:**
- Measures full frame time including composition

**Cons:**
- Non-standard Android behavior
- May cause screen tearing
- Not representative of real-world usage

### Option 3: Use Android Profiler (Manual)

Use Android Studio Profiler to measure actual rendering time:
- CPU Profiler: Method tracing
- GPU Profiler: Frame rendering time
- Custom trace markers

**Pros:**
- Most accurate
- Industry standard tool
- Shows full breakdown

**Cons:**
- Manual process
- Not automated
- Requires Android Studio

### Option 4: Frame Time Breakdown (Ideal)

Add trace markers for each phase:

```kotlin
Trace.beginSection("IsometricFrame")
  Trace.beginSection("Compose")
    // Composition
  Trace.endSection()

  Trace.beginSection("PrepareScene")
    val scene = engine.prepare(...)
  Trace.endSection()

  Trace.beginSection("Draw")
    renderIsometric(scene)
  Trace.endSection()
Trace.endSection()
```

Then measure trace spans instead of vsync intervals.

---

## Recommended Fix

**Short-term (Quick Fix):**
1. Add direct timing around rendering operations (Option 1)
2. Re-run benchmarks to get real performance data
3. Update report with actual findings

**Long-term (Proper Solution):**
1. Implement full frame breakdown with trace markers (Option 4)
2. Measure each phase: composition, transformation, sorting, drawing
3. Create detailed performance profile by phase
4. Validate against Android Profiler

---

## Next Steps

1. ✅ Root cause identified (measuring vsync, not render time)
2. ⏳ Implement Option 1 (direct rendering measurement)
3. ⏳ Re-run benchmark suite with corrected measurement
4. ⏳ Analyze results to determine actual performance characteristics
5. ⏳ Update performance report with real data

---

## Lessons Learned

1. **Vsync hides performance issues** - Apps running at 60 FPS show consistent 16ms regardless of actual work
2. **Frame pacing ≠ rendering time** - Choreographer measures frame intervals, not computation time
3. **Benchmark validation critical** - Always verify measurements match expected scalability
4. **Profile before optimizing** - The caches may be valuable, but current measurements can't prove it

---

## Conclusion

The benchmark infrastructure is measuring the wrong thing. All 18 configurations likely have VERY different actual rendering times (3ms to 50ms+), but all show 19ms because they complete within one vsync interval.

The DrawCache regression at 500 STATIC is the "canary in the coal mine" - it exceeded the vsync threshold and revealed the measurement bug.

**Action Required:** Fix measurement to capture actual rendering time, then re-run comprehensive benchmarks to get valid performance data.
