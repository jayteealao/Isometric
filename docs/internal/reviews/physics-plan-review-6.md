# Physics Implementation Plan -- Review Round 6

> **Date**: 2026-03-13
> **Reviewer**: Architecture review agent
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md)
> **Status**: Issues identified -- plan revision required

---

## Review Summary

The plan has matured substantially through five rounds of review. The R5 revision addresses 83 issues across all previous rounds, and the core physics architecture (math primitives, rigid body model, collision pipeline, solver, island-based sleep, threading model) is solid. The fix tracking table is thorough and every previous finding has a documented resolution.

However, this round identifies **2 critical issues**, **3 important issues**, and **3 suggestions** that are genuinely new -- not repeats of previous findings. The most severe is that `PhysicsShape` never removes its `RigidBody` from the `PhysicsWorld` when the composable leaves composition, causing an unbounded body leak. The second critical issue is that `LocalPhysicsSnapshot` is used but never defined or provided.

The Compose integration layer (Phase 4b) continues to be the area with the most issues, consistent with the pattern across all review rounds.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. `PhysicsShape` never removes its `RigidBody` from the world -- body leak on recomposition

The `PhysicsShape` composable (section 4b.5d, line ~2274) creates a `RigidBody` inside the `ComposeNode` factory lambda and adds it to the world:

```kotlin
ComposeNode<PhysicsShapeNode, IsometricApplier>(
    factory = {
        val (rb, centeredShape) = RigidBody.create(shape, body)
        world.addBody(rb)
        PhysicsShapeNode(centeredShape, color, rb.id)
    },
    update = {
        set(color) { this.color = it; markDirty() }
        set(visible) { this.isVisible = it; markDirty() }
    }
)
```

When the `PhysicsShape` composable leaves the composition (e.g., conditional rendering, list item removal, navigation), the Compose runtime removes the `PhysicsShapeNode` from the tree via `IsometricApplier.remove()`. However, the `RigidBody` remains in the `PhysicsWorld.bodiesById` and `bodiesList` forever. There is no `DisposableEffect` or other cleanup mechanism to call `world.removeBody(rb)`.

Compare with `rememberPhysicsBody` (line ~2389), which correctly uses `DisposableEffect(Unit) { onDispose { world.removeBody(bodyRef.body) } }`.

With conditional rendering or list-based `ForEach` that adds/removes `PhysicsShape` composables, each removal leaks a body. The leaked bodies continue to participate in collision detection, broadphase, island building, and solver iterations -- accumulating phantom objects that waste CPU and produce incorrect physics behavior (invisible collisions).

**Evidence**: Plan section 4b.5d lines ~2263-2294: no `DisposableEffect` with `removeBody`. Contrast with `rememberPhysicsBody` at line ~2389 which has correct cleanup.

**Fix**: Add body cleanup. The challenge is that `ComposeNode` does not support `DisposableEffect` inside it. The body reference must be captured and cleaned up separately:

```kotlin
@Composable
fun PhysicsScope.PhysicsShape(
    shape: Shape,
    color: IsoColor = LocalDefaultColor.current,
    body: BodyConfig = BodyConfig(),
    onCollision: ((CollisionEvent) -> Unit)? = null,
    visible: Boolean = true
) {
    val world = LocalPhysicsWorld.current

    // Create body once, remembered across recompositions
    val (rb, centeredShape) = remember(shape, body) {
        RigidBody.create(shape, body).also { (b, _) -> world.addBody(b) }
    }

    // Remove body when composable leaves composition
    DisposableEffect(rb) {
        onDispose { world.removeBody(rb) }
    }

    ComposeNode<PhysicsShapeNode, IsometricApplier>(
        factory = { PhysicsShapeNode(centeredShape, color, rb.id) },
        update = {
            set(color) { this.color = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
        }
    )

    // ...collision callback...
}
```

### C2. `LocalPhysicsSnapshot` used in `rememberPhysicsBody` but never defined or provided

The `rememberPhysicsBody` composable (section 4b.7, line ~2383) reads:

```kotlin
val snapshotProvider = LocalPhysicsSnapshot.current  // provides latest snapshot (FIX R3-I5)
```

But `LocalPhysicsSnapshot` is never defined anywhere in the plan. There is no `compositionLocalOf` or `staticCompositionLocalOf` declaration for it, and `PhysicsScene` never provides it via `CompositionLocalProvider`. The only CompositionLocal provided by `PhysicsScene` is `LocalPhysicsWorld` (line ~2067).

Without this, `rememberPhysicsBody` will not compile because `LocalPhysicsSnapshot` is an undefined symbol.

**Evidence**: `LocalPhysicsSnapshot` appears exactly once in the plan (line 2383). No definition exists.

**Fix**: Define `LocalPhysicsSnapshot` and provide it from `PhysicsScene`:

```kotlin
// In CompositionLocals.kt (physics-compose)
val LocalPhysicsSnapshot = staticCompositionLocalOf<() -> Map<Int, PhysicsStep.BodySnapshot>> {
    { emptyMap() }  // default: no snapshot available
}

// In PhysicsScene, add to CompositionLocalProvider:
CompositionLocalProvider(
    LocalPhysicsWorld provides world,
    LocalPhysicsSnapshot provides { physicsThread.readSnapshot() }
) {
    IsometricScene(...)
}
```

Note: Using `staticCompositionLocalOf` is appropriate because the snapshot provider function reference does not change, even though the snapshot data it returns does.

---

## IMPORTANT Issues (Should Fix)

### I1. `ColliderFactory` referenced by `RigidBody.create()` but never defined

`RigidBody.create()` (line ~682) calls `ColliderFactory.fromShape(baseShape, shape)`:

```kotlin
body.collider = config.colliderOverride?.toCollider()
    ?: ColliderFactory.fromShape(baseShape, shape)
```

`ColliderFactory` is listed in the module structure (line ~184) as `ColliderFactory.kt` but is never defined with code anywhere in the plan. It needs to:
1. Dispatch on the **original** `shape` type (`is Prism`, `is Cylinder`, etc.) since `baseShape` is a plain `Shape` (returned by `Shape.translate()`, which loses the subclass type)
2. Use the dimensions from the original typed shape (e.g., `shape.dx`, `shape.dy`, `shape.dz` for Prism)
3. Create colliders positioned at origin (since `baseShape` is origin-centered)

This is distinct from the R5-I1 finding (which was about `RigidBody.create()` itself). `RigidBody.create()` is now defined, but it delegates to a `ColliderFactory` that is not.

**Evidence**: Plan line ~682 references `ColliderFactory.fromShape()`. Module structure line ~184 lists `ColliderFactory.kt`. No implementation exists.

**Fix**: Define `ColliderFactory`:

```kotlin
object ColliderFactory {
    /**
     * Derive a collider from a shape.
     * @param baseShape Origin-centered shape (for geometry extraction in fallback)
     * @param originalShape Original typed shape (for type dispatch and dimension access)
     */
    fun fromShape(baseShape: Shape, originalShape: Shape): Collider = when (originalShape) {
        is Prism -> PrismCollider(
            halfExtents = Vector(originalShape.dx / 2, originalShape.dy / 2, originalShape.dz / 2),
            center = Point.ORIGIN
        )
        is Cylinder -> CylinderCollider(
            radius = originalShape.radius,
            halfHeight = originalShape.height / 2,
            center = Point.ORIGIN
        )
        is Pyramid -> PyramidCollider(/* vertex enumeration from baseShape.paths */)
        is Octahedron -> OctahedronCollider(/* vertex enumeration from baseShape.paths */)
        is Stairs -> StairsCompoundCollider.create(Point.ORIGIN, originalShape.stepCount)
        is Knot -> KnotCompoundCollider.create(Point.ORIGIN)
        else -> {
            // Fallback: AABB-based box collider from actual vertices
            val aabb = AABB.fromPoints(baseShape.paths.flatMap { it.points })
            PrismCollider(
                halfExtents = Vector(
                    (aabb.maxX - aabb.minX) / 2,
                    (aabb.maxY - aabb.minY) / 2,
                    (aabb.maxZ - aabb.minZ) / 2
                ),
                center = Point.ORIGIN
            )
        }
    }
}
```

### I2. `BodyConfigBuilder` drops `userData` -- field unreachable from DSL

`BodyConfig` has a `userData: Any? = null` property (line ~730). However, `BodyConfigBuilder` (section 4b.6) has no `userData` property, and `BodyConfigBuilder.build()` (line ~2359-2371) does not pass `userData` to the `BodyConfig` constructor:

```kotlin
internal fun build(): BodyConfig = BodyConfig(
    type = type,
    material = material,
    colliderOverride = _colliderOverride,
    collisionFilter = _filter,
    gravityScale = gravityScale,
    linearDamping = linearDamping,
    angularDamping = angularDamping,
    isBullet = isBullet,
    allowSleep = allowSleep,
    tag = tag
    // userData is missing
)
```

Users who want to attach custom data to a body via the DSL cannot do so. The `userData` field was presumably added to `BodyConfig` for user convenience, but the builder silently ignores it.

**Evidence**: `BodyConfig` definition at line ~719 includes `userData: Any? = null`. `BodyConfigBuilder.build()` at lines ~2359-2371 omits it.

**Fix**: Add `var userData: Any? = null` to `BodyConfigBuilder` and include `userData = userData` in the `build()` call.

### I3. `PhysicsScene` passes `/* engine ref */` placeholder in `screenToWorldRay` -- no actual engine access

The `PhysicsScene` tap handler (line ~2083) calls:

```kotlin
val ray = PhysicsRaycastUtils.screenToWorldRay(x, y, /* engine ref */)
```

The `/* engine ref */` comment is a placeholder -- no actual `IsometricEngine` reference is available at that point. The `IsometricEngine` is created inside `IsometricScene` as `val engine = remember { IsometricEngine() }` (line 100 of `IsometricScene.kt`), which is a private local variable inaccessible from `PhysicsScene`.

The plan already added `onRootNodeReady` to expose the root node. A similar mechanism would be needed for the engine, or the raycast utility would need to operate differently (e.g., using the same isometric projection constants that the engine uses, without requiring the engine instance).

**Evidence**: Plan line ~2083 has a `/* engine ref */` placeholder comment. `IsometricEngine` is private inside `IsometricScene` (line 100 of `IsometricScene.kt`).

**Fix**: Either:
- (a) Add an `onEngineReady` callback to `IsometricScene` (following the `onRootNodeReady` / `onHitTestReady` pattern), or
- (b) Have `PhysicsRaycastUtils.screenToWorldRay()` take the isometric projection parameters (angle, scale) directly instead of an engine reference, or
- (c) Use the `onHitTestReady` callback (which already exists) to capture a hit-test function, and combine it with a physics-level raycast

---

## SUGGESTIONS (Nice to Have)

### S1. `Octahedron` vertex centroid is not at the geometric center -- origin-centering in `RigidBody.create()` may shift unexpectedly

`RigidBody.create()` computes the centroid from all path vertices:

```kotlin
val allPoints = shape.paths.flatMap { it.points }
val centroid = Point(
    allPoints.sumOf { it.x } / allPoints.size,
    allPoints.sumOf { it.y } / allPoints.size,
    allPoints.sumOf { it.z } / allPoints.size
)
```

For `Octahedron`, the paths are created by rotating two triangles 4 times, then scaling by `sqrt(2)/2` in XY. Each triangular face has 3 vertices, and there are 8 faces = 24 points total. However, many of these points are duplicates (the Octahedron has only 6 unique vertices). The centroid computed by averaging all 24 points with duplicates gives the same result as averaging the 6 unique vertices ONLY IF the duplicates are uniformly distributed. For a regular octahedron centered at `origin.translate(0.5, 0.5, 0.5)`, the averaging works out correctly because of rotational symmetry.

However, for shapes where path vertices are NOT uniformly duplicated (e.g., Stairs, which has a zigzag path with many more vertices on one side), the centroid computation could be biased. Stairs has `2 * stepCount` individual face paths plus 2 zigzag side paths. The zigzag paths contribute `2 * (2 * stepCount + 2)` points, while the step faces contribute `4 * 2 * stepCount` points. This makes the centroid slightly biased toward the step faces rather than being at the true geometric center `(0.5, 0.5, 0.5)`.

**Evidence**: `Stairs.kt` zigzag paths have variable point count. `RigidBody.create()` averages all vertices including duplicates.

**Fix**: For known shape types, use analytic centroids instead of vertex averaging:

```kotlin
val centroid = when (shape) {
    is Prism -> Point(origin.x + dx/2, origin.y + dy/2, origin.z + dz/2)
    is Stairs -> Point(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)
    // ...
    else -> /* vertex average fallback */
}
```

### S2. `PhysicsGroupNode.bodyConfig` is stored but never used

`PhysicsGroupNode` (section 4b.5a, line ~2193-2198) stores a `bodyConfig: BodyConfig` property:

```kotlin
class PhysicsGroupNode(
    val bodyConfig: BodyConfig
) : GroupNode()
```

But no code in the plan ever reads `PhysicsGroupNode.bodyConfig`. The `PhysicsGroup` composable creates the node with `PhysicsGroupNode(body)` but never creates a `RigidBody` from it or uses it for any physics computation. The node functions purely as a GroupNode for the Compose applier.

If the intent is for `PhysicsGroup` to represent a compound body, the plan should define how the group's children are merged into a single compound collider and how a single `RigidBody` is created for the group. Currently, each child `PhysicsShape` creates its own independent body.

**Evidence**: `PhysicsGroupNode.bodyConfig` at line ~2194. No code reads it.

**Fix**: Either:
- (a) Remove `bodyConfig` from `PhysicsGroupNode` if groups are purely organizational, or
- (b) Add code to create a compound `RigidBody` from the group's children, using `bodyConfig` for the compound body's properties

### S3. `PhysicsScene` `latestSnapshot` state is written but never read for rendering

`PhysicsScene` (line ~2036) declares:

```kotlin
val latestSnapshot = remember { mutableStateOf<Map<Int, PhysicsStep.BodySnapshot>>(emptyMap()) }
```

And in the `LaunchedEffect` (line ~2056):

```kotlin
latestSnapshot.value = snapshot
```

But `latestSnapshot` is never read outside the `LaunchedEffect`. The sync function `syncPositionsToNodes` receives the snapshot directly from `physicsThread.readSnapshot()`. The `latestSnapshot` state variable appears to be vestigial from an earlier design iteration where it was used to provide the snapshot to composables (e.g., as a `LocalPhysicsSnapshot` provider). Since `LocalPhysicsSnapshot` is never defined or provided (see C2), and the sync uses the direct read, this state serves no purpose and adds a wasted state write per frame.

**Evidence**: `latestSnapshot` assigned at line ~2036, written at line ~2056, never read elsewhere.

**Fix**: Either remove `latestSnapshot` entirely, or use it to provide `LocalPhysicsSnapshot`:

```kotlin
CompositionLocalProvider(
    LocalPhysicsWorld provides world,
    LocalPhysicsSnapshot provides { latestSnapshot.value }
) { ... }
```

This would also fix C2.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **C1**: `PhysicsShape` body leak -- no `removeBody` on disposal | Low | Unbounded body accumulation in physics world |
| 2 | **C2**: `LocalPhysicsSnapshot` undefined -- blocks `rememberPhysicsBody` compilation | Low | Compilation failure |
| 3 | **I1**: `ColliderFactory` undefined -- blocks collider auto-derivation | Medium | No automatic colliders from shapes |
| 4 | **I3**: `screenToWorldRay` has placeholder `/* engine ref */` | Medium | Physics tap/raycast broken |
| 5 | **I2**: `BodyConfigBuilder` drops `userData` | Trivial | DSL completeness |
| 6 | **S3**: `latestSnapshot` state unused -- connects to C2 fix | Trivial | Clean code / enables C2 fix |
| 7 | **S2**: `PhysicsGroupNode.bodyConfig` unused | Low | API clarity |
| 8 | **S1**: Vertex centroid bias for non-uniform shapes | Low | Geometric precision |

---

## Overall Assessment

The plan is in strong shape after five rounds of revision. The core physics architecture is sound, the module split is correct, the threading model is well-reasoned, and the fix tracking is excellent. The 83 previous fixes have been incorporated cleanly.

The findings this round are narrower in scope than previous rounds, which is a healthy sign of convergence. The two critical issues (C1: body leak, C2: undefined CompositionLocal) are both in the Compose integration layer (Phase 4b) and are straightforward to fix. The body leak (C1) is the most impactful because it silently corrupts the physics world over time -- bodies accumulate without visible symptoms until performance degrades or phantom collisions occur.

The `ColliderFactory` gap (I1) is the most significant design-level omission. While its purpose is clear from context, the actual type dispatch logic (particularly the subtlety that `baseShape` loses its subclass type through `Shape.translate()`) needs to be documented and coded.

The `/* engine ref */` placeholder (I3) in the tap handler indicates the screen-to-world raycast pipeline is not fully designed. This is a cross-cutting concern that touches both `IsometricScene` (which owns the engine) and `PhysicsScene` (which needs it for raycasting). A pattern similar to `onHitTestReady` would work.

**Phases 0-4a** (pure physics core) are ready for implementation. **Phase 4b** needs C1, C2, I1, and I3 resolved before implementation begins. The remaining issues (I2, S1-S3) can be addressed during implementation without blocking.
