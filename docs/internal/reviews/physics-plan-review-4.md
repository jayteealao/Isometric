# Physics Implementation Plan -- Review Round 4

> **Date**: 2026-03-13
> **Reviewer**: Architecture review agent
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md)
> **Status**: Issues identified -- plan revision required

---

## Review Summary

The R3 revision has addressed the vast majority of earlier findings well. The module split, threading model, compound colliders, island-based sleep, snapshot-based position sync, and mutable contact pools are all sound. However, cross-referencing every code snippet against the actual codebase reveals **3 critical issues** that will prevent compilation or cause runtime crashes, **5 important issues** that will cause incorrect behavior, and **4 suggestions**. The most severe cluster is in the Compose integration layer (Phase 4b), where the plan's `PhysicsScene` content lambda references a `rootNode` that is inaccessible from the `IsometricScope`, and the `PhysicsShapeNode.render()` applies the `RenderContext` accumulated transform on top of the physics transform, resulting in double-translation for shapes inside groups.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. `PhysicsScene` content lambda cannot access `rootNode` -- will not compile

The plan's `PhysicsScene` (section 4b.4, line ~1782) delegates to `IsometricScene` and passes a content lambda that calls:

```kotlin
content = {
    val snapshot = latestSnapshot.value
    if (snapshot.isNotEmpty()) {
        syncPositionsToNodes(this.rootNode, snapshot)
    }
    PhysicsScopeImpl(this, world).content()
}
```

Inside `IsometricScene`'s content lambda, `this` is `IsometricScope` -- a marker interface with zero properties (`IsometricScope.kt` line 10-14):

```kotlin
@Stable
interface IsometricScope {
    // marker interface only
}
```

The `rootNode` is a *local variable* inside `IsometricScene` (`IsometricScene.kt` line 99: `val rootNode = remember { GroupNode() }`). It is never exposed through `IsometricScope` or any other public API. The plan's content lambda cannot reference `this.rootNode` because `IsometricScope` has no such property.

Similarly, `PhysicsScopeImpl(this, world)` passes `this` (an `IsometricScope`) as the first argument, but the plan never defines `PhysicsScopeImpl`'s constructor parameters. If it expects an `IsometricScope`, it still cannot reach the root node.

**Evidence**: `IsometricScope.kt` lines 10-14, `IsometricScene.kt` line 99.

**Fix**: The position sync cannot happen inside the `IsometricScene` content lambda. Options:
- (a) Expose `rootNode` through a new `IsometricScope` property or callback (requires modifying `isometric-compose`)
- (b) Move `syncPositionsToNodes` into a `SideEffect` or `LaunchedEffect` that captures the root node via a different mechanism -- e.g., have `PhysicsScene` create its own `GroupNode` root, pass it to `IsometricScene` (but `IsometricScene` creates its own root internally, so this doesn't work either)
- (c) Have `PhysicsScene` not wrap `IsometricScene` at all, but instead duplicate the necessary rendering setup with its own root node that it controls directly. This is more code but avoids the encapsulation problem entirely
- (d) Add a `rootNodeRef` callback parameter to `IsometricScene` similar to the existing `onHitTestReady` pattern, e.g., `onRootNodeReady: ((GroupNode) -> Unit)? = null`. This is the least invasive option

Option (d) is recommended: add a single callback parameter to `IsometricScene` that exposes the root node reference.

### C2. `PhysicsShapeNode.render()` double-applies context transforms for nested groups

The plan's `PhysicsShapeNode.render()` (section 4b.2, line ~1634) first applies physics rotation + translation to all vertices:

```kotlin
val rx = rotMatrix.m00 * p.x + rotMatrix.m01 * p.y + rotMatrix.m02 * p.z
...
Point(physicsPosition.x + rx, physicsPosition.y + ry, physicsPosition.z + rz)
```

Then it applies the `RenderContext` accumulated transform:

```kotlin
val finalShape = context.applyTransformsToShape(transformed)
return renderShape(finalShape, color, context)
```

`RenderContext.applyTransformsToShape()` (line 84-111 of `RenderContext.kt`) translates by `accumulatedPosition`, rotates by `accumulatedRotation`, and scales by `accumulatedScale`. If a `PhysicsShapeNode` is placed inside a `Group(position = Point(5.0, 0.0, 0.0))`, the context will have `accumulatedPosition = (5, 0, 0)`. The physics already positioned the shape at `physicsPosition`. The context transform will shift it an additional `(5, 0, 0)`.

For a physics body at world position `(3, 2, 0)` inside a group at `(5, 0, 0)`, the rendered position would be `(8, 2, 0)` instead of `(3, 2, 0)`.

The existing `ShapeNode.render()` applies both context transforms AND local node transforms, which is correct for the static scene graph. But physics shapes should ignore the parent group's accumulated transform because physics positions are in world space, not relative to the parent group.

**Evidence**: `RenderContext.kt` lines 84-111 (applies accumulated transforms), `PhysicsShapeNode.render()` plan lines ~1649 (calls `context.applyTransformsToShape`).

**Fix**: `PhysicsShapeNode.render()` should NOT call `context.applyTransformsToShape()`. It should use a fresh `RenderContext` (or pass the already-world-space transformed shape directly to `renderShape` without the context transform). The isometric projection (2D screen mapping) is done by `IsometricEngine`, not by `RenderContext`, so skipping the context transform does not affect the final 2D projection.

### C3. Plan's Phase 0 `Cylinder` changes the super-call from `Shape.extrude(Circle(...))` to non-existent `createPaths()`

The plan's Phase 0 shows Cylinder as:

```kotlin
class Cylinder(
    origin: Point,
    val radius: Double = 1.0,
    val vertices: Int = 20,
    val height: Double = 1.0
) : Shape(createPaths(origin, radius, vertices, height))
```

But the actual `Cylinder` super-call is:

```kotlin
) : Shape(Shape.extrude(Circle(origin, radius, vertices), height).paths)
```

There is no `createPaths` companion function in `Cylinder`. The plan introduces a function that does not exist without noting that it needs to be created, or that the super-call should remain as the existing `Shape.extrude(Circle(...))` chain.

**Evidence**: `Cylinder.kt` lines 10-15.

**Fix**: Either keep the existing super-call (`Shape(Shape.extrude(Circle(origin, radius, vertices), height).paths)`) and just add `val` to the constructor parameters, or explicitly note that a `createPaths` companion function must be created that wraps the extrude logic.

---

## IMPORTANT Issues (Should Fix)

### I1. `Stairs` constructor parameter `stepCount` is not `val` -- plan assumes Phase 0 adds it but the change is fragile

The plan's Phase 0 shows:

```kotlin
class Stairs(origin: Point, val stepCount: Int) : Shape(createPaths(origin, stepCount))
```

The actual `Stairs.kt` has:

```kotlin
class Stairs(origin: Point, stepCount: Int) : Shape(createPaths(origin, stepCount))
```

Adding `val` here is correct and intended. However, `Stairs` does NOT have a `createPaths` that matches the plan's signature issues noted in C3. Actually, `Stairs.kt` line 10 does use `createPaths(origin, stepCount)` -- so this is fine for Stairs. The issue is isolated to Cylinder.

But the plan also adds computed properties:

```kotlin
val width: Double get() = 1.0
val depth: Double get() = 1.0
val height: Double get() = 1.0
```

These return hardcoded `1.0` values, which is correct for the current implementation. However, `Stairs` does NOT currently take `width`/`depth`/`height` parameters -- the staircase is always 1x1x1. If anyone later parameterizes Stairs dimensions, these computed properties will silently return wrong values. The plan should add a comment noting that these are valid ONLY because Stairs is fixed-size.

**Evidence**: `Stairs.kt` lines 10-54 (fixed 1x1x1 geometry).

**Fix**: Add a comment in the plan noting the `width`/`depth`/`height` properties are only valid because Stairs is inherently 1x1x1 and would need to become `val` constructor parameters if Stairs is ever parameterized.

### I2. `RigidBody.aabb` is `MutableAABB` but `CcdSolver` calls `.expanded()` which is only on `AABB`

The plan's `CcdSolver` (section 4a.4, line ~1532):

```kotlin
val swept = bullet.aabb.expanded(bullet.velocity.magnitude() * dt)
```

`bullet.aabb` is `MutableAABB` (section 1.2, line 528). But `expanded()` is defined only on the immutable `AABB` data class (section 1.1, line 386). `MutableAABB` has `updateFrom()`, `intersects()`, and `toImmutable()` -- but NOT `expanded()`.

**Evidence**: Plan section 1.1 `MutableAABB` (lines 415-423) vs `AABB` (lines 374-409), and `CcdSolver` (line 1532).

**Fix**: Either add `expanded()` to `MutableAABB` (returning a new `AABB`), or change the CCD code to `bullet.aabb.toImmutable().expanded(...)`.

### I3. `PhysicsVector.kt` defines `operator fun Vector.plus/minus/times` but `Vector` is a `data class` -- Kotlin already generates `component1/2/3` but NOT operators

The plan defines extension operators:

```kotlin
operator fun Vector.plus(other: Vector): Vector
operator fun Vector.minus(other: Vector): Vector
operator fun Vector.times(scalar: Double): Vector
```

These are correct as extension functions. However, the plan also uses `direction * strength` where `direction: Vector` and `strength: Double`. This works. But in `ForceField.Directional`:

```kotlin
body.applyForce(direction * strength)
```

If `strength` is negative (repulsion), this produces a reversed vector. Fine. But in `PhysicsStep.update()`, the plan accumulates gravity as `gravity * body.config.gravityScale`. This calls `Vector.times(Double)`. But `Vector.ZERO` and `Vector.GRAVITY` are proposed as constants in `Vector.Companion`. The plan defines `Vector.GRAVITY = Vector(0.0, 0.0, -9.81)`, but `PhysicsConfig.gravity` also defaults to `Vector(0.0, 0.0, -9.81)`. Having two sources of the gravity constant is redundant and risks divergence.

**Evidence**: Plan section 0.1 (`Vector.GRAVITY`) and section 1.3 (`PhysicsConfig.gravity`).

**Fix**: Either have `PhysicsConfig.gravity` default to `Vector.GRAVITY` (after Phase 0 adds it), or remove `Vector.GRAVITY` and keep the gravity default only in `PhysicsConfig`. One canonical gravity constant, not two.

### I4. `Knot` does NOT use `origin` as a simple position offset -- physics collider derivation will be wrong

The plan's `KnotCompoundCollider.create(origin: Point)` decomposes the knot into 3 prism segments. But the actual `Knot.kt` geometry is complex:

1. Creates 3 prisms at hardcoded positions: `Point.ORIGIN`, `Point(4.0, 1.0, 0.0)`, `Point(4.0, 4.0, -2.0)`
2. Adds 2 custom triangular faces
3. Scales everything by `1.0/5.0` around `Point.ORIGIN`
4. Translates by `(-0.1, 0.15, 0.4)`
5. THEN translates by `(origin.x, origin.y, origin.z)`

The final geometry is NOT simply "3 prisms at origin-relative positions." The `1/5` scaling and the `(-0.1, 0.15, 0.4)` offset transform all positions. The compound collider must apply these same transformations to match the visual shape.

The plan's `KnotCompoundCollider` just says "Decompose knot's 3 prism segments into separate convex colliders. Each segment has its own local transform." This is insufficient -- the actual local transforms involve scaling by 0.2 and an additional fixed offset, which are not mentioned.

Additionally, the 2 extra custom triangular paths (lines 23-38 of `Knot.kt`) are NOT prisms and cannot be represented by `PrismCollider`. They would need to be either ignored (leaving collision gaps) or represented as `PyramidCollider` or generic vertex colliders.

**Evidence**: `Knot.kt` lines 17-43.

**Fix**: Document the full Knot decomposition including the 1/5 scale factor and (-0.1, 0.15, 0.4) offset. Either approximate the 2 extra faces as part of the nearest prism's AABB or add dedicated colliders for them. This is not trivial and should be acknowledged as such.

### I5. `Island` is a `data class` with a computed `isSleeping` property -- `data class` generates `equals`/`hashCode`/`toString`/`copy` that DO NOT include computed properties

The plan (section 3.3, line ~1370):

```kotlin
data class Island(
    val bodies: List<RigidBody>,
    val manifolds: List<ContactManifold>
) {
    val isSleeping: Boolean get() = bodies.all { it.isSleeping || it.isStatic }
}
```

Since `isSleeping` is a computed `get()` property (not a constructor parameter), it is NOT included in `data class` equals/hashCode/toString/copy. Two islands with the same bodies and manifolds but different body sleep states would be `==` even if one is sleeping and one is not.

This is unlikely to cause bugs in practice (islands are transient objects rebuilt each step and never equality-compared), but `toString()` will not show `isSleeping`, making debug logging misleading.

**Evidence**: Kotlin `data class` specification -- only constructor parameters participate in generated members.

**Fix**: Change `Island` to a regular `class`, or at minimum document that `isSleeping` is intentionally excluded from equality. Since `Island` is a transient per-step object, a regular class is more appropriate.

---

## SUGGESTIONS (Nice to Have)

### S1. `PhysicsConfig.gravity` defaults to `Vector(0.0, 0.0, -9.81)` but `Vector` fields are `i`, `j`, `k` -- not `x`, `y`, `z`

Throughout the plan, gravity is created with `Vector(0.0, 0.0, -9.81)`. This sets `i = 0.0`, `j = 0.0`, `k = -9.81`. The mapping is `i = x-axis`, `j = y-axis`, `k = z-axis`. In the isometric coordinate system, `z` (stored as `k`) is the vertical axis. So `Vector(0.0, 0.0, -9.81)` means gravity pulls in the `-k` direction, which is downward in the isometric world. This is correct.

However, the `Vector` field names (`i`, `j`, `k`) are unconventional for physics code which typically uses (`x`, `y`, `z`). The `PhysicsVector.kt` extension functions will work fine since they operate on `Vector` instances, but any physics code that needs to access individual components will use `body.velocity.i` instead of `body.velocity.x`. This is a readability concern, not a bug.

**Suggestion**: Consider adding extension properties `val Vector.x get() = i`, `val Vector.y get() = j`, `val Vector.z get() = k` in `PhysicsVector.kt` for readability in physics code.

### S2. `EventDispatcher` listener removal uses referential equality on lambdas

The plan's `EventDispatcher` (section 4a.1):

```kotlin
fun removeCollisionListener(listener: (CollisionEvent) -> Unit) { collisionListeners.remove(listener) }
```

`MutableList.remove()` uses `equals()`. For lambdas in Kotlin/JVM, referential equality applies -- two separately declared lambdas with identical bodies are NOT equal. This means:

```kotlin
dispatcher.addCollisionListener { event -> println(event) }
// Cannot remove: a new lambda instance is not == the one added
dispatcher.removeCollisionListener { event -> println(event) }  // no-op
```

Users must store a reference to the listener lambda to remove it later. This is a common pattern but should be documented.

**Suggestion**: Document that listeners must be stored as `val` references for removal. Consider returning a `Disposable` or registration handle from `addCollisionListener` for cleaner lifecycle management.

### S3. `PhysicsBodyRef.velocity` reads directly from `RigidBody` -- acknowledged but still inconsistent

The plan (section 4b.7, line ~2003):

```kotlin
val velocity: Vector get() = body.velocity
```

The comment says "Velocity is NOT interpolated -- reads the physics thread's latest value." While the plan acknowledges this, it creates an inconsistent API surface: `position` and `orientation` come from the snapshot (safe), but `velocity` comes from the live body (potentially mid-step). A user who reads `position` and `velocity` in the same frame may get temporally inconsistent values (position from end of last step, velocity from middle of current step).

**Suggestion**: Add `velocity` and `angularVelocity` to the snapshot so all reads are temporally consistent. The overhead is minimal (two extra `Vector` values per body per snapshot).

### S4. `scheduleWithFixedDelay` with 16ms delay means physics runs at ~55 Hz, not 60 Hz

The plan uses `scheduleWithFixedDelay({...}, 0, 16L, TimeUnit.MILLISECONDS)` (section 1.4, line ~750). `scheduleWithFixedDelay` waits 16ms after the task *completes*. If the physics step takes 2ms, the actual period is 18ms (55.5 Hz). With a fixed timestep of `1.0/60.0` and an 18ms real-world period, the accumulator will never quite catch up -- each frame adds 18ms but only consumes 16.67ms, so occasional double-steps will occur.

This is functionally fine (the fixed timestep accumulator handles variable input deltas correctly), and the Android `Choreographer`-based thread avoids this entirely. But for JVM-only tests, the effective step rate will be slightly irregular.

**Suggestion**: For the JVM `PhysicsThread`, use `scheduleAtFixedRate` (not `scheduleWithFixedDelay`) with a guard against runaway catch-up: if the step takes longer than the interval, skip the make-up step rather than running multiple back-to-back. Alternatively, accept the ~55 Hz rate since the accumulator handles it and the JVM thread is only for testing, not production.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **C1**: `PhysicsScene` content lambda cannot access `rootNode` | Medium | Compilation failure -- blocks entire Phase 4b |
| 2 | **C2**: Double-transform in `PhysicsShapeNode.render()` for nested groups | Low | All physics shapes inside groups render at wrong positions |
| 3 | **C3**: Cylinder `createPaths` does not exist -- super-call wrong | Low | Phase 0 Cylinder change will not compile |
| 4 | **I2**: `MutableAABB` missing `expanded()` for CCD | Trivial | CCD code will not compile |
| 5 | **I4**: Knot compound collider ignores 1/5 scale and offset | Medium | Knot collisions will not match visual geometry |
| 6 | **I3**: Duplicate gravity constants (`Vector.GRAVITY` vs `PhysicsConfig.gravity`) | Trivial | Maintenance risk |
| 7 | **I5**: `Island` as data class excludes computed `isSleeping` | Trivial | Misleading debug output |
| 8 | **I1**: Stairs hardcoded dimension properties need documentation | Trivial | Maintenance risk |
| 9 | **S3**: `PhysicsBodyRef.velocity` reads from live body, not snapshot | Low | Temporal inconsistency |
| 10 | **S1**: Vector `i/j/k` vs physics `x/y/z` readability | Trivial | Developer experience |
| 11 | **S2**: Lambda listener removal semantics | Trivial | API clarity |
| 12 | **S4**: `scheduleWithFixedDelay` effective rate ~55 Hz | Trivial | Minor for JVM-only tests |

---

## Overall Assessment

The plan has matured significantly through four rounds of review. The architectural decisions are sound: the module split, the snapshot-based threading model, the compound collider approach, and the phased implementation strategy are all well-reasoned. The R3 revision successfully addressed the compilation failures (missing `override val children`, vertex buffer, sync traversal) and the algorithmic errors (Stairs geometry, Octahedron scale, Island sleep state).

The remaining issues cluster in two areas:

1. **Phase 4b Compose integration** (C1, C2): The `PhysicsScene` wrapper around `IsometricScene` has a fundamental encapsulation problem -- it needs access to the root node that `IsometricScene` keeps private. The double-transform issue in `PhysicsShapeNode.render()` would silently produce wrong positions for any physics shape inside a `Group()`. Both require targeted fixes before Phase 4b can proceed.

2. **Phase 0 accuracy** (C3): The Cylinder super-call change is a minor but real compilation error that should be fixed in the plan to avoid confusion during implementation.

The physics core (Phases 1-4a) is in good shape and can proceed after the trivial fixes (I2 expanded, I3 gravity constant). Phase 4b needs the C1 root node access solution designed before implementation begins.
