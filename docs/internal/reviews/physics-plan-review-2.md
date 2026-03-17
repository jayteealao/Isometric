# Physics Implementation Plan Review (Round 2)

> **Reviewer**: Architecture Review
> **Date**: 2026-03-13
> **Plan reviewed**: `docs/plans/physics-implementation-plan.md` (Revised)
> **Status**: Review of the revised plan that addresses findings from `physics-plan-review.md`

---

## Review Summary

The revised plan is substantially improved over the original, with thoughtful fixes for the first review's critical issues. The module split, compound collider approach, and threading model are well-reasoned. However, several new issues have emerged from the fixes themselves, and some assumptions about the codebase do not match reality. The plan also has structural problems in its Compose integration layer that would cause implementation failures.

**Verdict**: The plan needs targeted fixes before implementation begins. Most issues are in Phase 0 and Phase 4b.

---

## CRITICAL Issues

### C1: Phase 0 shape classes do NOT store constructor parameters -- the plan's `val` change is a breaking API change, not "source-compatible"

**Evidence from codebase**: The current `Prism` constructor uses bare parameters, not `val`:

```kotlin
// Prism.kt (actual)
class Prism(
    origin: Point,       // <-- NOT val
    dx: Double = 1.0,    // <-- NOT val
    dy: Double = 1.0,
    dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz))
```

The plan proposes:

```kotlin
class Prism(
    val origin: Point,   // <-- added val
    val dx: Double = 1.0,
    ...
)
```

The plan states: "These are source-compatible additions -- existing code uses constructor parameters that already match these field names."

**This is wrong.** Adding `val` to constructor parameters of an `open class` (`Shape` is `open`) changes the class ABI. While it is source-compatible for callers (construction sites don't change), it creates new fields and getter methods. More critically:

- `Prism`, `Cylinder`, `Pyramid`, `Stairs`, `Knot`, `Octahedron` currently do NOT store their constructor parameters at all. They are consumed only by `createPaths()` in the `Shape(...)` super-call and then discarded.
- Adding `val origin: Point` creates a stored field. For `Prism`, this means the `origin` Point is now permanently held in memory alongside the already-computed paths that embed the origin. This doubles memory for origin data.
- The `Octahedron` scales its paths by `sqrt(2)/2` around a computed center. The stored `origin` would NOT match the actual geometric center of the shape, which is potentially confusing for physics calculations.
- `Stairs(origin, stepCount)` -- the `origin` parameter doesn't have a `val` prefix, and `stepCount` is also bare. The plan correctly shows these being added but does not discuss that `Stairs` stores no size information (it always creates a 1x1x1 staircase). The plan needs to expose `width = 1.0` / `depth = 1.0` / `height = 1.0` as constants or computed properties, not just `stepCount`.

**Impact**: Not a true blocker (the change does work), but the claim of "source-compatible, no behavior changes" is misleading. The change is binary-incompatible and the memory impact should be acknowledged. More importantly, the stored `origin` will be stale if the shape is later translated -- physics code must NOT use `shape.origin` as the current position.

**Fix**: Add a clear note that `origin` is the *construction* origin, not the runtime position. Consider whether `origin` should be stored at all, since physics will track position separately in `RigidBody.position`. Only `dx/dy/dz/radius/height/stepCount` are truly needed for collider derivation.

---

### C2: `PhysicsShapeNode` extends `ShapeNode` but the Applier only accepts `GroupNode` children

**Evidence from codebase** (`IsometricApplier.kt`):

```kotlin
override fun insertBottomUp(index: Int, instance: IsometricNode) {
    val parent = current as? GroupNode
        ?: error("Can only insert children into GroupNode, but current is ${current::class.simpleName}")
    parent.children.add(index, instance)
    ...
}
```

The plan proposes `PhysicsShapeNode extends ShapeNode`. Since `ShapeNode extends IsometricNode`, this is fine for the type hierarchy. However, the plan's `PhysicsScene` composable creates a `GroupNode` as root and wraps `IsometricScene`. The `ComposeNode<PhysicsShapeNode, IsometricApplier>` call in `PhysicsShape` will work because `PhysicsShapeNode` is an `IsometricNode` and the parent is a `GroupNode`.

**Wait -- actually this does work.** The Applier casts `current` to `GroupNode`, but `PhysicsShapeNode` is being added as a *child*, not used as a *parent*. The `children` list is `MutableList<IsometricNode>`, so any `IsometricNode` subclass can be added. I retract this as critical.

However, there IS a problem: `PhysicsShapeNode` overrides `render()` and calls `bodyRef.getTransformedShape(shape)`, but `ShapeNode.render()` also applies `position`, `rotation`, `scale` from the node's local transform properties. The plan's `PhysicsShapeNode.render()` bypasses the parent's local transform logic entirely and delegates to `context.applyTransformsToShape()`. This means:

- If a `PhysicsShape` is placed inside a `Group(position = ...)`, the group's accumulated transform from `RenderContext` will be applied.
- But the node's own `position`/`rotation` fields (inherited from `IsometricNode`) are ignored by the overridden render, which only uses `bodyRef` position.

This is actually **correct behavior** for physics shapes (physics owns the position), but it means the `ShapeNode` base class inheritance is misleading. The plan should document that `PhysicsShapeNode` intentionally ignores the `IsometricNode.position`/`rotation`/`scale` properties. Better yet, consider not extending `ShapeNode` at all and extending `IsometricNode` directly.

**Revised severity**: IMPORTANT (not CRITICAL), moved to I1 below.

---

### C3: `BodyPair.key` uses `pairHash()` but `ContactManager.pairKey()` uses bit-shifted `hashCode()` -- two different keying schemes for the same concept

**Evidence from plan**:

`BodyPair`:
```kotlin
val key: Long = if (a.id < b.id) pairHash(a.id, b.id) else pairHash(b.id, a.id)
```

`ContactManager.pairKey()`:
```kotlin
val idA = a.id.hashCode().toLong()
val idB = b.id.hashCode().toLong()
return if (idA <= idB) (idA shl 32) or (idB and 0xFFFFFFFFL)
else (idB shl 32) or (idA and 0xFFFFFFFFL)
```

These produce **different values** for the same body pair. `BodyPair.key` calls an undefined `pairHash()` function, while `ContactManager.pairKey()` uses `String.hashCode()` bit-shifting. This means the contact manager cannot correctly match broad phase pairs to cached manifolds.

Additionally, `String.hashCode()` is **not guaranteed collision-free**. Two different body IDs could have the same `hashCode()`, making `(idA shl 32) or idB` collide. With 500 bodies, that's 124,750 possible pairs. Birthday-paradox collision probability with 32-bit hashes is non-trivial at this scale. This directly contradicts the C5 determinism fix.

**Fix**: Use a single, consistent pairing function across the codebase. Use the string comparison approach (`a.id < b.id`) for ordering plus a collision-resistant hash, or simply use `Pair<String, String>` as the map key (less efficient but correct).

---

### C4: `EventDispatcher` uses `MutableSharedFlow` from `kotlinx.coroutines` but `isometric-physics-core` is pure JVM with no coroutines dependency

**Evidence from plan** (Phase 1 gradle):
```kotlin
dependencies {
    api(project(":isometric-core"))
    testImplementation(kotlin("test"))
    testImplementation("com.google.truth:truth:1.4.2")
}
```

No `kotlinx-coroutines` dependency is declared. But Phase 4a's `EventDispatcher` uses:
```kotlin
private val _collisionEvents = MutableSharedFlow<CollisionEvent>(extraBufferCapacity = 64)
val collisionEvents: SharedFlow<CollisionEvent> = _collisionEvents
```

`MutableSharedFlow` and `SharedFlow` are in `kotlinx.coroutines.flow`. This will fail to compile.

**Fix**: Either add `kotlinx-coroutines-core` to the physics-core dependencies, or use a simpler callback-based event system for the core module and only provide Flow adapters in the compose module.

---

### C5: `PhysicsScene` wraps `IsometricScene` but the `IsometricScene` signature does not accept `frameVersion` in the simplified overload

**Evidence from codebase**: The `IsometricScene` has two overloads. The full overload accepts `frameVersion`, but the plan's `PhysicsScene` calls `IsometricScene(...)` with `frameVersion = frameVersion`. This works with the full overload.

However, the plan's `PhysicsScene` also passes `onTap = { x, y, node -> ... }`, but the actual `IsometricScene` signature has `onTap: (x: Double, y: Double, node: IsometricNode?) -> Unit`. The plan's lambda captures this and calls `world.raycast(ray)`, which returns a `RaycastResult?` containing a `RigidBody`, not an `IsometricNode`. The plan conflates two different tap handling paths: the existing node-based hit testing and the new physics raycast. These need to be reconciled -- either the physics scene replaces the node hit test entirely, or it augments it.

**Fix**: Design the tap flow clearly. Either: (a) `PhysicsScene` does its own pointer input handling (not delegating to `IsometricScene`'s gesture system), or (b) the `onTap` callback in `PhysicsScene` receives both the `IsometricNode?` from the renderer's hit test AND the `RigidBody?` from raycasting.

---

## IMPORTANT Issues

### I1: `PhysicsShapeNode` inheritance from `ShapeNode` is misleading and creates a fragile override contract

As discussed in C2 above, `PhysicsShapeNode` extends `ShapeNode` but completely overrides its `render()` method, ignoring the node's local transform properties (`position`, `rotation`, `scale`). If `ShapeNode.render()` is ever refactored (e.g., to add caching or new transform types), `PhysicsShapeNode` won't benefit.

**Fix**: Extend `IsometricNode` directly. The `PhysicsShapeNode` only needs `shape`, `color`, and `bodyRef` -- it doesn't benefit from `ShapeNode`'s render logic.

---

### I2: The threading model has a subtle race in `syncPositionsToNodes`

The plan's `PhysicsScene` reads the snapshot on the main thread and calls `syncPositionsToNodes(rootNode, snapshot)`. This function presumably mutates the `shape` property of `PhysicsShapeNode` instances (or their positions). But the plan says positions are applied via `bodyRef.getTransformedShape()` at render time, not via node property mutation.

If `syncPositionsToNodes` is a no-op (because `PhysicsShapeNode.render()` reads directly from `bodyRef`), then the physics thread writes to `RigidBody.position`/`orientation` while the render thread reads them in `getTransformedShape()`. The `AtomicReference<Map<...>>` snapshot is the safe path, but the `bodyRef.getTransformedShape()` call in `render()` reads `bodyRef.position` directly -- bypassing the snapshot entirely.

The plan has two contradictory position-sync paths:
1. Snapshot via `AtomicReference` (safe)
2. Direct `bodyRef` field reads in `render()` (unsafe)

**Fix**: Pick one. Either `PhysicsShapeNode.render()` reads from the published snapshot (via a local copy of interpolated position/orientation), or the snapshot is removed and `RigidBody` fields are made `@Volatile`. The current design does both, which is inconsistent.

---

### I3: `AABB.fromShape()` dispatches on `is Prism`, `is Cylinder`, etc. -- this breaks the open/closed principle and will miss custom shapes

The plan uses `when (shape) { is Prism -> ... }` for both `InertiaCalculator` and `AABB.fromShape()`. This works for the known shapes but:

- The `else` fallback in `InertiaCalculator` uses `boxInertia(mass, 1.0, 1.0, 1.0)` -- a unit cube. This is wildly wrong for a large flat ground plane (`Prism(origin, 20.0, 20.0, 0.1)` vs default `1.0, 1.0, 1.0`).
- If a user creates a `Shape(paths)` directly (which is possible since `Shape` is `open`), neither `AABB.fromShape()` nor `InertiaCalculator` will have stored dimensions. The fallback should at minimum compute AABB from the actual path vertices.

**Fix**: `AABB.fromShape()` should have a robust fallback: `AABB.fromPoints(shape.paths.flatMap { it.points })`. The `else` branch in `InertiaCalculator` should compute inertia from the actual vertex hull, not assume a unit cube.

---

### I4: `BodyType.Dynamic` is a `data class` with `mass` -- but `BodyType.Static` and `BodyType.Sensor` are `object` singletons, and `BodyType.Kinematic` is a `data class`

This is inconsistent. `data class Dynamic(val mass: Double = 1.0)` means two `Dynamic` types with different masses are `!=` to each other. This is fine semantically but means you can't do `type == BodyType.Dynamic` to check if a body is dynamic -- you must use `type is BodyType.Dynamic`. The plan's code does use `is` checks, so this works. But the DSL builder has:

```kotlin
fun dynamic(mass: Double = 1.0) { type = BodyType.Dynamic(mass) }
```

If the user writes `type = BodyType.Dynamic()` and also calls `dynamic(2.0)`, the last one wins -- which is correct. But `BodyConfig` is a `data class`, so `BodyConfig(type = BodyType.Dynamic(1.0))` and `BodyConfig(type = BodyType.Dynamic(2.0))` have different equality, which could surprise users who compare configs.

**Impact**: Minor but worth documenting.

---

### I5: `scheduleAtFixedRate` interval is `1000L / 60 = 16` microseconds, not 16 milliseconds

**Evidence from plan**:
```kotlin
executor.scheduleAtFixedRate({
    ...
}, 0, 1000L / 60, TimeUnit.MICROSECONDS)
```

`1000L / 60 = 16` (integer division). With `TimeUnit.MICROSECONDS`, this schedules the task every **16 microseconds** (62,500 Hz), not every 16 milliseconds (60 Hz). This will spin the CPU at maximum speed.

**Fix**: Change to `TimeUnit.MILLISECONDS` or use `16_667L` with `TimeUnit.MICROSECONDS` (1,000,000 / 60 = 16,667 microseconds).

---

### I6: The plan's `getTransformedShape` caches based on `Point` and `Quaternion` equality, but `Point` is a `data class` using `Double` equality

**Evidence from codebase**: `Point` is `data class Point(val x: Double, val y: Double, val z: Double)`. Double equality is exact bit comparison -- `0.0 != -0.0`, and NaN handling can be surprising. For physics where positions change by tiny floating-point increments every frame, the cache will almost never hit because the position changes every step.

The cache is actually designed for the case where a sleeping body doesn't move -- that's valid. But the check `position == cachedPosition` will create a new `Shape` with new `Path` and `Point` objects every single frame for every moving body, regardless of caching.

**Impact**: The caching provides no benefit for active bodies (which are the ones being rendered) and adds overhead for the equality check and nullable field access. This contradicts the plan's FIX I6 claim of "pre-allocated transform buffers, cached transformed shapes."

**Fix**: The caching is fine for sleeping bodies. For active bodies, consider pre-allocating mutable arrays for transformed vertices rather than creating new `Path`/`Point` objects every frame. This is the actual GC fix that FIX I6 was supposed to address.

---

### I7: `PhysicsScene` uses `LaunchedEffect(Unit)` with `withFrameNanos` -- this runs at display refresh rate on the main thread, which is correct, but the `syncPositionsToNodes` is undefined

The plan shows `syncPositionsToNodes(rootNode, snapshot)` being called but never defines this function. It's unclear how the interpolated snapshot gets applied to the nodes. If `PhysicsShapeNode.render()` reads from `bodyRef` directly (as shown), then `syncPositionsToNodes` might just be a no-op frame trigger. But the plan shows `rootNode.markDirty()` being called, which propagates to `onDirty` which increments `sceneVersion`, but `PhysicsScene` already has its own `frameVersion`. This is redundant -- either use `sceneVersion` (dirty propagation) or `frameVersion` (explicit counter), not both.

---

### I8: The `Wind` force field is a `data class` with a mutable `phase` property

```kotlin
data class Wind(..., private var phase: Double = 0.0) : ForceField()
```

`data class` with `var` properties breaks the `equals`/`hashCode`/`copy` contract. Two `Wind` instances that started identical will diverge as `phase` mutates, making `data class` comparison unreliable.

**Fix**: Use a regular `class` for `Wind` (and arguably all `ForceField` subclasses, since they have mutable state via `apply()`).

---

## SUGGESTIONS

### S1: Consider using integer body IDs instead of String IDs

The plan uses `String` IDs throughout (`val id: String`), which means every `LinkedHashMap` lookup, `pairKey()` computation, and contact matching involves string hashing. For 500 bodies with 10+ solver iterations per frame, this adds up. Integer IDs with an `AtomicInteger` counter would be faster and eliminate hash collision concerns entirely.

---

### S2: The `Collider` sealed interface wrapper adds unnecessary indirection

```kotlin
sealed interface Collider {
    data class Convex(val collider: ConvexCollider) : Collider
    data class Compound(val collider: CompoundCollider) : Collider
}
```

Every collider access requires unwrapping: `colliderA.collider`. Consider making `CompoundCollider` implement a common `Collider` interface alongside `ConvexCollider`, avoiding the wrapper layer.

---

### S3: The `IslandManager` initializes arrays with size 0

```kotlin
private val parent = IntArray(0)
private val rank = IntArray(0)
```

These will need to be reallocated every frame to match the body count. Pre-allocate to expected capacity and grow as needed.

---

### S4: Consider implementing the solver per-island rather than globally

The current plan runs the solver over ALL manifolds:
```kotlin
solver.solve(resolvedManifolds, dt, config.solverIterations)
```

Since islands are independent, solving per-island would (a) allow sleeping islands to skip solving entirely, and (b) enable future parallelization. The island build happens AFTER solving in the current step order (step 9 vs step 6), which means the solver can't benefit from islands.

**Fix**: Move island building before the solver in the step sequence, skip solving for sleeping islands.

---

### S5: The depth sort concern (FIX S4) is critical for the 500-body target but the plan only "acknowledges" it

The existing `sortPaths()` in `IsometricEngine` is O(N^2) with N = total paths. At 500 bodies x 6 faces = 3000 paths, that's ~4.5M pair tests per frame. The plan mentions `enableBroadPhaseSort = true` but the actual implementation of broad-phase sorting in the renderer is separate from the physics broad phase. The plan should explicitly state whether physics shapes use the existing rendering sort or bypass it.

---

### S6: `ContactManifold.points` is `Array(4) { ContactPoint() }` -- magic number 4

The maximum of 4 contact points per manifold is standard for box-box contacts, but for GJK/EPA on arbitrary convex shapes, EPA typically produces a single contact point. Contact clipping for manifold generation is not described anywhere in the plan, but the 4-point array assumes it exists. The plan should either describe the contact clipping algorithm or reduce the manifold to single-point contacts (simpler, and sufficient for a first implementation).

---

### S7: `screenToWorldRay` is defined in `PhysicsWorld` but requires `IsometricEngine` parameters

The plan shows:
```kotlin
fun screenToWorldRay(screenX: Double, screenY: Double, engine: IsometricEngine): Ray
```

This couples the physics core to the rendering engine. The function should live in the compose module (or be a standalone utility), not in `PhysicsWorld`.

---

### S8: Missing `slerp` function definition

`PhysicsStep.computeInterpolatedSnapshot()` calls `slerp(prev.orientation, curr.orientation, currentAlpha)` but `slerp` is never defined or imported. This needs to be implemented in the `Quaternion` companion or as an extension function.

---

## Priority-Ordered Fix Table

| Priority | ID | Type | Phase | Issue | Effort |
|----------|----|------|-------|-------|--------|
| 1 | C5 | CRITICAL | 4b | `PhysicsScene` timer interval is 16 **micro**seconds not milliseconds (labeled I5 above, but really critical for usability) | Trivial |
| 2 | C3 | CRITICAL | 2-3 | Two different pair key functions produce different values | Small |
| 3 | C4 | CRITICAL | 4a | `SharedFlow` used without coroutines dependency | Small |
| 4 | I5 | IMPORTANT | 1 | `scheduleAtFixedRate` spins at 62,500 Hz | Trivial |
| 5 | C1 | CRITICAL | 0 | Phase 0 `val` claim of "source-compatible" is misleading; `origin` storage is unnecessary for physics | Small |
| 6 | C5 | CRITICAL | 4b | `PhysicsScene` tap handling conflates node hit test and physics raycast | Medium |
| 7 | I2 | IMPORTANT | 4b | Two contradictory position-sync paths (snapshot vs direct bodyRef reads) | Medium |
| 8 | S4 | SUGGESTION | 3 | Move island building before solver to enable per-island solving | Medium |
| 9 | I3 | IMPORTANT | 1-2 | AABB/Inertia fallback uses unit cube instead of actual vertex bounds | Small |
| 10 | I1 | IMPORTANT | 4b | `PhysicsShapeNode` extends `ShapeNode` unnecessarily | Small |
| 11 | S1 | SUGGESTION | 1 | Use integer body IDs for performance | Medium |
| 12 | I6 | IMPORTANT | 1 | Transform caching provides no GC benefit for active bodies | Medium |
| 13 | I8 | IMPORTANT | 4a | `Wind` is `data class` with mutable state | Trivial |
| 14 | S6 | SUGGESTION | 2 | Contact clipping algorithm is undescribed but 4-point manifold assumes it | Medium |
| 15 | S8 | SUGGESTION | 1 | Missing `slerp` implementation | Small |
| 16 | S7 | SUGGESTION | 4a | `screenToWorldRay` couples physics core to rendering engine | Small |
| 17 | I7 | IMPORTANT | 4b | `syncPositionsToNodes` is undefined; redundant dirty/frameVersion | Small |
| 18 | S3 | SUGGESTION | 3 | `IslandManager` arrays initialized at size 0 | Trivial |
| 19 | S2 | SUGGESTION | 2 | `Collider` sealed wrapper adds unnecessary indirection | Small |
| 20 | I4 | IMPORTANT | 1 | `BodyType.Dynamic` data class equality semantics | Trivial (docs) |

---

## Overall Assessment

The revised plan demonstrates strong physics engine knowledge and the module architecture is sound. The decision to split into `isometric-physics-core` (JVM) and `isometric-physics-compose` (Android) correctly mirrors the existing codebase structure and avoids the build cycle that plagued the original plan.

**Strengths**:
- The compound collider approach for non-convex shapes (Stairs, Knot) is the right call
- The phased approach with clear deliverables and test criteria per phase is excellent
- The determinism focus (LinkedHashMap, sorted contacts, fixed timestep) is thorough
- The decision to use a custom engine rather than wrapping dyn4j gives full control

**Weaknesses**:
- The Compose integration layer (Phase 4b) has the most issues -- the position sync model needs to be clarified (snapshot vs direct read), and the `PhysicsScene` wrapper has implementation bugs
- The plan repeatedly references undefined functions (`pairHash`, `syncPositionsToNodes`, `slerp`)
- The threading interval bug (I5/C5) would cause immediate CPU spinning in any test
- The contact manifold generation algorithm (clipping) is a significant omission -- GJK/EPA alone gives you one contact point, not the 4-point manifold needed for stable stacking

**Recommendation**: Fix the critical issues (especially the threading interval, pair key inconsistency, and coroutines dependency) before beginning implementation. The Phase 4b Compose integration design needs a focused design pass to resolve the position synchronization model. Phase 0 through Phase 3 can proceed after the smaller fixes.
