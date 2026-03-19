# WS3 Implementation Review — Naming & Readability Cleanup

> **Date**: 2026-03-15
> **Scope**: All uncommitted changes on `feat/api-changes` implementing WS3 (naming & readability cleanup)
> **Plan ref**: `docs/plans/2026-03-14-ws3-naming-readability.md`
> **Files reviewed**: 25 files (6 core source, 5 compose source, 3 test, 5 app/sample, 5 docs, 1 benchmark)
> **Build**: All modules compile. All unit tests pass.
> **Second pass**: 2026-03-15 — full re-read of all 25 files + documentation audit; 8 new findings added

---

## Summary

| | WS3 |
|---|---|
| **Verdict** | Clean, well-executed rename workstream. 1 bug, 3 missed renames, 1 deviation (improvement), 2 cosmetic issues |
| **Architecture** | Pure rename/refactor — no behavioral changes except planned PathNode translate guard removal |
| **Test coverage** | All existing tests updated. No new tests needed (renames are structural) |
| **Plan adherence** | High. All 6 steps executed. One unplanned rename (`Performance` → `NoDepthSorting`) is a net improvement |

---

## Step-by-Step Verification

### Step 1: Preset Rename — `Quality` → `NoCulling` (F15) ✅

**File**: `RenderOptions.kt`

Both presets renamed:
- `Quality` → `NoCulling` ✅ (per plan)
- `Performance` → `NoDepthSorting` ✅ (DEVIATION — see W3-D1)

KDoc updated from subjective "Quality mode / Performance mode" to descriptive "Disable backface culling..." / "Disable depth sorting...". Good improvement.

All 19 test references updated across `IsometricEngineTest`, `IsometricRendererTest`, `IsometricRendererPathCachingTest`, `IsometricRendererNativeCanvasTest`.

**Grep verification**: Zero remaining references to `RenderOptions.Quality` or `RenderOptions.Performance` in `*.kt` files. ✅

---

### Step 2a: `options` → `renderOptions` (F31) ✅

**File**: `IsometricEngine.kt` — parameter renamed in `projectScene()` (combined with Step 3a method rename).

**Call sites**:
- `IsometricCanvas.kt`: Named arg `options = renderOptions` → `renderOptions = renderOptions` ✅
- `IsometricRenderer.kt`: Named arg `options = context.renderOptions` → `renderOptions = context.renderOptions` ✅
- All positional callers: unchanged (correct — positional args don't depend on param name)

Internal references in method body: `options.enableBackfaceCulling` → `renderOptions.enableBackfaceCulling` etc., all updated in the extracted `projectAndCull()` method. ✅

---

### Step 2b: `viewportWidth/Height` → `width/height` (F32) ✅

**File**: `PreparedScene.kt` — fields, equals, hashCode, toString all updated.

**Call sites**:
- `IsometricEngine.kt`: Constructor call uses positional args — no change needed ✅
- `IsometricRenderer.kt`: `cachedPreparedScene!!.viewportWidth/Height` → `.width/.height` ✅
- `IsometricView.kt`: `cachedScene?.viewportWidth/Height` → `.width/.height` ✅
- `IsometricEngineTest.kt`: `scene.viewportWidth/Height` → `.width/.height` ✅
- `IsometricRendererTest.kt`: `renderer.currentPreparedScene!!.viewportWidth/Height` → `.width/.height` ✅

**Correctly NOT renamed**:
- `BenchmarkOrchestrator.viewportWidth/Height` — local fields, not PreparedScene accessors ✅
- `InteractionSimulator` parameters — local params ✅
- `BenchmarkScreen` — reads from `RuntimeFlagSnapshot.canvasWidth/Height`, not PreparedScene ✅

---

### Step 2c: `id` → `commandId` (F33) ✅

**File**: `RenderCommand.kt` — field, equals, hashCode, toString all updated.

**Call sites** (verified exhaustively):
- `IsometricEngine.kt`: `id = transformedItem.item.id` → `commandId = transformedItem.item.id` ✅ (note: `item.id` is `SceneItem.id`, correctly NOT renamed)
- `IsometricNode.kt`: All three node types (ShapeNode, PathNode, BatchNode) — `id =` → `commandId =` ✅
- `IsometricRenderer.kt`: 9 references — `command.id`, `hit.id`, map lookups — all updated to `command.commandId`, `hit.commandId` ✅
- `IsometricEngineTest.kt`: 8 references — `it.id` → `it.commandId` in assertions ✅
- `IsometricNodeRenderTest.kt`: 2 references — `cmd.id` → `cmd.commandId` ✅
- `PrimitiveLevelsExample.kt` (`MultiShapeNode`): `id =` → `commandId =` ✅
- `README.md`: `item.id` → `item.commandId` ✅
- `RUNTIME_API.md`: `item.id` → `item.commandId` ✅
- `ComposeActivity.kt`: `item.id` → `item.commandId` ✅

**Grep verification**: Zero remaining `command.id`, `hit.id`, or `it.id` references on `RenderCommand` in source files. ✅

---

### Step 2d: `shape` → `geometry` in Composable (F34) ✅

**File**: `IsometricComposables.kt` — parameter, KDoc, factory, and update block all updated.

**Call sites**: All `Shape(shape = Prism(...))` → `Shape(geometry = Prism(...))` in:
- `RuntimeApiActivity.kt` (21 calls) ✅
- `PrimitiveLevelsExample.kt` (high-level calls only) ✅
- `OptimizedPerformanceSample.kt` (2 calls) ✅
- `BenchmarkScreen.kt` (1 call) ✅
- `IsometricRendererNativeCanvasTest.kt` (1 call) ✅

**Correctly NOT renamed**:
- `ShapeNode(shape = ...)` constructor calls — these use the node's internal `shape` property, not the composable parameter. The plan explicitly says "ShapeNode.shape property is NOT renamed". ✅
- Low-level `ComposeNode<ShapeNode>` examples in `PrimitiveLevelsExample.kt` (lines 52, 104, 132) — use `ShapeNode` constructor ✅

---

### Step 3a: `prepare()` → `projectScene()` (F38) ✅

**File**: `IsometricEngine.kt` — method declaration renamed.

**Call sites**:
- `IsometricRenderer.kt`: `engine.prepare(...)` → `engine.projectScene(...)` ✅
- `IsometricCanvas.kt`: Both call sites updated ✅
- `IsometricView.kt`: Updated ✅
- `IsometricEngineTest.kt`: All 20 calls updated ✅
- KDoc in `IsometricRenderer.kt`: Two references updated ✅

### MISSED W3-M1: `BenchmarkFlags.kt` KDoc still references `engine.prepare()`

**Severity**: Low (documentation only)
**File**: `isometric-benchmark/.../BenchmarkFlags.kt` line 10

```
@property enablePreparedSceneCache PreparedScene caching across frames (skips engine.prepare() when scene is unchanged)
```

Should be `engine.projectScene()`.

### MISSED W3-M2: `IsometricEngine.kt` KDoc still says "Prepare the scene"

**Severity**: Low (documentation only)
**File**: `isometric-core/.../IsometricEngine.kt` lines 91-93

```kotlin
/**
 * Prepare the scene for rendering at the given viewport size
 * Returns a platform-agnostic PreparedScene with sorted render commands
 */
fun projectScene(
```

The KDoc should reflect the new method name. Suggested: "Project the 3D scene to 2D screen space at the given viewport size".

---

### Step 3b: `invalidate()` → `clearCache()` (F38) ✅

**File**: `IsometricRenderer.kt` — method declaration renamed.

**Call sites**:
- `IsometricRenderer.kt`: `if (forceRebuild) invalidate()` → `clearCache()` ✅
- `IsometricRendererTest.kt`: `renderer.invalidate()` → `renderer.clearCache()` ✅
- `IsometricRendererPathCachingTest.kt`: `renderer.invalidate()` → `renderer.clearCache()` ✅

**Correctly NOT renamed**:
- `IsometricView.invalidate()` — these are `android.view.View.invalidate()` calls (request redraw), NOT the renderer's cache-clearing method. Different API entirely. ✅

### OBSERVATION W3-O1: Test assertion message still says "invalidate"

**Severity**: Cosmetic
**File**: `IsometricRendererPathCachingTest.kt` line 75

```kotlin
assertEquals("invalidate should clear cached paths", 0, cachedPathCount(renderer))
```

Should say `"clearCache should clear cached paths"`.

---

### Step 3c: `clearDirty()` → `markClean()` (F38) ✅

**File**: `IsometricNode.kt` — method declaration + recursive call updated.

**Call sites**:
- `IsometricRenderer.kt`: `rootNode.clearDirty()` → `rootNode.markClean()` ✅
- `IsometricRendererTest.kt`: 4 calls updated ✅

**Grep verification**: Zero remaining `clearDirty()` references in `*.kt` files. ✅

---

### Step 4: Boolean Naming Standardization (F29) — No changes needed ✅

As the plan determined, all remaining booleans already use the `enable*` prefix after WS2 removed `drawStroke`/`useNativeCanvas` from the public API into typed wrappers. Correctly skipped.

---

### Step 5: Sample Updates to Named Arguments (F35) — Partial

The plan called for converting all positional constructor arguments to named arguments in samples (e.g., `Prism(position = Point(...), width = 4.0, depth = 5.0, height = 2.0)`). The implementation only applied the `shape` → `geometry` rename at call sites. Positional numeric arguments remain as-is:

```kotlin
// Current (unchanged)
Shape(
    geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0),
    color = IsoColor(33.0, 150.0, 243.0)
)
```

vs the plan's target:
```kotlin
Shape(
    geometry = Prism(
        position = Point(x = 0.0, y = 0.0, z = 0.0),
        width = 2.0, depth = 2.0, height = 2.0
    ),
    color = IsoColor(r = 33.0, g = 150.0, b = 243.0)
)
```

### MISSED W3-M3: Named arguments not applied to sample constructor calls

**Severity**: Low (readability, not correctness)
**Files**: `RuntimeApiActivity.kt`, `PrimitiveLevelsExample.kt`, `OptimizedPerformanceSample.kt`, `ComposeActivity.kt`

The `Prism(Point(...), 2.0, 2.0, 2.0)` positional calls still have three unlabeled doubles. The plan (Step 5) specifically targeted these for named argument conversion. This was the primary readability improvement for end-user sample code.

**Note**: `ViewSampleActivity.kt` is not in the modified file list, suggesting it wasn't touched at all (plan listed it in Step 5e).

---

### Step 6a: `projectAndCull()` extraction (F39) ✅

**File**: `IsometricEngine.kt`

The `mapNotNull` lambda body (12 lines with `return@mapNotNull null` early returns) correctly extracted to `private fun projectAndCull()`. The `projectScene()` method body is now clean:

```kotlin
val transformedItems = items.mapNotNull { item ->
    projectAndCull(item, originX, originY, renderOptions, normalizedLight, width, height)
}
```

Extracted method signature matches the plan. Parameters correctly passed. Logic identical — backface culling, bounds checking, lighting, wrapped in a nullable return. ✅

---

### Step 6b: Render nesting reduction (F39) ✅

**File**: `IsometricRenderer.kt` — `render()` method

The plan's early-return pattern was applied correctly:

```kotlin
val paths = if (enablePathCaching) cachedPaths else null
if (paths == null) {
    cachedPreparedScene?.let { scene -> renderPreparedScene(scene, ...) }
    benchmarkHooks?.onDrawEnd()
    return
}
// Fast path at top level — no nesting
for (i in paths.indices) { ... }
```

This eliminates one nesting level and the `cachedPaths!!` assertion. ✅

---

### Step 6c: `hitTestSpatial()` extraction (F39) ✅

**File**: `IsometricRenderer.kt`

The 4-level nested spatial index code was correctly extracted to `private fun hitTestSpatial()`. The new method uses early returns for empty checks:

```kotlin
private fun hitTestSpatial(x: Double, y: Double): IsometricNode? {
    val index = spatialIndex ?: return null
    val candidateIds = index.query(x, y, HIT_TEST_RADIUS_PX)
    if (candidateIds.isEmpty()) return null
    // ...
}
```

**Improvement over plan**: The implementation uses `val preparedScene = cachedPreparedScene ?: return null` instead of `cachedPreparedScene!!`, which is safer. The original code had `cachedPreparedScene!!.viewportWidth` inside a nested block that was only reachable if `enableSpatialIndex && spatialIndex != null`, but there was no formal guarantee that `cachedPreparedScene` was non-null in that context. The null-safe check is better.

The main `hitTest()` method is now clean:

```kotlin
if (enableSpatialIndex) {
    hitTestSpatial(x, y)?.let { return it }
}
// Linear fallback
val hit = engine.findItemAt(...) ?: return null
return findNodeByCommandId(hit.commandId)
```

---

### Step 6d: `applyLocalTransforms()` extraction (F39) ✅

**File**: `IsometricNode.kt`

Two overloads extracted to the base `IsometricNode` class:
- `protected fun applyLocalTransforms(shape: Shape): Shape`
- `protected fun applyLocalTransforms(path: Path): Path`

All three node types (`ShapeNode`, `PathNode`, `BatchNode`) now use the shared method instead of duplicating 9 lines each. Total: 27 lines of duplication eliminated.

**Behavioral note** (acknowledged in plan): `PathNode` previously had a guard:
```kotlin
if (position.x != 0.0 || position.y != 0.0 || position.z != 0.0) {
    transformedPath = transformedPath.translate(...)
}
```
The shared method unconditionally calls `translate()`. Translating by (0,0,0) is a no-op that creates a new Path with identical points. This is functionally identical but allocates an extra Path object when position is zero. The plan explicitly noted this and accepted it.

---

## Findings

### DEVIATION W3-D1: `Performance` → `NoDepthSorting` (unplanned)

**Severity**: Positive (improvement)
**File**: `RenderOptions.kt`

The plan only scoped Step 1 to rename `Quality` → `NoCulling`. The implementation also renamed `Performance` → `NoDepthSorting`. The plan mentioned `Performance` by name as an analogy:

> *"This is the same naming pattern as `Performance` (which disables depth sorting) — descriptive of what changes, not a subjective quality judgment."*

The implementation applied the same reasoning to `Performance` itself — `NoDepthSorting` is strictly more descriptive. The KDoc was also improved. No action needed.

All call sites updated: `IsometricEngineTest.kt` (2 references), `IsometricRendererTest.kt` (1 reference), `MIGRATION.md` (1 reference). ✅

---

### BUG W3-B1: `RUNTIME_API.md` indentation error

**Severity**: Low (documentation only)
**File**: `docs/RUNTIME_API.md` lines 310-315

```markdown
```kotlin
IsometricScene {
        Shape(
            geometry = Prism(Point(0.0, 0.0, 0.0)),
            color = IsoColor(33.0, 150.0, 243.0)
        )
}
```‌
```

The `Shape(...)` call is indented 8 spaces (double-indented) instead of 4 spaces. In the original, it was correctly at 4 spaces. The rename tool likely introduced the extra indentation.

**Fix**:
```kotlin
IsometricScene {
    Shape(
        geometry = Prism(Point(0.0, 0.0, 0.0)),
        color = IsoColor(33.0, 150.0, 243.0)
    )
}
```

---

### MISSED W3-M1: `BenchmarkFlags.kt` KDoc still references `engine.prepare()`

**Severity**: Low (documentation)
**File**: `isometric-benchmark/.../BenchmarkFlags.kt` line 10

The `@property enablePreparedSceneCache` KDoc says `"skips engine.prepare() when scene is unchanged"`. Should be `engine.projectScene()`.

---

### MISSED W3-M2: `IsometricEngine.projectScene()` KDoc still says "Prepare the scene"

**Severity**: Low (documentation)
**File**: `isometric-core/.../IsometricEngine.kt` lines 91-93

The method was renamed but the KDoc first line still reads "Prepare the scene for rendering". Should be "Project the 3D scene to 2D screen space" or similar.

---

### MISSED W3-M3: Named arguments not applied to sample constructor calls (Step 5)

**Severity**: Low (readability)
**Files**: All sample files

Step 5 of the plan called for converting positional numeric arguments to named arguments:
```kotlin
// Plan target
Prism(position = Point(x = 0.0, y = 0.0, z = 0.0), width = 2.0, depth = 2.0, height = 2.0)

// Current (unchanged)
Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0)
```

This step appears to have been skipped entirely. Three unlabeled `Double` positional arguments remain throughout all samples.

---

### OBSERVATION W3-O1: Test assertion message references old name "invalidate"

**Severity**: Cosmetic
**File**: `IsometricRendererPathCachingTest.kt` line 75

```kotlin
assertEquals("invalidate should clear cached paths", 0, cachedPathCount(renderer))
```

### OBSERVATION W3-O2: Test assertion messages reference old name "viewportWidth/Height"

**Severity**: Cosmetic
**File**: `IsometricRendererTest.kt` lines 657, 659

```kotlin
assertEquals("viewportWidth should be updated after hitTest with new size", ...)
assertEquals("viewportHeight should be updated after hitTest with new size", ...)
```

The field was renamed to `width`/`height` but the assertion string messages weren't updated.

---

### OBSERVATION W3-O3: Research/review docs not updated

**Severity**: Cosmetic (non-user-facing)

The following research/review documents still reference `prepare()`, `invalidate()`, `clearDirty()`, or old preset names. These are historical reference documents, not user-facing API docs, so updating them is optional:

- `docs/research/WEBGPU_ANALYSIS.md` — `prepare()` (5 refs), `clearDirty()` (1 ref)
- `docs/research/TEXTURE_SHADER_RESEARCH.md` — `prepare()` (3 refs)
- `docs/research/PHYSICS_RESEARCH.md` — `clearDirty()` (1 ref)
- `docs/reviews/physics-plan-review-7.md` — `engine.prepare()` (3 refs)
- `docs/reviews/physics-plan-review-9.md` — `clearDirty()` (1 ref)
- `docs/reviews/physics-plan-review-14.md` — `clearDirty()` (1 ref)
- `docs/reviews/2026-03-11-spatial-index-implementation-review.md` — `invalidate()` (2 refs)
- `docs/plans/2026-03-14-ws5-architecture-modularity.md` — `invalidate()` (4 refs)
- `docs/plans/2026-03-12-phase3-*.md` — `invalidate()` (4 refs)

**Recommendation**: Leave these as-is. They are historical records. The active user-facing docs (`RUNTIME_API.md`, `MIGRATION.md`, `PERFORMANCE_OPTIMIZATIONS.md`, `README.md`) were all correctly updated.

---

### OBSERVATION W3-O4: README.md feature bullet changed wording

**Severity**: Cosmetic

```diff
-- 💾 Path caching enabled by default (30-40% less GC)
+- 💾 Prepared-scene caching enabled by default
```

The old text was inaccurate (path caching defaults to `enablePathCaching = false`). The new text is factually correct — prepared-scene caching IS enabled by default. However, the old "30-40% less GC" metric was about path caching, which is still available as an opt-in. The new text drops the metric entirely. This is fine — the metric was misleading in context.

---

### OBSERVATION W3-O5: Trailing newlines added to several files

Multiple sample files (`OptimizedPerformanceSample.kt`, `PrimitiveLevelsExample.kt`, `RuntimeApiActivity.kt`, `ComposeActivity.kt`) had a trailing newline added. `README.md` had its missing trailing newline fixed (the `\ No newline at end of file` is gone). These are standard formatting cleanups.

---

## Correctness Matrix

| Area | Status | Notes |
|---|---|---|
| `Quality` → `NoCulling` rename | PASS | All 19 test refs + 2 source refs updated |
| `Performance` → `NoDepthSorting` rename | PASS | All refs updated (deviation from plan) |
| `options` → `renderOptions` param rename | PASS | Named call sites updated, positional unaffected |
| `viewportWidth/Height` → `width/height` | PASS | PreparedScene fields + all accessors |
| `id` → `commandId` | PASS | All RenderCommand field accesses updated |
| `shape` → `geometry` composable param | PASS | Composable + all high-level call sites |
| `ShapeNode.shape` NOT renamed | PASS | Node-internal property preserved |
| `prepare()` → `projectScene()` | PASS | All call sites + most KDoc refs |
| `invalidate()` → `clearCache()` | PASS | All source call sites |
| `clearDirty()` → `markClean()` | PASS | All source call sites |
| `projectAndCull()` extraction | PASS | Identical logic, cleaner structure |
| `hitTestSpatial()` extraction | PASS | Improved null-safety over original |
| `applyLocalTransforms()` extraction | PASS | 27 lines dedup; PathNode zero-translate guard removed (planned) |
| Render nesting reduction | PASS | Early-return pattern eliminates one nesting level |
| Benchmark `viewportWidth/Height` NOT renamed | PASS | Local fields correctly identified |
| `IsometricView.invalidate()` NOT renamed | PASS | Android `View.invalidate()`, not renderer method |

---

## Cross-Boundary Analysis: WS3 ↔ WS1/WS2

WS3 renames APIs established in WS1 and consumed by WS2. The following checks verify the three workstreams interact correctly.

### Boundary Check Results

| # | Question | Result | Details |
|---|----------|--------|---------|
| 1 | Does WS2's `AdvancedSceneConfig.renderOptions` default still work after `Quality` → `NoCulling`? | **PASS** | `AdvancedSceneConfig` defaults to `RenderOptions.Default`, not `Quality`/`NoCulling`. No dependency on the renamed constant. |
| 2 | Does WS2's `IsometricScene.kt` use `projectScene()` correctly? | **PASS** | `IsometricScene` doesn't call the engine directly — it delegates to `IsometricRenderer`, which calls `engine.projectScene()`. The rename chain: `IsometricScene` → `renderer.render()` → `renderer.rebuildCache()` → `engine.projectScene()`. All links updated. |
| 3 | Does WS2's `StrokeStyle` interact with the `commandId` rename? | **PASS** | `StrokeStyle` doesn't access `RenderCommand.commandId`. It's consumed by the renderer during the draw phase, not the hit-test/command-ID phase. No interaction. |
| 4 | Does WS2's `GestureConfig.onTap` callback flow through `hitTestSpatial()`? | **PASS** | Tap → `renderer.hitTest()` → `hitTestSpatial()` (if spatial index enabled) → `findNodeByCommandId(hit.commandId)`. The rename from `hit.id` → `hit.commandId` is correctly applied in the extracted method. |
| 5 | Does WS1's `HitOrder` enum work with the renamed `commandId` in `findItemAt()`? | **PASS** | `findItemAt()` returns a `RenderCommand` — the caller accesses `.commandId` to get the ID. WS1 changed the `findItemAt` signature (HitOrder enum), WS3 changed what the caller reads from the result (`.id` → `.commandId`). Both changes applied, no conflict. |
| 6 | Does WS1's `PreparedScene` (converted from data class) work with the `width/height` rename? | **PASS** | WS1 converted PreparedScene to a regular class with explicit equals/hashCode. WS3 renames the fields within that class. The explicit equals/hashCode correctly use the new field names. |
| 7 | Do WS2's config types pass `clearCache()` correctly? | **PASS** | `AdvancedSceneConfig.forceRebuild` → `renderer.forceRebuild = config.forceRebuild` → `if (forceRebuild) clearCache()`. The rename only affects the method name, not the boolean flag. |
| 8 | Does WS1's covariant `translate()` work with `applyLocalTransforms()`? | **PASS** | `applyLocalTransforms(shape)` calls `shape.translate(...)`. WS1 made `Shape.translate()` open and added covariant overrides (Prism.translate() returns Prism). The shared method stores the result as `Shape`, which is correct — covariant return types are widened to the base type. |
| 9 | Do the extracted helper methods (`projectAndCull`, `hitTestSpatial`, `applyLocalTransforms`) access any WS2-introduced state? | **PASS** | `projectAndCull` is on `IsometricEngine` (core module, no WS2 dependency). `hitTestSpatial` accesses `spatialIndex`, `commandIdMap`, `commandOrderMap`, `cachedPreparedScene` — all pre-WS2 state. `applyLocalTransforms` reads `position`, `rotation`, `scale` — all pre-WS2 node properties. |
| 10 | Does the `geometry` rename in the composable interact with WS2's `SceneConfig`/`AdvancedSceneConfig`? | **PASS** | The composable parameter name (`geometry`) is independent of config types. `SceneConfig` and `AdvancedSceneConfig` don't reference composable parameter names. |

### Boundary Verdict

**No cross-boundary issues.** WS3 is a pure naming layer that renames identifiers consumed across module boundaries, and all consumption sites are updated consistently. The three workstreams (WS1 types, WS2 config, WS3 names) compose cleanly.

---

## Action Items

| ID | Severity | Description |
|---|---|---|
| W3-B1 | Low | Fix `RUNTIME_API.md` Example 1 indentation — `Shape` is double-indented (8 spaces instead of 4) |
| W3-M1 | Low | Update `BenchmarkFlags.kt` KDoc: `engine.prepare()` → `engine.projectScene()` |
| W3-M2 | Low | Update `IsometricEngine.projectScene()` KDoc: "Prepare the scene" → "Project the 3D scene to 2D screen space" |
| W3-M3 | Low | Apply named arguments to sample constructor calls per Step 5 (optional — readability only) |
| W3-O1 | Cosmetic | Update `IsometricRendererPathCachingTest` assertion message: "invalidate" → "clearCache" |
| W3-O2 | Cosmetic | Update `IsometricRendererTest` assertion messages: "viewportWidth/Height" → "width/height" |
| W3-T1 | Low | `IsometricEngineTest`: comment "Use Quality mode" still references old name (line 22) |
| W3-T2 | Low | `IsometricEngineTest`: 2 test function names still say "prepare" instead of "projectScene" (lines 32, 134) |
| W3-D1 | Medium | `PERFORMANCE_OPTIMIZATIONS.md`: 7 code blocks use old flat `IsometricScene` params (`useNativeCanvas`, `enablePathCaching`, `onTap = { x, y, node ->`) that don't exist on the current API |
| W3-D2 | Low | `RUNTIME_API.md`, `README.md`: ~15 `Shape()` calls in doc examples use positional args instead of `geometry =` |
| W3-D3 | Low | `OPTIMIZATION_SUMMARY.md`: code block uses old flat `IsometricScene(enablePathCaching = true, ...)` params |
| W3-D4 | Low | `IsometricEngine.sortPaths()` private method still uses param name `options` instead of `renderOptions` |

---

## Second Pass Findings (2026-03-15)

Full re-read of all 25 modified files, all sample/app files verified for correctness, all documentation code examples audited.

### Source Code Verdict

**All Kotlin source files are correct.** Zero missed renames in compiled code. The three agents verified:
- Every `RenderCommand` field access uses `commandId` (not `id`)
- Every `PreparedScene` field access uses `width`/`height` (not `viewportWidth`/`viewportHeight`)
- Every `IsometricEngine` call uses `projectScene()` (not `prepare()`)
- Every `IsometricRenderer` cache clear uses `clearCache()` (not `invalidate()`)
- Every node dirty reset uses `markClean()` (not `clearDirty()`)
- Every high-level `Shape()` composable call uses `geometry =` (not `shape =`)
- Every `ShapeNode()` constructor call correctly retains `shape =` (node-internal, not renamed)
- `applyLocalTransforms()` is used consistently in all three node types
- `hitTestSpatial()` extraction is correct with improved null-safety
- `projectAndCull()` extraction is behaviorally identical to inline original

### Sample/App Verification

| File | Status | Notes |
|---|---|---|
| `RuntimeApiActivity.kt` | ✅ CLEAN | All `geometry =`, correct `TapEvent`/`DragEvent` usage, `Modifier.fillMaxSize()` present |
| `PrimitiveLevelsExample.kt` | ✅ CLEAN | High-level `Shape()` uses `geometry =`; low-level `ShapeNode()` uses `shape =`; `MultiShapeNode.render()` uses `commandId =` |
| `OptimizedPerformanceSample.kt` | ✅ CLEAN | `geometry =`, `AdvancedSceneConfig`, `GestureConfig` all correct |
| `ComposeActivity.kt` | ✅ CLEAN | Uses `IsometricCanvas` API with `item.commandId` |
| `BenchmarkScreen.kt` | ✅ CLEAN | `geometry =` on Shape composable |

---

### BUG W3-T1: `IsometricEngineTest` comment references old "Quality" name

**Severity**: Low (test comment)
**File**: `isometric-core/src/test/.../IsometricEngineTest.kt` line 22

```kotlin
// Use Quality mode (no culling) to get all faces
val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)
```

The code is correct (`NoCulling`), but the comment still says "Quality mode".

---

### BUG W3-T2: Two test function names still say "prepare" instead of "projectScene"

**Severity**: Low (test names)
**File**: `isometric-core/src/test/.../IsometricEngineTest.kt`

```kotlin
// Line 32:
fun `prepare generates correct viewport dimensions`()

// Line 134:
fun `prepare without lightDirection uses engine default`()
```

Both test bodies correctly call `engine.projectScene()`, but the function names still say "prepare". JUnit test names serve as documentation — these should match the current API.

---

### BUG W3-D1 (Significant): `PERFORMANCE_OPTIMIZATIONS.md` has 7 code blocks using old API that doesn't exist

**Severity**: Medium (user-facing docs with non-compilable examples)
**File**: `docs/PERFORMANCE_OPTIMIZATIONS.md`

Multiple code examples pass flat parameters directly to `IsometricScene()` that were removed in WS2. These examples won't compile against the current API:

**Lines 309-313** — `useNativeCanvas` as flat param:
```kotlin
IsometricScene(
    useNativeCanvas = true  // ❌ Not a parameter on IsometricScene
) { ... }
```

**Lines 322-330** — Multiple flat params + old gesture callback:
```kotlin
IsometricScene(
    enablePathCaching = true,         // ❌ AdvancedSceneConfig field
    enableSpatialIndex = true,        // ❌ AdvancedSceneConfig field
    useNativeCanvas = true,           // ❌ AdvancedSceneConfig field
    enableOffThreadComputation = true, // ❌ Doesn't exist anywhere
    onTap = { x, y, node ->           // ❌ Old callback signature
        println("Tapped: $node")
    }
) { ... }
```

**Lines 423-431** — Same pattern repeated in "Best Practices" section:
```kotlin
IsometricScene(
    useNativeCanvas = true  // ❌
) { ... }

IsometricScene(
    useNativeCanvas = true,
    enableOffThreadComputation = true  // ❌
) { ... }
```

**Line 487** — Troubleshooting section:
```kotlin
IsometricScene(useNativeCanvas = useNative) { ... }  // ❌
```

These should all use `config = SceneConfig(...)` or `config = AdvancedSceneConfig(...)` with the appropriate properties.

Additionally, `enableOffThreadComputation` doesn't exist in the codebase at all — it appears to be aspirational/documented-ahead-of-implementation.

---

### BUG W3-D2: `README.md` and `RUNTIME_API.md` doc examples use positional args instead of `geometry =`

**Severity**: Low (compiles but inconsistent)
**Files**: `README.md`, `docs/RUNTIME_API.md`

Approximately 15 `Shape()` calls in documentation code examples use positional arguments:

**README.md examples** (lines 163, 267, 271, 296, 312, 323, 327, 384, 390):
```kotlin
Shape(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
```

**RUNTIME_API.md migration examples** (lines 536, 565-566):
```kotlin
Shape(Prism(...), color1)
```

These compile (positional args are valid Kotlin), but every `.kt` sample file now uses `geometry =` as a named argument. The docs are inconsistent with the code.

Note: Some of these are in "overview" or "concept" blocks where brevity is intentional (e.g., `Shape(Prism(...), color)` on README line 163 is a one-liner summary). The migration examples on `RUNTIME_API.md` should be explicit since they're teaching the new API.

---

### BUG W3-D3: `OPTIMIZATION_SUMMARY.md` uses old flat `IsometricScene` parameters

**Severity**: Low (user-facing docs)
**File**: `docs/OPTIMIZATION_SUMMARY.md`

The file was only partially updated — the `strokeStyle` parameter change was applied (line 98) but the surrounding code blocks still reference the old flat API. Reviewing the full file wasn't in WS3's scope since `OPTIMIZATION_SUMMARY.md` wasn't in the plan's file list, but since it was modified, the inconsistency was introduced.

---

### OBSERVATION W3-D4: `IsometricEngine.sortPaths()` private method uses `options` param name

**Severity**: Low (internal)
**File**: `isometric-core/.../IsometricEngine.kt` line 304

```kotlin
private fun sortPaths(items: List<TransformedItem>, options: RenderOptions): List<TransformedItem> {
```

The private method parameter is still named `options` while the public `projectScene()` uses `renderOptions`. This is a private method and not a public API concern, but it's an internal inconsistency that could confuse future readers.

---

### Revised Correctness Matrix (Second Pass)

| Area | Status | Notes |
|---|---|---|
| All `.kt` source files | **PASS** | Zero missed renames in compiled code |
| All `.kt` test files | **PASS** | Logic correct; 1 stale comment, 2 stale test names |
| All app/sample `.kt` files | **PASS** | All `geometry =`, all config types correct |
| `README.md` code examples | **PARTIAL** | `geometry =` on first example ✅; positional args in ~10 other examples |
| `RUNTIME_API.md` code examples | **PARTIAL** | API signatures updated ✅; some migration examples use positional args |
| `PERFORMANCE_OPTIMIZATIONS.md` | **FAIL** | 7 code blocks use old flat `IsometricScene` API that won't compile |
| `OPTIMIZATION_SUMMARY.md` | **PARTIAL** | `strokeStyle` updated; other code blocks still reference old API |
| `MIGRATION.md` | **PASS** | `NoDepthSorting` correctly updated |
| `PRIMITIVE_LEVELS.md` | **PASS** | `geometry =` on composable calls; `shape =` on `ShapeNode` calls |
