# Physics Implementation Plan -- Review Round 7

> **Date**: 2026-03-13
> **Reviewer**: Architecture review agent
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md)
> **Status**: Issues identified -- plan revision required

---

## Review Summary

The R6 revision addresses 95 issues across six prior review rounds, and the plan is mature. The core physics architecture (math, collision, solver, threading, island-based sleep) is sound, and the Compose integration layer has improved substantially. However, careful cross-referencing of the plan's code against both the actual codebase and the plan's own Phase 0 specification reveals **1 critical issue** that will cause a compilation failure in multiple shape types, **3 important issues** involving type mismatches and incorrect API references, and **3 suggestions**. The critical issue is an internal contradiction within the plan itself: Phase 0 explicitly declares `origin` as a bare parameter (not stored), but `RigidBody.create()` accesses `shape.origin` on every shape type.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. `RigidBody.create()` accesses `shape.origin` which does not exist -- contradicts Phase 0 specification

The plan's `RigidBody.create()` factory method (section 1.2, lines 675-706) computes the centroid for each shape type using `shape.origin`:

```kotlin
is Prism -> Point(
    shape.origin.x + shape.dx / 2,
    shape.origin.y + shape.dy / 2,
    shape.origin.z + shape.dz / 2
)
is Cylinder -> Point(
    shape.origin.x,
    shape.origin.y,
    shape.origin.z + shape.height / 2
)
// ... same pattern for Stairs, Knot, Pyramid, Octahedron
```

However, the plan's own Phase 0 specification (section 0.1, lines 333-342) explicitly states that `origin` is a **bare constructor parameter** -- NOT stored as a field:

```kotlin
class Prism(
    origin: Point,           // bare parameter -- consumed by createPaths only
    val dx: Double = 1.0,   // val -- stored as field
    ...
```

Line 333 says: "Only **dimension parameters** are promoted to `val` fields. `origin` is intentionally NOT stored -- physics tracks position separately via `RigidBody.position`."

This is a direct internal contradiction. After Phase 0 is applied, `shape.origin` will not compile for any shape type because `origin` is not a `val` -- it is consumed by `createPaths()` / `Shape.extrude()` and then discarded. The compiler will report "Unresolved reference: origin" on every `shape.origin` access.

This affects all six centroid computations in `RigidBody.create()` (Prism, Cylinder, Stairs, Knot, Pyramid, Octahedron) -- the factory method is completely broken.

**Evidence**: Plan line 338 (`origin: Point, // bare parameter`), plan lines 677-704 (`shape.origin.x` used 18 times). Actual `Prism.kt` line 11 confirms `origin` is not `val`.

**Fix**: The centroid computation cannot use `shape.origin`. Two options:

**(a) Preferred -- use the vertex fallback for all types.** Since the shape's paths already embed the origin in their vertices (e.g., `Prism.createPaths(origin, dx, dy, dz)` produces vertices at `origin + offsets`), compute the centroid from shape dimensions alone, using knowledge of the shape's construction geometry relative to its vertex positions:

```kotlin
is Prism -> {
    val allPoints = shape.paths.flatMap { it.points }
    val minX = allPoints.minOf { it.x }
    val minY = allPoints.minOf { it.y }
    val minZ = allPoints.minOf { it.z }
    Point(minX + shape.dx / 2, minY + shape.dy / 2, minZ + shape.dz / 2)
}
```

Or, more simply, use `AABB.fromShape(shape).center()` which already exists in the plan (line 484) and correctly derives the bounding box from path vertices.

**(b) Alternative -- store `origin` as `val`.** But this directly contradicts the plan's design decision (R2-C1) and the reasoning at line 333 that storing origin creates a stale value. This option is not recommended.

Option (a) is consistent with the plan's fallback path (lines 707-716) which already computes centroid from vertices. The analytic per-shape centroids can use `AABB.fromShape()` or simply the vertex-average fallback for all types, since the only reason the per-type dispatch exists (R6-S1 fix) is to avoid vertex duplication bias -- but `AABB.fromShape()` dispatches to analytic functions (line 484-491) that do not suffer from duplication bias.

---

## IMPORTANT Issues (Should Fix)

### I1. `CollisionEvent` properties are `self`/`other` but collision listener uses `event.bodyA`/`event.bodyB`

The plan's `CollisionEvent` definition (section 4a.1, lines 1802-1811) declares:

```kotlin
sealed class CollisionEvent {
    abstract val self: RigidBody
    abstract val other: RigidBody
    ...
}
```

But the collision listener in `PhysicsShape` (section 4b.5d, line 2448) accesses:

```kotlin
if (event.bodyA.id == rb.id || event.bodyB.id == rb.id) {
    onCollision(event)
}
```

`bodyA` and `bodyB` do not exist on `CollisionEvent`. The properties are `self` and `other`. This will not compile.

**Evidence**: Plan line 1803-1804 (`self`/`other`), plan line 2448 (`event.bodyA.id`/`event.bodyB.id`).

**Fix**: Change line 2448 to:

```kotlin
if (event.self.id == rb.id || event.other.id == rb.id) {
    onCollision(event)
}
```

### I2. `ColliderFactory.fromShape()` passes `List<Point>` to `PyramidCollider`/`OctahedronCollider` constructors that expect `Array<Point>`

The `ColliderFactory` (section 1.2, lines 795-803) creates colliders for Pyramid and Octahedron:

```kotlin
is Pyramid -> PyramidCollider(
    vertices = baseShape.paths.flatMap { it.points }.distinct(),
    center = Point.ORIGIN
)
is Octahedron -> OctahedronCollider(
    vertices = baseShape.paths.flatMap { it.points }.distinct(),
    center = Point.ORIGIN
)
```

The `.distinct()` call returns `List<Point>`. However, both `PyramidCollider` and `OctahedronCollider` are defined (section 2.2, lines 1286-1287) with `Array<Point>` constructors:

```kotlin
class PyramidCollider(val vertices: Array<Point>) : ConvexCollider
class OctahedronCollider(val vertices: Array<Point>) : ConvexCollider
```

Kotlin does not implicitly convert `List<T>` to `Array<T>`. This is a type mismatch that will not compile.

Additionally, neither `PyramidCollider` nor `OctahedronCollider` constructors include a `center` parameter, but the `ColliderFactory` passes `center = Point.ORIGIN` as a named argument.

**Evidence**: Plan lines 796-803 (passes `List` + `center`), lines 1286-1287 (expects `Array`, no `center` param).

**Fix**: Either:
- (a) Change the collider constructors to accept `List<Point>` and add `center: Point`, or
- (b) Add `.toTypedArray()` in the factory and remove the `center` parameter:

```kotlin
is Pyramid -> PyramidCollider(
    vertices = baseShape.paths.flatMap { it.points }.distinct().toTypedArray()
)
```

### I3. `ColliderShape.Sphere.toCollider()` creates an `OctahedronCollider` with an undefined constructor

The `ColliderShape` sealed interface (section 4b.5c, line 2383) converts `Sphere` to a collider:

```kotlin
is Sphere -> OctahedronCollider(/* vertex enumeration from radius */)
```

This is a placeholder comment, not code. When implemented, it would need to generate 6 vertices from the radius. But more fundamentally, a sphere should not map to an octahedron collider -- an octahedron is a poor approximation of a sphere. A sphere collider should be its own type with an analytic support function (`support(dir) = center + dir.normalized() * radius`), which is both simpler and more accurate than vertex enumeration.

The `ColliderShape.Cylinder.toCollider()` also has the same API mismatch: it passes `center = Point.ORIGIN` to `CylinderCollider`, which is consistent with the constructor definition at line 1285 (which does include `center`). So Cylinder is fine -- this issue is specific to Sphere.

**Evidence**: Plan line 2383 (placeholder comment), plan line 1287 (`OctahedronCollider` constructor).

**Fix**: Either:
- (a) Add a `SphereCollider` class that implements `ConvexCollider` with an analytic support function, or
- (b) Define the vertex enumeration properly and fix the constructor call

---

## SUGGESTIONS (Nice to Have)

### S1. `PhysicsShapeNode.renderShape()` comment mischaracterizes `RenderContext` as doing isometric projection

The plan's `PhysicsShapeNode.renderShape()` comment (section 4b.2, lines 2056-2058) says:

```
// NOTE: Uses context only for isometric projection (2D screen mapping via
// IsometricEngine), NOT for transform accumulation.
```

This is misleading. `RenderContext` does NOT perform isometric projection. The isometric projection (3D-to-2D mapping) is performed by `IsometricEngine.prepare()` -> `translatePoint()`, which is called from `IsometricRenderer.rebuildCache()`. `RenderContext` only performs 3D transform accumulation (position/rotation/scale).

`PhysicsShapeNode.render()` returns `RenderCommand` objects with 3D paths in `originalPath` and `points = emptyList()`. The renderer passes these to the engine which fills in the 2D points. The `RenderContext` is not used for projection at all in the physics path.

**Evidence**: `IsometricRenderer.kt` lines 376-402 (renders via `engine.clear()` + `engine.add()` + `engine.prepare()`). `RenderContext.kt` lines 84-111 (`applyTransformsToShape` does 3D transforms only). `IsometricEngine.kt` lines 206-211 (`translatePoint` does isometric projection).

**Fix**: Correct the comment to:

```
// NOTE: RenderContext is NOT used here -- physics shapes are already in world-space.
// The IsometricEngine handles 3D-to-2D projection when the renderer calls engine.prepare().
```

### S2. `PhysicsScene` creates `AndroidPhysicsThread` unconditionally -- blocks JVM/desktop usage of compose module

The `PhysicsScene` composable (section 4b.4, line 2165) always creates an `AndroidPhysicsThread`:

```kotlin
val physicsThread = remember { AndroidPhysicsThread(world, step) }
```

`AndroidPhysicsThread` depends on `android.os.HandlerThread` and `android.view.Choreographer`, which are Android-only classes. If the `isometric-physics-compose` module is ever used in a JVM desktop Compose application (Compose Desktop / Compose Multiplatform), this will throw `NoClassDefFoundError` at runtime.

The plan already has `PhysicsThread` (JVM-based, section 1.4) as an alternative. A factory pattern or `expect`/`actual` mechanism could select the appropriate implementation.

**Evidence**: Plan line 2165 (`AndroidPhysicsThread`), plan lines 2082-2121 (uses `android.os.HandlerThread` and `android.view.Choreographer`).

**Fix**: Extract the thread creation into a factory, or document that `isometric-physics-compose` is Android-only. Given that the plan's module structure already labels it as "Android library" (`com.android.library` plugin, line 1960), this is a documentation concern rather than a design flaw. Add a comment noting the Android-only restriction.

### S3. `updateSleep` logic allows bodies with `allowSleep = false` to have their sleep timer incremented

In the `IslandManager.updateSleep()` method (section 3.3, lines 1708-1713), the `allBelowThreshold` check treats bodies with `!body.config.allowSleep` as passing the threshold test:

```kotlin
val allBelowThreshold = island.bodies.all { body ->
    body.isStatic || !body.config.allowSleep ||
    (body.velocity.magnitudeSquared() < ...)
}
```

This means bodies with `allowSleep = false` contribute to the island being considered "below threshold." Then in the sleep application loop (lines 1716-1721):

```kotlin
for (body in island.bodies) {
    if (body.isStatic) continue
    body.sleepTimer += dt
    if (body.sleepTimer >= config.sleepTimeThreshold) {
        body.isSleeping = true
```

There is no check for `body.config.allowSleep` before setting `body.isSleeping = true`. A body with `allowSleep = false` will still have its sleep timer incremented and will eventually be put to sleep, violating the `allowSleep = false` contract.

**Evidence**: Plan lines 1708-1721 -- the sleep timer increment and `isSleeping = true` assignment happen unconditionally for non-static bodies.

**Fix**: Add an `allowSleep` check before applying sleep:

```kotlin
for (body in island.bodies) {
    if (body.isStatic || !body.config.allowSleep) continue
    body.sleepTimer += dt
    if (body.sleepTimer >= config.sleepTimeThreshold) {
        body.isSleeping = true
        body.velocity = Vector.ZERO
        body.angularVelocity = Vector.ZERO
    }
}
```

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **C1**: `shape.origin` access does not exist -- internal contradiction with Phase 0 | Medium | `RigidBody.create()` will not compile for any shape type |
| 2 | **I1**: `event.bodyA`/`event.bodyB` should be `event.self`/`event.other` | Trivial | Collision listener in `PhysicsShape` will not compile |
| 3 | **I2**: `List<Point>` passed to `Array<Point>` constructors + spurious `center` param | Trivial | `ColliderFactory` will not compile for Pyramid/Octahedron |
| 4 | **I3**: `ColliderShape.Sphere.toCollider()` is a placeholder -- no sphere collider type | Low | `ColliderShape.Sphere` is unusable |
| 5 | **S3**: `allowSleep = false` bodies can still be put to sleep | Trivial | Violates API contract |
| 6 | **S1**: `renderShape()` comment mischaracterizes `RenderContext` role | Trivial | Documentation accuracy |
| 7 | **S2**: `AndroidPhysicsThread` hardcoded -- blocks non-Android Compose usage | Low | Platform portability |

---

## Overall Assessment

The plan is in strong shape after six rounds of revision. The 95 previously identified fixes have been incorporated cleanly, and the core physics architecture is sound.

The most significant finding this round is C1 -- a self-contradiction within the plan where Phase 0 explicitly removes `origin` as a stored field but `RigidBody.create()` reads `shape.origin` on every shape type. This is a compile-time failure that affects the central body creation factory, so it must be resolved before Phase 1 can be implemented. The fix is straightforward: derive centroids from vertex geometry (via `AABB.fromShape().center()`) rather than from a non-existent `origin` field.

The remaining issues (I1-I3) are type mismatches and placeholder code that would be caught at compile time but should be fixed in the plan to avoid confusion during implementation. The suggestions (S1-S3) are minor correctness and documentation improvements.

**Phases 0-4a** (pure physics core) are ready for implementation once C1 is resolved. **Phase 4b** needs C1, I1, and I2 resolved before implementation. The overall architecture -- module split, threading model, snapshot-based position sync, compound colliders, island-based sleep -- remains sound.
