# Physics Implementation Plan -- Review Round 13

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R12)
> **Status**: Issues identified -- plan revision recommended

---

## Review Summary

The R12 revision addresses 118 issues across twelve prior review rounds. The plan is mature, architecturally sound, and thoroughly documented. After reading the full ~3185-line plan, all 11 available review documents (rounds 2-12), and cross-referencing against the actual codebase (`IsometricScene.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `IsometricApplier.kt`, `RenderContext.kt`, `IsometricComposables.kt`, `IsometricScope.kt`, `CompositionLocals.kt`, all shape classes in `isometric-core/src/main/kotlin/.../shapes/`, `Vector.kt`, `Point.kt`, `Path.kt`, `Shape.kt`, and `settings.gradle`), I have identified **1 important issue** and **1 suggestion** that are genuinely new -- not repeats of any previously reported findings.

No critical issues were found. Both findings are consequences of the R12-I1 deferred force queue fix.

---

## CRITICAL Issues (Must Fix Before Implementation)

None identified.

---

## IMPORTANT Issues (Should Fix)

### I1. `Point - Point` operator used in `drainPendingForces()` but never defined -- compilation failure

The plan's `RigidBody.drainPendingForces()` (section 1.2, lines 609-616) uses `Point` subtraction in two places:

```kotlin
is ForceRequest.ForceAtPoint -> {
    force = force + req.f
    torque = torque + (req.worldPoint - position).cross(req.f)       // line 610
}
is ForceRequest.ImpulseAtPoint -> {
    velocity = velocity + req.impulse * inverseMass
    angularVelocity = angularVelocity +
        worldInverseInertia * (req.worldPoint - position).cross(req.impulse)  // line 615
}
```

`req.worldPoint` is a `Point` (defined at line 588: `data class ForceAtPoint(val f: Vector, val worldPoint: Point)`). `position` is a `Point` (defined at line 555: `var position: Point`). The expression `req.worldPoint - position` requires an `operator fun Point.minus(other: Point): Vector` to compile.

No such operator exists:
- The actual codebase's `Point.kt` defines `translate()`, `scale()`, `rotateX/Y/Z()`, and `depth()` but no arithmetic operators.
- The plan's `PhysicsVector.kt` (section 1.1, lines 528-535) defines operators on `Vector` (`plus`, `minus`, `times`, `unaryMinus`, `cross`, `dot`), but NOT on `Point`.
- The existing `Vector.fromTwoPoints(p1, p2)` is a static factory method, not an operator.

The `.cross()` call also requires the result of `Point - Point` to be a `Vector`, since `cross` is defined as `fun Vector.cross(other: Vector): Vector` (plan line 532).

This will produce a compilation error at two call sites (lines 610 and 615).

**Evidence**: Plan line 610 (`req.worldPoint - position` where both are `Point`), plan line 615 (same pattern), plan lines 526-544 (`PhysicsVector.kt` defines operators only on `Vector`), actual `Point.kt` (no `minus` operator).

**Why this was not caught before**: The `drainPendingForces()` method was added in R12 as part of the R12-I1 fix (deferred force queue). The torque computation formula `r x F` (moment arm cross force) is physically correct, but the code uses `Point - Point` which is not a defined operation. Prior reviews did not examine R12-I1's implementation for compilation correctness because the focus was on the threading semantics (eliminating the read-modify-write race). The same formula pattern exists in `ImpulseAtPoint` for angular velocity updates.

**Fix**: Add `operator fun Point.minus(other: Point): Vector` to `PhysicsVector.kt`:

```kotlin
// In PhysicsVector.kt — alongside existing Vector operator extensions
operator fun Point.minus(other: Point): Vector =
    Vector(x - other.x, y - other.y, z - other.z)
```

This is the standard physics convention: the difference of two positions is a displacement vector. Alternatively, inline `Vector.fromTwoPoints()` at the two call sites, but the operator is cleaner and will be used extensively throughout the physics engine (contact point offsets, force application points, etc.).

---

## SUGGESTIONS (Nice to Have)

### S1. `ForceField.apply()` calls `body.applyForce()` which enqueues into the deferred force queue -- forces from force fields are delayed by one physics step

The plan's `ForceField.Directional.apply()` (section 4a.3, line 1949) calls:

```kotlin
override fun apply(body: RigidBody, dt: Double) { body.applyForce(direction * strength) }
```

After the R12-I1 fix, `body.applyForce()` (section 1.2, line 596) enqueues into `pendingForces`:

```kotlin
fun applyForce(f: Vector) { pendingForces.add(ForceRequest.Force(f)) }
```

The `PhysicsWorld.step()` sequence (section 3.2, lines 1681-1689) is:

```
Step 1a: clearForces()
         for (body in bodiesList) { body.drainPendingForces() }  // drains queue
Step 1b: applyForces(dt)  // applies gravity + force fields
```

Force fields execute during step 1b, which is AFTER `drainPendingForces()` has already run at step 1a. The forces enqueued by force fields during step 1b will remain in the `pendingForces` queue until the NEXT step's `drainPendingForces()` call. This introduces a one-step delay for all force field effects (Directional, Radial, Wind, Vortex).

The same issue applies to gravity if `applyForces(dt)` uses `body.applyForce()` rather than direct field assignment. However, since `applyForces()` runs on the physics thread and could directly write `body.force = body.force + gravity * ...`, gravity may not be affected -- it depends on whether the implementation uses the public `applyForce()` API or direct field mutation.

For force fields specifically, the plan's code explicitly uses `body.applyForce()` (the queued version), so the one-step delay is certain.

**Severity**: For continuous force fields (gravity scale, wind, radial attraction), a one-step delay (~16ms at 60 Hz) is likely imperceptible. The force is applied every step, just shifted by one step. For impulse-like force fields or fields that change rapidly (turbulent wind), the delay could introduce a subtle one-frame lag. This is unlikely to affect gameplay but is a semantic correctness issue.

**Evidence**: Plan line 1949 (`body.applyForce(direction * strength)` -- enqueues), plan line 596 (`applyForce` enqueues into `pendingForces`), plan lines 1685-1689 (`drainPendingForces` at step 1a, `applyForces` at step 1b).

**Why this was not caught before**: The R12-I1 fix focused on the main-thread-to-physics-thread race in `applyForce()`. The review correctly identified that the deferred queue eliminates the race. However, the force field implementation (section 4a.3) was written before R12-I1 changed `applyForce()` from direct field mutation to queue-based. The force field code was not updated to account for the new semantics.

**Fix**: Two options:

**(a) Physics-thread-only force application method.** Add an `internal` method on `RigidBody` that directly mutates the force accumulator, intended only for physics-thread callers (gravity, force fields, solver):

```kotlin
// In RigidBody — for physics-thread-only callers (ForceField, gravity, solver)
internal fun applyForceInternal(f: Vector) { force = force + f }
internal fun applyTorqueInternal(t: Vector) { torque = torque + t }
```

Force fields and `applyForces(dt)` (gravity) use `applyForceInternal()`. The public `applyForce()` remains queue-based for main-thread callers. This matches the dual-path pattern already established: `addBody()`/`removeBody()` (queued, public) vs `drainPendingMutations()` (direct, internal).

**(b) Run `drainPendingForces()` after `applyForces(dt)`.** Move the drain to after force field application:

```
Step 1a: clearForces()
Step 1b: applyForces(dt)  // gravity + force fields write directly to force accumulator
Step 1c: for (body in bodiesList) { body.drainPendingForces() }  // drain user forces
```

This requires force fields to use direct field mutation (not the queued API), and user forces are drained after gravity/fields are applied. This ordering is correct: user forces and physics forces accumulate additively.

Option (a) is cleaner: it maintains the clear separation between public (queued) and internal (direct) mutation paths.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **I1**: `Point - Point` operator undefined -- compilation failure in `drainPendingForces()` | Trivial (add one operator extension) | Build fails -- code cannot compile as written |
| 2 | **S1**: Force fields delayed by one step due to deferred queue ordering | Low (add internal force application method) | One-frame lag for force fields; imperceptible for steady-state forces |

---

## Overall Assessment

The plan is in excellent shape after twelve rounds of revision. The 118 previously identified fixes have been incorporated thoroughly, and the architecture is sound at every level -- module split, threading model, snapshot-based position sync, deferred body mutation queue, deferred force queue, compound colliders with centroid correction, island-based sleep, Compose lifecycle integration, and renderer cache invalidation via `markDirty()`.

The important finding this round (I1) is a missing operator: `Point - Point` is used in the R12-I1 deferred force queue implementation but no such operator is defined in either the plan's `PhysicsVector.kt` or the existing codebase's `Point.kt`. This is a straightforward compilation failure. The fix is trivial -- add `operator fun Point.minus(other: Point): Vector` to `PhysicsVector.kt`. This operator will be widely useful throughout the physics engine for computing displacement vectors between positions (moment arms, contact offsets, force application points).

The suggestion (S1) is a semantic ordering issue introduced by R12-I1: force fields call `body.applyForce()` (now queued) during `applyForces(dt)`, but `drainPendingForces()` has already run. Force field contributions sit in the queue for one extra step. The fix is to provide an internal direct-mutation method for physics-thread callers (gravity, force fields, solver) while keeping the public `applyForce()` queue-based for main-thread callers.

**The plan is ready for implementation once I1 is resolved.** The fix is trivial -- a single operator extension function. S1 should also be addressed before implementation but is lower priority since steady-state force fields are functionally unaffected by a one-step delay.
