# PreparedScene Cache Design

**Goal:** Eliminate redundant scene preparation for static/unchanged scenes, targeting 70-90% reduction in frame time for static scenarios.

**Target Performance:**
- Baseline static 100 objects: 65.2ms → Target: <20ms
- Expected improvement: ~70% reduction

---

## Problem Statement

Currently, `IsometricEngine.prepare()` performs full scene transformation and depth sorting every frame, even when the scene hasn't changed. This is wasteful for static scenes.

**Baseline measurements:**
- Static 100 objects: 65.2ms avg (should be near-instant after first frame)
- Mutation 100 objects: 209.7ms avg (expected - scene changes every frame)

**Root cause:** No caching mechanism - `prepare()` always does:
1. Transform all items from 3D → 2D
2. Depth sort all items
3. Apply culling/backface removal
4. Create RenderCommands

---

## Architecture

### Cache Location

**Store cache in `IsometricEngine`** (not `IsometricSceneState`):
- Engine owns the `prepare()` logic
- Platform-agnostic (works for Compose, Android View, future platforms)
- `IsometricSceneState` is just a thin Compose wrapper

### Cache Invalidation Strategy

**Invalidate cache when any of these change:**
1. **Scene version** - Items added/removed/modified
2. **Viewport size** - Canvas width/height changes
3. **Render options** - Depth sorting, culling, etc. changes

**Cache key:** `(sceneVersion, viewportWidth, viewportHeight, renderOptions)`

### Cache Hit/Miss Logic

```kotlin
fun prepare(sceneVersion: Int, width: Int, height: Int, options: RenderOptions): PreparedScene {
    // Fast path: cache hit (zero allocations)
    if (cachedScene != null &&
        sceneVersion == cachedVersion &&
        width == cachedWidth &&
        height == cachedHeight &&
        options === cachedOptions) {
        return cachedScene!!
    }

    // Slow path: cache miss - prepare scene
    val scene = prepareSceneInternal(width, height, options)

    // Update cache
    cachedScene = scene
    cachedVersion = sceneVersion
    cachedWidth = width
    cachedHeight = height
    cachedOptions = options

    return scene
}
```

---

## Implementation Details

### 1. IsometricEngine Changes

**Add cache fields:**

```kotlin
class IsometricEngine(...) {
    // Existing
    private val items = mutableListOf<SceneItem>()

    // NEW: Cache state (zero-allocation checking)
    private var cachedScene: PreparedScene? = null
    private var cachedVersion: Int = -1
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedOptions: RenderOptions? = null
}
```

**Update prepare() signature:**

```kotlin
// OLD
fun prepare(width: Int, height: Int, options: RenderOptions): PreparedScene

// NEW
fun prepare(
    sceneVersion: Int,  // NEW parameter
    width: Int,
    height: Int,
    options: RenderOptions = RenderOptions.Default
): PreparedScene
```

**Rename existing prepare() implementation:**

```kotlin
// Existing prepare() becomes private
private fun prepareSceneInternal(
    width: Int,
    height: Int,
    options: RenderOptions
): PreparedScene {
    // ... existing transformation, sorting, culling logic ...
}

// New public prepare() with caching
fun prepare(
    sceneVersion: Int,
    width: Int,
    height: Int,
    options: RenderOptions = RenderOptions.Default
): PreparedScene {
    // Cache check + call prepareSceneInternal()
}
```

### 2. IsometricSceneState Changes

**No changes needed!** Already has `currentVersion` property:

```kotlin
class IsometricSceneState internal constructor(
    internal val engine: IsometricEngine
) {
    private var version by mutableIntStateOf(0)

    internal val currentVersion: Int get() = version  // Already exists!
}
```

### 3. IsometricCanvas Changes

**Update prepare() call to pass version:**

```kotlin
// OLD
val preparedScene = state.engine.prepare(
    width = canvasWidth,
    height = canvasHeight,
    options = renderOptions
)

// NEW
val preparedScene = state.engine.prepare(
    sceneVersion = state.currentVersion,  // NEW
    width = canvasWidth,
    height = canvasHeight,
    options = renderOptions
)
```

### 4. Android View Integration

**If Android View binding exists, update similarly:**

```kotlin
// In any View-based renderer
val preparedScene = engine.prepare(
    sceneVersion = currentSceneVersion,  // Track version in View layer
    width = viewWidth,
    height = viewHeight,
    options = renderOptions
)
```

---

## Performance Optimizations

### 1. Zero-Allocation Cache Key Checking

**Instead of creating CacheKey objects:**

```kotlin
// SLOW (allocates object every frame)
data class CacheKey(val version: Int, val width: Int, val height: Int)
if (CacheKey(v, w, h) == cachedKey) { ... }

// FAST (zero allocations - primitive comparisons only)
if (cachedScene != null &&
    sceneVersion == cachedVersion &&
    width == cachedWidth &&
    height == cachedHeight &&
    options === cachedOptions) { ... }
```

**Impact:** Eliminates allocation overhead in fast path (cache hit)

### 2. Reference Equality for RenderOptions

Use `===` (reference equality) instead of `==` (structural equality):

```kotlin
options === cachedOptions  // Fast pointer comparison
```

**Why:** RenderOptions instances rarely change - same instance used throughout app lifecycle. Reference equality is orders of magnitude faster than deep comparison.

### 3. Fast Path Dominance

For static scenes (target use case):
- **Fast path (cache hit):** 4 int comparisons + 1 reference check = ~5ns
- **Slow path (cache miss):** Full scene preparation = ~65ms

**Result:** Cache hit is **~13 million times faster** than cache miss!

---

## Testing Strategy

### Benchmark Scenarios

**1. Static scene (cache hit every frame):**
- **Baseline:** 65.2ms avg
- **Expected:** <20ms avg (~70% reduction)
- **Validation:** All frames after first should be <1ms (cache hits)

**2. Mutation scene (cache miss every frame):**
- **Baseline:** 209.7ms avg
- **Expected:** ~210ms avg (no improvement - invalidates cache every frame)
- **Validation:** Performance unchanged (proves cache doesn't add overhead)

**3. Viewport resize:**
- Render 100 frames → resize viewport → render 100 frames
- **Expected:** Fast → slow (1 frame) → fast
- **Validation:** Cache invalidates on resize, rebuilds on next frame

**4. RenderOptions change:**
- Render with bounds checking → disable bounds checking → render
- **Expected:** Cache invalidates, rebuilds with new options
- **Validation:** Output changes correctly

### Unit Tests

```kotlin
@Test
fun `cache hit when scene unchanged`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0, 0, 0)), IsoColor.RED)

    val scene1 = engine.prepare(version=1, width=100, height=100)
    val scene2 = engine.prepare(version=1, width=100, height=100)

    assertSame(scene1, scene2)  // Same instance = cache hit
}

@Test
fun `cache miss when scene version changes`() {
    val engine = IsometricEngine()
    val scene1 = engine.prepare(version=1, width=100, height=100)
    val scene2 = engine.prepare(version=2, width=100, height=100)

    assertNotSame(scene1, scene2)  // Different instances = cache miss
}

@Test
fun `cache miss when viewport changes`() {
    val engine = IsometricEngine()
    val scene1 = engine.prepare(version=1, width=100, height=100)
    val scene2 = engine.prepare(version=1, width=200, height=200)

    assertNotSame(scene1, scene2)
}
```

---

## Edge Cases

### 1. Empty Scene
- **Behavior:** Cache works normally
- **Result:** Empty PreparedScene cached
- **Cost:** Near-zero (no items to process)

### 2. Very Large Scene (1000+ objects)
- **Behavior:** Cache becomes MORE valuable
- **Baseline:** 3+ seconds per frame
- **Cached:** <1ms per frame
- **Impact:** 3000x+ improvement!

### 3. Rapid Version Changes
- **Scenario:** Scene mutates every frame
- **Behavior:** Cache miss every frame
- **Impact:** No performance degradation (same as baseline)

### 4. Viewport Changes Mid-Frame
- **Scenario:** Canvas resized during rendering
- **Behavior:** Next frame gets cache miss, rebuilds with new size
- **Impact:** 1-frame delay, then back to cached performance

### 5. Multiple Engines Sharing State
- **Scenario:** Multiple IsometricEngine instances
- **Behavior:** Each engine maintains independent cache
- **Impact:** No cache sharing (intentional - engines may have different transformations)

---

## Migration Path

### Backward Compatibility

**Breaking change:** `prepare()` signature changes from 2 to 4 parameters.

**Migration strategy:**

1. **Add new signature alongside old:**
   ```kotlin
   // New (recommended)
   fun prepare(sceneVersion: Int, width: Int, height: Int, options: RenderOptions): PreparedScene

   // Old (deprecated)
   @Deprecated("Use overload with sceneVersion parameter")
   fun prepare(width: Int, height: Int, options: RenderOptions): PreparedScene {
       return prepare(sceneVersion = 0, width, height, options)  // Always cache miss
   }
   ```

2. **Update internal usages first** (Compose, View bindings)

3. **Mark old signature deprecated** in next release

4. **Remove old signature** in major version bump

### Phased Rollout

**Phase 1:** Implement in isometric-core
**Phase 2:** Update isometric-compose integration
**Phase 3:** Update isometric-android-view (if exists)
**Phase 4:** Run benchmarks, validate performance
**Phase 5:** Deprecate old API, document migration

---

## Success Criteria

**Performance targets:**
- ✅ Static 100 objects: 65.2ms → <20ms (~70% improvement)
- ✅ Static 500 objects: Expected <50ms (vs baseline ~500ms)
- ✅ Cache hit overhead: <10ns (4 int checks + 1 ref check)
- ✅ No regression on mutation scenarios (209.7ms → ~210ms)

**Correctness criteria:**
- ✅ Output identical to non-cached implementation
- ✅ Cache invalidates correctly on all state changes
- ✅ No visual glitches or rendering artifacts
- ✅ All existing tests pass

**Code quality:**
- ✅ Zero allocations in cache hit path
- ✅ Clear API with good documentation
- ✅ Comprehensive unit tests
- ✅ Benchmark tests validate performance

---

## Future Enhancements

**Out of scope for this design** (potential future work):

1. **Multi-level cache:**
   - Cache transformed items separately from sorted items
   - Allows partial invalidation (e.g., only re-sort on viewport change)

2. **Differential updates:**
   - Track which specific items changed
   - Only re-transform changed items, not entire scene

3. **Cache eviction policy:**
   - Currently: 1-entry cache (LRU of size 1)
   - Future: Cache multiple viewport sizes for resize handling

4. **Metrics/instrumentation:**
   - Track cache hit/miss rates
   - Measure actual performance improvement per scenario

**These are explicitly deferred** to keep the initial implementation simple and focused on the 80/20 win.

---

## Implementation Plan

See: `docs/plans/2026-01-03-preparedscene-cache-implementation.md` (to be created)

**Estimated effort:** 2-3 hours
- IsometricEngine changes: 1 hour
- Integration updates: 30 min
- Tests: 1 hour
- Benchmarks: 30 min
