# WS3: Naming & Readability Cleanup — Detailed Implementation Plan

> **Workstream**: 3 of 8
> **Phase**: 2 (after WS1b and WS2 complete)
> **Scope**: Rename inconsistent APIs, standardize boolean prefixes, improve call-site readability across engine, renderer, composables, and samples
> **Findings**: F15, F29, F31, F32, F33, F34, F35, F38, F39
> **Depends on**: WS1b (parameter renames `origin`→`position`, `dx`→`width`, `i`→`x` already applied), WS2 (F41 eliminates `drawStroke` before F29 attempts to rename it)
> **Review ref**: `docs/reviews/api-first-run-usability-review.md` §3.3, §3.8

---

## Execution Order

The 9 findings decompose into 6 ordered steps. Each step is self-contained and produces a compilable codebase.

1. **Step 1**: Preset rename — `Quality` -> `NoCulling` (F15)
2. **Step 2**: Field/parameter renames (F31, F32, F33, F34)
3. **Step 3**: Method renames (F38)
4. **Step 4**: Boolean naming standardization (F29)
5. **Step 5**: Sample updates to named arguments (F35)
6. **Step 6**: Nesting reduction via local variables (F39)

Steps 1-4 are sequential (each rename can interact with subsequent renames, and compilation must be verified between them). Steps 5-6 are parallelizable after step 4.

**Cohesion note**: WS4 deletes the legacy `IsometricCanvas` surface. If that lands before WS3, drop any `IsometricCanvas*` rename work from this plan and apply the naming cleanup only to the surviving runtime API/docs/tests.

**F30 resolved**: `IsoColor` is kept as-is — the abbreviated name stands; no rename needed.

---

## Step 1: Preset Rename — `Quality` -> `NoCulling` (F15)

### Rationale

`RenderOptions.Quality` disables backface culling and bounds checking but keeps depth sorting enabled. The name "Quality" implies higher visual fidelity, but what it actually does is disable two culling optimizations. A developer reading `RenderOptions.Quality` has no idea what it toggles; `RenderOptions.NoCulling` communicates the exact behavior. This is the same naming pattern as `Performance` (which disables depth sorting) — descriptive of what changes, not a subjective quality judgment.

### Best Practice

Preset names should describe their configuration, not make value judgments. Compare: `RenderOptions.Performance` (clear — it trades correctness for speed) vs `RenderOptions.Quality` (unclear — quality of what?). Android's own `RenderEffect.createBlurEffect(DECAL)` names the mode, not the quality level.

### Files and Changes

#### 1a. `RenderOptions.kt` — Rename companion constant

**Current** (line 43):
```kotlin
val Quality = RenderOptions(
    enableDepthSorting = true,
    enableBackfaceCulling = false,  // Show all faces
    enableBoundsChecking = false,    // Render everything
    enableBroadPhaseSort = false
)
```

**After**:
```kotlin
val NoCulling = RenderOptions(
    enableDepthSorting = true,
    enableBackfaceCulling = false,
    enableBoundsChecking = false,
    enableBroadPhaseSort = false
)
```

#### 1b. Test files — Update all `RenderOptions.Quality` references

19 total references across 4 files:

| File | Count |
|------|-------|
| `isometric-core/src/test/.../IsometricEngineTest.kt` | 11 |
| `isometric-compose/src/test/.../IsometricRendererTest.kt` | 6 |
| `isometric-compose/src/androidTest/.../IsometricRendererPathCachingTest.kt` | 1 |
| `isometric-compose/src/androidTest/.../IsometricRendererNativeCanvasTest.kt` | 1 |

All are straightforward `RenderOptions.Quality` -> `RenderOptions.NoCulling` replacements.

### Verification

Compile all modules. Run `IsometricEngineTest` and `IsometricRendererTest` — all 19 test references must resolve.

---

## Step 2: Field/Parameter Renames (F31, F32, F33, F34)

### Best Practice

Direct breaking rename — no deprecation cycles per user preference.

### Changes

#### 2a. `IsometricEngine.prepare()` — `options` -> `renderOptions` (F31)

**Rationale**: The parameter name `options` is generic. In context (`engine.prepare(800, 600, options = ...)`) it is unclear what kind of options. `renderOptions` matches the field name on `RenderContext.renderOptions` and `RuntimeFlagSnapshot.renderOptions`, creating consistency across the API.

**Current** (`IsometricEngine.kt` line 91):
```kotlin
fun prepare(
    width: Int,
    height: Int,
    options: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = this.defaultLightDirection
): PreparedScene {
```

**After**:
```kotlin
fun prepare(
    width: Int,
    height: Int,
    renderOptions: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = this.defaultLightDirection
): PreparedScene {
```

**Internal references**: All uses of `options.enableBackfaceCulling`, `options.enableBoundsChecking`, `options.enableDepthSorting` within the method body update to `renderOptions.*`.

**Call site updates**:

| File | Call sites | Impact |
|------|-----------|--------|
| `IsometricRenderer.kt` (line 397-400) | `options = context.renderOptions` | Named arg — update to `renderOptions = context.renderOptions` |
| `IsometricCanvas.kt` (line 66) | `options = renderOptions` | Named arg — update to `renderOptions = renderOptions` |
| `IsometricCanvas.kt` (line 87) | `engine.prepare(canvasWidth, canvasHeight, renderOptions)` | Positional — no change needed |
| `IsometricView.kt` (line 135) | `engine.prepare(width, height, renderOptions)` | Positional — no change needed |
| `IsometricEngineTest.kt` (all calls) | Positional | No change needed |

Only 2 call sites use the named argument `options =` and need updating. Positional callers are unaffected by parameter renames.

#### 2b. `PreparedScene.viewportWidth/viewportHeight` -> `width/height` (F32)

**Rationale**: The `viewport` prefix is redundant. `PreparedScene` is scoped to a single viewport preparation — its `width` and `height` can only mean viewport dimensions. The prefix adds 8 characters to every access for no disambiguation value. Compare: `Size.width` (not `Size.viewportWidth`), `Canvas.width` (not `Canvas.viewportWidth`).

**Current** (`PreparedScene.kt`):
```kotlin
data class PreparedScene(
    val commands: List<RenderCommand>,
    val viewportWidth: Int,
    val viewportHeight: Int
)
```

**After** (note: WS1 converts this to a regular class — this rename applies on top):
```kotlin
class PreparedScene(
    val commands: List<RenderCommand>,
    val width: Int,
    val height: Int
) {
    // equals/hashCode/toString from WS1
}
```

**Call site updates**: ~14 `PreparedScene` field references across 6 files:

| File | References |
|------|-----------|
| `PreparedScene.kt` | 2 (declaration + KDoc) |
| `IsometricEngine.kt` | 1 (construction at line 143) |
| `IsometricRenderer.kt` | 2 (`cachedPreparedScene!!.viewportWidth/Height`) |
| `IsometricView.kt` | 2 (`cachedScene?.viewportWidth/Height`) |
| `IsometricEngineTest.kt` | 2 (`scene.viewportWidth/Height` assertions) |
| `IsometricRendererTest.kt` | 4 (`renderer.currentPreparedScene!!.viewportWidth/Height`) |
| `BenchmarkOrchestrator.kt` | 2 (`viewportWidth`/`viewportHeight` — these are local fields, NOT `PreparedScene` fields — verify before changing) |
| `InteractionSimulator.kt` | Many (`viewportWidth`/`viewportHeight` — local params, NOT `PreparedScene` — do NOT change) |

**Important**: Only rename the `PreparedScene` fields and their direct accessors. The `BenchmarkOrchestrator.viewportWidth` and `InteractionSimulator` parameters are independent local names that happen to share the same name — they are NOT accessing `PreparedScene` fields and must NOT be renamed.

#### 2c. `RenderCommand.id` -> `commandId` (F33)

**Rationale**: The field name `id` is the most common name in any codebase. In a `when` block or a `map` lambda, `it.id` is ambiguous — is it a node ID, a command ID, a shape ID? `commandId` makes the scope explicit. This mirrors the existing `ownerNodeId` field on the same class.

**Current** (`RenderCommand.kt` line 14):
```kotlin
data class RenderCommand(
    val id: String,
    val points: List<Point2D>,
    ...
)
```

**After**:
```kotlin
class RenderCommand(
    val commandId: String,
    val points: List<Point2D>,
    ...
)
```

**Call site updates**: 20 references across 4 source files + tests:

| File | References | Pattern |
|------|-----------|---------|
| `IsometricEngine.kt` | 1 | `id = transformedItem.item.id` -> `commandId = transformedItem.item.id` |
| `IsometricNode.kt` | 3 | `commandId = "${nodeId}_..."` in ShapeNode (line 162), PathNode (line 209), BatchNode (line 250) |
| `IsometricRenderer.kt` | 9 | `command.id`, `hit.id`, `commandId = command.id` |
| `IsometricEngineTest.kt` | 8 | `it.id` in map/set operations |
| `IsometricNodeRenderTest.kt` | 2 | `it.id` in assertions |

#### 2d. `Shape(shape:)` -> `Shape(geometry:)` in composable (F34)

**Rationale**: The current call site reads `Shape(shape = Prism(...))` — "shape" appears twice, once as the composable name and once as the parameter name. This is tautological. `geometry` describes the parameter's role: it is the geometric definition that the composable renders.

**Current** (`IsometricComposables.kt` line 33):
```kotlin
fun IsometricScope.Shape(
    shape: Shape,
    color: IsoColor = LocalDefaultColor.current,
    ...
)
```

**After**:
```kotlin
fun IsometricScope.Shape(
    geometry: Shape,
    color: IsoColor = LocalDefaultColor.current,
    ...
)
```

**Internal updates** within the composable body:
```kotlin
// factory
factory = { ShapeNode(geometry, color) },
// update block
set(geometry) { this.shape = it; markDirty() }
```

Note: `ShapeNode.shape` property is NOT renamed — it is a node-internal field, not a public API parameter. The composable parameter name and the node field name can differ.

**Call site updates**: 43 named-argument call sites across 7 files:

| File | Count |
|------|-------|
| `app/.../RuntimeApiActivity.kt` | 21 |
| `app/.../PrimitiveLevelsExample.kt` | 7 |
| `app/.../OptimizedPerformanceSample.kt` | 2 |
| `isometric-compose/src/test/.../IsometricRendererTest.kt` | 3 |
| `isometric-compose/src/test/.../IsometricNodeRenderTest.kt` | 7 |
| `isometric-compose/src/androidTest/.../IsometricRendererPathCachingTest.kt` | 1 |
| `isometric-compose/src/androidTest/.../IsometricRendererNativeCanvasTest.kt` | 2 |

All `shape = Prism(...)` -> `geometry = Prism(...)`.

### Verification

After each sub-step (2a-2d), compile all modules. Run full test suite after 2d completes.

---

## Step 3: Method Renames (F38)

### Rationale

Three methods use generic, implementation-focused names that hide their intent:

| Current | Problem | After | Clarity |
|---------|---------|-------|---------|
| `engine.prepare()` | "Prepare what? For what?" — generic verb | `engine.projectScene()` | Describes the action: project 3D scene to 2D commands |
| `renderer.invalidate()` | Ambiguous — could mean "invalidate the view" (Android meaning) or "invalidate the cache" | `renderer.clearCache()` | Describes the action: clear all cached data |
| `node.clearDirty()` | Imperative form that reads like an action on the flag itself | `node.markClean()` | Pairs with `markDirty()` — symmetric naming |

### Best Practice

Method names should describe intent, not mechanism. `projectScene()` says what happens (3D->2D projection). `clearCache()` says what it affects (the cache). `markClean()` pairs with its inverse `markDirty()` — the same pattern Compose uses internally (`Snapshot.markClean()`/`markDirty()`).

### Files and Changes

#### 3a. `IsometricEngine.prepare()` -> `projectScene()`

**File**: `IsometricEngine.kt` (line 88)

**Declaration change**:
```kotlin
// Before
fun prepare(
    width: Int,
    height: Int,
    renderOptions: RenderOptions = RenderOptions.Default,  // already renamed in Step 2a
    lightDirection: Vector = this.defaultLightDirection
): PreparedScene {

// After
fun projectScene(
    width: Int,
    height: Int,
    renderOptions: RenderOptions = RenderOptions.Default,
    lightDirection: Vector = this.defaultLightDirection
): PreparedScene {
```

**Call site updates**: 26 call sites across source + test files:

| File | Count | Pattern |
|------|-------|---------|
| `IsometricRenderer.kt` | 1 | `engine.prepare(...)` -> `engine.projectScene(...)` |
| `IsometricCanvas.kt` | 2 | `state.engine.prepare(...)` -> `state.engine.projectScene(...)` |
| `IsometricView.kt` | 1 | `engine.prepare(...)` -> `engine.projectScene(...)` |
| `IsometricEngineTest.kt` | 20 | All `engine.prepare(...)` calls |
| `BenchmarkFlags.kt` | 1 | KDoc reference |
| `IsometricRenderer.kt` | 1 | KDoc reference (`engine.prepare()`) |

#### 3b. `IsometricRenderer.invalidate()` -> `clearCache()`

**File**: `IsometricRenderer.kt` (line 355)

**Declaration change**:
```kotlin
// Before
fun invalidate() {
    cacheValid = false
    cachedPreparedScene = null
    ...
}

// After
fun clearCache() {
    cacheValid = false
    cachedPreparedScene = null
    ...
}
```

**Call site updates**:

| File | Count | Pattern |
|------|-------|---------|
| `IsometricRenderer.kt` | 1 | `if (forceRebuild) invalidate()` -> `if (forceRebuild) clearCache()` |
| `IsometricRendererTest.kt` | 1 | `renderer.invalidate()` (line 542) -> `renderer.clearCache()` |
| `IsometricRendererPathCachingTest.kt` | 1 | `renderer.invalidate()` (line 76) -> `renderer.clearCache()` |

#### 3c. `IsometricNode.clearDirty()` -> `markClean()`

**File**: `IsometricNode.kt` (line 93)

**Declaration change**:
```kotlin
// Before
fun clearDirty() {
    isDirty = false
    childrenSnapshot.forEach { it.clearDirty() }
}

// After
fun markClean() {
    isDirty = false
    childrenSnapshot.forEach { it.markClean() }
}
```

**Call site updates**:

| File | Count |
|------|-------|
| `IsometricNode.kt` | 1 (recursive call in the method body) |
| `IsometricRenderer.kt` | 1 (`rootNode.clearDirty()` -> `rootNode.markClean()`) |
| `IsometricRendererTest.kt` | 4 (`root.clearDirty()` -> `root.markClean()`) |

### Cross-Workstream Note

These renames happen BEFORE WS5 (engine/renderer decomposition). WS5 inherits the clean names `projectScene()`, `clearCache()`, `markClean()` and does not need to rename anything. This ordering is critical -- renaming after decomposition would require changes in more files.

### Verification

Compile all modules after each sub-step. Run full test suite after 3c.

---

## Step 4: Boolean Naming Standardization (F29)

### Rationale

The API currently uses four boolean prefix patterns:
- `enable*`: `enableGestures`, `enableDepthSorting`, `enableBackfaceCulling`, `enableBoundsChecking`, `enablePathCaching`, `enableSpatialIndex`, `enableBroadPhaseSort`
- `draw*`: `drawStroke`, `drawFill`
- `use*`: `useNativeCanvas`, `useRadius`
- `force*`: `forceRebuild`
- bare: `reverseSort`

Inconsistency forces users to guess the prefix. The `enable*` pattern is the most common (7 occurrences), most descriptive, and aligns with Android/Compose conventions (`enableEdgeToEdge`, `enableAutoSize`).

### Scope Exclusions

F29 in WS3 only handles booleans NOT covered by other workstreams:
- **WS2** wraps `drawStroke`/`drawFill`/`useNativeCanvas` into the `StrokeStyle` sealed type and `RenderingBackend` enum — F29 skips these
- **WS7** handles `reverseSort`/`useRadius` via `HitOrder` enum — F29 skips these
- **WS2** moves `forceRebuild` out of the public API into benchmark-internal config — F29 skips this

**Remaining booleans for F29**: After WS2 and WS7 exclusions, ALL remaining booleans already use the `enable*` prefix: `enableGestures`, `enableDepthSorting`, `enableBackfaceCulling`, `enableBoundsChecking`, `enablePathCaching`, `enableSpatialIndex`, `enableBroadPhaseSort`.

### Decision: No changes required

The booleans that remain after WS2/WS7 exclusions are already consistently named with the `enable*` prefix. F29 is satisfied by the WS2 and WS7 changes — no additional renames needed in WS3.

### Verification

Confirm by searching for boolean parameters without `enable*` prefix in the post-WS2/WS7 API surface. Expected result: none.

---

## Step 5: Sample Updates to Named Arguments (F35)

### Rationale

The most common call site in samples today reads:
```kotlin
Shape(
    shape = Prism(Point(0.0, 0.0, 0.0), 4.0, 5.0, 2.0),
    color = IsoColor(33.0, 150.0, 243.0)
)
```

After WS1 renames (`origin`->`position`, `dx`->`width`) and WS3 renames (`shape`->`geometry`), the ideal call site is:
```kotlin
Shape(
    geometry = Prism(position = Point(0.0, 0.0, 0.0), width = 4.0, depth = 5.0, height = 2.0),
    color = IsoColor(33.0, 150.0, 243.0)
)
```

Or, after WS1 defaults (`position = Point.ORIGIN`):
```kotlin
Shape(
    geometry = Prism(width = 4.0, depth = 5.0, height = 2.0),
    color = IsoColor(33, 150, 243)
)
```

### Best Practice

Kotlin named arguments serve as inline documentation. Android sample guidelines recommend named arguments for any call with more than two parameters or any constructor with non-obvious positional values. Three unlabeled `Double` values (`4.0, 5.0, 2.0`) are a readability failure at the call site.

### Files and Changes

All sample/activity files in `app/src/main/kotlin/.../sample/`:

#### 5a. `RuntimeApiActivity.kt` — ~21 `Shape()` calls + `Cylinder()` positional calls

**Before**:
```kotlin
Shape(
    geometry = Prism(Point(0.0, 0.0, 0.0), 2.0, 2.0, 2.0),
    color = IsoColor(33.0, 150.0, 243.0)
)
Shape(
    geometry = Cylinder(Point(-3.0, 0.0, 0.0), 0.5, 20, 2.0),
    color = IsoColor(76.0, 175.0, 80.0)
)
```

**After**:
```kotlin
Shape(
    geometry = Prism(
        position = Point(x = 0.0, y = 0.0, z = 0.0),
        width = 2.0, depth = 2.0, height = 2.0
    ),
    color = IsoColor(r = 33.0, g = 150.0, b = 243.0)
)
Shape(
    geometry = Cylinder(
        position = Point(x = -3.0, y = 0.0, z = 0.0),
        radius = 0.5, height = 2.0, vertices = 20
    ),
    color = IsoColor(r = 76.0, g = 175.0, b = 80.0)
)
```

Note: After WS1 adds `Point.ORIGIN` default and `IsoColor` Int constructor, many calls simplify further:
```kotlin
Shape(
    geometry = Prism(width = 2.0, depth = 2.0, height = 2.0),
    color = IsoColor(33, 150, 243)
)
```

#### 5b. `PrimitiveLevelsExample.kt` — ~7 `Shape()` calls

Same pattern. Convert all positional arguments to named.

#### 5c. `OptimizedPerformanceSample.kt` — ~2 `Shape()` calls

Same pattern.

#### 5d. `ComposeActivity.kt` — Color/Shape construction

Convert to named arguments.

#### 5e. `ViewSampleActivity.kt` — Engine API calls

Convert `engine.add(shape, color)` and shape construction to named arguments.

### Verification

Compile `app` module. Visual smoke test: run each sample activity and confirm rendering is unchanged.

---

## Step 6: Nesting Reduction via Local Variables (F39)

### Rationale

Several methods in the engine and renderer have deep nesting (3-4 levels of lambdas/conditionals) that hinders readability. Extracting intermediate results to named local variables flattens the nesting and gives each step a descriptive name.

### Best Practice

"Explain variable" refactoring (Fowler). Extract a complex expression or nested lambda into a named local variable whose name documents the step. This replaces structural nesting with sequential named steps — easier to read, debug, and profile.

### Files and Changes

#### 6a. `IsometricEngine.kt` — `projectScene()` method body

**Current** (after Step 3a rename, lines 93-143): The `mapNotNull` lambda contains nested `if` guards and a `return@mapNotNull`:

```kotlin
val transformedItems = items.mapNotNull { item ->
    val transformedPoints = item.path.points.map { point ->
        translatePoint(point, originX, originY)
    }
    if (options.enableBackfaceCulling && cullPath(transformedPoints)) {
        return@mapNotNull null
    }
    if (options.enableBoundsChecking && !itemInDrawingBounds(transformedPoints, width, height)) {
        return@mapNotNull null
    }
    val litColor = transformColor(item.path, item.baseColor, normalizedLight)
    TransformedItem(item, transformedPoints, litColor)
}
```

**After**: Extract the culling checks into a private method:

```kotlin
val transformedItems = items.mapNotNull { item ->
    projectAndCull(item, originX, originY, renderOptions, normalizedLight, width, height)
}

private fun projectAndCull(
    item: SceneItem,
    originX: Double,
    originY: Double,
    renderOptions: RenderOptions,
    normalizedLight: Vector,
    width: Int,
    height: Int
): TransformedItem? {
    val screenPoints = item.path.points.map { translatePoint(it, originX, originY) }

    if (renderOptions.enableBackfaceCulling && cullPath(screenPoints)) return null
    if (renderOptions.enableBoundsChecking && !itemInDrawingBounds(screenPoints, width, height)) return null

    val litColor = transformColor(item.path, item.baseColor, normalizedLight)
    return TransformedItem(item, screenPoints, litColor)
}
```

This reduces the lambda body from 12 lines with nested returns to 1 line.

#### 6b. `IsometricRenderer.kt` — `render()` method

**Current** (lines 165-199): Nested `if/else` with `let`:

```kotlin
if (enablePathCaching && cachedPaths != null) {
    val paths = cachedPaths!!
    for (i in paths.indices) {
        val cached = paths[i]
        drawPath(cached.path, cached.fillColor, style = Fill)
        if (drawStroke) {
            drawPath(cached.path, cached.strokeColor, style = Stroke(width = strokeWidth))
        }
    }
} else {
    cachedPreparedScene?.let { scene ->
        renderPreparedScene(scene, strokeWidth, drawStroke)
    }
}
```

**After**: Extract early-return for the slow path, flatten:

```kotlin
val paths = if (enablePathCaching) cachedPaths else null

if (paths == null) {
    cachedPreparedScene?.let { renderPreparedScene(it, strokeWidth, drawStroke) }
    benchmarkHooks?.onDrawEnd()
    return
}

for (i in paths.indices) {
    val cached = paths[i]
    drawPath(cached.path, cached.fillColor, style = Fill)
    if (drawStroke) {
        drawPath(cached.path, cached.strokeColor, style = Stroke(width = strokeWidth))
    }
}
```

This eliminates one nesting level and removes the `!!` operator.

#### 6c. `IsometricRenderer.kt` — `hitTest()` spatial index path

**Current** (lines 275-320): Nested `if` with `isNotEmpty` checks:

```kotlin
if (enableSpatialIndex && spatialIndex != null) {
    val candidateIds = spatialIndex!!.query(x, y, HIT_TEST_RADIUS_PX)
    if (candidateIds.isNotEmpty()) {
        val candidateCommands = candidateIds
            .mapNotNull { id -> commandIdMap[id] }
            .sortedBy { command -> commandOrderMap[command.id] ?: Int.MAX_VALUE }
        if (candidateCommands.isNotEmpty()) {
            val filteredScene = PreparedScene(...)
            val hit = engine.findItemAt(...)
            if (hit != null) {
                return findNodeByCommandId(hit.id)
            }
        }
    }
}
```

**After**: Extract to a private method, use early returns:

```kotlin
private fun hitTestSpatial(x: Double, y: Double): IsometricNode? {
    val index = spatialIndex ?: return null
    val candidateIds = index.query(x, y, HIT_TEST_RADIUS_PX)
    if (candidateIds.isEmpty()) return null

    val candidateCommands = candidateIds
        .mapNotNull { commandIdMap[it] }
        .sortedBy { commandOrderMap[it.commandId] ?: Int.MAX_VALUE }
    if (candidateCommands.isEmpty()) return null

    val filteredScene = PreparedScene(
        commands = candidateCommands,
        width = cachedPreparedScene!!.width,
        height = cachedPreparedScene!!.height
    )
    val hit = engine.findItemAt(
        preparedScene = filteredScene,
        x = x, y = y,
        reverseSort = true, useRadius = true, radius = HIT_TEST_RADIUS_PX
    ) ?: return null

    return findNodeByCommandId(hit.commandId)
}
```

Then in `hitTest()`:
```kotlin
if (enableSpatialIndex) {
    hitTestSpatial(x, y)?.let { return it }
}
// Fall through to linear scan
```

This reduces 4 levels of nesting to 0 in the main method.

#### 6d. `IsometricNode.kt` — `ShapeNode.render()`, `PathNode.render()`, `BatchNode.render()`

**Current**: Each `render()` method manually applies position, rotation, and scale transforms with nested `if` guards:

```kotlin
transformedShape = transformedShape.translate(position.x, position.y, position.z)
if (rotation != 0.0) {
    val origin = rotationOrigin ?: position
    transformedShape = transformedShape.rotateZ(origin, rotation)
}
if (scale != 1.0) {
    val origin = scaleOrigin ?: position
    transformedShape = transformedShape.scale(origin, scale)
}
```

**After**: Extract to a shared method on `IsometricNode`:

```kotlin
// In IsometricNode base class
protected fun applyLocalTransforms(shape: Shape): Shape {
    var result = shape.translate(position.x, position.y, position.z)
    if (rotation != 0.0) {
        result = result.rotateZ(rotationOrigin ?: position, rotation)
    }
    if (scale != 1.0) {
        result = result.scale(scaleOrigin ?: position, scale)
    }
    return result
}

protected fun applyLocalTransforms(path: io.fabianterhorst.isometric.Path): io.fabianterhorst.isometric.Path {
    var result = path.translate(position.x, position.y, position.z)
    if (rotation != 0.0) {
        result = result.rotateZ(rotationOrigin ?: position, rotation)
    }
    if (scale != 1.0) {
        result = result.scale(scaleOrigin ?: position, scale)
    }
    return result
}
```

Then each node's `render()` simplifies to:
```kotlin
// ShapeNode
val transformedShape = applyLocalTransforms(context.applyTransformsToShape(shape))

// PathNode
val transformedPath = applyLocalTransforms(context.applyTransformsToPath(path))
```

**Behavioral note**: `PathNode.render()` currently has a conditional guard around translate (`if (position.x != 0.0 || ...)`) that `ShapeNode` and `BatchNode` do not have. The proposed shared `applyLocalTransforms()` unconditionally calls `translate()`, which changes `PathNode`'s behavior: it will now call `translate(0, 0, 0)` when position is zero. This is functionally a no-op (translating by zero produces identical points), but the behavioral difference should be acknowledged. The simplification is worth it — the conditional was a premature optimization.

This removes 9 lines of duplicated transform logic from each of the three node types (27 lines total).

### Verification

Run full test suite. Nesting reduction is purely structural — no behavioral changes. All tests must pass unchanged.

---

## Cross-Workstream Dependencies

| This step | Produces | Consumed by |
|-----------|----------|-------------|
| Step 1 (F15: Quality -> NoCulling) | Renamed preset constant | WS8 (documentation uses preset names in examples) |
| Step 2a (F31: options -> renderOptions) | Consistent param name | WS5 (engine decomposition inherits the clean param name) |
| Step 2b (F32: viewportWidth -> width) | Shorter field names | WS5 (PreparedScene accessor code), WS8 (docs) |
| Step 2c (F33: id -> commandId) | Disambiguated field | WS4 (hit-test resolution uses `command.commandId`), WS5 (spatial index keyed by `commandId`) |
| Step 2d (F34: shape -> geometry) | Renamed composable param | WS8 (all sample code in docs), WS6 (CustomNode composable will follow the pattern) |
| Step 3a (F38: prepare -> projectScene) | Renamed engine method | WS5 (engine decomposition inherits clean name), WS8 (docs reference the method) |
| Step 3b (F38: invalidate -> clearCache) | Renamed renderer method | WS5 (renderer decomposition inherits clean name) |
| Step 3c (F38: clearDirty -> markClean) | Renamed node method | WS7 (dirty tracking correctness uses markDirty/markClean pair) |
| Step 4 (F29: boolean standardization) | No changes needed — already consistent | — |
| Step 6 (F39: nesting reduction) | Extracted helper methods | WS5 (decomposition inherits smaller, testable methods) |

### Ordering Constraints

| Constraint | Reason |
|-----------|--------|
| WS1b completes before WS3 starts | WS1b renames `origin`->`position`, `dx`->`width`, `i`->`x`. WS3 renames build on the WS1b names. |
| WS2 completes before WS3 starts | WS2 (F41) eliminates `drawStroke` before F29 attempts to rename it. |
| WS3 Step 2a before Step 3a | Step 2a renames the `options` param; Step 3a renames the method. Doing both at once risks merge conflicts. |
| WS3 completes before WS5 starts | WS5 decomposes engine/renderer into smaller classes. Renaming after decomposition multiplies the file count. |

---

## File Change Summary

| File | Steps | Changes |
|------|-------|---------|
| `RenderOptions.kt` | 1 | `Quality` -> `NoCulling` |
| `IsometricEngine.kt` | 2a, 3a, 6a | `options`->`renderOptions`, `prepare()`->`projectScene()`, extract `projectAndCull()` |
| `PreparedScene.kt` | 2b | `viewportWidth/Height` -> `width/height` |
| `RenderCommand.kt` | 2c | `id` -> `commandId` |
| `IsometricComposables.kt` | 2d | `shape`->`geometry` param |
| `IsometricNode.kt` | 2c, 3c, 6d | `id`->`commandId` in node render, `clearDirty()`->`markClean()`, extract `applyLocalTransforms()` |
| `IsometricRenderer.kt` | 2b, 2c, 3a, 3b, 6b, 6c | field renames, `invalidate()`->`clearCache()`, `prepare()`->`projectScene()`, nesting reduction |
| `IsometricCanvas.kt` | 2a, 3a | `options`->`renderOptions`, `prepare()`->`projectScene()` |
| `IsometricView.kt` | 2b, 3a | `viewportWidth/Height`->`width/height`, `prepare()`->`projectScene()` |
| `IsometricEngineTest.kt` | 1, 2c, 3a | `Quality`->`NoCulling`, `it.id`->`it.commandId`, `prepare()`->`projectScene()` |
| `IsometricRendererTest.kt` | 1, 2b, 2c, 3b, 3c | `Quality`->`NoCulling`, field renames, method renames |
| `IsometricNodeRenderTest.kt` | 2c, 2d | `id`->`commandId`, `shape`->`geometry` |
| `IsometricRendererPathCachingTest.kt` | 1, 2d, 3b | `Quality`->`NoCulling`, `shape`->`geometry`, `invalidate()`->`clearCache()` |
| `IsometricRendererNativeCanvasTest.kt` | 1, 2d | `Quality`->`NoCulling`, `shape`->`geometry` |
| `RuntimeApiActivity.kt` | 2d, 5 | `shape`->`geometry`, named args |
| `PrimitiveLevelsExample.kt` | 2d, 5 | `shape`->`geometry`, named args |
| `OptimizedPerformanceSample.kt` | 2d, 5 | `shape`->`geometry`, named args |
| `ComposeActivity.kt` | 5 | named args |
| `ViewSampleActivity.kt` | 5 | named args |
