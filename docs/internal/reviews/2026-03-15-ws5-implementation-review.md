# WS5 Implementation Review — Architecture & Modularity

> **Date**: 2026-03-15
> **Scope**: Commit `d261389` on `feat/api-changes` implementing WS5
> **Plan ref**: `docs/plans/2026-03-14-ws5-architecture-modularity.md`
> **Methodology**: Fresh-eyes review of `git diff d261389^..d261389` against plan spec, full file reads for all new/modified files, code boundary and behavioral analysis
> **Passes**: 3 (initial diff review + deep behavioral/correctness review + final correctness verification)

---

## Summary

17 files changed (+867 −812). 6 of 8 plan steps are represented in the commit. The core decomposition goals (SceneProjector interface, engine facade, renderer slimming, accumulator render pattern, conversion deduplication, infix operators) are correctly implemented. Step 1a (Java collection replacement) and Step 8b (`api` → `implementation`) were skipped; Step 7 was partially completed (2 of 4 planned extractions); Step 8a (`:lib` module removal) was partially completed (deregistered from build but directory not deleted).

Pass 1 identified **0 must-fix** and **5 should-fix** findings. Pass 2 performed deep correctness analysis of the extracted collaborators and identified **3 additional should-fix** findings (S6–S8), including a behavioral regression in the engine's constructor-to-`projectScene` light direction wiring. Pass 3 performed final correctness verification across 4 dimensions (engine math equivalence, compose-side extraction correctness, test coverage, edge cases/contract violations) and identified **1 additional should-fix** finding (S9) plus significant test coverage gaps. The decomposition is sound at the architectural level — the facade pattern preserves backward compatibility while enabling independent testing of collaborators. The accumulator pattern correctly eliminates intermediate list allocations.

---

## Finding Index

| # | Severity | Step | Title |
|---|----------|------|-------|
| S1 | Should | 1a | Java collection constructors (`HashMap`, `HashSet`, `ArrayList`) not replaced in `DepthSorter` |
| S2 | Should | 5 | `AdvancedSceneConfig.engine` still typed as concrete `IsometricEngine`, not `SceneProjector` |
| S3 | Should | 8a | `lib/` directory still exists on disk (only deregistered from `settings.gradle`) |
| S4 | Should | 7 | `SceneCache` / `HitTestResolver` extractions skipped — `IsometricRenderer` still 492 lines |
| S5 | Should | 8b | `api(project(":isometric-core"))` not tightened to `implementation` (no justification documented) |
| S6 | Should | 5 | `IsometricEngine.defaultLightDirection` is dead code; constructor `lightDirection` silently ignored by `projectScene()` |
| S7 | Should | 6d | `HitTester.buildConvexHull()` is misnamed — not a convex hull, incorrect for non-quad polygons |
| S8 | Should | 7a | `SpatialGrid.query()` early-return guard is dead code — post-clamp check is always false |
| S9 | Should | 3 | Misleading `points = emptyList()` comments — "Will be filled by engine" implies mutation of immutable `val` |

---

## S1 — Java Collection Constructors Not Replaced (Should-fix)

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/DepthSorter.kt` lines 105, 121, 122

**Problem**: Step 1a targets zero-arg Java collection constructors for replacement with Kotlin factories. These three lines were moved verbatim from `IsometricEngine` to `DepthSorter` without conversion:

```kotlin
val grid = HashMap<Long, MutableList<Int>>()   // → hashMapOf<Long, MutableList<Int>>()
val seen = HashSet<Long>()                     // → hashSetOf<Long>()
val pairs = ArrayList<Pair<Int, Int>>()        // → mutableListOf<Pair<Int, Int>>()
```

The plan explicitly notes that capacity-arg `HashMap(initialCapacity)` in `IsometricRenderer.buildCommandMaps()` should be kept (performance optimization). These three are zero-arg constructors — the Kotlin factories produce identical backing types.

**Fix**: Mechanical replacement, zero behavioral change.

---

## S2 — `AdvancedSceneConfig.engine` Still Concrete Type (Should-fix)

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/AdvancedSceneConfig.kt` line 18

**Current**:
```kotlin
val engine: IsometricEngine = IsometricEngine(),
```

**Problem**: Step 5c correctly changed `IsometricRenderer`'s constructor to accept `SceneProjector` (line 48). This enables testing with fake projectors. But `AdvancedSceneConfig` still pins the concrete `IsometricEngine` type, so `IsometricScene` — the only production code path that creates renderers — can only receive concrete engines:

```kotlin
// IsometricScene.kt line 76:
val engine = remember(config.engine) { config.engine }
// engine is IsometricEngine, not SceneProjector
```

The interface benefit is currently limited to direct `IsometricRenderer` construction (used in tests and benchmarks). The composable entry point doesn't benefit.

**Fix**: Change `AdvancedSceneConfig.engine` type to `SceneProjector` with the same default:

```kotlin
val engine: SceneProjector = IsometricEngine(),
val onEngineReady: ((SceneProjector) -> Unit)? = null,
```

Consumers who need engine-specific features can downcast. Consumers who want fakes can provide them through `IsometricScene`.

**Note**: This also affects `onEngineReady: ((IsometricEngine) -> Unit)?` — the callback type should match. The `LaunchedEffect` and `SideEffect` blocks in `IsometricScene.kt` that reference `engine` would need no changes since they already only call `SceneProjector` methods.

Additionally, 5 files still reference `IsometricEngine.DEFAULT_LIGHT_DIRECTION` instead of `SceneProjector.DEFAULT_LIGHT_DIRECTION`:
- `AdvancedSceneConfig.kt`, `CompositionLocals.kt`, `RenderContext.kt`, `SceneConfig.kt`, `IsometricScene.kt`

These work because `IsometricEngine.DEFAULT_LIGHT_DIRECTION` delegates to `SceneProjector.DEFAULT_LIGHT_DIRECTION`, but referencing the interface constant directly is more consistent with the abstraction.

---

## S3 — `lib/` Directory Not Deleted (Should-fix)

**File**: `settings.gradle` (line removed) + `lib/` directory (still exists)

**Problem**: Step 8a correctly removed `include ':lib'` from `settings.gradle`, so the module is no longer part of the build. But the `lib/` directory with its Java source files (`Isometric.java`, `Color.java`, `Shape.java`, etc.), build config, screenshots, and Eclipse project files still exists on disk.

The plan says: *"Delete the entire directory."*

A developer cloning the repo still sees the `lib/` directory and may be confused about which implementation is current.

**Fix**: `rm -rf lib/` (or `git rm -r lib/`). The directory contains only legacy Java sources that duplicate `isometric-core`. If historical preservation is desired, the files are still in git history.

---

## S4 — `SceneCache` / `HitTestResolver` Not Extracted (Should-fix)

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricRenderer.kt` (492 lines)

**Problem**: Step 7 planned 4 extractions from `IsometricRenderer`:

| Extraction | Plan Target | Actual |
|-----------|-------------|--------|
| 7a. `SpatialGrid` + `ShapeBounds` | Top-level `internal` class | Done — `SpatialGrid.kt` (94 lines) |
| 7b. `SceneCache` | Encapsulate all cache logic | Not done |
| 7c. `HitTestResolver` | Encapsulate hit-test resolution | Not done |
| 7d. `NativeSceneRenderer` | Isolate Android-native rendering | Done — `NativeSceneRenderer.kt` (113 lines) |

The plan targeted ~100 lines for `IsometricRenderer` after all 4 extractions. With only 7a and 7d done, the renderer is 492 lines — down from the pre-WS5 ~750 lines, but still well above the target. It still owns:

- All cache fields (lines 101-118)
- `ensurePreparedScene()` (lines 297-315)
- `rebuildCache()` (lines 323-376)
- `buildPathCache()`, `buildCommandMaps()`, `buildSpatialIndex()`, `buildNodeIdMap()` (lines 378-491)
- `hitTest()` and `hitTestSpatial()` (lines 221-475)
- `findNodeByCommandId()` (lines 477-479)

**Impact**: The renderer is functional and correct — this is a structural cleanliness concern, not a bug. The remaining extractions would enable independent testing of cache logic with fake projectors, and isolate hit-test resolution for unit testing without rendering.

**Fix**: Extract `SceneCache` and `HitTestResolver` per the plan. This is a pure refactoring with no behavioral change.

---

## S5 — `api` to `implementation` Not Addressed (Should-fix)

**File**: `isometric-compose/build.gradle.kts` line 49

**Current**:
```kotlin
api(project(":isometric-core"))
```

**Problem**: Step 8b discusses changing this to `implementation` to reduce autocomplete noise for consumers. The plan ultimately recommends keeping `api` because `Shape`, `Point`, `IsoColor`, etc. appear in composable signatures and consumers need them. But the plan asks for an explicit decision.

Neither action was taken — the line is unchanged, and no justification is documented.

**Recommended resolution**: Keep `api(project(":isometric-core"))` as the plan suggests, and add a comment explaining why:

```kotlin
// api (not implementation) because Shape, Point, IsoColor, Vector etc. appear in
// composable signatures — consumers need direct access to core types.
api(project(":isometric-core"))
```

The plan's recommendation is sound: all decomposed internal types (`SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester`) are `internal`, so they don't leak through `api`. The only visible types are the ones consumers need.

---

## S6 — `defaultLightDirection` Dead Code / Constructor Behavioral Regression (Should-fix)

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/IsometricEngine.kt` line 38

**Current**:
```kotlin
class IsometricEngine(
    ...
    private val lightDirection: Vector = DEFAULT_LIGHT_DIRECTION,
    ...
) : SceneProjector {
    ...
    private val defaultLightDirection: Vector = lightDirection.normalize()  // ← stored but never read
```

**Problem**: Pre-WS5, `projectScene()` was a non-override method with default:

```kotlin
// Pre-WS5:
fun projectScene(..., lightDirection: Vector = this.defaultLightDirection): PreparedScene
```

Post-WS5, `projectScene()` overrides the `SceneProjector` interface, which defines its own default:

```kotlin
// SceneProjector interface:
fun projectScene(..., lightDirection: Vector = DEFAULT_LIGHT_DIRECTION.normalize()): PreparedScene
```

Since Kotlin override methods inherit default parameter values from the interface, the constructor's `lightDirection` parameter no longer feeds `projectScene()`. The stored `defaultLightDirection` field is computed at construction but never read — **dead code**.

**Behavioral regression**: `IsometricEngine(lightDirection = Vector(1, 0, 0))` followed by `engine.projectScene(800, 600)` used the constructor's light direction pre-WS5 but ignores it post-WS5 (uses `Vector(2, -1, 3).normalize()` instead). No current call site triggers this — every `IsometricEngine()` in the codebase uses the default constructor — but the constructor parameter creates a false expectation.

**Fix** (two options):

1. **Remove the constructor parameter**: Delete `lightDirection` from the constructor and `defaultLightDirection` from the body. This is the cleanest approach since callers pass the light direction explicitly via `projectScene()` or `RenderContext` in the compose path.
2. **Wire it through**: Have `IsometricEngine.projectScene()` declare its own default `= defaultLightDirection` instead of inheriting from the interface. This requires the method to declare the default explicitly (which Kotlin allows alongside an interface default).

Option 1 is preferred — it eliminates the dead code and the misleading constructor parameter. The `require(lightDirection.magnitude() > 0.0)` validation in `init` also becomes dead.

---

## S7 — `HitTester.buildConvexHull()` is Misnamed and Incorrect for Complex Polygons (Should-fix)

**File**: `isometric-core/src/main/kotlin/io/fabianterhorst/isometric/HitTester.kt` lines 61–94

**Problem**: The method is named `buildConvexHull` but does not compute a convex hull. It collects the four axis-extreme points (leftmost, topmost, rightmost, bottommost) and any additional points sharing those extreme coordinates, returning them in insertion order via `distinct()`. This is a bounding-extrema collector, not a convex hull algorithm.

**Pre-existing**: This code was written in M1 (`08680c0`) and has never been a real convex hull. WS5 moved it verbatim from `IsometricEngine` to `HitTester` without correction. The extraction gave it a named class (`HitTester`) that makes the incorrect function name more prominent.

**Why it works today**: All isometric faces produced by the engine are quads (4 vertices) or triangles (3 vertices). For a convex quadrilateral, the four axis-extreme points *are* the four vertices, and `[left, top, right, bottom]` produces a non-self-intersecting diamond winding. `IntersectionUtils.isPointInPoly()` uses ray-casting which handles any simple polygon — so the ordering works for symmetric and near-symmetric parallelograms.

**Where it breaks**:

1. **Non-convex polygons with 5+ vertices** — Interior vertices are discarded. The resulting polygon is an inflated bounding approximation, producing false-positive hits in concavity regions. Any user-defined `Path` with non-convex geometry would be hit-tested incorrectly.
2. **Asymmetric parallelograms** — For highly skewed quads where extrema points are non-adjacent in the actual winding order, `[left, top, right, bottom]` *could* form a self-intersecting bowtie. In practice this is unlikely for typical isometric projections but is geometrically possible.

**Fix** (simplest correct approach): Skip the hull step entirely and pass `command.points` directly to `isPointInPoly`. The projected points are already in correct winding order from the engine. This eliminates both the incorrect "hull" computation and the `hull.map { Point(it.x, it.y, 0.0) }` per-command allocation:

```kotlin
fun findItemAt(...): RenderCommand? {
    ...
    for (command in commandsList) {
        val hullPoints = command.points.map { Point(it.x, it.y, 0.0) }
        val isInside = if (touchRadius > 0.0) { ... } else { ... }
        ...
    }
}
```

The `Point2D` → `Point` conversion remains necessary because `IntersectionUtils` accepts `List<Point>`, not `List<Point2D>`. Fixing that type mismatch is a separate concern (not in WS5 scope).

---

## S8 — `SpatialGrid.query()` Early-Return Guard is Dead Code (Should-fix)

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/SpatialGrid.kt` lines 81–83

**Current**:
```kotlin
fun query(x: Double, y: Double, radius: Double = 0.0): List<String> {
    val minCol = max(0, floor((x - radius) / cellSize).toInt())
    val maxCol = min(cols - 1, floor((x + radius) / cellSize).toInt())
    val minRow = max(0, floor((y - radius) / cellSize).toInt())
    val maxRow = min(rows - 1, floor((y + radius) / cellSize).toInt())

    if (maxRow < 0 || maxCol < 0 || minRow >= rows || minCol >= cols) {  // ← dead code
        return emptyList()
    }
    ...
```

**Problem**: The four index variables are clamped *before* the bounds check:

- `minCol` is always `≥ 0` (via `max(0, ...)`)
- `maxCol` is always `≤ cols - 1` (via `min(cols - 1, ...)`)
- `minRow` is always `≥ 0`, `maxRow` is always `≤ rows - 1`

Therefore `maxRow < 0` is always false, `maxCol < 0` is always false, `minRow >= rows` is always false, `minCol >= cols` is always false. The early-return condition can **never** trigger.

**Consequence**: Off-screen queries (e.g., `query(10000.0, 10000.0, 0.0)`) don't short-circuit. Instead, clamping forces all indices to the grid boundary (e.g., `minRow = maxRow = rows - 1`, `minCol = maxCol = cols - 1`), and the loop scans the corner cell, returning whatever IDs happen to be there. This is functionally incorrect — it may return false candidates for points far outside the viewport.

**Fix**: Check bounds on the raw (pre-clamp) values:

```kotlin
fun query(x: Double, y: Double, radius: Double = 0.0): List<String> {
    val rawMinCol = floor((x - radius) / cellSize).toInt()
    val rawMaxCol = floor((x + radius) / cellSize).toInt()
    val rawMinRow = floor((y - radius) / cellSize).toInt()
    val rawMaxRow = floor((y + radius) / cellSize).toInt()

    // Early return for queries entirely outside the grid
    if (rawMaxCol < 0 || rawMinCol >= cols || rawMaxRow < 0 || rawMinRow >= rows) {
        return emptyList()
    }

    val minCol = max(0, rawMinCol)
    val maxCol = min(cols - 1, rawMaxCol)
    val minRow = max(0, rawMinRow)
    val maxRow = min(rows - 1, rawMaxRow)
    ...
```

This also benefits `insert()` — which has the same clamping pattern but is saved by the `minCol > maxCol` guard downstream. For consistency, `insert()` could adopt the same pre-clamp check.

---

## S9 — Misleading "Will be filled by engine" Comments on Immutable Field (Should-fix)

**File**: `isometric-compose/src/main/kotlin/io/fabianterhorst/isometric/compose/runtime/IsometricNode.kt` lines 191, 219

**Current**:
```kotlin
// ShapeNode.renderTo() line 191:
output.add(RenderCommand(
    points = emptyList(), // Will be filled by engine
    ...
))

// PathNode.renderTo() line 219:
output.add(RenderCommand(
    points = emptyList(), // Will be filled by engine
    ...
))
```

**Problem**: The comment "Will be filled by engine" implies the engine mutates the `points` field of the `RenderCommand` object. But `RenderCommand.points` is a `val` (immutable):

```kotlin
// RenderCommand.kt:
data class RenderCommand(
    val points: List<Point2D>,
    ...
)
```

The actual flow is a **template-replacement pattern**: nodes emit template `RenderCommand` objects with empty points. `IsometricRenderer.rebuildCache()` extracts `originalPath`, `color`, `commandId`, and `ownerNodeId` from these templates, calls `engine.add()` with those values, then calls `engine.projectScene()` which creates **entirely new** `RenderCommand` objects with real projected points. The templates are discarded — never mutated.

**Why it matters**: A developer reading the comment would expect `points` to be populated on the same object, creating confusion about the immutability contract. The misleading comment is especially dangerous because `BatchNode.renderTo()` (line ~238) uses the same pattern without the misleading comment — inconsistency suggests intentionality.

**Fix**: Replace the comments to describe the actual pattern:

```kotlin
points = emptyList(), // Template — engine.projectScene() produces new commands with projected points
```

Or simply remove the comment — the `originalPath` and `originalShape` fields already signal that projection is pending.

---

## Code Boundary Analysis

### Boundary 1: `SceneProjector` Interface × `IsometricEngine` Facade × Collaborators

**Flow**: `IsometricEngine` implements `SceneProjector` and delegates to 4 `internal` collaborators:

```
SceneProjector (interface)
    └── IsometricEngine (facade, public)
            ├── SceneGraph (internal) — mutable scene state
            ├── IsometricProjection (internal) — 3D→2D, lighting, culling
            ├── DepthSorter (internal object) — depth sorting
            └── HitTester (internal object) — hit testing
```

**Correctness**: The facade correctly preserves the pre-decomposition API surface. All 4 `override` methods on `IsometricEngine` delegate to collaborators without adding logic. The `projectAndCull()` private method orchestrates projection → culling → lighting — this is the only method that touches multiple collaborators, and it's correctly kept in the engine (orchestration responsibility).

**Visibility**: All collaborators are `internal` — invisible outside `isometric-core`. The only public API is `IsometricEngine` (via `SceneProjector` interface) and the existing data types (`PreparedScene`, `RenderCommand`, etc.). This achieves the plan's goal of reducing autocomplete noise.

**Type leakage**: `DepthSorter.TransformedItem` and `SceneGraph.SceneItem` are `internal data class`es that cross the engine↔collaborator boundary within the module. This is correct — they're implementation types shared between `internal` classes.

### Boundary 2: `IsometricRenderer` × `NativeSceneRenderer` Delegation

**Flow**: `IsometricRenderer` creates `NativeSceneRenderer` eagerly at line 121. The `renderNative()` method delegates:

```kotlin
with(nativeRenderer) {
    renderNative(scene, strokeStyle, onRenderError)
}
```

**Correctness**: The `onRenderError` callback is passed as a parameter, not set as a field on `NativeSceneRenderer`. This means the native renderer is stateless w.r.t. error handling — if the callback changes between frames (via `SideEffect`), the latest value is always used. This is better than the pre-WS5 design where the callback was read from a field on `IsometricRenderer`.

**Lazy paint initialization**: `fillPaint` and `strokePaint` use `by lazy {}` to avoid `UnsatisfiedLinkError` when constructing on non-Android JVM. This pattern was preserved from the pre-WS5 renderer — correct.

**Lifecycle**: `NativeSceneRenderer` holds no disposable resources (Paint objects are cheap). The `close()` method on `IsometricRenderer` does NOT call anything on `nativeRenderer` — this is correct since there's nothing to clean up.

### Boundary 3: Accumulator Pattern (`renderTo`) × Tree Traversal

**Flow**: `IsometricRenderer.rebuildCache()` creates a single `mutableListOf<RenderCommand>()` and passes it to `rootNode.renderTo(commands, context)`. The root node (GroupNode) iterates `childrenSnapshot` and calls `child.renderTo(output, childContext)` recursively. Each leaf node adds directly to the shared list.

**Allocation analysis** (scene with 50 shapes × 6 faces = 300 commands):

| Pattern | List allocations | Iterator allocations |
|---------|-----------------|---------------------|
| Old (`render()` + `flatMap`) | ~350 (1 per node + intermediate) | ~50 (flatMap iterators) |
| New (`renderTo()` + `for`) | 1 (the shared output list) | 0 (indexed `for` loops) |

**Error recovery**: If a node's `renderTo()` throws, the exception propagates to `rebuildCache()`'s try/catch. The partially-filled `commands` list is abandoned — the previous cache is preserved. The `output` list is a local variable in `rebuildCache()`, so no cleanup is needed.

**Breaking change**: `render()` → `renderTo()` changes the abstract method signature on `IsometricNode`. Any external subclass (e.g., `MultiShapeNode` in `PrimitiveLevelsExample.kt`) must update. The diff shows this was done correctly — `MultiShapeNode` now uses `renderTo()` with `output.add()` instead of returning lists.

### Boundary 4: `RenderExtensions.kt` Deduplication

**Flow**: `toComposePath()` is now defined once in `RenderExtensions.kt` (package `io.fabianterhorst.isometric.compose`). It's used by:

1. `IsometricRenderer.buildPathCache()` (path caching) — via import
2. `IsometricRenderer.renderPreparedScene()` (uncached fallback) — via import

`toComposeColor()` lives in `ColorExtensions.kt` (created by an earlier WS). Both are top-level extension functions — no `object` wrapper, no `with()` dispatch.

**Verification**: `grep -r "private fun.*toComposePath\|private fun.*toComposeColor"` returns zero results — all private duplicates are eliminated. The canonical definitions are the only ones.

### Boundary 5: `DepthSorter` Statefulness

**Design**: `DepthSorter` is an `internal object` (singleton). All methods are pure functions — `sort()` takes `items` and `options`, returns sorted items. No mutable state. This is correct for a utility that's called from `IsometricEngine.projectScene()`.

**Thread safety**: Since `DepthSorter` is stateless, it's inherently thread-safe. Multiple engine instances can call `DepthSorter.sort()` concurrently without contention. The `HashMap`, `HashSet`, and `ArrayList` inside `buildBroadPhaseCandidatePairs()` are local variables — no shared state.

### Boundary 6: `IsometricProjection` Statefulness

**Design**: `IsometricProjection` is an `internal class` (not object) because it stores the `transformation` matrix and `scale` computed from constructor parameters. The KDoc correctly says "Stateless after construction — all methods are pure functions."

**Plan deviation**: The plan's constructor includes `lightDirection` and stores `normalizedLightDirection`. The actual implementation does NOT store the light direction — it receives it as a method parameter on `transformColor()`. This is **better** than the plan: the projection truly has no mutable state, and the light direction can change per-frame without recreating the projection object. `IsometricEngine` creates the `IsometricProjection` once and reuses it across all `projectScene()` calls with different light directions.

### Boundary 7: `SceneProjector.DEFAULT_LIGHT_DIRECTION` Placement

**Design**: The default light direction is now canonical on `SceneProjector.Companion`:

```kotlin
interface SceneProjector {
    companion object {
        val DEFAULT_LIGHT_DIRECTION: Vector = Vector(2.0, -1.0, 3.0)
    }
}
```

`IsometricEngine.Companion.DEFAULT_LIGHT_DIRECTION` delegates:

```kotlin
val DEFAULT_LIGHT_DIRECTION: Vector = SceneProjector.DEFAULT_LIGHT_DIRECTION
```

**Backward compatibility**: Code referencing `IsometricEngine.DEFAULT_LIGHT_DIRECTION` still works. The delegation ensures the same object identity. Five files reference `IsometricEngine.DEFAULT_LIGHT_DIRECTION` (AdvancedSceneConfig, CompositionLocals, RenderContext, SceneConfig, IsometricScene) — they could reference `SceneProjector.DEFAULT_LIGHT_DIRECTION` for interface-consistency, but the current delegation is functionally equivalent.

---

## Positive Observations

### What was done well:

1. **Accumulator pattern (Step 3)** — Clean conversion from `render(): List<RenderCommand>` to `renderTo(output: MutableList<RenderCommand>)`. Every node type (`GroupNode`, `ShapeNode`, `PathNode`, `BatchNode`) plus the external `MultiShapeNode` in the sample app were correctly updated. `GroupNode` replaced `flatMap` with a `for` loop. `ShapeNode` and `BatchNode` replaced `map`/`flatMapIndexed` with indexed `for` loops + `output.add()`. The test helper `collectCommands()` wraps the new pattern cleanly.

2. **Infix operator usage (Step 4)** — `IsometricProjection.transformColor()` replaces 30 lines of raw component math with 5 lines of readable geometry: `Vector.fromTwoPoints()`, `edge1 cross edge2`, `normal dot lightDirection`. The mathematical intent is immediately clear.

3. **Engine decomposition (Step 6)** — `IsometricEngine` went from 491 lines to ~95 lines. Four focused collaborators (`SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester`) each own a single concern. The facade pattern preserves the single-class ergonomics while making each concern independently testable.

4. **`SceneProjector` interface (Step 5)** — Clean interface with the right method set (`add`, `clear`, `projectScene`, `findItemAt`). The `DEFAULT_LIGHT_DIRECTION` placement on the companion is idiomatic. The `projectScene` default parameter for `lightDirection` uses the companion constant.

5. **`NativeSceneRenderer` extraction (Step 7d)** — Android-specific code is cleanly isolated. The `onRenderError` is passed as a parameter (not a field), maintaining the WS4 error-handling pattern. The `TODO(KMP)` comments on both the class and the extension functions correctly flag the KMP migration target.

6. **`SpatialGrid` extraction (Step 7a)** — The spatial grid and `ShapeBounds`/`getBounds()` are in a focused file. The `NaN` guard on `getBounds()` and the clamping in `insert()`/`query()` are preserved from the original code.

7. **Test updates** — `IsometricNodeRenderTest` correctly introduces a `collectCommands()` helper that wraps the new accumulator pattern, keeping tests readable. `IsometricRendererTest`'s anonymous `badNode` overrides are updated to `renderTo()`. All 8 test call sites updated.

8. **`fillMaxWidth()` addition** — The sample activities (`ComposeActivity`, `RuntimeApiActivity`) gained `.fillMaxWidth()` modifiers on `IsometricScene` inside weighted columns. This fixes a subtle layout issue where the Canvas would have zero intrinsic width inside a `weight(1f)` column, preventing rendering.

---

## Step-by-Step Coverage Verification

| Plan Step | Finding(s) | Status | Notes |
|-----------|-----------|--------|-------|
| Step 1a: Java collections (F69) | — | Not done | `HashMap`, `HashSet`, `ArrayList` in `DepthSorter` → **S1** |
| Step 1b: Member extensions (F68) | — | N/A | WS4 already deleted `ComposeRenderer.kt`; `RenderExtensions.kt` achieves the intent |
| Step 2: Deduplicate conversions (F50) | — | Done | `RenderExtensions.kt` with shared `toComposePath()`; `ColorExtensions.kt` with `toComposeColor()` |
| Step 3: Accumulator render (F77) | — | Done | `render()` → `renderTo()`, all nodes + caller + tests updated |
| Step 4: Infix operators (F52) | — | Done | `cross`/`dot` in `IsometricProjection.transformColor()` |
| Step 5: `SceneProjector` interface (F53) | — | Done | Interface extracted, `IsometricEngine` implements, `IsometricRenderer` accepts interface |
| Step 6: Decompose engine (F48) | — | Done | `SceneGraph`, `IsometricProjection`, `DepthSorter`, `HitTester` extracted |
| Step 7: Decompose renderer (F49) | — | Partial | `NativeSceneRenderer` + `SpatialGrid` extracted; `SceneCache` + `HitTestResolver` skipped → **S4** |
| Step 8a: Remove `:lib` (F54) | — | Partial | `settings.gradle` updated; directory not deleted → **S3** |
| Step 8b: `api` → `implementation` (F55) | — | Not done | No change, no justification → **S5** |

---

## File Change Inventory

| File | Plan says | Actual | Match? |
|------|-----------|--------|--------|
| `SceneProjector.kt` | NEW (Step 5) | Created | Yes |
| `SceneGraph.kt` | NEW (Step 6a) | Created | Yes |
| `IsometricProjection.kt` | NEW (Step 6b) | Created | Yes (improved — no stored light direction) |
| `DepthSorter.kt` | NEW (Step 6c) | Created | Yes |
| `HitTester.kt` | NEW (Step 6d) | Created | Yes |
| `IsometricEngine.kt` | Slim to ~80 lines | ~95 lines | Yes (close to target) |
| `RenderExtensions.kt` | NEW (Step 2a) | Created | Yes (`toComposePath()` only — `toComposeColor()` in separate `ColorExtensions.kt`) |
| `NativeSceneRenderer.kt` | NEW (Step 7d) | Created | Yes (improved — uses `StrokeStyle` + `onRenderError`) |
| `SpatialGrid.kt` | NEW (Step 7a) | Created | Yes (includes `ShapeBounds` + `getBounds()`) |
| `SceneCache.kt` | NEW (Step 7b) | **Not created** | S4 |
| `HitTestResolver.kt` | NEW (Step 7c) | **Not created** | S4 |
| `IsometricNode.kt` | Step 3 | Modified | Yes |
| `IsometricRenderer.kt` | Steps 2, 3, 5, 7 | Modified | S4 (still 492 lines vs ~100 target) |
| `IsometricNodeRenderTest.kt` | Step 3 (test update) | Modified | Yes |
| `IsometricRendererTest.kt` | Step 3 (test update) | Modified | Yes |
| `PrimitiveLevelsExample.kt` | Step 3 (external node) | Modified | Yes |
| `ComposeActivity.kt` | Not in plan | `fillMaxWidth()` added | Yes (bonus fix) |
| `RuntimeApiActivity.kt` | Not in plan | `fillMaxWidth()` added | Yes (bonus fix) |
| `settings.gradle` | Step 8a | `:lib` removed | S3 (directory not deleted) |
| `isometric-compose/build.gradle.kts` | Step 8b | **Not changed** | S5 |
| `lib/` directory | Step 8a (DELETE) | **Not deleted** | S3 |

---

## Pass 2: Behavioral Correctness Analysis

The second pass performed deep correctness analysis of the extracted collaborators, focusing on hot-path allocations, spatial index edge cases, hit-testing geometry, and the interface extraction's effect on constructor contracts.

### Analysis 1: `projectScene()` Default Parameter Wiring Break

Pre-WS5, `projectScene`'s default `lightDirection` was `this.defaultLightDirection` — the constructor's normalized value. Post-WS5, the `SceneProjector` interface defines the default as `DEFAULT_LIGHT_DIRECTION.normalize()`, and Kotlin's override semantics silently adopt the interface default. This orphaned the stored `defaultLightDirection` field and changed the behavior of `projectScene()` for any engine constructed with a non-default light direction. No current code triggers this (all engines use `IsometricEngine()`), but the constructor parameter is now a lie → **S6**.

### Analysis 2: Depth Sort Allocation Cost (Pre-existing, Not Introduced by WS5)

`DepthSorter.checkDepthDependency()` (lines 86–88) creates `Point` objects and lists from `Point2D` on every pair comparison:

```kotlin
itemA.transformedPoints.map { Point(it.x, it.y, 0.0) }
itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
```

This was verified as character-for-character identical in the pre-WS5 engine. The root cause is a type mismatch — `IntersectionUtils.hasIntersection` accepts `List<Point>` while the projection produces `List<Point2D>`. WS5 moved it verbatim. Not a WS5 finding, but worth noting: for 100 scene items, the baseline loop performs up to 4,950 pair checks, each allocating 2 lists + O(n) `Point` objects. The broad-phase prunes pairs but doesn't eliminate the per-pair cost.

### Analysis 3: `buildConvexHull` Geometry Analysis

Traced through the algorithm to verify correctness for the shapes this engine actually produces. For isometric quad faces, the four axis-extreme points (`left`, `top`, `right`, `bottom`) coincide with the four actual vertices of the projected parallelogram. The ordering `[left, top, right, bottom]` traces a diamond winding that is non-self-intersecting for all convex quadrilaterals — verified by geometric analysis of skewed parallelogram cases.

For triangular faces (3 points), `distinct()` correctly deduplicates the extrema to the 3 unique vertices. Ray-casting in `isPointInPoly` handles any vertex ordering for a triangle.

The algorithm fails for polygons with 5+ vertices where interior vertices are non-extreme — these are silently dropped, producing an inflated bounding shape with false-positive hit regions. While no current engine-generated face has >4 vertices, user-defined `Path` objects could → **S7**.

### Analysis 4: `SpatialGrid` Bounds Safety

Systematically verified all edge cases for `SpatialGrid.insert()` and `query()`:

| Case | `insert()` | `query()` |
|------|-----------|----------|
| Negative coordinates | Clamped to 0, safe | Clamped to 0, enters loop |
| Exact boundary (x = width) | Clamped to `cols-1`, safe | Clamped to `cols-1`, enters loop |
| Far out-of-bounds (x = 10000) | `min(cols-1, ...)` clamps; `minCol > maxCol` guard fires, skips | Clamped to edge — **scans corner cell instead of returning empty** |
| NaN coordinates | `getBounds()` returns null, skipped | N/A (NaN items never inserted) |
| Infinity coordinates | `toInt()` gives `Int.MAX_VALUE`, `minCol > maxCol` guard fires, safe | Same pattern — clamped to edge |
| Zero/negative width/height | Impossible — `ensurePreparedScene` guards prevent | — |

The `query()` early-return is provably dead code → **S8**. No `ArrayIndexOutOfBoundsException` is possible in any case.

### Analysis 5: `SceneGraph.nextId` Reset Safety

`SceneGraph.clear()` resets `nextId = 0`, meaning IDs like `item_0`, `item_1` are reused across clear/rebuild cycles. Verified this is **inert in the compose path** — `IsometricRenderer.rebuildCache()` always supplies explicit `id = command.commandId` (from `IsometricNode.nodeId`, backed by a never-reset `AtomicLong`). The `SceneGraph.nextId` counter is only reached in the raw engine path where no current consumer compares IDs across rebuilds. No maps, grids, or caches hold references to IDs from previous cycles — all are rebuilt from scratch.

---

## Pass 3: Final Correctness Verification

The third pass performed comprehensive correctness verification across 4 dimensions using parallel analysis agents. Each dimension targeted a specific risk category.

### Dimension 1: Engine Projection Math Equivalence

Verified that the 4-collaborator decomposition preserves the exact mathematical behavior of the pre-WS5 monolithic `IsometricEngine`:

| Concern | Result |
|---------|--------|
| `IsometricProjection.transformColor()` cross/dot/normalize | Mathematically identical — `fromTwoPoints` → `cross` → `normalize` → `dot` produces the same components as the pre-WS5 manual `(y1*z2 - z1*y2, ...)` expansion |
| `normalize()` zero-magnitude safety | Returns zero vector (same as pre-WS5) — no division-by-zero |
| `DepthSorter.sort()` iteration order | Character-for-character identical topological sort + circular-dependency fallback |
| `SceneGraph.nextId` scope | Instance-scoped (not companion), matches pre-WS5; `clear()` resets to 0, inert in compose path |
| `init` block ordering vs collaborator construction | `init` (lines 27-34) runs BEFORE collaborator fields (lines 36-38) — Kotlin textual ordering. `require(lightDirection.magnitude() > 0.0)` in init correctly validates before `defaultLightDirection = lightDirection.normalize()` |
| Visibility rules | All 4 collaborators are `internal`. `DepthSorter.TransformedItem` and `SceneGraph.SceneItem` cross engine↔collaborator boundary within module — correct |

**Result**: All 6 concerns verified clean. No behavioral divergence from pre-WS5.

### Dimension 2: Compose-Side Extraction Correctness

Verified that the renderer-side changes (accumulator pattern, extension deduplication, native renderer extraction) preserve behavioral equivalence:

| Concern | Result |
|---------|--------|
| `toComposePath()` deduplication | Single canonical definition in `RenderExtensions.kt`, zero private duplicates remaining (grep verified) |
| `NativeSceneRenderer.toAndroidColor()` ARGB ordering | `Color.argb(a, r, g, b)` — correct channel ordering for Android's `android.graphics.Color` |
| `NativeSceneRenderer` lazy paint init | `fillPaint`/`strokePaint` use `by lazy {}` — prevents `UnsatisfiedLinkError` on non-Android JVM |
| `renderTo()` error propagation | Exception in `renderTo()` propagates to `rebuildCache()`'s try/catch; previous cache preserved |
| `ShapeNode.renderTo()` path ordering | Uses `transformedShape.paths` (unordered), not `orderedPaths()` — pre-existing divergence from direct engine `add(shape)` path, not a WS5 regression |
| `GroupNode.renderTo()` child iteration | `childrenSnapshot` (COW list copy) iterated with `for` loop — safe against concurrent modification |
| Template-replacement flow | Nodes emit `RenderCommand(points = emptyList())` → `rebuildCache` extracts fields → `engine.add()` → `engine.projectScene()` creates new objects with projected points. Templates are discarded, not mutated → **S9** (misleading comments) |

**Result**: All 7 concerns verified clean. One misleading comment issue found → S9.

### Dimension 3: Edge Cases and Contract Violations

Verified 8 potential contract violation scenarios across the codebase:

| Concern | Result |
|---------|--------|
| `SpatialGrid` with NaN/Infinity coordinates | `getBounds()` returns null for NaN (skipped); Infinity clamped safely |
| `SpatialGrid` with negative coordinates | Clamped to 0, no AIOOBE possible |
| `SpatialGrid` far out-of-bounds query | Clamped to grid edge (existing dead-guard issue S8), no crash |
| `HitTester.buildConvexHull` empty/single-point input | Returns empty list — `isPointInPoly` returns false for < 3 points |
| `RenderCommand.points` immutability | `val` field — engine cannot mutate template objects |
| `PreparedScene` constructor with empty commands | Creates valid scene with empty command list — renders nothing |
| `IsometricRenderer.close()` idempotency | `closed = true` + `clearCache()` — repeated calls safe |
| `IsometricRenderer` use-after-close | `check(!closed)` in both `ensurePreparedScene()` and `rebuildCache()` — throws `IllegalStateException` |

**Result**: All 8 concerns verified safe. No contract violations found.

### Dimension 4: Test Coverage Assessment

Analyzed test coverage for the WS5 changes, comparing what exists against what the decomposition enables:

**Existing test coverage**:
- `IsometricRendererTest.kt` (977 lines) — Comprehensive renderer tests covering render, hit-test, caching, path caching, spatial index, native rendering, error handling, benchmark hooks, close behavior
- `IsometricNodeRenderTest.kt` (148 lines) — Node-level render tests with `collectCommands()` helper for the accumulator pattern
- All tests use real `IsometricEngine()`, never a fake `SceneProjector`

**Coverage gaps identified**:

| Gap | Severity | Notes |
|-----|----------|-------|
| No unit tests for `SceneGraph` | Medium | State management (add/clear/nextId) untested in isolation |
| No unit tests for `IsometricProjection` | Medium | `transformColor()`, `transformPath()`, `shouldCullFace()` untested independently — only exercised through full engine pipeline |
| No unit tests for `DepthSorter` | Medium | Topological sort + circular dependency fallback untested in isolation — only covered through integration |
| No unit tests for `HitTester` | Medium | `findItemAt()` and `buildConvexHull()` untested independently — only tested via renderer hit-test integration tests |
| No fake `SceneProjector` tests | Low | The interface was extracted to enable testing with fakes, but no tests exercise this capability yet |
| `PathNode` not directly tested | Low | Only exercised indirectly through renderer integration tests |
| `BatchNode` not directly tested | Low | Only exercised indirectly through renderer integration tests |
| `projectScene without lightDirection uses engine default` test doesn't detect S6 | Low | Both values (constructor default and interface default) are the same literal `Vector(2, -1, 3)`, so the test passes even with the behavioral change |

The 4 extracted collaborators are the primary decomposition benefit of WS5 — they were extracted specifically to enable independent testing. Creating unit tests for them would validate the extraction's correctness at a granular level and catch regressions that integration tests miss. The `HitTester` tests are particularly important given the `buildConvexHull` misnaming issue (S7).

---

## Verdict

The implementation delivers the most impactful architectural improvements from WS5: the `SceneProjector` interface, engine decomposition into 4 focused collaborators, accumulator render pattern, conversion deduplication, and native renderer isolation. The `IsometricEngine` reduction from 491 to ~95 lines is a significant maintainability improvement. The accumulator pattern eliminates ~350 intermediate list allocations per frame for a typical scene.

Pass 2 uncovered three additional issues. S6 (dead `defaultLightDirection` field / constructor regression) is the most significant — the constructor's `lightDirection` parameter is silently ignored after the `SceneProjector` interface extraction, which could surprise users of the raw engine API. S7 (`buildConvexHull` misnaming) is pre-existing but was a missed opportunity during extraction. S8 (`query()` dead guard) is a minor logic bug that allows false-positive spatial candidates for off-screen queries.

Pass 3 verified the decomposition's correctness across all dimensions — engine math equivalence, compose-side extraction correctness, edge case safety, and contract adherence — and found no behavioral divergences from pre-WS5. One additional should-fix was identified: S9 (misleading comments implying mutation of immutable `RenderCommand.points`). Pass 3 also identified significant test coverage gaps — none of the 4 extracted collaborators have independent unit tests, which undermines the primary testing benefit of the decomposition.

All 9 findings are should-fix — no must-fix. The most impactful remaining work is:
1. **S6**: Remove or wire the dead `defaultLightDirection` field (behavioral correctness)
2. **S4**: Complete the renderer decomposition (structural cleanliness)
3. **S7**: Fix hit testing to use original points instead of the misnamed "hull" (correctness for complex shapes)
4. **Unit tests for collaborators**: Add tests for `SceneGraph`, `IsometricProjection`, `DepthSorter`, and `HitTester` (realize the decomposition's testing benefit)

**Should-fix** (9):
- **S1**: Replace zero-arg `HashMap`/`HashSet`/`ArrayList` in `DepthSorter` with Kotlin factories
- **S2**: Change `AdvancedSceneConfig.engine` type to `SceneProjector` (and `onEngineReady` callback type)
- **S3**: Delete `lib/` directory from disk (not just `settings.gradle`)
- **S4**: Extract `SceneCache` and `HitTestResolver` from `IsometricRenderer` per plan Steps 7b/7c
- **S5**: Add justification comment for keeping `api(project(":isometric-core"))` in build config
- **S6**: Remove dead `defaultLightDirection` field and constructor `lightDirection` parameter (or wire it to `projectScene` default)
- **S7**: Replace `buildConvexHull` with direct use of `command.points` in `HitTester.findItemAt()`
- **S8**: Fix `SpatialGrid.query()` to check bounds before clamping, not after
- **S9**: Fix misleading "Will be filled by engine" comments to describe the template-replacement pattern accurately
