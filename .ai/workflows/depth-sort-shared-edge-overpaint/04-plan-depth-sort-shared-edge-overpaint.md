---
schema: sdlc/v1
type: plan
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 4
created-at: "2026-04-26T19:33:07Z"
updated-at: "2026-04-27T22:32:06Z"
metric-files-to-touch: 9
metric-step-count: 14
has-blockers: false
revision-count: 1
amends: 02-shape-amend-1.md
applies-amendment: 1
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
  - 3x3-grid
  - screen-overlap-gate
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 02-shape.md
  shape-amendment: 02-shape-amend-1.md
  diagnostic: 07-review-grid-regression-diagnostic.md
  siblings: []
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Plan: depth-sort shared-edge overpaint

## Current State

**Amendment-1 update**: Path.kt was modified by `3e811aa fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon`. That change is the WS10 fix and remains correct in isolation. The amendment-1 work focuses on a SECOND bug class — over-aggressive topological edges in 3×3 grid layouts — that only became visible on emulator after `3e811aa` shipped. Per the diagnostic in `07-review-grid-regression-diagnostic.md`, the mechanism is: permissive `result > 0` produces "winning votes" in `closerThan` for prism face pairs whose 2D iso-projected polygons don't actually overlap, and `IntersectionUtils.hasIntersection` accepts boundary-touching AABBs as overlapping (line 99-103 of `IntersectionUtils.kt`), so the gate that should reject these pairs lets them through. Topological sort then pushes corner-cube vertical faces to output positions 0–2 where they get painted over.

**`IntersectionUtils.hasIntersection` current behaviour** (read at amendment time):
- AABB rejection (lines 84–103) accepts `A.maxX == B.minX` as overlap (`<=` checks). Permissive about boundary touch.
- SAT edge-crossing test (lines 137–151) uses strict `< -1e-9` so edges that just touch don't count as crossing. Correct.
- `isPointInPoly` containment fallback (lines 154–166) uses ray-casting; boundary points have asymmetric inclusion behaviour. Mostly correct but boundary-only contact CAN return true via the AABB+isPointInPoly path.

The plan needs a tighter gate: when two prism face polygons are separated by a non-zero gap or share only a boundary edge in iso-projected screen space, the gate must return false even if `hasIntersection` returns true. Concretely: add an interior-overlap check that requires non-trivial intersection area (or equivalently, at least one polygon vertex strictly INSIDE the other, OR at least one edge crossing strictly interior to both polygons).

**Pre-amendment tests**: 183 isometric-core tests green at commit `3e811aa`. PathTest (10 cases including the WS10 closerThan unit), DepthSorterTest (8 cases including the WS10 NodeIdSample integration test and the antisymmetry invariant), IntersectionUtilsTest (3 cases — disjoint, overlapping, contains). All retained as-is; the amendment ADDS new tests rather than modifying existing ones.

**Path.kt last modified:** `c4a62a5 build: prepare release v1.0.0`, then `3e811aa fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon`. The `closerThan`/`countCloserThan` functions are now in their post-`3e811aa` state and remain UNCHANGED by this amendment.

**Exact bug arithmetic** (verified by sub-agent 1 against the live source):

```kotlin
// Path.kt — countCloserThan, lines 127-140
// Careful with rounding approximations
if (observerPosition * pPosition >= 0.000000001) {
    result++
}
if (observerPosition * pPosition >= -0.000000001 && observerPosition * pPosition < 0.000000001) {
    result0++
}
// ...
return if (result == 0) {
    0
} else {
    (result + result0) / points.size   // ← integer division collapse
}
```

For the diagnosed face pair (factory-top vs hq-right at the WS10 NodeIdSample's coordinates):
- `factory_top.countCloserThan(hq_right)`: result = 0 (none of factory's vertices are on observer side of hq_right's plane) → returns 0.
- `hq_right.countCloserThan(factory_top)`: result = 2 (two of hq_right's four vertices are on observer side of factory_top's plane) → `(2 + 0) / 4 = 0` (integer division collapse) → returns 0.
- `closerThan(factory_top, hq_right) = 0 - 0 = 0`. `DepthSorter.checkDepthDependency` adds no edge. Pre-sort wins. Pre-sort puts hq_right at lower index (drawn first, in back) and factory_top at higher index (drawn later, on top) → factory paints over hq → bug visible.

**Test infrastructure** (sub-agent 3):
- `isometric-core`: 172 tests, all green. JUnit 4 + kotlin.test. Backtick test naming. No `truth`, no Kotest, no shared scene factory.
- `isometric-compose`: Paparazzi 1.3.0. JUnit 4 + Truth. CamelCase Paparazzi test naming. Snapshot baselines NOT committed to git (only 2 of 13 PNGs on disk, untracked) — pre-existing tech debt, out of scope for this fix but the new baseline we add must be committed.
- CI runs `./gradlew test` on Linux. Windows build-logic race does not affect CI. Local Windows verification requires the same `--stop` + `mv build-logic/build aside` dance we've been doing all session.

**No `IntersectionUtilsTest.kt` exists.** Will be created in this fix.

**No shared scene factory exists** in `isometric-compose:src/test/`. Snapshot tests build scenes inline in their test bodies. The user chose to extract the NodeIdSample geometry to a shared factory in this PLAN — adds one new file.

## Reuse Opportunities

- **`Prism` shape constructor** at `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Prism.kt` — *reuse as-is*. The new tests construct prisms identically to existing `DepthSorterTest` cases.
- **`IsometricEngine.add()` + `projectScene(800, 600, RenderOptions.NoCulling)`** pattern from `DepthSorterTest.kt` — *reuse as-is* for the integration test (AC-4). This is the established recipe in the file.
- **`Paparazzi()` no-args + `Box(modifier = Modifier.size(_, _))` + `IsometricScene { Shape(...) }`** pattern from `IsometricCanvasSnapshotTest.kt` — *reuse as-is* for the snapshot test (AC-5), with size = `800.dp x 600.dp` per Round 2 decision.
- **`@Test fun \`backtick test name\`()` + `kotlin.test.assertEquals/assertTrue`** pattern from `PathTest.kt` and `DepthSorterTest.kt` — *reuse as-is* for new core unit tests.
- **`Building` data class semantics** from `InteractionSamplesActivity.NodeIdSample` (`hq` h=3.0 / `factory` h=2.0 / `warehouse` h=1.5 / `tower` h=4.0 at `Point(i*2.0, 1.0, 0.1)`) — *extract into shared utility then use*. New file in `isometric-compose/src/test/kotlin/.../scenes/` will host the factory; both the new snapshot test and any future regression tests can call it.

No reuse candidate exists for the `closerThan` math itself. The original-scope fix is local to `Path.kt`.

**Amendment-1 reuse opportunities:**
- **`IntersectionUtils.hasIntersection`** at `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt:71-169` — *reuse with modification*. Existing SAT-based polygon overlap test that combines AABB rejection (lines 84–103), edge-crossing detection (lines 137–151), and point-in-polygon containment fallback (lines 154–166). The amendment requires either tightening this function's behaviour for boundary-only contact OR adding a sibling helper `hasInteriorIntersection` that returns false for the same boundary cases. Recommend ADDING a sibling helper to preserve the existing function's contract for any other (non-DepthSorter) callers — even if today it has only one caller.
- **`IntersectionUtils.isPointInPoly`** at `IntersectionUtils.kt:48-60` — *reuse as-is*. Ray-casting containment test; useful inside the new `hasInteriorIntersection` for "is at least one vertex strictly INSIDE the other polygon" check.
- **`WS10NodeIdScene`** at `isometric-compose/src/test/kotlin/.../scenes/WS10NodeIdScene.kt` — *reuse as-is*. Already extracted; the amendment-1 work adds three sibling factories (`OnClickRowScene`, `LongPressGridScene`, `AlphaSampleScene`) following the same pattern.
- **`DepthSorterTest`** existing pattern — *reuse as-is*. AC-9's 3×3 grid integration test follows the same `IsometricEngine().add(...).projectScene(800, 600, RenderOptions.NoCulling)` recipe used by the existing `WS10 NodeIdSample` integration test.

## Likely Files / Areas to Touch

1. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`** — modify `countCloserThan` (lines 127-140): change the integer-division return to permissive `result > 0`, and widen epsilon from `0.000000001` (1e-9) to `0.000001` (1e-6). Update KDoc on `countCloserThan` to explain the new semantics. **Net diff: ~10 lines**.
2. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`** — extend with new `closerThan`/`countCloserThan` unit tests covering AC-1, AC-2, AC-3 (parameterised over canonical adjacency configurations). **Net diff: ~80 lines new, no deletions**.
3. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — extend with the four-building integration test (AC-4) and the antisymmetry invariant `@Test` (AC-7). **Net diff: ~50 lines new**.
4. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** *(NEW)* — new file. Baseline coverage for adjacent / shared-edge / non-overlapping cases of `hasIntersection`. **Net diff: ~50 lines new**.
5. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`** *(NEW)* — new file. Extracts NodeIdSample geometry as a reusable test factory. **Net diff: ~30 lines new**.
6. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — append one new `@Test fun nodeIdSharedEdge()` calling `paparazzi.snapshot { ... }` with `Box(800.dp, 600.dp)` + `IsometricScene { WS10NodeIdScene() }`. **Net diff: ~10 lines new**.
7. **`isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`** *(NEW)* — new file. Generated by `recordPaparazzi`, committed to git so CI's `verifyPaparazzi` can compare against it. **Net diff: 1 binary file**.

**Total: 5 source files (3 modified, 2 new) + 1 binary snapshot baseline.** Original-scope estimate; superseded by amendment-1 below.

**Amendment-1 file additions:**

8. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`** — add a NEW public function `hasInteriorIntersection(pointsA, pointsB): Boolean` that reuses the existing AABB and SAT logic but rejects boundary-only contact (no overlapping interior area). Existing `hasIntersection` UNCHANGED so other callers retain their semantics. **Net diff: ~30 lines added**.

9. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`** — modify `checkDepthDependency` (lines 124-149) to use `hasInteriorIntersection` instead of `hasIntersection` as the gate for adding topological edges. Single-line behavioural change. **Net diff: ~3 lines (1 modified line + 2-3 line comment update)**.

10. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** — extend with cases for AC-10 + EC-9, EC-10: (a) two polygons sharing exactly one edge → `hasInteriorIntersection` returns false, `hasIntersection` keeps returning true (regression marker). (b) two polygons sharing exactly one vertex → false. (c) bounding-boxes overlap but interiors disjoint (e.g., one inside the other's BBox in a non-overlapping zone) → false. (d) interior overlap → true. **Net diff: ~50 lines new**.

11. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — extend with AC-9: `3x3 grid corner cube vertical faces are not at output positions 0-2`. Build the scene with 9 unit prisms at `Point(col*1.8, row*1.8, 0.1)` for col,row ∈ {0,1,2}, run DepthSorter, identify back-right cube's front (y=3.6 plane) and left (x=3.6 plane) faces by vertex geometry, assert `commandIndex >= 3`. **Net diff: ~40 lines new**.

12. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/OnClickRowScene.kt`** *(NEW)* — reusable factory replicating the OnClickSample geometry (5 prisms in a row, optionally one elevated to height=2 + IsoColor.YELLOW). **Net diff: ~25 lines new**.

13. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/LongPressGridScene.kt`** *(NEW)* — reusable factory replicating the LongPressSample 3×3 grid geometry. **Net diff: ~25 lines new**.

14. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/AlphaSampleScene.kt`** *(NEW)* — reusable factory replicating the AlphaSample mixed geometry (pyramid + cylinder + prisms). **Net diff: ~30 lines new**.

15. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — REPLACE the single `nodeIdSharedEdge` test (added at original scope, step 8) with FOUR new tests: `nodeIdRowScene` (renamed from nodeIdSharedEdge with its WS10NodeIdScene factory), `onClickRowScene`, `longPressGridScene`, `alphaSampleScene`. **Net diff: ~30 lines (replace 10 lines with 40)**.

16. **Four Paparazzi snapshot baselines** *(NEW)* under `isometric-compose/src/test/snapshots/images/` for each of the four AC-11 tests. Generated by `recordPaparazzi` on Linux CI; committed to git. **Net diff: 4 binary files**.

**Amendment-1 total: 9 source/test files (3 modified, 6 new) + 4 binary snapshot baselines.** Plus the original-scope work which is mostly already committed in `3e811aa`. The original `nodeIdSharedEdge` snapshot is REPLACED by `nodeIdRowScene` (new name reflecting its use as the row-layout regression test rather than the original "shared edge" framing). Remains within medium-appetite ceiling.

## Proposed Change Strategy

**Amendment-1 strategy (overlays on the original strategy below):**

The amendment work is purely additive on top of `3e811aa`. The existing red-green TDD steps 1–9 are CONSIDERED COMPLETE (committed in `3e811aa`). Amendment-1 work is steps A1–A6 below, executed as a second atomic commit `fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases`.

Original-scope strategy (already executed) follows for context:



1. **Red**: write the failing `closerThan` shared-edge unit test in `PathTest.kt`. Run; confirm it fails with the diagnosed `0` return value.
2. **Green**: edit `countCloserThan` in `Path.kt`: replace `(result + result0) / points.size` with `if (result > 0) 1 else 0`, and widen epsilon. Re-run the failing test; confirm it passes.
3. **Layered tests**: add the parameterised `closerThan` tests (AC-3), the `DepthSorter` integration test (AC-4), and the antisymmetry invariant (AC-7) to `DepthSorterTest`. Add the new `IntersectionUtilsTest.kt` baseline.
4. **Snapshot**: add `WS10NodeIdScene.kt` test factory; append the snapshot test to `IsometricCanvasSnapshotTest.kt`; run `recordPaparazzi` locally to generate the baseline PNG.
5. **Regression sweep**: run the full `:isometric-core:test` and `:isometric-compose:test` suites. Confirm no other test breaks. If any existing snapshot pixel-diffs (the strict policy from Round 5 of shape requires investigation), pause and review before re-baselining.
6. **Atomic commit**: stage all source changes + the new snapshot PNG + the (already-uncommitted) build-logic CC fix as separate commits if appropriate; the depth-sort fix itself is one `fix(depth-sort): ...` commit.

The KDoc update on `countCloserThan` is included in step 2's diff.

## Step-by-Step Plan

**Amendment-1 steps (NEW — execute these in this order):**

A1. **Pre-flight (amendment)**: confirm `isometric-core/.../Path.kt` and `isometric-core/.../DepthSorter.kt` are at commit `3e811aa` state (both files match `git show HEAD:<path>`). Confirm 183 isometric-core tests green. The diagnostic logging from yesterday's session must be REVERTED already (verified: working tree clean for both files).

A2. **Red — failing AC-9 test**: in `DepthSorterTest.kt`, add:
    ```kotlin
    @Test
    fun `WS10 LongPress 3x3 grid back-right cube vertical faces are not drawn first`() {
        val engine = IsometricEngine()
        engine.add(Prism(Point(-1.0, -1.0, 0.0), 8.0, 6.0, 0.1), IsoColor.LIGHT_GRAY)
        for (i in 0 until 9) {
            val row = i / 3; val col = i % 3
            engine.add(
                Prism(Point(col * 1.8, row * 1.8, 0.1), 1.2, 1.2, 1.0),
                IsoColor((col + 1) * 80.0, (row + 1) * 80.0, 150.0)
            )
        }
        val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

        // Identify back-right cube's vertical faces by vertex geometry
        val backRightFront = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.y in 3.59..3.61 } &&
                cmd.path.points.any { it.x in 3.59..3.61 } &&
                cmd.path.points.any { it.x in 4.79..4.81 }
        }
        val backRightLeft = scene.commands.indexOfFirst { cmd ->
            cmd.path.points.all { it.x in 3.59..3.61 } &&
                cmd.path.points.any { it.y in 3.59..3.61 } &&
                cmd.path.points.any { it.y in 4.79..4.81 }
        }

        assertTrue(backRightFront >= 3, "back-right cube's front face must not be at output positions 0-2; was at $backRightFront")
        assertTrue(backRightLeft >= 3, "back-right cube's left face must not be at output positions 0-2; was at $backRightLeft")
    }
    ```
    Run; confirm it FAILS (current `3e811aa` state has back-right vertical faces at positions 0–2 per the diagnostic).

A3. **Red — failing AC-10 test**: in `IntersectionUtilsTest.kt`, add:
    ```kotlin
    @Test
    fun `hasInteriorIntersection returns false for polygons sharing only an edge`() {
        val polyA = listOf(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0), Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0))
        val polyB = listOf(Point(1.0, 0.0, 0.0), Point(2.0, 0.0, 0.0), Point(2.0, 1.0, 0.0), Point(1.0, 1.0, 0.0))
        assertFalse(IntersectionUtils.hasInteriorIntersection(polyA, polyB))
        assertTrue(IntersectionUtils.hasIntersection(polyA, polyB), "old hasIntersection still returns true for shared-edge case (regression marker)")
    }

    @Test
    fun `hasInteriorIntersection returns false for polygons sharing only a vertex`() { /* analogous */ }

    @Test
    fun `hasInteriorIntersection returns true for genuine interior overlap`() { /* analogous, returns true */ }
    ```
    Run; confirm: (a) the new tests fail because `hasInteriorIntersection` doesn't exist yet; (b) the regression-marker assertion would FAIL too if we used the old function.

A4. **Green — implement `hasInteriorIntersection`**: in `IntersectionUtils.kt`, add:
    ```kotlin
    /**
     * Tests whether two convex polygons have a non-trivial interior overlap area.
     *
     * Stricter than [hasIntersection]: returns false when polygons share only an
     * edge or vertex (boundary touch with zero interior overlap). Used by
     * [DepthSorter.checkDepthDependency] to gate topological-edge insertion —
     * boundary-touch pairs do not need a draw-order edge because they don't
     * actually overpaint each other.
     */
    fun hasInteriorIntersection(pointsA: List<Point>, pointsB: List<Point>): Boolean {
        if (!hasIntersection(pointsA, pointsB)) return false

        // hasIntersection passed; now require at least one of:
        //   (1) a strictly-interior point of one polygon inside the other, OR
        //   (2) a strictly-interior edge crossing (already strict in hasIntersection's SAT path)
        // For boundary-only contact, hasIntersection's containment-fallback returns true
        // via isPointInPoly returning true for a vertex on the boundary, but no STRICT
        // crossing exists. We re-check that explicitly here.

        // ... implementation: invoke a strict-interior test (SAT with strict <0 thresholds
        // and isPointInPoly with ε exclusion of boundary) ...
    }
    ```
    Implement the strict-interior logic. Run AC-10 tests; confirm green.

A5. **Green — wire the gate**: in `DepthSorter.kt:133-136`, change:
    ```kotlin
    if (IntersectionUtils.hasIntersection(  // OLD
    ```
    to:
    ```kotlin
    if (IntersectionUtils.hasInteriorIntersection(  // NEW
    ```
    Update the surrounding comment to mention the gate's new strictness. Run AC-9 test from step A2; confirm green. Run the full `:isometric-core:test` suite; confirm 183 pre-existing tests + AC-9 + 3 AC-10 cases all green.

A6. **Replace Paparazzi snapshot test (AC-11)**: in `IsometricCanvasSnapshotTest.kt`, REMOVE the single `nodeIdSharedEdge()` test added at original-scope step 8. ADD four new tests:
    - `nodeIdRowScene()` — uses the existing `WS10NodeIdScene` factory.
    - `onClickRowScene()` — uses new `OnClickRowScene` factory (with one shape selected/elevated to test the height-change case).
    - `longPressGridScene()` — uses new `LongPressGridScene` factory (default state, no long-press; this is where the regression manifests).
    - `alphaSampleScene()` — uses new `AlphaSampleScene` factory.

    Create the three new scene factories under `isometric-compose/src/test/kotlin/.../scenes/`. Run `recordPaparazzi` on Linux CI (or accept the Windows blank-render gap and wait for CI). Commit four new baseline PNGs.

A7. **Maestro flow snapshots**: the existing maestro flows at `.ai/workflows/depth-sort-shared-edge-overpaint/maestro/{01-onclick.yaml, 02-longpress.yaml, 03-alpha.yaml}` produced screenshots in `verify-evidence/`. After the fix lands, re-run them and compare against the post-fix expected behaviour. Capture into `verify-evidence/post-fix/` for the verify stage.

A8. **Atomic commit**: `fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases`. Stage:
    - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`
    - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
    - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`
    - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/OnClickRowScene.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/LongPressGridScene.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/AlphaSampleScene.kt`
    - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`
    - 4 baseline PNGs under `isometric-compose/src/test/snapshots/images/`

    Commit message:
    ```
    fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases

    The permissive `result > 0` threshold landed in 3e811aa correctly resolves
    the WS10 NodeIdSample shared-edge case, but its asymmetric "winning votes"
    in countCloserThan fire even for face pairs whose 2D iso-projected polygons
    don't actually overlap. In 3x3 grid layouts (LongPressSample, AlphaSample),
    multiple such spurious votes accumulate as topological edges in
    DepthSorter.checkDepthDependency, pushing corner cubes' vertical faces to
    output positions 0-2 where they get painted over by faces drawn afterward.
    Visible regression: the back-right cube of the 3x3 grid renders with only
    its top face visible.

    Add IntersectionUtils.hasInteriorIntersection — stricter than hasIntersection;
    rejects boundary-only contact (shared edges, shared vertices, no interior
    overlap area). Wire DepthSorter.checkDepthDependency to gate topological-edge
    insertion on this stricter test. The closerThan algorithm itself is unchanged;
    only the gate that decides which closerThan results matter changes.

    Adds AC-9 (3x3 grid integration test asserting corner cubes' vertical faces
    are not at output positions 0-2), AC-10 (hasInteriorIntersection unit cases
    for boundary-only contact), AC-11 (four scene-factory Paparazzi snapshots
    replacing the single nodeIdSharedEdge baseline).

    Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
    ```

---

**Original-scope steps (already executed in commit `3e811aa`)**:

1. **Pre-flight**: confirm current branch is `feat/ws10-interaction-props`; confirm `git status` shows only the build-logic CC fix as uncommitted modification (no other surprises). Run `:isometric-core:test` once to confirm 172 tests pass on the pre-fix baseline.

2. **Red — write failing test**. In `PathTest.kt`, add:

   ```kotlin
   @Test
   fun `closerThan returns nonzero for hq-right vs factory-top shared-edge case`() {
       // Reproduces the WS10 NodeIdSample bug: hq right side (vertical at x=1.5,
       // z spans 0.1..3.1) vs factory top (horizontal at z=2.1, x spans 2.0..3.5).
       val hqRight = Path(
           Point(1.5, 1.0, 0.1), Point(1.5, 2.5, 0.1),
           Point(1.5, 2.5, 3.1), Point(1.5, 1.0, 3.1)
       )
       val factoryTop = Path(
           Point(2.0, 1.0, 2.1), Point(3.5, 1.0, 2.1),
           Point(3.5, 2.5, 2.1), Point(2.0, 2.5, 2.1)
       )
       val observer = Point(-10.0, -10.0, 20.0)
       val result = factoryTop.closerThan(hqRight, observer)
       // Expected sign: factory_top is "closer" by Newell semantics because
       // hq_right has 2 of 4 vertices on observer side of factory_top's plane.
       assertTrue(result > 0, "closerThan should return positive (factory_top wins as 'closer'); got $result")
   }
   ```

   Run `:isometric-core:test --tests PathTest`. Confirm this test fails with `result == 0`.

3. **Green — apply fix**. Edit `Path.kt`, `countCloserThan` (lines 127-140):

   - Change epsilon `0.000000001` → `0.000001` in both occurrences (lines 128 and 132).
   - Replace the return block:
     ```kotlin
     // Before
     return if (result == 0) {
         0
     } else {
         (result + result0) / points.size
     }
     // After — permissive: any vertex on observer side of pathA's plane wins
     return if (result > 0) 1 else 0
     ```
   - Update KDoc (line 105):
     ```kotlin
     /**
      * Count whether any vertex of this path is on the same side of pathA's plane
      * as the observer (within a 1e-6 epsilon to absorb floating-point noise).
      *
      * Returns 1 when at least one vertex is strictly observer-side, 0 otherwise.
      * Used by [closerThan] which subtracts both directions to produce a signed
      * comparator: positive if `this` is closer than pathA, negative if farther.
      *
      * Permissive ("any vertex" rather than "majority") was chosen to match Newell
      * semantics: A is closer than B if at least one of A's vertices lies on the
      * observer's side of B's plane. The previous integer-division `(result + result0)
      * / points.size` collapsed mixed cases (e.g. 2 of 4 same-side) to 0, producing
      * spurious "tied" results that the topological sort then could not resolve —
      * see workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
      */
     ```

   Re-run the test from step 2; confirm green.

4. **Layered unit tests**. In `PathTest.kt`, add a parameterised set covering AC-3 canonical adjacency pairings:

   - Two prisms sharing an X-face (left/right adjacent at the same y, same height).
   - Two prisms sharing a Y-face (front/back adjacent at the same x, same height).
   - One prism's top vs adjacent prism's vertical side, equal heights.
   - One prism's top vs adjacent prism's vertical side, *different* heights (the diagnosed case generalised).
   - Two prisms with truly coplanar non-overlapping faces (genuine tie → assert returns 0).

   Each as its own `@Test fun \`...\`()` with `assertTrue(result > 0, ...)` or `assertEquals(0, result, ...)`.

5. **Integration test for AC-4**. In `DepthSorterTest.kt`, add:

   ```kotlin
   @Test
   fun `WS10 NodeIdSample four buildings render in correct front-to-back order`() {
       val engine = IsometricEngine()
       val buildings = listOf(
           Triple("hq",       Point(0.0, 1.0, 0.1), 3.0),
           Triple("factory",  Point(2.0, 1.0, 0.1), 2.0),
           Triple("warehouse",Point(4.0, 1.0, 0.1), 1.5),
           Triple("tower",    Point(6.0, 1.0, 0.1), 4.0),
       )
       buildings.forEach { (_, pos, h) ->
           engine.add(Prism(pos, width = 1.5, depth = 1.5, height = h), IsoColor.BLUE)
       }
       val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

       // For every adjacent-building face pair where one is closer to observer,
       // the closer face's command appears LATER in the command list (drawn on top).
       // Specifically: hq's right side face must be drawn AFTER factory's top face.
       // (Test inspects scene.commands order; index of hq_right > index of factory_top.)
       // ... implementation of the order-inspection helper here ...
   }
   ```

6. **Antisymmetry invariant for AC-7**. In `DepthSorterTest.kt`, add:

   ```kotlin
   @Test
   fun `closerThan is antisymmetric for representative non-coplanar pairs`() {
       val pairs = listOf(/* hq_right + factory_top, plus 4-5 other shared-edge pairs */)
       val observer = Point(-10.0, -10.0, 20.0)
       pairs.forEach { (a, b) ->
           val ab = a.closerThan(b, observer)
           val ba = b.closerThan(a, observer)
           assertEquals(0, ab + ba, "closerThan must be antisymmetric for $a vs $b")
       }
   }
   ```

7. **New `IntersectionUtilsTest.kt`**. Create file. Cover: (a) two completely disjoint 2D polygons → false; (b) two overlapping 2D polygons → true; (c) two 2D polygons sharing only an edge (boundary touch, no interior overlap) → behaviour TBD by current implementation, document it. This is *baseline* coverage; not directly fixing the bug but creates the test surface for a future AABB-minimax workflow.

8. **Snapshot test infrastructure**. Create `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`:

   ```kotlin
   package io.github.jayteealao.isometric.compose.scenes

   import androidx.compose.runtime.Composable
   import io.github.jayteealao.isometric.IsoColor
   import io.github.jayteealao.isometric.Point
   import io.github.jayteealao.isometric.compose.runtime.IsometricSceneScope
   import io.github.jayteealao.isometric.compose.runtime.Shape
   import io.github.jayteealao.isometric.shapes.Prism

   /**
    * The 4-building scene from WS10 NodeIdSample, extracted as a reusable test factory.
    * Use inside `IsometricScene { WS10NodeIdScene() }` to render the canonical bug case.
    */
   @Composable
   fun IsometricSceneScope.WS10NodeIdScene() {
       Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 10.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
       Shape(geometry = Prism(Point(0.0, 1.0, 0.1), 1.5, 1.5, 3.0), color = IsoColor.BLUE)
       Shape(geometry = Prism(Point(2.0, 1.0, 0.1), 1.5, 1.5, 2.0), color = IsoColor.ORANGE)
       Shape(geometry = Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 1.5), color = IsoColor.GREEN)
       Shape(geometry = Prism(Point(6.0, 1.0, 0.1), 1.5, 1.5, 4.0), color = IsoColor.PURPLE)
   }
   ```

   Then in `IsometricCanvasSnapshotTest.kt`, append:

   ```kotlin
   @Test
   fun nodeIdSharedEdge() {
       paparazzi.snapshot {
           Box(modifier = Modifier.size(800.dp, 600.dp)) {
               IsometricScene { WS10NodeIdScene() }
           }
       }
   }
   ```

   Run `./gradlew :isometric-compose:recordPaparazzi`. The new PNG appears at `isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`. **Manually inspect** the PNG to confirm: no factory-top-over-hq-right artefact; back-to-front order looks correct.

9. **Atomic commit**. Stage:
   - `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
   - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`
   - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`
   - `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`
   - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`
   - `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`
   - `isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`

   Commit message:

   ```
   fix(depth-sort): permissive countCloserThan threshold + 1e-6 epsilon

   Path.countCloserThan previously collapsed a per-vertex plane-side vote
   into an integer fraction via integer division: (result + result0) / points.size.
   For a 4-vertex face where 2 vertices were on the observer side of the
   other plane, this evaluated to 2/4 = 0, discarding a real signal and
   reporting an ambiguous tie to closerThan. DepthSorter then added no
   topological edge for the pair, the back-to-front pre-sort by Path.depth
   became the sole determiner, and a farther face could be painted over a
   closer one at their shared screen-space corner. Visible regression in
   the WS10 NodeIdSample: factory's top face painted over hq's right side.

   Replace the integer-division collapse with a permissive sign-preserving
   threshold: countCloserThan returns 1 when any vertex of `this` is on
   the observer side of pathA's plane, 0 otherwise. Loosen the
   plane-side epsilon from 1e-9 to 1e-6 to absorb floating-point noise
   for the project's typical 0..100 coordinate range.

   Adds layered regression coverage: closerThan unit cases for canonical
   shared-edge pairings, DepthSorter integration test for the four-building
   scene, antisymmetry invariant test, IntersectionUtils baseline, and a
   Paparazzi snapshot of the WS10 NodeIdSample geometry.

   Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
   ```

## Test / Verification Plan

### Automated checks (post-amendment-1 expected state)

- **Lint/typecheck**: `./gradlew :isometric-core:assemble :isometric-compose:assemble` — confirms compile-clean.
- **Unit tests**: `./gradlew :isometric-core:test` should run **187 tests** (183 from pre-amendment-1 baseline + 1 AC-9 + 3 AC-10 cases), all green:
  - **From original scope (already passing in `3e811aa`):** PathTest AC-1, AC-2, AC-3 cases, DepthSorterTest AC-4 (WS10 NodeIdSample), AC-7 (antisymmetry), IntersectionUtilsTest 3 baseline cases.
  - **New (amendment-1):**
    - `DepthSorterTest.WS10 LongPress 3x3 grid back-right cube vertical faces are not drawn first` (AC-9).
    - `IntersectionUtilsTest.hasInteriorIntersection returns false for polygons sharing only an edge` (AC-10 case 1 + EC-10).
    - `IntersectionUtilsTest.hasInteriorIntersection returns false for polygons sharing only a vertex` (AC-10 case 2).
    - `IntersectionUtilsTest.hasInteriorIntersection returns true for genuine interior overlap` (AC-10 case 3).
- **Snapshot verification**: `./gradlew :isometric-compose:verifyPaparazzi` — must pass for FOUR baselines:
  - `IsometricCanvasSnapshotTest_nodeIdRowScene.png` (replaces `nodeIdSharedEdge.png`).
  - `IsometricCanvasSnapshotTest_onClickRowScene.png` (NEW, AC-11).
  - `IsometricCanvasSnapshotTest_longPressGridScene.png` (NEW, AC-11 — primary regression marker).
  - `IsometricCanvasSnapshotTest_alphaSampleScene.png` (NEW, AC-11).
- **Regression**: existing `DepthSorterTest` cases (`coplanar adjacent prisms`, `coplanar tile grid` broad-phase parity, `cycle fallback`, `kahn algorithm preserves existing broad phase sparse test`) must remain green. The new gate's stricter behaviour MUST NOT break the coplanar-tile-grid case (which relies on edges being added for tile-grid face pairs that DO have interior overlap in screen projection).

### Interactive verification (human-in-the-loop)

- **AC-5 — visual diff inspection of the new Paparazzi snapshot**:
  - Platform: developer machine running Paparazzi (Linux/Mac/Windows JVM, no emulator needed).
  - Tool: `./gradlew :isometric-compose:recordPaparazzi`, then open `isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png` in any image viewer.
  - Steps: run `recordPaparazzi`; locate the new PNG; visually inspect at the hq/factory boundary.
  - Pass criteria: no orange pixels appear within blue's right-side face area; the four buildings appear in left-to-right back-to-front order with correct occlusion at every adjacency.
  - Evidence capture: the PNG itself, committed to git as the baseline.

- **End-to-end verification on the actual sample (optional but recommended)**:
  - Platform: Android emulator `emulator-5554` (already running this session).
  - Tool: rebuild + reinstall via `./gradlew :app:installDebug` (with the build-logic Windows workaround dance), then re-run the Maestro flow `.ai/workflows/hotfix-long-press-node-id-sort/maestro-verify-nodeid.yaml` (it already navigates to the Node ID tab and screenshots).
  - Steps: build → install → maestro test → compare new screenshot to `04-nodeid-fixed.png` from the hotfix workflow.
  - Pass criteria: the boundary between blue (hq) and orange (factory) shows hq's right side in front of factory's top — no orange overpaint.
  - Evidence capture: pull screenshot via `adb shell screencap -p /sdcard/X.png` (with `MSYS_NO_PATHCONV=1`), save under `.ai/workflows/depth-sort-shared-edge-overpaint/screenshots/`.

### Risks / Watchouts

- **Build-logic Windows file-lock race** is still uncommitted and still active. Local `:isometric-compose:test` runs will hit it. Workaround documented in earlier hotfix logs — `./gradlew --stop && mv build-logic/build build-logic/build.stale-N-$(date +%s)` then build immediately. CI on Linux unaffected.
- **Paparazzi baselines not committed**: only 2 of 13 existing snapshot PNGs are on disk and even those are untracked. The new baseline must be committed explicitly. CI's `verifyPaparazzi` would currently fail for the 11 missing baselines if it were running — flag for a follow-up workflow but DO NOT include in this fix's scope.
- **Permissive threshold trade-off**: `result > 0` means a single vertex on observer side wins. In pathological scenes with extreme coordinate values where floating-point noise puts a vertex on the wrong side of a plane, a spurious dependency edge could be added. The 1e-6 epsilon mitigates; relative-epsilon scaling is the deferred future work.
- **Snapshot pixel diffs in the existing 2 baselines**: `pyramid.png` and `sampleThree.png` are already on disk and untracked. If our fix changes their pixels, we may need to re-record them (and commit them as drive-by improvement) — but per Round 5 strict policy, investigate first. Most likely they are unaffected because their geometry doesn't trigger the integer-division collapse, but verify.

## Dependencies on Other Slices

None. This is single-plan mode; no sibling plans exist.

## Assumptions

- The user's permissive-threshold choice (Round 2) is correct; the failing test in step 2 will be flipped to passing by step 3 with no further iteration. If the test still fails after step 3, return to PLAN to revisit the threshold (specifically check whether `result > 0` actually fires in BOTH directions for the hq-right case — sub-agent 1's math says yes, but verify with the actual print on first run).
- `recordPaparazzi` will succeed locally and produce a stable PNG. If pixel hashing differs across JVM versions or Paparazzi rendering quirks, the baseline may be flaky in CI — mitigated by Paparazzi using a fixed Layoutlib snapshot per version.
- The 11 missing Paparazzi baselines are pre-existing and not caused by this fix. Verified by `git ls-files isometric-compose/src/test/snapshots/` returning empty before this fix.
- The build-logic CC fix in the working tree continues to suffice for local Windows builds. Not committing it as part of this fix; it can be a separate `chore(build-logic):` commit at handoff.

## Blockers

None.

## Freshness Research

Inherited from shape's freshness pass. No additional searches required for plan synthesis. Sources still authoritative:
- [Newell's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Newell%27s_algorithm) — confirms the diagnosed case as the "shared-edge plane-side ambiguity" failure mode.
- [Painter's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Painter%27s_algorithm) — broader context.
- [shaunlebron.github.io/IsometricBlocks](https://shaunlebron.github.io/IsometricBlocks/) — alternative AABB minimax approach (deferred).
- [Paparazzi changelog](https://cashapp.github.io/paparazzi/changelog/) — version 1.3.0 pinned, no upgrade in this fix.
- [Kotlin sortedByDescending — stdlib docs](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/sorted-by-descending.html) — confirms TimSort stability of pre-sort fallback.

## Revision History

### Revision 1 — 2026-04-27T22:32:06Z — Directed Fix (apply amendment-1)

- **Mode:** Directed Fix (`/wf-plan depth-sort-shared-edge-overpaint amend-1`).
- **Source:** `02-shape-amend-1.md` and `07-review-grid-regression-diagnostic.md`.
- **What changed:**
  - Frontmatter: `metric-files-to-touch` 5 → 9, `metric-step-count` 9 → 14 (original 9 + amendment-1 A1–A8 with A6/A7 sometimes counted as one step block), `revision-count` 0 → 1, added `amends`, `applies-amendment`, `tags` for grid + screen-overlap-gate, `next-invocation` from wf-verify to wf-implement (since amendment-1 work is unimplemented).
  - `## Current State`: noted that Path.kt is already at `3e811aa` post-fix state and the amendment-1 work focuses on a SECOND bug class (over-aggressive edges in 3×3 grids). Added a deep read of `IntersectionUtils.hasIntersection` showing the AABB-rejection step accepts boundary-touching boxes, which is the proximate cause of the over-aggressive gate.
  - `## Reuse Opportunities`: added `IntersectionUtils.hasIntersection` (reuse with modification — recommend ADD a sibling `hasInteriorIntersection` rather than modifying the existing function), `IntersectionUtils.isPointInPoly` (reuse as-is in the new helper), `WS10NodeIdScene` (reuse as-is, plus add three sibling factories), `DepthSorterTest` integration test pattern.
  - `## Likely Files / Areas to Touch`: added 9 new file additions (steps A1–A8 above, listing the new IntersectionUtils sibling helper, the DepthSorter gate change, three new scene factories, expanded snapshot test file, and four new baselines).
  - `## Proposed Change Strategy`: layered amendment-1 strategy on top of the original (which is now committed in `3e811aa`).
  - `## Step-by-Step Plan`: prepended steps A1–A8 for amendment-1 work; original steps 1–9 retained for context but marked as "already executed in `3e811aa`".
  - `## Test / Verification Plan`: total test count changed from ~182–184 to 187 (183 pre-amendment + 4 new from AC-9 and AC-10). Replaced the single `nodeIdSharedEdge` snapshot with FOUR new scene-factory snapshots covering the regressed samples (LongPress, Alpha, OnClick) plus the original WS10 case (renamed).
- **Why:** The original `02-shape.md` was scoped to row-layout shared-edge cases. The diagnostic in `07-review-grid-regression-diagnostic.md` revealed a second bug class (3×3 grid corner cubes lose vertical faces because of accumulated over-aggressive topological edges). Amendment-1 expanded the shape to cover both bug classes; the plan needed to grow the file list, step sequence, test inventory, and snapshot baseline set accordingly.
- **What is preserved unchanged from revision 0:** all original-scope steps (1–9) and their committed result (`3e811aa`); `Path.kt`'s permissive `result > 0` predicate; the 1e-6 epsilon; AC-1 through AC-8 verification logic; the `WS10NodeIdScene` factory; the `IntersectionUtilsTest` baseline case set.
- **What is invalidated:** the single `nodeIdSharedEdge` snapshot test (REPLACED by `nodeIdRowScene`, equivalent geometry, renamed for clarity); the implicit assumption that the WS10 fix was the complete bug class.

## Recommended Next Stage

- **Option A (default, post-amendment-1):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — execute amendment-1 steps A1–A8 (add `IntersectionUtils.hasInteriorIntersection`, wire it in `DepthSorter.checkDepthDependency`, add AC-9 + AC-10 tests, replace the single Paparazzi snapshot with four scene-factory snapshots). All original-scope work is already in `3e811aa`. The directed-fix revision history above documents exactly what the implementer is to add. **Compact recommended before invoking implement** — the back-and-forth from the prior failed reviews-mode attempt and the diagnostic investigation is noise; the plan now stands alone.

- **Option B:** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — only if the user wants to re-verify the existing `3e811aa` state without the amendment-1 work. Not recommended; the verify stage already ran (06-verify-*) and produced result `partial` with the LongPress regression unverified — that's exactly what amendment-1 addresses.

- **Option C:** `/wf-amend depth-sort-shared-edge-overpaint from-review` — only if amendment-1's strategy itself proves wrong during implement. Not currently triggered.
