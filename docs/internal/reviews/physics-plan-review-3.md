# Physics Implementation Plan - Review Round 3

> **Date**: 2026-03-13
> **Reviewer**: Senior Game Engine Architect
> **Reviewed**: `docs/plans/physics-implementation-plan.md` (Revised R2)
> **Method**: Full plan reading + cross-referencing against actual codebase source files

---

## Review Summary

The R2 revision has addressed the surface-level issues well. The plan is architecturally sound in broad strokes, and the module split, threading model, and phase ordering are all reasonable. However, deeper analysis reveals several issues that will cause real implementation failures, particularly around the Compose integration layer where the plan's assumptions do not match the actual node hierarchy and Applier constraints. There are also algorithmic correctness issues, a subtle threading race, and several places where the plan's code snippets will not compile against the real codebase.

---

## CRITICAL Issues

### C1: `PhysicsShapeNode` cannot be a leaf node in the current Applier

**Severity**: Will crash at runtime

The `IsometricApplier.insertBottomUp()` casts `current` to `GroupNode` and throws an error if the cast fails:

```kotlin
// IsometricApplier.kt, line 51-52
val parent = current as? GroupNode
    ?: error("Can only insert children into GroupNode, but current is ${current::class.simpleName}")
```

The plan's `PhysicsShapeNode` extends `IsometricNode` directly, not `GroupNode`. This is correct for a leaf node -- it will be *inserted into* a `GroupNode` parent, not act as a parent itself. So this specific cast is fine for `PhysicsShapeNode` as a **child**.

However, `IsometricNode` declares `abstract val children: MutableList<IsometricNode>`. The plan's `PhysicsShapeNode` (section 4b.2) does **not** override this abstract property. The class will not compile. Every concrete `IsometricNode` subclass in the codebase (`ShapeNode`, `GroupNode`, `PathNode`, `BatchNode`) overrides `children` with `mutableListOf<IsometricNode>()`.

**Fix**: Add `override val children = mutableListOf<IsometricNode>()` to `PhysicsShapeNode`. The same applies to `PhysicsGroupNode`.

**Evidence**: `IsometricNode.kt` line 23: `abstract val children: MutableList<IsometricNode>`

### C2: `PhysicsShapeNode.render()` signature mismatch -- will not compile

**Severity**: Will not compile

The plan's `PhysicsShapeNode.render()` returns `List<RenderCommand>`:

```kotlin
fun render(context: RenderContext): List<RenderCommand>
```

But the actual `IsometricNode.render()` abstract method signature (line 101) is:

```kotlin
abstract fun render(context: RenderContext): List<RenderCommand>
```

This actually matches. However, the plan's `render()` method creates `Point` objects inline via `Point(vertexBuffer[idx - 3], vertexBuffer[idx - 2], vertexBuffer[idx - 1])`. The `Point` class is a `data class` -- this creates a new object per vertex per frame. For a 500-body scene with ~6 faces/body and ~4 vertices/face, that is 12,000 `Point` allocations per frame **just from the physics node rendering path alone**, defeating the stated goal of the pre-allocated `DoubleArray` buffer (FIX R2-I6). The buffer stores the values but then immediately allocates new `Point` objects to build the `Path` lists.

**The core issue**: The existing `Path` and `Shape` classes are immutable and store `List<Point>`. There is no way to avoid `Point` allocation when constructing `Path` objects for rendering. The `DoubleArray` vertex buffer provides zero benefit because the values must be copied into new `Point` objects anyway.

**Fix**: Either (a) accept the GC pressure and remove the misleading `DoubleArray` buffer, or (b) add a mutable `Path` variant that accepts `DoubleArray` directly, or (c) pool `Point` objects. Option (a) is simplest and most honest. At 500 bodies, the real bottleneck is the depth sort (acknowledged in the plan), not vertex allocation.

### C3: `syncPositionsToNodes` traversal only checks `GroupNode`, misses `PhysicsGroupNode`

**Severity**: Physics groups will not sync positions

Section 4b.4a defines:

```kotlin
private fun syncPositionsToNodes(node: IsometricNode, snapshot: Map<Int, PhysicsStep.BodySnapshot>) {
    if (node is PhysicsShapeNode) { ... }
    if (node is GroupNode) {
        for (child in node.children) {
            syncPositionsToNodes(child, snapshot)
        }
    }
}
```

If `PhysicsGroupNode` is not a `GroupNode` (which it likely is not, since it would need its own type), this traversal will stop at `PhysicsGroupNode` and never recurse into its children. Even if `PhysicsGroupNode` extends `GroupNode`, the plan does not specify this clearly.

More fundamentally, the traversal uses `node.children` (the mutable list) rather than `node.childrenSnapshot` (the thread-safe copy). Since this runs on the main thread during frame sync and the Applier may be mutating `children` during recomposition, this could see inconsistent state.

**Fix**: (a) Use `node.childrenSnapshot` for traversal, (b) make the traversal generic by recursing into all `IsometricNode` children regardless of type, not just `GroupNode`:

```kotlin
for (child in node.childrenSnapshot) {
    syncPositionsToNodes(child, snapshot)
}
```

### C4: `BodyPairKey.of()` packs IDs incorrectly -- high bits will collide

**Severity**: Incorrect collision detection for bodies with IDs >= 2^31

The plan defines:

```kotlin
fun of(idA: Int, idB: Int): Long {
    val lo = minOf(idA, idB).toLong()
    val hi = maxOf(idA, idB).toLong()
    return (lo shl 32) or (hi and 0xFFFFFFFFL)
}
```

The naming is backwards: `lo` (the smaller ID) goes into the **high** 32 bits, and `hi` (the larger ID) goes into the **low** 32 bits. This is semantically confusing but functionally correct *for positive IDs*, since `AtomicInteger` starts at 0 and increments. However, if the counter ever wraps to negative (after ~2.1 billion body creations -- unlikely but possible in a long-running session), `.toLong()` on a negative `Int` sign-extends, and the `and 0xFFFFFFFFL` mask is only applied to `hi`, not `lo`. A negative `lo` shifted left 32 bits will produce an incorrect key.

**Realistic impact**: Low for most applications. But the naming inversion (`lo` in high bits, `hi` in low bits) is a code maintenance trap. Rename the variables to `smallId` and `largeId`, or swap the packing order to match the names.

### C5: `PhysicsScene` tap overlay creates a second `Box` that covers the `IsometricScene`

**Severity**: Will intercept all touch events, preventing `IsometricScene` gesture handling

The plan's `PhysicsScene` (section 4b.4) creates an `IsometricScene` and then, if `onTap` is not null, creates a separate `Box` with `pointerInput`:

```kotlin
if (onTap != null) {
    Box(modifier = modifier.pointerInput(Unit) {
        detectTapGestures { offset -> ... }
    })
}
```

This `Box` uses the **same modifier** as the `IsometricScene`, so it occupies the same space. But it is a sibling composable, not overlaid properly. In Compose, two sibling composables with the same modifier will be laid out sequentially (vertically in a Column, etc.), not overlaid. To overlay, you need a `Box` wrapper around both.

Even if overlaid correctly, the `pointerInput` on this Box would consume tap events before `IsometricScene`'s own gesture handling (drag, etc.) can process them, since Compose dispatches pointer events to the topmost composable first.

**Fix**: Integrate physics tap handling into the `IsometricScene`'s `onTap` callback rather than creating a separate overlay, or use a single `Box` parent with proper z-ordering and event dispatching.

---

## IMPORTANT Issues

### I1: `Prism` constructor parameters are not `val` -- Phase 0 change is binary-incompatible and assumes no secondary constructors capture dimensions

**Impact**: Phase 0 will break the secondary constructor

The plan correctly notes that adding `val` is source-compatible but binary-incompatible. However, `Prism` has a secondary constructor:

```kotlin
constructor(origin: Point) : this(origin, 1.0, 1.0, 1.0)
```

This delegates to the primary constructor. After Phase 0, `Prism(somePoint)` will create a Prism with `dx=1.0, dy=1.0, dz=1.0` stored as fields. This is fine. But the `Cylinder` class has a secondary constructor with a different parameter order:

```kotlin
constructor(origin: Point, vertices: Int, height: Double) : this(origin, 1.0, vertices, height)
```

If `radius` becomes a `val`, this secondary constructor still works. However, since the primary constructor now has default values (`radius: Double = 1.0`), calling `Cylinder(origin, 20, 2.0)` would be ambiguous between the secondary constructor and the primary constructor with named/defaulted args. Kotlin resolves this in favor of the secondary constructor, so it should be fine, but this edge case should be tested explicitly.

### I2: `Octahedron` has no user-configurable dimensions, but the plan says "fixed geometry, scale = 1.0" -- this is wrong

**Impact**: Incorrect AABB and inertia for Octahedron

The actual `Octahedron` applies a scale of `sqrt(2)/2` to all paths (line 35-36 of `Octahedron.kt`):

```kotlin
val scale = sqrt(2.0) / 2.0
return paths.map { it.scale(center, scale, scale, 1.0) }
```

Note: the Z axis is **not** scaled (scale is `1.0` for Z, `sqrt(2)/2 ~= 0.707` for X and Y). The plan's `fromOctahedron(origin: Point)` AABB and `octahedronInertia(mass, 1.0)` both assume a unit octahedron. The actual octahedron is scaled non-uniformly: it is narrower in X and Y (by factor 0.707) but full size in Z. This means the AABB and inertia will be wrong.

The bounding box of the actual octahedron is approximately `[-0.354, 0.354]` in X and Y but `[0, 1]` in Z (relative to origin), not a unit cube.

**Fix**: The `fromOctahedron` AABB must account for the `sqrt(2)/2` XY scale and the 1.0 Z scale. The inertia calculation should use the actual vertex positions, not assume a regular unit octahedron.

### I3: `StairsCompoundCollider` decomposition geometry is wrong

**Impact**: Stairs collider will not match the visual shape

The plan decomposes stairs as:

```kotlin
val stepOrigin = Point(origin.x, origin.y, origin.z + i * (1.0 / stepCount))
val stepWidth = 1.0 / stepCount
CompoundCollider.ChildCollider(
    convex = PrismCollider(
        halfExtents = Vector(0.5, 0.5, stepWidth / 2),
        center = Point.ORIGIN
    ),
    localPosition = stepOrigin
)
```

But looking at the actual `Stairs.kt`, each step's geometry is:

```kotlin
val stepCorner = origin.translate(0.0, i / stepCount.toDouble(), (i + 1) / stepCount.toDouble())
```

The steps progress along the **Y axis** (depth), not the Z axis. Step `i` starts at `y = i/stepCount` and goes to `y = (i+1)/stepCount`. The Z coordinate increases as `z = (i+1)/stepCount`. So each step is a prism with:
- X extent: `1.0` (the full staircase width)
- Y extent: `1.0 / stepCount` (one step's depth)
- Z extent: the step height from its base to its top, which is `1.0 / stepCount`

The plan uses `halfExtents = Vector(0.5, 0.5, stepWidth / 2)`, giving each step a Y half-extent of 0.5 (full width of the staircase!), which is completely wrong. Each step should have `halfExtents = Vector(0.5, stepWidth / 2, stepWidth / 2)`.

Furthermore, the step origin progression in the plan uses Z only (`origin.z + i * (1.0 / stepCount)`), ignoring the Y progression entirely. In the actual stairs, each step moves in both Y and Z.

**Fix**: Match the actual geometry: each step at position `(origin.x, origin.y + i/n, origin.z + i/n)` with half-extents `(0.5, 1/(2n), 1/(2n))` where `n = stepCount`. However, this still doesn't capture the step's vertical riser face correctly -- each step is an L-shaped cross-section, not a simple prism. A more accurate decomposition would use two prisms per step (one for the tread, one for the riser), or accept that the prism approximation leaves small gaps.

### I4: Island `isSleeping` is a `data class` val, set at construction, but the plan sets it in `updateSleep` which runs AFTER `buildIslands`

**Impact**: Islands built by `buildIslands` will never have `isSleeping = true` on the first frame

The plan shows `Island` as:

```kotlin
data class Island(
    val bodies: List<RigidBody>,
    val manifolds: List<ContactManifold>,
    val isSleeping: Boolean = false
)
```

In the step function (section 3.2), `buildIslands` is called first (step 6), then `solver.solve` checks `island.isSleeping` (step 7), then `updateSleep` is called (step 10). Since `buildIslands` creates `Island` objects with `isSleeping = false` by default, the solver will **never** skip a sleeping island on the first frame after sleep should have triggered. Sleep only takes effect on the **next** frame when `buildIslands` might set the flag based on body state.

But wait -- `buildIslands` doesn't know about sleep state either, since it is a union-find on contacts/joints. The `isSleeping` flag in the `Island` data class is never actually set to `true` by any code shown in the plan. `updateSleep` modifies `body.isSleeping`, not `island.isSleeping`.

**Fix**: Either (a) have `buildIslands` check if all bodies in the island are sleeping and set `isSleeping` accordingly, or (b) change the solver loop to check individual body sleep states, or (c) make `Island.isSleeping` a computed property: `val isSleeping: Boolean get() = bodies.all { it.isSleeping || it.isStatic }`.

### I5: `PhysicsBodyRef` exposes `body.position` and `body.velocity` directly -- threading violation

**Impact**: Reads stale or torn values from physics thread

`PhysicsBodyRef` (section 4b.7) has:

```kotlin
val position: Point get() = body.position
val velocity: Vector get() = body.velocity
```

But `body.position` and `body.velocity` are mutated on the physics thread. The plan explicitly states that the main thread should only read from the interpolated snapshot published via `AtomicReference` (FIX R2-I2). Yet `PhysicsBodyRef` bypasses this entirely by reading directly from the `RigidBody` fields. Since `Point` is a `data class` (immutable), the read is at least atomic at the reference level (JVM guarantees reference writes are atomic), but the values will be from the physics thread's current state, not the interpolated snapshot. This means:

1. Position values will jitter (not interpolated)
2. Values may be mid-step (between force application and integration)
3. The entire snapshot-based position sync model (FIX R2-I2) is undermined

**Fix**: `PhysicsBodyRef` should read from the published snapshot, not from `RigidBody` directly. Alternatively, document that `PhysicsBodyRef.position` returns the "last physics step" position (not interpolated) and is only safe for non-rendering purposes like game logic checks.

### I6: `contactManager.update()` is called with `manifolds` from narrow phase, but those manifolds reference pooled `ContactManifold` objects that may be reused

**Impact**: Warm starting may read stale impulse values

The narrow phase creates manifolds (possibly from the `ContactPool`), which are then passed to `contactManager.update()`. The contact manager is supposed to copy accumulated impulses from cached manifolds into the new ones. But if the narrow phase uses the pool and the contact manager also holds references to pooled manifolds from the previous frame, then `release()` and `acquire()` cycles could cause the same `ContactManifold` object to appear in both the "new" and "cached" sets.

The plan does not specify when manifolds are released back to the pool. If they are released after `contactManager.update()` processes them, the timing is critical and error-prone.

**Fix**: Document the manifold lifecycle explicitly. The safest approach: narrow phase always acquires fresh manifolds, contact manager holds references to the "active" manifolds, and only releases them when they are pruned (no longer active). Never release a manifold that is still referenced by the contact manager.

### I7: Java toolchain mismatch between physics-core (Java 17) and isometric-compose (Java 11)

**Impact**: Potential build failure or runtime crash

The plan specifies `JavaLanguageVersion.of(17)` for `isometric-physics-core`, but the existing `isometric-compose` uses `jvmTarget = "11"`. Since `isometric-physics-compose` depends on both, and Android projects typically use Java 11 or 17 depending on minSdk, there could be a bytecode compatibility issue. Java 17 class files cannot be loaded by a Java 11 runtime.

The existing `isometric-core` uses `JavaLanguageVersion.of(17)` via `jvmToolchain`, but `isometric-compose` targets `jvmTarget = "11"`. This actually works in the current codebase because `isometric-core` is a JVM library consumed at compile time. But it suggests the project has not uniformly adopted Java 17.

**Fix**: Either align all modules to the same JVM target, or explicitly set `isometric-physics-core` to Java 11 to match the lowest consumer. On Android, Java 17 APIs are available from API 34+, but the `isometric-compose` targets `minSdk = 24`.

---

## SUGGESTIONS

### S1: `AABB` as a `data class` will cause GC pressure at 500 bodies

Every AABB computation (via `fromPrism`, `fromCylinder`, etc.) allocates a new `AABB` object. With 500 bodies updated every physics step, that is 500 AABB allocations per step (60 Hz = 30,000/sec). The `AABB` class should be mutable with an `update()` method, consistent with the plan's approach to `ContactManifold` and `ContactPoint`.

### S2: `data class AABB` and `data class Quaternion` will generate `equals`/`hashCode`/`toString`/`copy` methods

For math types used in hot loops (`AABB`, `Quaternion`, `Matrix3x3`), `data class` adds unnecessary overhead. The generated `equals()` compares all fields with boxing for `Double` values. Consider using regular classes with manual `equals`/`hashCode` if needed, or accept the overhead.

### S3: `slerp` does not normalize the result in the non-linear branch

The plan's `slerp` implementation (section 1.1) normalizes in the linear fallback branch (`dot > 0.9995`) but not in the trigonometric branch. While mathematically the slerp formula preserves unit length, floating-point drift can accumulate over many interpolations. Adding `.normalized()` to the result of the trigonometric branch is cheap insurance.

### S4: `PhysicsVector.kt` extension properties on `Vector.Companion` may not compile

The plan defines:

```kotlin
val Vector.Companion.ZERO: Vector
val Vector.Companion.GRAVITY: Vector
```

In Kotlin, you can define extension properties on companion objects. However, `Vector` already has a `companion object` (it contains `fromTwoPoints`, `crossProduct`, `dotProduct`), so this will compile. But extension properties cannot have backing fields -- they must have custom getters. Every access to `Vector.ZERO` will allocate a new `Vector(0.0, 0.0, 0.0)` unless the getter returns a cached constant. The plan does not show the getter implementation.

**Fix**: Define `ZERO` and `GRAVITY` as `val` constants inside the existing `Vector.Companion`, not as extension properties. This requires modifying `isometric-core`, which is already being modified in Phase 0.

### S5: `RigidBody.isStatic` uses `==` but `isKinematic` uses `is` -- inconsistent

```kotlin
val isStatic: Boolean get() = config.type == BodyType.Static
val isDynamic: Boolean get() = config.type is BodyType.Dynamic
```

`BodyType.Static` is an `object`, so `==` and `is` are equivalent. But `isStatic` using `==` while `isDynamic` uses `is` is confusing, especially since the plan documents "always use `is` checks" (FIX R2-I4). Use `is` consistently.

### S6: `ContactPoint.reset()` does not reset `position`, `penetration`, or `localPointA`/`localPointB`

The plan's `ContactPoint.reset()`:

```kotlin
fun reset() { normalImpulse = 0.0; tangentImpulse1 = 0.0; tangentImpulse2 = 0.0 }
```

This only resets impulse accumulators but leaves stale geometric data. If a pooled `ContactPoint` is reused for a different collision pair, the stale `position`, `penetration`, and `localPoint` values from the previous frame will be visible until overwritten. If any code path reads these before the narrow phase overwrites them, it will produce incorrect physics.

### S7: The plan does not specify how `PhysicsShapeNode` produces correct `RenderCommand` IDs for hit testing

The existing `ShapeNode.render()` generates IDs as `"${nodeId}_${path.hashCode()}"`. The plan's `PhysicsShapeNode` has a `renderShape` helper but does not show its implementation. For hit testing and the spatial index to work correctly, `RenderCommand` IDs must be unique and stable. Since the physics transform changes every frame, using `path.hashCode()` (which depends on vertex positions) will produce different IDs every frame, defeating cache-based optimizations.

### S8: Consider whether `scheduleAtFixedRate` is appropriate for physics stepping

`scheduleAtFixedRate` tries to maintain a fixed rate by compensating for execution time. If a physics step takes longer than 16ms, the executor will immediately schedule the next step with no delay, potentially causing back-pressure. `scheduleWithFixedDelay` might be more appropriate to ensure a minimum 16ms gap between step completions, preventing runaway simulation when the physics is overloaded.

### S9: `PhysicsScene` uses `LaunchedEffect` with `withFrameNanos` for position sync, but the physics thread independently runs at ~60Hz

There is no synchronization between the physics thread's `scheduleAtFixedRate(16ms)` and the Compose frame clock's `withFrameNanos`. This means:
- The physics thread may run 0, 1, or 2 steps between Compose frames
- The interpolation alpha computed by the physics thread corresponds to the physics thread's timing, not the Compose frame timing
- The snapshot read by `withFrameNanos` may be stale by up to 16ms

This is acceptable for most games, but it means the interpolation is not frame-perfect. For truly smooth rendering, the physics step should be driven by the render frame's delta time, not by an independent timer. The `AndroidPhysicsThread` wrapping the JVM `PhysicsThread` suggests Choreographer integration, but the implementation just delegates to the JVM timer. This defeats the purpose of Choreographer.

---

## Priority-Ordered Fix Table

| Priority | Issue | Type | Effort | Phase |
|----------|-------|------|--------|-------|
| 1 | **C1**: `PhysicsShapeNode` missing `override val children` | CRITICAL | Trivial | 4b |
| 2 | **C3**: `syncPositionsToNodes` skips non-GroupNode containers, uses mutable children | CRITICAL | Low | 4b |
| 3 | **C5**: Tap overlay `Box` layout and event interception | CRITICAL | Medium | 4b |
| 4 | **I3**: `StairsCompoundCollider` geometry wrong (Y vs Z, half-extents) | IMPORTANT | Medium | 2 |
| 5 | **I4**: `Island.isSleeping` never set to true | IMPORTANT | Low | 3 |
| 6 | **I5**: `PhysicsBodyRef` reads directly from RigidBody, bypassing snapshot | IMPORTANT | Medium | 4b |
| 7 | **C2**: `DoubleArray` vertex buffer provides no GC benefit (Points still allocated) | CRITICAL (misleading) | Low | 4b |
| 8 | **I2**: Octahedron non-uniform scale not accounted for in AABB/inertia | IMPORTANT | Low | 1 |
| 9 | **I7**: Java toolchain version mismatch (17 vs 11) | IMPORTANT | Trivial | 1 |
| 10 | **I6**: Manifold pool lifecycle undefined | IMPORTANT | Medium | 2 |
| 11 | **S4**: `Vector.ZERO`/`GRAVITY` as extension properties allocate per-access | SUGGESTION | Trivial | 0/1 |
| 12 | **C4**: `BodyPairKey` variable naming inversion | CRITICAL (maintenance) | Trivial | 2 |
| 13 | **S9**: Physics thread timing independent of render frame | SUGGESTION | High | 4b |
| 14 | **S1**: `AABB` data class GC pressure | SUGGESTION | Low | 1 |
| 15 | **S8**: `scheduleAtFixedRate` vs `scheduleWithFixedDelay` | SUGGESTION | Trivial | 1 |

---

## Overall Assessment

The plan is well-structured and the phased approach is sound. The R2 revision has fixed the most obvious architectural issues from earlier reviews. However, the Compose integration layer (Phase 4b) has several issues that would cause compilation failures or runtime crashes if implemented as written. These all stem from the plan's code snippets not being verified against the actual codebase's class hierarchy and Compose Applier constraints.

The physics algorithms (GJK, EPA, sequential impulse solver, island-based sleep) are described at the right level of detail and the approaches are well-established. The main algorithmic concern is the Stairs compound collider decomposition (I3), which does not match the actual geometry.

**Recommendation**: Fix C1, C3, and C5 before implementation begins, as they affect the core Compose integration architecture. I3 and I4 can be fixed during their respective phases. The `DoubleArray` vertex buffer (C2) should be removed or replaced with a genuinely useful optimization -- as currently described, it is misleading complexity that provides no benefit.

The plan's performance targets are realistic for the stated 500-body target on modern Android hardware, assuming the acknowledged depth-sort bottleneck is addressed via `enableBroadPhaseSort`. The 8ms budget for 500 bodies at 10 solver iterations is achievable with a well-implemented sequential impulse solver on ARM64.
