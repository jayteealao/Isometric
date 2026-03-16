# Physics System Research: Isometric Engine

> **Date**: 2026-03-12
> **Scope**: Collision detection, rigid body dynamics, Compose runtime integration, engine selection, and API design for the Isometric rendering library
> **Cross-references**: [WEBGPU_ANALYSIS.md](./WEBGPU_ANALYSIS.md) (GPU acceleration), [TEXTURE_SHADER_RESEARCH.md](./TEXTURE_SHADER_RESEARCH.md) (material system)

---

## Table of Contents

1. [Current Architecture Analysis](#1-current-architecture-analysis)
2. [Physics Engine Selection](#2-physics-engine-selection)
3. [Isometric-Specific Physics Challenges](#3-isometric-specific-physics-challenges)
4. [Collision Detection Architecture](#4-collision-detection-architecture)
5. [Rigid Body Dynamics](#5-rigid-body-dynamics)
6. [Compose Runtime Integration](#6-compose-runtime-integration)
7. [Declarative Physics API Design](#7-declarative-physics-api-design)
8. [Developer Experience](#8-developer-experience)
9. [Performance Patterns](#9-performance-patterns)
10. [Raycasting and Spatial Queries](#10-raycasting-and-spatial-queries)
11. [Advanced Physics Features](#11-advanced-physics-features)
12. [Lessons from Classic Isometric Games](#12-lessons-from-classic-isometric-games)
13. [Pitfalls and Mitigations](#13-pitfalls-and-mitigations)
14. [Implementation Roadmap](#14-implementation-roadmap)
15. [Sources](#15-sources)

---

## 1. Current Architecture Analysis

### 1.1 Three-Tier Architecture

The engine uses a clean three-tier separation that is already well-suited for physics integration:

```
┌───────────────────────────────────────────────┐
│  Composable DSL                               │  Shape(), Group(), Path(), Batch()
│  (IsometricComposables.kt)                    │  ForEach(), If()
├───────────────────────────────────────────────┤
│  Node Tree                                    │  GroupNode, ShapeNode, PathNode, BatchNode
│  (IsometricNode.kt + IsometricApplier.kt)     │  Dirty tracking & propagation
│                                               │  Transform accumulation (RenderContext)
├───────────────────────────────────────────────┤
│  Rendering Engine                             │  IsometricEngine
│  (IsometricEngine.kt + IsometricRenderer.kt)  │  3D→2D projection, depth sorting
│                                               │  Lighting, culling, caching
└───────────────────────────────────────────────┘
```

### 1.2 What Already Exists That Physics Can Leverage

| System | Location | How Physics Can Use It |
|--------|----------|----------------------|
| **Node tree** | `IsometricNode.kt:14-259` | Attach physics properties to existing nodes |
| **Transform system** | `IsometricNode.kt:42-48` | position, rotation, scale already exist per-node |
| **Dirty propagation** | `IsometricNode.kt:56-96` | `markDirty()` → single `sceneVersion++` — batch-friendly |
| **Applier batching** | `IsometricApplier.kt:19-36` | `onBeginChanges()`/`onEndChanges()` — ideal for body registration |
| **Spatial index** | `IsometricRenderer.kt:509-541` | 2D grid for hit testing — can be adapted for broad-phase |
| **Intersection utils** | `IntersectionUtils.kt:50-148` | Polygon-polygon intersection with AABB pre-check |
| **Point utilities** | `Point.kt:90-130` | Distance calculations, `distanceToSegment()` |
| **Hit testing** | `IsometricRenderer.kt:246-303` | Screen-to-node mapping with spatial index acceleration |
| **3D coordinate system** | `Point.kt` | Full 3D `(x, y, z)` — physics operates in world space |
| **Benchmark harness** | `BenchmarkOrchestrator.kt` | Can measure physics performance with existing infrastructure |

### 1.3 What Is Missing for Physics

| Gap | Impact | Difficulty |
|-----|--------|-----------|
| No velocity/acceleration per node | Cannot simulate motion | Low — add fields to node |
| No physics update loop | No frame-driven simulation step | Low — `LaunchedEffect` + `withFrameNanos` |
| No 3D AABB computation | Cannot do broad-phase collision | Low — analytic per shape type |
| No collision detection | No object interaction | Medium — GJK + EPA implementation |
| No forces/constraints | No gravity, impulses, joints | Medium — sequential impulse solver |
| No rigid body abstraction | Shapes are purely visual | Medium — `PhysicsBody` wrapper |
| No contact manifolds | No stable resting contacts | Medium-High — Sutherland-Hodgman clipping |
| No sleep system | All bodies active every frame | Low — velocity threshold + timer |

### 1.4 Coordinate System

The engine uses a right-handed 3D coordinate system:

- **X-axis**: Projects at 30° to the right on screen
- **Y-axis**: Projects at 150° (perpendicular in isometric view)
- **Z-axis**: Vertical on screen, scaled by `scale` factor (default: 70)
- **Depth metric**: `depth = x + y - 2z` (lower = farther from camera)
- **View direction**: Proportional to `(1, 1, -2)` normalized

The isometric projection (`IsometricEngine.kt:38-48`) converts world space to screen space:

```
screenX = originX + x·scale·cos(30°) + y·scale·cos(150°)
screenY = originY - x·scale·sin(30°) - y·scale·sin(150°) - z·scale
```

**Critical principle**: Physics operates entirely in world space `(x, y, z)`. The isometric projection is a rendering concern only. This is already enforced by the architecture — shapes store `Point(x, y, z)` vertices, projection happens in `IsometricEngine.prepare()`.

### 1.5 Current Data Flow

```
User updates Compose state
  ↓
Composable recomposes (adds/removes/updates nodes)
  ↓
IsometricApplier mutates node tree
  ↓
markDirty() propagates to root
  ↓
sceneVersion++ (single MutableState<Long>)
  ↓
Canvas reads sceneVersion → triggers Draw phase only
  ↓
renderer.ensurePreparedScene() → rebuildCache() if dirty
  ↓
engine.prepare(): project 3D→2D, cull, light, depth sort
  ↓
Draw from cached Compose paths or native Canvas
  ↓
clearDirty()
```

**Where physics inserts**: Between "Compose recomposes" and "markDirty()", a physics step reads the node tree, simulates, and writes back updated positions. The existing dirty propagation then handles everything downstream.

---

## 2. Physics Engine Selection

### 2.1 Engine Comparison Matrix

| Engine | Language | 2D/3D | Maintained | JVM Perf | Mobile | License | Recommendation |
|--------|----------|-------|------------|----------|--------|---------|----------------|
| **dyn4j** | Java | 2D | Yes (v5.0.2, 2024) | Good | Yes | BSD | **Top pick** |
| Box2D v3 (NDK) | C | 2D | Yes (2024) | Excellent | Yes | MIT | Best raw perf |
| Rapier (JNI) | Rust | 2D/3D | Community | Excellent | Possible | Apache 2.0 | Deterministic |
| Jolt-JNI | C++/Java | 3D | Yes | Excellent | Yes | MIT | Best 3D |
| Libbulletjme | C++/Java | 3D | Yes | Good | Yes | BSD | Proven 3D |
| JBox2D | Java | 2D | **No** (2013) | Fair | Yes | BSD | Avoid |
| jBullet | Java | 3D | **No** (2008) | Fair | Maybe | ZLIB | Avoid |
| Custom | Kotlin | Any | N/A | Tunable | Yes | N/A | Simple needs |

### 2.2 dyn4j — Primary Recommendation

**dyn4j** is a 100% Java, zero-dependency 2D physics engine. It is the strongest choice for this project because:

- **Pure JVM**: No JNI, no native libraries, no platform-specific builds
- **Actively maintained**: v5.0.2 with 10x CCD performance improvement, N-body joints
- **Both SAT and GJK**: Interchangeable narrow-phase detectors
- **Sweep-and-prune**: Built-in broad-phase options (SAP, DynamicAABBTree, BruteForce)
- **Proven in Compose**: Used by ComposePhysicsLayout library
- **2000+ JUnit tests**: Battle-tested collision detection
- **BSD license**: Commercial-friendly

```kotlin
// dyn4j API from Kotlin
val world = World<Body>()
world.gravity = Vector2(0.0, -9.8)

val body = Body()
body.addFixture(Geometry.createRectangle(1.0, 1.0))
body.translate(1.0, 4.0)
body.setMass(MassType.NORMAL)
world.addBody(body)

world.step(1)  // one step at default frequency
```

**Limitation**: dyn4j is 2D. For the isometric engine's 3D world space, there are two strategies:

1. **Project to 2D for physics**: Run dyn4j on the XY-plane, handle Z (height) separately with simple gravity/ground checks. Suitable for most isometric games.
2. **Dual-plane physics**: Run separate dyn4j worlds for XY and XZ planes, combining results. More complex but handles true 3D interactions.

### 2.3 Box2D v3 via NDK — Performance Alternative

Box2D v3 (August 2024) provides a pure-C API with multithreading and SIMD optimizations. Integration via Kotlin Multiplatform:

- **Android**: JNI bridge with NDK + CMake → `.so` per ABI
- **Performance**: 3-4x faster than JBox2D, eliminates GC pressure
- **Overhead**: JNI call overhead manageable if batched (one call per step, bulk read positions)

**When to choose Box2D over dyn4j**: Only if physics becomes a measured bottleneck at >500 dynamic bodies. The build complexity (native toolchain, per-platform binaries) is significant.

### 2.4 Rapier via JNI — Deterministic Alternative

Rapier (Rust) provides cross-platform deterministic simulation (IEEE 754-2008 compliant, bit-exact across platforms). Available via `rapier-ffi` community Java bindings.

**When to choose Rapier**: Only if deterministic physics is required (replay systems, lockstep multiplayer). The Rust FFI overhead and community-maintained bindings add risk.

### 2.5 Custom Lightweight Physics — Simplest Path

For basic use cases (gravity, AABB collision, simple bounce), a custom implementation is trivially small:

```kotlin
// Entire "physics" for simple isometric gravity + ground collision
fun simplePhysicsStep(bodies: List<PhysicsBody>, dt: Double) {
    for (body in bodies) {
        if (body.isStatic) continue
        body.velocity.z += GRAVITY * dt
        body.position = body.position.translate(
            body.velocity.x * dt, body.velocity.y * dt, body.velocity.z * dt
        )
        // Ground collision
        if (body.position.z < body.groundZ) {
            body.position = Point(body.position.x, body.position.y, body.groundZ)
            body.velocity.z = -body.velocity.z * body.restitution
        }
    }
}
```

**When to choose custom**: When the use case is limited to gravity, simple AABB collisions, and basic bounce. Avoids any dependency. Graduate to dyn4j when stacking, friction, or arbitrary shapes are needed.

### 2.6 Hybrid Approach — Recommended Strategy

The recommended strategy combines the best of both worlds:

| Layer | Implementation |
|-------|---------------|
| **Broad-phase** | Custom spatial hash grid (tuned for isometric world) |
| **Narrow-phase** | dyn4j's GJK/SAT (or custom GJK with shape-specific support functions) |
| **Solver** | dyn4j's sequential impulse solver (or custom for simple cases) |
| **Isometric-specific** | Custom depth sorting, projection, screen-to-world conversion |
| **Integration** | Custom Compose integration via node tree + dirty tracking |

This allows starting with a custom lightweight implementation and progressively replacing components with dyn4j as complexity grows.

---

## 3. Isometric-Specific Physics Challenges

### 3.1 World Space vs Screen Space

**Strong recommendation: Always run physics in world space.**

| Approach | Pros | Cons |
|----------|------|------|
| **World-space (3D)** | Natural collision shapes, gravity works correctly, height/stacking trivial | Need screen-to-world for input |
| **Screen-space (2D)** | Input mapping is direct | Shapes are distorted diamonds, gravity wrong, height faked |

The engine already stores shapes in 3D world space (`Point(x, y, z)`) and projects to screen in `prepare()`. Physics should operate on the same world coordinates.

### 3.2 2D Engine in 3D Space

Since most practical physics engines are 2D (dyn4j, Box2D) while isometric worlds have 3 axes, the standard pattern is:

```
3D Isometric World                2D Physics Engine (dyn4j)
┌───────────────────┐            ┌──────────────────┐
│ x (right)         │  map to →  │ x                │
│ y (forward)       │  map to →  │ y                │
│ z (up/height)     │  separate  │ (handled manually)│
└───────────────────┘            └──────────────────┘
```

- **XY plane**: Run through the 2D physics engine for horizontal collision, friction, sliding
- **Z axis**: Handle separately with simple gravity, ground detection, and height-based collision filtering

This is how classic isometric games (Diablo, Age of Empires) handle physics:
- Characters have circle/rectangle colliders on the XY ground plane
- Height (Z) determines visual depth ordering but not XY collision
- Jumping/falling uses simple parabolic Z-velocity with ground checks

### 3.3 Depth Ordering with Physics Objects

Moving physics objects change depth constantly, requiring per-frame re-sort. The existing O(n²) topological sort in `sortPaths()` (`IsometricEngine.kt:278-335`) becomes more expensive with physics because:

1. **Every frame has position changes** → cache invalidation every frame
2. **Stacked objects** break simple sort rules (transitivity may not hold)
3. **Fast objects** may visually "pop" between depth layers

**Mitigations**:
- The existing `PreparedScene` cache still helps for static objects — only dirty physics nodes need re-sorting
- Spatial index partitioning limits the pair count for depth comparison
- Physics sleep system (Section 5.4) reduces the number of active movers per frame

### 3.4 Stacking in Isometric View

Stacking is one of the hardest rendering problems in isometric games. A box sitting on another box must render in the correct order regardless of position.

**Physics helps here**: The physics solver handles contact resolution and stacking stability through its iterative constraint solver. The renderer extracts positions from the physics bodies each frame and feeds them to the existing topological sort. The physics engine guarantees physically plausible positions; the renderer handles visual ordering.

### 3.5 Tunneling Prevention

Fast-moving objects can pass through thin walls between physics steps. Solutions ranked by cost:

1. **Increase physics tick rate**: 120Hz instead of 60Hz — doubles CPU cost but halves max tunneling distance
2. **Expand broad-phase AABBs**: `aabb += velocity * dt` — cheap, catches most cases, may cause false positives
3. **CCD per-body flag**: dyn4j's `body.setBullet(true)` — enables continuous collision for specific bodies
4. **Raycasting for projectiles**: Skip physics entirely, use ray-shape intersection for bullets/arrows
5. **Sub-stepping**: `repeat(4) { world.step(dt/4) }` — linear cost increase, eliminates tunneling for moderate speeds

### 3.6 Slope/Ramp Physics

For sloped surfaces in isometric tile-based games:

```kotlin
// Each tile can define height at its four corners
data class SlopeTile(val h00: Float, val h10: Float, val h01: Float, val h11: Float)

fun getHeightAt(tile: SlopeTile, localX: Float, localY: Float): Float {
    // Bilinear interpolation
    val h0 = tile.h00 * (1 - localX) + tile.h10 * localX
    val h1 = tile.h01 * (1 - localX) + tile.h11 * localX
    return h0 * (1 - localY) + h1 * localY
}
```

**Key principle**: Slopes are non-solid for XY collision. A post-processing step projects entities onto the slope surface. This prevents entities getting stuck on slope edges.

---

## 4. Collision Detection Architecture

### 4.1 Two-Phase Pipeline

All performant collision systems use a two-phase approach:

```
All N objects
  ↓
Broad Phase: O(N log N) or O(N) — find POTENTIALLY colliding pairs
  ↓
Candidate pairs (k << N²)
  ↓
Narrow Phase: O(k × (vA + vB)) — precise collision test per pair
  ↓
Contact manifolds with penetration depth, normal, contact points
  ↓
Constraint Solver: resolve overlaps and apply impulses
```

Without broad-phase, 100 objects require 4,950 pair tests. With a spatial grid (10×10 grid, ~1 object per cell), each object checks ~8 neighbors → ~400 tests. That is a 12× reduction.

### 4.2 Broad Phase: Spatial Hash Grid

A spatial hash grid is the simplest and most effective broad-phase for isometric scenes with similarly-sized objects:

```kotlin
class SpatialHashGrid3D(
    val cellSize: Double,
    initialCapacity: Int = 1024
) {
    private val cells = HashMap<Long, MutableList<Int>>(initialCapacity)

    private fun hash(cx: Int, cy: Int, cz: Int): Long {
        return (cx * 73856093L) xor (cy * 19349663L) xor (cz * 83492791L)
    }

    fun insert(objectId: Int, aabb: AABB) {
        val minCX = floor(aabb.minX / cellSize).toInt()
        val maxCX = floor(aabb.maxX / cellSize).toInt()
        // ... same for Y and Z
        for (cx in minCX..maxCX)
            for (cy in minCY..maxCY)
                for (cz in minCZ..maxCZ)
                    cells.getOrPut(hash(cx, cy, cz)) { mutableListOf() }.add(objectId)
    }

    fun query(aabb: AABB): Set<Int> { /* collect from overlapping cells */ }
    fun clear() { cells.clear() }
}
```

**Cell size**: 2× median object size. For the engine's default unit-cube Prism shapes, a cell size of 2.0 world units is optimal.

**Comparison of broad-phase structures**:

| Structure | Insert | Remove | Update | Query | Best For |
|-----------|--------|--------|--------|-------|----------|
| Hash Grid | O(1) | O(1) | O(1) rebuild | O(k) | <1000 similar-size objects |
| Octree | O(d) | O(d) | O(d) | O(d+k) | Clustered objects |
| AABB Tree | O(log n) | O(log n) | O(log n)* | O(log n + k) | General purpose |
| Sweep-and-Prune | O(n) init | O(n) | O(n + swaps) | O(n + pairs) | High frame coherence |

*amortized with fattened margins

### 4.3 AABB Computation per Shape Type

Each shape type has a known analytic AABB, avoiding vertex iteration:

```kotlin
data class AABB(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double
) {
    fun intersects(other: AABB): Boolean {
        return minX <= other.maxX && maxX >= other.minX &&
               minY <= other.maxY && maxY >= other.minY &&
               minZ <= other.maxZ && maxZ >= other.minZ
    }
}

// Prism(origin, dx, dy, dz) — directly an axis-aligned box
fun Prism.computeAABB(): AABB = AABB(
    origin.x, origin.y, origin.z,
    origin.x + dx, origin.y + dy, origin.z + dz
)

// Pyramid(origin, dx, dy, dz) — same bounding box as its containing prism
fun Pyramid.computeAABB(): AABB = AABB(
    origin.x, origin.y, origin.z,
    origin.x + dx, origin.y + dy, origin.z + dz
)

// Cylinder(origin, radius, height) — circle extruded along Z
fun Cylinder.computeAABB(): AABB = AABB(
    origin.x - radius, origin.y - radius, origin.z,
    origin.x + radius, origin.y + radius, origin.z + height
)

// Octahedron — fits within unit cube from origin
fun Octahedron.computeAABB(): AABB = AABB(
    origin.x, origin.y, origin.z,
    origin.x + 1.0, origin.y + 1.0, origin.z + 1.0
)

// Stairs — fits within unit cube by construction
fun Stairs.computeAABB(): AABB = AABB(
    origin.x, origin.y, origin.z,
    origin.x + 1.0, origin.y + 1.0, origin.z + 1.0
)
```

### 4.4 Narrow Phase: GJK Algorithm

GJK (Gilbert-Johnson-Keerthi) is the ideal narrow-phase for this engine because it works with **any** convex shape through a single abstraction — the support function:

```kotlin
interface ConvexShape {
    /** Returns the point on the shape farthest in the given direction */
    fun support(direction: Vector): Point
}
```

Each shape type implements `support()` analytically:

```kotlin
// Prism (Box): select vertex with maximum dot product
class PrismCollider(val min: Point, val max: Point) : ConvexShape {
    override fun support(direction: Vector): Point = Point(
        if (direction.i >= 0) max.x else min.x,
        if (direction.j >= 0) max.y else min.y,
        if (direction.k >= 0) max.z else min.z
    )
}

// Cylinder: analytic support — decompose into axial and radial
class CylinderCollider(
    val center: Point, val halfHeight: Double, val radius: Double
) : ConvexShape {
    override fun support(direction: Vector): Point {
        val radialLen = sqrt(direction.i * direction.i + direction.j * direction.j)
        val z = if (direction.k >= 0) center.z + halfHeight else center.z - halfHeight
        return if (radialLen > 1e-10) {
            val scale = radius / radialLen
            Point(center.x + direction.i * scale, center.y + direction.j * scale, z)
        } else {
            Point(center.x, center.y, z)
        }
    }
}

// Pyramid/Octahedron: vertex enumeration (5-8 vertices)
class VertexCollider(val vertices: Array<Point>) : ConvexShape {
    override fun support(direction: Vector): Point {
        var best = vertices[0]
        var bestDot = dot(direction, best)
        for (i in 1 until vertices.size) {
            val d = dot(direction, vertices[i])
            if (d > bestDot) { bestDot = d; best = vertices[i] }
        }
        return best
    }
}
```

**GJK core algorithm** (typically 5-20 iterations, near-constant with warm-starting):

```kotlin
fun gjk(shapeA: ConvexShape, shapeB: ConvexShape): Boolean {
    var direction = Vector(1.0, 0.0, 0.0)
    val simplex = mutableListOf<Point>()

    simplex.add(minkowskiSupport(shapeA, shapeB, direction))
    direction = negate(toVector(simplex[0]))

    for (iteration in 0 until 64) {
        val a = minkowskiSupport(shapeA, shapeB, direction)
        if (dot(toVector(a), direction) < 0) return false  // No intersection
        simplex.add(a)
        if (handleSimplex(simplex, direction)) return true  // Contains origin
    }
    return false
}

private fun minkowskiSupport(a: ConvexShape, b: ConvexShape, d: Vector): Point {
    val pA = a.support(d)
    val pB = b.support(negate(d))
    return Point(pA.x - pB.x, pA.y - pB.y, pA.z - pB.z)
}
```

**For Stairs**: Decompose into convex sub-shapes (each step is a Prism). Run GJK against each sub-shape. The `Knot` shape similarly decomposes into prisms and custom paths.

### 4.5 Penetration Depth: EPA

When GJK confirms intersection, EPA (Expanding Polytope Algorithm) finds the minimum translation vector (penetration depth + direction):

```kotlin
data class EpaResult(
    val normal: Vector,      // Penetration direction (from B into A)
    val depth: Double,       // Penetration depth
    val contactPoint: Point  // Approximate contact point
)

fun epa(
    shapeA: ConvexShape, shapeB: ConvexShape,
    gjkSimplex: List<Point>,
    tolerance: Double = 1e-6
): EpaResult {
    // 1. Initialize polytope from GJK's tetrahedron
    // 2. Find face closest to origin
    // 3. Expand with new Minkowski support point
    // 4. Repeat until support distance ≈ face distance
    // Returns (normal, depth) of minimum penetration
}
```

**Complexity**: O(k × (n + m)) per collision pair, where k is typically 10-30 EPA iterations.

### 4.6 Contact Manifold Generation

For stable physics response, generate contact manifolds using Sutherland-Hodgman clipping:

1. Identify the **reference face** (most aligned with collision normal) on one shape
2. Identify the **incident face** (most opposed to collision normal) on the other shape
3. Clip the incident face polygon against the reference face's side planes
4. Keep points behind the reference face — these form the contact manifold

```kotlin
data class ContactManifold(
    val normal: Vector,
    val points: List<ContactPoint>
)

data class ContactPoint(
    val position: Point,
    val penetration: Double
)
```

**Persistent manifolds**: Cache contacts across frames. Each frame, add new deepest-penetration points and remove stale ones. When above 4 points, reduce by keeping the deepest + 3 that maximize contact area. This avoids recomputing the full manifold every frame and dramatically improves stacking stability.

---

## 5. Rigid Body Dynamics

### 5.1 Rigid Body Data Structure

```kotlin
data class RigidBody(
    // Spatial state
    var position: Point,
    var orientation: Quaternion,
    var velocity: Vector,
    var angularVelocity: Vector,

    // Mass properties
    val mass: Double,
    val inverseMass: Double,         // 0 for static bodies
    val inertia: Matrix3x3,
    val inverseInertia: Matrix3x3,   // Zero matrix for static bodies

    // Material
    val restitution: Double = 0.3,
    val friction: Double = 0.5,

    // Simulation control
    var sleeping: Boolean = false,
    var sleepTimer: Double = 0.0,
    var force: Vector = Vector.ZERO,
    var torque: Vector = Vector.ZERO,

    // Link to rendering
    var node: IsometricNode? = null
)
```

### 5.2 Integration Method

**Semi-Implicit Euler** — the standard for game physics (used by Box2D, Bullet, dyn4j, PhysX):

```kotlin
fun integrateSymplecticEuler(body: RigidBody, dt: Double, gravity: Vector) {
    if (body.sleeping || body.inverseMass == 0.0) return

    // Update velocity first (semi-implicit — this is what makes it stable)
    body.velocity += (gravity + body.force * body.inverseMass) * dt
    body.angularVelocity += body.inverseInertia * body.torque * dt

    // Then update position using NEW velocity
    body.position = body.position.translate(
        body.velocity.i * dt, body.velocity.j * dt, body.velocity.k * dt
    )
    body.orientation = integrateOrientation(body.orientation, body.angularVelocity, dt)

    // Damping
    body.velocity *= (1.0 - body.linearDamping * dt)
    body.angularVelocity *= (1.0 - body.angularDamping * dt)

    // Clear per-frame forces
    body.force = Vector.ZERO
    body.torque = Vector.ZERO
}
```

**Why Semi-Implicit Euler over RK4**: Semi-implicit Euler is symplectic (conserves energy over long timescales), requires only 1 evaluation per step (vs 4 for RK4), and interfaces cleanly with impulse-based solvers. RK4's higher accuracy is wasted when the constraint solver dominates the result.

### 5.3 Moment of Inertia per Shape

```kotlin
// Prism/Box (mass m, dimensions w × h × d)
fun boxInertia(mass: Double, w: Double, h: Double, d: Double): Matrix3x3 {
    val m12 = mass / 12.0
    return Matrix3x3.diagonal(
        m12 * (h * h + d * d),  // Ixx
        m12 * (w * w + d * d),  // Iyy
        m12 * (w * w + h * h)   // Izz
    )
}

// Cylinder (mass m, radius r, height h)
fun cylinderInertia(mass: Double, r: Double, h: Double): Matrix3x3 {
    return Matrix3x3.diagonal(
        mass * (3 * r * r + h * h) / 12.0,  // Ixx (transverse)
        mass * (3 * r * r + h * h) / 12.0,  // Iyy (transverse)
        mass * r * r / 2.0                    // Izz (axial)
    )
}

// Pyramid (approximation: solid cone)
fun pyramidInertia(mass: Double, baseW: Double, baseD: Double, h: Double): Matrix3x3 {
    return Matrix3x3.diagonal(
        mass * (4 * h * h + 3 * baseW * baseW) / 80.0,
        mass * (4 * h * h + 3 * baseD * baseD) / 80.0,
        mass * (baseW * baseW + baseD * baseD) / 20.0
    )
}

// Octahedron (regular, edge length a) — uniform: I = ma²/10
fun octahedronInertia(mass: Double, edge: Double): Matrix3x3 {
    val i = mass * edge * edge / 10.0
    return Matrix3x3.diagonal(i, i, i)
}
```

### 5.4 Impulse-Based Collision Response

```kotlin
fun resolveCollision(
    a: RigidBody, b: RigidBody,
    contact: ContactPoint, normal: Vector
) {
    val rA = vectorFromPoints(a.position, contact.position)
    val rB = vectorFromPoints(b.position, contact.position)

    // Relative velocity at contact point
    val vRel = (b.velocity + cross(b.angularVelocity, rB)) -
               (a.velocity + cross(a.angularVelocity, rA))

    val vRelAlongNormal = dot(vRel, normal)
    if (vRelAlongNormal > 0) return  // Separating — no impulse needed

    val e = minOf(a.restitution, b.restitution)

    // Effective inverse mass at contact point
    val rAxN = cross(rA, normal)
    val rBxN = cross(rB, normal)
    val effectiveInvMass = a.inverseMass + b.inverseMass +
        dot(cross(a.inverseInertia * rAxN, rA), normal) +
        dot(cross(b.inverseInertia * rBxN, rB), normal)

    val j = -(1 + e) * vRelAlongNormal / effectiveInvMass

    // Apply impulse
    val impulse = normal * j
    a.velocity -= impulse * a.inverseMass
    b.velocity += impulse * b.inverseMass
    a.angularVelocity -= a.inverseInertia * cross(rA, impulse)
    b.angularVelocity += b.inverseInertia * cross(rB, impulse)
}
```

### 5.5 Sequential Impulse Solver

The solver iterates over all contact constraints, applying impulses with accumulated clamping:

```kotlin
class SequentialImpulseSolver(val iterations: Int = 10) {
    fun solve(contacts: List<ContactConstraint>, dt: Double) {
        // Warm start from previous frame's accumulated impulses
        for (contact in contacts) warmStart(contact)

        // Iterate
        repeat(iterations) {
            for (contact in contacts) {
                val lambda = computeImpulse(contact, dt)
                val oldAccum = contact.accumulatedNormalImpulse
                contact.accumulatedNormalImpulse = maxOf(0.0, oldAccum + lambda)
                applyImpulse(contact, contact.accumulatedNormalImpulse - oldAccum)
                solveFriction(contact)
            }
        }
    }
}
```

**Warm starting** is essential for stacking stability — it reuses impulses from the previous frame as initial guesses, providing 2-3× faster convergence.

### 5.6 Position Correction

After velocity resolution, objects may still overlap. Approaches ranked by complexity:

1. **Baumgarte Stabilization** (simplest): Feed penetration back as velocity bias
   ```kotlin
   val correction = maxOf(penetration - slop, 0.0) * 0.2 / dt
   // Add to velocity constraint bias
   ```

2. **Split Impulse**: Separate position correction from velocity. More stable stacking.

3. **NGS (Non-linear Gauss-Seidel)**: Direct position constraint solving. Most accurate, most expensive.

**Recommendation**: Start with Baumgarte (one line of code). Graduate to split impulse if stacking instability appears.

### 5.7 Sleep System

Sleeping bodies are excluded from simulation, saving CPU:

```kotlin
class SleepSystem(
    val linearThreshold: Double = 0.01,   // m/s
    val angularThreshold: Double = 0.05,  // rad/s
    val timeToSleep: Double = 0.5         // seconds
) {
    fun update(body: RigidBody, dt: Double) {
        if (body.velocity.magnitude() < linearThreshold &&
            body.angularVelocity.magnitude() < angularThreshold) {
            body.sleepTimer += dt
            if (body.sleepTimer >= timeToSleep) {
                body.sleeping = true
                body.velocity = Vector.ZERO
                body.angularVelocity = Vector.ZERO
            }
        } else {
            body.sleepTimer = 0.0
            body.sleeping = false
        }
    }
}
```

**Island-based sleep**: Group interconnected bodies (through contacts/joints) using Union-Find. An island sleeps only when ALL bodies are below threshold. Wake the entire island when any body is disturbed.

---

## 6. Compose Runtime Integration

### 6.1 The Fundamental Tension

Physics engines operate imperatively (step world → read positions → apply forces). Compose is declarative (describe what should exist given current state). Three patterns bridge this gap:

### 6.2 Pattern A: Physics-Driven State Injection

The physics engine runs on its own loop and pushes positions into Compose state. Simplest but creates tight coupling.

```kotlin
class PhysicsWorld {
    fun step(dt: Float) {
        engine.step(dt)
        bodies.forEach { body ->
            body.composablePosition.value = body.enginePosition
        }
    }
}
```

**Problem**: Creates O(N) `MutableState` objects, causing O(N) recomposition scopes.

### 6.3 Pattern B: Direct Node Mutation — **Recommended**

Mutate node positions directly, trigger a single `markDirty()` on root. This aligns perfectly with the existing architecture:

```kotlin
fun physicsStep(rootNode: GroupNode, dt: Float) {
    for (body in physicsBodies) {
        // Directly mutate the node — no Compose state involved
        body.node.position = Point(body.x, body.y, body.z)
        body.node.rotation = body.angle
    }
    // Single dirty propagation triggers one Canvas redraw
    rootNode.markDirty()
}
```

**Why this is optimal**: The existing pattern in `IsometricScene.kt:105-114` uses a single `sceneVersion` state that the Canvas reads. The Canvas lambda at line 279-280 only triggers the Draw phase, not recomposition. Physics updates N bodies but only causes 1 state read → 1 draw.

### 6.4 Pattern C: Hybrid Compose Lifecycle + Direct Mutation

Compose manages the **lifecycle** (creation/disposal of physics bodies). The physics loop manages **per-frame position updates** via direct node mutation. This is the recommended pattern.

```kotlin
@Composable
fun IsometricScope.PhysicsBody(
    shape: Shape,
    bodyConfig: BodyConfig,
    color: IsoColor = LocalDefaultColor.current,
) {
    // Compose manages lifecycle — creates/removes the node
    ComposeNode<PhysicsShapeNode, IsometricApplier>(
        factory = { PhysicsShapeNode(shape, color, bodyConfig) },
        update = {
            // Only structural/config changes go through Compose
            set(bodyConfig) { this.bodyConfig = it; reconfigureBody() }
            set(color) { this.color = it; markDirty() }
        }
    )
    // Position updates happen OUTSIDE Compose via the physics loop
}
```

### 6.5 Frame Loop Integration

**`LaunchedEffect` + `withFrameNanos`** — the standard Compose frame loop:

```kotlin
@Composable
fun PhysicsLoop(world: PhysicsWorld, rootNode: GroupNode) {
    var lastFrameNanos by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L) {
                    val deltaNanos = frameTimeNanos - lastFrameNanos
                    val deltaSeconds = deltaNanos / 1_000_000_000.0

                    // Step physics with fixed timestep (see Section 9.3)
                    world.step(deltaSeconds)

                    // Sync all positions to nodes
                    world.syncToNodes()

                    // Single dirty propagation
                    rootNode.markDirty()
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }
}
```

`withFrameNanos` is a suspending function synchronized with frame production. It does **not** trigger recomposition — the state mutations inside are what trigger Canvas invalidation.

**Alternative — Choreographer**: For tighter VSYNC control on Android, `Choreographer.FrameCallback` provides the actual VSYNC timestamp (more accurate delta than the time the callback runs). Use this when the `LaunchedEffect` pattern shows frame pacing issues.

### 6.6 State Management Strategy

| Approach | Overhead | Thread Safety | Use Case |
|----------|----------|---------------|----------|
| `MutableState<T>` per body | O(N) recomposition | Main thread only | Never for physics |
| `StateFlow<T>` per body | Medium | Thread-safe | External observation only |
| Single `mutableStateOf(version)` | O(1) | Main thread | **Canvas invalidation — existing pattern** |
| Direct mutation + `markDirty()` | Near-zero | Volatile-guarded | **Physics position updates — recommended** |

### 6.7 Physics Body Registration via Applier

The existing `IsometricApplier.onEndChanges()` batching is the ideal hook for registering/unregistering physics bodies:

```kotlin
// In a PhysicsAwareApplier extension
override fun onEndChanges() {
    super.onEndChanges()
    // Register any new PhysicsShapeNodes with the physics world
    for (group in modifiedGroups) {
        group.children.filterIsInstance<PhysicsShapeNode>()
            .filter { it.physicsBody == null }
            .forEach { physicsWorld.registerBody(it) }
    }
}
```

---

## 7. Declarative Physics API Design

### 7.1 Three-Tier Progressive Disclosure

**Tier 1: Zero-config (2 lines)**
```kotlin
IsometricScene {
    PhysicsShape(shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0))
    // Falls with default gravity, default mass, default collider
}
```

**Tier 2: Configured (DSL builder)**
```kotlin
IsometricScene(physics = physicsConfig { gravity = Vector(0.0, 0.0, -9.81) }) {
    PhysicsShape(
        shape = Prism(Point.ORIGIN, 1.0, 1.0, 1.0),
        body = bodyConfig {
            mass = 2f
            material { restitution = 0.7f }
        }
    )
    PhysicsShape(
        shape = Prism(Point(0.0, 0.0, -1.0), 10.0, 10.0, 0.1),
        body = bodyConfig { isStatic = true }
    )
}
```

**Tier 3: Full control (direct engine access)**
```kotlin
IsometricScene(physics = physicsConfig { gravity = Vector(0.0, 0.0, -9.81) }) {
    val world = LocalPhysicsWorld.current

    PhysicsShape(
        shape = myShape,
        body = bodyConfig {
            mass = 2f
            collider {
                shape = ColliderShape.ConvexHull(customPoints)
                filter = CollisionFilter(
                    category = CollisionCategory.PLAYER,
                    collidesWith = CollisionCategory.TERRAIN or CollisionCategory.ENEMY
                )
            }
        },
        onCollision = { event ->
            when (event) {
                is CollisionEvent.Begin -> handleContact(event)
                is CollisionEvent.End -> cleanupContact(event)
            }
        }
    )

    LaunchedEffect(Unit) {
        world.addForceField(center = Point(5.0, 5.0, 0.0), strength = 10f)
    }
}
```

### 7.2 Kotlin DSL with @DslMarker

```kotlin
@DslMarker
annotation class PhysicsDsl

@PhysicsDsl
class BodyConfigBuilder {
    var mass: Float = 1f
    var material: PhysicsMaterial = PhysicsMaterial.Default
    var isStatic: Boolean = false
    var isSensor: Boolean = false
    var gravityScale: Float = 1f
    var linearDamping: Float = 0f
    var angularDamping: Float = 0.01f
    private var _collider: ColliderConfig? = null

    fun collider(block: ColliderScope.() -> Unit) {
        _collider = ColliderScope().apply(block).build()
    }

    fun material(block: PhysicsMaterialBuilder.() -> Unit) {
        material = PhysicsMaterialBuilder().apply(block).build()
    }

    internal fun build() = BodyConfig(/* ... */)
}

fun bodyConfig(block: BodyConfigBuilder.() -> Unit): BodyConfig =
    BodyConfigBuilder().apply(block).build()
```

The `@PhysicsDsl` marker prevents accidental access to outer scopes in nested lambdas — e.g., `collider { }` cannot accidentally access `mass` from the outer `bodyConfig { }`.

### 7.3 Physics-Aware Scene Composable

```kotlin
@Composable
fun PhysicsIsometricScene(
    modifier: Modifier = Modifier,
    physics: PhysicsConfig = PhysicsConfig(),
    onTap: ((Float, Float, IsometricNode?) -> Unit)? = null,
    content: @Composable IsometricScope.() -> Unit
) {
    val world = remember { PhysicsWorld(physics) }

    // Provide physics world to children via CompositionLocal
    CompositionLocalProvider(LocalPhysicsWorld provides world) {
        IsometricScene(
            modifier = modifier,
            onTap = onTap,
            content = content
        )
    }

    // Physics frame loop
    PhysicsLoop(world, /* rootNode from scene */)
}
```

### 7.4 Collision Filtering with Bitmasks

```kotlin
object CollisionCategory {
    val DEFAULT = CollisionMask(1u)
    val PLAYER = CollisionMask(2u)
    val ENEMY = CollisionMask(4u)
    val PROJECTILE = CollisionMask(8u)
    val TERRAIN = CollisionMask(16u)
    val SENSOR = CollisionMask(32u)
}

@JvmInline
value class CollisionMask(val bits: UInt) {
    infix fun or(other: CollisionMask) = CollisionMask(bits or other.bits)
    fun contains(other: CollisionMask) = (bits and other.bits) != 0u
}

data class CollisionFilter(
    val category: CollisionMask = CollisionCategory.DEFAULT,
    val collidesWith: CollisionMask = CollisionMask(UInt.MAX_VALUE),
    val contactsWith: CollisionMask = CollisionMask(0u)  // generates events
)
```

### 7.5 Collision Events

```kotlin
sealed class CollisionEvent {
    abstract val self: PhysicsShapeNode
    abstract val other: PhysicsShapeNode
    abstract val contactPoint: Point
    abstract val normal: Vector
    abstract val impulse: Float

    data class Begin(/* ... */) : CollisionEvent()
    data class End(/* ... */) : CollisionEvent()
    data class Stay(/* ... */) : CollisionEvent()
}

// Declarative callback
PhysicsShape(
    shape = playerShape,
    onCollision = { event ->
        when {
            event is CollisionEvent.Begin &&
            event.other.hasTag("pickup") -> collectPickup(event.other)
        }
    }
)

// Flow-based approach for complex event processing
LaunchedEffect(Unit) {
    world.collisionEvents
        .filter { it.involves(CollisionCategory.PLAYER) }
        .debounce(100)
        .collect { handlePlayerCollision(it) }
}
```

### 7.6 Compound Bodies via Compose Groups

```kotlin
// A PhysicsGroup creates a compound body from its children
PhysicsGroup(body = bodyConfig { mass = 5f }) {
    Shape(shape = Prism(Point.ORIGIN, 1.0, 1.0, 0.5), color = red)   // base
    Shape(shape = Prism(Point(0.0, 0.0, 0.5), 0.5, 0.5, 0.5), color = blue)  // top
    // Both shapes move as one physics body
}
```

### 7.7 Collider Shape Hierarchy

```kotlin
sealed class ColliderShape {
    data class Box(val width: Double, val height: Double, val depth: Double) : ColliderShape()
    data class Sphere(val radius: Double) : ColliderShape()
    data class Capsule(val radius: Double, val height: Double) : ColliderShape()
    data class Cylinder(val radius: Double, val height: Double) : ColliderShape()
    data class ConvexHull(val points: List<Point>) : ColliderShape()
    object FromVisual : ColliderShape()  // Auto-generate from shape geometry
    data class Compound(val children: List<Pair<ColliderShape, Point>>) : ColliderShape()
}
```

### 7.8 Body Type Hierarchy

```kotlin
sealed interface BodyType {
    object Static : BodyType           // Immovable, infinite mass
    data class Dynamic(               // Full physics simulation
        val mass: Float = 1f
    ) : BodyType
    data class Kinematic(             // Script-controlled with physics response
        val pushable: Boolean = false
    ) : BodyType
    object Sensor : BodyType          // Reports contacts, no collision response
}
```

---

## 8. Developer Experience

### 8.1 Physics Material Presets

```kotlin
sealed class PhysicsMaterial {
    abstract val friction: Float
    abstract val restitution: Float
    abstract val density: Float

    object Default : PhysicsMaterial() {
        override val friction = 0.5f; override val restitution = 0.3f; override val density = 1f
    }
    object Ice : PhysicsMaterial() {
        override val friction = 0.02f; override val restitution = 0.1f; override val density = 0.917f
    }
    object Rubber : PhysicsMaterial() {
        override val friction = 0.8f; override val restitution = 0.85f; override val density = 1.1f
    }
    object Metal : PhysicsMaterial() {
        override val friction = 0.4f; override val restitution = 0.2f; override val density = 7.8f
    }
    object Wood : PhysicsMaterial() {
        override val friction = 0.6f; override val restitution = 0.4f; override val density = 0.6f
    }

    data class Custom(
        override val friction: Float = 0.5f,
        override val restitution: Float = 0.3f,
        override val density: Float = 1f
    ) : PhysicsMaterial()
}

// Usage
PhysicsShape(shape = myShape, body = bodyConfig { material = PhysicsMaterial.Rubber })
```

### 8.2 Debug Visualization

```kotlin
@Composable
fun IsometricScope.PhysicsDebugOverlay(
    showColliders: Boolean = true,
    showVelocity: Boolean = false,
    showContactPoints: Boolean = false,
    showAABB: Boolean = false,
    showSleepState: Boolean = false,
) {
    val world = LocalPhysicsWorld.current ?: return

    if (showColliders) {
        world.bodies.forEach { body ->
            Path(
                path = body.colliderDebugPath(),
                color = if (body.isSleeping) sleepColor else colliderColor
            )
        }
    }

    if (showVelocity) {
        world.bodies.filter { !it.isStatic }.forEach { body ->
            Path(path = createArrowPath(body.position, body.position + body.velocity * 0.5),
                 color = velocityColor)
        }
    }

    if (showContactPoints) {
        world.contacts.forEach { contact ->
            Shape(shape = Prism(contact.point, 0.05, 0.05, 0.05), color = contactColor)
        }
    }
}

// One-line debug toggle
IsometricScene {
    // ... scene content ...
    if (BuildConfig.DEBUG) {
        PhysicsDebugOverlay(showColliders = true, showVelocity = true)
    }
}
```

### 8.3 Convenience Extensions

```kotlin
// Common body configurations
fun BodyConfigBuilder.kinematic() { mass = 0f; isStatic = false; gravityScale = 0f }
fun BodyConfigBuilder.staticGround() { isStatic = true; material = PhysicsMaterial.Default }

// Force application
fun PhysicsShapeNode.applyForce(force: Vector) { physicsBody?.applyForce(force) }
fun PhysicsShapeNode.applyImpulse(impulse: Vector) { physicsBody?.applyImpulse(impulse) }

// Composable helpers
@Composable
fun IsometricScope.Ground(
    width: Double = 10.0, depth: Double = 10.0, thickness: Double = 0.1,
    color: IsoColor = IsoColor.GRAY
) {
    PhysicsShape(
        shape = Prism(Point(0.0, 0.0, -thickness), width, depth, thickness),
        color = color,
        body = bodyConfig { staticGround() }
    )
}
```

### 8.4 Testing Physics Behavior

```kotlin
class TestPhysicsWorld(
    gravity: Vector = Vector(0.0, 0.0, -9.81),
    private val fixedDt: Float = 1f / 60f
) {
    private val world = PhysicsWorld(gravity)

    /** Deterministic: same inputs always produce same outputs */
    fun stepN(n: Int) { repeat(n) { world.step(fixedDt) } }

    /** Advance until predicate or timeout */
    fun stepUntil(maxSteps: Int = 600, predicate: (PhysicsWorld) -> Boolean): Int {
        var steps = 0
        while (steps < maxSteps && !predicate(world)) { world.step(fixedDt); steps++ }
        return steps
    }
}

@Test
fun `ball bounces on ground and comes to rest`() {
    val world = TestPhysicsWorld()
    val ball = world.addBody(position = Point(0.0, 0.0, 5.0), material = PhysicsMaterial.Rubber)
    val ground = world.addBody(position = Point.ORIGIN, bodyConfig = bodyConfig { isStatic = true })

    world.stepN(300)  // 5 seconds

    assertThat(ball.position.z).isCloseTo(0.5, within(0.01))
    assertThat(ball.velocity.magnitude).isLessThan(0.001)
    assertThat(ball.isSleeping).isTrue()
}
```

---

## 9. Performance Patterns

### 9.1 Mobile Performance Budgets

| Target | Frame Budget | Physics Budget | Body Limit |
|--------|-------------|----------------|-----------|
| 60 FPS | 16.67 ms | 2-4 ms (15-25%) | ~200-300 (dyn4j) |
| 30 FPS | 33.33 ms | 5-8 ms | ~500-800 (dyn4j) |
| Sustained | 65% CPU | 2 ms | ~150 (thermal safe) |

**Practical limits on Android**:
- dyn4j: ~200-300 dynamic bodies before exceeding 4ms on mid-range 2023 device
- Box2D native: ~500-800 in the same budget
- Custom AABB physics: ~1000-2000 easily

### 9.2 Batch Updates — Single Dirty Propagation

The existing dirty tracking pattern is already optimal for physics:

```kotlin
class PhysicsSystem(private val rootNode: GroupNode) {
    fun step(dt: Float) {
        engine.step(dt)

        // Sync ALL positions with NO per-body markDirty()
        var anyChanged = false
        for (body in bodies) {
            val newPos = engine.getPosition(body.id)
            if (newPos != body.node.position) {
                body.node.position = newPos
                body.node.rotation = engine.getRotation(body.id)
                anyChanged = true
            }
        }

        // Single dirty propagation for entire batch
        if (anyChanged) rootNode.markDirty()
    }
}
```

This batches N body updates into exactly 1 `sceneVersion++` → 1 Canvas Draw phase invalidation.

### 9.3 Fixed Timestep with Interpolation

The canonical "Fix Your Timestep" pattern adapted for Compose:

```kotlin
class FixedTimestepLoop(
    private val fixedDt: Double = 1.0 / 60.0,
    private val maxFrameTime: Double = 0.25  // Spiral-of-death guard
) {
    private var accumulator: Double = 0.0
    private val previousPositions = mutableMapOf<String, Point>()
    private val currentPositions = mutableMapOf<String, Point>()

    fun update(frameTimeSeconds: Double, world: PhysicsWorld) {
        accumulator += minOf(frameTimeSeconds, maxFrameTime)

        while (accumulator >= fixedDt) {
            // Save previous positions for interpolation
            world.bodies.forEach { body ->
                previousPositions[body.id] = currentPositions[body.id] ?: body.position
                currentPositions[body.id] = body.position
            }
            world.step(fixedDt.toFloat())
            accumulator -= fixedDt
        }
    }

    /** Interpolated position for smooth rendering between physics ticks */
    fun getInterpolatedPosition(bodyId: String): Point {
        val alpha = accumulator / fixedDt
        val prev = previousPositions[bodyId] ?: return currentPositions[bodyId] ?: Point.ORIGIN
        val curr = currentPositions[bodyId] ?: return prev
        return Point(
            prev.x + (curr.x - prev.x) * alpha,
            prev.y + (curr.y - prev.y) * alpha,
            prev.z + (curr.z - prev.z) * alpha
        )
    }
}
```

**Why fixed timestep**: Physics tick rate must be independent of display refresh rate. Modern Android devices have 60/90/120Hz displays. Physics runs at fixed 60Hz; interpolation smooths the visual result at any display rate.

### 9.4 JVM-Specific Optimizations

- **Avoid allocations in hot loops**: Pre-allocate vectors, points, contact arrays. Use object pools for GJK/EPA temporaries.
- **Primitive arrays**: `DoubleArray` / `IntArray` instead of `List<Double>` / `List<Int>` to avoid boxing.
- **Inline classes**: `@JvmInline value class CollisionMask(val bits: UInt)` avoids boxing.
- **Structure-of-Arrays**: For SIMD-friendly access: `positionsX: DoubleArray, positionsY: DoubleArray` instead of `Array<Point>`.
- **Sleep system**: Bodies at rest consume zero physics CPU.

### 9.5 Recomposition Avoidance

The existing project already gets this right. Physics should NOT trigger Composition phase:

```kotlin
// BAD: triggers recomposition for every body every frame
@Composable
fun PhysicsBody(body: Body) {
    val position by body.positionState.collectAsState()  // N recomposition scopes
    Shape(shape = box, position = position)
}

// GOOD: only triggers Draw phase via single sceneVersion read
Canvas(modifier = Modifier.fillMaxSize()) {
    @Suppress("UNUSED_EXPRESSION")
    sceneVersion  // Single state dependency → Draw phase only
    renderer.render(rootNode, context, strokeWidth, drawStroke)
}
```

---

## 10. Raycasting and Spatial Queries

### 10.1 Screen-to-World Ray Conversion

The isometric projection is orthographic, so screen clicks produce parallel rays (not diverging like perspective projection). The view direction is proportional to the depth gradient `(1, 1, -2)`:

```kotlin
fun screenToWorldRay(
    screenX: Double, screenY: Double,
    originX: Double, originY: Double,
    angle: Double, scale: Double
): Ray {
    val cosA = cos(angle)
    val sinA = sin(angle)
    val det = -2.0 * scale * scale * cosA * sinA

    val sx = screenX - originX
    val sy = screenY - originY

    // Solve inverse projection at z=0
    val worldX = (sx * (-sinA) - sy * (-cosA)) / det * scale
    val worldY = (sx * (-sinA) - sy * (cosA)) / det * scale

    val viewDir = Vector(1.0, 1.0, -2.0).normalize()
    return Ray(Point(worldX, worldY, 0.0), viewDir)
}
```

### 10.2 Ray-AABB Intersection (Slab Method)

O(1) — approximately 13 floating-point operations, branchless:

```kotlin
fun rayAABB(ray: Ray, aabb: AABB): Double? {
    val tx1 = (aabb.minX - ray.origin.x) * ray.invDirection.i
    val tx2 = (aabb.maxX - ray.origin.x) * ray.invDirection.i
    var tmin = minOf(tx1, tx2)
    var tmax = maxOf(tx1, tx2)

    val ty1 = (aabb.minY - ray.origin.y) * ray.invDirection.j
    val ty2 = (aabb.maxY - ray.origin.y) * ray.invDirection.j
    tmin = maxOf(tmin, minOf(ty1, ty2))
    tmax = minOf(tmax, maxOf(ty1, ty2))

    val tz1 = (aabb.minZ - ray.origin.z) * ray.invDirection.k
    val tz2 = (aabb.maxZ - ray.origin.z) * ray.invDirection.k
    tmin = maxOf(tmin, minOf(tz1, tz2))
    tmax = minOf(tmax, maxOf(tz1, tz2))

    return if (tmax >= maxOf(tmin, 0.0)) tmin else null
}
```

### 10.3 Ray-Shape Intersection (GJK-Raycast)

For precise ray-shape intersection, use GJK with a sphere of zero radius swept along the ray direction. Alternatively, use specialized tests:

- **Ray-Prism**: Transform ray to box local space → slab method
- **Ray-Cylinder**: Analytic quadratic equation for infinite cylinder, clamp to caps
- **Ray-Pyramid/Octahedron**: Ray vs each triangular face, pick nearest

### 10.4 Region Overlap Queries

```kotlin
fun overlapQuery(queryAABB: AABB, broadPhase: SpatialHashGrid): List<RigidBody> {
    return broadPhase.query(queryAABB)
        .map { bodies[it] }
        .filter { it.computeAABB().intersects(queryAABB) }
}
```

### 10.5 Enhanced Hit Testing

The existing `findItemAt()` (`IsometricEngine.kt:147-191`) uses 2D screen-space polygons. With physics, we can add 3D world-space queries:

```kotlin
// New: world-space queries using physics spatial index
fun findBodiesAt(worldPoint: Point): List<RigidBody> {
    val queryAABB = AABB(
        worldPoint.x - 0.1, worldPoint.y - 0.1, worldPoint.z - 0.1,
        worldPoint.x + 0.1, worldPoint.y + 0.1, worldPoint.z + 0.1
    )
    return overlapQuery(queryAABB, broadPhase)
        .filter { pointInConvexPolyhedron(worldPoint, it.faces) }
}

// New: raycast from screen tap through isometric scene
fun raycastFromScreen(screenX: Float, screenY: Float): RaycastResult? {
    val ray = screenToWorldRay(screenX, screenY, ...)
    var nearest: RaycastResult? = null
    broadPhase.queryRay(ray) { bodyId ->
        val t = rayShapeIntersection(ray, bodies[bodyId])
        if (t != null && (nearest == null || t < nearest!!.distance)) {
            nearest = RaycastResult(bodies[bodyId], t, ray.at(t))
        }
    }
    return nearest
}
```

---

## 11. Advanced Physics Features

### 11.1 Joints and Constraints

Joints connect bodies and restrict their relative motion:

```kotlin
sealed class Joint {
    data class Fixed(val bodyA: PhysicsBody, val bodyB: PhysicsBody, val anchor: Point) : Joint()
    data class Revolute(val bodyA: PhysicsBody, val bodyB: PhysicsBody, val pivot: Point,
                        val motorSpeed: Float = 0f, val maxTorque: Float = 0f) : Joint()
    data class Distance(val bodyA: PhysicsBody, val bodyB: PhysicsBody,
                        val anchorA: Point, val anchorB: Point,
                        val length: Double, val stiffness: Float = 1f) : Joint()
    data class Prismatic(val bodyA: PhysicsBody, val bodyB: PhysicsBody,
                         val anchor: Point, val axis: Vector) : Joint()
}

// Declarative usage
PhysicsGroup {
    val bodyA = PhysicsShape(shape = Prism(...), body = bodyConfig { mass = 1f })
    val bodyB = PhysicsShape(shape = Prism(...), body = bodyConfig { mass = 1f })
    RevoluteJoint(bodyA, bodyB, pivot = Point(1.0, 0.0, 0.5))
}
```

### 11.2 Particle Systems

For effects like dust, sparks, debris — using Verlet integration:

```kotlin
data class Particle(
    var position: Point,
    var previousPosition: Point,
    var lifetime: Float,
    var color: IsoColor
)

fun updateParticles(particles: MutableList<Particle>, dt: Double, gravity: Vector) {
    val iter = particles.iterator()
    while (iter.hasNext()) {
        val p = iter.next()
        p.lifetime -= dt.toFloat()
        if (p.lifetime <= 0f) { iter.remove(); continue }

        val temp = p.position
        p.position = Point(
            2 * p.position.x - p.previousPosition.x + gravity.i * dt * dt,
            2 * p.position.y - p.previousPosition.y + gravity.j * dt * dt,
            2 * p.position.z - p.previousPosition.z + gravity.k * dt * dt
        )
        p.previousPosition = temp
    }
}
```

**Composable API**:
```kotlin
@Composable
fun IsometricScope.ParticleEmitter(
    position: Point,
    rate: Int = 10,           // particles per second
    lifetime: Float = 2f,     // seconds
    velocity: Vector,
    spread: Float = 0.5f,
    color: IsoColor = IsoColor.WHITE
) { /* ... */ }
```

### 11.3 Spring-Mass Systems (Soft Bodies)

```kotlin
data class Spring(
    val particleA: Int, val particleB: Int,
    val restLength: Double,
    val stiffness: Double = 50.0,
    val damping: Double = 0.5
)

// Cloth: grid of particles with structural, shear, and bend springs
// Bridge: chain of particles with distance constraints
// Rope: line of particles with distance constraints
```

### 11.4 Force Fields

```kotlin
sealed class ForceField {
    data class Gravity(val direction: Vector, val strength: Double) : ForceField()
    data class Radial(val center: Point, val strength: Double,
                      val falloff: ForceFalloff = ForceFalloff.InverseSquare) : ForceField()
    data class Wind(val direction: Vector, val strength: Double,
                    val turbulence: Double = 0.0) : ForceField()
    data class Vortex(val center: Point, val axis: Vector,
                      val strength: Double) : ForceField()
}

enum class ForceFalloff { Constant, Linear, InverseSquare }
```

### 11.5 Triggers and Sensors

Non-colliding volumes that fire events when objects enter/exit:

```kotlin
PhysicsShape(
    shape = Prism(Point(5.0, 5.0, 0.0), 3.0, 3.0, 3.0),
    body = bodyConfig { isSensor = true },
    color = IsoColor(0.0, 255.0, 0.0, 50.0),  // Semi-transparent green
    onCollision = { event ->
        when (event) {
            is CollisionEvent.Begin -> startCutscene()
            is CollisionEvent.End -> endCutscene()
        }
    }
)
```

---

## 12. Lessons from Classic Isometric Games

### 12.1 What Classic Games Actually Do

| Game | Physics Approach |
|------|-----------------|
| **Diablo** (1996) | Tile-based passability, circle-circle for characters, raycasting for projectiles |
| **Age of Empires** (1997) | Grid passability, circle-based soft push collision, parabolic projectiles |
| **SimCity** (1989-2013) | No physics — purely tile-based simulation rules |
| **Bastion** (2011) | World-space 2D physics + height as visual layer, hitbox combat |
| **Hades** (2020) | Box2D for combat physics, tile-based navigation |

**Pattern**: Classic isometric games overwhelmingly use **simplified custom physics**:
1. Grid/tile-based passability (A* pathfinding handles collision avoidance)
2. Circle-based entity collision (soft push resolution)
3. Simple velocity + gravity (no iterative solver)
4. Raycasting for projectiles
5. Full physics engines only for combat/destruction effects

### 12.2 Patterns to Follow

- Separate physics world from visual world completely
- Use world-space coordinates for all game logic
- Keep collision shapes simple (circles, AABBs) — simpler than visual shapes
- Use the tile grid as primary spatial partitioning
- Batch physics updates (fixed timestep, accumulator)
- Sleep inactive bodies aggressively

### 12.3 Anti-Patterns to Avoid

- **Do NOT** perform collision detection in screen space (diamond-shaped distortion)
- **Do NOT** couple physics step rate to frame rate
- **Do NOT** use a full 3D physics engine for simple isometric games (massive overkill)
- **Do NOT** align gravity to the isometric visual "down" direction
- **Do NOT** sort depth per-frame without spatial partitioning (O(n²) trap)
- **Do NOT** make every visual object a physics body (only dynamic objects need physics)

---

## 13. Pitfalls and Mitigations

### 13.1 Performance Pitfalls

| Pitfall | Impact | Mitigation |
|---------|--------|-----------|
| Per-body `MutableState` | O(N) recomposition per frame | Direct node mutation + single `markDirty()` |
| Physics every render frame | Double CPU work at 120Hz | Fixed timestep (60Hz) independent of display |
| GC from allocations in physics loop | Frame spikes from GC pauses | Pre-allocated arrays, object pools, `@JvmInline` |
| All bodies active always | Wasted CPU on resting objects | Sleep system with island detection |
| Rebuilding spatial index every frame | Unnecessary work for static scenes | Incremental update: only re-insert moved bodies |
| Full O(n²) depth sort with physics | Every frame invalidates prepared scene | Partial re-sort: only re-sort moved objects' depth dependencies |

### 13.2 Stability Pitfalls

| Pitfall | Symptom | Mitigation |
|---------|---------|-----------|
| Variable timestep | Jitter, tunneling, energy gain | Fixed timestep with accumulator |
| No warm starting | Stacks collapse, bouncing piles | Cache impulses across frames |
| Insufficient solver iterations | Objects sink through each other | 8-20 iterations for PGS |
| No sleep system | Stacks slowly drift apart | Sleep bodies below velocity threshold |
| Large mass ratios | Light objects jitter on heavy ones | Shock propagation or sub-stepping |
| No CCD for fast objects | Bullets pass through walls | Per-body CCD flag or raycasting |

### 13.3 Isometric-Specific Pitfalls

| Pitfall | Impact | Mitigation |
|---------|--------|-----------|
| Physics in screen space | Diamond-shaped collisions, wrong gravity | Always physics in world space |
| Height ignored in collision | Objects walk through elevated platforms | Separate Z-axis handling |
| Depth sort breaks with movers | Visual popping between layers | Topological sort with physics positions |
| Screen-to-world input mapping wrong | Taps hit wrong objects | Proper inverse projection with Z-sampling |

### 13.4 API Design Pitfalls

| Pitfall | Impact | Mitigation |
|---------|--------|-----------|
| Exposing physics engine API directly | Vendor lock-in, leaky abstraction | Abstract behind `PhysicsWorld` interface |
| No progressive disclosure | Overwhelming API for simple use cases | Three tiers: zero-config → configured → full control |
| Collision callbacks on main thread | Blocking render | Queue events, dispatch in `LaunchedEffect` |
| No debug visualization | Impossible to diagnose collision issues | `PhysicsDebugOverlay` composable |

---

## 14. Implementation Roadmap

### Phase 1: Foundation (Custom Lightweight)

**Goal**: Gravity, AABB collision, basic bounce — no external dependencies.

| Task | Effort | Files |
|------|--------|-------|
| Add `AABB` data class with `intersects()` | 1 day | `isometric-core` |
| Compute AABB for each shape type | 1 day | `isometric-core` |
| Add `RigidBody` data class (position, velocity, mass) | 1 day | `isometric-core` |
| Create `PhysicsWorld` (gravity, step, body list) | 2 days | `isometric-core` |
| Semi-implicit Euler integration | 1 day | `isometric-core` |
| AABB-AABB collision detection + simple bounce | 2 days | `isometric-core` |
| `PhysicsShapeNode` extending `ShapeNode` | 1 day | `isometric-compose` |
| `PhysicsIsometricScene` composable with frame loop | 2 days | `isometric-compose` |
| Fixed timestep with accumulator | 1 day | `isometric-compose` |
| Sleep system (velocity threshold + timer) | 1 day | `isometric-core` |

**Deliverable**: Objects fall under gravity, bounce off ground and each other.

### Phase 2: Proper Collision (GJK + dyn4j)

**Goal**: Precise collision between all shape types, friction, proper response.

| Task | Effort | Files |
|------|--------|-------|
| `ConvexShape` interface with `support()` per shape | 3 days | `isometric-core` |
| GJK implementation (or integrate dyn4j) | 3 days | `isometric-core` |
| EPA for penetration depth + normal | 2 days | `isometric-core` |
| Sequential impulse solver with warm starting | 3 days | `isometric-core` |
| Contact manifold generation (clipping) | 3 days | `isometric-core` |
| Friction model (Coulomb) | 1 day | `isometric-core` |
| Spatial hash grid broad-phase | 2 days | `isometric-core` |
| Island detection + island-based sleep | 2 days | `isometric-core` |

**Deliverable**: Stacking, friction, stable resting contacts.

### Phase 3: Compose API + DX

**Goal**: Ergonomic Kotlin DSL, collision events, debug visualization.

| Task | Effort | Files |
|------|--------|-------|
| `bodyConfig { }` DSL with `@PhysicsDsl` | 2 days | `isometric-compose` |
| `PhysicsMaterial` sealed class with presets | 1 day | `isometric-core` |
| `CollisionCategory` bitmask system | 1 day | `isometric-core` |
| `CollisionEvent` sealed class + callbacks | 2 days | `isometric-compose` |
| `PhysicsDebugOverlay` composable | 2 days | `isometric-compose` |
| `PhysicsGroup` for compound bodies | 2 days | `isometric-compose` |
| `LocalPhysicsWorld` CompositionLocal | 1 day | `isometric-compose` |
| Interpolation for smooth rendering | 1 day | `isometric-compose` |

**Deliverable**: Full declarative API matching Section 7 design.

### Phase 4: Advanced Features

**Goal**: Joints, raycasting, particles, force fields.

| Task | Effort | Files |
|------|--------|-------|
| Screen-to-world ray conversion | 2 days | `isometric-core` |
| Ray-AABB + ray-shape intersection | 2 days | `isometric-core` |
| Joint system (fixed, revolute, distance) | 3 days | `isometric-core` |
| Particle system with Verlet integration | 3 days | `isometric-core` |
| Force field system (gravity, radial, wind) | 2 days | `isometric-core` |
| Sensor/trigger bodies | 1 day | `isometric-core` |
| CCD for fast-moving bodies | 2 days | `isometric-core` |

**Deliverable**: Feature-complete physics system.

### Phase 5: Optimization + WebGPU

**Goal**: Performance optimization and GPU compute integration (per WEBGPU_ANALYSIS.md).

| Task | Effort | Files |
|------|--------|-------|
| Physics benchmark scenarios | 2 days | `isometric-benchmark` |
| Profile-guided optimization (hot paths) | 3 days | `isometric-core` |
| GPU broad-phase via compute shader | 3 days | new module |
| GPU constraint solver (experimental) | 5 days | new module |

**Deliverable**: Measured performance, optional GPU acceleration.

---

## 15. Sources

### Physics Engines
1. [dyn4j Official Site](https://dyn4j.org/) — Pure Java 2D physics engine, v5.0.2
2. [dyn4j GitHub](https://github.com/dyn4j/dyn4j) — Source, 2000+ tests
3. [dyn4j SAT Implementation](https://dyn4j.org/2010/01/sat/) — Separating Axis Theorem tutorial
4. [dyn4j GJK Implementation](https://dyn4j.org/2010/04/gjk-gilbert-johnson-keerthi/) — Gilbert-Johnson-Keerthi tutorial
5. [dyn4j EPA Implementation](https://dyn4j.org/2010/05/epa-expanding-polytope-algorithm/) — Expanding Polytope Algorithm
6. [Box2D Official](https://box2d.org/) — Erin Catto's physics engine
7. [Box2D v3 Solver](https://box2d.org/posts/2024/02/solver2d/) — TGS Soft solver, sub-stepping
8. [Box2D Simulation Islands](https://box2d.org/posts/2023/10/simulation-islands/) — Sleep, islands, AABB tree
9. [Box2D Determinism](https://box2d.org/posts/2024/08/determinism/) — IEEE 754 compliance
10. [Rapier Physics](https://rapier.rs/) — Rust physics with WASM/FFI bindings
11. [Jolt-JNI](https://github.com/stephengold/jolt-jni) — JVM bindings for Jolt Physics
12. [Libbulletjme](https://github.com/stephengold/Libbulletjme) — JNI bindings for Bullet Physics
13. [Box2D v3 KMP Integration](https://medium.com/@hasantuncay2635/cross-platform-integration-of-the-box2d-v3-physics-engine-in-kotlin-multiplatform-via-jni-and-c-955d8abd042c) — JNI + CInterop approach
14. [KTX Box2D Extensions](https://github.com/libktx/ktx/blob/master/box2d/README.md) — Kotlin DSL for LibGDX Box2D

### Collision Detection Algorithms
15. [GJK Algorithm — winter.dev](https://winter.dev/articles/gjk-algorithm) — Visual tutorial
16. [GJK in 200 Lines of C](https://github.com/kroitor/gjk.c) — Minimal implementation
17. [Van den Bergen: Fast and Robust GJK](http://www.dtecta.com/papers/jgt98convex.pdf) — Warm-starting
18. [Contact Points Using Clipping — dyn4j](https://dyn4j.org/2011/11/contact-points-using-clipping/) — Sutherland-Hodgman
19. [Erin Catto: Contact Manifolds GDC 2007](https://box2d.org/files/ErinCatto_ContactManifolds_GDC2007.pdf)
20. [Spatial Partition Pattern](https://gameprogrammingpatterns.com/spatial-partition.html) — Game Programming Patterns
21. [Ray-AABB Branchless — tavianator](https://tavianator.com/2011/ray_box.html) — Slab method
22. [AABB vs OBB Math](https://dev.to/pratyush_mohanty_6b8f2749/the-math-behind-bounding-box-collision-detection-aabb-vs-obbseparate-axis-theorem-1gdn)
23. [Unified EPA Implementation](https://github.com/notgiven688/unified_epa)

### Physics Simulation
24. [Fix Your Timestep — Gaffer On Games](https://gafferongames.com/post/fix_your_timestep/) — Fixed timestep pattern
25. [Integration Basics — Gaffer On Games](https://gafferongames.com/post/integration_basics/) — Euler, Verlet, RK4
26. [Sequential Impulse Solver Explained](https://raphaelpriatama.medium.com/sequential-impulses-explained-from-the-perspective-of-a-game-physics-beginner-72a37f6fea05)
27. [PGS vs SI Comparison](http://www.mft-spirit.nl/files/MTamis_PGS_SI_Comparison.pdf)
28. [Newcastle Physics Tutorials](https://research.ncl.ac.uk/game/mastersdegree/gametechnologies/) — Full course on game physics
29. [Allen Chou: Contact Constraints](https://allenchou.net/2013/12/game-physics-resolution-contact-constraints/)
30. [Allen Chou: Stability Slops](https://allenchou.net/2014/01/game-physics-stability-slops/) — Baumgarte stabilization
31. [SIGGRAPH Contact and Friction Notes](https://siggraphcontact.github.io/assets/files/SIGGRAPH22_friction_contact_notes.pdf)
32. [Verlet Integration for Cloth](https://pikuma.com/blog/verlet-integration-2d-cloth-physics-simulation)

### Compose Integration
33. [ComposePhysicsLayout](https://github.com/KlassenKonstantin/ComposePhysicsLayout) — dyn4j + Compose
34. [Compose Snapshot System](https://dev.to/zachklipp/introduction-to-the-compose-snapshot-system-19cn)
35. [Compose Performance](https://developer.android.com/develop/ui/compose/performance) — Optimization guide
36. [Compose Phases](https://developer.android.com/develop/ui/compose/phases) — Composition, Layout, Draw
37. [Android Choreographer](https://developer.android.com/ndk/reference/group/choreographer) — VSYNC callbacks
38. [Android Frame Pacing](https://developer.android.com/games/sdk/frame-pacing) — Swappy library
39. [Composable Node Tree](https://newsletter.jorgecastillo.dev/p/the-composable-node-tree) — Custom Compose runtime
40. [Compose for Non-UI](https://arunkumar.dev/jetpack-compose-for-non-ui-tree-construction-and-code-generation/)

### Isometric Game Design
41. [Isometric Projection in Games — Pikuma](https://pikuma.com/blog/isometric-projection-in-games)
42. [Isometric Depth Sorting for Moving Platforms](https://gamedevelopment.tutsplus.com/tutorials/isometric-depth-sorting-for-moving-platforms--cms-30226)
43. [Isometric Collision Detection — GameDev.net](https://www.gamedev.net/forums/topic/709015-3d-collision-detection-in-2d-isometric-game/)

### Game Engine Physics Design
44. [Unity Physics](https://docs.unity3d.com/Packages/com.unity.physics@1.0/manual/index.html) — ECS physics
45. [Godot Physics Introduction](https://docs.godotengine.org/en/stable/tutorials/physics/physics_introduction.html)
46. [SpriteKit Physics — Apple](https://developer.apple.com/documentation/spritekit/skphysicsbody)
47. [react-three-rapier](https://github.com/pmndrs/react-three-rapier) — Best-in-class declarative physics
48. [Observer Pattern in Games](https://gameprogrammingpatterns.com/observer.html)
49. [Designing a Physics Engine — winter.dev](https://winter.dev/articles/physics-engine)

### Kotlin DSL Design
50. [Kotlin Type-Safe Builders](https://kotlinlang.org/docs/type-safe-builders.html)
51. [Building Type-Safe DSLs](https://carrion.dev/en/posts/building-type-safe-dsls/)
52. [Kotlin Sealed Classes](https://kotlinlang.org/docs/sealed-classes.html)
