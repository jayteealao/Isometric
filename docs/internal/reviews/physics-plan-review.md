# Physics Implementation Plan — Review

> **Date**: 2026-03-13
> **Reviewer**: Architecture review agent
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md)
> **Status**: Issues identified — plan revision required

---

## Review Summary

The plan is ambitious and well-structured at a high level, with clear phase boundaries and good test coverage specifications. However, it has **5 critical issues** that would cause implementation failures, **7 important issues** that would cause significant problems, and **7 suggestions** for improvement.

The most concerning pattern is that the plan assumes capabilities the codebase does not have: stored shape dimensions, full 3D rotation in the renderer, platform-agnostic threading primitives, and convex-only shapes. These gaps between the plan and the actual code will surface as blockers during Phase 1-2.

---

## CRITICAL Issues (Must Fix Before Implementation)

### C1. Stairs/Knot Are Non-Convex — GJK Support Function Is Mathematically Wrong

The plan has `StairsCollider` and `KnotCollider` implementing the `ConvexCollider` interface with a `support()` function that returns "the farthest support point across all sub-colliders." This is mathematically incorrect. The Minkowski difference support function is only valid for convex shapes. Taking the max support across disjoint convex sub-shapes produces the convex hull of the union, not the union itself. GJK on this convex hull will miss collisions in the concavities (e.g., the inner step corners of Stairs, or the interior angle of the Knot's L-shapes).

**Evidence**: `Stairs.kt` (line 10-54) composes step faces that form a staircase — clearly non-convex. `Knot.kt` (line 11-48) composes three separate Prisms at different positions plus custom paths — also non-convex.

**Fix**: Stairs and Knot must use compound collider decomposition with per-sub-shape GJK. The broad phase returns the compound body as a candidate, then the narrow phase tests all sub-collider pairs. This requires:
1. A `CompoundCollider` type that is NOT a `ConvexCollider`
2. The `NarrowPhase` pipeline must branch on compound vs. convex
3. Each sub-collider carries its own local transform within the compound

### C2. Double-Buffered AtomicReference Destroys Interpolation Data

The `PhysicsThread` stores a single `AtomicReference<Map<String, Pair<Point, Quaternion>>>` — the current position snapshot. But `PhysicsStep` needs both the previous and current state to compute `interpolatedPosition = prev + alpha * (curr - prev)`. The `PhysicsStep.getInterpolatedPosition()` stores `previousPositions` internally, but the `PhysicsThread.readPositions()` API only exposes one snapshot. The main thread calls `readPositions()` and gets the latest positions, but has no access to the interpolation alpha or previous positions.

There are two contradictory designs: `PhysicsStep` assumes it runs on the same thread as the renderer (stores previous/current internally), but `PhysicsThread` runs it on a background thread and exposes only a position map to the main thread.

**Fix**: Either:
- (a) The physics thread publishes *interpolated* positions (computing interpolation on the physics thread using render-frame timing), or
- (b) The published buffer contains both previous and current states plus the alpha value so the main thread can interpolate.

Option (a) is simpler but requires the physics thread to know the render timestamp.

### C3. Module Dependency Creates Unsolvable Build Cycle

The plan says `isometric-physics` depends on both `isometric-core` (api) and `isometric-compose` (implementation). Phases 1-3 are `kotlin("jvm")` but Phase 4.5 switches the entire module to `com.android.library` with `kotlin("android")`.

This means:
- All Phase 1-3 unit tests that run on JVM will break when the module switches to Android library (Android library tests require an Android test runner or Robolectric)
- The pure physics engine becomes Android-only, even though the decision summary says "WebGPU-compatible"

**Fix**: Split into two modules:
- `isometric-physics-core` — pure JVM, no Compose/Android dependency (math, bodies, collision, solver)
- `isometric-physics-compose` — Android library, depends on both `isometric-physics-core` and `isometric-compose` (PhysicsScene, PhysicsShape, debug overlays)

This matches the existing `isometric-core` / `isometric-compose` split pattern.

### C4. Shape Types Have No Stored Dimensions — Collider Derivation Is Impossible

The plan's `BodyConfig.colliderShape` says `null = derive from visual shape`. The `AABB.fromPrism(origin, dx, dy, dz)` and `InertiaCalculator.compute()` both require shape dimensions. But the actual `Shape` class stores only `val paths: List<Path>`. The concrete subclasses `Prism`, `Pyramid`, `Cylinder`, etc. pass their dimensions to companion `createPaths()` functions but **do not store** `dx`, `dy`, `dz`, `radius`, `height`, etc. as fields — they are consumed in the constructor and discarded.

The plan's `InertiaCalculator` uses `is Prism -> boxInertia(mass, shape.dx, shape.dy, shape.dz)` — but `Prism` has no `dx` field.

**Fix**: Either:
- (a) Modify the core shape classes to store their construction parameters (breaking change to `isometric-core` but cleanest solution)
- (b) Require explicit `ColliderShape` for every body (no "derive from visual" mode)
- (c) Reverse-engineer dimensions from the `paths` list (fragile and slow)

Option (a) is recommended as it benefits the entire library.

### C5. "Deterministic, Bit-Exact" Claim Is False on JVM with HashMap

The plan claims bit-exact determinism. On the JVM, `double` arithmetic is IEEE 754, but the JIT compiler may use 80-bit extended precision x87 registers on some platforms. More importantly, the plan uses `HashMap` extensively — `HashMap` iteration order is non-deterministic in JVM.

The spatial hash grid uses `HashMap<Long, MutableList<RigidBody>>` and iterates cells to produce pairs. The pair order determines solver processing order, which affects the final result due to the sequential nature of the impulse solver.

**Fix**:
1. Replace all `HashMap` with `LinkedHashMap` (preserves insertion order)
2. Add explicit sorting of pairs/contacts by a deterministic key (e.g., body ID comparison) before solving
3. Document that `strictfp` on all physics classes is required if cross-platform bit-exactness is truly needed
4. Alternatively, downgrade the claim to "reproducible within a single platform/JVM version" which is achievable

---

## IMPORTANT Issues (Should Fix)

### I1. PhysicsShape Cannot Link Physics Body to Visual Node

The `PhysicsShape` composable calls `Shape()` at the end, which creates a `ShapeNode` via `ReusableComposeNode<ShapeNode, IsometricApplier>`. But the `ShapeNode` is created by the Compose runtime's factory lambda — `PhysicsShape` has no reference to the resulting `ShapeNode`. The physics body's `nodeId` field is set, but the visual `ShapeNode.nodeId` (generated by `System.identityHashCode`) is a different, unrelated ID.

Furthermore, `PhysicsShape` passes `position = pos` to the `Shape()` composable. The `set(position)` updater calls `markDirty()`. With 500 physics bodies updating positions at 60fps, that is 500 `markDirty()` calls per frame, each propagating to root. The batching in `IsometricApplier.onBeginChanges()/onEndChanges()` only batches structural changes (insert/remove/move), not property updates.

**Fix**: Physics position updates should bypass the Compose recomposition path entirely. The physics thread should write positions directly into the node tree (via a proper node reference), then call `markDirty()` once on the root. This requires `PhysicsShape` to obtain a reference to its `ShapeNode` — likely via a custom `PhysicsShapeNode` that extends `ShapeNode`.

### I2. Phase 4 Is Massively Overloaded

Phase 4 bundles: collision events, raycasting, force fields, CCD, the JVM-to-Android module transition, PhysicsScene composable, PhysicsShape/PhysicsGroup composables, DSL builders, CompositionLocals, rememberPhysicsBody, and Ground() helper.

**Fix**: Split Phase 4 into:
- Phase 4a: Events + raycasting + force fields (pure physics core additions)
- Phase 4b: Module restructuring into core/compose split
- Phase 4c: Compose integration (PhysicsScene, PhysicsShape, DSL)
- Phase 4d: CCD (independent advanced feature)

### I3. PhysicsThread Uses Android HandlerThread in Phase 1

`PhysicsThread` uses `HandlerThread` and `android.os.Process` — Android framework classes. But Phase 1 is supposed to be a `kotlin("jvm")` module with no Android dependencies.

**Fix**: Use `java.util.concurrent` primitives (e.g., `ScheduledExecutorService` or a simple `Thread` with a loop) for the JVM physics core. Provide an Android-specific implementation using `HandlerThread` + `Choreographer` in the Compose integration module.

### I4. Sleep System and Island Manager Not Wired Together

The `SleepSystem` checks individual body velocity thresholds. The `IslandManager` is listed separately and says "An island sleeps only when ALL bodies are below threshold." But in the `PhysicsWorld.step()` flow, `sleepSystem.update()` operates on individual bodies, not islands. `IslandManager.updateSleep()` is never called.

If sleep is per-body without island awareness, a body at the top of a 10-box stack could sleep (low velocity) while the body below it is awake and moving, causing the sleeping body to float.

**Fix**: The step function must call `islandManager.buildIslands()` before sleep evaluation, then delegate sleep decisions to islands rather than individual bodies.

### I5. Rotation Mismatch: Physics Quaternion vs Renderer Z-Only

Physics bodies store `orientation: Quaternion` for full 3D rotation. But the rendering pipeline only supports Z-axis rotation: `ShapeNode.rotation: Double` is a single scalar, `RenderContext.accumulatedRotation` is a single `Double`.

`PhysicsShape` does `rotation = orient.toEulerZ()` — projecting a full 3D quaternion to a single Z-angle. A box tumbling in X or Y will render incorrectly.

**Fix**: Either:
- (a) Extend the rendering pipeline to support full 3D rotation (X, Y, Z) — significant refactor of `RenderContext`, `ShapeNode.render()`, and the transform accumulation system
- (b) Constrain physics to Z-rotation only initially, with full 3D as a later enhancement
- (c) Physics applies 3D rotation to shape vertices directly (bypassing the node rotation system) — creates new Shape instances but is geometrically correct

Option (c) is the pragmatic middle ground: physics transforms the `Shape` geometry directly each frame.

### I6. Immutable Shape Translate Chain Creates Massive GC Pressure

Every render frame creates new `Shape`/`Path`/`List<Point>` objects for all 500 bodies due to the immutable `.translate()` chain in `ShapeNode.render()`. With 500 bodies × 6 faces × 4 points = 12,000 new `Point` objects minimum per frame, plus the intermediate `Path` and `Shape` wrappers.

**Fix**: The physics renderer should update positions without recreating shapes. Options:
- Pre-transform shape vertices into a reusable buffer
- Apply physics transform as a matrix in the rendering pipeline rather than vertex-by-vertex translation
- Cache transformed shapes and only recompute when position actually changes

### I7. bodyConfig DSL Has Conflicting Properties

`BodyConfigBuilder` has `type: BodyType`, `mass: Double`, and `isStatic: Boolean`. The `build()` function derives type from `isStatic`, ignoring direct `type` assignments. Setting `type = BodyType.Kinematic()` then `isStatic = false` overwrites kinematic with Dynamic.

**Fix**: Remove `isStatic` convenience setter. Use `type` as the single source of truth. Provide convenience functions: `static()`, `dynamic(mass)`, `kinematic()`, `sensor()`.

---

## SUGGESTIONS (Nice to Have)

### S1. Move Basic Debug Output to Phase 2

The plan places debug visualization in Phase 6, after Compose integration. But debugging GJK/EPA without visualization is extremely painful — a box falling through another because EPA returned the wrong normal is nearly impossible to diagnose from unit test position values alone.

**Suggestion**: Add minimal headless debug output (e.g., SVG dump of body positions, contact points, normals, AABBs) in Phase 2. The full Compose debug overlay can remain in Phase 6.

### S2. Phase 7 (Rope) Depends on Phase 5 (Joints) But Text Claims Independence

The dependency diagram correctly shows Phase 7 depending on Phase 5. But the text says "After Phase 4, Phases 5-9 can be worked in parallel or in any order since they are independent features." The `RopeBody` uses Verlet integration (not joints), so the dependency may be weaker than shown, but the plan should be consistent.

### S3. Spatial Hash Grid Should Use Incremental Updates

`SpatialHashGrid3D.update()` calls `cells.clear()` and re-inserts all bodies. For 500 bodies where most are sleeping, an incremental approach (only update moved bodies) would be significantly faster.

### S4. Plan Ignores the Existing O(N²) Depth Sort Bottleneck

`IsometricEngine.sortPaths()` builds an O(N²) dependency graph. With 500 bodies × 6 faces = 3000 paths, that is ~4.5 million pair tests per frame. This will dominate frame time far more than the physics step. The plan should acknowledge this and consider broad-phase sort optimization as a prerequisite for the 500-body target.

### S5. GC Pressure From data class Contacts in Hot Loop

`ContactManifold` and `ContactPoint` are `data class`. The solver iterates contacts 10 times per step at 60fps. Use mutable classes for contacts and pre-allocated pools for contact storage.

### S6. rememberPhysicsBody Without PhysicsShape Creates Invisible Bodies

A user calling `rememberPhysicsBody` without `PhysicsShape` gets a body that participates in physics but is invisible. This is arguably a feature (sensors, triggers) but should be documented explicitly.

### S7. Consider dyn4j for Phases 2-3 to De-Risk

The research document recommends dyn4j as the primary choice. The plan goes fully custom for GJK, EPA, sequential impulse solver, contact manifolds, and warm starting. A custom Kotlin solver is unlikely to outperform dyn4j's 14+ years of Java optimization.

**Suggestion**: Consider using dyn4j for Phases 2-3 (collision + solver) and only implementing custom code for the isometric-specific parts (broad phase, Compose integration, 3D extensions). The custom GJK/EPA/solver can replace dyn4j later if needed for 3D or WebGPU migration.

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | C3: Split module into core + compose | Medium | Unblocks clean Phase 1-3 |
| 2 | C4: Store shape dimensions in core | Low-Medium | Unblocks collider derivation + inertia |
| 3 | C1: Compound collider for Stairs/Knot | Medium | Correctness for non-convex shapes |
| 4 | C2: Fix threading/interpolation model | Medium | Smooth rendering from background thread |
| 5 | C5: Replace HashMap + sort contacts | Low | Determinism |
| 6 | I5: Address 3D rotation rendering gap | High | Full 3D tumbling works |
| 7 | I2: Split Phase 4 into 4a/4b/4c/4d | Low | Realistic phase sizing |
| 8 | I1: Custom PhysicsShapeNode + direct mutation | Medium | Performance with 500 bodies |
| 9 | I4: Wire island manager into step() | Low | Correct sleep behavior |
| 10 | I7: Fix bodyConfig DSL conflicts | Low | Clean API |
