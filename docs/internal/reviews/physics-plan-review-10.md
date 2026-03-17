# Physics Implementation Plan -- Review Round 10

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R9)
> **Status**: Issues identified -- plan revision recommended

---

## Review Summary

The R9 revision addresses 110 issues across nine prior review rounds. The plan is mature, thorough, and architecturally sound. The fix tracking is exemplary. After reading the full ~3095-line plan, all 8 prior review documents (rounds 2-9), and cross-referencing against the actual codebase (`IsometricScene.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `IsometricApplier.kt`, `RenderContext.kt`, all shape classes, `Vector.kt`, `Point.kt`, `Path.kt`, `IsometricEngine.kt`, `IsometricComposables.kt`, `IsometricScope.kt`, `CompositionLocals.kt`, and both `build.gradle.kts` files), I have identified **1 important issue** and **3 suggestions** that are genuinely new -- not repeats of any previously reported findings.

No critical issues were found. The single important issue is a memory-visibility gap in `PhysicsBodyRef` write operations that could cause applied forces/impulses to be silently lost on multi-core devices.

---

## CRITICAL Issues (Must Fix Before Implementation)

None identified. The plan is architecturally sound after 9 rounds of revision, and the R9 fix (C1: `markDirty()` after sync) was the last remaining rendering correctness issue.

---

## IMPORTANT Issues (Should Fix)

### I1. `PhysicsBodyRef` write operations (`applyForce`, `applyImpulse`, `applyTorque`) have no memory-visibility guarantee -- applied forces may be silently lost on multi-core devices

The plan's `PhysicsBodyRef` (section 4b.7, lines 2612-2614) exposes write operations that delegate directly to `RigidBody` fields:

```kotlin
fun applyForce(force: Vector) = body.applyForce(force)
fun applyImpulse(impulse: Vector) = body.applyImpulse(impulse)
fun applyTorque(torque: Vector) = body.applyTorque(torque)
```

The plan states (line 2611): "Write operations are safe -- consumed at start of next physics step."

`RigidBody.applyForce()` (section 1.2, line 576) performs a read-modify-write on `body.force`:

```kotlin
fun applyForce(f: Vector) {
    force = force + f  // read-modify-write
}
```

`RigidBody.force` is declared as `var force: Vector = Vector.ZERO` (section 1.2, line 562) -- a plain `var` with no `@Volatile` annotation or synchronization. These writes happen on the main thread (from Compose callbacks), while reads happen on the physics thread (in `applyForces()`, step 1 of `world.step()`).

Without `@Volatile`, the JVM provides no guarantee that a write on the main thread's CPU core will be visible to the physics thread's CPU core. On multi-core ARM64 devices (which are the primary target -- Android), the CPU cache coherency model is weaker than x86. A force applied via `body.applyForce()` on the main thread could remain in the main thread's L1 cache indefinitely, never flushing to shared memory. The physics thread would read a stale `Vector.ZERO` (or the previous frame's force), and the user's applied force would be silently lost.

The same issue applies to `body.torque` (written by `applyTorque`) and the direct velocity mutation in `applyImpulse` (which modifies `body.velocity`).

This is distinct from the R8-C1 finding (which addressed structural mutations to body collections via a deferred queue). The R8-C1 fix correctly uses `ConcurrentLinkedQueue` with inherent memory barriers. But the force/impulse/torque fields on `RigidBody` are plain `var` properties with no such protection.

**Evidence**: Plan section 1.2 line 562 (`var force: Vector = Vector.ZERO` -- no `@Volatile`), plan section 4b.7 lines 2612-2614 (`applyForce` called from main thread), plan section 3.2 line 1634 (`applyForces(dt)` reads `body.force` on physics thread).

**Why this was not caught before**: Previous threading reviews focused on (a) the snapshot-based position sync model (R1-C2, R2-I2, R3-I5), which is correctly designed with `AtomicReference`, (b) body collection mutation (R8-C1), which is correctly deferred via `ConcurrentLinkedQueue`, and (c) `markDirty()` rendering invalidation (R9-C1). The `applyForce`/`applyImpulse` write path was categorized as "safe" in the plan's documentation (line 2611) without analysis of the JVM Memory Model implications. On x86 (where most unit tests run), the stronger memory model (Total Store Order) masks this bug -- writes are visible almost immediately. On ARM64 (production Android devices), the weaker memory model makes visibility failures realistic.

**Fix**: Mark `force`, `torque`, and `velocity`/`angularVelocity` as `@Volatile` on `RigidBody`:

```kotlin
@Volatile var force: Vector = Vector.ZERO
@Volatile var torque: Vector = Vector.ZERO
@Volatile var velocity: Vector = Vector.ZERO
@Volatile var angularVelocity: Vector = Vector.ZERO
```

Since `Vector` is an immutable `data class`, the `@Volatile` annotation ensures that reference writes on the main thread are immediately visible to the physics thread. The read-modify-write race in `applyForce` (`force = force + f`) is still not atomic, but since all main-thread writes are sequential (Compose runs on a single thread), concurrent main-thread writes cannot occur. The only race is main-thread write vs physics-thread read, which `@Volatile` resolves.

Alternatively, use a deferred force/impulse queue (similar to the body mutation queue), but this adds complexity for minimal benefit since `@Volatile` on reference types is zero-cost on modern JVMs (the write barrier is a single `StoreStore` fence on ARM64).

**Note**: `position` and `orientation` do NOT need `@Volatile` because they are only written on the physics thread and read from the snapshot on the main thread. The existing `AtomicReference`-based snapshot publishing provides the necessary memory barriers for those fields.

---

## SUGGESTIONS (Nice to Have)

### S1. `PhysicsWorld.findByTag()` and `findById()` read body collections from an unspecified thread -- same unsynchronized access pattern as R8-C1

The plan's `PhysicsWorld` (section 1.3, lines 854-856) exposes query functions:

```kotlin
fun findByTag(tag: String): RigidBody?
fun findById(id: Int): RigidBody?
val bodyCount: Int get() = bodiesList.size
val activeBodies: List<RigidBody> get() = bodiesList.filter { !it.isSleeping }
```

These read from `bodiesById` and `bodiesList`, which are unsynchronized `LinkedHashMap` and `ArrayList` (mutated only on the physics thread via `drainPendingMutations`). If these query functions are called from the main thread (e.g., a user looking up a body by tag during a Compose callback), while the physics thread is mid-step and iterating the same collections, the behavior is undefined.

The R8-C1 fix correctly deferred add/remove mutations via `ConcurrentLinkedQueue`. But read access from the main thread was not addressed. `findByTag()` iterates `bodiesList` via `filter`, `findById()` calls `bodiesById[id]`, `bodyCount` reads `bodiesList.size`, and `activeBodies` filters `bodiesList`. All of these are unsynchronized reads against collections mutated on the physics thread.

For `findById(id)` specifically, `LinkedHashMap.get()` is safe to call concurrently with iteration (no structural modification), but is NOT safe to call concurrently with `put`/`remove` (which happen in `drainPendingMutations`). Since `drainPendingMutations` runs at the start of `step()`, and the main thread could call `findById()` at any time, a concurrent `findById()` during `drainPendingMutations` could see inconsistent internal state.

**Evidence**: Plan section 1.3 lines 854-857 (query functions), lines 814-815 (unsynchronized collections), lines 838-852 (`drainPendingMutations` modifies collections on physics thread).

**Why this was not caught before**: R8-C1 focused on the `step()` iteration path (where `ConcurrentModificationException` would crash). The query API path was not analyzed because the functions were presented without discussion of their threading contract.

**Fix**: Either:
- (a) Document that `findByTag()`, `findById()`, `bodyCount`, and `activeBodies` must ONLY be called from the physics thread (or from within `step()` callbacks). This restricts usability but is simple.
- (b) Provide thread-safe query alternatives that read from the published snapshot. For example, `findById()` could check the deferred mutation queue and the snapshot.
- (c) Use a `ReadWriteLock` -- physics thread takes write lock during `drainPendingMutations`, read lock during iteration; main thread takes read lock for queries. Since `drainPendingMutations` only runs once at the start of `step()`, read lock contention is minimal.

Option (a) is simplest and is how most physics engines handle this (Box2D's `b2World::GetBodyList()` is explicitly not thread-safe).

### S2. `PhysicsShape` uses `ComposeNode` but the existing codebase uses `ReusableComposeNode` -- missing reuse optimization

The plan's `PhysicsShape` (section 4b.5d, line 2460) uses `ComposeNode`:

```kotlin
ComposeNode<PhysicsShapeNode, IsometricApplier>(
    factory = { PhysicsShapeNode(centeredShape, color, rb.id) },
    update = { ... }
)
```

The existing `IsometricComposables.kt` uses `ReusableComposeNode` for all composables (`Shape`, `Group`, `Path`, `Batch`):

```kotlin
ReusableComposeNode<ShapeNode, IsometricApplier>(
    factory = { ShapeNode(shape, color) },
    update = { ... }
)
```

`ReusableComposeNode` is a Compose optimization that allows the runtime to reuse node instances when items move within a list (e.g., `ForEach` reordering), instead of destroying and recreating them. For physics bodies, reuse would mean the `PhysicsShapeNode` is recycled when a `PhysicsShape` composable moves within a `ForEach` -- avoiding a factory call and reducing GC pressure.

However, `PhysicsShape` has side effects outside the `ComposeNode` (`remember` for body creation, `DisposableEffect` for cleanup), so reuse semantics are more complex than for static shapes. The `remember(key ?: shape, body)` block would need to be stable across reuses. Using `ComposeNode` is the safer choice, but `ReusableComposeNode` could be adopted after verifying that the body lifecycle is compatible.

**Evidence**: Plan section 4b.5d line 2460 (`ComposeNode`), actual `IsometricComposables.kt` lines 43, 80, 120, 159 (`ReusableComposeNode`).

**Fix**: Consider using `ReusableComposeNode` with the `update` block handling all mutable properties. Document why `ComposeNode` was chosen if reuse is intentionally avoided.

### S3. `Pyramid` constructor parameters `dx`/`dy`/`dz` are bare (not `val`) in the actual codebase -- Phase 0 must add `val` to `Pyramid` as well, but plan omits `Pyramid` from the explicit Phase 0 code listings

The plan's Phase 0 (section 0.1, lines 274-321) shows explicit code for `Prism`, `Cylinder`, `Octahedron`, `Stairs`, and `Knot` -- each with `val` added to the relevant constructor parameters. But `Pyramid` is not shown with explicit code. The actual `Pyramid.kt` has:

```kotlin
class Pyramid(
    origin: Point,
    dx: Double = 1.0,
    dy: Double = 1.0,
    dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz))
```

Like `Prism`, the `dx`/`dy`/`dz` parameters are bare (not `val`). `InertiaCalculator.compute()` (section 1.5, line 1007) accesses `shape.dx`, `shape.dy`, `shape.dz` for the `Pyramid` case:

```kotlin
is Pyramid -> pyramidInertia(mass, shape.dx, shape.dy, shape.dz)
```

This will not compile unless Phase 0 adds `val` to `Pyramid`'s `dx`/`dy`/`dz` parameters. The plan's Phase 0 description says "Only dimension parameters are promoted to val fields" and lists `Pyramid.kt` in the module structure, and the Phase 0 tests mention "Shapes created via companion factory functions also expose dimensions." However, there is no explicit code snippet for Pyramid comparable to the Prism/Cylinder/Octahedron/Stairs/Knot snippets.

**Evidence**: Actual `Pyramid.kt` lines 11-16 (bare `dx`/`dy`/`dz`), plan section 1.5 line 1007 (`shape.dx`/`shape.dy`/`shape.dz` access on `Pyramid`), plan section 0.1 lines 274-321 (no `Pyramid` code snippet).

**Fix**: Add an explicit `Pyramid` code snippet to Phase 0 showing `val dx`, `val dy`, `val dz`:

```kotlin
// Pyramid.kt
class Pyramid(
    origin: Point,
    val dx: Double = 1.0,
    val dy: Double = 1.0,
    val dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz))
```

This is almost certainly the intended behavior (the fix table references "all shape classes expose dimensions"), but the explicit listing prevents implementation confusion.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **I1**: `PhysicsBodyRef` write operations lack `@Volatile` -- forces/impulses silently lost on ARM64 | Trivial (add `@Volatile` to 4 fields) | Applied forces/impulses may be invisible to physics thread on production Android devices |
| 2 | **S1**: `findByTag()`/`findById()` not thread-safe for main-thread callers | Low (add documentation or `ReadWriteLock`) | Undefined behavior if called during physics step |
| 3 | **S3**: `Pyramid` Phase 0 code snippet missing | Trivial | Implementation clarity |
| 4 | **S2**: `ComposeNode` vs `ReusableComposeNode` | Trivial | Minor performance optimization for list reordering |

---

## Overall Assessment

The plan is in excellent shape after nine rounds of revision. The 110 previously identified fixes have been incorporated thoroughly, and the architecture is sound at every level -- module split, threading model, snapshot-based position sync, deferred body mutation queue, compound colliders with centroid correction, island-based sleep, Compose lifecycle integration, and renderer cache invalidation.

The single important finding this round (I1) is a JVM Memory Model visibility gap in the force/impulse application path. `RigidBody.force`, `torque`, `velocity`, and `angularVelocity` are written from the main thread via `PhysicsBodyRef.applyForce()` etc. and read from the physics thread during `step()`. Without `@Volatile`, writes on one CPU core may not be visible to reads on another core, particularly on ARM64 (Android's production architecture). The fix is trivial: add `@Volatile` to these four fields. This was not caught in prior reviews because (a) the threading analysis correctly identified and fixed the two main cross-thread data flows (snapshot publishing via `AtomicReference` for reads, deferred queue via `ConcurrentLinkedQueue` for structural mutations), but (b) the force/impulse write path was assessed as "safe" based on the assumption that plain field writes are visible across threads, which is not guaranteed by the JVM Memory Model without a happens-before relationship.

The query API thread safety concern (S1) is a lesser version of the same pattern -- main-thread reads of physics-thread-owned collections without synchronization. This is a common physics engine design trade-off and can be addressed with documentation.

**The plan is ready for implementation once I1 is resolved.** The fix is trivial -- adding `@Volatile` to four `RigidBody` fields. No architectural changes are needed.

The physics architecture as designed -- with its snapshot-based threading model, origin-centered shape convention, deferred mutation queue, compound collider centroid correction, island-based sleep, Compose lifecycle management, and renderer cache invalidation via `markDirty()` -- is well-thought-out and production-ready. Ten rounds of review have produced a comprehensive and internally consistent plan.
