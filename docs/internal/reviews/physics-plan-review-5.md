# Physics Implementation Plan -- Review Round 5

> **Date**: 2026-03-13
> **Reviewer**: Architecture review agent
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md)
> **Status**: Issues identified -- plan revision required

---

## Review Summary

The R4 revision has addressed the vast majority of issues from previous rounds. The architecture is solid, the module split is correct, the threading model is well-reasoned, and the fix table demonstrates thorough tracking. However, cross-referencing every code snippet against the actual codebase reveals **3 critical issues** that will cause incorrect rendering or compilation failures, **4 important issues** with undefined types/factories that would block implementation, and **3 suggestions**. The most severe finding is that `PhysicsShapeNode.render()` rotates shape vertices around the world origin `(0,0,0)` instead of the shape's local center of mass, which will produce wildly wrong geometry for any shape constructed at a non-zero origin.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. `PhysicsShapeNode.render()` rotates vertices around world origin, not shape's local center -- tumbling shapes will orbit wildly

The plan's vertex transform (section 4b.2, line ~1778) applies the rotation matrix directly to the raw vertex positions and then translates by the physics position:

```kotlin
val rx = rotMatrix.m00 * p.x + rotMatrix.m01 * p.y + rotMatrix.m02 * p.z
...
Point(physicsPosition.x + rx, physicsPosition.y + ry, physicsPosition.z + rz)
```

This rotates each vertex `p` around the coordinate origin `(0, 0, 0)`. For a `Prism(Point.ORIGIN, 1, 1, 1)` with vertices from `(0,0,0)` to `(1,1,1)`, the center of mass is at `(0.5, 0.5, 0.5)`. A 90-degree rotation around the Z-axis would swing the corner at `(1, 1, 0)` to `(-1, 1, 0)` -- the shape orbits around `(0,0,0)` rather than spinning in place around its center.

This gets dramatically worse with the `TestPhysicsWorld.addDynamic()` helper (section 1.6), which creates `shape = Prism(pos, 1.0, 1.0, 1.0)` -- embedding the body's world position into vertex coordinates. A shape at position `(10, 5, 0)` would have vertices at `(10,5,0)` to `(11,6,1)`. Rotating these around `(0,0,0)` would swing the shape across the entire scene. Even without the construction origin issue, the local-center rotation problem applies to all shapes.

**Evidence**: Plan section 4b.2, `PhysicsShapeNode.render()` lines ~1778-1782. Actual `Prism.kt` lines 20-57 show vertices are relative to `origin` parameter.

**Fix**: The rotation must be applied around the shape's local center of mass, not around `(0,0,0)`. Compute the centroid of the `baseShape` once (or store it from the collider), then subtract centroid before rotation and re-add after:

```kotlin
// Compute local center once (cache it)
val cx = localCenter.x; val cy = localCenter.y; val cz = localCenter.z
// For each point p:
val lx = p.x - cx; val ly = p.y - cy; val lz = p.z - cz  // center-relative
val rx = rotMatrix.m00 * lx + rotMatrix.m01 * ly + rotMatrix.m02 * lz
val ry = rotMatrix.m10 * lx + rotMatrix.m11 * ly + rotMatrix.m12 * lz
val rz = rotMatrix.m20 * lx + rotMatrix.m21 * ly + rotMatrix.m22 * lz
Point(physicsPosition.x + rx, physicsPosition.y + ry, physicsPosition.z + rz)
```

Additionally, all `baseShape` instances should be constructed with `origin = Point.ORIGIN` so that `physicsPosition` is the sole position source. The `TestPhysicsWorld.addDynamic()` should use `Prism(Point.ORIGIN, 1.0, 1.0, 1.0)` and set `RigidBody.position = pos` separately. The plan's `PhysicsShape` composable passes the user-provided `shape` directly as `baseShape`, which may have an embedded origin. This must be documented or enforced.

### C2. `PhysicsScene` passes `null` to `IsometricScene.onTap` which is non-nullable -- will not compile

The plan's `PhysicsScene` (section 4b.4, line ~1933) passes:

```kotlin
onTap = if (onTap != null) { x, y, node ->
    val ray = PhysicsRaycastUtils.screenToWorldRay(x, y, /* engine ref */)
    val hit = world.raycast(ray)
    onTap(x, y, hit?.body)
} else null,
```

But the actual `IsometricScene.onTap` parameter (line 92 of `IsometricScene.kt`) is:

```kotlin
onTap: (x: Double, y: Double, node: IsometricNode?) -> Unit = { _, _, _ -> },
```

This is a **non-nullable** function type with a default empty lambda. Passing `null` will fail with a type mismatch at compile time. Kotlin does not implicitly convert `null` to a lambda type.

**Evidence**: `IsometricScene.kt` line 92 -- `onTap` parameter is `(Double, Double, IsometricNode?) -> Unit`, not `((Double, Double, IsometricNode?) -> Unit)?`.

**Fix**: Pass the default empty lambda instead of `null`:

```kotlin
onTap = if (onTap != null) { x, y, node ->
    val ray = PhysicsRaycastUtils.screenToWorldRay(x, y, /* engine ref */)
    val hit = world.raycast(ray)
    onTap(x, y, hit?.body)
} else { _, _, _ -> },
```

### C3. `PhysicsGroupNode` used in `ComposeNode` but never defined -- will not compile

The plan references `PhysicsGroupNode` in multiple locations:
- Section 4b.5 line ~2059: `ComposeNode<PhysicsGroupNode, IsometricApplier>( factory = { PhysicsGroupNode(body) }, ... )`
- Section 4b.5 line ~2062: `content = { PhysicsScopeImpl(isometricScope, world).content() }`
- R3-C3 fix table: "PhysicsGroupNode and any future node types are traversed correctly"
- Phase 4b tests: "Traverses PhysicsGroupNode children correctly"

But `PhysicsGroupNode` is never defined anywhere in the plan. Its constructor, class hierarchy, properties, and `render()` method are all unspecified. Since `IsometricApplier.insertBottomUp()` casts `current` to `GroupNode` (line 51 of `IsometricApplier.kt`) and throws an error if the cast fails, `PhysicsGroupNode` **must** extend `GroupNode` (not `IsometricNode` directly) for child insertion to work.

Additionally, the `PhysicsGroup` composable's `content` lambda calls `PhysicsScopeImpl(isometricScope, world).content()` -- but `isometricScope` is an undefined variable in this context. The `this` inside the `ComposeNode` `content` lambda is not an `IsometricScope`.

**Evidence**: `IsometricApplier.kt` lines 50-52, plan section 4b.5 lines ~2057-2064.

**Fix**: Define `PhysicsGroupNode` explicitly:

```kotlin
class PhysicsGroupNode(
    val bodyConfig: BodyConfig
) : GroupNode() {
    // GroupNode already provides: override val children, render() with child traversal
    // Physics position sync handled by syncPositionsToNodes traversal (which recurses into all nodes)
}
```

Also fix the `content` lambda in `PhysicsGroup` -- the `isometricScope` reference needs to be captured from the enclosing composable scope.

---

## IMPORTANT Issues (Should Fix)

### I1. `RigidBody.create()` factory method referenced but never defined

The plan uses `RigidBody.create(shape, body)` in `PhysicsShape` (line ~2032) and `RigidBody.create(config)` in `rememberPhysicsBody` (line ~2132). But the `RigidBody` class definition (section 1.2) only has a primary constructor with individual parameters and a `companion object` with `nextId()`. There is no `create()` factory method.

The factory method needs to:
1. Generate the next integer ID via `RigidBody.nextId()`
2. Compute the collider from the shape (or use the `colliderOverride`)
3. Compute inertia via `InertiaCalculator.compute(shape, mass)`
4. Compute `inverseMass` from the body type
5. Compute `localInverseInertia` from the inertia tensor
6. Set the initial position (from the shape's origin? from a parameter?)

The lack of this factory means the plan has no single place where the body creation logic is specified -- the collider derivation, inertia calculation, and initial position determination are all implicit.

**Evidence**: Plan section 1.2 `RigidBody` class, section 4b.5 `PhysicsShape` composable, section 4b.7 `rememberPhysicsBody`.

**Fix**: Define `RigidBody.create()` factory methods in the `companion object`, specifying exactly how shape-to-collider derivation, inertia computation, and initial position work. This is particularly important for the C1 issue above -- `create(shape, config)` needs to extract the shape's construction origin as the initial `position` and store a center-relative version of the shape as `baseShape`.

### I2. `PhysicsScopeImpl` class never defined

The plan references `PhysicsScopeImpl(this, world)` in both `PhysicsScene` (line ~1942) and `PhysicsGroup` (line ~2062), but this class is never defined. It presumably implements a `PhysicsScope` interface that `PhysicsShape`, `PhysicsGroup`, and `Ground` composables are scoped to.

Neither `PhysicsScope` nor `PhysicsScopeImpl` appear in the plan beyond their usage. The `PhysicsScope` interface needs to provide at minimum:
- The `LocalPhysicsWorld` composable local (or access to the world)
- The `IsometricScope` delegate (so standard `Shape()` and `Group()` calls still work)

**Evidence**: Plan section 4b.4 line ~1942, section 4b.5 line ~2062.

**Fix**: Define both:

```kotlin
interface PhysicsScope : IsometricScope {
    val world: PhysicsWorld
}

internal class PhysicsScopeImpl(
    private val isometricScope: IsometricScope,
    override val world: PhysicsWorld
) : PhysicsScope, IsometricScope by isometricScope
```

### I3. `ColliderShape` type referenced in `BodyConfig` but never defined

`BodyConfig.colliderOverride` is typed as `ColliderShape?` (line ~622), and the `BodyConfigBuilder` has a `collider(block: ColliderScope.() -> Unit)` method (line ~2094) that builds a `ColliderScope`. Neither `ColliderShape` nor `ColliderScope` is defined anywhere in the plan.

This is the API surface for users who want to provide a custom collider instead of auto-deriving from the visual shape. Without it, the only option is auto-derivation (which has its own issues per C1).

**Evidence**: Plan section 1.2 `BodyConfig` line ~622, section 4b.6 `BodyConfigBuilder` line ~2094.

**Fix**: Define `ColliderShape` and `ColliderScope`:

```kotlin
sealed interface ColliderShape {
    data class Box(val halfExtents: Vector) : ColliderShape
    data class Sphere(val radius: Double) : ColliderShape
    data class Custom(val collider: Collider) : ColliderShape
}

class ColliderScope {
    private var shape: ColliderShape? = null
    fun box(halfExtents: Vector) { shape = ColliderShape.Box(halfExtents) }
    fun sphere(radius: Double) { shape = ColliderShape.Sphere(radius) }
    internal fun build(): ColliderShape = shape ?: error("No collider shape specified")
}
```

### I4. `isometric-physics-core` Java 11 toolchain cannot compile against `isometric-core` Java 17 bytecode

The R3 review (I7) correctly identified a Java toolchain mismatch. The plan's fix (R3-I7) was to set `isometric-physics-core` to `JavaLanguageVersion.of(11)`. But `isometric-core` uses `jvmToolchain { languageVersion.set(JavaLanguageVersion.of(17)) }`, which causes the Kotlin compiler to emit class files with Java 17 bytecode version (class file version 61).

When `isometric-physics-core` uses `java { toolchain { languageVersion.set(JavaLanguageVersion.of(11)) } }`, Gradle provisions a Java 11 JDK. The Kotlin compiler daemon, running on Java 11, will attempt to read `isometric-core`'s Java 17 class files. Java 11 **cannot** read class file version 61 (Java 17), causing a compilation failure:

```
Unsupported class file major version 61
```

The `isometric-compose` module avoids this because Android build tools (D8/R8) handle cross-version bytecode. But `isometric-physics-core` is a pure JVM module -- no Android tooling to bridge the gap.

**Evidence**: `isometric-core/build.gradle.kts` line 19-21 (`jvmToolchain(17)`), plan section 1.7 line ~892 (`JavaLanguageVersion.of(11)`).

**Fix**: Use Java 17 toolchain for compilation but set `jvmTarget = "11"` for bytecode output:

```kotlin
kotlin {
    jvmToolchain(17)  // use Java 17 JDK (can read isometric-core's class files)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)  // emit Java 11 bytecode
    }
}
```

This ensures the Kotlin compiler can read `isometric-core`'s Java 17 class files while still producing Java 11-compatible output for Android consumers.

---

## SUGGESTIONS (Nice to Have)

### S1. `Knot.kt` custom paths are quad faces, not triangular -- plan mischaracterizes them

The plan's `KnotCompoundCollider` description (section 2.2, line ~1087) says "2 custom triangular paths (lines 23-38 of Knot.kt) are NOT prisms." But looking at the actual `Knot.kt` (lines 23-38), both custom paths have 4 vertices each -- they are quadrilateral faces, not triangular:

```kotlin
Path(Point(0,0,2), Point(0,0,1), Point(1,0,1), Point(1,0,2))  // 4-point quad
Path(Point(0,0,2), Point(0,1,2), Point(0,1,1), Point(0,0,1))  // 4-point quad
```

The collider approximation (enlarging the nearest prism) is still valid for quads, but the documentation is inaccurate. This matters if someone later tries to create dedicated colliders for these faces -- they would need quad colliders, not triangle colliders.

**Evidence**: `Knot.kt` lines 23-38.

**Fix**: Change "triangular" to "quadrilateral" in the `KnotCompoundCollider` documentation.

### S2. `PhysicsEventFlow.collisionEvents()` leaks the listener -- no disposal path

The `PhysicsEventFlow.collisionEvents()` (section 4b.4b, line ~1987) registers a listener on the `EventDispatcher` via `addCollisionListener` and returns the `SharedFlow`. But the `Disposable` handle returned by `addCollisionListener` is discarded:

```kotlin
fun collisionEvents(dispatcher: EventDispatcher): SharedFlow<CollisionEvent> {
    val flow = MutableSharedFlow<CollisionEvent>(extraBufferCapacity = 64)
    dispatcher.addCollisionListener { event ->  // handle discarded
        flow.tryEmit(event)
    }
    return flow
}
```

If `collisionEvents()` is called multiple times (e.g., across recompositions), each call registers a new listener that is never removed. The listeners accumulate, causing duplicate event emissions and memory leaks.

**Fix**: Return a `Pair<SharedFlow<CollisionEvent>, Disposable>` or make the function a `@Composable` that handles disposal via `DisposableEffect`. The same applies to `physicsEvents()`.

### S3. `PhysicsConfig.gravity` defaults to `Vector(0.0, 0.0, -9.81)` -- constructor uses positional args for `Vector(i, j, k)`

`Vector`'s constructor is `Vector(val i: Double, val j: Double, val k: Double)`. The plan consistently writes `Vector(0.0, 0.0, -9.81)` for gravity. While the plan's R4-S1 fix added `val Vector.x get() = i` / `.y` / `.z` extension properties for readability, the gravity construction itself silently maps to `i=0, j=0, k=-9.81`. A reader unfamiliar with the codebase might expect `Vector(x, y, z)` constructor parameter names. Using named arguments for clarity would help:

```kotlin
val gravity: Vector = Vector(i = 0.0, j = 0.0, k = -9.81)
```

This is purely a readability concern -- the code is functionally correct.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **C1**: Vertex rotation around world origin instead of local center | Medium | All rotating physics shapes render incorrectly |
| 2 | **C3**: `PhysicsGroupNode` undefined -- blocks compound bodies | Low | Compilation failure in Phase 4b |
| 3 | **C2**: `null` passed to non-nullable `onTap` parameter | Trivial | Compilation failure in Phase 4b |
| 4 | **I4**: Java 11 toolchain cannot read Java 17 class files | Low | Build failure in Phase 1 |
| 5 | **I1**: `RigidBody.create()` factory undefined | Medium | Blocks body creation in Phase 4b |
| 6 | **I2**: `PhysicsScopeImpl` class undefined | Low | Blocks PhysicsScene in Phase 4b |
| 7 | **I3**: `ColliderShape` / `ColliderScope` types undefined | Low | Blocks custom collider API |
| 8 | **S2**: `PhysicsEventFlow` listener leak | Low | Memory leak on recomposition |
| 9 | **S1**: Knot paths mischaracterized as triangular | Trivial | Documentation accuracy |
| 10 | **S3**: Gravity vector readability | Trivial | Developer experience |

---

## Overall Assessment

The plan has matured substantially through five rounds of review. The core architecture -- module split, threading model, compound colliders, island-based sleep, snapshot-based position sync -- is sound and well-documented. The fix tracking table at the top of the plan is excellent and provides clear traceability.

**The most impactful finding this round is C1** (vertex rotation around world origin). This is a geometric correctness issue that would cause every rotating physics body to render incorrectly. It is subtle because it works correctly in the special case where shapes are constructed at `Point.ORIGIN` -- which is NOT the default in the existing codebase (`Prism(pos, dx, dy, dz)` embeds `pos` into vertices). The fix requires both a code change (center-relative rotation) and an API convention (physics shapes should be constructed at origin, with position tracked separately). This convention needs to be enforced or documented in `RigidBody.create()`, which itself needs to be defined (I1).

**The Phase 4b Compose integration** continues to have the most issues across all review rounds. The undefined `PhysicsGroupNode`, `PhysicsScopeImpl`, `ColliderShape`, and `RigidBody.create()` types indicate that the Compose layer needs a focused design pass to define all the type contracts before implementation begins. The non-nullable `onTap` issue (C2) is trivial but would block the first compilation.

**The Java toolchain issue** (I4) is a self-inflicted problem: the R3-I7 fix (lowering to Java 11) creates a new failure because `isometric-core` produces Java 17 bytecode. The correct approach is to use Java 17 for compilation and set `jvmTarget = "11"` for output.

Phases 0-4a (the pure physics core) are in good shape and can proceed after fixing I4 (toolchain). Phase 4b needs C1, C2, C3, I1, I2, and I3 resolved before implementation begins.
