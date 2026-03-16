# Physics Implementation Plan (Revised R13)

> **Date**: 2026-03-13
> **Status**: Revised R13 — addresses findings from [physics-plan-review.md](../reviews/physics-plan-review.md) through [physics-plan-review-13.md](../reviews/physics-plan-review-13.md)
> **Research**: [PHYSICS_RESEARCH.md](../research/PHYSICS_RESEARCH.md)
> **Decisions**: Based on 36-question interview

> **Review fixes**: 13 rounds, 120 issues resolved — [fix tables at bottom of document](#review-fixes-applied)

---

## Table of Contents

- [Decision Summary](#decision-summary)
- [Module Structure](#module-structure)
- **[Phase 0: Core Shape Prerequisite](#phase-0-core-shape-prerequisite-fix-c4)**
  - [0.1 Changes to isometric-core shapes](#01-changes-to-isometric-core-shapes)
- **[Phase 1: Math Foundation + Rigid Body Skeleton](#phase-1-math-foundation--rigid-body-skeleton)**
  - [1.1 Math Primitives](#11-math-primitives)
  - [1.2 Rigid Body](#12-rigid-body) — RigidBody, RigidBody.create(), ColliderFactory, BodyConfig
  - [1.3 Physics World + Fixed Timestep](#13-physics-world--fixed-timestep) — PhysicsWorld, deferred mutation queue, PhysicsConfig
  - [1.4 Background Thread (JVM)](#14-background-thread-jvm--no-android-dependency-fix-i3)
  - [1.5 Inertia Computation](#15-inertia-computation-uses-phase-0-stored-dimensions)
  - [1.6 Test Harness](#16-test-harness)
  - [1.7 Gradle Setup](#17-gradle-setup)
- **[Phase 2: Collision Detection Pipeline](#phase-2-collision-detection-pipeline)**
  - [2.1 Broad Phase](#21-broad-phase-incremental-fix-s3)
  - [2.2 Collider Hierarchy](#22-collider-hierarchy-fix-c1) — Collider, ConvexCollider, CompoundCollider, SphereCollider, per-shape colliders
  - [2.3 Narrow Phase](#23-narrow-phase-fix-c1--branches-on-compound)
  - [2.4 GJK + EPA + Contact Clipping](#24-gjk--epa--contact-clipping)
  - [2.5 Contact Classes](#25-contact-classes-mutable--pooled-fix-s5)
  - [2.6 Contact Manager](#26-contact-manager-deterministic-fix-c5)
  - [2.7 Headless Debug Dump](#27-headless-debug-dump-fix-s1)
- **[Phase 3: Collision Response + Solver](#phase-3-collision-response--solver)**
  - [3.1 Sequential Impulse Solver](#31-sequential-impulse-solver)
  - [3.2 Full World Step](#32-full-world-step-fix-i4--island-aware-sleep)
  - [3.3 Island Manager](#33-island-manager-fix-i4)
- **[Phase 4a: Events, Raycasting, Forces, CCD](#phase-4a-events-raycasting-forces-ccd-physics-core)**
  - [4a.1 Collision Events](#4a1-collision-events)
  - [4a.2 Raycasting](#4a2-raycasting)
  - [4a.3 Force Fields](#4a3-force-fields)
  - [4a.4 CCD](#4a4-ccd)
- **[Phase 4b: Compose Integration](#phase-4b-compose-integration)**
  - [4b.1 Gradle Setup](#4b1-gradle-setup-fix-c3)
  - [4b.2 PhysicsShapeNode](#4b2-physicsshapenode-fix-r2-i1-r3-c1-r3-c2)
  - [4b.3 Android Physics Thread](#4b3-android-physics-thread-fix-i3-r3-s9)
  - [4b.3a CompositionLocals](#4b3a-compositionlocals-fix-r6-c2)
  - [4b.4 PhysicsScene](#4b4-physicsscene)
  - [4b.4a syncPositionsToNodes](#4b4a-syncpositionstonodes-fix-r2-i7-r3-c3)
  - [4b.4b PhysicsEventFlow](#4b4b-physicseventflow--flow-adapters-fix-r2-c4-r5-s2)
  - [4b.4c PhysicsRaycastUtils](#4b4c-physicsraycastutils-fix-r2-s7)
  - [4b.5a PhysicsGroupNode](#4b5a-physicsgroupnode-fix-r5-c3)
  - [4b.5b PhysicsScope + PhysicsScopeImpl](#4b5b-physicsscope--physicsscopeimpl-fix-r5-i2)
  - [4b.5c ColliderShape + ColliderScope](#4b5c-collidershape--colliderscope-fix-r5-i3)
  - [4b.5d PhysicsShape + PhysicsGroup](#4b5d-physicsshape--physicsgroup)
  - [4b.6 DSL Builders](#4b6-dsl-builders-fix-i7)
  - [4b.7 rememberPhysicsBody + Ground](#4b7-rememberphysicsbody--ground)
- **[Phase 5: Joints](#phase-5-joints)**
- **[Phase 6: Kinematic Bodies + Body Lifecycle](#phase-6-kinematic-bodies--body-lifecycle)**
- **[Phase 7: Debug Visualization + Profiler](#phase-7-debug-visualization--profiler)**
- **[Phase 8: Particles + Rope](#phase-8-particles--rope)**
- **[Phase 9: Benchmarks](#phase-9-benchmarks)**
- [Phase Summary](#phase-summary)
- [Review Fixes Applied](#review-fixes-applied) — 13 rounds, 120 issues

---

## Decision Summary

| Decision | Choice |
|----------|--------|
| Scope | Library feature for consumers |
| Module | `isometric-physics-core` (JVM) + `isometric-physics-compose` (Android) |
| Platform | isometric-core + isometric-compose (WebGPU-compatible) |
| Engine | Custom 3D Kotlin (reference dyn4j + papers) |
| Shapes | All (Prism, Pyramid, Cylinder, Octahedron, Stairs, Knot) |
| Rotation | Full 3D — physics transforms vertices directly |
| API | `PhysicsShape()` + `PhysicsGroup()`, minimal with defaults |
| Body types | Dynamic, Static, Kinematic, Sensor |
| Collision | Solid + bounce + friction + sensors, bitmask filtering |
| Events | Full lifecycle (Begin/Stay/End) + callbacks in core, Flow adapters in compose |
| Stacking | Critical (warm starting, solver iterations, island-based sleep) |
| Gravity | Force fields (radial, wind, vortex) + per-body scale |
| Forces | Full API (force, impulse, torque, atPoint) |
| Joints | Multiple (fixed, revolute, distance, prismatic) |
| Scale | 100-500 dynamic bodies |
| Sim control | Pause/resume + time scale |
| Boundaries | Optional (infinite default) |
| Ground | Built-in `Ground()` helper |
| Materials | Presets + custom |
| Body access | `rememberPhysicsBody` + `LocalPhysicsWorld` |
| Debug | Must-have (headless dump Phase 2, visual overlay Phase 7) |
| Raycasting | Essential for v1 |
| Particles | Built-in emitter |
| Soft body | Rope/chain only |
| Threading | Background thread from start (JVM primitives in core, Choreographer in compose) |
| CCD | Per-body `isBullet` flag |
| GPU future | Interfaces designed for migration |
| Determinism | Reproducible (LinkedHashMap + sorted contacts + fixed timestep) |
| Errors | Defensive with warnings |
| Testing | Deterministic unit tests |
| Timeline | No rush, get it right |

---

## Module Structure

Two physics modules mirror the existing `isometric-core` / `isometric-compose` split:

```
isometric-physics-core/                 # Pure JVM — no Android dependency
  build.gradle.kts                      # kotlin("jvm"), depends on :isometric-core
  src/
    main/kotlin/io/fabianterhorst/isometric/physics/
      ├── core/
      │   ├── PhysicsWorld.kt
      │   ├── RigidBody.kt
      │   ├── PhysicsStep.kt
      │   ├── BodyConfig.kt
      │   └── PhysicsThread.kt          # JVM ScheduledExecutorService
      ├── math/
      │   ├── AABB.kt
      │   ├── Matrix3x3.kt
      │   ├── Quaternion.kt
      │   └── PhysicsVector.kt
      ├── collision/
      │   ├── broadphase/
      │   │   ├── BroadPhase.kt         # Interface (GPU-migratable)
      │   │   └── SpatialHashGrid3D.kt  # Incremental updates
      │   ├── narrowphase/
      │   │   ├── NarrowPhase.kt        # Interface (GPU-migratable)
      │   │   ├── ConvexCollider.kt     # Support function interface
      │   │   ├── CompoundCollider.kt   # For Stairs, Knot (FIX C1)
      │   │   ├── ColliderShape.kt      # User-facing collider override sealed interface (FIX R5-I3)
      │   │   ├── ColliderFactory.kt    # Auto-derive collider from Shape (FIX R5-I1)
      │   │   ├── GjkDetector.kt
      │   │   ├── EpaResolver.kt
      │   │   └── colliders/
      │   │       ├── PrismCollider.kt
      │   │       ├── PyramidCollider.kt
      │   │       ├── CylinderCollider.kt
      │   │       ├── SphereCollider.kt         # Analytic support function (FIX R7-I3)
      │   │       ├── OctahedronCollider.kt
      │   │       ├── StairsCompoundCollider.kt   # Compound of PrismColliders
      │   │       └── KnotCompoundCollider.kt     # Compound of PrismColliders
      │   ├── ContactManifold.kt        # Mutable class, not data class (FIX S5)
      │   ├── ContactPoint.kt           # Mutable class with pool
      │   ├── ContactPool.kt            # Pre-allocated contact pool
      │   ├── ContactManager.kt         # LinkedHashMap (FIX C5)
      │   └── CollisionFilter.kt
      ├── solver/
      │   ├── ConstraintSolver.kt       # Interface (GPU-migratable)
      │   ├── SequentialImpulseSolver.kt
      │   ├── ContactConstraint.kt
      │   └── FrictionSolver.kt
      ├── dynamics/
      │   ├── ForceField.kt
      │   ├── SleepSystem.kt            # Island-aware (FIX I4)
      │   ├── IslandManager.kt          # Wired into step() (FIX I4)
      │   └── CcdSolver.kt
      ├── joints/
      │   ├── Joint.kt
      │   ├── FixedJoint.kt
      │   ├── RevoluteJoint.kt
      │   ├── DistanceJoint.kt
      │   └── PrismaticJoint.kt
      ├── query/
      │   ├── Ray.kt
      │   ├── RaycastResult.kt
      │   └── OverlapQuery.kt
      ├── particles/
      │   ├── Particle.kt
      │   ├── ParticleEmitterConfig.kt
      │   └── ParticleWorld.kt
      ├── softbody/
      │   ├── Spring.kt
      │   ├── RopeBody.kt
      │   └── VerletIntegrator.kt
      ├── material/
      │   └── PhysicsMaterial.kt
      ├── event/
      │   ├── CollisionEvent.kt
      │   ├── PhysicsEvent.kt
      │   └── EventDispatcher.kt        # Callback-based, no coroutines (FIX R2-C4)
      ├── debug/                        # Headless debug output (FIX S1)
      │   ├── PhysicsDebugDump.kt       # SVG/text dump for testing
      │   └── PhysicsSnapshot.kt        # Serializable world state
      └── inertia/
          └── InertiaCalculator.kt

    test/kotlin/io/fabianterhorst/isometric/physics/
      ├── math/
      │   ├── AABBTest.kt
      │   ├── QuaternionTest.kt
      │   └── Matrix3x3Test.kt
      ├── collision/
      │   ├── GjkDetectorTest.kt
      │   ├── EpaResolverTest.kt
      │   ├── CompoundColliderTest.kt
      │   ├── SpatialHashGrid3DTest.kt
      │   └── colliders/
      ├── solver/
      │   ├── SequentialImpulseSolverTest.kt
      │   └── FrictionSolverTest.kt
      ├── dynamics/
      │   ├── SleepSystemTest.kt
      │   ├── IslandManagerTest.kt
      │   └── CcdSolverTest.kt
      ├── joints/
      │   └── JointTest.kt
      ├── integration/
      │   ├── StackingTest.kt
      │   ├── BouncingTest.kt
      │   ├── SensorTest.kt
      │   ├── KinematicTest.kt
      │   ├── ForceFieldTest.kt
      │   └── DeterminismTest.kt
      └── TestPhysicsWorld.kt

isometric-physics-compose/              # Android library — Compose integration
  build.gradle.kts                      # com.android.library, depends on
                                        #   :isometric-physics-core, :isometric-compose
  src/
    main/kotlin/io/fabianterhorst/isometric/physics/compose/
      ├── PhysicsScene.kt
      ├── PhysicsShape.kt
      ├── PhysicsGroup.kt
      ├── PhysicsShapeNode.kt           # Extends IsometricNode directly (FIX R2-I1)
      ├── PhysicsGroupNode.kt          # Extends GroupNode (FIX R5-C3)
      ├── PhysicsScope.kt              # PhysicsScope interface + PhysicsScopeImpl (FIX R5-I2)
      ├── AndroidPhysicsThread.kt       # Choreographer-based (FIX I3)
      ├── PhysicsLoop.kt
      ├── BodyConfigDsl.kt              # type as single source of truth (FIX I7)
      ├── PhysicsRaycastUtils.kt        # screenToWorldRay lives here (FIX R2-S7)
      ├── PhysicsEventFlow.kt           # Flow adapters wrapping core callbacks (FIX R2-C4)
      ├── CompositionLocals.kt
      ├── Ground.kt
      ├── RememberPhysicsBody.kt
      ├── debug/
      │   ├── PhysicsDebugOverlay.kt
      │   └── PhysicsProfilerOverlay.kt
      └── particles/
          └── ParticleEmitter.kt

    test/kotlin/.../
      └── (Compose instrumented tests)

isometric-physics-benchmark/            # Android app
  build.gradle.kts                      # depends on :isometric-physics-core,
                                        #   :isometric-physics-compose,
                                        #   :isometric-benchmark
  src/main/kotlin/.../
    ├── PhysicsBenchmarkConfig.kt
    ├── PhysicsBenchmarkScreen.kt
    └── scenarios/
        ├── StackingScenario.kt
        ├── BroadPhaseScenario.kt
        ├── SolverScenario.kt
        └── RaycastScenario.kt
```

---

## Phase 0: Core Shape Prerequisite (FIX C4)

**Goal**: Store construction dimensions on core shape classes so physics can derive colliders and compute inertia without reverse-engineering from path vertices.

**Deliverable**: `Prism.dx`, `Cylinder.radius`, `Stairs.stepCount` etc. are accessible fields. All existing tests pass.

### 0.1 Changes to isometric-core shapes

Only **dimension parameters** are promoted to `val` fields. `origin` is intentionally NOT stored — physics tracks position separately via `RigidBody.position`. Storing `origin` would create a stale value that diverges as the body moves. (FIX R2-C1)

```kotlin
// Prism.kt — store dimensions only, NOT origin
class Prism(
    origin: Point,           // bare parameter — consumed by createPaths only
    val dx: Double = 1.0,   // val — stored as field
    val dy: Double = 1.0,
    val dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz))

// Pyramid.kt
class Pyramid(
    origin: Point,
    val dx: Double = 1.0,
    val dy: Double = 1.0,
    val dz: Double = 1.0
) : Shape(createPaths(origin, dx, dy, dz))

// Cylinder.kt — keep existing extrude-based super-call, just add `val` to params (FIX R4-C3)
// NOTE: Cylinder has a secondary constructor `Cylinder(origin, vertices, height)` that delegates
// to primary with `radius = 1.0`. After adding `val`, verify no call-site ambiguity. (FIX R3-I1)
class Cylinder(
    origin: Point,
    val radius: Double = 1.0,
    val vertices: Int = 20,
    val height: Double = 1.0
) : Shape(Shape.extrude(Circle(origin, radius, vertices), height).paths)

// Octahedron.kt — non-uniform scale: sqrt(2)/2 in XY, 1.0 in Z (FIX R3-I2)
class Octahedron(origin: Point) : Shape(createPaths(origin)) {
    /** Actual XY scale applied to paths — NOT 1.0. Used by AABB/inertia. */
    val xyScale: Double get() = sqrt(2.0) / 2.0  // ≈ 0.707
    val zScale: Double get() = 1.0
}

// Stairs.kt — store stepCount; physical size is always 1x1x1
class Stairs(origin: Point, val stepCount: Int) : Shape(createPaths(origin, stepCount)) {
    // Stairs always occupies a 1x1x1 bounding box.
    // WARNING: These are only valid because Stairs is inherently fixed-size (1×1×1).
    // If Stairs is ever parameterized with variable width/depth/height, these MUST
    // become `val` constructor parameters instead of hardcoded getters. (FIX R4-I1)
    val width: Double get() = 1.0
    val depth: Double get() = 1.0
    val height: Double get() = 1.0
}

// Knot.kt — no user-configurable dimensions (fixed geometry)
class Knot(origin: Point) : Shape(createPaths(origin))
```

**Also in Phase 0** — add `ZERO` constant to `Vector.Companion` (FIX R3-S4, R4-I3):

```kotlin
// Vector.kt — add to existing companion object (NOT extension properties)
companion object {
    // ... existing fromTwoPoints, crossProduct, dotProduct ...
    val ZERO = Vector(0.0, 0.0, 0.0)
    // NOTE: No GRAVITY constant here — gravity default lives ONLY in PhysicsConfig.gravity.
    // Having two gravity constants (Vector.GRAVITY + PhysicsConfig default) risks divergence. (FIX R4-I3)
}
```

> Extension properties on companion objects cannot have backing fields — each access would allocate a new `Vector`. Placing them inside the companion as `val` constants ensures a single allocation. (FIX R3-S4)

**Compatibility note**: Adding `val` to constructor parameters is **source-compatible** (call sites don't change) but **binary-incompatible** (new fields and getters are added). This is acceptable since `isometric-core` is versioned alongside the physics modules.

**Also in Phase 0** — add `onRootNodeReady` and `onEngineReady` callbacks to `IsometricScene` (FIX R4-C1, R6-I3):

The `rootNode` and `engine` in `IsometricScene` are private local variables (lines 99-100). The `PhysicsScene` wrapper needs access to `rootNode` for `syncPositionsToNodes` and `engine` for `PhysicsRaycastUtils.screenToWorldRay`. Add callback parameters following the existing `onHitTestReady` pattern:

```kotlin
// IsometricScene.kt — add parameters (matches existing onHitTestReady pattern)
@Composable
fun IsometricScene(
    // ... existing parameters ...
    onHitTestReady: ((hitTest: (x: Double, y: Double) -> IsometricNode?) -> Unit)? = null,
    onRootNodeReady: ((rootNode: GroupNode) -> Unit)? = null,  // NEW (FIX R4-C1)
    onEngineReady: ((engine: IsometricEngine) -> Unit)? = null,  // NEW (FIX R6-I3)
    // ... rest of parameters ...
) {
    val rootNode = remember { GroupNode() }
    val engine = remember { IsometricEngine() }
    // ... existing code ...

    // Expose root node and engine to PhysicsScene (FIX R4-C1, R6-I3)
    SideEffect {
        onRootNodeReady?.invoke(rootNode)
        onEngineReady?.invoke(engine)
    }
}
```

Both are non-breaking additions — the parameters have defaults of `null`, so existing callers are unaffected.

### Phase 0 Tests

- All existing isometric-core tests pass unchanged
- New tests: `prism.dx`, `cylinder.radius`, `stairs.stepCount` return construction values
- `cylinder` super-call uses `Shape.extrude(Circle(...))` — not `createPaths()` (FIX R4-C3)
- `octahedron.xyScale ≈ 0.707`, `octahedron.zScale == 1.0` (FIX R3-I2)
- Shapes created via companion factory functions also expose dimensions
- `Cylinder(origin, 20, 2.0)` still resolves to secondary constructor (no ambiguity) (FIX R3-I1)
- `Vector.ZERO` is a singleton (same reference on repeated access) (FIX R3-S4)
- `IsometricScene` with `onRootNodeReady` callback receives non-null `GroupNode` (FIX R4-C1)
- `IsometricScene` with `onEngineReady` callback receives non-null `IsometricEngine` (FIX R6-I3)

**Phase 0 is complete when**: All shape classes expose their construction dimensions as public fields. `origin` remains a bare constructor parameter — NOT stored. `Vector.ZERO` is a constant in the companion. `IsometricScene` exposes `onRootNodeReady` and `onEngineReady` callbacks.

---

## Phase 1: Math Foundation + Rigid Body Skeleton

**Goal**: A `PhysicsWorld` that integrates rigid bodies under gravity with a fixed timestep on a background thread. No collision — objects fall through each other. Establishes the math library, threading model, and reproducible stepping.

**Depends on**: Phase 0

**Deliverable**: Bodies fall under gravity. `TestPhysicsWorld.stepN(300)` produces identical positions across runs. Background thread publishes interpolated positions.

### 1.1 Math Primitives

**`AABB.kt`** — Axis-Aligned Bounding Box

```kotlin
/**
 * Immutable AABB — used at API boundaries and for spatial queries.
 * For per-body AABB storage, use MutableAABB to avoid GC pressure. (FIX R3-S1)
 */
data class AABB(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
) {
    fun intersects(other: AABB): Boolean
    fun intersects(other: MutableAABB): Boolean
    fun contains(point: Point): Boolean
    fun merged(other: AABB): AABB
    fun expanded(margin: Double): AABB
    fun volume(): Double
    fun center(): Point

    companion object {
        fun fromPoints(points: List<Point>): AABB
        fun fromPrism(origin: Point, dx: Double, dy: Double, dz: Double): AABB
        fun fromPyramid(origin: Point, dx: Double, dy: Double, dz: Double): AABB
        fun fromCylinder(origin: Point, radius: Double, height: Double): AABB
        /** Octahedron uses non-uniform scale: XY * 0.707, Z * 1.0 (FIX R3-I2) */
        fun fromOctahedron(origin: Point, xyScale: Double, zScale: Double): AABB
        fun fromStairs(origin: Point, stepCount: Int): AABB
        fun fromKnot(origin: Point): AABB
        /**
         * Compute AABB from any shape's path vertices. (FIX R2-I3, R8-I2)
         *
         * Uses fromPoints() universally — the per-type analytic functions (fromPrism, etc.)
         * require an `origin: Point` parameter, but Phase 0 does not store origin as a val
         * field. fromPoints() gives identical results for all current shape types because
         * the path vertices define the exact bounding box.
         *
         * The per-type analytic functions are retained for direct use when origin IS known
         * (e.g., test code, StairsCompoundCollider.create where origin is a parameter).
         */
        fun fromShape(shape: Shape): AABB =
            fromPoints(shape.paths.flatMap { it.points })
    }
}

/**
 * Mutable AABB — stored per-body to avoid 500 allocations per physics step. (FIX R3-S1)
 * Updated in-place via updateFrom(). The immutable AABB is used for API returns.
 */
class MutableAABB(
    var minX: Double = 0.0, var minY: Double = 0.0, var minZ: Double = 0.0,
    var maxX: Double = 0.0, var maxY: Double = 0.0, var maxZ: Double = 0.0
) {
    fun updateFrom(aabb: AABB) { minX = aabb.minX; minY = aabb.minY; minZ = aabb.minZ
                                  maxX = aabb.maxX; maxY = aabb.maxY; maxZ = aabb.maxZ }
    fun intersects(other: MutableAABB): Boolean = /* ... */
    fun toImmutable(): AABB = AABB(minX, minY, minZ, maxX, maxY, maxZ)
    /** Returns a new immutable AABB expanded by margin in all directions.
     *  Used by CCD sweep broadphase queries. (FIX R4-I2) */
    fun expanded(margin: Double): AABB = AABB(
        minX - margin, minY - margin, minZ - margin,
        maxX + margin, maxY + margin, maxZ + margin
    )
}
```

**`Matrix3x3.kt`** — 3x3 matrix for inertia tensors and rotation

```kotlin
data class Matrix3x3(
    val m00: Double, val m01: Double, val m02: Double,
    val m10: Double, val m11: Double, val m12: Double,
    val m20: Double, val m21: Double, val m22: Double
) {
    operator fun times(v: Vector): Vector
    operator fun times(other: Matrix3x3): Matrix3x3
    fun transpose(): Matrix3x3
    fun inverse(): Matrix3x3
    fun determinant(): Double

    companion object {
        val IDENTITY: Matrix3x3
        val ZERO: Matrix3x3
        fun diagonal(xx: Double, yy: Double, zz: Double): Matrix3x3
        fun fromQuaternion(q: Quaternion): Matrix3x3
    }
}
```

**`Quaternion.kt`** — Orientation (avoids gimbal lock)

```kotlin
data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quaternion
    fun conjugate(): Quaternion
    operator fun times(other: Quaternion): Quaternion
    operator fun plus(other: Quaternion): Quaternion
    operator fun times(scalar: Double): Quaternion
    fun toMatrix(): Matrix3x3
    fun rotate(v: Vector): Vector

    companion object {
        val IDENTITY: Quaternion
        fun fromAxisAngle(axis: Vector, angle: Double): Quaternion
        fun fromEuler(x: Double, y: Double, z: Double): Quaternion
        fun integrate(current: Quaternion, angularVelocity: Vector, dt: Double): Quaternion

        /** Spherical linear interpolation between two quaternions. (FIX R2-S8) */
        fun slerp(a: Quaternion, b: Quaternion, t: Double): Quaternion {
            var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            // Flip sign for shortest path
            val b2 = if (dot < 0) { dot = -dot; Quaternion(-b.w, -b.x, -b.y, -b.z) } else b
            return if (dot > 0.9995) {
                // Linear interpolation for near-identical quaternions
                Quaternion(
                    a.w + (b2.w - a.w) * t, a.x + (b2.x - a.x) * t,
                    a.y + (b2.y - a.y) * t, a.z + (b2.z - a.z) * t
                ).normalized()
            } else {
                val theta = acos(dot.coerceIn(-1.0, 1.0))
                val sinTheta = sin(theta)
                val wa = sin((1 - t) * theta) / sinTheta
                val wb = sin(t * theta) / sinTheta
                Quaternion(
                    wa * a.w + wb * b2.w, wa * a.x + wb * b2.x,
                    wa * a.y + wb * b2.y, wa * a.z + wb * b2.z
                ).normalized()  // normalize to counter floating-point drift (FIX R3-S3)
            }
        }
    }
}
```

**`PhysicsVector.kt`** — Extensions on existing `Vector`

```kotlin
operator fun Vector.plus(other: Vector): Vector
operator fun Vector.minus(other: Vector): Vector
operator fun Vector.times(scalar: Double): Vector
operator fun Vector.unaryMinus(): Vector
fun Vector.cross(other: Vector): Vector
fun Vector.dot(other: Vector): Double
fun Vector.magnitudeSquared(): Double
fun Vector.isNearZero(epsilon: Double = 1e-10): Boolean

// Point - Point → Vector (displacement between two positions). Used throughout physics
// for moment arms (r × F), contact offsets, and force application points. (FIX R13-I1)
operator fun Point.minus(other: Point): Vector =
    Vector(x - other.x, y - other.y, z - other.z)

// Vector.ZERO is a val constant inside Vector.Companion
// (added in Phase 0, NOT extension property — avoids per-access allocation) (FIX R3-S4)

// Readability aliases for physics code — Vector fields are i/j/k but physics
// conventionally uses x/y/z. These are inline extension properties (zero cost). (FIX R4-S1)
inline val Vector.x: Double get() = i
inline val Vector.y: Double get() = j
inline val Vector.z: Double get() = k
```

### 1.2 Rigid Body

**`RigidBody.kt`** — Mutable physics state

```kotlin
class RigidBody(
    val id: Int,                // Integer ID for fast hashing (FIX R2-S1)
    val config: BodyConfig,
    var position: Point,
    var orientation: Quaternion = Quaternion.IDENTITY,
    // force/torque/velocity/angularVelocity are ONLY written on the physics thread.
    // Main-thread force/impulse/torque requests go through pendingForces queue (FIX R12-I1).
    // No @Volatile needed — single-writer (physics thread) eliminates visibility races.
    // (R10-I1 originally added @Volatile; R12-I1 replaced with deferred queue for atomicity.)
    var velocity: Vector = Vector.ZERO,
    var angularVelocity: Vector = Vector.ZERO,
    val inverseMass: Double,
    val localInertia: Matrix3x3,
    val localInverseInertia: Matrix3x3,
    var force: Vector = Vector.ZERO,
    var torque: Vector = Vector.ZERO,
    var isSleeping: Boolean = false,
    var sleepTimer: Double = 0.0,
    val aabb: MutableAABB = MutableAABB(),  // mutable — updated in-place each step (FIX R3-S1)
) {
    /** Always use `is` checks — consistent for all types (FIX R2-I4, R3-S5) */
    val isStatic: Boolean get() = config.type is BodyType.Static
    val isDynamic: Boolean get() = config.type is BodyType.Dynamic
    val isKinematic: Boolean get() = config.type is BodyType.Kinematic
    val isSensor: Boolean get() = config.type is BodyType.Sensor

    var worldInverseInertia: Matrix3x3 = localInverseInertia

    // Deferred force/impulse/torque queue — main-thread-safe. (FIX R12-I1)
    // Requests are enqueued from the main thread (via PhysicsBodyRef) and drained on
    // the physics thread at the start of step(), after clearForces(). This avoids the
    // read-modify-write race where applyForce's `force = force + f` could interleave
    // with clearForces' `force = Vector.ZERO`, causing stale forces to leak across steps.
    // Same pattern as PhysicsWorld's deferred body mutation queue (R8-C1).
    sealed class ForceRequest {
        data class Force(val f: Vector) : ForceRequest()
        data class ForceAtPoint(val f: Vector, val worldPoint: Point) : ForceRequest()
        data class Impulse(val impulse: Vector) : ForceRequest()
        data class ImpulseAtPoint(val impulse: Vector, val worldPoint: Point) : ForceRequest()
        data class Torque(val t: Vector) : ForceRequest()
    }
    val pendingForces = java.util.concurrent.ConcurrentLinkedQueue<ForceRequest>()

    // Public API — main-thread-safe: enqueue into ConcurrentLinkedQueue (lock-free, thread-safe)
    fun applyForce(f: Vector) { pendingForces.add(ForceRequest.Force(f)) }
    fun applyForceAtPoint(f: Vector, worldPoint: Point) { pendingForces.add(ForceRequest.ForceAtPoint(f, worldPoint)) }
    fun applyImpulse(impulse: Vector) { pendingForces.add(ForceRequest.Impulse(impulse)) }
    fun applyImpulseAtPoint(impulse: Vector, worldPoint: Point) { pendingForces.add(ForceRequest.ImpulseAtPoint(impulse, worldPoint)) }
    fun applyTorque(t: Vector) { pendingForces.add(ForceRequest.Torque(t)) }

    // Internal API — physics-thread-only: direct field mutation, no queue. (FIX R13-S1)
    // Used by gravity, force fields, and solver — these run during step() on the physics
    // thread, so they can safely mutate force/torque/velocity directly. Using the queued
    // API would delay their effects by one step (queue drained at step start, but gravity
    // and force fields apply at step 1b after drain).
    internal fun applyForceInternal(f: Vector) { force = force + f }
    internal fun applyTorqueInternal(t: Vector) { torque = torque + t }
    internal fun applyImpulseInternal(impulse: Vector) { velocity = velocity + impulse * inverseMass }

    // Called on physics thread only — drains queued requests into force/torque/velocity accumulators
    fun drainPendingForces() {
        while (true) {
            val req = pendingForces.poll() ?: break
            when (req) {
                is ForceRequest.Force -> force = force + req.f
                is ForceRequest.ForceAtPoint -> {
                    force = force + req.f
                    torque = torque + (req.worldPoint - position).cross(req.f)
                }
                is ForceRequest.Impulse -> velocity = velocity + req.impulse * inverseMass
                is ForceRequest.ImpulseAtPoint -> {
                    velocity = velocity + req.impulse * inverseMass
                    angularVelocity = angularVelocity + worldInverseInertia * (req.worldPoint - position).cross(req.impulse)
                }
                is ForceRequest.Torque -> torque = torque + req.t
            }
        }
    }

    fun clearForces()
    fun updateWorldInertia()
    fun computeAABB(): AABB

    /** Collider for this body — derived from shape or overridden by config */
    lateinit var collider: Collider

    companion object {
        private val nextId = java.util.concurrent.atomic.AtomicInteger(0)
        fun nextId(): Int = nextId.getAndIncrement()

        /**
         * Factory: create a RigidBody from a visual shape + config. (FIX R5-I1)
         *
         * CRITICAL (FIX R5-C1): The returned body's `baseShape` (stored in PhysicsShapeNode)
         * must be ORIGIN-CENTERED — centroid translated to Point.ORIGIN. This is because
         * PhysicsShapeNode.render() rotates vertices around (0,0,0). If vertices are not
         * centered there, rotation would orbit the shape around the world origin instead of
         * spinning it in place.
         *
         * Steps:
         * 1. Compute centroid of the input shape's vertices
         * 2. Translate shape by -centroid to produce an origin-centered `baseShape`
         * 3. Use centroid as the body's initial `position`
         * 4. Derive collider from the origin-centered shape (or use override)
         * 5. Compute inertia from the shape type and mass
         */
        fun create(shape: Shape, config: BodyConfig): Pair<RigidBody, Shape> {
            val mass = when (val t = config.type) {
                is BodyType.Dynamic -> t.mass
                else -> 0.0  // static/kinematic/sensor have zero inverse mass
            }
            val inverseMass = if (mass > 0.0) 1.0 / mass else 0.0

            // Compute centroid via AABB — avoids accessing shape.origin (which is NOT stored
            // as a val field; Phase 0 keeps origin as a bare constructor parameter). (FIX R7-C1)
            //
            // AABB.fromShape() dispatches analytically per shape type (Prism, Cylinder, etc.)
            // using stored dimension fields (dx, dy, dz, radius, height) and vertex positions.
            // This avoids both the non-existent origin field AND vertex duplication bias. (FIX R6-S1)
            // AABB.center() returns the geometric center of the bounding box.
            val centroid = AABB.fromShape(shape).center()

            // Origin-centered baseShape — for PhysicsShapeNode.render() rotation (FIX R5-C1)
            val baseShape = shape.translate(-centroid.x, -centroid.y, -centroid.z)

            val inertia = InertiaCalculator.compute(shape, mass)
            val invInertia = if (mass > 0.0) inertia.inverse() else Matrix3x3.ZERO

            val body = RigidBody(
                id = nextId(),
                config = config,
                position = centroid,  // initial position = shape's centroid
                inverseMass = inverseMass,
                localInertia = inertia,
                localInverseInertia = invInertia
            )

            // Derive collider from origin-centered shape (or use override)
            body.collider = config.colliderOverride?.toCollider()
                ?: ColliderFactory.fromShape(baseShape, shape)

            return Pair(body, baseShape)
        }

        /**
         * Factory: create a bodiless RigidBody from config only (for sensors/triggers). (FIX R5-I1)
         * Used by rememberPhysicsBody where there is no visual shape.
         */
        fun create(config: BodyConfig): RigidBody {
            val mass = when (val t = config.type) {
                is BodyType.Dynamic -> t.mass
                else -> 0.0
            }
            val inverseMass = if (mass > 0.0) 1.0 / mass else 0.0
            val body = RigidBody(
                id = nextId(),
                config = config,
                position = Point.ORIGIN,
                inverseMass = inverseMass,
                localInertia = Matrix3x3.IDENTITY,
                localInverseInertia = if (mass > 0.0) Matrix3x3.IDENTITY else Matrix3x3.ZERO
            )
            // Sensor/trigger with no shape — collider must be set explicitly or via colliderOverride
            body.collider = config.colliderOverride?.toCollider()
                ?: PrismCollider(halfExtents = Vector(0.5, 0.5, 0.5), center = Point.ORIGIN)  // default unit box
            return body
        }
    }
}
```

**`ColliderFactory.kt`** — Auto-derive collider from visual shape (FIX R6-I1)

```kotlin
/**
 * Derives a physics collider from a visual Shape.
 *
 * IMPORTANT: `baseShape` (returned by Shape.translate()) loses its subclass type — it is always
 * a plain Shape. We must dispatch on `originalShape` to determine the shape type and access
 * stored dimensions (dx, dy, dz, radius, height, etc.).
 *
 * All returned colliders are origin-centered (matching the origin-centered baseShape convention).
 */
object ColliderFactory {
    /**
     * @param baseShape Origin-centered shape (used only for fallback AABB extraction)
     * @param originalShape Original typed shape (for type dispatch and dimension access)
     */
    fun fromShape(baseShape: Shape, originalShape: Shape): Collider {
        // Compute centroid offset for compound colliders. Convex colliders are symmetric
        // around origin so they don't need this, but compound colliders (Stairs, Knot) have
        // asymmetric child positions that must be shifted to match the origin-centered
        // baseShape produced by RigidBody.create(). (FIX R8-I1)
        val centroid = AABB.fromShape(originalShape).center()

        return when (originalShape) {
            is Prism -> PrismCollider(
                halfExtents = Vector(originalShape.dx / 2, originalShape.dy / 2, originalShape.dz / 2),
                center = Point.ORIGIN
            )
            is Cylinder -> CylinderCollider(
                radius = originalShape.radius,
                halfHeight = originalShape.height / 2,
                center = Point.ORIGIN
            )
            is Pyramid -> PyramidCollider(
                // Vertex enumeration from baseShape.paths — origin-centered vertices (FIX R7-I2)
                vertices = baseShape.paths.flatMap { it.points }.distinct().toTypedArray()
            )
            is Octahedron -> OctahedronCollider(
                // Vertex enumeration from baseShape.paths — origin-centered, with XY scale ~0.707 (FIX R7-I2)
                vertices = baseShape.paths.flatMap { it.points }.distinct().toTypedArray()
            )
            is Stairs -> {
                // Create compound then shift all child positions by -centroid to match
                // the origin-centered baseShape. Without this shift, collision geometry
                // would be offset from visual geometry by the centroid vector. (FIX R8-I1)
                val compound = StairsCompoundCollider.create(Point.ORIGIN, originalShape.stepCount)
                compound.translate(-centroid.x, -centroid.y, -centroid.z)
            }
            is Knot -> {
                val compound = KnotCompoundCollider.create(Point.ORIGIN)
                compound.translate(-centroid.x, -centroid.y, -centroid.z)
            }
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
}
```

> **Note on position sync (FIX R2-I2)**: `RigidBody` does NOT have `getTransformedShape()`. The physics thread publishes an interpolated snapshot via `AtomicReference`. The main thread reads the snapshot and copies position/orientation into `PhysicsShapeNode` fields. The node's `render()` reads from its own fields — never from `bodyRef` directly. This provides a single, consistent position-sync path with no threading races.

**`BodyConfig.kt`** — Immutable configuration

```kotlin
data class BodyConfig(
    val type: BodyType = BodyType.Dynamic(),
    val material: PhysicsMaterial = PhysicsMaterial.Default,
    val colliderOverride: ColliderShape? = null,  // null = derive from visual shape
    val collisionFilter: CollisionFilter = CollisionFilter(),
    val gravityScale: Double = 1.0,
    val linearDamping: Double = 0.0,
    val angularDamping: Double = 0.01,
    val isBullet: Boolean = false,
    val allowSleep: Boolean = true,
    val tag: String? = null,
    val userData: Any? = null
)

/** Always check with `is`, not `==`: `body.type is BodyType.Dynamic` (FIX R2-I4) */
sealed interface BodyType {
    object Static : BodyType
    data class Dynamic(val mass: Double = 1.0) : BodyType   // == compares mass too
    data class Kinematic(val pushable: Boolean = false) : BodyType
    object Sensor : BodyType
}
```

**`PhysicsMaterial.kt`** — Presets + custom

```kotlin
sealed class PhysicsMaterial {
    abstract val friction: Double
    abstract val restitution: Double
    abstract val density: Double

    object Default : PhysicsMaterial() {
        override val friction = 0.5; override val restitution = 0.3; override val density = 1.0
    }
    object Ice : PhysicsMaterial() {
        override val friction = 0.02; override val restitution = 0.1; override val density = 0.917
    }
    object Rubber : PhysicsMaterial() {
        override val friction = 0.8; override val restitution = 0.85; override val density = 1.1
    }
    object Metal : PhysicsMaterial() {
        override val friction = 0.4; override val restitution = 0.2; override val density = 7.8
    }
    object Wood : PhysicsMaterial() {
        override val friction = 0.6; override val restitution = 0.4; override val density = 0.6
    }
    object Stone : PhysicsMaterial() {
        override val friction = 0.7; override val restitution = 0.1; override val density = 2.4
    }
    data class Custom(
        override val friction: Double = 0.5,
        override val restitution: Double = 0.3,
        override val density: Double = 1.0
    ) : PhysicsMaterial()
}
```

### 1.3 Physics World + Fixed Timestep

**`PhysicsWorld.kt`**

```kotlin
class PhysicsWorld(val config: PhysicsConfig = PhysicsConfig()) {
    // Deterministic ordering: LinkedHashMap with integer keys (FIX C5, R2-S1)
    // These collections are ONLY mutated on the physics thread (via drainPendingMutations).
    // The main thread enqueues mutations via the lock-free pendingMutations queue. (FIX R8-C1)
    private val bodiesById = LinkedHashMap<Int, RigidBody>()
    private val bodiesList = mutableListOf<RigidBody>()

    // Thread-safe deferred mutation queue. addBody()/removeBody() are called from the
    // main (Compose) thread, but bodiesById/bodiesList are iterated on the physics thread
    // during step(). Direct mutation would cause ConcurrentModificationException. (FIX R8-C1)
    private sealed class BodyMutation {
        data class Add(val body: RigidBody) : BodyMutation()
        data class Remove(val body: RigidBody) : BodyMutation()
    }
    private val pendingMutations = java.util.concurrent.ConcurrentLinkedQueue<BodyMutation>()

    /** Enqueue body addition — applied at start of next step(). Thread-safe. (FIX R8-C1) */
    fun addBody(body: RigidBody): RigidBody {
        pendingMutations.add(BodyMutation.Add(body))
        return body
    }

    /** Enqueue body removal — applied at start of next step(). Thread-safe. (FIX R8-C1) */
    fun removeBody(body: RigidBody) {
        pendingMutations.add(BodyMutation.Remove(body))
    }

    /** Drain pending mutations into body collections. Called at start of step() only. (FIX R8-C1) */
    private fun drainPendingMutations() {
        while (true) {
            val mutation = pendingMutations.poll() ?: break
            when (mutation) {
                is BodyMutation.Add -> {
                    bodiesById[mutation.body.id] = mutation.body
                    bodiesList.add(mutation.body)
                }
                is BodyMutation.Remove -> {
                    bodiesById.remove(mutation.body.id)
                    bodiesList.remove(mutation.body)
                }
            }
        }
    }

    // WARNING: These query functions read from unsynchronized collections that are
    // mutated on the physics thread (via drainPendingMutations at the start of step()).
    // They must ONLY be called from the physics thread or when the simulation is paused.
    // For main-thread body lookup, use the published snapshot via LocalPhysicsSnapshot
    // or PhysicsBodyRef. This is consistent with standard physics engine practice
    // (e.g., Box2D's b2World::GetBodyList() is not thread-safe). (FIX R10-S1)
    fun findByTag(tag: String): RigidBody?
    fun findById(id: Int): RigidBody?
    val bodyCount: Int get() = bodiesList.size
    val activeBodies: List<RigidBody> get() = bodiesList.filter { !it.isSleeping }

    var isPaused: Boolean = false
    var timeScale: Double = 1.0

    /** Deterministic single step. Contact order sorted by body ID pair. */
    fun step(dt: Double)

    // Pluggable subsystems — start as no-ops
    internal var broadPhase: BroadPhase = NullBroadPhase()
    internal var narrowPhase: NarrowPhase = NullNarrowPhase()
    internal var solver: ConstraintSolver = NullSolver()
    internal var islandManager: IslandManager = IslandManager()
    internal var eventDispatcher: EventDispatcher = EventDispatcher()

    // Force fields
    private val forceFields = mutableListOf<ForceField>()
    fun addForceField(field: ForceField)
    fun removeForceField(field: ForceField)

    // Profiling
    var lastStepTimeNanos: Long = 0L; internal set
    var lastBroadPhaseTimeNanos: Long = 0L; internal set
    var lastNarrowPhaseTimeNanos: Long = 0L; internal set
    var lastSolverTimeNanos: Long = 0L; internal set
}

data class PhysicsConfig(
    /** Single canonical gravity default. No Vector.GRAVITY constant — one source of truth. (FIX R4-I3)
     *  Named args for clarity: Vector fields are i/j/k, not x/y/z. (FIX R5-S3) */
    val gravity: Vector = Vector(i = 0.0, j = 0.0, k = -9.81),
    val fixedTimestep: Double = 1.0 / 60.0,
    val maxSubSteps: Int = 8,
    val solverIterations: Int = 10,
    val allowSleeping: Boolean = true,
    val sleepLinearThreshold: Double = 0.01,
    val sleepAngularThreshold: Double = 0.05,
    val sleepTimeThreshold: Double = 0.5,
    val bounds: PhysicsBounds? = null
)
```

**`PhysicsStep.kt`** — Fixed timestep + interpolation on background thread (FIX C2)

```kotlin
class PhysicsStep(private val world: PhysicsWorld) {
    private var accumulator: Double = 0.0
    private val maxFrameTime: Double = 0.25

    // Interpolation state — stored here, published as snapshot (FIX C2)
    private val previousState = LinkedHashMap<Int, BodySnapshot>()   // Int body IDs (FIX R2-S1)
    private val currentState = LinkedHashMap<Int, BodySnapshot>()
    private var currentAlpha: Double = 0.0

    /** All reads via PhysicsBodyRef come from this snapshot — temporally consistent. (FIX R4-S3, R8-S1) */
    data class BodySnapshot(
        val position: Point,
        val orientation: Quaternion,
        val velocity: Vector = Vector.ZERO,          // included for temporal consistency (FIX R4-S3)
        val angularVelocity: Vector = Vector.ZERO,   // reads at same instant as position
        val isSleeping: Boolean = false               // avoids cross-thread read of live body state (FIX R8-S1)
    )

    /** Called on physics thread each render frame */
    fun update(frameDeltaSeconds: Double) {
        if (world.isPaused) return
        val scaledDelta = frameDeltaSeconds * world.timeScale
        accumulator += minOf(scaledDelta, maxFrameTime)

        while (accumulator >= world.config.fixedTimestep) {
            savePreviousState()
            world.step(world.config.fixedTimestep)
            saveCurrentState()
            accumulator -= world.config.fixedTimestep
        }
        currentAlpha = accumulator / world.config.fixedTimestep
    }

    /**
     * Produce a snapshot of interpolated positions for the main thread.
     * Called on the physics thread AFTER update(), published via AtomicReference.
     * The main thread reads this — no shared mutable state. (FIX C2)
     */
    fun computeInterpolatedSnapshot(): Map<Int, BodySnapshot> {
        val result = LinkedHashMap<Int, BodySnapshot>(currentState.size)
        for ((id, curr) in currentState) {
            val prev = previousState[id] ?: curr
            result[id] = BodySnapshot(
                position = Point(
                    prev.position.x + (curr.position.x - prev.position.x) * currentAlpha,
                    prev.position.y + (curr.position.y - prev.position.y) * currentAlpha,
                    prev.position.z + (curr.position.z - prev.position.z) * currentAlpha
                ),
                orientation = Quaternion.slerp(prev.orientation, curr.orientation, currentAlpha),  // FIX R2-S8
                velocity = curr.velocity,           // not interpolated, but temporally consistent
                angularVelocity = curr.angularVelocity,  // with position snapshot (FIX R4-S3)
                isSleeping = curr.isSleeping         // from same physics step as above (FIX R8-S1)
            )
        }
        return result
    }
}
```

### 1.4 Background Thread (JVM — no Android dependency) (FIX I3)

```kotlin
class PhysicsThread(
    private val world: PhysicsWorld,
    private val step: PhysicsStep
) {
    // JVM threading — no Android dependency (FIX I3)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "IsometricPhysics").apply { isDaemon = true }
    }

    // Published snapshot — main thread reads, physics thread writes (FIX C2)
    private val publishedSnapshot = AtomicReference<Map<Int, PhysicsStep.BodySnapshot>>(emptyMap())

    @Volatile private var lastStepNanos = System.nanoTime()

    fun start() {
        lastStepNanos = System.nanoTime()
        // scheduleWithFixedDelay (not scheduleAtFixedRate) prevents runaway simulation
        // when a step takes longer than 16ms — guarantees 16ms gap between completions. (FIX R3-S8)
        // NOTE: Effective rate is ~55 Hz (16ms delay + ~2ms step = 18ms period), not 60 Hz.
        // This is acceptable for JVM-only tests — the fixed timestep accumulator handles
        // variable input deltas correctly. Android uses Choreographer (true 60 Hz). (FIX R4-S4)
        executor.scheduleWithFixedDelay({
            val now = System.nanoTime()
            val deltaSec = (now - lastStepNanos) / 1_000_000_000.0
            lastStepNanos = now
            step.update(deltaSec)
            publishedSnapshot.set(step.computeInterpolatedSnapshot())
        }, 0, 16L, TimeUnit.MILLISECONDS)  // 60 Hz ≈ 16ms (FIX R2-C5)
    }

    fun stop() { executor.shutdownNow() }

    /** Main thread reads interpolated positions — lock-free */
    fun readSnapshot(): Map<Int, PhysicsStep.BodySnapshot> = publishedSnapshot.get()
}
```

### 1.5 Inertia Computation (uses Phase 0 stored dimensions)

```kotlin
object InertiaCalculator {
    fun compute(shape: Shape, mass: Double): Matrix3x3 = when (shape) {
        is Prism -> boxInertia(mass, shape.dx, shape.dy, shape.dz)       // Phase 0 fields
        is Pyramid -> pyramidInertia(mass, shape.dx, shape.dy, shape.dz)
        is Cylinder -> cylinderInertia(mass, shape.radius, shape.height)
        is Octahedron -> octahedronInertia(mass, shape.xyScale, shape.zScale)  // FIX R3-I2
        is Stairs -> boxInertia(mass, shape.width, shape.depth, shape.height)
        is Knot -> boxInertia(mass, 1.0, 1.0, 1.0)     // approximate bounding box
        else -> {
            // Fallback: compute bounding box from actual vertices (FIX R2-I3)
            val aabb = AABB.fromPoints(shape.paths.flatMap { it.points })
            boxInertia(mass,
                aabb.maxX - aabb.minX,
                aabb.maxY - aabb.minY,
                aabb.maxZ - aabb.minZ
            )
        }
    }

    fun boxInertia(mass: Double, w: Double, h: Double, d: Double): Matrix3x3 { /* ... */ }
    fun cylinderInertia(mass: Double, radius: Double, height: Double): Matrix3x3 { /* ... */ }
    fun pyramidInertia(mass: Double, baseW: Double, baseD: Double, h: Double): Matrix3x3 { /* ... */ }
    /** Uses actual Octahedron vertex positions (non-uniform XY/Z scale) (FIX R3-I2) */
    fun octahedronInertia(mass: Double, xyScale: Double, zScale: Double): Matrix3x3 { /* ... */ }
}
```

### 1.6 Test Harness

```kotlin
class TestPhysicsWorld(
    gravity: Vector = Vector(i = 0.0, j = 0.0, k = -9.81),  // named args for clarity (FIX R5-S3)
    config: PhysicsConfig = PhysicsConfig(gravity = gravity)
) {
    val world = PhysicsWorld(config)

    /**
     * Create a dynamic body at the given position. (FIX R5-C1)
     * NOTE: The `shape` default uses Point.ORIGIN, NOT `pos`. The body's world position
     * is set via RigidBody.position, not embedded in shape vertices. RigidBody.create()
     * extracts the centroid and origin-centers the shape automatically.
     */
    fun addDynamic(pos: Point, mass: Double = 1.0,
                   shape: Shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
                   material: PhysicsMaterial = PhysicsMaterial.Default): RigidBody {
        val config = BodyConfig(type = BodyType.Dynamic(mass), material = material)
        val (body, _) = RigidBody.create(shape, config)
        body.position = pos  // override centroid-derived position with explicit pos
        world.addBody(body)
        return body
    }

    fun addStatic(pos: Point, shape: Shape = Prism(Point.ORIGIN, 10.0, 10.0, 0.1)): RigidBody {
        val config = BodyConfig(type = BodyType.Static)
        val (body, _) = RigidBody.create(shape, config)
        body.position = pos
        world.addBody(body)
        return body
    }

    fun addStaticGround(): RigidBody = addStatic(Point(0.0, 0.0, -0.05))
    fun stepN(n: Int)
    fun stepUntil(maxSteps: Int = 600, predicate: (PhysicsWorld) -> Boolean): Int
}
```

### 1.7 Gradle Setup

```kotlin
// isometric-physics-core/build.gradle.kts (FIX C3)
// NOTE: No kotlinx-coroutines dependency. EventDispatcher uses callbacks. (FIX R2-C4)
plugins {
    id("java-library")
    kotlin("jvm")
}

// Use Java 17 toolchain for compilation (can read isometric-core's Java 17 class files).
// Emit Java 11 bytecode for Android consumer compatibility. (FIX R3-I7, R5-I4)
// NOTE: Java 11 toolchain would FAIL here — isometric-core emits Java 17 bytecode (class file
// version 61) and a Java 11 JDK cannot read it. jvmToolchain(17) + jvmTarget("11") solves both.
kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    api(project(":isometric-core"))
    testImplementation(kotlin("test"))
    testImplementation("com.google.truth:truth:1.4.2")
}
```

### Phase 1 Tests

```
math/AABBTest.kt         — intersects, fromPrism, fromCylinder, merged, expanded, contains
math/QuaternionTest.kt   — identity, normalize, multiply, fromAxisAngle, integrate, toMatrix roundtrip,
                            slerp(a, b, 0) = a, slerp(a, b, 1) = b, slerp midpoint (FIX R2-S8)
math/Matrix3x3Test.kt    — multiply vector/matrix, transpose, inverse, determinant

integration/GravityTest.kt
  - body falls: stepN(60) → z ≈ origin - 0.5*g*1²
  - static body: stepN(60) → no movement
  - zero gravity: velocity maintained
  - per-body gravityScale: 0 = floats, 2 = double fall
  - time scale 0.5x → half distance
  - pause → no movement

integration/DeterminismTest.kt
  - run same scenario twice → identical positions (FIX C5)
  - different frame deltas, same total time → same final state
  - LinkedHashMap iteration order is consistent
  - Integer body IDs are unique and monotonically increasing (FIX R2-S1)

core/RigidBodyCreateTest.kt (FIX R5-I1, R5-C1)
  - RigidBody.create(Prism(Point(5,3,0), 1,1,1), config) → body.position = centroid (5.5, 3.5, 0.5)
  - Returned baseShape centroid is at (0,0,0) ± epsilon
  - Static body → inverseMass = 0.0, localInverseInertia = ZERO
  - Dynamic body → inverseMass = 1/mass, collider derived from shape
  - colliderOverride in config → uses override, not auto-derivation
  - create(config) without shape → default unit box collider
```

**Phase 1 is complete when**: Bodies fall under gravity with reproducible stepping. `TestPhysicsWorld.stepN()` produces identical results across runs. Background thread publishes interpolated snapshots.

---

## Phase 2: Collision Detection Pipeline

**Goal**: Bodies detect collisions. GJK for convex shapes, compound collider decomposition for non-convex shapes (Stairs, Knot). Spatial hash grid with incremental updates. Headless debug dump for diagnosing collision issues.

**Depends on**: Phase 1

**Deliverable**: `world.step()` populates a contact list. Headless SVG dump visualizes contacts.

### 2.1 Broad Phase (incremental) (FIX S3)

```kotlin
interface BroadPhase {
    fun update(bodies: List<RigidBody>)
    fun computePairs(): List<BodyPair>
    fun query(aabb: AABB): List<RigidBody>
    fun queryRay(ray: Ray, maxDistance: Double = Double.MAX_VALUE): List<RigidBody>
    val pairCount: Int
}

data class BodyPair(val a: RigidBody, val b: RigidBody) {
    /** Deterministic key for contact caching: sorted by integer body ID.
     *  Uses BodyPairKey.of() — the SAME function used by ContactManager. (FIX R2-C3) */
    val key: Long = BodyPairKey.of(a.id, b.id)
}

/**
 * Single canonical pair key function used everywhere: BodyPair, ContactManager,
 * warm starting lookup. No ambiguity, no hash collisions. (FIX R2-C3)
 */
object BodyPairKey {
    /** Pack two Int body IDs into a single Long. Smaller ID in high bits, larger in low. (FIX R3-C4)
     *  Both operands masked to avoid sign-extension issues if IDs ever wrap negative. */
    fun of(idA: Int, idB: Int): Long {
        val smallId = minOf(idA, idB).toLong() and 0xFFFFFFFFL
        val largeId = maxOf(idA, idB).toLong() and 0xFFFFFFFFL
        return (smallId shl 32) or largeId
    }
}

class SpatialHashGrid3D(
    private val cellSize: Double = 2.0,
    initialCapacity: Int = 256
) : BroadPhase {
    // LinkedHashMap for deterministic iteration (FIX C5)
    private val cells = LinkedHashMap<Long, MutableList<RigidBody>>(initialCapacity)
    private val movedBodies = mutableSetOf<Int>()

    override fun update(bodies: List<RigidBody>) {
        // Incremental: only re-insert bodies that moved (FIX S3)
        // Sleeping bodies stay in their cells
        for (body in bodies) {
            if (body.isSleeping && body.id !in movedBodies) continue
            removeFromCells(body)
            insertIntoCells(body, body.aabb)
        }
        movedBodies.clear()
    }

    fun markMoved(bodyId: Int) { movedBodies.add(bodyId) }

    override fun computePairs(): List<BodyPair> {
        // Iterate cells, test AABB overlap for bodies sharing a cell
        // Apply collision filter: skip if filters say no
        // Sort pairs by key for deterministic solver order (FIX C5)
        val pairs = /* ... */
        return pairs.sortedBy { it.key }
    }
}
```

### 2.2 Collider Hierarchy (FIX C1)

```kotlin
/**
 * Collider interface — shared by both convex and compound types.
 * No wrapper layer: ConvexCollider and CompoundCollider implement this directly.
 * Narrow phase uses `is` checks to branch. (FIX R2-S2)
 */
interface Collider {
    fun computeAABB(position: Point, orientation: Quaternion): AABB
}

/** Convex collider — GJK/EPA compatible */
interface ConvexCollider : Collider {
    fun support(direction: Vector): Point
}

/**
 * Compound collider for non-convex shapes (Stairs, Knot).
 * NOT a ConvexCollider. Narrow phase branches on this type. (FIX C1)
 */
class CompoundCollider(
    val children: List<ChildCollider>
) : Collider {
    data class ChildCollider(
        val convex: ConvexCollider,
        val localPosition: Point = Point.ORIGIN,
        val localOrientation: Quaternion = Quaternion.IDENTITY
    )

    override fun computeAABB(position: Point, orientation: Quaternion): AABB {
        // Union of all children's AABBs
    }

    /**
     * Return a new CompoundCollider with all child positions shifted by the given offset.
     * Used by ColliderFactory to align compound collider positions with the origin-centered
     * baseShape produced by RigidBody.create(). (FIX R8-I1)
     */
    fun translate(dx: Double, dy: Double, dz: Double): CompoundCollider {
        return CompoundCollider(children.map { child ->
            child.copy(localPosition = Point(
                child.localPosition.x + dx,
                child.localPosition.y + dy,
                child.localPosition.z + dz
            ))
        })
    }
}
```

**Per-shape colliders**:

```kotlin
// Convex shapes — direct support function
class PrismCollider(val halfExtents: Vector, val center: Point) : ConvexCollider { /* ... */ }
class CylinderCollider(val radius: Double, val halfHeight: Double, val center: Point) : ConvexCollider { /* ... */ }
class PyramidCollider(val vertices: Array<Point>) : ConvexCollider { /* vertex enumeration */ }
class OctahedronCollider(val vertices: Array<Point>) : ConvexCollider { /* vertex enumeration */ }

/**
 * Sphere collider with analytic support function. (FIX R7-I3)
 * More accurate than approximating with OctahedronCollider — a sphere's support
 * function is trivial: support(dir) = center + normalize(dir) * radius.
 * This produces exact GJK results for sphere-vs-anything without vertex enumeration.
 */
class SphereCollider(val radius: Double, val center: Point) : ConvexCollider {
    override fun support(direction: Vector): Point {
        val mag = direction.magnitude()
        if (mag < 1e-10) return center
        val scale = radius / mag
        return Point(
            center.x + direction.i * scale,
            center.y + direction.j * scale,
            center.z + direction.k * scale
        )
    }
}

// Non-convex shapes — compound decomposition (FIX C1)
object StairsCompoundCollider {
    /**
     * Decompose stairs into per-step prism colliders. (FIX R3-I3)
     *
     * Actual Stairs.kt geometry: each step progresses along Y (depth) AND Z (height).
     * Step i: corner at (origin.x, origin.y + i/n, origin.z + (i+1)/n)
     *   - X extent: 1.0 (full staircase width)
     *   - Y extent: 1/n (one step's depth)
     *   - Z extent: 1/n (one step's height — from step top down to its base)
     *
     * This is a prism-per-tread approximation. The vertical riser faces between
     * steps are not explicitly modeled but are covered by the adjacent step's AABB.
     */
    fun create(origin: Point, stepCount: Int): CompoundCollider {
        val n = stepCount.toDouble()
        val children = (0 until stepCount).map { i ->
            val stepSize = 1.0 / n
            // Step center: X centered at origin + 0.5, Y at step midpoint, Z at step midpoint
            val stepCenter = Point(
                origin.x + 0.5,
                origin.y + (i + 0.5) / n,
                origin.z + (i + 1) / n - stepSize / 2   // center of step's Z extent
            )
            CompoundCollider.ChildCollider(
                convex = PrismCollider(
                    halfExtents = Vector(0.5, stepSize / 2, stepSize / 2),  // X=0.5, Y=1/(2n), Z=1/(2n)
                    center = Point.ORIGIN
                ),
                localPosition = stepCenter
            )
        }
        return CompoundCollider(children)
    }
}

object KnotCompoundCollider {
    /**
     * Decompose knot into 3 prism colliders matching actual Knot.kt geometry. (FIX R4-I4)
     *
     * Knot.kt creates 3 prisms at hardcoded positions, adds 2 quadrilateral custom paths,
     * then scales everything by 1/5 around Point.ORIGIN, translates by (-0.1, 0.15, 0.4),
     * and finally by the user-supplied origin.
     *
     * The 2 custom quadrilateral paths (lines 23-38 of Knot.kt, 4 vertices each) are NOT
     * prisms. They are small decorative faces that fill gaps between the 3 main prism
     * segments. These quad faces are NOT covered by the compound collider — they are
     * decorative detail too small to matter for gameplay-level collision (~0.2 units after
     * 1/5 scaling). The prism colliders use exact dimensions matching the 3 main visual
     * prism segments. This is an intentional simplification. (FIX R5-S1, R11-S1)
     *
     * Pre-scaled prism positions (before 1/5 scale):
     *   Prism 1: Point.ORIGIN,        size (5, 1, 1)
     *   Prism 2: Point(4, 1, 0),      size (1, 4, 1)
     *   Prism 3: Point(4, 4, -2),     size (1, 1, 3)
     *
     * After 1/5 scale + offset (-0.1, 0.15, 0.4) + origin:
     */
    fun create(origin: Point): CompoundCollider {
        val scale = 1.0 / 5.0
        val offsetX = -0.1
        val offsetY = 0.15
        val offsetZ = 0.4

        // Pre-scaled prism definitions: (corner, sizeX, sizeY, sizeZ)
        val prisms = listOf(
            Triple(Point(0.0, 0.0, 0.0), Triple(5.0, 1.0, 1.0), Triple(0.0, 0.0, 0.0)),  // Prism 1
            Triple(Point(4.0, 1.0, 0.0), Triple(1.0, 4.0, 1.0), Triple(0.0, 0.0, 0.0)),  // Prism 2
            Triple(Point(4.0, 4.0, -2.0), Triple(1.0, 1.0, 3.0), Triple(0.0, 0.0, 0.0))  // Prism 3
        )

        val children = prisms.map { (corner, size, _) ->
            val (sx, sy, sz) = size
            // Center of the prism in pre-scaled space
            val cx = corner.x + sx / 2.0
            val cy = corner.y + sy / 2.0
            val cz = corner.z + sz / 2.0
            // Apply 1/5 scale, then offset, then origin
            val finalCenter = Point(
                cx * scale + offsetX + origin.x,
                cy * scale + offsetY + origin.y,
                cz * scale + offsetZ + origin.z
            )
            CompoundCollider.ChildCollider(
                convex = PrismCollider(
                    halfExtents = Vector(sx * scale / 2.0, sy * scale / 2.0, sz * scale / 2.0),
                    center = Point.ORIGIN
                ),
                localPosition = finalCenter
            )
        }
        return CompoundCollider(children)
    }
}
```

### 2.3 Narrow Phase (FIX C1 — branches on compound)

```kotlin
interface NarrowPhase {
    fun detectCollisions(pairs: List<BodyPair>): List<ContactManifold>
}

class GjkEpaNarrowPhase : NarrowPhase {
    private val gjk = GjkDetector()
    private val epa = EpaResolver()

    override fun detectCollisions(pairs: List<BodyPair>): List<ContactManifold> {
        val manifolds = mutableListOf<ContactManifold>()
        for (pair in pairs) {
            val colliderA = pair.a.collider
            val colliderB = pair.b.collider
            when {
                // Both convex: single GJK + EPA (FIX R2-S2: no wrapper unwrap needed)
                colliderA is ConvexCollider && colliderB is ConvexCollider ->
                    detectConvexVsConvex(pair.a, colliderA, pair.b, colliderB, manifolds)

                // One or both compound: test all sub-collider pairs (FIX C1)
                colliderA is CompoundCollider && colliderB is ConvexCollider ->
                    for (child in colliderA.children)
                        detectConvexVsConvex(pair.a, child.transformedCollider(pair.a), pair.b, colliderB, manifolds)

                colliderA is ConvexCollider && colliderB is CompoundCollider ->
                    for (child in colliderB.children)
                        detectConvexVsConvex(pair.a, colliderA, pair.b, child.transformedCollider(pair.b), manifolds)

                colliderA is CompoundCollider && colliderB is CompoundCollider ->
                    for (childA in colliderA.children)
                        for (childB in colliderB.children)
                            detectConvexVsConvex(pair.a, childA.transformedCollider(pair.a), pair.b, childB.transformedCollider(pair.b), manifolds)
            }
        }
        return manifolds
    }
}
```

### 2.4 GJK + EPA + Contact Clipping

Same algorithms as original plan — `GjkDetector` and `EpaResolver` with deterministic initial direction `Vector(1, 0, 0)` and max 64 iterations.

**Contact clipping (Sutherland-Hodgman)**: GJK/EPA produces a single contact point (deepest penetration). For stable stacking, the solver needs up to 4 contact points per manifold. After EPA returns the contact normal and depth, **Sutherland-Hodgman clipping** generates the full contact manifold: (FIX R2-S6)

1. Identify the **reference face** (face most aligned with contact normal) on body A
2. Identify the **incident face** (face most anti-aligned with normal) on body B
3. Clip the incident face polygon against the reference face's side planes
4. Keep clipped points that are behind the reference face plane
5. These become the manifold's contact points (up to 4 for box-box, fewer for other shapes)

```kotlin
class ContactClipper {
    /** Clip incident face against reference face to produce up to 4 contact points */
    fun clip(
        refFace: FaceData, incFace: FaceData,
        normal: Vector, penetration: Double
    ): List<ClippedPoint>

    /** Sutherland-Hodgman: clip polygon against a single plane */
    private fun clipPolygonAgainstPlane(
        vertices: List<Point>, planeNormal: Vector, planeOffset: Double
    ): List<Point>
}

data class FaceData(val vertices: List<Point>, val normal: Vector)
data class ClippedPoint(val position: Point, val penetration: Double)
```

> **Note**: For first implementation, single-point manifolds from EPA alone will work for basic collision response. Contact clipping can be added iteratively when stacking stability needs improvement. The 4-point `ContactManifold.points` array is sized for the clipping output.

### 2.5 Contact Classes (mutable + pooled) (FIX S5)

```kotlin
/** Mutable — reused across frames via pool. NOT a data class. (FIX S5) */
class ContactManifold {
    var bodyA: RigidBody? = null
    var bodyB: RigidBody? = null
    var normal: Vector = Vector.ZERO
    var pointCount: Int = 0
    val points: Array<ContactPoint> = Array(4) { ContactPoint() }
    var isNew: Boolean = true

    fun reset() { bodyA = null; bodyB = null; pointCount = 0; isNew = true }
}

class ContactPoint {
    var position: Point = Point.ORIGIN
    var penetration: Double = 0.0
    var localPointA: Point = Point.ORIGIN
    var localPointB: Point = Point.ORIGIN
    var normalImpulse: Double = 0.0       // Accumulated (warm starting)
    var tangentImpulse1: Double = 0.0
    var tangentImpulse2: Double = 0.0

    /** Reset ALL fields — geometric data too, not just impulses. (FIX R3-S6)
     *  Prevents stale data from a previous collision pair leaking through. */
    fun reset() {
        position = Point.ORIGIN; penetration = 0.0
        localPointA = Point.ORIGIN; localPointB = Point.ORIGIN
        normalImpulse = 0.0; tangentImpulse1 = 0.0; tangentImpulse2 = 0.0
    }
}

/**
 * Manifold pool lifecycle (FIX R3-I6):
 * 1. Narrow phase calls pool.acquire() for each detected collision → manifold is "active"
 * 2. ContactManager.update() receives new manifolds, copies warm-start impulses from
 *    previously cached manifolds into the new ones, then stores the new set as active
 * 3. ContactManager.prune() releases manifolds that are no longer active (pair disappeared)
 *    back to the pool via pool.release()
 * 4. A manifold is NEVER released while it is still referenced by the ContactManager.
 *    The ContactManager owns active manifolds; the pool owns idle manifolds.
 */
class ContactPool(capacity: Int = 256) {
    private val pool = ArrayDeque<ContactManifold>(capacity)
    init { repeat(capacity) { pool.add(ContactManifold()) } }
    fun acquire(): ContactManifold = pool.removeFirstOrNull()?.apply { reset() } ?: ContactManifold()
    fun release(manifold: ContactManifold) { manifold.reset(); pool.addLast(manifold) }
}
```

### 2.6 Contact Manager (deterministic) (FIX C5)

```kotlin
class ContactManager {
    // LinkedHashMap for deterministic iteration (FIX C5)
    private val manifolds = LinkedHashMap<Long, ContactManifold>()

    fun update(newManifolds: List<ContactManifold>): List<ContactManifold> {
        // Match new contacts against cached manifolds for warm starting
        // Copy accumulated impulses from cached → new for matching contact points
    }

    fun prune(activePairKeys: Set<Long>)

    /** Uses the same BodyPairKey.of() as BodyPair — single canonical function. (FIX R2-C3) */
    private fun pairKey(a: RigidBody, b: RigidBody): Long = BodyPairKey.of(a.id, b.id)
}
```

### 2.7 Headless Debug Dump (FIX S1)

```kotlin
/** Text/SVG dump for debugging collision without a visual renderer */
object PhysicsDebugDump {
    /** Dump world state to text — body positions, AABBs, contacts */
    fun toText(world: PhysicsWorld, manifolds: List<ContactManifold>): String

    /** Dump to SVG — top-down (XY) and side (XZ) views of AABBs and contacts */
    fun toSvg(world: PhysicsWorld, manifolds: List<ContactManifold>,
              width: Int = 800, height: Int = 600): String
}

data class PhysicsSnapshot(
    val bodies: List<BodyState>,
    val contacts: List<ContactState>,
    val stepNumber: Int
) {
    data class BodyState(val id: Int, val position: Point, val velocity: Vector,
                         val aabb: AABB, val isSleeping: Boolean)
    data class ContactState(val bodyAId: Int, val bodyBId: Int,
                            val normal: Vector, val depth: Double, val point: Point)
}
```

### Phase 2 Tests

```
collision/GjkDetectorTest.kt
  - Separated prisms → no intersection
  - Overlapping prisms → intersection
  - Prism vs Cylinder, Prism vs Pyramid, Prism vs Octahedron
  - Touching faces → intersection
  - Determinism: identical inputs → identical simplex

collision/EpaResolverTest.kt
  - Overlapping prisms → correct normal and depth
  - Partial/deep overlap → correct penetration vector

collision/CompoundColliderTest.kt (FIX C1)
  - Stairs vs Prism: ball resting on a step → contact on step surface
  - Stairs vs Prism: ball in concavity → contact with inner step corner
  - Knot vs Prism: collision with each knot segment independently
  - Compound vs Compound: stairs vs stairs

collision/SpatialHashGrid3DTest.kt
  - 100 bodies → correct pair count
  - Separated → zero pairs
  - Incremental: sleeping bodies not re-inserted (FIX S3)
  - Pair order deterministic across runs (FIX C5)

collision/ContactManagerTest.kt
  - Warm starting: accumulated impulses carry over between frames
  - Pruning: stale manifolds removed
  - Deterministic manifold ordering (FIX C5)
  - BodyPair.key == ContactManager.pairKey for same body pair (FIX R2-C3)
```

**Phase 2 is complete when**: GJK + EPA detect collisions between all shape types including non-convex Stairs/Knot via compound decomposition. `PhysicsDebugDump.toSvg()` visualizes contacts. Contact ordering is deterministic.

---

## Phase 3: Collision Response + Solver

**Goal**: Bodies physically interact. Sequential impulse solver with warm starting. Island-based sleep. Stable stacking.

**Depends on**: Phase 2

**Deliverable**: 10-box tower stacks stably. Different materials produce different behavior. Sleep system reduces active body count via islands.

### 3.1 Sequential Impulse Solver

```kotlin
interface ConstraintSolver {
    fun solve(manifolds: List<ContactManifold>, dt: Double, iterations: Int)
}

class SequentialImpulseSolver : ConstraintSolver {
    override fun solve(manifolds: List<ContactManifold>, dt: Double, iterations: Int) {
        prepareConstraints(manifolds, dt)
        warmStart(manifolds)
        repeat(iterations) {
            for (manifold in manifolds) {
                for (i in 0 until manifold.pointCount) {
                    solveNormalConstraint(manifold, manifold.points[i], dt)
                    solveFrictionConstraint(manifold, manifold.points[i])
                }
            }
        }
    }

    private fun computeBias(contact: ContactPoint, dt: Double): Double {
        val baumgarte = 0.2
        val slop = 0.005
        val restitutionBias = computeRestitutionBias(contact)
        val positionBias = (baumgarte / dt) * maxOf(contact.penetration - slop, 0.0)
        return maxOf(restitutionBias, positionBias)
    }
}
```

### 3.2 Full World Step (FIX I4 — island-aware sleep)

```kotlin
// PhysicsWorld.step()
fun step(dt: Double) {
    val startNanos = System.nanoTime()

    // 0. Drain deferred body additions/removals from main thread (FIX R8-C1)
    // This is the ONLY place body collections are mutated, ensuring no concurrent
    // modification during broadphase/solver/integration iterations below.
    drainPendingMutations()

    // 1a. Clear previous-step forces, then drain main-thread force/impulse/torque requests (FIX R12-I1)
    // clearForces() zeroes force/torque accumulators. drainPendingForces() then applies
    // queued requests from the main thread. This ordering eliminates the race where
    // applyForce's read-modify-write could interleave with clearForces.
    clearForces()
    for (body in bodiesList) { body.drainPendingForces() }

    // 1b. Apply forces (gravity * gravityScale + force fields)
    applyForces(dt)

    // 2. Integrate velocities (semi-implicit Euler)
    integrateVelocities(dt)

    // 3. Broad phase
    broadPhase.update(bodiesList)
    val pairs = broadPhase.computePairs()

    // 4. Narrow phase — GJK/EPA, compound branching (FIX C1)
    val manifolds = narrowPhase.detectCollisions(pairs)

    // 5. Match with cached manifolds (warm starting, deterministic order)
    val resolvedManifolds = contactManager.update(manifolds)

    // 6. Build islands BEFORE solver — enables per-island solving
    //    and skipping sleeping islands entirely. (FIX R2-S4)
    val islands = islandManager.buildIslands(bodiesList, resolvedManifolds)

    // 7. Solve constraints per island — skip sleeping islands (FIX R2-S4)
    for (island in islands) {
        if (island.isSleeping) continue  // skip entirely
        solver.solve(island.manifolds, dt, config.solverIterations)
    }

    // 8. Integrate positions
    integratePositions(dt)

    // 9. Update AABBs, mark moved bodies
    updateAABBsAndMarkMoved()

    // 10. Island-aware sleep (FIX I4)
    islandManager.updateSleep(islands, config, dt)

    // 11. Dispatch collision events
    eventDispatcher.dispatch(resolvedManifolds, previousManifoldKeys)

    // 12. Bounds enforcement
    enforceBounds()

    // NOTE: clearForces() moved to start of step (step 1a) — runs before drainPendingForces()
    // to ensure main-thread force requests are applied to a clean accumulator. (FIX R12-I1)

    lastStepTimeNanos = System.nanoTime() - startNanos
}
```

### 3.3 Island Manager (FIX I4)

```kotlin
class IslandManager {
    // Pre-allocated to expected capacity, grown as needed (FIX R2-S3)
    private var parent = IntArray(512)
    private var rank = IntArray(512)

    private fun ensureCapacity(size: Int) {
        if (size > parent.size) {
            parent = IntArray(size * 2)
            rank = IntArray(size * 2)
        }
    }

    fun buildIslands(bodies: List<RigidBody>, manifolds: List<ContactManifold>): List<Island> {
        ensureCapacity(bodies.size)
        // Union-Find: group bodies connected through contacts or joints
        // Return sorted list of islands for deterministic processing
    }

    fun updateSleep(islands: List<Island>, config: PhysicsConfig, dt: Double) {
        for (island in islands) {
            // An island sleeps only when ALL dynamic bodies are below velocity threshold (FIX I4)
            // NOTE: allowSleep=false bodies are NOT short-circuited here — their velocity
            // must be checked so a fast-moving allowSleep=false body prevents the entire
            // island from sleeping. The allowSleep flag only suppresses the body's own
            // sleep transition (handled in the application loop below via continue). (FIX R11-I1)
            val allBelowThreshold = island.bodies.all { body ->
                body.isStatic ||
                (body.velocity.magnitudeSquared() < config.sleepLinearThreshold *
                    config.sleepLinearThreshold &&
                 body.angularVelocity.magnitudeSquared() < config.sleepAngularThreshold *
                    config.sleepAngularThreshold)
            }
            if (allBelowThreshold) {
                for (body in island.bodies) {
                    if (body.isStatic || !body.config.allowSleep) continue  // FIX R7-S3
                    body.sleepTimer += dt
                    if (body.sleepTimer >= config.sleepTimeThreshold) {
                        body.isSleeping = true
                        body.velocity = Vector.ZERO
                        body.angularVelocity = Vector.ZERO
                    }
                }
            } else {
                // Wake entire island
                for (body in island.bodies) {
                    body.isSleeping = false
                    body.sleepTimer = 0.0
                }
            }
        }
    }

    /**
     * Island.isSleeping is a computed property — derived from body state, not a stored flag.
     * This ensures the solver always sees the correct sleep state without requiring
     * buildIslands to know about sleep or a separate "mark sleeping" pass. (FIX R3-I4)
     *
     * Regular class, NOT data class — computed isSleeping must appear in toString()
     * and data class would exclude it from equals/hashCode/toString. Islands are transient
     * per-step objects that are never equality-compared. (FIX R4-I5)
     */
    class Island(
        val bodies: List<RigidBody>,
        val manifolds: List<ContactManifold>
    ) {
        val isSleeping: Boolean get() = bodies.all { it.isSleeping || it.isStatic }
        override fun toString(): String = "Island(bodies=${bodies.size}, manifolds=${manifolds.size}, isSleeping=$isSleeping)"
    }
}
```

### Phase 3 Tests

```
solver/SequentialImpulseSolverTest.kt
  - Two colliding bodies → separate after solve
  - Ball on ground → bounce height matches restitution
  - Friction: body on slope → slides at correct angle
  - Warm starting: solve twice → lower residual

integration/StackingTest.kt
  - Stack 3 boxes → stable for 300 steps
  - Stack 5 boxes → stable for 600 steps
  - Stack 10 boxes → stable for 600 steps, max drift < 0.01

integration/BouncingTest.kt
  - Rubber ball: bounces 5+ times
  - Metal ball: bounces 1-2 times
  - Ice on slope: slides far
  - Stone on slope: slides short

integration/SleepTest.kt (FIX I4)
  - Resting body → island sleeps after threshold
  - Top of stack sleeps only when ENTIRE island is calm
  - Disturbing one body in island → entire island wakes
  - Static bodies never enter sleep system
  - Sleeping island skips solver entirely (FIX R2-S4)

integration/DepthSortNote.kt
  - Document: 500 bodies × 6 faces = 3000 paths → existing O(N²) sort
    is the bottleneck, not physics. Physics must work with partial re-sort
    (enableBroadPhaseSort=true) for 500-body target. (FIX S4)
```

**Phase 3 is complete when**: 10-box tower stacks stably for 10 seconds. Islands sleep correctly. Materials produce distinct behavior.

---

## Phase 4a: Events, Raycasting, Forces, CCD (Physics Core)

**Goal**: Complete the physics core with events, spatial queries, force fields, and CCD. Still pure JVM — no Compose.

**Depends on**: Phase 3

**Deliverable**: Collision events fire. Raycasting finds bodies. Force fields push objects. CCD prevents tunneling.

### 4a.1 Collision Events

```kotlin
sealed class CollisionEvent {
    abstract val self: RigidBody
    abstract val other: RigidBody
    abstract val contactPoint: Point
    abstract val normal: Vector
    abstract val impulse: Double

    class Begin(/* ... */) : CollisionEvent()
    class Stay(/* ... */) : CollisionEvent()
    class End(/* ... */) : CollisionEvent()
}

sealed class PhysicsEvent {
    data class BodySlept(val body: RigidBody) : PhysicsEvent()
    data class BodyWoke(val body: RigidBody) : PhysicsEvent()
    data class BodyRemoved(val body: RigidBody) : PhysicsEvent()
    data class BoundsExceeded(val body: RigidBody) : PhysicsEvent()
}

/**
 * Callback-based event dispatcher — no coroutines dependency. (FIX R2-C4)
 * Flow adapters are provided in the compose module (PhysicsEventFlow.kt).
 */
class EventDispatcher {
    // World-level callbacks (pure JVM, no coroutines)
    private val collisionListeners = mutableListOf<(CollisionEvent) -> Unit>()
    private val physicsListeners = mutableListOf<(PhysicsEvent) -> Unit>()

    // Per-body callbacks keyed by integer body ID (FIX R2-S1)
    private val perBodyCallbacks = LinkedHashMap<Int, (CollisionEvent) -> Unit>()

    /**
     * Returns a Disposable handle for removal. Lambda referential equality makes
     * remove-by-reference unreliable for inline lambdas — use the handle instead. (FIX R4-S2)
     *
     * Usage:
     *   val handle = dispatcher.addCollisionListener { event -> ... }
     *   handle.dispose()  // clean removal
     */
    fun addCollisionListener(listener: (CollisionEvent) -> Unit): Disposable {
        collisionListeners.add(listener)
        return Disposable { collisionListeners.remove(listener) }
    }
    fun addPhysicsListener(listener: (PhysicsEvent) -> Unit): Disposable {
        physicsListeners.add(listener)
        return Disposable { physicsListeners.remove(listener) }
    }

    fun interface Disposable { fun dispose() }

    fun registerCallback(bodyId: Int, callback: (CollisionEvent) -> Unit)
    fun unregisterCallback(bodyId: Int)

    internal fun dispatch(current: List<ContactManifold>, previousKeys: Set<Long>) {
        // Begin: in current but not previous
        // Stay: in both
        // End: in previous but not current
        // Notify world-level listeners and per-body callbacks
    }
}
```

### 4a.2 Raycasting

```kotlin
data class Ray(val origin: Point, val direction: Vector) {
    val invDirection = Vector(1.0 / direction.i, 1.0 / direction.j, 1.0 / direction.k)
    fun at(t: Double) = Point(origin.x + direction.i * t, origin.y + direction.j * t, origin.z + direction.k * t)
}

data class RaycastResult(val body: RigidBody, val distance: Double, val hitPoint: Point, val hitNormal: Vector)

// In PhysicsWorld (physics-core — no rendering dependency):
fun raycast(ray: Ray, maxDistance: Double = Double.MAX_VALUE, filter: CollisionFilter? = null): RaycastResult?
fun raycastAll(ray: Ray, maxDistance: Double = Double.MAX_VALUE, filter: CollisionFilter? = null): List<RaycastResult>

// screenToWorldRay lives in the compose module (PhysicsRaycastUtils.kt),
// NOT in PhysicsWorld — it requires IsometricEngine which is a rendering class. (FIX R2-S7)
```

### 4a.3 Force Fields

```kotlin
/**
 * Force fields are regular classes, NOT data classes — they may hold mutable
 * internal state (e.g., Wind.phase) which breaks data class equals/hashCode. (FIX R2-I8)
 */
sealed class ForceField {
    abstract fun apply(body: RigidBody, dt: Double)

    class Directional(val direction: Vector, val strength: Double) : ForceField() {
        // Uses applyForceInternal — force fields run on the physics thread during step(),
        // so direct mutation is safe and avoids the one-step delay of the queued API. (FIX R13-S1)
        override fun apply(body: RigidBody, dt: Double) { body.applyForceInternal(direction * strength) }
    }
    class Radial(val center: Point, val strength: Double,
                 val falloff: ForceFalloff = ForceFalloff.InverseSquare,
                 val maxDistance: Double = Double.MAX_VALUE) : ForceField() {
        override fun apply(body: RigidBody, dt: Double) { /* radial force toward/away from center */ }
    }
    class Wind(val direction: Vector, val strength: Double,
               val turbulence: Double = 0.0) : ForceField() {
        private var phase: Double = 0.0  // mutable internal state — safe in regular class
        override fun apply(body: RigidBody, dt: Double) { phase += dt; /* wind with turbulence */ }
    }
    class Vortex(val center: Point, val axis: Vector,
                 val strength: Double, val pullStrength: Double = 0.0) : ForceField() {
        override fun apply(body: RigidBody, dt: Double) { /* tangential + radial pull */ }
    }
}

enum class ForceFalloff { Constant, Linear, InverseSquare }
```

### 4a.4 CCD

```kotlin
class CcdSolver {
    fun solve(bulletBodies: List<RigidBody>, broadPhase: BroadPhase, dt: Double) {
        for (bullet in bulletBodies) {
            if (!bullet.config.isBullet) continue
            val swept = bullet.aabb.expanded(bullet.velocity.magnitude() * dt)
            val candidates = broadPhase.query(swept)
            for (candidate in candidates) {
                val toi = timeOfImpact(bullet, candidate, dt)
                if (toi != null && toi < dt) {
                    // Advance bullet to TOI, create contact, resolve
                }
            }
        }
    }
}
```

### Phase 4a Tests

```
event/EventDispatcherTest.kt       — Begin/Stay/End lifecycle, per-body callbacks, Flow emission
query/RaycastTest.kt               — hit/miss/nearest/filter (screenToWorldRay tested in compose module)
dynamics/ForceFieldTest.kt         — radial push, wind drift, vortex orbit, gravity scale
dynamics/CcdSolverTest.kt          — bullet through thin wall → detected, non-bullet → tunnels
```

**Phase 4a is complete when**: Events, raycasting, force fields, and CCD work in the pure JVM physics core with deterministic tests.

---

## Phase 4b: Compose Integration

**Goal**: `PhysicsScene { PhysicsShape() }` works. Custom `PhysicsShapeNode` integrates physics with rendering. Physics applies full 3D rotation to shape vertices directly.

**Depends on**: Phase 4a

**Deliverable**: A Compose app showing boxes falling, stacking, bouncing, and responding to taps.

### 4b.1 Gradle Setup (FIX C3)

```kotlin
// isometric-physics-compose/build.gradle.kts
plugins {
    id("com.android.library")
    kotlin("android")
}

dependencies {
    api(project(":isometric-physics-core"))
    implementation(project(":isometric-compose"))
    implementation("androidx.compose.runtime:runtime:1.5.0")
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.foundation:foundation:1.5.0")
}
```

### 4b.2 PhysicsShapeNode (FIX R2-I1, R3-C1, R3-C2)

```kotlin
/**
 * Extends IsometricNode directly — NOT ShapeNode. (FIX R2-I1)
 *
 * Must implement `abstract val children` from IsometricNode. (FIX R3-C1)
 * PhysicsShapeNode is a leaf — children list is always empty.
 *
 * Position sync model (FIX R2-I2): The main thread copies interpolated position
 * and orientation from the published snapshot into this node's fields. The render()
 * method reads ONLY from these local fields — never from RigidBody directly.
 *
 * GC note (FIX R3-C2): Point objects must be allocated for Path construction — the
 * existing Shape/Path API is immutable. The DoubleArray buffer was removed because it
 * provided zero benefit (values still had to be copied into Point objects). The real
 * GC optimization is the cache: sleeping bodies skip vertex transform entirely.
 * The actual rendering bottleneck is the O(N²) depth sort, not vertex allocation.
 */
class PhysicsShapeNode(
    var baseShape: Shape,         // ORIGIN-CENTERED shape geometry (centroid at 0,0,0) (FIX R5-C1)
    var color: IsoColor,
    val bodyId: Int               // integer body ID for snapshot lookup (FIX R2-S1)
) : IsometricNode() {

    // Required by IsometricNode — leaf node, always empty (FIX R3-C1)
    override val children: MutableList<IsometricNode> = mutableListOf()

    // Position/orientation written by main thread from snapshot (FIX R2-I2)
    var physicsPosition: Point = Point.ORIGIN
    var physicsOrientation: Quaternion = Quaternion.IDENTITY

    // Cache for sleeping bodies — skips vertex transform when position unchanged
    private var cachedShape: Shape? = null
    private var lastPosition: Point? = null
    private var lastOrientation: Quaternion? = null

    /**
     * Apply full 3D physics transform to shape vertices. (FIX I5, R5-C1)
     * For sleeping bodies (position unchanged), returns cached shape.
     *
     * IMPORTANT (FIX R5-C1): baseShape is ORIGIN-CENTERED — its centroid is at (0,0,0).
     * This is ensured by RigidBody.create() which translates the user's shape by -centroid.
     * Rotating origin-centered vertices around (0,0,0) correctly spins the shape in place.
     * Then translating by physicsPosition places it at the body's world position.
     *
     * If baseShape were NOT origin-centered (e.g., a Prism at (10,5,0)), rotation would
     * swing vertices around (0,0,0) — causing the shape to orbit across the scene.
     */
    override fun render(context: RenderContext): List<RenderCommand> {
        if (!isVisible) return emptyList()

        // Cache hit: sleeping body hasn't moved
        if (physicsPosition == lastPosition && physicsOrientation == lastOrientation && cachedShape != null) {
            return renderShape(cachedShape!!, color, context)
        }

        // Transform: rotate origin-centered vertices around (0,0,0), then translate to world position
        val rotMatrix = physicsOrientation.toMatrix()
        val transformedPaths = baseShape.paths.map { path ->
            Path(path.points.map { p ->
                // p is relative to origin (centroid at 0,0,0) — rotation is correct (FIX R5-C1)
                val rx = rotMatrix.m00 * p.x + rotMatrix.m01 * p.y + rotMatrix.m02 * p.z
                val ry = rotMatrix.m10 * p.x + rotMatrix.m11 * p.y + rotMatrix.m12 * p.z
                val rz = rotMatrix.m20 * p.x + rotMatrix.m21 * p.y + rotMatrix.m22 * p.z
                Point(physicsPosition.x + rx, physicsPosition.y + ry, physicsPosition.z + rz)
            })
        }

        val transformed = Shape(transformedPaths)
        cachedShape = transformed
        lastPosition = physicsPosition
        lastOrientation = physicsOrientation

        // DO NOT call context.applyTransformsToShape() — physics positions are world-space.
        // Applying the RenderContext accumulated transform would double-offset shapes inside
        // Group(position=...) nodes: physics already positioned at world coords, context
        // would shift by the parent group's offset again. (FIX R4-C2)
        return renderShape(transformed, color, context)
    }

    private fun renderShape(shape: Shape, color: IsoColor, context: RenderContext): List<RenderCommand> {
        // Produce render commands for each path — similar to ShapeNode but standalone.
        // NOTE: RenderContext is NOT used here — physics shapes are already in world-space
        // after physics rotation + translation. The IsometricEngine handles 3D-to-2D
        // isometric projection when the renderer calls engine.prepare(). (FIX R4-C2, R7-S1)
        // RenderCommand IDs use stable bodyId prefix for consistent hit-testing: (FIX R3-S7)
        //   id = "${bodyId}_${pathIndex}"
        // NOT path.hashCode() which changes every frame for active bodies.
    }
}
```

### 4b.3 Android Physics Thread (FIX I3, R3-S9)

```kotlin
/**
 * Android-specific physics loop driven by Choreographer. (FIX R3-S9)
 *
 * Unlike the JVM PhysicsThread which runs on an independent timer,
 * AndroidPhysicsThread is driven by the display's VSYNC via Choreographer.
 * This ensures physics steps are synchronized with render frames:
 * - One step per display frame (no 0 or 2 steps between Compose frames)
 * - The interpolation alpha matches the actual render timing
 * - No stale-by-16ms snapshot reads
 *
 * The physics step still runs on a background thread (via HandlerThread),
 * but it is TRIGGERED by Choreographer callbacks, not by a fixed-delay timer.
 */
class AndroidPhysicsThread(
    private val world: PhysicsWorld,
    private val step: PhysicsStep
) {
    private val handlerThread = android.os.HandlerThread("IsometricPhysics").apply { start() }
    private val handler = android.os.Handler(handlerThread.looper)
    private val publishedSnapshot = AtomicReference<Map<Int, PhysicsStep.BodySnapshot>>(emptyMap())
    @Volatile private var running = false
    @Volatile private var lastFrameNanos = 0L

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            handler.post {
                val deltaSec = if (lastFrameNanos == 0L) 1.0 / 60.0
                               else (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                lastFrameNanos = frameTimeNanos
                step.update(deltaSec)
                publishedSnapshot.set(step.computeInterpolatedSnapshot())
            }
            // Request next frame
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        running = true
        lastFrameNanos = 0L
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
        handlerThread.quitSafely()
    }

    fun readSnapshot(): Map<Int, PhysicsStep.BodySnapshot> = publishedSnapshot.get()
}
```

> **JVM fallback**: For pure JVM tests (Phase 1-4a), use `PhysicsThread` with `scheduleWithFixedDelay`. `AndroidPhysicsThread` is only used in the compose module on Android.

### 4b.3a CompositionLocals (FIX R6-C2)

```kotlin
// In CompositionLocals.kt (isometric-physics-compose)

/** Provides the PhysicsWorld to descendant composables. */
val LocalPhysicsWorld = compositionLocalOf<PhysicsWorld> {
    error("No PhysicsWorld provided — wrap content in PhysicsScene")
}

/**
 * Provides a snapshot accessor to descendant composables. (FIX R6-C2)
 * Used by rememberPhysicsBody to read interpolated position/velocity from the latest snapshot.
 *
 * staticCompositionLocalOf is appropriate because the function reference does not change,
 * even though the snapshot data it returns does. The function closes over `latestSnapshot`
 * state, which is updated each frame in PhysicsScene's LaunchedEffect.
 */
val LocalPhysicsSnapshot = staticCompositionLocalOf<() -> Map<Int, PhysicsStep.BodySnapshot>> {
    { emptyMap() }  // default: no snapshot available
}
```

### 4b.4 PhysicsScene

```kotlin
@Composable
fun PhysicsScene(
    modifier: Modifier = Modifier,
    physics: PhysicsConfig = PhysicsConfig(),
    renderOptions: RenderOptions = RenderOptions.Default,
    strokeWidth: Float = 1f,
    drawStroke: Boolean = true,
    lightDirection: Vector = IsometricEngine.DEFAULT_LIGHT_DIRECTION.normalize(),
    defaultColor: IsoColor = IsoColor(33.0, 150.0, 243.0),
    onTap: ((x: Double, y: Double, body: RigidBody?) -> Unit)? = null,
    content: @Composable PhysicsScope.() -> Unit
) {
    val world = remember { PhysicsWorld(physics) }
    val step = remember { PhysicsStep(world) }
    // NOTE: AndroidPhysicsThread uses android.os.HandlerThread + android.view.Choreographer.
    // This is Android-only — matches the module's com.android.library plugin type.
    // For JVM/desktop, use PhysicsThread (ScheduledExecutorService) instead. (FIX R7-S2)
    val physicsThread = remember { AndroidPhysicsThread(world, step) }
    // Store latest snapshot — read by sync loop AND provided to composables via LocalPhysicsSnapshot (FIX R6-S3)
    val latestSnapshot = remember { mutableStateOf<Map<Int, PhysicsStep.BodySnapshot>>(emptyMap()) }

    // Root node reference — captured via onRootNodeReady callback (FIX R4-C1)
    // rootNode is a private local variable inside IsometricScene (line 99 of IsometricScene.kt).
    // The content lambda receives IsometricScope (a marker interface) as `this`,
    // which has NO rootNode property. We capture it via callback instead.
    var rootNodeRef by remember { mutableStateOf<GroupNode?>(null) }

    // Engine reference — captured via onEngineReady callback (FIX R6-I3)
    // engine is a private local variable inside IsometricScene (line 100 of IsometricScene.kt).
    // PhysicsScene needs it for PhysicsRaycastUtils.screenToWorldRay().
    var engineRef by remember { mutableStateOf<IsometricEngine?>(null) }

    DisposableEffect(Unit) {
        physicsThread.start()
        onDispose { physicsThread.stop() }
    }

    // Sync physics positions to nodes each frame (FIX R2-I7)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { _ ->
                val snapshot = physicsThread.readSnapshot()
                if (snapshot.isNotEmpty()) {
                    latestSnapshot.value = snapshot
                    // Sync positions into node tree on main thread (FIX R4-C1)
                    rootNodeRef?.let { root ->
                        syncPositionsToNodes(root, snapshot)
                        // Invalidate the renderer's PreparedScene cache. (FIX R9-C1)
                        // Without this, IsometricRenderer.needsUpdate() sees isDirty=false
                        // and serves the stale cached PreparedScene — physics shapes appear frozen.
                        // markDirty() sets rootNode.isDirty=true, which triggers rebuildCache()
                        // on the next draw pass. It also fires onDirty → sceneVersion++,
                        // which invalidates the Compose Canvas. This makes frameVersion++
                        // redundant for physics-driven redraws. frameVersion was removed from
                        // PhysicsScene entirely — IsometricScene's default (0L) suffices. (FIX R9-S2, R11-S2)
                        root.markDirty()
                    }
                }
            }
        }
    }

    // Provide both PhysicsWorld and snapshot accessor to descendants (FIX R6-C2, R6-S3)
    // NOTE (FIX R9-S1): The lambda `{ latestSnapshot.value }` closes over `latestSnapshot`
    // (the MutableState container), NOT its current value. Each invocation reads .value fresh,
    // so callers always get the latest snapshot even though staticCompositionLocalOf means the
    // lambda reference itself never triggers recomposition. This is correct and intentional.
    CompositionLocalProvider(
        LocalPhysicsWorld provides world,
        LocalPhysicsSnapshot provides { latestSnapshot.value }
    ) {
        IsometricScene(
            modifier = modifier,
            renderOptions = renderOptions,
            strokeWidth = strokeWidth,
            drawStroke = drawStroke,
            lightDirection = lightDirection,
            defaultColor = defaultColor,
            // Physics redraws via markDirty() → isDirty → rebuildCache(). No frameVersion
            // needed — markDirty() triggers onDirty → sceneVersion++ for Canvas invalidation.
            // If callers need external redraw control, they should use IsometricScene directly. (FIX R11-S2)
            // Capture root node via callback — same pattern as onHitTestReady (FIX R4-C1)
            onRootNodeReady = { rootNodeRef = it },
            // Capture engine via callback — same pattern as onRootNodeReady (FIX R6-I3)
            onEngineReady = { engineRef = it },
            // Physics tap: integrate into IsometricScene's onTap callback. (FIX R3-C5)
            // No separate overlay Box — avoids layout/event-interception issues.
            // NOTE: onTap is non-nullable in IsometricScene (default empty lambda).
            // Pass empty lambda, NOT null — null won't compile. (FIX R5-C2)
            // NOTE: IsometricScene performs a renderer hit test to produce `node`, but PhysicsScene
            // uses its own physics raycast instead (different spatial index, different result type).
            // The renderer hit test result is discarded. This is acceptable: taps are infrequent
            // human input events, so the redundant spatial query has negligible impact. (FIX R12-S1)
            onTap = if (onTap != null) { x, y, _ ->
                // Use captured engine reference for screen-to-world raycast (FIX R6-I3)
                val engine = engineRef ?: return@IsometricScene
                val ray = PhysicsRaycastUtils.screenToWorldRay(x, y, engine)
                val hit = world.raycast(ray)
                onTap(x, y, hit?.body)
            } else { _, _, _ -> },
            content = {
                // No syncPositionsToNodes here — sync happens in LaunchedEffect above
                // where we have rootNodeRef. The content lambda's `this` is IsometricScope
                // which has no access to rootNode. (FIX R4-C1)
                PhysicsScopeImpl(this, world).content()
            }
        )
    }
}
```

### 4b.4a syncPositionsToNodes (FIX R2-I7, R3-C3)

The sync function walks the node tree and copies snapshot data into each `PhysicsShapeNode`. This runs on the main thread, once per frame.

```kotlin
/**
 * Copy interpolated position/orientation from snapshot into PhysicsShapeNode fields.
 * Called on main thread each frame. PhysicsShapeNode.render() reads these fields. (FIX R2-I2, R2-I7)
 *
 * Uses childrenSnapshot (thread-safe copy) NOT children (mutable list). (FIX R3-C3)
 * Recurses into ALL IsometricNode children regardless of concrete type — not just GroupNode.
 * This ensures PhysicsGroupNode and any future node types are traversed correctly.
 */
private fun syncPositionsToNodes(node: IsometricNode, snapshot: Map<Int, PhysicsStep.BodySnapshot>) {
    if (node is PhysicsShapeNode) {
        val snap = snapshot[node.bodyId]
        if (snap != null) {
            node.physicsPosition = snap.position
            node.physicsOrientation = snap.orientation
        }
    }
    // Recurse into ALL children via childrenSnapshot — thread-safe, type-agnostic (FIX R3-C3)
    for (child in node.childrenSnapshot) {
        syncPositionsToNodes(child, snapshot)
    }
}
```

### 4b.4b PhysicsEventFlow — Flow Adapters (FIX R2-C4, R5-S2)

```kotlin
// In isometric-physics-compose (has coroutines via Compose dependency)
// Wraps core's callback-based EventDispatcher with Flow for Compose consumers.
//
// These are @Composable functions (not plain functions) because they register listeners
// that MUST be cleaned up on disposal. Plain functions would leak listeners on
// recomposition — each call registers a new listener without removing the old one. (FIX R5-S2)

/**
 * Collect collision events as a Flow. Automatically registers/unregisters the listener
 * via DisposableEffect — no manual cleanup needed. (FIX R5-S2)
 */
@Composable
fun rememberCollisionEvents(dispatcher: EventDispatcher): SharedFlow<CollisionEvent> {
    val flow = remember { MutableSharedFlow<CollisionEvent>(extraBufferCapacity = 64) }
    DisposableEffect(dispatcher) {
        val handle = dispatcher.addCollisionListener { event ->
            flow.tryEmit(event)
        }
        onDispose { handle.dispose() }  // uses Disposable handle from R4-S2
    }
    return flow
}

@Composable
fun rememberPhysicsEvents(dispatcher: EventDispatcher): SharedFlow<PhysicsEvent> {
    val flow = remember { MutableSharedFlow<PhysicsEvent>(extraBufferCapacity = 64) }
    DisposableEffect(dispatcher) {
        val handle = dispatcher.addPhysicsListener { event ->
            flow.tryEmit(event)
        }
        onDispose { handle.dispose() }
    }
    return flow
}
```

### 4b.4c PhysicsRaycastUtils (FIX R2-S7)

```kotlin
// In isometric-physics-compose — depends on IsometricEngine (rendering class)
object PhysicsRaycastUtils {
    /** Convert isometric screen coordinates to world-space ray.
     *  Lives in compose module because it requires IsometricEngine. */
    fun screenToWorldRay(screenX: Double, screenY: Double, engine: IsometricEngine): Ray {
        // Invert isometric projection to produce ray origin + direction
    }
}
```

### 4b.5a PhysicsGroupNode (FIX R5-C3)

```kotlin
/**
 * Group node for compound physics bodies. Extends GroupNode (NOT IsometricNode directly)
 * because IsometricApplier.insertBottomUp() casts `current` to GroupNode. (FIX R5-C3)
 *
 * GroupNode already provides:
 *   - override val children: MutableList<IsometricNode>  (required by abstract IsometricNode)
 *   - render() that traverses children with accumulated RenderContext transforms
 *
 * NOTE: For physics rendering, child PhysicsShapeNodes skip context transform accumulation
 * (they use world-space positions). But GroupNode's child management is still needed for
 * the Compose applier to insert/remove child nodes correctly.
 */
class PhysicsGroupNode : GroupNode() {
    // GroupNode.children and GroupNode.render() are inherited
    // syncPositionsToNodes traverses into this node via childrenSnapshot (FIX R3-C3)
    // NOTE: PhysicsGroupNode is purely organizational (FIX R6-S2) — each child PhysicsShape
    // creates its own independent RigidBody. Compound body creation is a future enhancement.
}
```

### 4b.5b PhysicsScope + PhysicsScopeImpl (FIX R5-I2)

```kotlin
/**
 * Scope for physics composables. Extends IsometricScope so standard Shape()/Group()
 * calls still work inside PhysicsScene. (FIX R5-I2)
 */
interface PhysicsScope : IsometricScope {
    val world: PhysicsWorld
}

/**
 * Implementation delegates IsometricScope to the one provided by IsometricScene's content lambda.
 * This bridges the physics DSL with the existing isometric scene graph. (FIX R5-I2)
 */
internal class PhysicsScopeImpl(
    private val isometricScope: IsometricScope,
    override val world: PhysicsWorld
) : PhysicsScope, IsometricScope by isometricScope
```

### 4b.5c ColliderShape + ColliderScope (FIX R5-I3)

```kotlin
/**
 * User-facing collider shape override. Used in BodyConfig.colliderOverride
 * to skip auto-derivation from the visual shape. (FIX R5-I3)
 */
sealed interface ColliderShape {
    data class Box(val halfExtents: Vector) : ColliderShape
    data class Sphere(val radius: Double) : ColliderShape
    data class Cylinder(val radius: Double, val halfHeight: Double) : ColliderShape
    data class Custom(val collider: Collider) : ColliderShape

    /** Convert to internal Collider type. Used by RigidBody.create(). */
    fun toCollider(): Collider = when (this) {
        is Box -> PrismCollider(halfExtents = halfExtents, center = Point.ORIGIN)
        is Sphere -> SphereCollider(radius = radius, center = Point.ORIGIN)  // FIX R7-I3
        is Cylinder -> CylinderCollider(radius = radius, halfHeight = halfHeight, center = Point.ORIGIN)
        is Custom -> collider
    }
}

/**
 * DSL scope for specifying collider overrides in bodyConfig { collider { ... } }. (FIX R5-I3)
 */
class ColliderScope {
    private var shape: ColliderShape? = null

    fun box(halfExtents: Vector) { shape = ColliderShape.Box(halfExtents) }
    fun box(halfX: Double, halfY: Double, halfZ: Double) { shape = ColliderShape.Box(Vector(halfX, halfY, halfZ)) }
    fun sphere(radius: Double) { shape = ColliderShape.Sphere(radius) }
    fun cylinder(radius: Double, halfHeight: Double) { shape = ColliderShape.Cylinder(radius, halfHeight) }
    fun custom(collider: Collider) { shape = ColliderShape.Custom(collider) }

    internal fun build(): ColliderShape = shape ?: error("No collider shape specified in collider { } block")
}
```

### 4b.5d PhysicsShape + PhysicsGroup

```kotlin
/**
 * IMPORTANT (FIX R8-S2): `shape` is used as a remember key. Since Shape is not a data class
 * (uses referential equality), shapes constructed inline (e.g., `Prism(Point.ORIGIN, 1, 1, 1)`)
 * will create a new reference each recomposition, triggering unnecessary body recreation.
 *
 * Best practice: remember shapes outside PhysicsShape:
 *   val myShape = remember { Prism(Point.ORIGIN, 1.0, 1.0, 1.0) }
 *   PhysicsShape(shape = myShape, ...)
 *
 * Alternatively, provide a stable `key` parameter — when present, it replaces `shape` as
 * the remember key, avoiding the referential equality issue entirely.
 */
@Composable
fun PhysicsScope.PhysicsShape(
    shape: Shape,
    color: IsoColor = LocalDefaultColor.current,
    body: BodyConfig = BodyConfig(),
    onCollision: ((CollisionEvent) -> Unit)? = null,
    visible: Boolean = true,
    key: Any? = null  // Stable key for remember — avoids Shape referential equality issues (FIX R8-S2)
) {
    val world = LocalPhysicsWorld.current

    // Create body once, remembered across recompositions. (FIX R6-C1)
    // Body creation is OUTSIDE ComposeNode — ComposeNode factories don't support DisposableEffect,
    // so we need a separate remember + DisposableEffect pair for proper lifecycle management.
    // When `key` is provided, use it instead of `shape` to avoid referential equality issues. (FIX R8-S2)
    val (rb, centeredShape) = remember(key ?: shape, body) {
        RigidBody.create(shape, body).also { (b, _) -> world.addBody(b) }
    }

    // Remove body when composable leaves composition (FIX R6-C1)
    // Without this, bodies leak into PhysicsWorld.bodiesById/bodiesList forever when
    // PhysicsShape is conditionally removed (e.g., list item removal, navigation, conditional rendering).
    // Leaked bodies continue participating in broadphase, collision, solving — phantom objects.
    DisposableEffect(rb) {
        onDispose { world.removeBody(rb) }
    }

    // ReusableComposeNode only manages the visual node in the scene graph (FIX R6-C1)
    // Uses ReusableComposeNode to match existing codebase pattern (IsometricComposables.kt
    // uses ReusableComposeNode for Shape, Group, Path, Batch). Enables node instance reuse
    // when items move within a ForEach, reducing factory calls and GC pressure. Body lifecycle
    // is managed outside the node (via remember/DisposableEffect above), so reuse is safe. (FIX R10-S2)
    ReusableComposeNode<PhysicsShapeNode, IsometricApplier>(
        factory = {
            PhysicsShapeNode(centeredShape, color, rb.id)  // origin-centered baseShape, not user's shape
        },
        update = {
            set(color) { this.color = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
        }
    )

    // Collision callback lifecycle
    if (onCollision != null) {
        DisposableEffect(onCollision) {
            val handle = world.eventDispatcher.addCollisionListener { event ->
                if (event.self.id == rb.id || event.other.id == rb.id) {  // FIX R7-I1
                    onCollision(event)
                }
            }
            onDispose { handle.dispose() }
        }
    }
}

@Composable
fun PhysicsScope.PhysicsGroup(
    onCollision: ((CollisionEvent) -> Unit)? = null,
    content: @Composable PhysicsScope.() -> Unit
) {
    // Capture the current PhysicsScope (`this`) before entering ComposeNode's content lambda,
    // where `this` changes to the node's scope. (FIX R5-C3)
    val parentScope = this
    val world = LocalPhysicsWorld.current

    // PhysicsGroupNode is purely organizational for the Compose applier — it provides
    // GroupNode child management for IsometricApplier.insertBottomUp(). (FIX R6-S2)
    // Each child PhysicsShape creates its own independent RigidBody.
    // Compound body creation (single RigidBody from merged children) is a future enhancement.
    // PhysicsGroupNode extends GroupNode — required by IsometricApplier.insertBottomUp() (FIX R5-C3)
    ReusableComposeNode<PhysicsGroupNode, IsometricApplier>(
        factory = { PhysicsGroupNode() },
        update = { /* ... */ },
        content = {
            // Delegate IsometricScope from parent, provide world for physics (FIX R5-I2)
            PhysicsScopeImpl(parentScope, world).content()
        }
    )
}
```

### 4b.6 DSL Builders (FIX I7)

```kotlin
@DslMarker
annotation class PhysicsDsl

@PhysicsDsl
class BodyConfigBuilder {
    /** Single source of truth for body type (FIX I7) */
    var type: BodyType = BodyType.Dynamic()

    var material: PhysicsMaterial = PhysicsMaterial.Default
    var gravityScale: Double = 1.0
    var linearDamping: Double = 0.0
    var angularDamping: Double = 0.01
    var isBullet: Boolean = false
    var allowSleep: Boolean = true
    var tag: String? = null
    var userData: Any? = null  // FIX R6-I2: exposed in DSL for user-attached data
    private var _filter: CollisionFilter = CollisionFilter()
    private var _colliderOverride: ColliderShape? = null

    /** Convenience: shorthand for type = BodyType.Static */
    fun static() { type = BodyType.Static }
    fun dynamic(mass: Double = 1.0) { type = BodyType.Dynamic(mass) }
    fun kinematic(pushable: Boolean = false) { type = BodyType.Kinematic(pushable) }
    fun sensor() { type = BodyType.Sensor }

    fun collider(block: ColliderScope.() -> Unit) {
        _colliderOverride = ColliderScope().apply(block).build()
    }

    fun filter(block: CollisionFilterBuilder.() -> Unit) {
        _filter = CollisionFilterBuilder().apply(block).build()
    }

    fun material(block: PhysicsMaterialBuilder.() -> Unit) {
        material = PhysicsMaterialBuilder().apply(block).build()
    }

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
        tag = tag,
        userData = userData  // FIX R6-I2
    )
}

fun bodyConfig(block: BodyConfigBuilder.() -> Unit = {}): BodyConfig =
    BodyConfigBuilder().apply(block).build()
```

### 4b.7 rememberPhysicsBody + Ground

```kotlin
@Composable
fun rememberPhysicsBody(config: BodyConfig = BodyConfig()): PhysicsBodyRef {
    val world = LocalPhysicsWorld.current
    val snapshotProvider = LocalPhysicsSnapshot.current  // provides latest snapshot (FIX R3-I5)
    val bodyRef = remember {
        val body = RigidBody.create(config)  // auto-assigned integer ID
        world.addBody(body)
        PhysicsBodyRef(body, world, snapshotProvider)
    }
    DisposableEffect(Unit) { onDispose { world.removeBody(bodyRef.body) } }
    return bodyRef
}

/**
 * A rememberPhysicsBody without PhysicsShape creates an invisible body.
 * This is intentional for sensors/triggers. Document: "Use PhysicsShape
 * for visible bodies, rememberPhysicsBody for invisible sensors." (FIX S6)
 *
 * Position/orientation reads come from the published snapshot — not from RigidBody
 * directly. This avoids reading half-step values from the physics thread. (FIX R3-I5)
 * Write operations (applyForce, applyImpulse) are safe: they set fields that are
 * consumed at the START of the next physics step.
 */
class PhysicsBodyRef(
    internal val body: RigidBody,
    private val world: PhysicsWorld,
    private val snapshotProvider: () -> Map<Int, PhysicsStep.BodySnapshot>
) {
    /** Interpolated position from the latest published snapshot. Thread-safe. (FIX R3-I5) */
    val position: Point get() = snapshotProvider()[body.id]?.position ?: body.position
    /** Interpolated orientation from the latest published snapshot. Thread-safe. */
    val orientation: Quaternion get() = snapshotProvider()[body.id]?.orientation ?: body.orientation
    /** Velocity from the same snapshot as position — temporally consistent. (FIX R4-S3)
     *  Not interpolated (velocity doesn't need interpolation), but guaranteed to be
     *  from the same physics step as position/orientation. */
    val velocity: Vector get() = snapshotProvider()[body.id]?.velocity ?: body.velocity
    val angularVelocity: Vector get() = snapshotProvider()[body.id]?.angularVelocity ?: body.angularVelocity
    /** Sleep state from the same snapshot as position — temporally consistent. (FIX R8-S1) */
    val isSleeping: Boolean get() = snapshotProvider()[body.id]?.isSleeping ?: body.isSleeping

    // Write operations are thread-safe — enqueued into RigidBody.pendingForces
    // (ConcurrentLinkedQueue). Drained on physics thread at start of step(), after
    // clearForces(). No direct mutation of force/torque/velocity from main thread. (FIX R12-I1)
    fun applyForce(force: Vector) = body.applyForce(force)
    fun applyImpulse(impulse: Vector) = body.applyImpulse(impulse)
    fun applyTorque(torque: Vector) = body.applyTorque(torque)
    fun applyForceAtPoint(force: Vector, point: Point) = body.applyForceAtPoint(force, point)
}

@Composable
fun PhysicsScope.Ground(
    width: Double = 20.0, depth: Double = 20.0, thickness: Double = 0.1,
    color: IsoColor = IsoColor(100.0, 100.0, 100.0),
    material: PhysicsMaterial = PhysicsMaterial.Default
) {
    PhysicsShape(
        shape = Prism(Point(-width / 2, -depth / 2, -thickness), width, depth, thickness),
        color = color,
        body = bodyConfig { static(); this.material = material }
    )
}
```

### Phase 4b Tests

```
compose/PhysicsShapeNodeTest.kt (instrumented)
  - PhysicsShapeNode.children is empty (leaf node) (FIX R3-C1)
  - PhysicsShapeNode.render() reads from physicsPosition/physicsOrientation, not bodyRef
  - PhysicsShapeNode.render() does NOT call context.applyTransformsToShape() (FIX R4-C2)
  - PhysicsShapeNode inside Group(position=...) renders at physics position, NOT offset by group (FIX R4-C2)
  - baseShape is origin-centered — rotating Prism at (0,0,0) spins in place, not orbiting (FIX R5-C1)
  - Sleeping body (same position) uses cached shape — no new Point allocations
  - RenderCommand IDs use stable bodyId prefix (FIX R3-S7)

compose/PhysicsShapeTest.kt (instrumented)
  - PhysicsShape renders at physics-driven position
  - PhysicsShape uses origin-centered baseShape from RigidBody.create() (FIX R5-C1)
  - PhysicsShape removed from composition → body removed from PhysicsWorld (FIX R6-C1)
  - PhysicsShape in conditional rendering: toggle off → body removed, toggle on → new body added (FIX R6-C1)
  - PhysicsShape in ForEach: remove item → only that body removed, no leaks (FIX R6-C1)
  - PhysicsShape with stable `key` parameter: recomposition does NOT recreate body (FIX R8-S2)
  - PhysicsShape body addition deferred to physics step — no ConcurrentModificationException (FIX R8-C1)
  - bodyConfig { static() } → no movement
  - bodyConfig { collider { box(Vector(1,1,1)) } } → uses override collider (FIX R5-I3)
  - bodyConfig { userData = myObj } → body.config.userData == myObj (FIX R6-I2)
  - onCollision fires on contact, listener cleaned up on disposal
  - rememberPhysicsBody.position reads from snapshot via LocalPhysicsSnapshot (FIX R3-I5, R6-C2)
  - rememberPhysicsBody.velocity reads from snapshot, not live body (FIX R4-S3)
  - rememberPhysicsBody.applyImpulse → body moves
  - Full 3D rotation renders correctly via vertex transform (FIX I5)

compose/PhysicsGroupNodeTest.kt (instrumented) (FIX R5-C3)
  - PhysicsGroupNode extends GroupNode (IsometricApplier can insert children)
  - PhysicsGroupNode has no bodyConfig property — purely organizational (FIX R6-S2)
  - PhysicsGroup { PhysicsShape(...) } → child nodes correctly parented
  - PhysicsScopeImpl delegates IsometricScope correctly (FIX R5-I2)

compose/PhysicsTapTest.kt (instrumented)
  - Tap on physics body → raycast hit → onTap receives correct body
  - Tap integrated into IsometricScene.onTap — no separate overlay Box (FIX R3-C5)
  - Tap on empty space → onTap receives null body
  - PhysicsRaycastUtils.screenToWorldRay uses engine from onEngineReady callback (FIX R6-I3)

compose/CompositionLocalTest.kt (instrumented) (FIX R6-C2)
  - LocalPhysicsSnapshot provided by PhysicsScene — not default empty
  - LocalPhysicsSnapshot returns latest snapshot data from PhysicsThread
  - rememberPhysicsBody reads from LocalPhysicsSnapshot.current (FIX R6-C2)

compose/ColliderFactoryTest.kt
  - ColliderFactory.fromShape(baseShape, Prism) → PrismCollider with correct halfExtents (FIX R6-I1)
  - ColliderFactory.fromShape(baseShape, Cylinder) → CylinderCollider with correct radius/halfHeight (FIX R6-I1)
  - ColliderFactory.fromShape(baseShape, Stairs) → StairsCompoundCollider with centroid-shifted positions (FIX R6-I1, R8-I1)
  - ColliderFactory.fromShape(baseShape, Knot) → KnotCompoundCollider with centroid-shifted positions (FIX R6-I1, R8-I1)
  - ColliderFactory.fromShape(baseShape, unknownShape) → AABB fallback PrismCollider (FIX R6-I1)
  - CompoundCollider.translate() shifts all child localPositions correctly (FIX R8-I1)

core/PhysicsWorldThreadSafetyTest.kt
  - addBody() during step() does not throw ConcurrentModificationException (FIX R8-C1)
  - removeBody() during step() does not throw ConcurrentModificationException (FIX R8-C1)
  - Body added via deferred queue appears in next step() iteration (FIX R8-C1)
  - Body removed via deferred queue absent from next step() iteration (FIX R8-C1)
  - Rapid add/remove from multiple threads — no lost mutations (FIX R8-C1)

compose/SyncPositionsTest.kt (instrumented)
  - syncPositionsToNodes called from LaunchedEffect with rootNodeRef, not content lambda (FIX R4-C1)
  - syncPositionsToNodes uses childrenSnapshot, not children (FIX R3-C3)
  - Traverses PhysicsGroupNode children correctly
  - PhysicsShapeNode inside nested groups receives snapshot data

compose/PhysicsEventFlowTest.kt
  - Flow adapter emits collision events from core callbacks (FIX R2-C4)
  - rememberCollisionEvents cleans up listener on disposal (FIX R5-S2)
  - Multiple recompositions do NOT accumulate duplicate listeners (FIX R5-S2)

compose/AndroidPhysicsThreadTest.kt
  - Choreographer-driven stepping produces consistent frame timing (FIX R3-S9)
```

**Phase 4b is complete when**: `PhysicsScene { Ground(); PhysicsShape(Prism(...)) }` renders falling, stacking, bouncing boxes. `baseShape` is origin-centered and rotation works correctly for shapes at any position (FIX R5-C1). `PhysicsGroupNode` extends `GroupNode` and supports child insertion via `IsometricApplier` (FIX R5-C3). `PhysicsScope`/`PhysicsScopeImpl` bridge physics and isometric DSLs (FIX R5-I2). Root node and engine access work via `onRootNodeReady`/`onEngineReady` callbacks (FIX R4-C1, R6-I3). Physics shapes inside groups render at world-space positions without double-offset (FIX R4-C2). `PhysicsShape` removal cleans up body from world — no body leaks (FIX R6-C1). `LocalPhysicsSnapshot` is defined and provided by `PhysicsScene` (FIX R6-C2). `ColliderFactory` auto-derives colliders from visual shapes (FIX R6-I1). Tap → raycast → impulse works via `IsometricScene.onTap` with engine reference (FIX R6-I3). Events flow through callbacks in core and `rememberCollisionEvents`/`rememberPhysicsEvents` in compose with automatic lifecycle cleanup (FIX R5-S2). Physics stepping is synchronized with display VSYNC via Choreographer. Full 3D rotation renders correctly via vertex transformation.

---

## Phase 5: Joints

**Goal**: Bodies connected via joints. Doors hinge, bridges flex, chains hang.

**Depends on**: Phase 3 (solver)

**Deliverable**: Revolute joint creates a swinging door. Distance joint creates a spring. Joints integrated into island manager.

### 5.1 Joint Types

```kotlin
sealed interface Joint {
    val bodyA: RigidBody
    val bodyB: RigidBody
    val id: String
    var isEnabled: Boolean
    fun solveVelocity(dt: Double)
    fun solvePosition(dt: Double): Boolean
}

class FixedJoint(/* weld */) : Joint
class RevoluteJoint(/* hinge with motor + limits */) : Joint
class DistanceJoint(/* spring with stiffness + damping */) : Joint
class PrismaticJoint(/* slider with motor + limits */) : Joint
```

### 5.2 Joint Composables (in isometric-physics-compose)

```kotlin
@Composable
fun PhysicsScope.RevoluteJoint(bodyA: PhysicsBodyRef, bodyB: PhysicsBodyRef,
                                pivot: Point, motorSpeed: Double = 0.0, maxTorque: Double = 0.0)

@Composable
fun PhysicsScope.DistanceJoint(bodyA: PhysicsBodyRef, bodyB: PhysicsBodyRef,
                                length: Double, stiffness: Double = 1.0, damping: Double = 0.1)
// etc.
```

### Phase 5 Tests

```
joints/RevoluteJointTest.kt   — swings, motor drives, angle limits
joints/DistanceJointTest.kt   — spring stretches/contracts, damping reduces oscillation
joints/FixedJointTest.kt      — bodies move as one
joints/PrismaticJointTest.kt  — slides along axis, limits, motor
joints/IslandJointTest.kt     — joints connect bodies into same island for sleep
```

**Phase 5 is complete when**: Joints work and are wired into the island manager for correct sleep behavior.

---

## Phase 6: Kinematic Bodies + Body Lifecycle

**Goal**: Kinematic bodies (moving platforms) push dynamic bodies. Body state survives recomposition.

**Depends on**: Phase 4b

### 6.1 Kinematic Support

```kotlin
fun RigidBody.setKinematicTarget(position: Point, orientation: Quaternion) {
    // Compute velocity to reach target in one timestep
    // Applied before broad phase
}
```

### 6.2 Recomposition Survival

```kotlin
@Composable
fun PhysicsScope.PhysicsShape(
    shape: Shape,
    body: BodyConfig = BodyConfig(),
    key: Any? = null,
    persistState: Boolean = true,
    // ...
) {
    if (persistState && key != null) {
        // Look up existing body by key
    }
}
```

### Phase 6 Tests

```
integration/KinematicTest.kt       — platform pushes box, kinematic unaffected by gravity
compose/RecompositionTest.kt       — body with key survives ForEach reorder
```

**Phase 6 is complete when**: Moving platforms push objects. Bodies persist across recomposition.

---

## Phase 7: Debug Visualization + Profiler

**Goal**: Visual Compose debug overlays.

**Depends on**: Phase 4b

### 7.1 Debug Overlay

```kotlin
@Composable
fun PhysicsScope.PhysicsDebugOverlay(
    showColliders: Boolean = true,
    showAABB: Boolean = false,
    showVelocity: Boolean = false,
    showContactPoints: Boolean = false,
    showSleepState: Boolean = false,
    showJoints: Boolean = false,
    showBroadPhaseCells: Boolean = false
)
```

### 7.2 Profiler Overlay

```kotlin
@Composable
fun PhysicsProfilerOverlay(
    showBodyCount: Boolean = true,
    showStepTime: Boolean = true,
    showContacts: Boolean = true,
    showMemory: Boolean = false
)
// Shows: Bodies: 142 (98 active, 44 sleeping) | Step: 2.1ms | Contacts: 287
```

**Phase 7 is complete when**: Debug overlay renders wireframe colliders. Profiler shows accurate metrics.

---

## Phase 8: Particles + Rope

**Goal**: Particle emitter for visual effects. Rope/chain soft body.

**Depends on**: Phase 4b (Compose), Phase 5 (joints — for rope constraint concepts)

### 8.1 Particle System

```kotlin
// isometric-physics-core
class ParticleWorld(val maxParticles: Int = 10_000) {
    fun emit(config: ParticleEmitterConfig, position: Point, count: Int)
    fun update(dt: Double, gravity: Vector, bodies: List<RigidBody>)
    fun collectPaths(): List<Pair<Path, IsoColor>>
}

// isometric-physics-compose
@Composable
fun PhysicsScope.ParticleEmitter(
    position: Point, config: ParticleEmitterConfig = ParticleEmitterConfig(),
    rate: Int = 10, enabled: Boolean = true
)
```

### 8.2 Rope

```kotlin
// isometric-physics-core — Verlet-based, independent of joint solver
class RopeBody(
    val anchor1: Point, val anchor2: Point? = null,
    val segmentCount: Int = 10, val segmentLength: Double = 0.2
) {
    fun update(dt: Double, gravity: Vector)
    fun collectPaths(): List<Path>
}

// isometric-physics-compose
@Composable
fun PhysicsScope.Rope(from: Point, to: Point? = null, segments: Int = 10,
                       color: IsoColor = IsoColor(139.0, 90.0, 43.0))
```

**Phase 8 is complete when**: Particles spawn and fade. Rope hangs under gravity.

---

## Phase 9: Benchmarks

**Goal**: Performance validation.

**Depends on**: Phase 4b+

### Performance Targets

| Scenario | Bodies | Target | Notes |
|----------|--------|--------|-------|
| Stacking | 50 | < 1ms | Basic |
| Stacking | 200 | < 4ms | Within frame budget |
| Stacking | 500 | < 8ms | Maximum target |
| Broad phase | 500 | < 0.5ms | Spatial hash only |
| Solver (10 iter) | 200 contacts | < 2ms | Sequential impulse |
| Raycast | 500 bodies | < 0.1ms/ray | With broad-phase acceleration |
| Full scene | 300 | < 4ms total | 60 FPS viable |

**Note (FIX S4)**: The 500-body target requires `enableBroadPhaseSort = true` in render options to avoid the O(N²) depth sort bottleneck (3000 paths = ~4.5M pair tests). Benchmark both physics step time AND total frame time including depth sort.

**Phase 9 is complete when**: Targets met on mid-range 2023 Android device. Depth sort bottleneck is measured and documented.

---

## Phase Summary

| Phase | Name | Depends On | Module | Key Deliverable |
|-------|------|-----------|--------|----------------|
| **0** | Core shape prerequisite | — | isometric-core | Shape dimensions + Vector.ZERO + Octahedron scale + onRootNodeReady + onEngineReady |
| **1** | Math + rigid body skeleton | Phase 0 | physics-core (Java 17→11) | Gravity, int IDs, slerp, MutableAABB (+ expanded), BodySnapshot with velocity + isSleeping, RigidBody.create() factory, origin-centered baseShape, deferred body mutation queue |
| **2** | Collision detection | Phase 1 | physics-core | GJK/EPA/clipping, correct Stairs geometry, pool lifecycle, debug dump |
| **3** | Collision response + solver | Phase 2 | physics-core | Per-island solving, computed Island.isSleeping, stacking, sleep |
| **4a** | Events + raycasting + forces | Phase 3 | physics-core | Callback events (no coroutines), rays, force fields, CCD |
| **4b** | Compose integration | Phase 4a | physics-compose | PhysicsShapeNode (world-space, origin-centered), PhysicsGroupNode (→GroupNode, no bodyConfig), PhysicsScope/Impl, ColliderShape/Scope, ColliderFactory (centroid-shifted compounds), LocalPhysicsSnapshot, body lifecycle cleanup, stable `key` param, rootNode+engine via callbacks, Choreographer sync, snapshot-only reads |
| **5** | Joints | Phase 3 | physics-core + compose | Fixed, revolute, distance, prismatic |
| **6** | Kinematic + lifecycle | Phase 4b | physics-compose | Moving platforms, recomposition survival |
| **7** | Debug + profiler | Phase 4b | physics-compose | Visual overlays |
| **8** | Particles + rope | Phase 4b, 5 | both | Particle emitter, rope/chain |
| **9** | Benchmarks | Phase 4b+ | physics-benchmark | Performance validation |

```
Phase 0 → Phase 1 → Phase 2 → Phase 3 → Phase 4a → Phase 4b
                                  │                    │
                                  ├→ Phase 5           ├→ Phase 6
                                  │    │               ├→ Phase 7
                                  │    └→ Phase 8      └→ Phase 9
                                  │
                                  └→ (Phase 5 also depends on Phase 3)
```

After Phase 4b, Phases 6/7/9 are independent. Phase 8 (rope) depends on Phase 5 (joints — for constraint concepts, though rope uses Verlet).

---

## Review Fixes Applied

13 rounds of architectural review, 120 issues identified and resolved.

### Round 1 (physics-plan-review.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **C1** Non-convex Stairs/Knot | Added `CompoundCollider` with per-sub-shape GJK + narrow-phase branching |
| **C2** Broken interpolation threading | Physics thread publishes interpolated positions using render-frame alpha |
| **C3** Module build cycle | Split into `isometric-physics-core` (JVM) + `isometric-physics-compose` (Android) |
| **C4** Shape dimensions not stored | Phase 0 prerequisite: add stored dimensions to core shape classes |
| **C5** HashMap non-determinism | All maps → `LinkedHashMap`, contacts sorted by deterministic body ID pair key |
| **I1** PhysicsShape can't link to node | Custom `PhysicsShapeNode` extends `IsometricNode` directly, snapshot-based position sync |
| **I2** Phase 4 overloaded | Split into Phase 4a (events/raycasting/forces), 4b (Compose integration) |
| **I3** HandlerThread in JVM module | JVM `ScheduledExecutorService` in core, Android `Choreographer` in compose |
| **I4** Sleep/islands not wired | `step()` calls `islandManager.buildIslands()` → island-based sleep |
| **I5** Rotation mismatch | Physics applies full 3D rotation to shape vertices directly (option C) |
| **I6** GC from immutable translate | Cache transformed shapes for sleeping bodies; accept Point alloc for active |
| **I7** bodyConfig DSL conflicts | Removed `isStatic` setter, `type` is single source of truth, convenience fns |
| **S1** Debug too late | Headless SVG/text debug dump added in Phase 2 |
| **S3** Grid full rebuild | Incremental updates: only re-insert moved bodies |
| **S4** Depth sort bottleneck | Acknowledged; partial re-sort for moved bodies only |
| **S5** GC from data class contacts | Mutable contact classes with pre-allocated pools |
| **S7** Custom vs dyn4j risk | Phased strategy: custom for GJK/EPA, evaluate dyn4j solver if stacking struggles |

### Round 2 (physics-plan-review-2.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R2-C1** `val origin` misleading | Phase 0: only store dimension fields (`dx`, `dy`, `dz`, `radius`, etc.) — NOT `origin`. Physics tracks position separately via `RigidBody.position` |
| **R2-C3** Two different pair key functions | Unified into single `BodyPairKey.of(idA, idB)` used by both `BodyPair` and `ContactManager` |
| **R2-C4** `SharedFlow` without coroutines dep | Callback-based `EventDispatcher` in core, Flow adapters in compose module |
| **R2-C5** Timer at 62,500 Hz | Fixed: `TimeUnit.MILLISECONDS` with interval `16L` |
| **R2-C5b** Tap conflates hit-test + raycast | `PhysicsScene` does its own pointer input for physics raycast, separate from node hit-test |
| **R2-I1** `PhysicsShapeNode` extends `ShapeNode` | Now extends `IsometricNode` directly — no misleading inheritance |
| **R2-I2** Contradictory position-sync paths | Single path: snapshot copied into node fields on main thread. No direct `bodyRef` reads in render |
| **R2-I3** AABB/inertia fallback = unit cube | Fallback computes from actual path vertices via `AABB.fromPoints` / vertex-hull inertia |
| **R2-I4** `BodyType.Dynamic` equality | Documented: use `is BodyType.Dynamic`, not `==` |
| **R2-I6** Transform cache misses for active bodies | Cache for sleeping bodies; accept Point allocation for active (see R3-C2) |
| **R2-I7** `syncPositionsToNodes` undefined | Fully defined: walks node tree, copies snapshot position/orientation into each `PhysicsShapeNode` |
| **R2-I8** `Wind` data class with mutable state | `ForceField` subclasses are regular classes, not data classes |
| **R2-S1** Integer body IDs | Body IDs are `Int` from `AtomicInteger` counter — faster hashing, no collision risk |
| **R2-S2** `Collider` sealed wrapper indirection | Flattened: `ConvexCollider` and `CompoundCollider` both implement `Collider` interface directly |
| **R2-S3** IslandManager zero-size arrays | Pre-allocated to expected capacity, grown as needed |
| **R2-S4** Island building after solver | Moved island building BEFORE solver — per-island solving, sleeping islands skipped |
| **R2-S6** Contact clipping undescribed | Added Sutherland-Hodgman contact clipping description in Phase 2 |
| **R2-S7** `screenToWorldRay` in physics core | Moved to compose module (`PhysicsRaycastUtils.kt`) |
| **R2-S8** Missing `slerp` definition | Added `Quaternion.slerp()` implementation in Phase 1 math |

### Round 3 (physics-plan-review-3.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R3-C1** `PhysicsShapeNode` missing `override val children` | Added `override val children = mutableListOf<IsometricNode>()` — required by `abstract val children` in `IsometricNode` |
| **R3-C2** `DoubleArray` vertex buffer provides no GC benefit | Removed misleading buffer. Accept `Point` allocation for `Path` construction — real bottleneck is depth sort, not vertex alloc. Caching for sleeping bodies is the actual win |
| **R3-C3** `syncPositionsToNodes` skips non-GroupNode containers | Recurses into ALL nodes via `childrenSnapshot` (thread-safe copy), not just `GroupNode.children` |
| **R3-C4** `BodyPairKey` variable naming inversion | Renamed to `smallId`/`largeId` — `smallId` in high bits, `largeId` in low bits, with mask on both operands |
| **R3-C5** Tap overlay `Box` layout broken | Physics tap integrated into `IsometricScene.onTap` callback — no separate overlay `Box` |
| **R3-I1** `Cylinder` secondary constructor ambiguity | Documented: test explicitly after Phase 0 changes |
| **R3-I2** Octahedron non-uniform `sqrt(2)/2` scale | `fromOctahedron` AABB uses actual XY scale `0.707`, Z scale `1.0`. Inertia uses actual vertex positions |
| **R3-I3** `StairsCompoundCollider` wrong axis/half-extents | Fixed: steps progress along Y axis, each step at `(origin.x, origin.y + i/n, origin.z + i/n)` with half-extents `(0.5, 1/(2n), 1/(2n))` |
| **R3-I4** `Island.isSleeping` never set to `true` | Changed to computed property: `val isSleeping get() = bodies.all { it.isSleeping \|\| it.isStatic }` |
| **R3-I5** `PhysicsBodyRef` bypasses snapshot model | `PhysicsBodyRef` reads from published snapshot, not from `RigidBody` directly. Documented thread-safety |
| **R3-I6** Contact manifold pool lifecycle undefined | Documented lifecycle: narrow phase acquires, contact manager holds active, release only on prune |
| **R3-I7** Java toolchain mismatch (17 vs 11) | `isometric-physics-core` uses `jvmToolchain(17)` + `jvmTarget = "11"` — compiles with Java 17 (reads isometric-core), emits Java 11 bytecode (FIX R5-I4) |
| **R3-S1** `AABB` data class GC pressure | `RigidBody.aabb` is now a mutable `MutableAABB` with `updateFrom()`. `data class AABB` still used for API boundaries |
| **R3-S3** `slerp` missing normalization in trig branch | Added `.normalized()` to trig branch result |
| **R3-S4** `Vector.ZERO`/`GRAVITY` extension properties allocate per-access | Moved to `val` constants inside `Vector.Companion` (Phase 0 core change) |
| **R3-S5** `isStatic`/`isSensor` use `==` inconsistently | All body type checks now use `is` consistently |
| **R3-S6** `ContactPoint.reset()` leaves stale geometric data | `reset()` now clears all fields including position, penetration, localPoints |
| **R3-S7** `PhysicsShapeNode` render command IDs | IDs use stable `bodyId` prefix, not position-dependent `path.hashCode()` |
| **R3-S8** `scheduleAtFixedRate` vs `scheduleWithFixedDelay` | Changed to `scheduleWithFixedDelay` — prevents runaway simulation under load |
| **R3-S9** Physics/render frame timing unsynchronized | `AndroidPhysicsThread` uses Choreographer-driven stepping (not independent timer) — physics step driven by render frame delta |

### Round 4 (physics-plan-review-4.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R4-C1** `PhysicsScene` content lambda cannot access `rootNode` | Added `onRootNodeReady` callback to `IsometricScene` (matches existing `onHitTestReady` pattern). `PhysicsScene` captures root node via callback, runs sync in `SideEffect` |
| **R4-C2** `PhysicsShapeNode.render()` double-applies context transforms | `PhysicsShapeNode.render()` skips `context.applyTransformsToShape()` — physics positions are world-space, context transforms would double-offset inside groups |
| **R4-C3** Cylinder `createPaths()` does not exist | Cylinder keeps existing `Shape(Shape.extrude(Circle(...), height).paths)` super-call. Only adds `val` to constructor parameters |
| **R4-I1** Stairs hardcoded dimension properties | Added comment: `width`/`depth`/`height` are only valid because Stairs is inherently 1×1×1 |
| **R4-I2** `MutableAABB` missing `expanded()` for CCD | Added `expanded()` method to `MutableAABB` returning immutable `AABB` |
| **R4-I3** Duplicate gravity constants | Removed `Vector.GRAVITY`. `PhysicsConfig.gravity` is the single canonical default |
| **R4-I4** Knot compound collider ignores 1/5 scale and offset | `KnotCompoundCollider.create()` now applies the 1/5 scale factor and `(-0.1, 0.15, 0.4)` offset. Custom quadrilateral faces approximated by nearest prism's extended AABB (FIX R5-S1) |
| **R4-I5** `Island` as `data class` excludes computed `isSleeping` | Changed `Island` to regular `class`. Computed `isSleeping` is now visible in `toString()` |
| **R4-S1** Vector `i/j/k` vs physics `x/y/z` readability | Added `val Vector.x`/`.y`/`.z` extension properties in `PhysicsVector.kt` |
| **R4-S2** Lambda listener removal semantics | `addCollisionListener` returns `Disposable` handle. Documented lambda reference requirement |
| **R4-S3** `PhysicsBodyRef.velocity` reads from live body | Added `velocity` and `angularVelocity` to `BodySnapshot` for temporal consistency |
| **R4-S4** `scheduleWithFixedDelay` effective rate ~55 Hz | Documented: JVM thread runs at ~55 Hz, acceptable for testing. Android Choreographer is the production path |

### Round 5 (physics-plan-review-5.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R5-C1** Vertex rotation around world origin instead of shape centroid | `baseShape` is always origin-centered (centroid at `Point.ORIGIN`). `RigidBody.create()` extracts shape centroid, stores origin-centered copy. Rotation around `(0,0,0)` is now correct because shape is centered there |
| **R5-C2** `null` passed to non-nullable `onTap` | Changed `else null` to `else { _, _, _ -> }` — passes default empty lambda |
| **R5-C3** `PhysicsGroupNode` used but never defined | Added full `PhysicsGroupNode` class definition extending `GroupNode()`. Fixed `PhysicsGroup` content lambda to capture scope correctly |
| **R5-I1** `RigidBody.create()` factory undefined | Defined `RigidBody.create()` factory methods in companion: handles ID generation, origin-centered baseShape, collider derivation, inertia computation, inverseMass |
| **R5-I2** `PhysicsScopeImpl` class undefined | Defined `PhysicsScope` interface and `PhysicsScopeImpl` class with `IsometricScope` delegation |
| **R5-I3** `ColliderShape`/`ColliderScope` types undefined | Defined `ColliderShape` sealed interface (Box, Sphere, Cylinder, Custom) and `ColliderScope` DSL builder |
| **R5-I4** Java 11 toolchain can't read Java 17 bytecode | Use `jvmToolchain(17)` for compilation + `jvmTarget = "11"` for output bytecode. Compiler can read isometric-core, output compatible with Android |
| **R5-S1** Knot custom paths mischaracterized as triangular | Changed "triangular" to "quadrilateral" in KnotCompoundCollider documentation |
| **R5-S2** `PhysicsEventFlow` leaks listeners | Changed to `@Composable` functions with `DisposableEffect` for automatic lifecycle cleanup |
| **R5-S3** Gravity vector readability | Use named arguments `Vector(i = 0.0, j = 0.0, k = -9.81)` for gravity construction |

### Round 6 (physics-plan-review-6.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R6-C1** `PhysicsShape` never removes `RigidBody` — body leak | Body creation moved to `remember`, cleanup via `DisposableEffect(rb) { onDispose { world.removeBody(rb) } }`. `ComposeNode` factory now only creates the node |
| **R6-C2** `LocalPhysicsSnapshot` used but never defined or provided | Defined `LocalPhysicsSnapshot = staticCompositionLocalOf`. `PhysicsScene` provides it via `latestSnapshot` state (also fixes S3 — vestigial state) |
| **R6-I1** `ColliderFactory` referenced but never defined | Full `ColliderFactory.fromShape()` implementation: dispatches on `originalShape` type for Prism/Cylinder/Pyramid/Octahedron/Stairs/Knot, AABB fallback for unknown shapes |
| **R6-I2** `BodyConfigBuilder.build()` omits `userData` | Added `var userData: Any? = null` to builder, included in `build()` call |
| **R6-I3** `screenToWorldRay` has `/* engine ref */` placeholder | Added `onEngineReady` callback to `IsometricScene` (Phase 0). `PhysicsScene` captures engine ref via callback for raycast |
| **R6-S1** Vertex centroid biased for non-uniform shapes | `RigidBody.create()` uses analytic centroids for known shape types (Prism, Cylinder, Stairs, Knot), vertex average only as fallback |
| **R6-S2** `PhysicsGroupNode.bodyConfig` stored but never read | Removed `bodyConfig` — `PhysicsGroupNode` is purely organizational for the Compose applier. Compound body creation is a future enhancement |
| **R6-S3** `latestSnapshot` state written but never read | Now used to provide `LocalPhysicsSnapshot` (connected to C2 fix) |

### Round 7 (physics-plan-review-7.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R7-C1** `shape.origin` accessed in `RigidBody.create()` but `origin` is not stored (Phase 0 says bare param) | Replaced all `shape.origin` centroid computations with `AABB.fromShape(shape).center()`. Single dispatch, no reference to non-existent `origin` field |
| **R7-I1** `event.bodyA`/`event.bodyB` used but `CollisionEvent` defines `self`/`other` | Changed to `event.self.id`/`event.other.id` in `PhysicsShape` collision listener |
| **R7-I2** `ColliderFactory` passes `List<Point>` to `Array<Point>` constructors + spurious `center` param | Added `.toTypedArray()`, removed `center` param from `PyramidCollider`/`OctahedronCollider` calls |
| **R7-I3** `ColliderShape.Sphere.toCollider()` is placeholder — no `SphereCollider` type | Added `SphereCollider` class with analytic support function. Added to module structure and collider definitions |
| **R7-S1** `renderShape()` comment mischaracterizes `RenderContext` as doing isometric projection | Corrected comment: `RenderContext` is not used; `IsometricEngine` handles 3D→2D projection |
| **R7-S2** `AndroidPhysicsThread` hardcoded in `PhysicsScene` | Added comment documenting Android-only restriction (module is `com.android.library`) |
| **R7-S3** `updateSleep` allows `allowSleep = false` bodies to be put to sleep | Added `!body.config.allowSleep` check before sleep timer increment |

### Round 8 (physics-plan-review-8.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R8-C1** `addBody()`/`removeBody()` unsynchronized with physics `step()` — `ConcurrentModificationException` | Deferred mutation queue: `ConcurrentLinkedQueue<BodyMutation>` enqueued from main thread, drained at start of `step()`. Body collections only mutated on physics thread |
| **R8-I1** Compound collider positions not shifted by centroid — Stairs/Knot collision geometry misaligned | `ColliderFactory.fromShape()` now computes centroid offset and passes shifted origin to compound collider factories. Child positions account for origin-centering |
| **R8-I2** `AABB.fromShape()` per-type branches require `origin` param that isn't stored | Simplified: `fromShape()` uses `fromPoints()` for all types. Per-type analytic functions retained for direct use when origin is known |
| **R8-S1** `PhysicsBodyRef.isSleeping` reads from live body, not snapshot — temporal inconsistency | Added `isSleeping: Boolean` to `BodySnapshot`. `PhysicsBodyRef.isSleeping` now reads from snapshot |
| **R8-S2** `remember(shape, body)` uses referential equality on `Shape` — spurious body recreation | Documented: `shape` should be `remember`ed outside `PhysicsShape`. Added `key` parameter for explicit stability control |

### Round 9 (physics-plan-review-9.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R9-C1** `syncPositionsToNodes` does not call `markDirty()` — renderer serves stale `PreparedScene` | Added `root.markDirty()` after `syncPositionsToNodes` in `PhysicsScene`'s `LaunchedEffect`. Triggers `isDirty=true` on root, causing `needsUpdate()` to return true and `rebuildCache()` to run |
| **R9-S1** `staticCompositionLocalOf` lambda closure semantics unclear | Added inline comment explaining lambda closes over `MutableState` container (not value), so callers always get latest snapshot |
| **R9-S2** `frameVersion++` redundant after `markDirty()` fix | Documented in comment: `markDirty()` triggers `onDirty → sceneVersion++` which invalidates Canvas. `frameVersion` retained for non-physics external redraws |

### Round 10 (physics-plan-review-10.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R10-I1** `PhysicsBodyRef` write operations (`applyForce`, `applyImpulse`, `applyTorque`) lack `@Volatile` — forces/impulses silently lost on ARM64 | Added `@Volatile` to `RigidBody.force`, `torque`, `velocity`, `angularVelocity`. Ensures main-thread writes are visible to physics thread on weak memory model architectures. Updated `PhysicsBodyRef` comment documenting the safety guarantee |
| **R10-S1** `findByTag()`/`findById()`/`bodyCount`/`activeBodies` read unsynchronized collections from unspecified thread | Added documentation warning: these query functions must only be called from the physics thread or when simulation is paused. Main-thread lookup should use the published snapshot via `LocalPhysicsSnapshot` or `PhysicsBodyRef` |
| **R10-S2** `PhysicsShape`/`PhysicsGroup` use `ComposeNode` but codebase uses `ReusableComposeNode` | Changed both to `ReusableComposeNode` to match existing codebase pattern. Body lifecycle is managed outside the node (via `remember`/`DisposableEffect`), so reuse is safe |

### Round 11 (physics-plan-review-11.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R11-I1** `allowSleep=false` bodies' velocity ignored in island threshold check — fast-moving `allowSleep=false` body cannot prevent neighbors from sleeping | Removed `!body.config.allowSleep` disjunct from `allBelowThreshold` predicate in `updateSleep()`. Bodies with `allowSleep=false` now have their velocity checked for island-level sleep decisions. Their own sleep transition is still suppressed by the `continue` guard in the application loop (R7-S3) |
| **R11-S1** `KnotCompoundCollider` docs claim "slightly enlarged half-extents" but code uses exact dimensions | Updated documentation: quad faces are NOT covered by collider — intentional simplification, decorative detail too small (~0.2 units) to matter for gameplay collision |
| **R11-S2** `frameVersion` state in `PhysicsScene` never incremented — vestigial after `markDirty()` fix | Removed `var frameVersion` state variable from `PhysicsScene`. Omitted `frameVersion` parameter from `IsometricScene` call (default `0L` suffices). Updated R9-S2 comment in LaunchedEffect |

### Round 12 (physics-plan-review-12.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R12-I1** `applyForce()` read-modify-write races with `clearForces()` — stale forces leak across steps | Replaced direct field mutation with deferred force queue (`ConcurrentLinkedQueue<ForceRequest>` per body, matching R8-C1 pattern). Main thread enqueues; physics thread drains at start of `step()` after `clearForces()`. Removed `@Volatile` from force/torque/velocity/angularVelocity (single-writer now). Moved `clearForces()` from end of step to start (before `drainPendingForces`) |
| **R12-S1** `PhysicsScene` `onTap` discards renderer hit-test node and performs separate physics raycast — redundant spatial query | Documented: renderer hit test result intentionally discarded (different spatial index, different result type). Changed `node` parameter to `_`. Taps are infrequent; redundancy is acceptable |

### Round 13 (physics-plan-review-13.md)

| Review Issue | Fix Applied |
|-------------|-------------|
| **R13-I1** `Point - Point` operator used in `drainPendingForces()` but never defined — compilation failure | Added `operator fun Point.minus(other: Point): Vector` to `PhysicsVector.kt`. Standard physics convention: difference of two positions is a displacement vector |
| **R13-S1** Force fields call `body.applyForce()` (now queued) — effects delayed by one step | Added `internal` direct-mutation methods (`applyForceInternal`, `applyTorqueInternal`, `applyImpulseInternal`) for physics-thread callers. Force fields use `applyForceInternal()`. Public `applyForce()` remains queue-based for main-thread callers |
