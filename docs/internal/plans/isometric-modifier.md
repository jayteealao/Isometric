# Per-Node Interaction Props — WS10 Implementation Plan

## Executive Summary

WS10 should not introduce an `IsometricModifier` chain.

The current runtime does not preserve or interpret a modifier pipeline at render time. All
candidate WS10 values (`alpha`, `onClick`, `onLongClick`, `testTag`) end up as flat fields on
`IsometricNode` and are then read directly by the render and hit-test paths. A modifier chain
would therefore be syntactic indirection only.

The correct WS10 design is:

1. add direct per-node props to the relevant composables
2. preserve the current public signatures by adding overloads, not by inserting new parameters
3. add `nodeId: String? = null` as a nullable prop so users can provide stable identity when needed
4. keep scene-level gesture routing, but have `IsometricScene` dispatch directly to the hit node

This keeps the hero path obvious, matches the current runtime architecture, and avoids adding a
second modifier concept beside Compose `Modifier`.

---

## 1. API Decision

### 1.1 Primary API shape

Expose per-node props directly on composables:

```kotlin
Shape(
    geometry = prism,
    color = IsoColor.BLUE,
    alpha = 0.5f,
    onClick = { selectedId = item.id },
    onLongClick = { showMenu(item.id) },
    testTag = "building-${item.id}",
    nodeId = item.id
)
```

### 1.2 Why this shape

This matches the actual runtime:

- `IsometricScene` hit-tests to `IsometricNode`
- `IsometricNode` stores flat fields
- `renderTo()` reads flat fields
- no modifier chain participates in rendering after compose time

So the direct-prop design is the smallest API that reflects the real execution model.

### 1.3 What WS10 is not doing

WS10 does **not** add:

- `IsometricModifier`
- `CombinedIsometricModifier`
- `foldIn` / `foldOut`
- modifier element data classes
- chain unpack helpers

That machinery would add complexity without adding real composition semantics.

---

## 2. Public API Surface

### 2.1 Renderable node composables

Add a second overload for these composables:

- `Shape`
- `Path`
- `Batch`
- `CustomNode`

The new overload adds:

- `alpha: Float = 1f`
- `onClick: (() -> Unit)? = null`
- `onLongClick: (() -> Unit)? = null`
- `testTag: String? = null`
- `nodeId: String? = null`

### 2.2 Group

`Group` is not directly rendered and is not directly hit-testable, so it should **not** expose
`alpha`, `onClick`, or `onLongClick` in v1.

`Group` should get a second overload with only:

- `testTag: String? = null`
- `nodeId: String? = null`

This keeps the surface honest:

- `Group.alpha` would imply subtree opacity, which the current runtime does not implement
- `Group.onClick` would imply a hittable group container, which the current hit-test model does not support

### 2.3 Add new parameters directly to existing signatures

Append the new props (all with defaults) to the existing composable signatures. Because every
new parameter has a default value, all existing call sites compile and behave identically
without any changes.

**Why not overloads?** Two overloads sharing the same required parameters with all-defaulted
optionals cause Kotlin overload resolution ambiguity. `@Deprecated(level = HIDDEN)` solves
ambiguity but adds unnecessary bridge boilerplate and a deprecation annotation that implies a
migration path this project does not want. Direct parameter addition is the simplest correct
approach.

### 2.4 Proposed signatures

#### Shape

```kotlin
fun IsometricScope.Shape(
    geometry: Shape,
    color: IsoColor = LocalDefaultColor.current,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null
)
```

Apply the same pattern to `Path`, `Batch`, and `CustomNode`.

#### Group

```kotlin
fun IsometricScope.Group(
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    renderOptions: RenderOptions? = null,
    testTag: String? = null,
    nodeId: String? = null,
    content: @Composable IsometricScope.() -> Unit
)
```

### 2.5 Parameter ordering

Append new props after the existing parameters, grouped by concern:

- geometry / color first (existing)
- alpha next (visual modifier, relates to color)
- transform / visibility (existing)
- interaction (`onClick`, `onLongClick`)
- testing / identity (`testTag`, `nodeId`)

---

## 3. Node Model Changes

### 3.1 `IsometricNode`

Add these fields to `IsometricNode`:

```kotlin
var alpha: Float = 1f
var onClick: (() -> Unit)? = null
var onLongClick: (() -> Unit)? = null
var testTag: String? = null
var explicitNodeId: String? = null
```

Change ID handling from a single generated `val` to an internal generated ID plus an effective ID:

```kotlin
private val generatedNodeId: String = "node_${nextId.getAndIncrement()}"

val nodeId: String
    get() = explicitNodeId ?: generatedNodeId
```

Validation rules:

- `alpha` must be in `0f..1f`
- `nodeId`, when non-null, must be non-blank

### 3.2 Why `explicitNodeId` is nullable

Most callers should not need to think about node identity.

`nodeId` is therefore:

- optional
- stable when supplied
- auto-generated when omitted

This preserves the simple path while enabling advanced routing, testing, and analytics use cases.

---

## 4. Render and Hit-Test Integration

### 4.1 Alpha

`alpha` is applied at node render time by modifying the command color before it enters the prepared scene.

Implementation path:

1. `ShapeNode.renderTo()`, `PathNode.renderTo()`, and `BatchNode.renderTo()` use `color.withAlpha(alpha)`
2. `CustomRenderNode.renderTo()` post-processes each returned `RenderCommand` by multiplying its existing `color.a` via `command.color.withAlpha(alpha)`
3. the resulting `RenderCommand.color` flows through projection and draw unchanged
4. Compose/native draw uses the color alpha already embedded in the command

Required pre-check:

- verify `IsoColor.a` already maps correctly in the color bridge used by `toComposeColor()`

### 4.2 Click routing

In `IsometricScene.kt`, the release handler already:

1. computes corrected hit coordinates
2. resolves `hitNode`
3. calls `currentGestures.onTap?.invoke(TapEvent(...))`

Add:

```kotlin
hitNode?.onClick?.invoke()
```

after the scene-level `onTap` callback.

That preserves existing scene-level behavior and adds the node-local path.

### 4.3 Long click

Add long-press detection inside the existing `pointerInput` block in `IsometricScene.kt`.

On `Press`:

- start a coroutine with `LONG_PRESS_TIMEOUT_MS = 500L`

On `Move` past drag threshold or `Release`:

- cancel the coroutine

If the timeout completes:

1. convert the stored press position through the same camera inverse-transform used by tap hit testing
2. hit-test at those corrected coordinates
2. call `hitNode?.onLongClick?.invoke()`

WS10 does **not** add a scene-level `GestureConfig.onLongPress` callback. That is a separate API decision.

### 4.4 `nodeId` and hit-test mapping

Because hit testing maps commands back to nodes by `ownerNodeId`, custom `nodeId` must be treated as
a correctness-sensitive value.

That means WS10 must also change `HitTestResolver.buildNodeIdMap()` so duplicate node IDs are a
hard failure, not silent map overwrite.

Required behavior:

```kotlin
if (map.put(node.nodeId, node) != null) {
    error("Duplicate nodeId '${node.nodeId}' detected. nodeId values must be unique within a scene.")
}
```

This is required for:

- `TapEvent.node`
- per-node click routing
- command-to-node resolution
- any future caller-owned routing keyed by `nodeId`

---

## 5. Evolution Strategy

### 5.1 Direct parameter addition

Append new defaulted parameters to existing signatures. No overloads, no bridges, no
deprecation annotations. One signature, one implementation per composable.

All existing call sites continue to compile unchanged because every new parameter has a
default value. No source churn across samples, tests, or docs.

---

## 6. Concrete File Changes

### 6.1 Runtime code

| File | Change |
|---|---|
| `isometric-compose/.../IsometricComposables.kt` | Add `alpha`, `onClick`, `onLongClick`, `testTag`, `nodeId` parameters to `Shape`, `Path`, `Batch`, `CustomNode`; add `testTag`, `nodeId` to `Group`; wire new props into node update blocks |
| `isometric-compose/.../IsometricNode.kt` | Add `alpha`, `onClick`, `onLongClick`, `testTag`, `explicitNodeId`; change `nodeId` to computed property; apply alpha in renderable nodes |
| `isometric-compose/.../IsometricScene.kt` | Dispatch `hitNode?.onClick?.invoke()` on release; add long-press detection for `onLongClick` |
| `isometric-compose/.../HitTestResolver.kt` | Reject duplicate effective `nodeId` values when building node map |
| `isometric-core/.../IsoColor.kt` | Add `withAlpha(alpha: Float): IsoColor` if not already present |
| compose color bridge file | Verify `IsoColor.a` is preserved into Compose/native colors |

### 6.2 Secondary runtime surfaces to audit

| File | Why |
|---|---|
| `Stack.kt` | No API change required; it delegates to `Group` and child composables |
| `TileGrid.kt` | No API change required; tile interaction remains scene-level via `TileGestureHub` |
| `HitTestResolver.kt` | Required for custom `nodeId` correctness |

### 6.3 Docs and examples to update

| File set | Change |
|---|---|
| `docs/examples/interactive-scenes.md` | Add direct `onClick` example and stable `nodeId` example |
| `docs/getting-started/quickstart.md` | Show direct per-node click path, not caller-owned routing tables |
| `docs/reference/composables.md` | Document new overloads and the `nodeId` uniqueness rule |
| `docs/guides/gestures.md` | Add per-node click/long-click routing notes |

---

## 7. Tests

### 7.1 Unit tests

Add or update tests for:

- `alpha` validation rejects values outside `0f..1f`
- `nodeId = ""` / blank rejects at update time
- `IsoColor.withAlpha()` scales alpha correctly
- duplicate `nodeId` values throw a clear error during hit-test index rebuild
- existing call sites without new params still compile and behave identically

### 7.2 Compose/runtime tests

Add or update tests for:

- `Shape(onClick = ...)` invokes on tap
- scene-level `onTap` still fires when `onClick` is present
- `Shape(onLongClick = ...)` invokes after timeout
- `visible = false` suppresses click routing
- `alpha = 0.5f` changes rendered command alpha
- `CustomNode(alpha = 0.5f)` scales returned command alpha without replacing the command color outright
- `testTag` is stored on the node
- `nodeId` overrides the generated ID
- `CustomNode(nodeId = "...")` still passes the effective node ID to its render lambda

### 7.3 Backward compatibility tests

Verify that call sites omitting all new parameters behave identically to pre-WS10:

- rendering remains unchanged
- hit testing still returns nodes
- no new parameter is accidentally required

---

## 8. Implementation Steps

### Phase 1 — Node storage and ID model

1. Add `alpha`, `onClick`, `onLongClick`, `testTag`, `explicitNodeId` to `IsometricNode`
2. convert `nodeId` to `generatedNodeId + explicitNodeId` model
3. add `IsoColor.withAlpha()` if needed
4. add duplicate `nodeId` detection in `HitTestResolver`

### Phase 2 — Public API expansion

5. add `alpha`, `onClick`, `onLongClick`, `testTag`, `nodeId` to `Shape`, `Path`, `Batch`, `CustomNode`
6. add `testTag`, `nodeId` to `Group`
7. wire new parameters into node update blocks
8. validate `alpha` and `nodeId` in the update blocks

### Phase 3 — Runtime behavior

9. apply `alpha` inside renderable node `renderTo()` implementations
10. route `hitNode?.onClick?.invoke()` from `IsometricScene`
11. add long-press handling for `onLongClick`

### Phase 4 — Docs and tests

12. update docs/examples
13. add direct-prop interaction tests
14. add duplicate-`nodeId` failure tests
15. add backward compatibility tests (omitted params behave identically)

---

## 9. Open Decisions

### D1 — `testTag` on `Group`

Recommendation: yes.

Reason:

- useful for tree traversal and testing
- no semantic conflict with rendering or hit testing

### D2 — `nodeId` on `Group`

Recommendation: yes.

Reason:

- stable identity is useful even for container nodes
- future tooling / diagnostics can rely on it
- no extra runtime complexity beyond the effective-ID model

### D3 — Scene-level long press

Recommendation: not in WS10.

Reason:

- per-node `onLongClick` is the immediate feature gap
- scene-level long press changes `GestureConfig` and should be a separate API decision

### D4 — `alpha` on `Group`

Recommendation: no.

Reason:

- current runtime has no subtree opacity model
- exposing it now would create a misleading surface

### D5 — `onClick` / `onLongClick` on `Group`

Recommendation: no.

Reason:

- `Group` does not emit render commands
- current hit testing resolves rendered geometry, not abstract containers

---

## 10. Final Scope

WS10 should ship:

- direct per-node props
- node-local click and long-click routing
- stable optional `nodeId`
- duplicate `nodeId` enforcement
- direct parameter addition to existing signatures

WS10 should not ship:

- `IsometricModifier`
- group subtree opacity
- scene-level long-press API redesign
- caller-owned handler maps as the primary interaction pattern
