# Physics Implementation Plan -- Review Round 12

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R11)
> **Status**: Issues identified -- plan revision recommended

---

## Review Summary

The R11 revision addresses 116 issues across eleven prior review rounds. The plan is mature, architecturally sound, and comprehensively documented. After reading the full ~3135-line plan, all 10 available review documents (rounds 2-11), and cross-referencing against the actual codebase (`IsometricScene.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `IsometricApplier.kt`, `RenderContext.kt`, `IsometricComposables.kt`, `IsometricScope.kt`, `CompositionLocals.kt`, all shape classes in `isometric-core/src/main/kotlin/.../shapes/`, `Vector.kt`, `Point.kt`, `Path.kt`, `Shape.kt`, `IsometricEngine.kt`, and both `build.gradle.kts` files plus `settings.gradle`), I have identified **1 important issue** and **1 suggestion** that are genuinely new -- not repeats of any previously reported findings.

No critical issues were found. The plan is close to implementation-ready.

---

## CRITICAL Issues (Must Fix Before Implementation)

None identified.

---

## IMPORTANT Issues (Should Fix)

### I1. `RigidBody.applyForce()` read-modify-write races with `clearForces()` on the physics thread -- stale forces from the previous step can leak into the next step

The plan's `RigidBody.applyForce()` (section 1.2, line 581) performs a read-modify-write on the `force` field:

```kotlin
fun applyForce(f: Vector) {
    force = force + f  // read current force, add f, write result
}
```

`force` is marked `@Volatile` (added by R10-I1, plan line 567):

```kotlin
@Volatile var force: Vector = Vector.ZERO
```

`PhysicsWorld.step()` (section 3.2, line 1687) calls `clearForces()` at the end of each step, which sets `body.force = Vector.ZERO` on the physics thread. Meanwhile, the main thread can call `body.applyForce(f)` at any time via `PhysicsBodyRef.applyForce()` (section 4b.7, line 2636).

The `@Volatile` annotation ensures that writes on one thread are visible to reads on another thread. However, the compound read-modify-write in `applyForce()` is NOT atomic. The following interleaving is possible:

1. Main thread begins `applyForce(f)`: reads `force` (volatile read) -- gets the accumulated force `F` from the current step
2. Physics thread: executes `clearForces()` -> writes `force = Vector.ZERO` (volatile write)
3. Main thread completes `applyForce(f)`: writes `force = F + f` (volatile write) -- overwrites the clear

After this interleaving, `force` is `F + f` instead of `f`. The force `F` from the previous physics step was not cleared and leaks into the next step. The user intended to apply `f` for one step, but the body receives `F + f`, where `F` includes gravity accumulation, force field contributions, and any other forces from the previous step.

The same race applies to `applyTorque()` and `applyImpulse()` (which performs `velocity = velocity + impulse * inverseMass`), racing against the physics thread's velocity integration and clear operations.

**Severity**: This is not a crash but a subtle physics correctness issue. The leaked force `F` is typically dominated by gravity (`mass * g * dt`), so a dynamic body receiving a user-applied force could experience a one-frame "double gravity" effect. For force fields, the leaked accumulated force from all active fields would carry over. The effect is intermittent (depends on thread timing) and more likely on faster devices where the physics step and main thread execute in tighter interleaving.

**Evidence**: Plan section 1.2 line 581 (`force = force + f` -- non-atomic RMW), plan line 567 (`@Volatile` provides visibility but not atomicity), plan section 3.2 line 1687 (`clearForces()` on physics thread), plan section 4b.7 line 2636 (`applyForce` called from main thread via `PhysicsBodyRef`).

**Why this was not caught before**: R10-I1 correctly identified the memory visibility problem and added `@Volatile`. The R10 analysis stated: "The read-modify-write race in applyForce (force = force + f) is still not atomic, but since all main-thread writes are sequential (Compose runs on a single thread), concurrent main-thread writes cannot occur. The only race is main-thread write vs physics-thread read, which @Volatile resolves." This analysis considered only two directions: (a) main-thread write racing with main-thread write (correctly dismissed -- single-threaded), and (b) main-thread write racing with physics-thread read (correctly resolved by `@Volatile`). It did not consider (c) main-thread read-modify-write racing with physics-thread write (`clearForces()`). The `clearForces()` write-to-zero is a physics-thread WRITE that can interleave with the main-thread's read phase of the read-modify-write, causing the main thread to read a stale pre-clear value and then overwrite the clear.

**Fix**: Use a deferred force accumulation approach, similar to the deferred body mutation queue (R8-C1). Instead of mutating `force` directly from the main thread, enqueue force/impulse/torque requests into a thread-safe queue. The physics thread drains the queue at the start of `applyForces()` (step 1 of `step()`), after `clearForces()` from the previous step has already run.

```kotlin
// In RigidBody:
// Remove @Volatile from force/torque -- no longer written from main thread
var force: Vector = Vector.ZERO
var torque: Vector = Vector.ZERO

// Thread-safe queue for deferred force/impulse/torque from main thread
sealed class ForceRequest {
    data class Force(val f: Vector) : ForceRequest()
    data class ForceAtPoint(val f: Vector, val point: Point) : ForceRequest()
    data class Impulse(val impulse: Vector) : ForceRequest()
    data class ImpulseAtPoint(val impulse: Vector, val point: Point) : ForceRequest()
    data class Torque(val t: Vector) : ForceRequest()
}
val pendingForces = ConcurrentLinkedQueue<ForceRequest>()

// Main-thread-safe methods:
fun applyForce(f: Vector) { pendingForces.add(ForceRequest.Force(f)) }
fun applyImpulse(impulse: Vector) { pendingForces.add(ForceRequest.Impulse(impulse)) }
fun applyTorque(t: Vector) { pendingForces.add(ForceRequest.Torque(t)) }

// Called on physics thread at start of step, after clearForces:
fun drainPendingForces() {
    while (true) {
        val req = pendingForces.poll() ?: break
        when (req) {
            is ForceRequest.Force -> force = force + req.f
            is ForceRequest.Impulse -> {
                velocity = velocity + req.impulse * inverseMass
            }
            is ForceRequest.Torque -> torque = torque + req.t
            // ... other cases
        }
    }
}
```

Then in `PhysicsWorld.step()`, drain pending forces after clearing previous-step forces:

```kotlin
fun step(dt: Double) {
    drainPendingMutations()

    // 1. Clear previous-step forces, then drain main-thread force requests
    clearForces()
    for (body in bodiesList) { body.drainPendingForces() }

    // 2. Apply gravity and force fields (adds to the now-clean force accumulator)
    applyForces(dt)
    // ... rest of step
}
```

This eliminates the race entirely: `force` is only ever written on the physics thread, and main-thread requests are queued through a thread-safe `ConcurrentLinkedQueue` (same pattern as the body mutation queue from R8-C1).

Alternatively, a simpler but slightly less clean approach: keep `@Volatile` on `force`/`torque` and accept the race as a minor physics artifact. Document that forces applied via `PhysicsBodyRef.applyForce()` may occasionally persist for one extra step. For most gameplay scenarios (tap-to-push, wind effects), this one-frame leakage is imperceptible. This pragmatic approach avoids the queue overhead.

The deferred queue is recommended for correctness, but the pragmatic approach is acceptable if documented.

---

## SUGGESTIONS (Nice to Have)

### S1. `PhysicsScene`'s `onTap` handler performs a redundant hit test -- `IsometricScene` already tests for the hit node, but `PhysicsScene` discards it and does a separate physics raycast

The plan's `PhysicsScene` (section 4b.4, lines 2253-2259) passes an `onTap` lambda to `IsometricScene`:

```kotlin
onTap = if (onTap != null) { x, y, node ->
    val engine = engineRef ?: return@IsometricScene
    val ray = PhysicsRaycastUtils.screenToWorldRay(x, y, engine)
    val hit = world.raycast(ray)
    onTap(x, y, hit?.body)
} else { _, _, _ -> },
```

The actual `IsometricScene` (codebase line 260-268) performs a hit test via `renderer.hitTest()` to produce the `node` parameter before calling `onTap`. This hit test walks the spatial index, builds a filtered `PreparedScene`, calls `engine.findItemAt()`, and resolves the command ID to a node. For a scene with 300 physics bodies (1800 paths), this is non-trivial work.

`PhysicsScene`'s lambda receives this `node` but immediately discards it, performing a completely separate physics raycast (`world.raycast(ray)`) against the physics broadphase and narrowphase. The renderer hit test and the physics raycast are independent code paths that produce different result types (`IsometricNode?` vs `RaycastResult?`).

This means every tap on a `PhysicsScene` performs two independent spatial queries: (1) the renderer's visual hit test (result discarded), and (2) the physics raycast (result used). The renderer hit test is wasted work.

**Evidence**: Plan line 2253-2259 (`node` parameter received but unused), actual codebase `IsometricScene.kt` lines 260-268 (renderer performs hit test before calling `onTap`).

**Why this was not caught before**: R3-C5 changed the tap from a separate overlay `Box` to using `IsometricScene.onTap`. R5-C2 fixed the null-vs-lambda issue. R6-I3 fixed the engine reference. All reviews focused on the correctness of the physics raycast path, not on the efficiency of the overall tap pipeline.

**Fix**: Two options:

**(a) Suppress the renderer hit test when physics handles taps.** Add an `enableHitTest: Boolean = true` parameter to `IsometricScene`. When `PhysicsScene` is handling taps via physics raycast, pass `enableHitTest = false` to skip the renderer's visual hit test entirely. The `onTap` lambda would receive `null` for `node`, which `PhysicsScene` already ignores.

**(b) Accept the redundancy.** Taps are infrequent (human input), so the wasted hit test is unlikely to matter in practice. Document that `PhysicsScene` uses physics raycasting for tap resolution, not the renderer's visual hit test.

Option (b) is simpler and sufficient. The redundancy only affects tap latency (a few extra microseconds), not rendering performance.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **I1**: `applyForce()` read-modify-write races with `clearForces()` -- stale forces leak across steps | Medium (deferred queue) or Trivial (document and accept) | Intermittent double-gravity or stale force field effects; subtle physics incorrectness |
| 2 | **S1**: Redundant renderer hit test on tap | Trivial (document) or Low (add enableHitTest flag) | Wasted computation on infrequent tap events |

---

## Overall Assessment

The plan is in excellent shape after eleven rounds of revision. The 116 previously identified fixes have been incorporated thoroughly, and the architecture is sound at every level -- module split, threading model, snapshot-based position sync, deferred body mutation queue, compound colliders with centroid correction, island-based sleep, Compose lifecycle integration, and renderer cache invalidation via `markDirty()`.

The single important finding this round (I1) is a read-modify-write atomicity gap in the force/impulse application path. While R10-I1 correctly identified and fixed the memory visibility issue (adding `@Volatile`), the atomicity of `force = force + f` racing against `clearForces()` (which writes `force = Vector.ZERO` on the physics thread) was not analyzed. If `clearForces()` executes between the main thread's read of `force` and its subsequent write of `force + f`, the clear is lost and stale forces carry over to the next step. The fix is either a deferred force queue (matching the R8-C1 pattern for body mutations) or documenting the race as an acceptable one-frame artifact.

The suggestion (S1) is a minor efficiency observation: `PhysicsScene`'s tap handler discards the `node` from `IsometricScene`'s hit test and performs a separate physics raycast. This results in two spatial queries per tap, but since taps are infrequent human input events, the practical impact is negligible.

**The plan is ready for implementation once I1 is addressed.** The deferred force queue fix is the cleanest solution but requires medium effort (new sealed class, `ConcurrentLinkedQueue` per body, drain at step start). The pragmatic alternative is to document the race as acceptable. No architectural changes are needed either way.

The physics architecture as designed -- with its snapshot-based threading model, origin-centered shape convention, deferred mutation queue, compound collider centroid correction, island-based sleep, Compose lifecycle management, and renderer cache invalidation via `markDirty()` -- is well-thought-out and production-ready. Twelve rounds of review have produced a comprehensive, internally consistent, and thoroughly vetted plan.
