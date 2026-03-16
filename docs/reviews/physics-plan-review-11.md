# Physics Implementation Plan -- Review Round 11

> **Date**: 2026-03-13
> **Reviewer**: Senior software architect (independent review)
> **Plan**: [physics-implementation-plan.md](../plans/physics-implementation-plan.md) (Revised R10)
> **Status**: Issues identified -- plan revision recommended

---

## Review Summary

The R10 revision addresses 113 issues across ten prior review rounds. The plan is mature, architecturally sound, and comprehensively documented. After reading the full ~3120-line plan, all 9 available review documents (rounds 2-10), and cross-referencing against the actual codebase (`IsometricScene.kt`, `IsometricRenderer.kt`, `IsometricNode.kt`, `IsometricApplier.kt`, `RenderContext.kt`, all shape classes in `isometric-core/src/main/kotlin/.../shapes/`, `Vector.kt`, `Point.kt`, `Path.kt`, `Shape.kt`, `IsometricEngine.kt`, `IsometricComposables.kt`, `IsometricScope.kt`, `CompositionLocals.kt`, and both `build.gradle.kts` files plus `settings.gradle` and the root `build.gradle`), I have identified **1 important issue** and **2 suggestions** that are genuinely new -- not repeats of any previously reported findings.

No critical issues were found. The plan is close to implementation-ready.

---

## CRITICAL Issues (Must Fix Before Implementation)

None identified.

---

## IMPORTANT Issues (Should Fix)

### I1. `IslandManager.updateSleep()` island-level threshold check treats `allowSleep=false` bodies as "below threshold" regardless of velocity -- a moving `allowSleep=false` body cannot prevent its island from sleeping

The plan's `updateSleep()` (section 3.3, lines 1717-1722) checks whether an island should sleep:

```kotlin
val allBelowThreshold = island.bodies.all { body ->
    body.isStatic || !body.config.allowSleep ||
    (body.velocity.magnitudeSquared() < config.sleepLinearThreshold *
        config.sleepLinearThreshold &&
     body.angularVelocity.magnitudeSquared() < config.sleepAngularThreshold *
        config.sleepAngularThreshold)
}
```

The `!body.config.allowSleep` disjunct evaluates to `true` when `allowSleep` is `false`, causing the body to unconditionally satisfy the "below threshold" predicate -- its velocity is never inspected. This means a fast-moving dynamic body with `allowSleep = false` does NOT prevent the island from being considered "all below threshold."

If the only moving body in an island has `allowSleep = false`, the other (slower) bodies pass the velocity check on their own merits, and the `allowSleep = false` body passes by short-circuit. The island is deemed "all below threshold" and the sleep timer logic engages. The R7-S3 fix correctly prevents the `allowSleep = false` body itself from entering sleep (line 1726: `if (body.isStatic || !body.config.allowSleep) continue`), but the OTHER bodies in the island DO get their sleep timers incremented and can be put to sleep.

Once those neighboring bodies sleep, their velocities are zeroed (line 1730-1731). On the next step, `Island.isSleeping` is still `false` (because the `allowSleep = false` body is awake), so the solver runs. But `integrateVelocities` and `integratePositions` typically skip sleeping bodies. The sleeping bodies cannot respond to solver impulses, effectively becoming static barriers that the `allowSleep = false` body collides against -- but without proper constraint resolution, the `allowSleep = false` body could tunnel through or jitter against the frozen bodies.

In standard physics engines (Box2D, Bullet), a body with `allowSleep = false` that has significant velocity prevents its entire island from sleeping. The body's actual velocity is always checked, and only its personal sleep-state transition is suppressed.

**Evidence**: Plan section 3.3 lines 1717-1722 (`!body.config.allowSleep` short-circuits velocity check), lines 1724-1733 (sleep application correctly skips allowSleep=false bodies but incorrectly lets neighbors sleep), lines 1666-1668 (solver skips sleeping islands, but `Island.isSleeping` is false here so the solver runs -- however sleeping bodies within the island don't integrate).

**Why this was not caught before**: R7-S3 identified and fixed the symptom (allowSleep=false bodies being PUT to sleep), but the island-level threshold logic was not re-examined after the fix. The `allBelowThreshold` predicate was inherited from the original design and R7-S3 only addressed the sleep-application loop, not the threshold computation. The two code blocks are 8 lines apart (threshold at line 1717, application at line 1724) but serve different purposes, making it easy to fix one without reconsidering the other.

**Fix**: Remove `!body.config.allowSleep` from the threshold predicate. Bodies with `allowSleep = false` should still have their velocity evaluated for the island-level decision. They just never enter sleep themselves:

```kotlin
val allBelowThreshold = island.bodies.all { body ->
    body.isStatic ||
    (body.velocity.magnitudeSquared() < config.sleepLinearThreshold *
        config.sleepLinearThreshold &&
     body.angularVelocity.magnitudeSquared() < config.sleepAngularThreshold *
        config.sleepAngularThreshold)
}
```

The sleep-application loop (lines 1724-1733) already correctly handles `allowSleep = false` via the `continue` guard at line 1726. No changes needed there.

---

## SUGGESTIONS (Nice to Have)

### S1. `KnotCompoundCollider` documentation claims "slightly enlarged half-extents" for custom quadrilateral paths, but the code uses exact prism dimensions -- collider does not cover the custom path geometry

The plan's `KnotCompoundCollider` documentation (section 2.2, lines 1339-1342) states:

> Rather than adding dedicated colliders for these tiny quad faces, the nearest prism colliders have slightly enlarged half-extents to cover the gap regions. This is a conservative approximation -- collisions are slightly larger than visual but never miss. (FIX R5-S1)

However, the actual `KnotCompoundCollider.create()` code (lines 1358-1383) uses exact prism dimensions with no enlargement:

```kotlin
val prisms = listOf(
    Triple(Point(0.0, 0.0, 0.0), Triple(5.0, 1.0, 1.0), ...),  // Prism 1: exact
    Triple(Point(4.0, 1.0, 0.0), Triple(1.0, 4.0, 1.0), ...),  // Prism 2: exact
    Triple(Point(4.0, 4.0, -2.0), Triple(1.0, 1.0, 3.0), ...)  // Prism 3: exact
)
```

The custom quadrilateral paths in the actual `Knot.kt` (lines 23-37) occupy pre-scaled positions at z=[1,2], x=[0,1], y=[0,1]. This region is partially ABOVE the first prism (which extends from z=0 to z=1). After 1/5 scaling and offset, these quads become small, but they are not covered by any collider.

The documentation claims coverage via enlarged extents, but the code does not implement any enlargement. This is an internal contradiction between the comment and the code.

**Evidence**: Plan lines 1339-1342 ("slightly enlarged half-extents"), plan lines 1358-1361 (exact `Triple(5.0, 1.0, 1.0)` etc. with no enlargement), actual `Knot.kt` lines 23-37 (custom paths at z=1-2 above first prism at z=0-1).

**Why this was not caught before**: R5-S1 changed the description from "triangular" to "quadrilateral" and added the "slightly enlarged" claim. Subsequent reviews accepted the documentation at face value without comparing the stated dimensions against the code's actual `Triple` values.

**Fix**: Either:
- (a) Actually enlarge the first prism's half-extents to cover the custom path region. Prism 1 extends from z=0 to z=1 (pre-scale). The custom paths extend to z=2. Enlarging Prism 1's z-extent from 1.0 to 2.0 (half-extent from 0.1 to 0.2 post-scale) would cover the gap. Adjust center accordingly.
- (b) Update the documentation to accurately state that the custom quad faces are NOT covered by the compound collider, and document this as an intentional simplification (the quads are decorative detail too small to matter for gameplay-level collision).

Option (b) is simpler and honest. The quads are tiny after 1/5 scaling (~0.2 units) and unlikely to affect gameplay collision.

### S2. `PhysicsScene` still increments `frameVersion` in its state declaration but never modifies it -- vestigial state variable after R9-S2

The plan's `PhysicsScene` (section 4b.4, line 2178) declares:

```kotlin
var frameVersion by remember { mutableStateOf(0L) }
```

And passes it to `IsometricScene` at line 2239:

```kotlin
frameVersion = frameVersion,
```

The R9-S2 fix acknowledged that `frameVersion++` is redundant after the `markDirty()` fix (R9-C1), and the increment was removed from the sync loop. But the `var` declaration and pass-through remain. Since `frameVersion` is never modified, it always passes `0L` to `IsometricScene`. The `remember { mutableStateOf(0L) }` allocates a `MutableState` object that serves no purpose.

**Evidence**: Plan line 2178 (`var frameVersion` -- never incremented), plan line 2239 (`frameVersion = frameVersion` -- always 0L), plan lines 2213-2215 (comment acknowledging redundancy per R9-S2 but retaining it "for non-physics external redraws").

**Why this was not caught before**: R9-S2 documented this as a simplification opportunity but recommended retaining `frameVersion` "as a parameter on IsometricScene for non-physics external redraws." The R10 review accepted this reasoning. However, `PhysicsScene` does not expose `frameVersion` as a parameter to its callers -- it's a purely internal variable. External callers of `PhysicsScene` cannot increment it. If a caller needs external redraw control, they should use `IsometricScene` directly, not `PhysicsScene`.

**Fix**: Replace `var frameVersion by remember { mutableStateOf(0L) }` with a direct literal:

```kotlin
// In IsometricScene call:
frameVersion = 0L,  // Physics redraws via markDirty(); frameVersion unused
```

Or remove the `frameVersion` parameter entirely from the `IsometricScene` call if the default (`0L`) suffices (which it does).

---

## Recommended Fix Priority

| Priority | Issue | Effort | Impact |
|----------|-------|--------|--------|
| 1 | **I1**: `allowSleep=false` bodies' velocity ignored in island threshold check -- neighbors sleep prematurely | Trivial (remove one disjunct from predicate) | Moving allowSleep=false body causes island neighbors to incorrectly sleep; those neighbors become unresponsive to solver impulses |
| 2 | **S1**: KnotCompoundCollider docs claim enlarged extents but code uses exact dimensions | Trivial (update docs or enlarge one extent) | Documentation accuracy; minor collision gap for decorative geometry |
| 3 | **S2**: Vestigial `frameVersion` state in `PhysicsScene` | Trivial (remove or simplify) | Minor: one unused `MutableState` allocation |

---

## Overall Assessment

The plan is in excellent shape after ten rounds of revision. The 113 previously identified fixes have been incorporated thoroughly, and the architecture is sound at every level -- module split, threading model, snapshot-based position sync, deferred body mutation queue, compound colliders with centroid correction, island-based sleep, Compose lifecycle integration, and renderer cache invalidation via `markDirty()`.

The single important finding this round (I1) is a semantic error in the island-level sleep threshold logic. The `allBelowThreshold` predicate short-circuits on `!body.config.allowSleep`, causing bodies that explicitly opt out of sleeping to be invisible to the threshold check. This means a fast-moving `allowSleep=false` body cannot prevent its island neighbors from sleeping, which violates the standard physics engine contract where island sleep requires ALL dynamic bodies to be calm. The fix is trivial: remove the `!body.config.allowSleep` disjunct from the threshold predicate (the sleep-application guard at line 1726 already correctly handles this case). This was not caught in prior reviews because R7-S3 fixed the symptom (allowSleep=false bodies being put to sleep) without re-examining the island-level threshold predicate that determines WHEN sleep timers start incrementing for the entire island.

The two suggestions are minor: a documentation-code inconsistency in `KnotCompoundCollider` (claims enlarged extents, code uses exact dimensions), and a vestigial `frameVersion` state variable in `PhysicsScene` that is never incremented.

**The plan is ready for implementation once I1 is resolved.** The fix is trivial -- removing one boolean disjunct from a predicate. No architectural changes are needed.
