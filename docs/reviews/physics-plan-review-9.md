# Physics Implementation Plan -- Review Round 9

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R8)
> **Status**: Issues identified -- plan revision recommended

---

## Review Summary

The R8 revision addresses 107 issues across eight prior review rounds. The plan is mature, the core physics architecture is sound, and the fix tracking is exemplary. After a thorough reading of the full ~3075-line plan and cross-referencing against the actual codebase (`IsometricScene.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `IsometricApplier.kt`, `RenderContext.kt`, all shape classes, `Vector.kt`, `Point.kt`, `Path.kt`, `IsometricEngine.kt`, `IsometricComposables.kt`, `IsometricScope.kt`, `CompositionLocals.kt`, and both `build.gradle.kts` files), I have identified **1 critical issue** and **2 suggestions** that are genuinely new -- not repeats of any previously reported findings.

The critical issue is a cache invalidation gap in the rendering pipeline: `syncPositionsToNodes` updates physics positions on `PhysicsShapeNode` instances but never signals the renderer's `PreparedScene` cache to rebuild. This would cause physics shapes to appear frozen at their initial positions.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. `syncPositionsToNodes` does not call `markDirty()` -- `IsometricRenderer` serves stale cached `PreparedScene` and physics shapes appear frozen

The plan's `syncPositionsToNodes` (section 4b.4a, lines 2257-2269) writes interpolated physics positions into `PhysicsShapeNode` fields:

```kotlin
private fun syncPositionsToNodes(node: IsometricNode, snapshot: Map<Int, PhysicsStep.BodySnapshot>) {
    if (node is PhysicsShapeNode) {
        val snap = snapshot[node.bodyId]
        if (snap != null) {
            node.physicsPosition = snap.position
            node.physicsOrientation = snap.orientation
        }
    }
    for (child in node.childrenSnapshot) {
        syncPositionsToNodes(child, snapshot)
    }
}
```

The function modifies `physicsPosition` and `physicsOrientation` but does **not** call `markDirty()` on the node or any ancestor. The plan says (line 2188): "frameVersion is the SOLE trigger for redraws."

However, `frameVersion` only triggers the Compose `Canvas` lambda to re-execute. Inside the Canvas draw pass, the renderer calls `ensurePreparedScene()`, which checks whether the cache needs rebuilding:

```kotlin
// IsometricRenderer.kt, actual codebase lines 338-350
internal fun needsUpdate(
    rootNode: GroupNode,
    context: RenderContext,
    width: Int,
    height: Int
): Boolean {
    val currentInputs = PrepareInputs(context.renderOptions, context.lightDirection)
    return rootNode.isDirty ||
            !cacheValid ||
            width != cachedWidth ||
            height != cachedHeight ||
            currentInputs != cachedPrepareInputs
}
```

Since `syncPositionsToNodes` never calls `markDirty()`, `rootNode.isDirty` remains `false`. The `cacheValid` flag, viewport size, and `PrepareInputs` are also unchanged between physics frames. Therefore `needsUpdate()` returns `false`, and the renderer serves the stale cached `PreparedScene` containing positions from the **first** frame when the cache was built.

The `PhysicsShapeNode.render()` method (section 4b.2, lines 2020-2061) correctly reads from `physicsPosition`/`physicsOrientation` and produces updated paths. But `render()` is only called from `rebuildCache()`, which is only called when `needsUpdate()` returns `true`. With `isDirty == false`, `rebuildCache()` is never called on subsequent physics frames. Physics shapes would appear frozen at their initial positions.

**Evidence**: Plan lines 2257-2269 (no `markDirty()` call), plan line 2188 ("frameVersion is the SOLE trigger"), actual codebase `IsometricRenderer.kt` lines 338-350 (`needsUpdate` checks `rootNode.isDirty`), actual codebase `IsometricNode.kt` lines 79-88 (`markDirty()` propagates to root and triggers `onDirty` callback).

**Why this was not caught before**: Previous reviews focused on the threading model (R1-C2, R2-I2), the `syncPositionsToNodes` traversal correctness (R3-C3), the root node access path (R4-C1), and the body collection mutation race (R8-C1). The renderer's `PreparedScene` caching was introduced in the actual codebase as a performance optimization after the physics plan was first drafted. The plan implicitly assumes that incrementing `frameVersion` is sufficient to force rendering updates, but the renderer's cache layer requires `isDirty` to be set for scene-graph-driven invalidation.

**Fix**: Call `markDirty()` on the root node after syncing all positions. This triggers a single dirty propagation (root is already the top of the tree) and sets `rootNode.isDirty = true`, which causes `needsUpdate()` to return `true` on the next draw pass:

```kotlin
private fun syncPositionsToNodes(
    root: IsometricNode,
    snapshot: Map<Int, PhysicsStep.BodySnapshot>
) {
    var anyUpdated = false
    fun walk(node: IsometricNode) {
        if (node is PhysicsShapeNode) {
            val snap = snapshot[node.bodyId]
            if (snap != null) {
                node.physicsPosition = snap.position
                node.physicsOrientation = snap.orientation
                anyUpdated = true
            }
        }
        for (child in node.childrenSnapshot) {
            walk(child)
        }
    }
    walk(root)
    if (anyUpdated) {
        root.markDirty()  // triggers isDirty=true, which invalidates PreparedScene cache
    }
}
```

Calling `root.markDirty()` once per frame (not per-node) is efficient -- it sets a single boolean flag and invokes the `onDirty` callback. The renderer already handles the dirty flag correctly: `rebuildCache()` calls `rootNode.clearDirty()` after processing, so the flag is clean for the next frame.

This also makes `frameVersion++` redundant for physics-driven redraws (since `markDirty()` triggers `onDirty` which increments `sceneVersion` in `IsometricScene`, which triggers Canvas invalidation). However, `frameVersion` is still useful as a separate signal for external redraw requests, so it can be retained.

Alternatively, the `PhysicsScene` `LaunchedEffect` could call `rootNodeRef?.markDirty()` directly after `syncPositionsToNodes`:

```kotlin
rootNodeRef?.let { root ->
    syncPositionsToNodes(root, snapshot)
    root.markDirty()
}
```

This is the simpler fix and keeps `syncPositionsToNodes` free of side effects.

---

## IMPORTANT Issues

None identified. The plan is architecturally sound after 8 rounds of revision. The module split, threading model, snapshot-based position sync, compound colliders, island-based sleep, Compose integration, and body lifecycle management are all well-designed and internally consistent.

---

## SUGGESTIONS (Nice to Have)

### S1. `PhysicsScene` provides `LocalPhysicsSnapshot` with a lambda that captures `latestSnapshot` -- but the lambda is created once via `staticCompositionLocalOf`, so updates to `latestSnapshot.value` are invisible to consumers who cache the lambda result

The plan (section 4b.4, lines 2206-2208) provides the snapshot accessor:

```kotlin
CompositionLocalProvider(
    LocalPhysicsSnapshot provides { latestSnapshot.value }
)
```

`LocalPhysicsSnapshot` is declared as `staticCompositionLocalOf` (section 4b.3a, line 2141). The lambda `{ latestSnapshot.value }` captures `latestSnapshot` (a `MutableState`). Each call to the lambda reads `latestSnapshot.value`, which returns the latest snapshot.

This is actually correct as written -- the lambda is a closure over `latestSnapshot` (the `MutableState` container, not its value), so it always reads the current value when invoked. The `staticCompositionLocalOf` is appropriate because the lambda reference itself never changes, even though the data it returns does.

However, this is a subtle pattern that could trip up future maintainers. A brief inline comment in the plan explaining *why* this works (the lambda closes over the mutable state container, not the immutable value) would be helpful. The existing comments in section 4b.3a partially address this but the one in section 4b.4 does not.

### S2. `PhysicsScene` uses `frameVersion++` as the sole redraw trigger, but after C1 is fixed, `markDirty()` already triggers Canvas invalidation via `sceneVersion` -- `frameVersion` becomes redundant for physics frames

After applying the C1 fix, `root.markDirty()` propagates to `rootNode.onDirty`, which increments `sceneVersion` in `IsometricScene` (actual codebase line 118: `rootNode.onDirty = { sceneVersion++ }`). The Canvas lambda reads `sceneVersion`, so it re-executes on dirty. Additionally, `needsUpdate()` sees `rootNode.isDirty == true` and rebuilds the cache.

With this fix, `frameVersion++` in the physics sync loop becomes redundant -- the dirty flag already triggers both Canvas invalidation and cache rebuilding. The plan could simplify by removing the `frameVersion` increment from the physics sync loop, relying solely on `markDirty()`. However, `frameVersion` is still useful as an external redraw signal for non-physics uses, so it should be retained as a parameter on `IsometricScene` -- just not incremented in the physics sync path.

This is purely a simplification suggestion; the system works correctly with both signals active simultaneously.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **C1**: `syncPositionsToNodes` does not call `markDirty()` -- renderer serves stale cached `PreparedScene` | Trivial (add one line) | Physics shapes appear frozen; rendering completely broken for all physics bodies |
| 2 | **S1**: Comment explaining `staticCompositionLocalOf` lambda closure semantics | Trivial | Documentation clarity |
| 3 | **S2**: `frameVersion` increment redundant after C1 fix | Trivial | Code simplification |

---

## Overall Assessment

The plan is in excellent shape after eight rounds of revision. The 107 previously identified fixes have been incorporated cleanly, and the architecture is sound at every level -- module split, threading model, snapshot-based position sync, deferred body mutation queue, compound colliders with centroid correction, island-based sleep, and Compose lifecycle integration.

The single critical finding this round (C1) is a cache invalidation gap between the physics position sync path and the renderer's `PreparedScene` caching layer. The physics plan correctly describes how positions flow from the physics thread to node fields via snapshots, but it does not account for the renderer's caching optimization that gates `rebuildCache()` behind `rootNode.isDirty`. Without `markDirty()` being called after position sync, the renderer serves stale results and physics shapes appear frozen. The fix is a single line: call `root.markDirty()` after `syncPositionsToNodes`. This was not caught in prior reviews because the renderer's `PreparedScene` caching is a codebase-level optimization that the plan interacts with implicitly.

**The plan is ready for implementation once C1 is resolved.** The fix is trivial -- adding `root.markDirty()` after the sync traversal. No architectural changes are needed.

The physics architecture as designed -- with its snapshot-based threading model, origin-centered shape convention, deferred mutation queue, compound collider centroid correction, and clean Compose lifecycle management -- is well-thought-out and production-ready. The 9 rounds of review have produced a plan that addresses thread safety, API consistency, type correctness, and edge cases comprehensively.
