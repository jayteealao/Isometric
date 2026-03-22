# Physics Implementation Plan

> **Revised**: 2026-03-17 — aligned to API workstreams WS1–WS9
> **Previous revision**: R13 (2026-03-13) — 120 issues resolved across 13 review rounds
> **Status**: Supersedes R13. All prior fixes are incorporated. All API references updated to match current codebase.
> **Research**: [PHYSICS_RESEARCH.md](../research/PHYSICS_RESEARCH.md)
> **Review ref**: `docs/internal/api-design-guideline.md` — all 12 principles apply

---

## What Changed Since R13

This revision is a full API alignment pass. The physics design decisions from R13 are preserved. What changed:

| Area | R13 | This revision |
|------|-----|---------------|
| Package root | `io.fabianterhorst.isometric.physics` | `io.github.jayteealao.isometric.physics` |
| `IsometricScene` entry point | Flat 21-parameter signature | `SceneConfig` / `AdvancedSceneConfig` overloads |
| `onRootNodeReady` / `onEngineReady` | Flat params on `IsometricScene` | `AdvancedSceneConfig` fields (already exist) |
| Lifecycle hooks | `SideEffect` | `DisposableEffect` with `onDispose` |
| Shape param names | `dx`, `dy`, `dz` | `width`, `depth`, `height` (WS1b, already done) |
| `Vector.ZERO` | Phase 0 addition | Still needed — `Vector.Companion` has no named constants |
| Shape dimension fields | Phase 0 addition | Already exist as `val` fields on all shape classes |
| `LocalIsometricEngine` | Phase 4b addition | Already exists in `CompositionLocals.kt` |
| `PhysicsScene` API | Multiple flat params | `SceneConfig` + `PhysicsConfig` dual-overload |
| `PhysicsScope` | Separate scope type | Extends `IsometricScope` (marker interface pattern) |
| Physics composable annotation | None specified | `@IsometricComposable` required |

---

## Table of Contents

- [Decision Summary](#decision-summary)
- [Module Structure](#module-structure)
- [Phase 0: Core Shape Prerequisites](#phase-0-core-shape-prerequisites) — **mostly complete**
- [Phase 1: Math Foundation + Rigid Body Skeleton](#phase-1-math-foundation--rigid-body-skeleton)
- [Phase 2: Collision Detection Pipeline](#phase-2-collision-detection-pipeline)
- [Phase 3: Collision Response + Solver](#phase-3-collision-response--solver)
- [Phase 4a: Events, Raycasting, Forces, CCD](#phase-4a-events-raycasting-forces-ccd)
- [Phase 4b: Compose Integration](#phase-4b-compose-integration) — **most affected by API changes**
- [Phase 5: Joints](#phase-5-joints)
- [Phase 6: Kinematic Bodies + Body Lifecycle](#phase-6-kinematic-bodies--body-lifecycle)
- [Phase 7: Debug Visualization + Profiler](#phase-7-debug-visualization--profiler)
- [Phase 8: Particles + Rope](#phase-8-particles--rope)
- [Phase 9: Benchmarks](#phase-9-benchmarks)
- [Phase Summary](#phase-summary)

---

## Decision Summary

| Decision | Choice |
|----------|--------|
| Scope | Library feature for consumers |
| Modules | `isometric-physics-core` (JVM) + `isometric-physics-compose` (Android) |
| Platform | Builds on `isometric-core` + `isometric-compose` |
| Engine | Custom 3D Kotlin (reference dyn4j + papers) — no third-party physics lib |
| Shapes | All (`Prism`, `Pyramid`, `Cylinder`, `Octahedron`, `Stairs`, `Knot`) |
| Rotation | Full 3D quaternion-based — physics transforms vertices directly |
| API entry point | `PhysicsScene` wraps `IsometricScene`; `PhysicsShape()` / `PhysicsGroup()` in `PhysicsScope` |
| Config split | `SceneConfig`/`AdvancedSceneConfig` for rendering + `PhysicsConfig` for physics |
| Body types | `Dynamic`, `Static`, `Kinematic`, `Sensor` |
| Collision | Solid + bounce + friction + sensors, bitmask filtering |
| Events | Full lifecycle (`Begin`/`Stay`/`End`) + callbacks in core, `Flow` adapters in compose |
| Stacking | Warm starting, solver iterations, island-based sleep |
| Gravity | Force fields (radial, wind, vortex) + per-body scale |
| Forces | Full API (`force`, `impulse`, `torque`, `applyAtPoint`) |
| Joints | `Fixed`, `Revolute`, `Distance`, `Prismatic` |
| Scale target | 100–500 dynamic bodies |
| Sim control | Pause/resume + time scale |
| Boundaries | Optional (infinite default); built-in `Ground()` helper |
| Materials | `PhysicsMaterial` presets + custom |
| Body access | `rememberPhysicsBody(id)` + `LocalPhysicsWorld` |
| Debug | Headless dump (Phase 2), visual overlay (Phase 7) |
| Raycasting | Essential for v1 |
| Particles | Built-in emitter (Phase 8) |
| Soft body | Rope/chain only (Phase 8) |
| Threading | Background thread: `ScheduledExecutorService` on JVM (core), `Choreographer` on Android (compose) |
| CCD | Per-body `isBullet` flag |
| Determinism | Reproducible (`LinkedHashMap` + sorted contacts + fixed timestep) |
| Errors | Defensive with logged warnings |
| Testing | Deterministic unit tests; headless `TestPhysicsWorld.stepN(n)` |
| Timeline | No rush — correctness first |

---

## Module Structure

Two physics modules mirror the existing `isometric-core` / `isometric-compose` split. A benchmark module is added for performance regression testing.

```
isometric-physics-core/
  build.gradle.kts                    # kotlin("jvm"), depends on :isometric-core
  src/
    main/kotlin/io/github/jayteealao/isometric/physics/
      ├── core/
      │   ├── PhysicsWorld.kt
      │   ├── RigidBody.kt
      │   ├── PhysicsStep.kt
      │   ├── BodyConfig.kt
      │   └── PhysicsThread.kt        # JVM ScheduledExecutorService
      ├── math/
      │   ├── AABB.kt
      │   ├── MutableAABB.kt
      │   ├── Matrix3x3.kt
      │   └── Quaternion.kt
      ├── collision/
      │   ├── broadphase/
      │   │   ├── BroadPhase.kt       # Interface (GPU-migratable)
      │   │   └── SpatialHashGrid3D.kt
      │   ├── narrowphase/
      │   │   ├── NarrowPhase.kt      # Interface (GPU-migratable)
      │   │   ├── ConvexCollider.kt
      │   │   ├── CompoundCollider.kt # For Stairs, Knot
      │   │   ├── ColliderShape.kt    # User-facing sealed interface
      │   │   ├── ColliderFactory.kt  # Auto-derive collider from Shape
      │   │   ├── GjkDetector.kt
      │   │   ├── EpaResolver.kt
      │   │   └── colliders/
      │   │       ├── PrismCollider.kt
      │   │       ├── PyramidCollider.kt
      │   │       ├── CylinderCollider.kt
      │   │       ├── SphereCollider.kt
      │   │       ├── OctahedronCollider.kt
      │   │       ├── StairsCompoundCollider.kt
      │   │       └── KnotCompoundCollider.kt
      │   ├── ContactManifold.kt      # Mutable, not data class
      │   ├── ContactPoint.kt         # Mutable with pool
      │   ├── ContactPool.kt
      │   ├── ContactManager.kt       # LinkedHashMap for determinism
      │   └── CollisionFilter.kt
      ├── solver/
      │   ├── ConstraintSolver.kt     # Interface (GPU-migratable)
      │   ├── SequentialImpulseSolver.kt
      │   ├── ContactConstraint.kt
      │   └── FrictionSolver.kt
      ├── dynamics/
      │   ├── ForceField.kt
      │   ├── SleepSystem.kt          # Island-aware
      │   ├── IslandManager.kt
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
      │   └── EventDispatcher.kt      # Callback-based, no coroutines in core
      ├── debug/
      │   ├── PhysicsDebugDump.kt     # SVG/text dump for headless testing
      │   └── PhysicsSnapshot.kt
      └── inertia/
          └── InertiaCalculator.kt

    test/kotlin/io/github/jayteealao/isometric/physics/
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

isometric-physics-compose/
  build.gradle.kts                    # com.android.library
                                      # depends on :isometric-physics-core, :isometric-compose
  src/
    main/kotlin/io/github/jayteealao/isometric/physics/compose/
      ├── PhysicsScene.kt             # Entry point composable
      ├── PhysicsShape.kt             # PhysicsScope.PhysicsShape composable
      ├── PhysicsGroup.kt             # PhysicsScope.PhysicsGroup composable
      ├── PhysicsShapeNode.kt         # Extends ShapeNode (open); overrides buildLocalContext()
      ├── PhysicsGroupNode.kt         # Extends GroupNode (open); overrides buildLocalContext()
      ├── PhysicsScope.kt             # PhysicsScope : IsometricScope
      ├── PhysicsLoop.kt              # PhysicsLoop interface + VsyncPhysicsLoop + BackgroundPhysicsLoop
      ├── BodyIdUtils.kt              # rememberBodyId() shared helper
      ├── NodeExtensions.kt           # IsometricNode.physicsBody() bridge extension
      ├── BodyConfigDsl.kt
      ├── PhysicsRaycastUtils.kt
      ├── PhysicsEventFlow.kt         # Flow adapters wrapping core callbacks
      ├── CompositionLocals.kt        # LocalPhysicsWorld
      ├── Ground.kt
      ├── RememberPhysicsBody.kt
      ├── ColliderScope.kt
      ├── debug/
      │   ├── PhysicsDebugOverlay.kt
      │   └── PhysicsProfilerOverlay.kt
      └── particles/
          └── ParticleEmitter.kt

isometric-physics-benchmark/
  build.gradle.kts                    # com.android.application
                                      # depends on :isometric-physics-core,
                                      #   :isometric-physics-compose, :isometric-benchmark
  src/main/kotlin/io/github/jayteealao/isometric/physics/benchmark/
    ├── PhysicsBenchmarkConfig.kt
    ├── PhysicsBenchmarkScreen.kt
    └── scenarios/
        ├── StackingScenario.kt
        ├── BroadPhaseScenario.kt
        ├── SolverScenario.kt
        └── RaycastScenario.kt
```

### `isometric-physics-core/build.gradle.kts`

```kotlin
plugins {
    kotlin("jvm")
}

group = "io.github.jayteealao"
version = libs.versions.isometric.get()

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":isometric-core"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
}
```

### `isometric-physics-compose/build.gradle.kts`

```kotlin
plugins {
    id("isometric.android.library")
}

dependencies {
    api(project(":isometric-physics-core"))
    api(project(":isometric-compose"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.kotlinx.coroutines.android)
}
```

---

## Phase 0: Core Shape Prerequisites

**Status**: **Largely complete.** The WS1b pass already promoted all dimension parameters to `val` fields and renamed them. What remains is auditing the edge cases documented below.

### What WS1b already delivered

| Shape | Fields now available | Notes |
|-------|----------------------|-------|
| `Prism` | `width`, `depth`, `height` | Renamed from `dx`/`dy`/`dz` |
| `Pyramid` | `width`, `depth`, `height` | Renamed from `dx`/`dy`/`dz` |
| `Cylinder` | `radius`, `height`, `vertices` | All `val` fields |
| `Stairs` | `stepCount` | `val` field |
| `Octahedron` | `position` only | No dimension params — fixed geometry |
| `Knot` | `position` only | No dimension params — fixed geometry |

### What still needs to be added in Phase 0

#### 0.1 `Vector.ZERO` — add missing constant

`Vector.Companion` has no named constants — only three static factory methods (`fromTwoPoints`, `crossProduct`, `dotProduct`). `Vector.ZERO` must be added before Phase 1, because `RigidBody` initialises six fields with it.

**File**: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Vector.kt`

```kotlin
class Vector(val x: Double, val y: Double, val z: Double) {
    companion object {
        /** The zero vector (0, 0, 0). Used as a neutral initial value for velocity, force, and torque. */
        @JvmField val ZERO = Vector(0.0, 0.0, 0.0)

        fun fromTwoPoints(p1: Point, p2: Point): Vector { ... }   // already present
        fun crossProduct(v1: Vector, v2: Vector): Vector { ... }  // already present
        fun dotProduct(v1: Vector, v2: Vector): Double { ... }    // already present
    }
    // ... all existing members unchanged ...
}
```

This is a purely additive change. No existing code is modified.

#### 0.2 `Octahedron` — expose geometry scaling constants

The `Octahedron` path generation applies a non-uniform scale: XY vertices use `sqrt(2)/2 ≈ 0.707`, Z vertices use `1.0`. The physics inertia calculator and AABB factory need these constants without re-deriving them from path vertices.

**File**: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Octahedron.kt`

```kotlin
class Octahedron(val position: Point = Point.ORIGIN) : Shape(createPaths(position)) {

    /** XY scale applied to path vertices. Use for AABB and inertia computation. */
    val xyScale: Double get() = sqrt(2.0) / 2.0   // ≈ 0.707

    /** Z scale applied to path vertices. */
    val zScale: Double get() = 1.0
}
```

These are derived constants, not constructor parameters — they must be `get()` properties to communicate "this is a fixed property of the geometry" rather than a user input.

#### 0.3 `AdvancedSceneConfig` — add `onRootNodeReady`

The `PhysicsScene` composable needs access to the `GroupNode` root to wire position sync. `AdvancedSceneConfig` already provides `onEngineReady: ((SceneProjector) -> Unit)?`. Add the matching root node callback.

**File**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/AdvancedSceneConfig.kt`

```kotlin
class AdvancedSceneConfig(
    // ... all existing fields ...
    val onEngineReady: ((SceneProjector) -> Unit)? = null,   // already present
    val onRendererReady: ((IsometricRenderer) -> Unit)? = null, // already present
    val onRootNodeReady: ((GroupNode) -> Unit)? = null,      // NEW
    // ...
) : SceneConfig(...)
```

**Wire it in `IsometricScene.kt`** — following the exact same pattern used for `onEngineReady` and `onRendererReady`:

```kotlin
// Already present — shown for context:
val currentOnEngineReady by rememberUpdatedState(config.onEngineReady)
val currentOnRendererReady by rememberUpdatedState(config.onRendererReady)

LaunchedEffect(engine) { currentOnEngineReady?.invoke(engine) }
LaunchedEffect(renderer) { currentOnRendererReady?.invoke(renderer) }

// NEW — add below the existing LaunchedEffect(renderer) block:
val currentOnRootNodeReady by rememberUpdatedState(config.onRootNodeReady)

LaunchedEffect(rootNode) { currentOnRootNodeReady?.invoke(rootNode) }
```

`LaunchedEffect(rootNode)` fires once when the composable enters the tree (`rootNode` is created via `remember { GroupNode() }` and never changes). `rememberUpdatedState` ensures the latest callback is always invoked even if `config.onRootNodeReady` is reassigned between recompositions. This is the same pattern `IsometricScene` already uses for `onEngineReady` and `onRendererReady`.

Note: the `DisposableEffect` pattern is NOT appropriate here. `LaunchedEffect` is correct for one-shot callbacks that don't require cleanup. `DisposableEffect` would force a meaningless `onDispose { }` and signals to readers that cleanup is happening when it isn't.

#### 0.4 `IsometricNode` — `buildLocalContext()` extension point

Add an open template-method hook to `IsometricNode` that decouples context construction from command emission. Make `ShapeNode` and `GroupNode` `open` so the physics module can subclass them without copying `renderTo()`.

**File**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt`

```kotlin
abstract class IsometricNode {
    // ... all existing fields unchanged ...

    /**
     * Builds the effective [RenderContext] for this node's rendering pass.
     *
     * The default implementation applies any [renderOptions] override and then
     * accumulates the node's stored [position]/[rotation]/[scale] transforms
     * via [RenderContext.withTransform].
     *
     * Override in subclasses that source their transform from something other
     * than the stored node fields — for example, the physics module overrides
     * this to read [RigidBody.interpolatedPosition] instead.
     *
     * @param parent The context inherited from the parent node.
     * @return A context ready to be passed into [RenderContext.applyTransformsToShape]
     *   or [RenderContext.applyTransformsToPath].
     */
    open fun buildLocalContext(parent: RenderContext): RenderContext {
        val effective = if (renderOptions != null)
            parent.withRenderOptions(renderOptions!!) else parent
        return effective.withTransform(position, rotation, scale, rotationOrigin, scaleOrigin)
    }
}
```

`ShapeNode`, `GroupNode` become `open class` and each calls `buildLocalContext()` instead of constructing the context inline:

```kotlin
open class ShapeNode(var shape: Shape, var color: IsoColor) : IsometricNode() {
    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return
        val localContext = buildLocalContext(context)   // ← single hook call replaces inline guard + withTransform
        val transformedShape = localContext.applyTransformsToShape(shape)
        for (path in transformedShape.paths) {
            output.add(RenderCommand(
                commandId = "${nodeId}_${path.hashCode()}",
                points = emptyList(),
                color = color,
                originalPath = path,
                originalShape = transformedShape,
                ownerNodeId = nodeId
            ))
        }
    }
}

open class GroupNode : IsometricNode() {
    internal override val children = mutableListOf<IsometricNode>()

    override fun renderTo(output: MutableList<RenderCommand>, context: RenderContext) {
        if (!isVisible) return
        val childContext = buildLocalContext(context)   // ← same hook; physics overrides this
        for (child in childrenSnapshot) {
            child.renderTo(output, childContext)
        }
    }
}
```

**Why `open` and not `internal`**: `ShapeNode` and `GroupNode` are already visible to downstream modules through composable factories. Making them `open` widens what dependants can *do* without widening the visible API surface. `PathNode` and `BatchNode` are not opened — the physics module has no reason to extend them.

**`buildLocalContext()` is `open`, not `abstract`**: Existing subclasses (`PathNode`, `BatchNode`, `CustomRenderNode`) inherit the default behaviour for free; no existing code requires changes.

**Module boundary for physics transform**: `withPhysicsTransform()` cannot be a method on `RenderContext` because `isometric-compose` cannot depend on `isometric-physics-core` without creating a circular dependency. It must be an extension function defined in `isometric-physics-compose`:

```kotlin
// isometric-physics-compose — no circular dependency
internal fun RenderContext.withPhysicsTransform(body: RigidBody): RenderContext {
    // Quaternion orientation requires projecting vertices directly rather than
    // using withTransform(rotation: Double) which only handles Z-axis rotation.
    // See PhysicsShapeNode KDoc for the full transform pipeline.
    return withTransform(
        position = body.interpolatedPosition.toPoint(),
        rotation = body.interpolatedOrientation.toYawDegrees(),  // projected Z-equivalent
        scale    = 1.0,
        rotationOrigin = null,
        scaleOrigin    = null
    )
}
```

**Open ambiguity — full 3D orientation**: The existing `RenderContext.withTransform(rotation: Double)` is a single Z-axis angle. Physics bodies have full quaternion orientation. For Phase 4b, project the quaternion yaw as the isometric rotation angle (valid for typical gameplay on flat ground). Full 3D orientation baked into vertex positions is a post-Phase-4b concern — track as a separate plan item.

#### 0.5 `AdvancedSceneConfig` — `withRootNodeCallback()` method

The field-forwarding blob in `PhysicsScene` (`combinedConfig = AdvancedSceneConfig(renderOptions = config.renderOptions, ...)`) silently drops any field added to `AdvancedSceneConfig` in future workstreams. Move the forwarding obligation into `AdvancedSceneConfig` itself where it is compiler-enforced.

**File**: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/AdvancedSceneConfig.kt`

Add after the existing `equals`/`hashCode`/`toString` block:

```kotlin
/**
 * Returns a copy of this config with [onRootNodeReady] replaced by [callback].
 * If this config already has an [onRootNodeReady] callback, both are called —
 * the existing callback first, then [callback].
 *
 * Used by [PhysicsScene] to inject its root-node wiring without requiring
 * manual forwarding of all other fields. Any PR adding a new field to
 * [AdvancedSceneConfig] that omits it here will fail to compile.
 *
 * Internal — not part of the public API surface.
 */
internal fun withRootNodeCallback(callback: (GroupNode) -> Unit): AdvancedSceneConfig {
    val existing = onRootNodeReady
    return AdvancedSceneConfig(
        renderOptions        = renderOptions,
        lightDirection       = lightDirection,
        defaultColor         = defaultColor,
        colorPalette         = colorPalette,
        strokeStyle          = strokeStyle,
        gestures             = gestures,
        useNativeCanvas      = useNativeCanvas,
        cameraState          = cameraState,
        engine               = engine,
        enablePathCaching    = enablePathCaching,
        enableSpatialIndex   = enableSpatialIndex,
        spatialIndexCellSize = spatialIndexCellSize,
        forceRebuild         = forceRebuild,
        frameVersion         = frameVersion,
        onHitTestReady       = onHitTestReady,
        onFlagsReady         = onFlagsReady,
        onRenderError        = onRenderError,
        onEngineReady        = onEngineReady,
        onRendererReady      = onRendererReady,
        onBeforeDraw         = onBeforeDraw,
        onAfterDraw          = onAfterDraw,
        onPreparedSceneReady = onPreparedSceneReady,
        onRootNodeReady      = if (existing != null) { node -> existing(node); callback(node) }
                               else callback
    )
}
```

**Maintenance contract**: the field list lives in one place — inside `AdvancedSceneConfig`. Adding a field without updating `withRootNodeCallback()` is a compile error. `PhysicsScene` never needs touching when `AdvancedSceneConfig` grows.

### Phase 0 complete when

- `Vector.ZERO` is accessible in `isometric-core`
- `Octahedron.xyScale` and `Octahedron.zScale` are accessible
- `AdvancedSceneConfig.onRootNodeReady` exists and fires with a non-null `GroupNode`
- `IsometricNode.buildLocalContext()` is `open`; `ShapeNode` and `GroupNode` are `open class`
- `AdvancedSceneConfig.withRootNodeCallback()` compiles and all its fields match the primary constructor
- All existing tests in `isometric-core` and `isometric-compose` continue to pass

---

## Phase 1: Math Foundation + Rigid Body Skeleton

**Goal**: A `PhysicsWorld` that integrates rigid bodies under gravity with a fixed timestep on a background thread. No collision — objects fall through each other. Establishes the math library, threading model, and reproducible stepping.

**Depends on**: Phase 0

**Deliverable**: Bodies fall under gravity. `TestPhysicsWorld.stepN(300)` produces identical positions across runs. Background thread publishes interpolated positions.

### 1.1 Math Primitives

All math types live in `io.github.jayteealao.isometric.physics.math` (not the root physics package — they are utilities, not the public API surface). They do not depend on `IsoColor`, `Shape`, or any rendering type.

**`AABB.kt`** — Axis-Aligned Bounding Box

```kotlin
package io.github.jayteealao.isometric.physics.math

import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape

/**
 * Immutable AABB — used at API boundaries and for spatial queries.
 * For per-body storage, use [MutableAABB] to avoid GC pressure.
 */
data class AABB(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
) {
    fun intersects(other: AABB): Boolean
    fun intersects(other: MutableAABB): Boolean
    fun contains(point: Point): Boolean
    fun merged(other: AABB): AABB
    fun expanded(margin: Double): AABB    // used by CCD sweep broadphase
    fun volume(): Double
    fun center(): Point

    companion object {
        fun fromPoints(points: List<Point>): AABB

        // Per-type analytic factories — faster than fromPoints when dimensions are known
        fun fromPrism(origin: Point, width: Double, depth: Double, height: Double): AABB
        fun fromPyramid(origin: Point, width: Double, depth: Double, height: Double): AABB
        fun fromCylinder(origin: Point, radius: Double, height: Double): AABB

        /**
         * Octahedron uses non-uniform scale: XY * xyScale, Z * zScale.
         * Use [Octahedron.xyScale] and [Octahedron.zScale] when calling this.
         */
        fun fromOctahedron(origin: Point, xyScale: Double, zScale: Double): AABB
        fun fromStairs(origin: Point, stepCount: Int): AABB
        fun fromKnot(origin: Point): AABB

        /**
         * Fallback: derive AABB from path vertices.
         *
         * Produces correct results for all current shape types. The per-type analytic
         * factories (fromPrism etc.) are preferred when construction parameters are
         * available — they avoid a full vertex iteration.
         */
        fun fromShape(shape: Shape): AABB =
            fromPoints(shape.paths.flatMap { it.points })
    }
}

/** Mutable AABB — stored per-body to avoid 500 allocations per physics step. */
class MutableAABB(
    var minX: Double = 0.0, var minY: Double = 0.0, var minZ: Double = 0.0,
    var maxX: Double = 0.0, var maxY: Double = 0.0, var maxZ: Double = 0.0
) {
    fun updateFrom(aabb: AABB)
    fun intersects(other: MutableAABB): Boolean
    fun toImmutable(): AABB
    fun expanded(margin: Double): AABB
}
```

**`Matrix3x3.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.math

import io.github.jayteealao.isometric.Vector

// Regular class, not data class — same rationale as Quaternion above.
// 9-Double data class in the solver hot path would allocate on every matrix multiply.
class Matrix3x3(
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

**`Quaternion.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.math

import io.github.jayteealao.isometric.Vector

// Regular class, not data class — consistent with Vector (isometric-core) and the WS1b
// binary stability ruling. data class would generate componentN() and copy() that expose
// internal fields and create allocation pressure in the solver hot path.
class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
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
        fun slerp(a: Quaternion, b: Quaternion, t: Double): Quaternion
    }
}
```

### 1.2 Rigid Body

**`RigidBody.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.core

import io.github.jayteealao.isometric.Vector
import io.github.jayteealao.isometric.physics.math.Matrix3x3
import io.github.jayteealao.isometric.physics.math.MutableAABB
import io.github.jayteealao.isometric.physics.math.Quaternion

/**
 * Runtime state of a single simulated body. Not constructed directly — use
 * [PhysicsWorld.addBody] or the [PhysicsShape] composable.
 *
 * All fields written exclusively by the physics thread. Positions published
 * to the render thread via [interpolatedPosition] / [interpolatedOrientation].
 */
class RigidBody internal constructor(
    val id: String,
    val type: BodyType,
    val config: BodyConfig
) {
    // --- Physics-thread state (read-only outside physics package) ---
    // Fields use explicit `internal set` — the comment alone does not enforce access.
    var position: Vector = Vector(0.0, 0.0, 0.0)
        internal set
    var orientation: Quaternion = Quaternion.IDENTITY
        internal set
    var linearVelocity: Vector = Vector.ZERO
        internal set
    var angularVelocity: Vector = Vector.ZERO
        internal set
    var force: Vector = Vector.ZERO
        internal set
    var torque: Vector = Vector.ZERO
        internal set
    var mass: Double = 0.0
        internal set
    var inverseMass: Double = 0.0
        internal set
    var inertiaTensor: Matrix3x3 = Matrix3x3.ZERO
        internal set
    var inverseInertiaTensor: Matrix3x3 = Matrix3x3.ZERO
        internal set
    internal val aabb: MutableAABB = MutableAABB()
    var isSleeping: Boolean = false
        internal set
    var sleepTimer: Double = 0.0
        internal set

    // --- Render-thread snapshot (written at end of physics step, read at render) ---
    //
    // Two separate @Volatile fields are NOT sufficient: the render thread can read
    // a new position paired with an old orientation between the two writes (torn read).
    // Instead, both fields are published as a single atomic reference to an immutable
    // snapshot. The physics thread writes the snapshot once; the render thread reads it
    // once per frame. No lock contention on the hot render path.
    data class RenderSnapshot(
        val position: Vector,
        val orientation: Quaternion
    )
    val renderSnapshot: AtomicReference<RenderSnapshot> =
        AtomicReference(RenderSnapshot(Vector.ZERO, Quaternion.IDENTITY))

    // Convenience accessors for render code — read from the atomic snapshot.
    val interpolatedPosition: Vector get() = renderSnapshot.get().position
    val interpolatedOrientation: Quaternion get() = renderSnapshot.get().orientation

    // --- Force API (callable from any thread — queued, applied on physics step) ---
    fun applyForce(force: Vector)
    fun applyImpulse(impulse: Vector)
    fun applyTorque(torque: Vector)
    fun applyForceAtPoint(force: Vector, point: Vector)
    fun applyImpulseAtPoint(impulse: Vector, point: Vector)
    fun setPosition(position: Vector)
    fun setVelocity(velocity: Vector)
    fun wakeUp()
}
```

**`BodyType.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.core

enum class BodyType {
    /** Fully simulated: responds to forces, gravity, and collisions. */
    Dynamic,
    /** Infinite mass: does not move, but collides with Dynamic bodies. */
    Static,
    /** Controlled position: not affected by forces, but pushes Dynamic bodies. */
    Kinematic,
    /** No collision response: fires collision events only. */
    Sensor
}
```

**`BodyConfig.kt`** — follows guideline §2 (Progressive Disclosure): safe defaults, `PhysicsMaterial` for grouping physical properties

```kotlin
package io.github.jayteealao.isometric.physics.core

import io.github.jayteealao.isometric.physics.collision.narrowphase.ColliderShape

/**
 * Configuration for a physics body. All fields have safe defaults.
 *
 * Pass a [BodyConfig] to [PhysicsShape] to customize body behavior. The common
 * cases:
 *
 * ```kotlin
 * PhysicsShape(geometry = Prism())                                    // dynamic, default material
 * PhysicsShape(geometry = Prism(), body = BodyConfig(type = BodyType.Static))
 * PhysicsShape(geometry = Prism(), body = BodyConfig(material = PhysicsMaterial.Bouncy))
 * ```
 *
 * @param type Dynamic, Static, Kinematic, or Sensor. Default Dynamic.
 * @param material Physical material (restitution, friction, density). Default [PhysicsMaterial.Default].
 * @param collider Override the auto-derived collider. Null = derive from geometry automatically.
 * @param linearDamping Air resistance on linear motion. Default 0.01.
 * @param angularDamping Air resistance on rotation. Default 0.01.
 * @param gravityScale Per-body gravity multiplier. 0.0 disables gravity for this body. Default 1.0.
 * @param isBullet Enable continuous collision detection for fast-moving objects. Default false.
 * @param collisionFilter Bitmask group and mask. Default [CollisionFilter.Default] (collides with all).
 *
 * **Immutable after body creation**: [BodyConfig] is read once when [PhysicsShape]
 * enters composition (inside `DisposableEffect(id)`). Changing `BodyConfig` fields in
 * a subsequent recomposition has no effect on the already-running body. To change
 * body properties at runtime, use the imperative API on [RigidBody] directly via
 * [rememberPhysicsBody]. To replace the configuration entirely, change the [id] —
 * this will dispose the old body and spawn a fresh one.
 */
class BodyConfig(
    val type: BodyType = BodyType.Dynamic,
    val material: PhysicsMaterial = PhysicsMaterial.Default,
    val collider: ColliderShape? = null,
    val linearDamping: Double = 0.01,
    val angularDamping: Double = 0.01,
    val gravityScale: Double = 1.0,
    val isBullet: Boolean = false,
    val collisionFilter: CollisionFilter = CollisionFilter.Default
) {
    init {
        require(linearDamping >= 0.0) { "linearDamping must be non-negative, got $linearDamping" }
        require(angularDamping >= 0.0) { "angularDamping must be non-negative, got $angularDamping" }
        require(gravityScale.isFinite()) { "gravityScale must be finite, got $gravityScale" }
    }
}
```

**`PhysicsMaterial.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.core

/**
 * Physical surface properties. Combine with [BodyConfig] to set restitution,
 * friction, and density.
 */
class PhysicsMaterial(
    val restitution: Double = 0.3,
    val friction: Double = 0.5,
    val density: Double = 1.0
) {
    init {
        require(restitution in 0.0..1.0) { "restitution must be in 0..1, got $restitution" }
        require(friction >= 0.0) { "friction must be non-negative, got $friction" }
        require(density > 0.0) { "density must be positive, got $density" }
    }

    companion object {
        val Default  = PhysicsMaterial()
        val Bouncy   = PhysicsMaterial(restitution = 0.9, friction = 0.1, density = 0.5)
        val Heavy    = PhysicsMaterial(restitution = 0.0, friction = 0.8, density = 5.0)
        val Ice      = PhysicsMaterial(restitution = 0.1, friction = 0.05, density = 0.9)
        val Rubber   = PhysicsMaterial(restitution = 0.7, friction = 0.9, density = 1.2)
    }
}
```

### 1.3 `PhysicsWorld` + Fixed Timestep

**`PhysicsWorld.kt`** — public API; internal implementation hidden

```kotlin
package io.github.jayteealao.isometric.physics.core

import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.Vector
import io.github.jayteealao.isometric.physics.event.CollisionEvent

/**
 * The physics simulation. Created via [PhysicsWorld.create].
 *
 * Thread safety: [addBody] / [removeBody] and force application are safe to call
 * from any thread — mutations are queued and applied at the start of each step.
 * [step] and [stepN] are not thread-safe and must be called on the physics thread only.
 */
class PhysicsWorld private constructor(val config: PhysicsConfig) {

    companion object {
        fun create(config: PhysicsConfig = PhysicsConfig()): PhysicsWorld
    }

    // --- Body management ---
    fun addBody(id: String, shape: Shape, config: BodyConfig = BodyConfig()): RigidBody
    fun removeBody(id: String)
    fun getBody(id: String): RigidBody?
    val bodies: List<RigidBody>

    // --- Simulation control ---
    fun step(dt: Double = config.fixedTimeStep)
    fun stepN(n: Int, dt: Double = config.fixedTimeStep)   // for deterministic testing
    fun pause()
    fun resume()
    var timeScale: Double

    // --- Force fields ---
    fun addForceField(field: ForceField)
    fun removeForceField(field: ForceField)

    // --- Collision events (callback-based in core — Flow adapters in compose) ---
    var onCollisionBegin: ((CollisionEvent) -> Unit)?
    var onCollisionStay:  ((CollisionEvent) -> Unit)?
    var onCollisionEnd:   ((CollisionEvent) -> Unit)?

    // --- Step lifecycle ---
    // Called on the physics thread at the end of each step, after all bodies have
    // published their render snapshots. Used by AndroidPhysicsThread to trigger
    // a scene invalidation. Not called during stepN (test use).
    var onStepComplete: (() -> Unit)?

    // --- Queries ---
    fun raycast(ray: Ray): RaycastResult?
    fun queryOverlap(aabb: AABB): List<RigidBody>

    // --- Debug ---
    fun snapshot(): PhysicsSnapshot
}
```

**`PhysicsConfig.kt`** — physics-side configuration; mirrors the `SceneConfig`/`AdvancedSceneConfig` pattern for the rendering side

```kotlin
package io.github.jayteealao.isometric.physics.core

import io.github.jayteealao.isometric.Vector

/**
 * Configuration for the physics simulation. All fields have safe, production-ready
 * defaults. Pass a [PhysicsConfig] to [PhysicsScene] to customize simulation behavior.
 *
 * @param gravity World gravity vector. Default (0, 0, -9.8) — downward in isometric Z.
 * @param fixedTimeStep Simulation step size in seconds. Default 1/60.
 * @param velocityIterations Sequential impulse solver iterations. Higher = more stable stacks.
 * @param positionIterations Position correction iterations. Higher = less penetration.
 * @param sleepEnabled Allow idle bodies to sleep. Recommended true for performance.
 * @param sleepLinearThreshold Linear velocity threshold for sleep eligibility.
 * @param sleepAngularThreshold Angular velocity threshold for sleep eligibility.
 * @param sleepTimeRequired Duration (seconds) body must be below threshold before sleeping.
 */
class PhysicsConfig(
    val gravity: Vector = Vector(0.0, 0.0, -9.8),
    val fixedTimeStep: Double = 1.0 / 60.0,
    val velocityIterations: Int = 10,
    val positionIterations: Int = 5,
    val sleepEnabled: Boolean = true,
    val sleepLinearThreshold: Double = 0.01,
    val sleepAngularThreshold: Double = 0.01,
    val sleepTimeRequired: Double = 0.5
) {
    init {
        require(fixedTimeStep > 0.0) { "fixedTimeStep must be positive, got $fixedTimeStep" }
        require(velocityIterations >= 1) { "velocityIterations must be at least 1" }
        require(positionIterations >= 1) { "positionIterations must be at least 1" }
    }

    // equals and hashCode are required because rememberPhysicsWorld uses this type
    // as a remember key. Without structural equality, remember(PhysicsConfig()) { ... }
    // sees a new reference on every recomposition and recreates the entire world,
    // destroying all bodies, joints, and simulation state on every frame.
    override fun equals(other: Any?): Boolean =
        other is PhysicsConfig &&
            gravity == other.gravity &&
            fixedTimeStep == other.fixedTimeStep &&
            velocityIterations == other.velocityIterations &&
            positionIterations == other.positionIterations &&
            sleepEnabled == other.sleepEnabled &&
            sleepLinearThreshold == other.sleepLinearThreshold &&
            sleepAngularThreshold == other.sleepAngularThreshold &&
            sleepTimeRequired == other.sleepTimeRequired

    override fun hashCode(): Int {
        var result = gravity.hashCode()
        result = 31 * result + fixedTimeStep.hashCode()
        result = 31 * result + velocityIterations
        result = 31 * result + positionIterations
        result = 31 * result + sleepEnabled.hashCode()
        result = 31 * result + sleepLinearThreshold.hashCode()
        result = 31 * result + sleepAngularThreshold.hashCode()
        result = 31 * result + sleepTimeRequired.hashCode()
        return result
    }
}
```

### 1.4 Background Thread (JVM)

**`PhysicsThread.kt`** — JVM-only, no Android dependency

```kotlin
package io.github.jayteealao.isometric.physics.core

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives [PhysicsWorld.step] on a dedicated background thread at the configured
 * fixed timestep. The Compose-layer counterpart ([AndroidPhysicsThread]) uses
 * Choreographer instead and is not used in JVM/test contexts.
 *
 * Start with [start], stop with [stop]. Safe to start/stop multiple times.
 */
class PhysicsThread(private val world: PhysicsWorld) {
    private val running = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    fun start()
    fun stop()
    val isRunning: Boolean get() = running.get()
}
```

### 1.5 Inertia Computation

**`InertiaCalculator.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.inertia

import io.github.jayteealao.isometric.physics.math.Matrix3x3
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.Pyramid
import io.github.jayteealao.isometric.shapes.Cylinder
import io.github.jayteealao.isometric.shapes.Octahedron
import io.github.jayteealao.isometric.shapes.Stairs

/**
 * Analytic inertia tensor computation for each shape type.
 * Uses shape dimension fields (width/depth/height etc.) rather than path vertices.
 */
object InertiaCalculator {
    fun forPrism(prism: Prism, mass: Double): Matrix3x3
    fun forPyramid(pyramid: Pyramid, mass: Double): Matrix3x3
    fun forCylinder(cylinder: Cylinder, mass: Double): Matrix3x3
    fun forOctahedron(octahedron: Octahedron, mass: Double): Matrix3x3
    fun forStairs(stairs: Stairs, mass: Double): Matrix3x3
    fun forKnot(mass: Double): Matrix3x3        // fixed geometry — no dimensions
}
```

### 1.6 Test Harness

**`TestPhysicsWorld.kt`** — deterministic headless test utility

```kotlin
package io.github.jayteealao.isometric.physics

import io.github.jayteealao.isometric.physics.core.PhysicsConfig
import io.github.jayteealao.isometric.physics.core.PhysicsWorld

/**
 * Wraps [PhysicsWorld] for deterministic unit testing. No threading — steps
 * are driven synchronously by the test via [stepN].
 *
 * Usage:
 * ```kotlin
 * val world = TestPhysicsWorld()
 * val body = world.addBody("ball", Prism(), BodyConfig())
 * world.stepN(300)
 * assertEquals(expectedZ, body.position.z, 0.001)
 * ```
 */
class TestPhysicsWorld(config: PhysicsConfig = PhysicsConfig()) {
    val world: PhysicsWorld = PhysicsWorld.create(config)
    fun stepN(n: Int)
    fun addBody(id: String, shape: Shape, config: BodyConfig = BodyConfig()): RigidBody =
        world.addBody(id, shape, config)
}
```

### Phase 1 Complete When

- `TestPhysicsWorld.stepN(300)` produces identical body positions across N runs on the same JVM
- Bodies with `BodyType.Dynamic` fall under gravity; `BodyType.Static` bodies do not move
- `PhysicsThread` drives the loop at the configured rate without drift
- All inertia tensor computations pass round-trip accuracy tests

---

## Phase 2: Collision Detection Pipeline

**Goal**: Bodies collide and stop. No impulse response — penetration is detected and manifolds are generated. Debug dump produces a human-readable snapshot.

**Depends on**: Phase 1

### 2.1 Broad Phase

**`BroadPhase.kt`** — interface allows future GPU migration

```kotlin
package io.github.jayteealao.isometric.physics.collision.broadphase

interface BroadPhase {
    fun insert(body: RigidBody)
    fun remove(id: String)
    fun update(body: RigidBody)

    // Consumer callback instead of List<Pair<>> return:
    // - Avoids allocating a Pair per candidate and a List wrapper per step
    // - At 500 bodies × 60fps, List<Pair<>> would generate thousands of heap objects/sec
    // - The consumer is called inline on the physics thread with no cross-thread concerns
    fun queryPairs(consumer: (RigidBody, RigidBody) -> Unit)

    fun queryRay(ray: Ray): List<RigidBody>
    fun clear()
}
```

**`SpatialHashGrid3D.kt`** — incremental updates; only changed bodies trigger re-insertion

### 2.2 Collider Hierarchy

**`ColliderShape.kt`** — user-facing sealed interface for overriding the auto-derived collider

```kotlin
package io.github.jayteealao.isometric.physics.collision.narrowphase

/**
 * Override collider for a physics body. Pass via [BodyConfig.collider].
 *
 * When null (the default), the collider is automatically derived from the
 * shape geometry via [ColliderFactory].
 *
 * Use [ColliderShape.Sphere] for fast spherical approximations.
 * Use [ColliderShape.Box] for axis-aligned box approximations.
 * Use [ColliderShape.Auto] to explicitly request auto-derivation (same as null).
 */
sealed class ColliderShape {
    /** Derived automatically from the shape's geometry. */
    object Auto : ColliderShape()

    /** Sphere with the given radius, centered at the body's position. */
    data class Sphere(val radius: Double) : ColliderShape() {
        init { require(radius > 0.0) { "radius must be positive, got $radius" } }
    }

    /** Axis-aligned box with the given half-extents. */
    data class Box(val halfWidth: Double, val halfDepth: Double, val halfHeight: Double) : ColliderShape()

    /** Convex hull of an arbitrary list of support points. Advanced use only. */
    class ConvexHull(val supportPoints: List<Vector>) : ColliderShape()
}
```

**`ColliderFactory.kt`** — auto-derives the correct collider from the shape class

```kotlin
package io.github.jayteealao.isometric.physics.collision.narrowphase

import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.shapes.*

/**
 * Derives the appropriate [ConvexCollider] or [CompoundCollider] for a given [Shape].
 *
 * Called automatically when [BodyConfig.collider] is null. The derived collider
 * uses the shape's `val` dimension fields (width, depth, height etc.) added in Phase 0.
 */
object ColliderFactory {
    fun derive(shape: Shape): Collider = when (shape) {
        is Prism      -> PrismCollider(shape.width, shape.depth, shape.height)
        is Pyramid    -> PyramidCollider(shape.width, shape.depth, shape.height)
        is Cylinder   -> CylinderCollider(shape.radius, shape.height)
        is Octahedron -> OctahedronCollider(shape.xyScale, shape.zScale)
        is Stairs     -> StairsCompoundCollider.create(shape.stepCount)
        is Knot       -> KnotCompoundCollider.create()
        else          -> FallbackConvexCollider(shape.paths.flatMap { it.points })
    }
}
```

### 2.3–2.7 Narrow Phase, GJK/EPA, Contact Classes, Contact Manager, Debug Dump

These phases have no API surface exposure (all internal). Package references are updated:

- All files: `io.github.jayteealao.isometric.physics.collision.*`
- `ContactManifold` and `ContactPoint` are mutable classes (not data classes) — pooled to avoid GC pressure
- `ContactManager` uses `LinkedHashMap` for deterministic iteration order
- `PhysicsDebugDump` produces SVG/text output readable without a display — critical for headless CI

---

## Phase 3: Collision Response + Solver

**Goal**: Bodies bounce, stack, and slide with friction. Sequential Impulse solver with warm starting.

**Depends on**: Phase 2

All solver types are in `io.github.jayteealao.isometric.physics.solver`. No public API surface changes from prior plans. Package name update only.

**`IslandManager`** drives island-based sleep: bodies in a sleeping island skip the solver entirely. `SleepSystem` tracks per-body sleep timers; `IslandManager` wires them into the world step.

---

## Phase 4a: Events, Raycasting, Forces, CCD

**Goal**: Full event lifecycle, raycasting, force fields, CCD for fast bodies.

**Depends on**: Phase 3

All types are in `io.github.jayteealao.isometric.physics.*`. No Compose dependency — these are pure JVM APIs. Package name update only from R13.

**`CollisionEvent.kt`**:

```kotlin
package io.github.jayteealao.isometric.physics.event

/**
 * Describes a collision between two bodies.
 *
 * **Lifecycle warning**: [contactPoints] contains references to pooled, mutable
 * [ContactPoint] objects. These objects are valid only for the duration of the
 * callback (`onCollisionBegin`, `onCollisionStay`, `onCollisionEnd`). Do NOT
 * store references to individual [ContactPoint] objects past the callback —
 * they will be reclaimed by the pool at the start of the next step.
 *
 * If contact data is needed after the callback, copy the fields:
 * ```kotlin
 * val position = event.contactPoints.first().position.let { Vector(it.x, it.y, it.z) }
 * ```
 */
class CollisionEvent(
    val bodyA: RigidBody,
    val bodyB: RigidBody,
    val contactPoints: List<ContactPoint>,   // pool-backed — valid during callback only
    val normal: Vector
)
```

**`ForceField.kt`** — sealed, user-constructable:

```kotlin
package io.github.jayteealao.isometric.physics.dynamics

/**
 * An additional force applied to bodies each step.
 *
 * **`ForceField.Gravity` vs `PhysicsConfig.gravity`**:
 * `PhysicsConfig.gravity` is the **world default** — it is applied to every dynamic
 * body every step. `ForceField.Gravity` is an **additive** field — it is applied on
 * top of the world gravity. They are not alternatives; they stack. To add a second
 * gravity zone (e.g., reduced gravity in a region), add a `ForceField.Radial` with
 * a strength that counters the world gravity, or use `BodyConfig.gravityScale = 0.0`
 * plus a `ForceField.Gravity` to replace gravity entirely for specific bodies.
 */
sealed class ForceField {
    data class Gravity(val direction: Vector, val strength: Double) : ForceField()
    data class Radial(val origin: Vector, val strength: Double, val falloff: Double) : ForceField()
    data class Wind(val direction: Vector, val strength: Double) : ForceField()
    data class Vortex(val axis: Vector, val origin: Vector, val strength: Double) : ForceField()
}
```

---

## Phase 4b: Compose Integration

**Goal**: `PhysicsScene` wraps `IsometricScene`. `PhysicsShape` and `PhysicsGroup` composables drive physics bodies from scene content. Position sync runs on the render frame.

**Depends on**: Phase 4a, current `isometric-compose` API (WS1–WS9)

This is the most API-sensitive phase. Every design decision here must align with the established API patterns.

### 4b.1 `PhysicsConfig` (already defined in Phase 1)

`PhysicsConfig` is the physics-side equivalent of `SceneConfig`. It covers simulation parameters only — no rendering concerns.

### 4b.2 `PhysicsScope`

`PhysicsScope` extends `IsometricScope` — the same marker-interface pattern. This is the correct approach because:
1. `IsometricScope` is a `@Stable interface` with no members — extension is the intended composition mechanism
2. Extending it means all `IsometricScope` composables (`Shape`, `Group`, `ForEach`, `If`, etc.) are available inside `PhysicsScene.content` without qualification
3. Physics-specific composables (`PhysicsShape`, `PhysicsGroup`) are extensions on `PhysicsScope`

**File**: `isometric-physics-compose/src/main/kotlin/io/github/jayteealao/isometric/physics/compose/PhysicsScope.kt`

```kotlin
package io.github.jayteealao.isometric.physics.compose

import androidx.compose.runtime.Stable
import io.github.jayteealao.isometric.compose.runtime.IsometricScope

/**
 * Receiver scope for [PhysicsScene] content.
 *
 * Extends [IsometricScope] — all standard scene composables ([Shape], [Group],
 * [ForEach], [If]) are available inside [PhysicsScene] content alongside the
 * physics-specific composables ([PhysicsShape], [PhysicsGroup]).
 */
@Stable
interface PhysicsScope : IsometricScope

internal class PhysicsScopeImpl(val world: PhysicsWorld) : PhysicsScope
```

### 4b.3 `PhysicsShapeNode` and `PhysicsGroupNode`

Both nodes use the `buildLocalContext()` hook added in Phase 0.4. They extend `ShapeNode` and `GroupNode` (now `open class`) respectively and override **only the context-construction step** — the entire `renderTo()` body, including path iteration and `RenderCommand` construction, is inherited unchanged.

**`PhysicsShapeNode.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.compose

import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.compose.runtime.ShapeNode
import io.github.jayteealao.isometric.physics.core.RigidBody

/**
 * A [ShapeNode] whose transform is driven by a physics [RigidBody].
 *
 * Overrides [buildLocalContext] to read [body.interpolatedPosition] and
 * [body.interpolatedOrientation] instead of the node's stored position/rotation/scale
 * fields. The full path-iteration and [RenderCommand] construction logic is inherited
 * from [ShapeNode.renderTo] — no duplication.
 *
 * The node's stored [position] field is the *spawn* position only; physics takes
 * over the transform immediately on first step.
 *
 * **3D orientation note**: [withPhysicsTransform] projects the quaternion yaw as
 * the isometric rotation angle. Full vertex-level 3D orientation is post-Phase-4b.
 */
internal class PhysicsShapeNode(
    shape: Shape,
    color: IsoColor,
    val body: RigidBody
) : ShapeNode(shape, color) {

    override fun buildLocalContext(parent: RenderContext): RenderContext =
        parent.withPhysicsTransform(body)
}
```

**`PhysicsGroupNode.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.compose

import io.github.jayteealao.isometric.compose.runtime.GroupNode
import io.github.jayteealao.isometric.compose.runtime.RenderContext
import io.github.jayteealao.isometric.physics.core.RigidBody

/**
 * A [GroupNode] whose transform is driven by a physics [RigidBody].
 *
 * Overrides [buildLocalContext] only. Child rendering and dirty propagation
 * are inherited from [GroupNode.renderTo] unchanged.
 */
internal class PhysicsGroupNode(val body: RigidBody) : GroupNode() {

    override fun buildLocalContext(parent: RenderContext): RenderContext =
        parent.withPhysicsTransform(body)
}
```

`withPhysicsTransform` is a package-internal extension function defined in `isometric-physics-compose` (see Phase 0.4 for module boundary rationale).

### 4b.4 `AndroidPhysicsThread`

**File**: `isometric-physics-compose/src/main/kotlin/io/github/jayteealao/isometric/physics/compose/AndroidPhysicsThread.kt`

**Threading model — explicit choice**: `AndroidPhysicsThread` runs on the **main thread** via `Choreographer`. `PhysicsThread` (core/JVM) runs on a **background thread** via `ScheduledExecutorService`. These are two different models:

| Model | Thread | Sync overhead | Scale |
|-------|--------|---------------|-------|
| `PhysicsThread` (JVM/test) | Background | `AtomicReference` snapshot | Unlimited — physics never blocks UI |
| `AndroidPhysicsThread` | Main (vsync) | None (same thread) | Limited — physics blocks each frame |

The main-thread model is simpler and appropriate for the initial implementation (no cross-thread synchronisation needed; the `AtomicReference` in `RigidBody` still works since it's written and read on the same thread). **However**: at the 100–500 body scale target, a single physics step with 10 solver iterations may take 2–5ms. On a 16ms frame budget, this leaves little headroom. If profiling in Phase 9 shows frame drops, the upgrade path is to move `AndroidPhysicsThread` to a background thread — the `AtomicReference` snapshot mechanism is already in place for that transition.

Uses `Choreographer.postFrameCallback` to advance the physics simulation at vsync, accumulating time and consuming it in fixed-timestep increments:

```kotlin
internal class AndroidPhysicsThread(private val world: PhysicsWorld) {
    private var lastFrameNanos: Long = 0L
    private var accumulator: Double = 0.0

    // Called via Choreographer.postFrameCallback
    internal fun onFrame(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 0.0
                 else (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
        lastFrameNanos = frameTimeNanos
        accumulator += dt * world.timeScale
        val step = world.config.fixedTimeStep
        while (accumulator >= step) {
            world.step(step)
            accumulator -= step
        }
        // interpolation alpha for render = accumulator / step (capped at 1.0)
    }

    fun start()
    fun stop()
}
```

The thread is wired via `DisposableEffect` in `PhysicsScene` — not `SideEffect`.

**`PhysicsLoop` — formalise the threading abstraction**

`AndroidPhysicsThread` and `PhysicsThread` (core/JVM) are two implementations of the same concept. Formalise this as an internal interface so the upgrade from main-thread to background-thread is a one-line swap in `PhysicsScene` with no public API changes:

```kotlin
// PhysicsLoop.kt — package-internal in isometric-physics-compose
internal interface PhysicsLoop {
    fun start()
    fun stop()
}

// Current implementation — main thread via Choreographer
internal class VsyncPhysicsLoop(world: PhysicsWorld) : PhysicsLoop { ... }

// Future implementation — background ScheduledExecutorService (Phase 9 upgrade path)
internal class BackgroundPhysicsLoop(world: PhysicsWorld) : PhysicsLoop { ... }
```

`PhysicsScene` constructs `val physicsThread: PhysicsLoop = remember(world) { VsyncPhysicsLoop(world) }`. When Phase 9 profiling shows the main-thread model saturating, swap the constructor — `PhysicsScene.kt` is the only file that changes. Add `BackgroundPhysicsLoop` to the file layout in the module structure section.

The `AtomicReference<RenderSnapshot>` in `RigidBody` is already the correct synchronisation primitive for the background-thread model — it was designed with this upgrade in mind.

### 4b.5 CompositionLocals

**File**: `isometric-physics-compose/src/main/kotlin/io/github/jayteealao/isometric/physics/compose/CompositionLocals.kt`

```kotlin
package io.github.jayteealao.isometric.physics.compose

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jayteealao.isometric.physics.core.PhysicsWorld

/**
 * Provides the [PhysicsWorld] created by the nearest enclosing [PhysicsScene].
 * Throws if accessed outside a [PhysicsScene].
 */
val LocalPhysicsWorld = staticCompositionLocalOf<PhysicsWorld> {
    error("No PhysicsWorld provided. LocalPhysicsWorld must be used within a PhysicsScene.")
}
```

`staticCompositionLocalOf` is correct here (consistent with WS7 ruling): `PhysicsWorld` is created once per `PhysicsScene` and does not change after initial provision.

`LocalIsometricEngine` is **not** added here — it already exists in `isometric-compose`'s `CompositionLocals.kt` and is provided by `IsometricScene`.

### 4b.6 `PhysicsScene`

This is the primary entry point. It follows the `IsometricScene` dual-overload pattern exactly: `SceneConfig` for the simple path, `AdvancedSceneConfig` for the advanced path. `PhysicsConfig` is always separate — it is never mixed into `SceneConfig`.

**File**: `isometric-physics-compose/src/main/kotlin/io/github/jayteealao/isometric/physics/compose/PhysicsScene.kt`

```kotlin
package io.github.jayteealao.isometric.physics.compose

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.jayteealao.isometric.IsometricEngine
import io.github.jayteealao.isometric.compose.runtime.AdvancedSceneConfig
import io.github.jayteealao.isometric.compose.runtime.IsometricScene
import io.github.jayteealao.isometric.compose.runtime.SceneConfig
import io.github.jayteealao.isometric.physics.core.PhysicsConfig
import io.github.jayteealao.isometric.physics.core.PhysicsWorld

/**
 * Isometric scene with a live physics simulation.
 *
 * Wraps [IsometricScene] and runs a [PhysicsWorld] in sync with the render loop.
 * [PhysicsShape] and [PhysicsGroup] composables in [content] create physics bodies
 * automatically. Standard scene composables ([Shape], [Group], [ForEach]) are also
 * available for non-physics rendering.
 *
 * The [world] parameter defaults to a remembered [PhysicsWorld] with default
 * [PhysicsConfig]. To customise gravity, timestep, or solver iterations, pass
 * `world = rememberPhysicsWorld(PhysicsConfig(...))` explicitly.
 *
 * @param modifier Modifier for sizing and layout
 * @param config Rendering configuration (lighting, gestures, stroke style, etc.)
 * @param world Physics world. Defaults to [rememberPhysicsWorld]. Pass an explicit
 *   [rememberPhysicsWorld] call with a [PhysicsConfig] to customise simulation parameters.
 * @param content Scene content lambda — receives [PhysicsScope]
 */
@Composable
fun PhysicsScene(
    modifier: Modifier = Modifier,
    config: SceneConfig = SceneConfig(),
    world: PhysicsWorld = rememberPhysicsWorld(),
    content: @Composable PhysicsScope.() -> Unit
) {
    // Remember a stable engine instance. Without this, AdvancedSceneConfig's default
    // parameter (IsometricEngine()) would create a fresh engine on every recomposition,
    // invalidating remember(config.engine) in IsometricScene's advanced overload and
    // causing renderer teardown/rebuild on every parent recomposition.
    // Mirrors the same guard in IsometricScene's simple overload.
    val engine = remember { IsometricEngine() }
    PhysicsScene(
        modifier = modifier,
        config = AdvancedSceneConfig(
            engine = engine,
            renderOptions = config.renderOptions,
            lightDirection = config.lightDirection,
            defaultColor = config.defaultColor,
            colorPalette = config.colorPalette,
            strokeStyle = config.strokeStyle,
            gestures = config.gestures,
            useNativeCanvas = config.useNativeCanvas,
            cameraState = config.cameraState
        ),
        world = world,
        content = content
    )
}

/**
 * Advanced [PhysicsScene] overload with full renderer and engine access.
 *
 * Use when benchmark hooks, custom engine instances, or render pipeline access
 * are needed alongside physics. Any existing [AdvancedSceneConfig.onRootNodeReady]
 * callback is chained — it fires before the physics wiring, so both callers receive
 * the root node without interference.
 *
 * @param modifier Modifier for sizing and layout
 * @param config Advanced rendering configuration
 * @param world Physics world instance
 * @param content Scene content lambda — receives [PhysicsScope]
 */
@Composable
fun PhysicsScene(
    modifier: Modifier = Modifier,
    config: AdvancedSceneConfig,
    world: PhysicsWorld = rememberPhysicsWorld(),
    content: @Composable PhysicsScope.() -> Unit
) {
    val physicsScope = remember(world) { PhysicsScopeImpl(world) }
    val physicsThread = remember(world) { AndroidPhysicsThread(world) }

    // Wire the Choreographer-driven physics loop via DisposableEffect (not SideEffect).
    // DisposableEffect correctly stops the thread when the composable leaves the tree.
    DisposableEffect(physicsThread) {
        physicsThread.start()
        onDispose { physicsThread.stop() }
    }

    // Build a combined config that injects the physics root-node callback via
    // withRootNodeCallback(). The field-forwarding obligation lives inside
    // AdvancedSceneConfig — any new field added there will produce a compile
    // error in withRootNodeCallback() if not forwarded. PhysicsScene itself
    // never needs updating when AdvancedSceneConfig grows.
    val combinedConfig = remember(config, world) {
        config.withRootNodeCallback { rootNode ->
            // Pull model: PhysicsShapeNode.buildLocalContext() reads
            // body.interpolatedPosition at draw time. The only push required
            // is marking the root dirty so the Canvas redraws each physics step.
            world.onStepComplete = { rootNode.markDirty() }
        }
    }

    CompositionLocalProvider(LocalPhysicsWorld provides world) {
        IsometricScene(modifier = modifier, config = combinedConfig) {
            physicsScope.content()
        }
    }
}

/**
 * Creates and remembers a [PhysicsWorld] for the lifecycle of the composition.
 *
 * Pass a [PhysicsConfig] to customise gravity, fixed timestep, or solver iterations:
 * ```kotlin
 * PhysicsScene(world = rememberPhysicsWorld(PhysicsConfig(gravity = Vector(0.0, 0.0, -5.0))))
 * ```
 */
@Composable
fun rememberPhysicsWorld(config: PhysicsConfig = PhysicsConfig()): PhysicsWorld {
    return remember(config) { PhysicsWorld.create(config) }
}
```

**Why `DisposableEffect` for the physics thread**: `SideEffect` would restart the thread on every recomposition. `DisposableEffect(physicsThread)` starts it once when the composable enters the tree and stops it when it leaves — the correct lifecycle for a background loop.

**Why `physicsConfig` was removed from `PhysicsScene`**: The previous signature had `physicsConfig: PhysicsConfig = PhysicsConfig()` as a parameter whose only effect was as the default argument to `world`. If a caller passed both `physicsConfig = ...` and an explicit `world = ...`, the `physicsConfig` was silently ignored — an §6 invalid-state smell. Removing it means world creation is always explicit via `rememberPhysicsWorld(config)`, and `PhysicsScene` deals exclusively with a world instance rather than world construction. The hero scenario remains one line: `PhysicsScene { ... }`.

### 4b.7 `PhysicsShape` and `PhysicsGroup`

Both use `@IsometricComposable` — the same `@ComposableTarget` annotation used by all other scene composables. This restricts them to the `IsometricApplier` tree and produces a compile-time error if called outside a physics/isometric scene.

**`PhysicsShape.kt`**

```kotlin
package io.github.jayteealao.isometric.physics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.compose.runtime.IsometricComposable
import io.github.jayteealao.isometric.compose.runtime.LocalDefaultColor
import io.github.jayteealao.isometric.physics.core.BodyConfig

/**
 * Renders a shape in the scene and simulates it as a physics body.
 *
 * **Hero scenario — no id needed:**
 * ```kotlin
 * PhysicsShape(geometry = Prism())
 * ```
 *
 * **Named body — required for [rememberPhysicsBody]:**
 * ```kotlin
 * PhysicsShape(id = "player", geometry = Prism())
 * val player = rememberPhysicsBody("player")
 * ```
 *
 * The [position] parameter is the **spawn position** — once composed, the body's
 * position is driven by the physics simulation. To move a body after spawning, use
 * [RigidBody.setPosition] or apply forces via [rememberPhysicsBody].
 *
 * @param geometry Shape to render and simulate
 * @param color Fill color. Defaults to [LocalDefaultColor]
 * @param position Initial spawn position in world space. Default [Point.ORIGIN]
 * @param visible Whether to render this shape
 * @param body Physics body configuration (type, material, collider, etc.)
 * @param id Optional unique identifier. Required only if [rememberPhysicsBody] is
 *   needed for imperative access. Null = anonymous body — stable across recompositions
 *   but not addressable by name.
 */
@IsometricComposable
@Composable
fun PhysicsScope.PhysicsShape(
    geometry: Shape,
    color: IsoColor = LocalDefaultColor.current,
    position: Point = Point.ORIGIN,
    visible: Boolean = true,
    body: BodyConfig = BodyConfig(),
    id: String? = null
) {
    val world = LocalPhysicsWorld.current

    // Resolve the stable body ID. For named bodies the caller's id is used directly.
    // For anonymous bodies a UUID is generated once and remembered for the lifetime of
    // this composable instance — stable across recompositions, invisible externally.
    val resolvedId = remember { id ?: "physics_anon_${java.util.UUID.randomUUID()}" }

    // Create and register the body; remove when composable leaves composition.
    // Keyed on resolvedId only — NOT on position.
    //
    // Position must NOT be a key. If it were, any recomposition with a different
    // position value would dispose the body (removing it with all its velocity, force,
    // and sleep state) and re-add it, resetting the simulation. The position parameter
    // is the *spawn* position — read once at effect entry; physics takes over from there.
    DisposableEffect(resolvedId) {
        world.addBody(resolvedId, geometry, body)
        world.getBody(resolvedId)?.setPosition(
            Vector(position.x, position.y, position.z)
        )
        onDispose { world.removeBody(resolvedId) }
    }

    // PhysicsShapeNode inherits ShapeNode.renderTo() and overrides buildLocalContext()
    // to read body.interpolatedPosition — no explicit position update needed here.
    val physicsBody = world.getBody(resolvedId) ?: return
    ReusableComposeNode<PhysicsShapeNode, IsometricApplier>(
        factory = { PhysicsShapeNode(geometry, color, physicsBody) },
        update = {
            // position/rotation/scale intentionally absent — physics drives these.
            set(geometry) { this.shape = it; markDirty() }
            set(color)    { this.color = it; markDirty() }
            set(visible)  { this.isVisible = it; markDirty() }
        }
    )
}
```

Note the reduced `set()` block count compared to `Shape`: `position`, `rotation`, `scale`, `rotationOrigin`, `scaleOrigin` are intentionally absent. Physics drives those fields. Their absence is a deliberate API signal, not an oversight.

**Shared helper — `rememberBodyId()`**

Both `PhysicsShape` and `PhysicsGroup` use the anonymous-body UUID pattern. Extract it once:

```kotlin
// BodyIdUtils.kt — package-internal
/**
 * Returns [id] if non-null, otherwise generates a UUID that is stable for the
 * lifetime of this composable instance. Anonymous bodies are not addressable
 * via [rememberPhysicsBody] — the stable id is invisible externally.
 */
@Composable
internal fun rememberBodyId(id: String?): String =
    remember { id ?: "physics_anon_${java.util.UUID.randomUUID()}" }
```

Both composables then call `val resolvedId = rememberBodyId(id)` instead of inlining the UUID logic.

**`PhysicsGroup.kt`** — follows the same `Group` pattern; children are transformed relative to the physics body's orientation

```kotlin
@IsometricComposable
@Composable
fun PhysicsScope.PhysicsGroup(
    position: Point = Point.ORIGIN,
    visible: Boolean = true,
    body: BodyConfig = BodyConfig(),
    id: String? = null,
    content: @Composable PhysicsScope.() -> Unit
) {
    val world = LocalPhysicsWorld.current
    val resolvedId = rememberBodyId(id)

    DisposableEffect(resolvedId) {
        world.addBody(resolvedId, config = body)
        world.getBody(resolvedId)?.setPosition(Vector(position.x, position.y, position.z))
        onDispose { world.removeBody(resolvedId) }
    }

    val physicsBody = world.getBody(resolvedId) ?: return
    ReusableComposeNode<PhysicsGroupNode, IsometricApplier>(
        factory = { PhysicsGroupNode(physicsBody) },
        update = {
            set(visible) { this.isVisible = it; markDirty() }
        },
        content = { PhysicsScopeImpl(world).content() }
    )
}
```

### 4b.8 `rememberPhysicsBody` and body access

#### 4b.8.1 `rememberPhysicsBody` — string-keyed lookup

Provides imperative access to a named body for force application outside the composable tree (e.g., in gesture callbacks or `LaunchedEffect` blocks):

```kotlin
package io.github.jayteealao.isometric.physics.compose

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.physics.core.RigidBody

/**
 * Returns the [RigidBody] with the given [id] from the enclosing [PhysicsScene]'s world.
 *
 * Returns null until the corresponding [PhysicsShape] or [PhysicsGroup] with matching
 * [id] has been composed and its body registered with the world.
 *
 * Only works for bodies that were given an explicit [id] in [PhysicsShape] or
 * [PhysicsGroup]. Anonymous bodies (id = null) are not addressable by name.
 *
 * Usage:
 * ```kotlin
 * PhysicsShape(geometry = Prism(), id = "ball")
 * val ball = rememberPhysicsBody("ball")
 * GestureConfig(
 *     onTap = { ball?.applyImpulse(Vector(0.0, 0.0, 5.0)) }
 * )
 * ```
 */
@Composable
fun rememberPhysicsBody(id: String): RigidBody? {
    val world = LocalPhysicsWorld.current
    // Do NOT use remember here. remember(id, world) { world.getBody(id) } runs once
    // and caches the result. If called before the corresponding PhysicsShape has
    // composed and added the body, it returns null permanently — the keys never
    // change, so remember never re-runs.
    //
    // world.getBody(id) is a HashMap lookup (O(1)) and safe to call on every
    // recomposition. The value is not reactive — callers who need to react when
    // the body becomes available should observe world state via collisionBeginFlow
    // or check for non-null in their gesture/effect handlers.
    return world.getBody(id)
}
```

#### 4b.8.2 `physicsBody()` — node-to-body bridge for tap handlers

`GestureConfig.onTap` delivers `TapEvent(x, y, node: IsometricNode?)`. When the tapped node is a `PhysicsShapeNode` or `PhysicsGroupNode`, callers typically want the backing `RigidBody` directly. A cast-based lookup inside user code is both verbose and coupled to internal type names. Provide a package-internal extension that hides the cast:

```kotlin
// NodeExtensions.kt — in isometric-physics-compose
import io.github.jayteealao.isometric.compose.runtime.IsometricNode
import io.github.jayteealao.isometric.physics.core.RigidBody

/**
 * Returns the [RigidBody] backing this node if it is a [PhysicsShapeNode] or
 * [PhysicsGroupNode], null otherwise. Safe to call on any node including
 * non-physics nodes.
 */
fun IsometricNode.physicsBody(): RigidBody? = when (this) {
    is PhysicsShapeNode -> body
    is PhysicsGroupNode -> body
    else -> null
}
```

Hero scenario for physics tap handling:

```kotlin
GestureConfig(
    onTap = { event ->
        event.node?.physicsBody()?.applyImpulse(Vector(0.0, 0.0, 3.0))
    }
)
```

This is the idiomatic Kotlin approach: an extension function on a type from another module, defined in the module that knows about both types. No new types, no new parameters on `GestureConfig`, no casting in user code.

**Module boundary**: `physicsBody()` is defined in `isometric-physics-compose` — it is the only module that knows both `IsometricNode` (from `isometric-compose`) and `PhysicsShapeNode`/`PhysicsGroupNode` (from itself). It cannot be defined in `isometric-compose` without creating a circular dependency.

#### 4b.8.3 `PhysicsBodyHandle` — v2 upgrade path

`rememberPhysicsBody` has a null-until-composed window: the body is null if called before the corresponding `PhysicsShape` has completed its first `DisposableEffect`. The HashMap lookup is also unidiomatic for callers who want a stable, non-string reference.

A v2 `PhysicsBodyHandle` eliminates both issues:

```kotlin
// v2 API — not in scope for Phase 4b
@Composable
fun rememberPhysicsHandle(): PhysicsBodyHandle = remember { PhysicsBodyHandle() }

class PhysicsBodyHandle internal constructor() {
    @Volatile var body: RigidBody? = null
        internal set
}

// PhysicsShape accepts an optional handle:
fun PhysicsScope.PhysicsShape(
    ...,
    handle: PhysicsBodyHandle? = null,   // ← new optional param
    ...
) {
    DisposableEffect(resolvedId) {
        world.addBody(resolvedId, ...)
        handle?.body = world.getBody(resolvedId)
        onDispose { world.removeBody(resolvedId); handle?.body = null }
    }
}
```

Call site:

```kotlin
val ballHandle = rememberPhysicsHandle()
PhysicsShape(geometry = Prism(), handle = ballHandle)
GestureConfig(
    onTap = { ballHandle.body?.applyImpulse(Vector(0.0, 0.0, 3.0)) }
)
```

No string key. No null-until-composed ambiguity (callers know it's nullable until the effect fires; the pattern is explicit). `rememberPhysicsBody(id)` stays as an alternative for callers whose data model already has a stable string id.

**Not in scope for Phase 4b** — `rememberPhysicsBody` is sufficient for v1. Track as a Phase 6 addition alongside the body lifecycle API.

### 4b.9 `PhysicsEventFlow`

Flow adapters wrapping the callback-based core event API. Lives in `isometric-physics-compose` because coroutines are an Android/Compose concern, not a JVM-core concern:

```kotlin
package io.github.jayteealao.isometric.physics.compose

import kotlinx.coroutines.flow.Flow
import io.github.jayteealao.isometric.physics.core.PhysicsWorld
import io.github.jayteealao.isometric.physics.event.CollisionEvent

/**
 * Returns a [Flow] of [CollisionEvent] for collision begin events.
 *
 * Suitable for use in [LaunchedEffect]:
 * ```kotlin
 * LaunchedEffect(world) {
 *     world.collisionBeginFlow().collect { event ->
 *         handleCollision(event.bodyA.id, event.bodyB.id)
 *     }
 * }
 * ```
 */
fun PhysicsWorld.collisionBeginFlow(): Flow<CollisionEvent>
fun PhysicsWorld.collisionStayFlow(): Flow<CollisionEvent>
fun PhysicsWorld.collisionEndFlow(): Flow<CollisionEvent>
```

### 4b.10 `PhysicsRaycastUtils`

```kotlin
package io.github.jayteealao.isometric.physics.compose

import io.github.jayteealao.isometric.compose.runtime.LocalIsometricEngine
import io.github.jayteealao.isometric.physics.query.Ray

/**
 * Converts a screen-space tap position to a 3D world ray for physics raycasting.
 *
 * Requires [LocalIsometricEngine] — only valid within an [IsometricScene] (or
 * [PhysicsScene], which wraps one). [LocalIsometricEngine] is already provided
 * by [IsometricScene] and does not need additional setup.
 *
 * Usage:
 * ```kotlin
 * val engine = LocalIsometricEngine.current
 * GestureConfig(
 *     onTap = { event ->
 *         val ray = screenToWorldRay(engine, event.x, event.y, viewportWidth, viewportHeight)
 *         val hit = world.raycast(ray)
 *     }
 * )
 * ```
 */
fun screenToWorldRay(
    engine: IsometricEngine,
    screenX: Double,
    screenY: Double,
    viewportWidth: Int,
    viewportHeight: Int
): Ray
```

### 4b.11 `Ground`

```kotlin
package io.github.jayteealao.isometric.physics.compose

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.compose.runtime.IsometricComposable
import io.github.jayteealao.isometric.physics.core.BodyConfig
import io.github.jayteealao.isometric.physics.core.BodyType

/**
 * A static ground plane at the given [elevation].
 *
 * Equivalent to a very large static [Prism] with [BodyType.Static], but
 * expressed as intent rather than geometry. The ground is invisible by default.
 *
 * @param elevation Z coordinate of the ground surface. Default 0.0.
 * @param friction Ground friction coefficient. Default 0.5.
 */
@IsometricComposable
@Composable
fun PhysicsScope.Ground(
    elevation: Double = 0.0,
    friction: Double = 0.5
) {
    PhysicsShape(
        id = "__ground__",
        geometry = GroundPlane,
        body = BodyConfig(
            type = BodyType.Static,
            material = PhysicsMaterial(friction = friction, restitution = 0.0, density = 1.0)
        ),
        position = Point(0.0, 0.0, elevation),
        visible = false
    )
}
```

### Hero Scenario (Layer 1 — Simple Path)

Per guideline §1, the first successful path must be achievable in a few lines:

```kotlin
@Composable
fun BouncingBoxes() {
    PhysicsScene(modifier = Modifier.fillMaxSize()) {
        Ground()
        repeat(5) { i ->
            PhysicsShape(
                geometry = Prism(),
                color = IsoColor.BLUE,
                position = Point(i.toDouble(), 0.0, 3.0 + i * 0.5)
            )
        }
    }
}
```

Four meaningful lines. No `BodyConfig`, no explicit `world`, no gesture setup required. This is the test for the Layer 1 path.

Note the removed `id` — anonymous bodies are sufficient for the hero scenario. Named ids are only needed when `rememberPhysicsBody` or `physicsBody()` tap routing is required.

---

## Phase 5: Joints

**Goal**: Connect bodies with constraint joints.

**Depends on**: Phase 3

All in `io.github.jayteealao.isometric.physics.joints`. No API surface changes from R13 beyond package name correction. Compose-layer composables `FixedJoint(bodyA, bodyB)` etc. follow the `@IsometricComposable` annotation pattern.

---

## Phase 6: Kinematic Bodies + Body Lifecycle

**Goal**: Kinematic bodies for programmatic movement. Clean API for creating and destroying bodies after initial composition.

**Depends on**: Phase 4b

`BodyType.Kinematic` is already in the enum. This phase focuses on the `setKinematicTarget` API and ensuring `PhysicsShape` composables can be conditionally included/excluded from the scene (standard Compose `if` blocks should work — `DisposableEffect.onDispose` in `PhysicsShape` handles removal).

---

## Phase 7: Debug Visualization + Profiler

**Goal**: Overlay rendering of collider wireframes, velocity vectors, sleep state, contact normals.

**Depends on**: Phase 4b

**`PhysicsDebugOverlay.kt`** — uses `AdvancedSceneConfig.onAfterDraw` to draw physics debug info on top of the rendered scene without a separate composable layer. This escape hatch already exists in `AdvancedSceneConfig`.

Add `debugConfig` to both `PhysicsScene` overloads (aligned with the signatures from Phase 4b.6 — note `physicsConfig` was removed from those signatures):

```kotlin
@Composable
fun PhysicsScene(
    modifier: Modifier = Modifier,
    config: SceneConfig = SceneConfig(),
    world: PhysicsWorld = rememberPhysicsWorld(),
    debugConfig: PhysicsDebugConfig = PhysicsDebugConfig.Disabled,  // NEW in Phase 7
    content: @Composable PhysicsScope.() -> Unit
)
```

`PhysicsDebugConfig.Disabled` is the default — zero overhead when not debugging. When enabled, `PhysicsScene` chains a debug draw function into `AdvancedSceneConfig.onAfterDraw` via `withRootNodeCallback`'s sibling method `withAfterDrawCallback()` — the same compiler-enforced pattern as `withRootNodeCallback()`. Add `withAfterDrawCallback()` to `AdvancedSceneConfig` in Phase 7 following the identical pattern established in Phase 0.5.

---

## Phase 8: Particles + Rope

**Goal**: Built-in particle emitter and Verlet-integrated rope/chain.

**Depends on**: Phase 4b

**`ParticleEmitter.kt`** — `PhysicsScope.ParticleEmitter(config: ParticleEmitterConfig)` composable following `@IsometricComposable` pattern.

**`Rope.kt`** — `PhysicsScope.Rope(bodyA: String, bodyB: String, segments: Int)` composable using `DistanceJoint` constraints between particle chain links.

---

## Phase 9: Benchmarks

**Goal**: Performance regression detection for the physics pipeline.

**Depends on**: Phase 7

Uses `isometric-physics-benchmark` module. Extends existing `isometric-benchmark` patterns. Key scenarios:
- `StackingScenario` — 200 stacked prisms, measure time-to-sleep
- `BroadPhaseScenario` — 500 dynamic bodies, measure broad-phase candidate count
- `SolverScenario` — 50-body pyramid stack, measure solver iteration cost
- `RaycastScenario` — 1000 bodies, measure raycast throughput

`PhysicsBenchmarkScreen` uses `AdvancedSceneConfig` with `onFlagsReady` and `onPreparedSceneReady` — the same hooks used by the existing `BenchmarkScreen`.

---

## Phase Summary

| Phase | Goal | Key deliverable | New modules |
|-------|------|-----------------|-------------|
| 0 | Core prerequisites | `Vector.ZERO`, `Octahedron.xyScale`, `onRootNodeReady`, `buildLocalContext()` hook, `open ShapeNode/GroupNode`, `withRootNodeCallback()` | None |
| 1 | Math + rigid body | `PhysicsWorld`, `RigidBody`, background thread, `TestPhysicsWorld` | `isometric-physics-core` |
| 2 | Collision detection | `BroadPhase`, `GjkDetector`, `ContactManifold`, debug dump | — |
| 3 | Collision response | `SequentialImpulseSolver`, islands, sleep | — |
| 4a | Events + raycasting + forces + CCD | `CollisionEvent`, `ForceField`, `Ray`, CCD solver | — |
| 4b | Compose integration | `PhysicsScene`, `PhysicsShape`, `PhysicsGroup`, `PhysicsScope`, `rememberPhysicsBody` | `isometric-physics-compose` |
| 5 | Joints | `FixedJoint`, `RevoluteJoint`, `DistanceJoint`, `PrismaticJoint` | — |
| 6 | Kinematic bodies | `setKinematicTarget`, conditional body lifecycle | — |
| 7 | Debug visualization | `PhysicsDebugOverlay`, `PhysicsProfilerOverlay` | — |
| 8 | Particles + rope | `ParticleEmitter`, `Rope` | — |
| 9 | Benchmarks | `PhysicsBenchmarkScreen`, 4 scenarios | `isometric-physics-benchmark` |

### Phase Gate Criteria

Each phase is complete when:
1. All new types compile with no warnings in the target modules
2. All new unit tests pass in a clean build
3. All existing tests in `isometric-core` and `isometric-compose` continue to pass
4. The hero scenario code (or its phase-appropriate equivalent) compiles and runs correctly on device
5. No `SideEffect` in new physics compose code — physics lifecycle hooks that wire imperative state (threads, callbacks that need teardown) use `DisposableEffect`; one-shot notification callbacks use `LaunchedEffect`
6. No `@IsometricComposable`-annotated composable is missing the annotation
