---
schema: sdlc/v1
type: plan
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 4
created-at: "2026-04-26T19:33:07Z"
updated-at: "2026-04-26T19:33:07Z"
metric-files-to-touch: 5
metric-step-count: 9
has-blockers: false
revision-count: 0
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 02-shape.md
  siblings: []
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
next-command: wf-verify
next-invocation: "/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Plan: depth-sort shared-edge overpaint

## Current State

**Path.kt last modified:** `c4a62a5 build: prepare release v1.0.0`. The `closerThan`/`countCloserThan` functions have not been touched since the v1.0.0 release commit. The bug is genuinely 2+ years old and was only exposed by the WS10 NodeIdSample geometry (commit `e4aa792`).

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

No reuse candidate exists for the `closerThan` math itself. The fix is local to `Path.kt`.

## Likely Files / Areas to Touch

1. **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`** — modify `countCloserThan` (lines 127-140): change the integer-division return to permissive `result > 0`, and widen epsilon from `0.000000001` (1e-9) to `0.000001` (1e-6). Update KDoc on `countCloserThan` to explain the new semantics. **Net diff: ~10 lines**.
2. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`** — extend with new `closerThan`/`countCloserThan` unit tests covering AC-1, AC-2, AC-3 (parameterised over canonical adjacency configurations). **Net diff: ~80 lines new, no deletions**.
3. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — extend with the four-building integration test (AC-4) and the antisymmetry invariant `@Test` (AC-7). **Net diff: ~50 lines new**.
4. **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** *(NEW)* — new file. Baseline coverage for adjacent / shared-edge / non-overlapping cases of `hasIntersection`. **Net diff: ~50 lines new**.
5. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`** *(NEW)* — new file. Extracts NodeIdSample geometry as a reusable test factory. **Net diff: ~30 lines new**.
6. **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — append one new `@Test fun nodeIdSharedEdge()` calling `paparazzi.snapshot { ... }` with `Box(800.dp, 600.dp)` + `IsometricScene { WS10NodeIdScene() }`. **Net diff: ~10 lines new**.
7. **`isometric-compose/src/test/snapshots/images/io.github.jayteealao.isometric.compose_IsometricCanvasSnapshotTest_nodeIdSharedEdge.png`** *(NEW)* — new file. Generated by `recordPaparazzi`, committed to git so CI's `verifyPaparazzi` can compare against it. **Net diff: 1 binary file**.

**Total: 5 source files (3 modified, 2 new) + 1 binary snapshot baseline.** Comfortably under the medium-appetite ceiling.

## Proposed Change Strategy

Strict red-green TDD per Round 2:

1. **Red**: write the failing `closerThan` shared-edge unit test in `PathTest.kt`. Run; confirm it fails with the diagnosed `0` return value.
2. **Green**: edit `countCloserThan` in `Path.kt`: replace `(result + result0) / points.size` with `if (result > 0) 1 else 0`, and widen epsilon. Re-run the failing test; confirm it passes.
3. **Layered tests**: add the parameterised `closerThan` tests (AC-3), the `DepthSorter` integration test (AC-4), and the antisymmetry invariant (AC-7) to `DepthSorterTest`. Add the new `IntersectionUtilsTest.kt` baseline.
4. **Snapshot**: add `WS10NodeIdScene.kt` test factory; append the snapshot test to `IsometricCanvasSnapshotTest.kt`; run `recordPaparazzi` locally to generate the baseline PNG.
5. **Regression sweep**: run the full `:isometric-core:test` and `:isometric-compose:test` suites. Confirm no other test breaks. If any existing snapshot pixel-diffs (the strict policy from Round 5 of shape requires investigation), pause and review before re-baselining.
6. **Atomic commit**: stage all source changes + the new snapshot PNG + the (already-uncommitted) build-logic CC fix as separate commits if appropriate; the depth-sort fix itself is one `fix(depth-sort): ...` commit.

The KDoc update on `countCloserThan` is included in step 2's diff.

## Step-by-Step Plan

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

### Automated checks

- **Lint/typecheck**: `./gradlew :isometric-core:assemble :isometric-compose:assemble` — confirms compile-clean.
- **Unit tests**: `./gradlew :isometric-core:test` should run 172 (pre-existing) + ~10–12 (new) = ~182–184 tests, all green. Specifically:
  - `PathTest.closerThan returns nonzero for hq-right vs factory-top shared-edge case` (AC-1)
  - `PathTest.closerThan returns 0 for genuinely coplanar non-overlapping faces` (AC-2)
  - `PathTest.<5 parameterised shared-edge cases>` (AC-3)
  - `DepthSorterTest.WS10 NodeIdSample four buildings render in correct front-to-back order` (AC-4)
  - `DepthSorterTest.closerThan is antisymmetric for representative non-coplanar pairs` (AC-7)
  - `IntersectionUtilsTest.<3 baseline cases>` (AC for the new file's coverage of adjacent / overlap / disjoint).
- **Snapshot verification**: `./gradlew :isometric-compose:verifyPaparazzi` — must pass for the new `nodeIdSharedEdge` test against the committed baseline PNG.
- **Regression**: existing `DepthSorterTest` cases (`coplanar adjacent prisms`, `3x3 grid face count`, `cycle fallback`, broad-phase parity) must remain green.

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

*(none yet — initial plan)*

## Recommended Next Stage

- **Option A (default):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — plan is execution-ready. Strict red-green order locked in. Compact recommended before invoking implement.
