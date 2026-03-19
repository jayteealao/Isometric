# Physics Implementation Plan -- Review Round 14

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R13)
> **Status**: Clean -- ready for implementation

---

## Review Summary

The R13 revision addresses 120 issues across thirteen prior review rounds. After reading the full ~3210-line plan, all 12 available review documents (rounds 2-13), and cross-referencing against the actual codebase (`IsometricScene.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `IsometricApplier.kt`, `RenderContext.kt`, `IsometricComposables.kt`, `IsometricScope.kt`, `CompositionLocals.kt`, all shape classes in `isometric-core/src/main/kotlin/.../shapes/`, `Vector.kt`, `Point.kt`, `Path.kt`, `Shape.kt`, `IsometricEngine.kt`, `RenderCommand.kt`, both `build.gradle.kts` files, and `settings.gradle`), I have found **no new issues** that meet the quality bar for this round.

---

## CRITICAL Issues (Must Fix Before Implementation)

None identified.

---

## IMPORTANT Issues (Should Fix)

None identified.

---

## SUGGESTIONS (Nice to Have)

None identified.

---

## Areas Verified

The following areas were specifically examined for correctness and internal consistency. All checked out clean:

1. **Phase 0 shape modifications**: All six shape classes (`Prism`, `Pyramid`, `Cylinder`, `Octahedron`, `Stairs`, `Knot`) are addressed. The actual codebase confirms that `dx`/`dy`/`dz`/`radius`/`height`/`stepCount` are bare parameters (not `val`), and the plan correctly proposes adding `val` to each. The `Pyramid` omission noted in R10-S3 was addressed -- the plan's Phase 0 text at line 283-289 now includes `Pyramid`. `Cylinder`'s secondary constructor (`Cylinder(origin, vertices, height)` delegating with `radius = 1.0`) is correctly noted with an ambiguity test (FIX R3-I1).

2. **`IsometricScene` callback additions**: The plan proposes `onRootNodeReady` and `onEngineReady` callbacks (lines 339-362). The actual `IsometricScene.kt` signature (line 74-96) shows that adding nullable callback parameters with `null` defaults is source-compatible. The `rootNode` and `engine` are private locals at lines 99-100, confirming the need for callbacks. The `SideEffect` usage matches the existing pattern for `onHitTestReady` (line 146) and `onFlagsReady` (line 158).

3. **`PhysicsShapeNode` extends `IsometricNode` directly**: The plan correctly extends `IsometricNode` (not `ShapeNode`), provides `override val children = mutableListOf<IsometricNode>()` (FIX R3-C1), and skips `context.applyTransformsToShape()` (FIX R4-C2). The actual `GroupNode.render()` (codebase line 111-127) creates a `childContext` with accumulated transforms and passes it to `child.render()`. `PhysicsShapeNode.render()` correctly ignores this context since physics positions are world-space.

4. **`PhysicsGroupNode` extends `GroupNode`**: The `IsometricApplier.insertBottomUp()` (codebase line 50-52) casts `current as? GroupNode`. `PhysicsGroupNode` extending `GroupNode` satisfies this cast (FIX R5-C3).

5. **`IsometricApplier` snapshot and dirty batching**: The applier batches `updateChildrenSnapshot()` and `markDirty()` calls via `onBeginChanges()`/`onEndChanges()` (codebase lines 22-36). The plan's `PhysicsScene` sync loop calls `root.markDirty()` after `syncPositionsToNodes()` (line 2278), which is outside the applier's batch scope -- this is correct because position sync is not a structural mutation.

6. **`markDirty()` guard semantics**: The actual `markDirty()` (codebase line 79-88) guards with `if (!isDirty)`. The renderer's `rebuildCache()` calls `clearDirty()` (codebase line 422) after processing. This means the physics sync loop's `root.markDirty()` correctly triggers a rebuild on every frame: sync marks dirty -> Canvas redraws -> renderer rebuilds and clears dirty -> next frame sync marks dirty again.

7. **Thread safety of physics sync**: The plan's `syncPositionsToNodes` (lines 2347-2358) runs on the main thread inside `LaunchedEffect { withFrameNanos { } }`. It reads from `snapshot` (an immutable `Map` obtained via `AtomicReference.get()`) and writes to `PhysicsShapeNode.physicsPosition`/`physicsOrientation`. The `PhysicsShapeNode.render()` reads these fields during the Canvas draw pass, also on the main thread. No cross-thread access of these fields. The `childrenSnapshot` traversal (line 2356) uses the thread-safe copy (codebase line 30: `@Volatile var childrenSnapshot`). This is correct.

8. **Deferred force queue (R12-I1 + R13-I1 + R13-S1)**: The `Point - Point` operator is now defined (line 539-540). Force fields use `applyForceInternal()` (line 1965) to avoid the one-step queue delay. The `drainPendingForces()` method (lines 617-634) correctly handles all `ForceRequest` variants. The `Point.minus(other: Point): Vector` extension returns a `Vector`, which is the correct type for `.cross()` calls at lines 624 and 629.

9. **`RenderCommand` construction**: The actual `RenderCommand` (codebase) requires `id`, `points`, `color`, `originalPath`, `originalShape`, and optional `ownerNodeId`. The plan's `PhysicsShapeNode.renderShape()` (lines 2126-2134) references using `bodyId` prefix for stable IDs, which is correct since `path.hashCode()` changes every frame for moving physics bodies.

10. **`ReusableComposeNode` usage**: Both `PhysicsShape` (line 2543) and `PhysicsGroup` (line 2581) use `ReusableComposeNode`, matching the codebase pattern (FIX R10-S2). The actual `IsometricComposables.kt` uses `ReusableComposeNode` for all composables (codebase lines 43, 80, 120, 159).

11. **Module dependency chain**: `isometric-physics-core` depends on `:isometric-core` (line 1153). `isometric-physics-compose` depends on `:isometric-physics-core` and `:isometric-compose` (lines 2037-2038). The actual `settings.gradle` includes `:isometric-core` and `:isometric-compose`. The new physics modules will need to be added to `settings.gradle`, which the plan acknowledges implicitly.

12. **Java toolchain configuration**: `isometric-core/build.gradle.kts` uses `jvmToolchain(17)` (codebase line 19). The plan's `isometric-physics-core` uses `jvmToolchain(17)` + `jvmTarget("11")` (lines 1145-1149), which correctly reads Java 17 class files from `isometric-core` while emitting Java 11 bytecode for Android compatibility (FIX R3-I7, R5-I4).

13. **Gravity direction convention**: The plan uses `Vector(i = 0.0, j = 0.0, k = -9.81)` for gravity (line 948). The existing codebase's isometric convention places Z as the vertical axis (confirmed by `Point.depth()` at codebase line 128: `x + y - 2 * z`, where Z has the highest weight). Negative-Z gravity is correct.

14. **`PhysicsScope` delegation**: `PhysicsScopeImpl` (line 2452-2455) delegates `IsometricScope` via `by isometricScope`. The actual `IsometricScope` is a marker interface with no members (codebase line 10-14), so delegation is trivially correct. `PhysicsScope` adds `val world: PhysicsWorld`, which `PhysicsScopeImpl` overrides.

15. **Body lifecycle in `PhysicsShape`**: Body creation via `remember(key ?: shape, body)` (line 2526), cleanup via `DisposableEffect(rb) { onDispose { world.removeBody(rb) } }` (lines 2534-2536). Both `addBody()` and `removeBody()` use the deferred mutation queue (FIX R8-C1), so they are thread-safe from Compose callbacks.

---

## Overall Assessment

The plan is **clean and ready for implementation** after thirteen rounds of review and 120 resolved issues. The architecture is sound at every level:

- **Module split**: `isometric-physics-core` (pure JVM) and `isometric-physics-compose` (Android) correctly mirror the existing `isometric-core`/`isometric-compose` split.
- **Threading model**: Physics thread publishes interpolated snapshots via `AtomicReference`. Main thread reads snapshots and syncs positions into node fields. Force/impulse requests use a lock-free `ConcurrentLinkedQueue`. Body additions/removals use a separate deferred mutation queue. Internal physics-thread callers (gravity, force fields, solver) use direct `applyForceInternal()` to avoid queue delay.
- **Renderer integration**: `syncPositionsToNodes` followed by `root.markDirty()` correctly invalidates the `PreparedScene` cache, triggering `rebuildCache()` on the next draw pass.
- **Compose lifecycle**: `remember` for body creation, `DisposableEffect` for cleanup, `ReusableComposeNode` for scene graph nodes, `CompositionLocalProvider` for `LocalPhysicsWorld` and `LocalPhysicsSnapshot`.
- **Shape convention**: Origin-centered `baseShape` ensures correct rotation. `ColliderFactory` dispatches on `originalShape` type for dimension access.
- **Determinism**: `LinkedHashMap` everywhere, sorted contact pairs by `BodyPairKey.of()`, fixed timestep with accumulator.
- **Sleep**: Island-aware sleep with correct `allowSleep=false` semantics -- velocity always checked for island threshold, only personal sleep transition suppressed.

No new issues were found. The plan has been reviewed exhaustively across threading, type safety, API consistency, Compose lifecycle, renderer cache invalidation, module dependencies, build configuration, algorithm correctness, and internal consistency. It is ready for implementation.
