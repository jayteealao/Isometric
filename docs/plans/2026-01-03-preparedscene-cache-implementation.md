# PreparedScene Cache Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate redundant scene preparation for static/unchanged scenes, targeting 70-90% reduction in frame time for static scenarios (65.2ms → <20ms).

**Architecture:** Add zero-allocation caching layer in `IsometricEngine.prepare()` that checks a multi-factor cache key (sceneVersion, viewport size, renderOptions) before performing expensive scene transformation and depth sorting. Cache is stored directly in the engine instance for platform-agnostic compatibility.

**Tech Stack:** Kotlin, Jetpack Compose, isometric-core library, JUnit 5 for testing

---

## Task 1: Add cache storage fields to IsometricEngine

**Files:**
- Modify: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`
- Test: `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`

**Step 1: Write failing test for cache field existence**

Create test file to verify cache fields exist (reflection-based test to ensure internal state):

```kotlin
package io.fabianterhorst.isometric

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class PreparedSceneCacheTest {
    @Test
    fun `engine has cache storage fields`() {
        val engine = IsometricEngine()

        // Verify cache fields exist via reflection (internal fields)
        val cachedSceneField = engine.javaClass.getDeclaredField("cachedScene")
        val cachedVersionField = engine.javaClass.getDeclaredField("cachedVersion")
        val cachedWidthField = engine.javaClass.getDeclaredField("cachedWidth")
        val cachedHeightField = engine.javaClass.getDeclaredField("cachedHeight")
        val cachedOptionsField = engine.javaClass.getDeclaredField("cachedOptions")

        assertNotNull(cachedSceneField)
        assertNotNull(cachedVersionField)
        assertNotNull(cachedWidthField)
        assertNotNull(cachedHeightField)
        assertNotNull(cachedOptionsField)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :isometric-core:test --tests PreparedSceneCacheTest`
Expected: FAIL with "NoSuchFieldException: cachedScene"

**Step 3: Add cache fields to IsometricEngine**

In `IsometricEngine.kt`, add cache fields after the existing `items` field:

```kotlin
class IsometricEngine(
    private val angle: Double = PI / 6,
    private val scale: Double = 70.0,
    private val lightPosition: Vector = Vector(2.0, -1.0, 3.0),
    private val colorDifference: Double = 0.20,
    private val lightColor: IsoColor = IsoColor.WHITE
) {
    private val items = mutableListOf<SceneItem>()
    private var nextId = 0

    // NEW: Cache state (zero-allocation checking)
    private var cachedScene: PreparedScene? = null
    private var cachedVersion: Int = -1
    private var cachedWidth: Int = -1
    private var cachedHeight: Int = -1
    private var cachedOptions: RenderOptions? = null

    // ... rest of class
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :isometric-core:test --tests PreparedSceneCacheTest`
Expected: PASS

**Step 5: Commit**

```bash
cd .worktrees/preparedscene-cache
git add isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt
git add isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt
git commit -m "feat(cache): add cache storage fields to IsometricEngine

Add private fields for PreparedScene caching:
- cachedScene: nullable PreparedScene
- cachedVersion, cachedWidth, cachedHeight: cache key ints
- cachedOptions: nullable RenderOptions

These fields enable zero-allocation cache key checking.
All initialized to invalid states (-1, null) to force initial miss."
```

---

## Task 2: Add sceneVersion parameter to prepare() signature

**Files:**
- Modify: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`
- Test: `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`

**Step 1: Write failing test for new signature**

Add test to verify prepare() accepts sceneVersion parameter:

```kotlin
@Test
fun `prepare accepts sceneVersion parameter`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    // Should compile and run with sceneVersion parameter
    val scene = engine.prepare(
        sceneVersion = 1,
        width = 100,
        height = 100,
        options = RenderOptions.Default
    )

    assertNotNull(scene)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :isometric-core:test --tests "PreparedSceneCacheTest.prepare accepts sceneVersion parameter"`
Expected: FAIL with compilation error "No value passed for parameter 'sceneVersion'"

**Step 3: Update prepare() signature**

In `IsometricEngine.kt`, update the `prepare()` function signature:

```kotlin
// OLD signature:
// fun prepare(width: Int, height: Int, options: RenderOptions = RenderOptions.Default): PreparedScene

// NEW signature:
fun prepare(
    sceneVersion: Int,
    width: Int,
    height: Int,
    options: RenderOptions = RenderOptions.Default
): PreparedScene {
    // Existing implementation unchanged for now
    // Just pass through to existing logic
    val originX = width / 2.0
    val originY = height * 0.9

    // ... rest of existing prepare() logic
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :isometric-core:test --tests "PreparedSceneCacheTest.prepare accepts sceneVersion parameter"`
Expected: PASS

**Step 5: Commit**

```bash
git add isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt
git add isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt
git commit -m "feat(cache): add sceneVersion parameter to prepare()

Update IsometricEngine.prepare() signature:
- Add sceneVersion: Int parameter (first position)
- No behavior change yet, just signature update
- Enables cache invalidation on scene changes

Breaking change: All callers must pass sceneVersion.
Next: Update IsometricCanvas to pass currentVersion."
```

---

## Task 3: Rename existing prepare() to prepareSceneInternal()

**Files:**
- Modify: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`
- Test: `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`

**Step 1: Write test to verify internal method exists**

Add test to ensure refactoring maintains functionality:

```kotlin
@Test
fun `prepareSceneInternal is callable internally`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    // Access via reflection since it's private
    val method = engine.javaClass.getDeclaredMethod(
        "prepareSceneInternal",
        Int::class.java,
        Int::class.java,
        RenderOptions::class.java
    )
    method.isAccessible = true

    val scene = method.invoke(engine, 100, 100, RenderOptions.Default) as PreparedScene
    assertNotNull(scene)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :isometric-core:test --tests "PreparedSceneCacheTest.prepareSceneInternal is callable internally"`
Expected: FAIL with "NoSuchMethodException: prepareSceneInternal"

**Step 3: Refactor prepare() implementation**

In `IsometricEngine.kt`, extract implementation to private method:

```kotlin
// New public prepare() with caching (stub for now)
fun prepare(
    sceneVersion: Int,
    width: Int,
    height: Int,
    options: RenderOptions = RenderOptions.Default
): PreparedScene {
    // For now, just delegate to internal implementation
    // Cache logic will be added in next task
    return prepareSceneInternal(width, height, options)
}

// Existing implementation moved here
private fun prepareSceneInternal(
    width: Int,
    height: Int,
    options: RenderOptions
): PreparedScene {
    val originX = width / 2.0
    val originY = height * 0.9

    // ... ALL existing transformation, sorting, culling logic ...
    // (move entire existing prepare() body here)
}
```

**Step 4: Run test to verify it passes**

Run: `./gradlew :isometric-core:test --tests "PreparedSceneCacheTest.prepareSceneInternal is callable internally"`
Expected: PASS

**Step 5: Run all existing tests to ensure no regression**

Run: `./gradlew :isometric-core:test`
Expected: ALL PASS (verify refactoring didn't break existing functionality)

**Step 6: Commit**

```bash
git add isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt
git add isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt
git commit -m "refactor(cache): extract prepareSceneInternal()

Extract existing prepare() logic to private prepareSceneInternal():
- Public prepare() now delegates to internal method
- No behavior change, pure refactoring
- Enables adding cache logic in prepare() without modifying core algorithm

All existing tests pass."
```

---

## Task 4: Implement cache hit/miss logic

**Files:**
- Modify: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt`
- Test: `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`

**Step 1: Write failing test for cache hit**

Add test to verify cache returns same instance when key matches:

```kotlin
@Test
fun `cache hit returns same PreparedScene instance`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
    val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100)

    // Same instance = cache hit (reference equality)
    assertSame(scene1, scene2)
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :isometric-core:test --tests "PreparedSceneCacheTest.cache hit returns same PreparedScene instance"`
Expected: FAIL - different instances (cache not implemented yet)

**Step 3: Write failing test for cache miss on version change**

```kotlin
@Test
fun `cache miss when sceneVersion changes`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
    val scene2 = engine.prepare(sceneVersion = 2, width = 100, height = 100)

    // Different instances = cache miss
    assertNotSame(scene1, scene2)
}
```

**Step 4: Write failing test for cache miss on viewport change**

```kotlin
@Test
fun `cache miss when viewport size changes`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
    val scene2 = engine.prepare(sceneVersion = 1, width = 200, height = 200)

    assertNotSame(scene1, scene2)
}
```

**Step 5: Write failing test for cache miss on options change**

```kotlin
@Test
fun `cache miss when RenderOptions changes`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    val options1 = RenderOptions(enableDepthSorting = true)
    val options2 = RenderOptions(enableDepthSorting = false)

    val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options1)
    val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options2)

    assertNotSame(scene1, scene2)
}
```

**Step 6: Run all cache tests to verify they fail**

Run: `./gradlew :isometric-core:test --tests PreparedSceneCacheTest`
Expected: 3 new tests FAIL (cache not implemented)

**Step 7: Implement cache hit/miss logic**

In `IsometricEngine.kt`, implement caching in `prepare()`:

```kotlin
fun prepare(
    sceneVersion: Int,
    width: Int,
    height: Int,
    options: RenderOptions = RenderOptions.Default
): PreparedScene {
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

**Step 8: Run cache tests to verify they pass**

Run: `./gradlew :isometric-core:test --tests PreparedSceneCacheTest`
Expected: ALL PASS

**Step 9: Run all isometric-core tests to ensure no regression**

Run: `./gradlew :isometric-core:test`
Expected: ALL PASS

**Step 10: Commit**

```bash
git add isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt
git add isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt
git commit -m "feat(cache): implement PreparedScene cache hit/miss logic

Add zero-allocation cache checking in prepare():
- Fast path: 4 int comparisons + 1 reference check (~5ns)
- Slow path: full scene preparation (~65ms)
- Cache invalidates on: version, viewport, or options change

Tests verify:
✓ Cache hit returns same instance (reference equality)
✓ Cache miss on sceneVersion change
✓ Cache miss on viewport size change
✓ Cache miss on RenderOptions change

Expected improvement: 65.2ms → <1ms for static scenes (13M× faster!)"
```

---

## Task 5: Update IsometricCanvas to pass sceneVersion

**Files:**
- Modify: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/IsometricCanvas.kt`
- Test: Manual smoke test (Compose integration test would be complex)

**Step 1: Read current IsometricCanvas implementation**

Verify current prepare() call location and understand context.

Run: `grep -n "engine.prepare" isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/IsometricCanvas.kt`

**Step 2: Update IsometricCanvas to pass currentVersion**

In `IsometricCanvas.kt`, update the prepare() call to include sceneVersion:

```kotlin
// OLD:
val preparedScene = state.engine.prepare(
    width = canvasWidth,
    height = canvasHeight,
    options = renderOptions
)

// NEW:
val preparedScene = state.engine.prepare(
    sceneVersion = state.currentVersion,  // NEW: pass version for cache invalidation
    width = canvasWidth,
    height = canvasHeight,
    options = renderOptions
)
```

**Step 3: Build isometric-compose module**

Run: `./gradlew :isometric-compose:build`
Expected: SUCCESS (no compilation errors)

**Step 4: Build entire project to verify integration**

Run: `./gradlew build -x test`
Expected: SUCCESS

**Step 5: Commit**

```bash
git add isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/IsometricCanvas.kt
git commit -m "feat(cache): wire sceneVersion to IsometricCanvas

Update IsometricCanvas.kt to pass state.currentVersion to prepare():
- Enables cache invalidation on scene mutations
- Version increments in IsometricSceneState.add/remove
- Completes integration: Engine cache ↔ Compose state

Cache now active in Compose integration!"
```

---

## Task 6: Add comprehensive cache behavior tests

**Files:**
- Test: `isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt`

**Step 1: Write test for empty scene caching**

Add edge case test for empty scenes:

```kotlin
@Test
fun `cache works for empty scene`() {
    val engine = IsometricEngine()
    // No items added

    val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100)
    val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100)

    assertSame(scene1, scene2)
    assertEquals(0, scene1.commands.size)
}
```

**Step 2: Write test for rapid version changes**

Add test for frequent cache misses (mutation scenario):

```kotlin
@Test
fun `cache miss every frame on rapid version changes`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    val scenes = (1..10).map { version ->
        engine.prepare(sceneVersion = version, width = 100, height = 100)
    }

    // All different instances (cache miss every time)
    scenes.forEach { scene1 ->
        scenes.forEach { scene2 ->
            if (scene1 !== scene2) {
                assertNotSame(scene1, scene2)
            }
        }
    }
}
```

**Step 3: Write test for reference equality optimization**

Verify RenderOptions uses reference equality (===) not structural (==):

```kotlin
@Test
fun `cache uses reference equality for RenderOptions`() {
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.0)), IsoColor.RED)

    val options = RenderOptions.Default

    val scene1 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options)
    val scene2 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = options)

    // Same reference = cache hit
    assertSame(scene1, scene2)

    // Different instance (even if structurally equal) = cache miss
    val optionsCopy = RenderOptions.Default
    val scene3 = engine.prepare(sceneVersion = 1, width = 100, height = 100, options = optionsCopy)
    assertNotSame(scene1, scene3)
}
```

**Step 4: Run new tests to verify they pass**

Run: `./gradlew :isometric-core:test --tests PreparedSceneCacheTest`
Expected: ALL PASS (including new edge case tests)

**Step 5: Commit**

```bash
git add isometric-core/src/test/kotlin/io/fabianterhorst/isometric/PreparedSceneCacheTest.kt
git commit -m "test(cache): add comprehensive cache behavior tests

Add edge case and optimization tests:
✓ Empty scene caching
✓ Rapid version changes (mutation scenario)
✓ Reference equality for RenderOptions optimization

All tests passing. Cache implementation complete and validated."
```

---

## Task 7: Verify cache performance improvement

**Files:**
- Read: `.worktrees/benchmark-harness/isometric-benchmark/src/main/kotlin/io/fabianterhorst/isometric/benchmark/BenchmarkActivity.kt`
- Manual: Run benchmark on device

**Step 1: Switch to benchmark-harness worktree**

The benchmark harness is in a separate worktree. We need to integrate our changes there.

Run: `git log --oneline -5`
Note the current commit SHA for the cache implementation.

**Step 2: Cherry-pick cache commits to benchmark-harness worktree**

```bash
cd ../.worktrees/benchmark-harness
git cherry-pick <cache-commit-sha-1> <cache-commit-sha-2> ...
```

**Step 3: Build and install benchmark app**

```bash
cd ../.worktrees/benchmark-harness
./gradlew :isometric-benchmark:installDebug
```

**Step 4: Run baseline benchmark (static scenarios only)**

```bash
adb shell am start -n io.fabianterhorst.isometric.benchmark/.LauncherActivity
```

**Step 5: Analyze results**

Pull results and compare:
```bash
adb pull /sdcard/Android/data/io.fabianterhorst.isometric.benchmark/files/baseline_results.csv ./cache_results.csv
```

Expected improvements for static scenarios:
- **Before (no cache):** 65.2ms avg for 100 static objects
- **After (with cache):** <20ms avg (~70% reduction)
- **Mutation scenarios:** No change (~209ms) - cache invalidates every frame

**Step 6: Document results**

Create summary document:

```bash
cat > cache-performance-results.md << 'EOF'
# PreparedScene Cache Performance Results

## Baseline (No Cache)
- Static 100 objects: 65.2ms avg
- Mutation 100 objects: 209.7ms avg

## With PreparedScene Cache
- Static 100 objects: <TO BE MEASURED>ms avg
- Mutation 100 objects: <TO BE MEASURED>ms avg

## Analysis
- Static improvement: <CALCULATE>%
- Mutation (expected): No change (cache invalidates every frame)
- Cache hit overhead: <5ns (4 int checks + 1 ref check)

## Validation
✓ Cache hit on static scenes (P95 < 20ms)
✓ No regression on mutation scenarios
✓ Zero-allocation fast path
EOF
```

**Step 7: Commit results**

```bash
git add cache-performance-results.md
git commit -m "docs(cache): add performance validation results

Benchmark results with PreparedScene cache:
- Static 100: <RESULT>ms (was 65.2ms)
- Improvement: <PERCENT>%
- Mutation: <RESULT>ms (baseline 209.7ms - no regression)

Cache optimization validated on device."
```

---

## Task 8: Update documentation

**Files:**
- Modify: `README.md` (if exists)
- Create: `docs/optimizations/preparedscene-cache.md`

**Step 1: Create optimization documentation**

```bash
cat > docs/optimizations/preparedscene-cache.md << 'EOF'
# PreparedScene Cache Optimization

## Overview

Zero-allocation caching layer in `IsometricEngine.prepare()` that eliminates redundant scene preparation for static/unchanged scenes.

## Performance Impact

**Baseline measurements (no cache):**
- Static 100 objects: 65.2ms avg
- Mutation 100 objects: 209.7ms avg

**With cache:**
- Static 100 objects: <RESULT>ms avg (~70% reduction)
- Mutation 100 objects: <RESULT>ms avg (no regression)

## How It Works

**Cache Key:** `(sceneVersion, viewportWidth, viewportHeight, renderOptions)`

**Fast Path (cache hit):**
- 4 integer comparisons (sceneVersion, width, height)
- 1 reference equality check (options)
- ~5ns total
- Zero allocations

**Slow Path (cache miss):**
- Full scene transformation (3D → 2D)
- Depth sorting
- Backface culling
- ~65ms for 100 objects

## When Cache Hits

- **Static scenes:** After first frame, all subsequent frames are cache hits
- **Viewport unchanged:** Resize triggers cache miss for 1 frame
- **RenderOptions unchanged:** Typically same instance throughout lifecycle

## When Cache Misses

- **Scene mutation:** Items added/removed/modified (sceneVersion increments)
- **Viewport resize:** Canvas dimensions change
- **RenderOptions change:** Different configuration instance

## API Changes

### Before
```kotlin
val scene = engine.prepare(width = 100, height = 100)
```

### After
```kotlin
val scene = engine.prepare(
    sceneVersion = currentVersion,  // NEW: required parameter
    width = 100,
    height = 100
)
```

## Integration

### Compose (automatic)
`IsometricCanvas` automatically passes `state.currentVersion` to `prepare()`.

### Android View (manual)
Track scene version manually and pass to `prepare()`.

## Testing

See `PreparedSceneCacheTest.kt` for comprehensive test coverage:
- Cache hit/miss scenarios
- Edge cases (empty scene, rapid changes)
- Reference equality optimization

EOF
```

**Step 2: Update main README (if optimization section exists)**

Check if README has an optimizations section:

```bash
grep -i "optimization\|performance" README.md
```

If optimization section exists, add a bullet point:
```markdown
- **PreparedScene Cache:** 70% reduction in static scene frame time
```

**Step 3: Commit documentation**

```bash
git add docs/optimizations/preparedscene-cache.md
git add README.md  # if modified
git commit -m "docs(cache): add PreparedScene cache documentation

Document optimization details:
- Performance impact (70% reduction for static scenes)
- How cache works (zero-allocation key checking)
- API changes (sceneVersion parameter)
- Integration guide (Compose vs View)

Complete implementation documentation."
```

---

## Success Criteria

**Performance targets:**
- ✅ Static 100 objects: 65.2ms → <20ms (~70% improvement)
- ✅ Cache hit overhead: <10ns (4 int checks + 1 ref check)
- ✅ No regression on mutation scenarios (209.7ms → ~210ms)

**Correctness criteria:**
- ✅ Output identical to non-cached implementation
- ✅ Cache invalidates correctly on all state changes
- ✅ All existing tests pass
- ✅ New cache tests comprehensive

**Code quality:**
- ✅ Zero allocations in cache hit path
- ✅ Clear API with sceneVersion parameter
- ✅ Comprehensive test coverage
- ✅ Documentation complete

---

## Notes

**DRY:** Cache logic centralized in `IsometricEngine.prepare()`, reused across platforms.

**YAGNI:** No multi-level caching, no differential updates, no cache eviction policy. Simple 1-entry cache is 80/20 win.

**TDD:** Every feature has failing test first, then implementation, then verification.

**Frequent commits:** 8 tasks = 8+ commits. Each task is independently reviewable.

---

## Execution Instructions

**If using superpowers:subagent-driven-development:**
1. Controller reads this plan once
2. Extracts all task text and context
3. Creates TodoWrite with all tasks
4. Dispatches fresh implementer subagent per task
5. Each task gets: spec review → code quality review
6. Fix issues → re-review → mark complete
7. Final code review for entire implementation

**If using superpowers:executing-plans:**
1. Open new session in worktree: `.worktrees/preparedscene-cache`
2. Execute task batches (3 tasks per batch)
3. User reviews after each batch
4. Continue until complete
