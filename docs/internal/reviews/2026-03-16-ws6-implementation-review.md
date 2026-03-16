# WS6 Implementation Review — Escape Hatches & Extensibility

> **Date**: 2026-03-16
> **Scope**: Uncommitted working tree changes on `feat/api-changes` implementing WS6
> **Plan ref**: `docs/plans/2026-03-14-ws6-escape-hatches-extensibility.md`
> **Methodology**: Fresh-eyes review of all new/modified source files against plan spec, full file reads for all 4 new files, 9 modified files, and 3 test files. Code boundary analysis, correctness verification, and cross-workstream dependency audit.
> **Passes**: 3 (Pass 1: source-level review against plan spec. Pass 2: code boundary analysis, data-flow tracing, transform pipeline correctness, cache invalidation audit, hit-test interaction verification. Pass 3: systematic file-by-file, diff-by-diff, context-by-context review of all 13 modified/new source files and 3 test files)

---

## Summary

4 new files, 9 modified files, 3 new test files. All 10 plan steps are represented in the implementation. The core extensibility goals (Point2D arithmetic, parameterized depth, projection API, mutable engine params, CompositionLocal exposure, camera state, render hooks, per-subtree renderOptions, custom nodes) are structurally implemented and compile-clean.

Pass 1 identified **7 should-fix** and **2 could-improve** findings. Pass 2 performed deep code-boundary analysis — tracing data flow through the SceneCache invalidation pipeline, the transform application order across node types, and the camera-to-hit-test coordinate interaction — and identified **4 additional findings** (S8–S10, C3), including a high-severity hit-test breakage when camera transforms are active (S8) and a transform-semantics inconsistency between existing and new node types (S9). Pass 3 performed systematic file-by-file review of all source files, verifying correctness of each code path, and identified **1 additional finding** (S11) — CustomRenderNode commands are non-hit-testable through the composable API.

**Total: 11 should-fix, 3 could-improve.**

---

## Step Coverage

| Step | Description | Status | Notes |
|------|-------------|--------|-------|
| 1 | `Point2D` arithmetic (F62) | **Complete** | All operators, `distanceTo`, `distanceToSquared`, `ZERO` |
| 2 | `RenderBenchmarkHooks` defaults (F78) | **Complete** | Interface methods now have empty default bodies |
| 3 | Depth parameterization (F76) | **Complete** | `Point.depth(angle)` and `Path.depth(angle)` |
| 4 | Projection API (F56) | **Complete** | `worldToScreen()`, `screenToWorld()` with inverse projection |
| 5 | Mutable engine params (F60) | **Complete** | `angle`/`scale` as `var` with validation + `rebuildProjection()` |
| 6 | `LocalIsometricEngine` (F61) | **Complete** | `staticCompositionLocalOf`, conditional provision via safe cast |
| 7 | `CameraState` (F19) | **Mostly complete** | Core pan/zoom/reset works; Step 7c drag→pan not wired; hit-test broken with camera (S8) |
| 8 | Render hooks (F57, F59) | **Complete** | `onBeforeDraw`, `onAfterDraw`, `onPreparedSceneReady` |
| 9 | Per-subtree `renderOptions` (F4) | **Partial** | Override propagated in context but architecturally non-functional for engine processing (S2, S10) |
| 10 | `CustomNode` composable (F3/F58) | **Complete** | `CustomRenderNode` + `CustomNode` composable; transform semantics differ from leaf nodes (S9); commands non-hit-testable (S11) |

---

## Finding Index

| # | Severity | Step | Title |
|---|----------|------|-------|
| S1 | Should | 5 | Mutable engine `angle`/`scale` don't invalidate renderer cache |
| S2 | Should | 9 | `renderOptions` on leaf nodes (`Shape`/`Path`/`Batch`) silently ignored |
| S3 | Should | 7 | Camera transform order produces zoom-around-origin, not zoom-around-center |
| S4 | Should | 8 | `onPreparedSceneReady` fires inside Canvas draw — state writes risk infinite loop |
| S5 | Should | 3 | `Point.depth()` KDoc falsely claims equivalence to `depth(PI/6)` |
| S6 | Should | 7 | `CameraState.zoom` direct assignment has no validation |
| S7 | Should | 10 | `CustomRenderNode.children` allocated but never rendered |
| S8 | Should | 7 | Hit testing ignores camera transforms — taps land on wrong coordinates |
| S9 | Should | 10 | Transform inconsistency: leaf node `position` is world-space, `CustomRenderNode` is parent-space |
| S10 | Should | 9 | Per-subtree `renderOptions` on `GroupNode` is architecturally non-functional for engine processing |
| S11 | Should | 10 | `CustomRenderNode` commands are non-hit-testable — render function has no access to `nodeId` |
| C1 | Could | 4 | `screenToWorld` determinant guard uses exact float equality |
| C2 | Could | 7 | Step 7c drag→pan convenience integration not implemented |
| C3 | Could | 6 | `LocalIsometricEngine` not `rememberUpdatedState`-backed — goes stale if engine reference changes |

---

## S1 — Mutable Engine Params Don't Invalidate Renderer Cache (Should-fix)

**Files**: `IsometricEngine.kt` lines 30–46, `IsometricRenderer.kt` (SceneCache), `IsometricScene.kt` lines 124–131

**Problem**: Step 5 makes `angle` and `scale` mutable with custom setters that call `rebuildProjection()`:

```kotlin
var angle: Double = angle
    set(value) {
        require(value.isFinite()) { "angle must be finite, got $value" }
        field = value
        rebuildProjection()  // rebuilds internal IsometricProjection
    }
```

`rebuildProjection()` correctly recreates the `IsometricProjection` instance, so the *next* call to `projectScene()` will use the new angle. **But the renderer's cache doesn't know it needs to call `projectScene()` again.** The cache staleness check (`SceneCache.needsUpdate()`) compares:

1. `rootNode.isDirty` — unchanged by engine param changes
2. `context != lastContext` — `RenderContext` doesn't reference engine state
3. `width`/`height` — unchanged

So after `engine.angle = PI / 4`, the cache returns the stale `PreparedScene` projected at the old angle. The scene visually doesn't update until an external trigger (node mutation, config change, or `forceRebuild = true`).

**Impact**: The primary use case for mutable engine params — user adjusting angle/scale via a slider — appears to do nothing. The feature compiles and passes validation but silently produces stale output.

**Fix**: Add a version counter to `IsometricEngine`:
```kotlin
var projectionVersion: Long = 0L
    private set

private fun rebuildProjection() {
    projection = IsometricProjection(this.angle, this.scale, colorDifference, lightColor)
    projectionVersion++
}
```

Then include the engine's version in the cache staleness check, or mark the root node dirty when the engine changes.

---

## S2 — `renderOptions` on Leaf Nodes Silently Ignored (Should-fix)

**Files**: `IsometricNode.kt` lines 191–215 (ShapeNode), 221–243 (PathNode), 249–275 (BatchNode); `IsometricComposables.kt` lines 46, 144, 194

**Problem**: Step 9 adds `renderOptions: RenderOptions? = null` to the `IsometricNode` base class (line 79) and wires it in all composables:

```kotlin
// In Shape, Path, and Batch composables:
set(renderOptions) { this.renderOptions = it; markDirty() }
```

But only `GroupNode.renderTo()` (line 165) and `CustomRenderNode.renderTo()` (line 293) check and apply the override via `context.withRenderOptions()`. The leaf nodes (`ShapeNode`, `PathNode`, `BatchNode`) store the value but never read it during `renderTo()`:

```kotlin
// ShapeNode.renderTo() — no renderOptions handling
override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
    if (!isVisible) return
    val transformedShape = applyLocalTransforms(context.applyTransformsToShape(shape))
    // ... produces commands using the unmodified context.renderOptions
}
```

A user writing `Shape(geometry = myShape, renderOptions = RenderOptions(enableBackfaceCulling = false))` gets no error and no effect — the override is silently ignored.

**Impact**: False API promise. Users who set `renderOptions` on individual shapes to disable culling or depth sorting will see no behavioral change.

**Fix**: Either:
- **(a)** Remove `renderOptions` parameter from `Shape`, `Path`, `Batch` composables. Document that per-subtree overrides require a `Group` wrapper: `Group(renderOptions = ...) { Shape(...) }`.
- **(b)** Apply the override in leaf node `renderTo()` methods, matching the pattern in `GroupNode`:
  ```kotlin
  val effectiveContext = if (renderOptions != null) context.withRenderOptions(renderOptions!!) else context
  ```

Option (a) is recommended since per-node renderOptions on leaf nodes has limited utility — the renderOptions affects engine-level processing (culling, sorting), which operates on the full command set, not individual commands.

---

## S3 — Camera Transform Order Produces Zoom-Around-Origin (Should-fix)

**File**: `IsometricScene.kt` lines 322–323

**Problem**: The camera transforms are applied as translate-then-scale:

```kotlin
drawContext.transform.translate(panX.toFloat(), panY.toFloat())
drawContext.transform.scale(zoom.toFloat(), zoom.toFloat())
```

In a matrix composition, the *last* applied transform acts *first* on the geometry. So the effective transform is: first scale (around Canvas origin = top-left), then translate. This means:

1. A zoom of 2x expands everything from the top-left corner
2. The pan offset is in *pre-zoom* space — panning 100px at 2x zoom actually moves the viewport 100px, not the expected 200px in screen space

The `CameraState.zoomBy()` KDoc says "zoom around the current center" (line 39 of CameraState.kt), but the actual behavior is zoom around the Canvas origin (0,0).

**Impact**: Zooming feels wrong — content moves toward/away from the top-left corner instead of the center. Pan distance is zoom-dependent.

**Fix**: For zoom-around-center behavior, apply a 3-step transform:
```kotlin
val cx = size.width / 2f
val cy = size.height / 2f
drawContext.transform.translate(cx + panX.toFloat(), cy + panY.toFloat())
drawContext.transform.scale(zoom.toFloat(), zoom.toFloat())
drawContext.transform.translate(-cx, -cy)
```

Alternatively, for stable panning at any zoom level (zoom around origin, pan in screen space):
```kotlin
drawContext.transform.scale(zoom.toFloat(), zoom.toFloat())
drawContext.transform.translate(panX.toFloat(), panY.toFloat())
```

---

## S4 — `onPreparedSceneReady` Fires Inside Canvas Draw Phase (Should-fix)

**File**: `IsometricScene.kt` lines 349–351

**Problem**: The callback is invoked inside the Canvas `onDraw` lambda:

```kotlin
// Inside Canvas { ... } draw block:
renderer.currentPreparedScene?.let { scene ->
    config.onPreparedSceneReady?.invoke(scene)
}
```

Compose's draw phase should not trigger state writes. If a user's callback writes to `mutableStateOf` (a common use case — e.g., caching the scene command count for a debug overlay), it triggers snapshot invalidation during the draw phase. This can cause:

1. Infinite recomposition loops (draw → state write → invalidation → draw → ...)
2. `IllegalStateException` from the Compose runtime in strict mode

The `onBeforeDraw` and `onAfterDraw` hooks are `DrawScope.() -> Unit` — they're designed for additional *drawing*, which is safe. But `onPreparedSceneReady` is `(PreparedScene) -> Unit` — its signature invites non-drawing side effects.

**Impact**: Users who use the hook for its intended purpose (inspection/debugging with state writes) trigger runtime errors or infinite loops.

**Fix**: Move the callback outside the draw phase. Capture the scene reference in the draw block, then invoke the user callback in a `SideEffect`:

```kotlin
// Inside Canvas draw block:
var latestScene: PreparedScene? = null
// ... after render ...
latestScene = renderer.currentPreparedScene

// Outside Canvas, in the composable body:
SideEffect {
    latestScene?.let { scene -> config.onPreparedSceneReady?.invoke(scene) }
}
```

Or use `snapshotFlow` to observe the scene reference change.

---

## S5 — `Point.depth()` KDoc Falsely Claims Equivalence to `depth(PI/6)` (Should-fix)

**File**: `Point.kt` lines 136–142

**Problem**: The KDoc states:

```kotlin
/**
 * The depth of a point in the isometric plane using the default 30° angle.
 * Equivalent to `depth(PI / 6)`.
 * z is weighted slightly to accommodate |_ arrangements
 */
fun depth(): Double {
    return x + y - 2 * z
}
```

But `depth()` = `x + y - 2z` while `depth(PI/6)` = `cos(30°)*x + sin(30°)*y - 2z` ≈ `0.866x + 0.5y - 2z`.

For `Point(1.0, 1.0, 0.0)`:
- `depth()` = 2.0
- `depth(PI/6)` ≈ 1.366

These are **not** equivalent. The legacy formula uses equal weighting (1.0) for both x and y, which doesn't correspond to any standard angle. (Equal weighting would require `cos(angle) = sin(angle) = 1.0`, which is impossible.)

**Impact**: Users reading the documentation will incorrectly assume the two methods produce the same results for the default engine angle, leading to ordering bugs when mixing parameterized and legacy depth calculations.

**Fix**: Replace the KDoc with an accurate description:

```kotlin
/**
 * Simplified depth formula using equal weighting for x and y.
 * This is a heuristic that provides correct relative ordering for standard
 * isometric scenes. For exact depth at arbitrary projection angles,
 * use [depth(angle)].
 *
 * Note: This is NOT equivalent to `depth(PI/6)` — the parameterized
 * version uses cos/sin weighting while this uses uniform weighting.
 */
```

---

## S6 — `CameraState.zoom` Direct Assignment Has No Validation (Should-fix)

**File**: `CameraState.kt` line 26

**Problem**: The `zoomBy()` method validates `factor > 0.0`, but the `zoom` property itself can be set to any value:

```kotlin
var zoom: Double by mutableDoubleStateOf(zoom)  // No validation

fun zoomBy(factor: Double) {
    require(factor > 0.0) { "Zoom factor must be positive, got $factor" }
    zoom *= factor
}
```

A user can write:
```kotlin
cameraState.zoom = -1.0   // Canvas mirrors — no error
cameraState.zoom = 0.0    // Canvas collapses to nothing — no error
cameraState.zoom = Double.NaN  // Undefined rendering — no error
```

This is inconsistent with `IsometricEngine.scale` (which validates in its custom setter) and with `CameraState.zoomBy()` (which validates the factor). The constructor also doesn't validate the initial `zoom` value.

**Impact**: Invalid zoom values cause rendering artifacts (mirroring, collapse, NaN coordinates) with no error message pointing to the root cause.

**Fix**: Use a backing state with a validating property:

```kotlin
private var _zoom by mutableDoubleStateOf(zoom)
var zoom: Double
    get() = _zoom
    set(value) {
        require(value > 0.0 && value.isFinite()) { "zoom must be positive and finite, got $value" }
        _zoom = value
    }

init {
    require(zoom > 0.0 && zoom.isFinite()) { "Initial zoom must be positive and finite, got $zoom" }
}
```

Apply the same pattern to `panX`/`panY` for NaN/Infinity validation.

---

## S7 — `CustomRenderNode.children` Allocated but Never Rendered (Should-fix)

**File**: `IsometricNode.kt` lines 284–309

**Problem**: `CustomRenderNode` overrides `children` with a mutable list, making it a container node:

```kotlin
class CustomRenderNode(
    var renderFunction: (context: RenderContext) -> List<RenderCommand>
) : IsometricNode() {
    internal override val children = mutableListOf<IsometricNode>()  // container

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        // ... transforms + renderOptions ...
        output += renderFunction(localContext)
        // NOTE: children are never iterated
    }
}
```

The plan notes (line 981): *"CustomRenderNode is a leaf node (has no children DSL content parameter)"*. Yet the implementation allocates a per-node ArrayList that is never read during rendering.

Two issues:
1. **Wasted allocation**: Every `CustomRenderNode` creates an empty `ArrayList` that is never used. Should use the shared `LEAF_CHILDREN` sentinel.
2. **Silent data loss**: If a user constructs a `CustomRenderNode` programmatically (outside the composable) and adds children, those children are stored but never rendered — silently dropped.

**Fix**: Use the leaf sentinel:

```kotlin
class CustomRenderNode(
    var renderFunction: (context: RenderContext) -> List<RenderCommand>
) : IsometricNode() {
    // Leaf node — no children. Use LEAF_CHILDREN sentinel (throws on mutation).
    // The CustomNode composable has no content slot, so children are never added.
```

Remove the `internal override val children` line entirely (inherits `LEAF_CHILDREN` from base).

---

## S8 — Hit Testing Ignores Camera Transforms (Should-fix)

**Files**: `IsometricScene.kt` lines 269–276 (hit test call), lines 316–324 (camera transform application)

**Problem**: When `CameraState` is active, camera transforms (translate + scale) are applied at the `DrawScope` level to shift/zoom the rendered output:

```kotlin
// Lines 322-323 — camera transforms applied to DrawScope
drawContext.transform.translate(panX.toFloat(), panY.toFloat())
drawContext.transform.scale(zoom.toFloat(), zoom.toFloat())
```

But the pointer-input hit test passes **raw screen coordinates** directly to the renderer:

```kotlin
// Lines 269-275 — raw pointer position used for hit test
val hitNode = renderer.hitTest(
    rootNode = rootNode,
    x = position.x.toDouble(),  // raw screen X
    y = position.y.toDouble(),  // raw screen Y
    context = currentRenderContext,
    width = currentCanvasWidth,
    height = currentCanvasHeight
)
```

The `renderer.hitTest()` delegates to `HitTestResolver`, which compares the given x/y against `PreparedScene` command coordinates. These command coordinates are in the **engine's un-transformed screen space** — they don't include the camera translate/scale. So when the camera pans right by 100px, a shape drawn at screen position (300, 200) was projected at engine position (200, 200). A tap at screen (300, 200) looks for a hit at engine (300, 200) — missing the shape entirely.

**Traced data flow**:
1. Engine projects shapes to screen coords (e.g., shape at engine-space (200, 200))
2. Camera transform shifts the DrawScope → shape renders at screen (300, 200)
3. User taps at screen (300, 200)
4. Hit test checks raw (300, 200) against engine-space coords → no hit at (300, 200) → miss

**Impact**: All tap interactions are broken when the camera has any non-identity pan or zoom. This is the primary user-facing symptom of combining `CameraState` with `GestureConfig`.

**Fix**: Inverse-transform the pointer coordinates before hit testing:

```kotlin
val cameraState = config.cameraState
val adjustedX: Double
val adjustedY: Double
if (cameraState != null) {
    adjustedX = (position.x.toDouble() - cameraState.panX) / cameraState.zoom
    adjustedY = (position.y.toDouble() - cameraState.panY) / cameraState.zoom
} else {
    adjustedX = position.x.toDouble()
    adjustedY = position.y.toDouble()
}
val hitNode = renderer.hitTest(
    rootNode = rootNode,
    x = adjustedX, y = adjustedY,
    ...
)
```

The same inverse transform must also be applied to the `onHitTestReady` callback (line 136) and to the `TapEvent` coordinates if they should represent engine-space positions.

---

## S9 — Transform Inconsistency: Leaf `position` Is World-Space, `CustomRenderNode` Is Parent-Space (Should-fix)

**Files**: `IsometricNode.kt` lines 196–214 (ShapeNode.renderTo), lines 289–308 (CustomRenderNode.renderTo); `RenderContext.kt` lines 67–116 (withTransform)

**Problem**: `ShapeNode`, `PathNode`, and `BatchNode` apply their local `position` as a **world-space** offset, while `CustomRenderNode` applies it as a **parent-space** offset. The two approaches produce different results when there is a parent rotation.

**Traced scenario** — GroupNode at `position=(5,0,0)` with `rotation=PI/4`, child at `position=(2,0,0)`:

**ShapeNode path** (line 199):
```kotlin
val transformedShape = applyLocalTransforms(context.applyTransformsToShape(shape))
```
1. `context.applyTransformsToShape(shape)` — translates by accumulated (5,0,0), then rotates by PI/4 around (5,0,0)
2. `applyLocalTransforms(...)` — calls `shape.translate(2, 0, 0)` **in world space** on the already-rotated geometry
3. **Result**: The child's +2 X offset is along the **world X axis**, regardless of the parent's rotation → final position ≈ **(7.0, 0.0, 0.0)** in world space

**CustomRenderNode path** (lines 299–305):
```kotlin
val localContext = effectiveContext.withTransform(position = position, ...)
output += renderFunction(localContext)
```
1. `withTransform(position=(2,0,0))` — rotates the local (2,0,0) by the accumulated rotation (PI/4) → rotated offset ≈ (1.414, 1.414, 0)
2. Adds rotated offset to accumulated position → new position ≈ (6.414, 1.414, 0)
3. **Result**: The child's +2 X offset is along the **parent's rotated local X axis** → final position ≈ **(6.414, 1.414, 0.0)** in world space

**Impact**: The `position` parameter means different things depending on whether you use `Shape(position=...)` or `CustomNode(position=...)`. A user migrating from a built-in shape to a custom node (or vice versa) under a rotated parent will see the geometry jump to a different location. This is a semantic inconsistency introduced by WS6 — the existing leaf nodes have always used world-space offsets, but the new `CustomRenderNode` correctly uses parent-space offsets, creating a visible discrepancy.

**Fix**: There are two approaches:

**(a) Align CustomRenderNode with existing leaf behavior** (preserves backward compatibility):
```kotlin
// In CustomRenderNode.renderTo():
val localContext = effectiveContext  // don't call withTransform
val result = applyLocalTransforms(...) // apply transforms the same way as ShapeNode
// But this breaks the context-passing to renderFunction
```

**(b) Align leaf nodes with CustomRenderNode** (correct behavior, breaking change):
Make `ShapeNode`/`PathNode`/`BatchNode` feed their local transforms through `context.withTransform()` and apply the resulting context to the shape, matching `CustomRenderNode`'s parent-space semantics:
```kotlin
// In ShapeNode.renderTo():
val localContext = context.withTransform(position = position, rotation = rotation, scale = scale, ...)
val transformedShape = localContext.applyTransformsToShape(shape)
```

Option (b) is recommended per the user's preference for direct breaking changes over deprecation. The current leaf behavior is a pre-existing quirk that WS6 exposed by introducing a node type that does it correctly.

---

## S10 — Per-Subtree `renderOptions` on GroupNode Is Architecturally Non-Functional (Should-fix)

**Files**: `IsometricNode.kt` lines 165–169 (GroupNode.renderTo), `SceneCache.kt` lines 80–129 (rebuild), `RenderContext.kt`

**Problem**: `GroupNode.renderTo()` correctly stores the overridden `renderOptions` in the child context:

```kotlin
val effectiveContext = if (renderOptions != null) {
    context.withRenderOptions(renderOptions!!)
} else { context }
```

But this override has **no downstream effect** on how the engine processes the commands produced by children. The rendering pipeline works as follows:

1. `SceneCache.rebuild()` calls `rootNode.renderTo(commands, context)` — collects all `RenderCommand` objects
2. Per-subtree `renderOptions` overrides modify the `RenderContext` passed to child nodes during traversal
3. But `RenderContext.applyTransformsToShape/Path()` never reads `renderOptions` — transforms are independent of render options
4. `RenderCommand` objects don't carry per-command `renderOptions`
5. `engine.projectScene(width, height, renderOptions=context.renderOptions, ...)` uses the **top-level** renderOptions for ALL commands — culling, depth sorting, and bounds checking are applied uniformly

**Traced data flow**:
```
GroupNode(renderOptions=NoCulling)
  └── ShapeNode(shape=prism)
       └── renderTo() produces RenderCommand{ originalPath=..., color=... }
            └── SceneCache feeds to engine.add(path, color, ...)
                 └── engine.projectScene(renderOptions=TOP_LEVEL_OPTIONS)
                      └── culls/sorts ALL commands with the SAME renderOptions
```

The per-subtree `RenderOptions(enableBackfaceCulling=false)` set on the GroupNode is stored in the child context but never consumed by any code that affects rendering behavior. It is only visible to `CustomRenderNode` render functions that explicitly read `context.renderOptions`.

**Impact**: Users who set `Group(renderOptions = RenderOptions(enableBackfaceCulling = false))` expecting the group's children to skip backface culling will see no behavioral change. The API suggests per-subtree control but delivers global-only processing.

**Relationship to S2**: S2 covers leaf nodes ignoring their own `renderOptions` property. S10 covers the deeper architectural issue: even `GroupNode`'s correctly-propagated override doesn't reach the engine's processing pipeline. Together, they mean per-subtree `renderOptions` is a non-functional feature for all standard nodes (only `CustomRenderNode` benefits).

**Fix**: Either:
- **(a)** Remove `renderOptions` from all node types except `CustomRenderNode` and document that `renderOptions` is scene-global. Users who need per-shape culling control should use `CustomNode`.
- **(b)** Redesign the rendering pipeline to carry `renderOptions` on each `RenderCommand`, then apply per-command render options during `projectScene()`. This is a significant engine change.

Option (a) is recommended for WS6 scope. Option (b) can be deferred to a future workstream.

---

## S11 — `CustomRenderNode` Commands Are Non-Hit-Testable via Composable API (Should-fix)

**Files**: `IsometricNode.kt` lines 284–308 (CustomRenderNode), `IsometricComposables.kt` lines 297–328 (CustomNode composable), `HitTestResolver.kt` lines 116–118 (findNodeByCommandId), lines 120–134 (buildCommandMaps)

**Problem**: The `CustomNode` composable's render function `(context: RenderContext) -> List<RenderCommand>` has no access to the underlying `CustomRenderNode`'s `nodeId`. The user cannot set `ownerNodeId` on their commands to match the node's identity:

```kotlin
// User's render function — no way to get the node's auto-generated nodeId
CustomNode(render = { context ->
    listOf(
        RenderCommand(
            commandId = "my_triangle",
            points = emptyList(),
            color = IsoColor.RED,
            originalPath = transformedPath,
            originalShape = null,
            ownerNodeId = null  // ← Can't be set to CustomRenderNode.nodeId
        )
    )
})
```

Meanwhile, all built-in nodes (`ShapeNode`, `PathNode`, `BatchNode`) automatically set `ownerNodeId = nodeId`:

```kotlin
// ShapeNode.renderTo() — always links to the node
output.add(RenderCommand(
    commandId = "${nodeId}_${path.hashCode()}",
    ...
    ownerNodeId = nodeId  // ← always set
))
```

**Traced data flow**:
1. `CustomRenderNode.renderTo()` → `output += renderFunction(localContext)` — user commands added with `ownerNodeId = null`
2. `SceneCache.rebuild()` → `engine.add(path = command.originalPath, ownerNodeId = command.ownerNodeId)` — null forwarded
3. `engine.projectScene()` → creates `RenderCommand(ownerNodeId = item.ownerNodeId)` — null preserved
4. `HitTestResolver.buildCommandMaps()` → `command.ownerNodeId?.let { nodeIdMap[it] }` → null ownerNodeId → no entry in `commandToNodeMap`
5. `hitTest()` → `findNodeByCommandId(hit.commandId)` → `commandToNodeMap[commandId]` → returns **null**

Even though the `CustomRenderNode` IS registered in `nodeIdMap` (via `buildNodeIdMap` tree traversal), the commands can't link back to it because the render function doesn't know the node's ID.

**Impact**: Scenes that mix built-in shapes (hit-testable) and custom nodes (not hit-testable) have inconsistent tap behavior. Tapping a `Shape` returns the node; tapping a `CustomNode`'s geometry always returns null. There is **no workaround** through the composable API — the render function closure is created before the `CustomRenderNode` is constructed, and the node's `nodeId` is never exposed through `RenderContext`.

**Fix**: Pass the node's `nodeId` to the render function. Change the signature to include the node identity:

```kotlin
class CustomRenderNode(
    var renderFunction: (context: RenderContext, nodeId: String) -> List<RenderCommand>
) : IsometricNode() {
    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        // ...
        output += renderFunction(localContext, nodeId)
    }
}
```

Update the `CustomNode` composable accordingly:

```kotlin
fun IsometricScope.CustomNode(
    // ...
    render: (context: RenderContext, nodeId: String) -> List<RenderCommand>
)
```

Users can then set `ownerNodeId = nodeId` on their commands for hit-test compatibility. The built-in test examples should also be updated to demonstrate this pattern.

---

## C1 — `screenToWorld` Determinant Guard Uses Exact Float Equality (Could-improve)

**File**: `IsometricProjection.kt` line 61

**Problem**: The degenerate matrix guard uses exact equality:

```kotlin
require(det != 0.0) { "Degenerate projection matrix — cannot invert" }
```

For `angle = PI/2`:
- `cos(PI/2)` ≈ 6.12e-17 (not exactly 0)
- `sin(PI - PI/2) = sin(PI/2)` = 1.0
- `det` ≈ `scale * 6.12e-17` — non-zero but numerically degenerate

The guard passes, but the inverse produces `worldX` and `worldY` values that are off by orders of magnitude.

**Impact**: Low — standard isometric angles (PI/6, PI/4) produce well-conditioned matrices. Only exotic angles near 0 or PI/2 trigger the issue.

**Fix**: Use a threshold-based check:
```kotlin
require(kotlin.math.abs(det) > 1e-10) { "Near-degenerate projection matrix (det=$det) — cannot reliably invert" }
```

---

## C2 — Step 7c Drag→Pan Convenience Not Implemented (Could-improve)

**Files**: `IsometricScene.kt` (pointer input handler, lines 228–289)

**Problem**: The plan's Step 7c describes an optional convenience integration:

> *"When `config.cameraState` is provided and `config.gestures` is enabled, the default drag behavior can optionally drive camera panning. [...] if the user provides both `config.cameraState` and `config.gestures.onDrag`, the explicit drag callback takes precedence."*

The current implementation doesn't connect drag gestures to `cameraState.pan()`. Users must manually wire `onDrag` to camera pan:

```kotlin
IsometricScene(
    config = SceneConfig(
        cameraState = camera,
        gestures = GestureConfig(
            enabled = true,
            onDrag = { event -> camera.pan(event.x, event.y) }
        )
    )
)
```

**Impact**: Minor convenience gap. The manual wiring is straightforward and explicit.

**Fix**: Add default drag→pan forwarding when cameraState is non-null and no explicit `onDrag` is provided:

```kotlin
if (isDragging) {
    val drag = DragEvent(delta.x.toDouble(), delta.y.toDouble())
    if (currentGestures.onDrag != null) {
        currentGestures.onDrag.invoke(drag)
    } else {
        config.cameraState?.pan(drag.x, drag.y)
    }
}
```

---

## C3 — `LocalIsometricEngine` Not `rememberUpdatedState`-Backed (Could-improve)

**File**: `IsometricScene.kt` lines 185, 191–209

**Problem**: The engine reference is resolved once and captured in the `DisposableEffect` closure:

```kotlin
// Line 185 — computed once via remember()
val isometricEngine = remember(engine) { engine as? IsometricEngine }

// Lines 190-209 — captured in DisposableEffect(composition)
DisposableEffect(composition) {
    composition.setContent {
        // isometricEngine captured at lambda-creation time
        if (isometricEngine != null) {
            add(LocalIsometricEngine provides isometricEngine)
        }
    }
    ...
}
```

If the `config.engine` reference changes (e.g., user replaces the engine instance), `isometricEngine` recomputes via `remember(engine)`, but the `DisposableEffect(composition)` doesn't re-run because its key (`composition`) hasn't changed. The child composition's `setContent` lambda still captures the **stale** `isometricEngine` reference.

Compare with other locals like `currentDefaultColor` which use `rememberUpdatedState()` — these auto-update through the State indirection. `isometricEngine` uses plain `remember()`, which doesn't have this indirection.

**Impact**: Low — the engine is typically static (the `LocalIsometricEngine` KDoc documents this). Only affects users who dynamically swap the engine instance, which is an uncommon pattern. The `staticCompositionLocalOf` declaration further reinforces that changes to this value trigger full subtree recomposition.

**Fix**: Use `rememberUpdatedState` for consistency:
```kotlin
val currentIsometricEngine by rememberUpdatedState(
    remember(engine) { engine as? IsometricEngine }
)
```
Then reference `currentIsometricEngine` inside the `setContent` lambda.

---

## Pass 2 — Code Boundary Analysis

### Transform Pipeline Audit

The transform application pipeline has two distinct patterns:

| Pattern | Used by | `position` semantics | Mechanism |
|---------|---------|---------------------|-----------|
| **Context-accumulate** | `GroupNode`, `CustomRenderNode` | Parent-local space | `context.withTransform(position=...)` rotates local position by parent's accumulated rotation before adding |
| **Post-apply** | `ShapeNode`, `PathNode`, `BatchNode` | World space | `applyLocalTransforms(context.applyTransformsToShape(shape))` adds local position as raw world-space offset after context transforms are baked in |

This inconsistency (see S9) means the same `position=(2,0,0)` under a rotated parent produces different world positions depending on the node type. The context-accumulate pattern (GroupNode/CustomRenderNode) is the correct behavior for hierarchical transforms.

### Cache Invalidation Audit

`SceneCache.needsUpdate()` checks exactly five fields:

| Field | Source | Triggers rebuild? |
|-------|--------|-------------------|
| `rootNode.isDirty` | Node tree mutations via Applier | Yes |
| `!cacheValid` | Explicit `clearCache()` call | Yes |
| `width != cachedWidth` | Canvas size change | Yes |
| `height != cachedHeight` | Canvas size change | Yes |
| `currentInputs != cachedPrepareInputs` | `renderOptions` or `lightDirection` change | Yes |

**Not tracked**: Engine internal state (`angle`, `scale`, `colorDifference`, `lightColor`). The `SceneProjector` interface has no state-version concept. This gap (S1) means mutable engine params create invisible staleness.

### Camera ↔ Hit-Test Coordinate Analysis

| Component | Coordinate space |
|-----------|-----------------|
| Engine `projectScene()` | Engine screen space (origin at viewport center, 0.9 Y) |
| `PreparedScene` commands | Engine screen space |
| Camera `translate`/`scale` | Applied to DrawScope — visual output only |
| Pointer events (`position`) | Raw screen space (Canvas pixel coordinates) |
| `renderer.hitTest(x, y)` | Expects engine screen space |

**Mismatch**: Pointer events deliver raw screen coordinates. Hit testing expects engine screen coordinates. Camera transforms create a divergence between the two. See S8.

### renderOptions Data Flow

```
User sets: Group(renderOptions = RenderOptions(enableBackfaceCulling = false))
         ↓
GroupNode.renderTo(): effectiveContext = context.withRenderOptions(noCulling)
         ↓
Child ShapeNode.renderTo(): uses effectiveContext for applyTransformsToShape()
    → but applyTransformsToShape() never reads renderOptions
    → produces RenderCommand (no renderOptions field)
         ↓
SceneCache.rebuild(): engine.projectScene(renderOptions = context.renderOptions)
    → uses TOP-LEVEL context.renderOptions for ALL commands
    → per-subtree override is lost
```

Per-subtree `renderOptions` is only meaningful for `CustomRenderNode`, where the user function can read `context.renderOptions` and make custom decisions. For all standard nodes, the override is propagated through the context but never consumed. See S2 and S10.

---

## Test Coverage Assessment

| Step | Test file | Coverage |
|------|-----------|----------|
| 1 | `Point2DTest.kt` (108 lines) | **Strong** — all operators, distance methods, edge cases (midpoint, lerp, radius check) |
| 2 | `WS6EscapeHatchesTest.kt` | **Adequate** — validates zero-override compilation |
| 3 | `IsometricEngineProjectionTest.kt` | **Strong** — trig correctness, ordering preservation, multi-angle, path depth |
| 4 | `IsometricEngineProjectionTest.kt` | **Strong** — round-trip at z=0, z≠0, custom angle, custom scale, origin check |
| 5 | `IsometricEngineProjectionTest.kt` | **Strong** — mutable params, validation (negative, zero, NaN, Infinity), round-trip after mutation |
| 6 | Not tested | **Gap** — no test for `LocalIsometricEngine` provision or error on missing |
| 7 | `WS6EscapeHatchesTest.kt` | **Adequate** — defaults, pan, zoomBy, negative rejection, reset |
| 8 | Not tested | **Gap** — no test for render hooks (requires DrawScope/Canvas context) |
| 9 | `WS6EscapeHatchesTest.kt` | **Adequate** — GroupNode override, null inheritance, transform preservation |
| 10 | `WS6EscapeHatchesTest.kt` | **Strong** — triangle render, visibility, transform accumulation, local position, multi-command, renderOptions override |

**Notable test gaps**:
- No test for `CameraState.zoom` with direct assignment of invalid values (would expose S6)
- No test for mutable engine params triggering scene update (would expose S1)
- No integration test for `onPreparedSceneReady` callback timing (would expose S4)
- No test for hit testing with active camera transforms (would expose S8)
- No test comparing ShapeNode vs CustomRenderNode position behavior under rotated parent (would expose S9)
- No test verifying per-subtree renderOptions actually affects engine culling (would expose S10)
- No test verifying CustomRenderNode commands are hit-testable (would expose S11 — all existing tests use `ownerNodeId = null`)
- Step 6 and Step 8 lack dedicated tests (acceptable — these require Compose test infrastructure)

---

## Code Quality Notes

**Positive observations**:
- `Point2D` operators exactly match the plan spec — clean, idiomatic Kotlin
- `IsometricProjection.screenToWorld()` correctly solves the 2x2 linear system via Cramer's rule — verified algebraically that forward/inverse round-trip is exact (det = scale² × sin(2×angle))
- `RenderContext.withRenderOptions()` correctly preserves all accumulated transforms
- `CameraState` properly uses `@Stable` annotation with `mutableDoubleStateOf` for Compose integration
- `CustomRenderNode.renderTo()` correctly applies both renderOptions and transform overrides — uses the context-accumulate pattern, which is the correct hierarchical transform approach
- `SceneConfig.equals()` correctly uses identity (`===`) for CameraState and `System.identityHashCode` for hashCode — appropriate for mutable state objects
- `LocalIsometricEngine` uses `staticCompositionLocalOf` with conditional provision via safe cast — graceful handling of non-IsometricEngine projectors
- `IsometricEngine.init` ordering is correct: property backing fields are assigned before the `init` block validates them, and `projection` initialization uses the already-validated property values. Custom setters are NOT invoked during construction (Kotlin semantics), so `rebuildProjection()` doesn't run before `projection` is first assigned.
- `IsometricApplier` correctly guards all mutation methods (`insertBottomUp`, `remove`, `move`) with `current as? GroupNode ?: error(...)`, preventing children from being added to non-container nodes
- `BenchmarkHooksImpl` correctly overrides all 6 hook methods with timing instrumentation — the default empty bodies from Step 2 have zero overhead when `benchmarkHooks` is null (the production path)

---

## Pass 3 — Systematic File-by-File Review

### Methodology

Performed a line-by-line read of all 13 modified/new source files and 3 test files, then traced each code path for correctness — forward projection algebra, Cramer's rule solution, Kotlin init ordering, closure capture semantics, operator definitions, `@Stable` contract compliance, and composable-to-node data flow.

### File-by-File Findings

| File | Status | Notes |
|------|--------|-------|
| `Point2D.kt` | **Clean** | All operators correct, `distanceTo`/`distanceToSquared` consistent, `ZERO` companion correct |
| `Point.kt` | **S5** | `depth(angle)` formula correct; KDoc on legacy `depth()` is misleading (existing finding) |
| `Path.kt` | **Clean** | `fun depth(angle)` delegates correctly; `val depth` precalculated at construction time; no name-shadowing issue (property vs function distinguished by call syntax) |
| `IsometricProjection.kt` | **Clean** | Cramer's rule algebra verified: forward `translatePoint` ↔ inverse `screenToWorld` round-trip is exact. `rhs2 = originY - screenPoint.y - z * scale` correctly derived from forward equation. **C1** remains (determinant guard) |
| `IsometricEngine.kt` | **S1** | Init ordering verified correct: property initializers → `init` block → `projection` initializer. Custom setters NOT invoked during construction (Kotlin semantics) — `rebuildProjection()` doesn't run before `projection` is first assigned. Cache invalidation gap (existing S1) confirmed |
| `CameraState.kt` | **S6** | `@Stable` contract honored — `mutableDoubleStateOf` fields ARE the observables. Validation gap on direct `zoom`/`panX`/`panY` assignment confirmed (existing S6) |
| `IsometricRenderer.kt` | **Clean** | `RenderBenchmarkHooks` default empty bodies correct; `ensurePreparedScene` error handling preserves cache on failure |
| `CompositionLocals.kt` | **Clean** | `staticCompositionLocalOf` appropriate for engine (rarely changes). Error factory provides actionable message |
| `SceneConfig.kt` | **Clean** | Identity comparison (`===`) and `System.identityHashCode` correct for mutable `CameraState` |
| `AdvancedSceneConfig.kt` | **Clean** | Callback properties deliberately excluded from `equals`/`hashCode` — correct because lambdas break structural equality and callbacks are read directly from config in SideEffect/draw blocks |
| `RenderContext.kt` | **Clean** | `withTransform` correctly scales then rotates child position; `withRenderOptions` preserves all accumulated state; `applyTransformsToPoint` always translates (minor: no zero-check unlike `applyTransformsToShape`) |
| `IsometricNode.kt` | **S2, S7, S9, S11** | ShapeNode/PathNode/BatchNode leaf transforms correct. CustomRenderNode transform approach correct (parent-space) but inconsistent with leaf nodes (S9). New finding: render function can't produce hit-testable commands (S11) |
| `IsometricComposables.kt` | **Clean** | All composables correctly wire properties to node fields via `set(...)`. `CustomNode` KDoc example shows `context.applyTransformsToPath()` usage — correct guidance |
| `IsometricScene.kt` | **S3, S4, S8, C3** | Camera transforms, draw-phase callback, hit-test coordinates, stale engine capture — all existing findings confirmed. `canvasWidth`/`canvasHeight` state writes inside draw block are pre-existing (not WS6) and stabilize after one frame |

### New Finding

**S11**: `CustomRenderNode` commands are non-hit-testable through the composable API. The render function `(RenderContext) -> List<RenderCommand>` has no access to the node's `nodeId`, so users cannot set `ownerNodeId` on their commands. All test examples normalize this gap by using `ownerNodeId = null`. See S11 detail section above.

### Verified Correct (No Issues Found)

- **Cramer's rule algebra**: Forward projection ↔ inverse projection round-trip verified exact for all `(x, y, z)` inputs. Determinant = `scale² × sin(2×angle)`, non-zero for standard angles.
- **Kotlin init ordering**: `IsometricEngine` property initializers execute before the `init` block; `projection` field is assigned after `angle`/`scale` properties. Custom setters are not invoked during property initialization — safe.
- **Path.depth name overload**: `val depth: Double` (property) and `fun depth(angle: Double)` (function) coexist without shadowing — Kotlin distinguishes by call syntax (`path.depth` vs `path.depth(angle)`).
- **CustomRenderNode error handling**: If `renderFunction` throws during `SceneCache.rebuild()`, the `try/catch` preserves the previous valid cache and reports via `onRenderError`. Correct.
- **Transform application order in CustomRenderNode**: User calls `context.applyTransformsToPath(path)` → 3D transforms → commands fed to engine → engine applies 3D-to-2D projection. No double-transform application.

---

## Verdict

**11 should-fix**, **3 could-improve** — the implementation covers all 10 planned steps and is structurally sound at the individual-step level. However, the code-boundary analysis in Pass 2 and the systematic file-by-file review in Pass 3 revealed significant cross-cutting issues that were invisible from a per-step review.

**Critical findings** (break primary feature use cases):
- **S1** (stale cache): Mutable engine params silently produce stale rendering — the feature's primary use case is broken
- **S8** (hit-test coordinates): Camera pan/zoom completely breaks tap detection — CameraState + GestureConfig cannot be used together
- **S9** (transform inconsistency): ShapeNode and CustomRenderNode interpret `position` differently under rotated parents — a correctness gap exposed by WS6's new node type

**Significant findings** (misleading API surface):
- **S2 + S10** (renderOptions): Per-subtree `renderOptions` is architecturally non-functional for standard nodes — the API parameter is accepted but has no effect on engine culling/sorting behavior
- **S3** (zoom): Camera zoom expands from top-left, not center, contradicting KDoc
- **S11** (hit testing): CustomNode commands are non-hit-testable through the composable API — render function has no access to `nodeId`, creating inconsistent tap behavior when mixing Shape and CustomNode

**Lower-severity findings** (validation gaps and cleanup):
- **S4** (draw-phase callback), **S5** (KDoc), **S6** (zoom validation), **S7** (dead children list)

The S1+S8 pair is particularly impactful: Step 5 (mutable params) and Step 7 (CameraState) are the two highest-visibility user-facing features in WS6, and both have correctness issues that prevent real-world usage. These should be addressed before the workstream is considered shippable.
