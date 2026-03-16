# Physics Implementation Plan -- Review Round 8

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R7)
> **Status**: Issues identified -- plan revision recommended

---

## Review Summary

The R7 revision addresses 102 issues across seven prior review rounds. The plan is mature and the core physics architecture is sound. The fix tracking is excellent and all previously reported issues have clear, documented resolutions. After a thorough reading of the full ~2900-line plan and cross-referencing against the actual codebase, I have identified **1 critical issue**, **2 important issues**, and **2 suggestions** that are genuinely new -- not repeats of any previously reported findings.

The critical issue is a thread-safety gap in the body addition/removal path. The important issues are a collider-position mismatch for compound shapes and a set of dead-code analytic AABB functions that cannot be called as designed.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. `PhysicsWorld.addBody()`/`removeBody()` are called from the main thread while `step()` iterates body collections on the physics thread -- unsynchronized concurrent access

The plan's `PhysicsWorld` (section 1.3, lines 865-866) stores bodies in unsynchronized collections:

```kotlin
private val bodiesById = LinkedHashMap<Int, RigidBody>()
private val bodiesList = mutableListOf<RigidBody>()
```

`PhysicsWorld.step()` (section 3.2, lines 1625-1673) iterates `bodiesList` on the physics thread during every step:

```kotlin
fun step(dt: Double) {
    // ...
    broadPhase.update(bodiesList)  // iterates bodiesList
    // ...
    integrateVelocities(dt)        // iterates bodiesList
    integratePositions(dt)         // iterates bodiesList
    // ...
}
```

Meanwhile, `addBody()` and `removeBody()` are called from the main (Compose) thread. `PhysicsShape` (section 4b.5d, lines 2416-2426) adds bodies during composition via `remember`:

```kotlin
val (rb, centeredShape) = remember(shape, body) {
    RigidBody.create(shape, body).also { (b, _) -> world.addBody(b) }
}
DisposableEffect(rb) {
    onDispose { world.removeBody(rb) }
}
```

`addBody()` inserts into `bodiesById` and `bodiesList`. `removeBody()` removes from both. These mutations happen on the main thread, but `step()` reads/iterates these same collections on the physics thread (via `PhysicsThread` or `AndroidPhysicsThread`).

`LinkedHashMap` and `ArrayList` are not thread-safe. Concurrent read-during-write will cause `ConcurrentModificationException` at runtime (most likely during `broadPhase.update(bodiesList)` which iterates the list while the main thread adds/removes from it). This can also cause corrupted internal state (e.g., a `LinkedHashMap` resize racing with a physics-thread iteration).

The same issue applies to `rememberPhysicsBody` (section 4b.7, line 2545-2549), which also calls `world.addBody()` during composition and `world.removeBody()` on disposal.

**Evidence**: Plan section 1.3 lines 865-866 (unsynchronized collections), section 3.2 lines 1625-1673 (`step()` iterates `bodiesList`), section 4b.5d lines 2416-2426 (`addBody`/`removeBody` from main thread), section 1.4 lines 999-1004 and section 4b.3 lines 2084-2093 (physics step runs on background thread).

**Why this was not caught before**: Previous reviews (R1-I1, R2-I2, R3-I5) focused on the position-sync threading model (snapshot vs direct reads) and concluded that the `AtomicReference`-based snapshot publishing is safe. That analysis is correct for position/orientation reads. But body collection mutations (add/remove) were never analyzed for thread safety. The `AtomicReference` protects snapshot data; it does nothing for the mutable `bodiesById`/`bodiesList` collections.

**Fix**: Synchronize body addition and removal with the physics step. Options:

**(a) Deferred mutation queue (recommended)**: `addBody()` and `removeBody()` on the main thread enqueue mutations into a thread-safe `ConcurrentLinkedQueue<BodyMutation>`. At the start of `step()`, the physics thread drains the queue and applies mutations to the collections. This keeps the hot path (iteration during step) lock-free.

```kotlin
sealed class BodyMutation {
    data class Add(val body: RigidBody) : BodyMutation()
    data class Remove(val body: RigidBody) : BodyMutation()
}

private val pendingMutations = ConcurrentLinkedQueue<BodyMutation>()

fun addBody(body: RigidBody) { pendingMutations.add(BodyMutation.Add(body)) }
fun removeBody(body: RigidBody) { pendingMutations.add(BodyMutation.Remove(body)) }

fun step(dt: Double) {
    // Drain pending mutations first
    while (true) {
        val mutation = pendingMutations.poll() ?: break
        when (mutation) {
            is BodyMutation.Add -> { bodiesById[mutation.body.id] = mutation.body; bodiesList.add(mutation.body) }
            is BodyMutation.Remove -> { bodiesById.remove(mutation.body.id); bodiesList.remove(mutation.body) }
        }
    }
    // ... rest of step
}
```

**(b) Synchronized collections**: Use `Collections.synchronizedMap()` and `Collections.synchronizedList()`. Simpler but adds lock contention on every iteration.

Option (a) is preferred because it preserves the lock-free iteration performance of the physics step, which is the hot path.

---

## IMPORTANT Issues (Should Fix)

### I1. `StairsCompoundCollider` and `KnotCompoundCollider` child positions are not origin-centered -- mismatch with baseShape after `RigidBody.create()` centroid shift

`RigidBody.create()` (section 1.2, lines 692-695) computes the centroid of the input shape and produces an origin-centered `baseShape`:

```kotlin
val centroid = AABB.fromShape(shape).center()
val baseShape = shape.translate(-centroid.x, -centroid.y, -centroid.z)
```

The collider is then derived from the origin-centered baseShape:

```kotlin
body.collider = config.colliderOverride?.toCollider()
    ?: ColliderFactory.fromShape(baseShape, shape)
```

`ColliderFactory.fromShape()` (section 1.2, lines 778-779) calls:

```kotlin
is Stairs -> StairsCompoundCollider.create(Point.ORIGIN, originalShape.stepCount)
is Knot -> KnotCompoundCollider.create(Point.ORIGIN)
```

The problem: these compound collider factory functions compute child positions from scratch using the shape's internal geometry, with `origin = Point.ORIGIN`. But the visual `baseShape` has been translated by `-centroid`. The compound collider child positions are NOT similarly translated.

For a `Stairs(Point.ORIGIN, 4)`, the AABB center (centroid) is approximately `(0.5, 0.5, 0.5)`. The baseShape vertices are shifted by `(-0.5, -0.5, -0.5)`. But `StairsCompoundCollider.create(Point.ORIGIN, 4)` places step 0's center at `(0.5, 0.125, 0.125)` -- relative to the unshifted origin, not the shifted baseShape.

This means the compound collider's child positions are offset from the visual geometry by the centroid vector `(0.5, 0.5, 0.5)`. Collisions will occur at the wrong positions relative to the rendered shape.

For symmetric convex shapes (Prism, Cylinder), this is not an issue because their colliders are centered at `Point.ORIGIN` with symmetric halfExtents. But compound colliders have asymmetric child positions that must account for the centroid shift.

**Evidence**: Plan section 1.2 lines 692-695 (centroid shift), lines 778-779 (`ColliderFactory` passes `Point.ORIGIN`), section 2.2 lines 1296-1314 (`StairsCompoundCollider.create` computes positions relative to origin parameter, NOT relative to shifted centroid), lines 1339-1373 (`KnotCompoundCollider.create` similarly unshifted).

**Fix**: The compound collider positions must be shifted by `-centroid` to match the origin-centered baseShape. Either:

**(a)** Pass the centroid to `ColliderFactory.fromShape()` and apply the shift inside the compound factory functions:

```kotlin
fun fromShape(baseShape: Shape, originalShape: Shape): Collider {
    val centroid = AABB.fromShape(originalShape).center()
    // ...
    is Stairs -> StairsCompoundCollider.create(Point.ORIGIN, originalShape.stepCount)
        .translate(-centroid.x, -centroid.y, -centroid.z)  // shift to match baseShape
    // ...
}
```

Where `CompoundCollider.translate()` shifts all child `localPosition` values by the given offset.

**(b)** Have the compound collider factories compute positions relative to the shape's centroid instead of its origin.

### I2. `AABB.fromShape()` per-type analytic functions all require `origin: Point` parameter which is not stored -- branches are impossible to call

The plan's `AABB` companion (section 1.1, lines 487-503) defines analytic factory functions and a type-dispatching `fromShape()`:

```kotlin
fun fromPrism(origin: Point, dx: Double, dy: Double, dz: Double): AABB
fun fromPyramid(origin: Point, dx: Double, dy: Double, dz: Double): AABB
fun fromCylinder(origin: Point, radius: Double, height: Double): AABB
fun fromOctahedron(origin: Point, xyScale: Double, zScale: Double): AABB
fun fromStairs(origin: Point, stepCount: Int): AABB
fun fromKnot(origin: Point): AABB

fun fromShape(shape: Shape): AABB = when (shape) {
    is Prism -> fromPrism(/* ... */)
    is Cylinder -> fromCylinder(/* ... */)
    is Octahedron -> fromOctahedron(/* origin */, shape.xyScale, shape.zScale)
    // ... other known shapes ...
    else -> fromPoints(shape.paths.flatMap { it.points })
}
```

All six analytic functions take `origin: Point` as their first parameter. Phase 0 (section 0.1, lines 346-347) explicitly declares that `origin` is a bare constructor parameter, NOT stored as a `val` field. The `/* ... */` and `/* origin */` placeholders in `fromShape()` cannot be filled in because `shape.origin` does not exist.

The R7-C1 fix correctly replaced `shape.origin` access in `RigidBody.create()` with `AABB.fromShape(shape).center()`. But `AABB.fromShape()` itself suffers from the same problem internally -- its per-type branches need `origin` to call the analytic functions.

The `else -> fromPoints(shape.paths.flatMap { it.points })` fallback works for all types because it derives the AABB from actual vertices. The per-type branches can only work by also extracting origin from vertices (e.g., computing the min point of the AABB), at which point they are redundant with `fromPoints()`.

**Evidence**: Plan section 1.1 lines 487-503 (analytic functions require `origin`), plan section 0.1 lines 346-347 (`origin` is bare parameter). Plan lines 498-502 show `/* ... */` placeholders that cannot be resolved.

**Fix**: Either:

**(a) Remove the per-type branches in `fromShape()` and use `fromPoints()` for all types.** This is the simplest and most honest approach. The per-type analytic functions (`fromPrism`, `fromCylinder`, etc.) are still useful when called directly with known parameters (e.g., from test code or when origin is available), but `fromShape()` should use the universal vertex-based path.

**(b) Change the per-type branches to derive origin from vertices first**, then call the analytic functions. For example:

```kotlin
is Prism -> {
    val points = shape.paths.flatMap { it.points }
    val minOrigin = Point(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z })
    fromPrism(minOrigin, shape.dx, shape.dy, shape.dz)
}
```

This preserves the analytic precision (avoids vertex duplication bias that R6-S1 was concerned about) while using vertices only to recover the origin.

Option (b) is more precise but option (a) is simpler. In practice, `fromPoints()` gives identical results for all current shape types because the path vertices define the exact bounding box.

---

## SUGGESTIONS (Nice to Have)

### S1. `PhysicsBodyRef.isSleeping` reads directly from `RigidBody.isSleeping` -- not from snapshot, inconsistent with position/velocity reads

The plan's `PhysicsBodyRef` (section 4b.7, lines 2568-2577) reads position, orientation, velocity, and angular velocity from the published snapshot. But `isSleeping` reads directly from the live body:

```kotlin
val position: Point get() = snapshotProvider()[body.id]?.position ?: body.position
val velocity: Vector get() = snapshotProvider()[body.id]?.velocity ?: body.velocity
val isSleeping: Boolean get() = body.isSleeping  // direct read from physics thread state
```

`body.isSleeping` is mutated on the physics thread by `IslandManager.updateSleep()` (section 3.3, lines 1711-1714). Reading it from the main thread without synchronization could observe a stale value (though `Boolean` reads are atomic on the JVM, the value may be from a different point in time than the snapshot's position/velocity). This creates a temporal inconsistency: position/velocity are from the interpolated snapshot, but `isSleeping` is from the current physics state.

This was partially addressed by R4-S3 (which added velocity/angularVelocity to the snapshot for temporal consistency), but `isSleeping` was not included.

**Fix**: Add `isSleeping: Boolean` to `BodySnapshot` and read it from the snapshot in `PhysicsBodyRef`:

```kotlin
data class BodySnapshot(
    val position: Point,
    val orientation: Quaternion,
    val velocity: Vector = Vector.ZERO,
    val angularVelocity: Vector = Vector.ZERO,
    val isSleeping: Boolean = false  // NEW
)
```

### S2. `remember(shape, body)` in `PhysicsShape` uses structural equality on `Shape` as a remember key -- expensive comparison for large shapes

The plan's `PhysicsShape` (section 4b.5d, line 2416):

```kotlin
val (rb, centeredShape) = remember(shape, body) {
    RigidBody.create(shape, body).also { (b, _) -> world.addBody(b) }
}
```

`remember(shape, body)` uses structural equality to determine if the keys have changed. `Shape` is an `open class` with `val paths: List<Path>`, and since it is not a `data class`, it uses referential equality by default (`===`). This means a new `Shape` instance with identical paths would be considered different, triggering unnecessary body recreation. Conversely, if users construct shapes inline in composable calls (e.g., `PhysicsShape(shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0))`, a new `Prism` is allocated each recomposition, each with a different reference, which would trigger body recreation every recomposition.

`BodyConfig` is a `data class`, so its equality comparison is correct. But `Shape` uses identity comparison, which will cause spurious body recreation when shapes are constructed inline.

**Fix**: Either:
- Document that `shape` should be `remember`ed outside of `PhysicsShape` to provide stable references.
- Use a different remember key strategy, such as the body's `tag` property or a user-supplied `key`.
- The existing `key: Any?` parameter in Phase 6's `PhysicsShape` (line 2745) addresses this, but Phase 4b's `PhysicsShape` does not have this parameter. Consider adding it earlier.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **C1**: `addBody`/`removeBody` unsynchronized with physics `step()` -- `ConcurrentModificationException` | Medium | Runtime crash when bodies are added/removed during simulation |
| 2 | **I1**: Compound collider positions not shifted by centroid -- Stairs/Knot collisions wrong | Low | Physics collisions misaligned with visual geometry for compound shapes |
| 3 | **I2**: `AABB.fromShape()` per-type branches have unfillable `origin` placeholders | Low | Dead code / impossible to implement as written |
| 4 | **S1**: `isSleeping` not in snapshot -- temporal inconsistency | Trivial | Minor inconsistency in `PhysicsBodyRef` API |
| 5 | **S2**: `Shape` identity equality causes spurious body recreation | Low | Performance issue with inline shape construction |

---

## Overall Assessment

The plan is in strong shape after seven rounds of revision. The 102 previously identified fixes have been incorporated cleanly, and the architecture is sound at every level -- module split, threading model, snapshot-based position sync, compound colliders, island-based sleep, and Compose integration.

The most significant finding this round is C1 (unsynchronized body collection access). This is a classic concurrent-modification bug that would manifest as a runtime crash (`ConcurrentModificationException`) the first time a user adds or removes a `PhysicsShape` while the simulation is running. The fix is well-understood (deferred mutation queue) and adds minimal complexity. This was not caught in prior reviews because the threading analysis focused on position/orientation data flow (which is correctly handled via `AtomicReference`), not on the body collection mutation path.

The compound collider centroid mismatch (I1) would cause incorrect collision geometry for Stairs and Knot shapes specifically. It is a consequence of the R5-C1 origin-centering fix interacting with the R4-I4 compound collider geometry in a way that was not previously analyzed end-to-end.

The `AABB.fromShape()` issue (I2) is a design inconsistency rather than a runtime failure -- the fallback `fromPoints()` path handles all types correctly, so the broken per-type branches would just never be reached during implementation. Resolving it is primarily a plan clarity concern.

**The plan is ready for implementation** once C1 is resolved. I1 should be fixed before Phase 2 (when compound colliders are built). I2 is a plan documentation cleanup that can be addressed during implementation.
