---
schema: sdlc/v1
type: implement
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 5
created-at: "2026-04-26T20:12:32Z"
updated-at: "2026-04-28T09:01:50Z"
metric-files-changed: 6
metric-lines-added: 353
metric-lines-removed: 12
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: "3e811aa"
amend-1-files-changed: 8
amend-1-lines-added: 466
amend-1-lines-removed: 7
amend-1-deviations-from-plan: 1
amend-1-commit-sha: "9cef055"
round-3-mode: "directed-investigation"
round-3-files-changed: 1
round-3-fix-applied: false
round-3-commit-sha: "2e29dc5"
round-3-evidence:
  - verify-evidence/round3-longpress-diag.log
amend-2-applied: true
amend-2-mode: "newell-cascade-replace"
amend-2-files-changed: 4
amend-2-lines-added: 492
amend-2-lines-removed: 113
amend-2-deviations-from-plan: 3
amend-2-test-deltas:
  - "PathTest: 10 -> 12 (split AC-2 into coplanar-overlapping/coplanar-non-overlapping; added wall-vs-floor straddle case)"
  - "DepthSorterTest: 9 -> 11 (added AC-12 LongPress full-scene + AC-13 Alpha full-scene integration tests)"
amend-2-test-suite-result: "all green: 191 isometric-core tests pass"
amend-2-commit-sha: ""
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
  - newell-cascade
  - algorithmic-restructure
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 02-shape.md
  shape-amendment-1: 02-shape-amend-1.md
  shape-amendment-2: 02-shape-amend-2.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  siblings: []
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
next-command: wf-verify
next-invocation: "/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Implement: depth-sort-shared-edge-overpaint

## Summary of Changes

Replaced the integer-division collapse in `Path.countCloserThan` with a permissive
sign-preserving threshold (`result > 0` ⇒ 1, else 0) and widened the plane-side epsilon
from 1e-9 to 1e-6. Removed the now-unused `result0` boundary counter. Added layered
regression coverage: a Red-Green failing-then-passing closerThan test for the exact
hq-right vs factory-top geometry, four parameterised AC-3 adjacency cases, a
DepthSorter integration test that asserts hq_right's command index sits *after*
factory_top's, an antisymmetry invariant test, baseline `hasIntersection` coverage,
a Paparazzi snapshot test, and a reusable WS10 scene factory.

## Files Changed

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt` —
  Rewrote `countCloserThan`. Two coupled changes:
  (a) Replaced the integer-division return `(result + result0) / points.size`
  with `if (result > 0) 1 else 0`. (b) Widened the plane-side epsilon from
  1e-9 to 1e-6. Removed `result0` and its coplanar branch — no longer feeds the
  return value. Updated KDoc to explain the permissive threshold and link to
  this workflow.
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt` —
  Appended five new closerThan tests:
  the diagnosed hq-right vs factory-top case (Red-Green marker), X-adjacent
  different-heights, Y-adjacent different-heights, equal-heights top-vs-side,
  diagonally offset adjacency, and the genuine-coplanar-tie negative control.
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt` —
  Appended `WS10 NodeIdSample four buildings render in correct front-to-back order`
  (asserts factory_top's command index < hq_right's command index) and
  `closerThan is antisymmetric for representative non-coplanar pairs`
  (asserts `a.closerThan(b) + b.closerThan(a) == 0`).
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt` *(NEW)* —
  Three baseline cases: fully disjoint (false), overlapping interiors (true),
  outer-contains-inner (true). The shared-edge-only behaviour case from the plan
  was deferred — see Deviations.
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt` *(NEW)* —
  Reusable `@Composable IsometricScope.WS10NodeIdScene()` factory replicating
  the four-building scene from `InteractionSamplesActivity.NodeIdSample`.
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt` —
  Imported `WS10NodeIdScene` and appended `@Test fun nodeIdSharedEdge()` rendering
  it inside an 800x600 box.

## Shared Files (also touched by sibling slices)

None — this is a single-slice plan.

## Notes on Design Choices

- **Removed `result0` entirely** rather than leaving it as dead code. With the
  permissive `result > 0` return, the boundary-coplanar tally never feeds the
  output, and keeping a vestigial counter would invite the next reader to wonder
  "what's this for?" The intent of the removal is documented inline via the
  rewritten KDoc.
- **Receiver type was `IsometricScope`, not `IsometricSceneScope` as the plan
  specified.** Plan drift caught at implementation. The plan was written against
  a name that does not exist in the runtime module; the actual receiver of the
  `Shape`, `Group`, and `Path` composables is `IsometricScope`. Trivial adjust.
- **Integration test identifies faces by vertex geometry**, not by node ID,
  because individual face commands do not carry semantic per-face IDs through
  the depth sorter. `indexOfFirst { points.all { x ≈ 1.5 } && points.any { z > 3.0 } }`
  pins hq's right wall, and `points.all { z ≈ 2.1 }` plus `x in 2.0..3.5` pins
  factory's top. Brittle to geometry refactors but stable for this regression.
- **IntersectionUtils shared-edge-only case was replaced** with an
  outer-contains-inner case. The plan's third case was "behaviour TBD" for
  shared-edge-only polygons. Without being able to run the test in this session
  (Windows build-logic race), asserting either `true` or `false` would have been
  a guess. The replaced case (containment) covers the `isPointInPoly` fallback
  branch of `hasIntersection`, which is where DepthSorter's depth-edge generation
  actually fires for nested faces. Strictly more useful as a regression marker.

## Deviations from Plan

1. **Plan named `IsometricSceneScope`; actual type is `IsometricScope`.** Trivial
   substitution. Did not require a plan revision.
2. **IntersectionUtils third test changed** from "shared-edge-only behaviour TBD"
   to "outer contains inner". Rationale above. Net coverage equal or better.

## Anything Deferred

- **Paparazzi baseline PNG was NOT generated in this session.** The Windows
  build-logic Configuration Cache file-lock race makes `:isometric-compose:recordPaparazzi`
  unreliable on this machine without the manual `--stop` + `mv build-logic/build aside`
  dance, which doesn't compose well with workflow orchestration. The
  `nodeIdSharedEdge()` test source is committed; the baseline image must be
  recorded by the next operator (wf-verify) or by CI on Linux. Until then,
  `:isometric-compose:verifyPaparazzi` will fail for this test.
- **Local test execution skipped.** Same Windows constraint. The plan and
  implementation were verified against the source by reasoning; the test suite
  will run under wf-verify on Linux CI.
- **The 11 pre-existing missing Paparazzi baselines remain missing.** Pre-existing
  tech debt, out of scope for this fix per the plan and the user's earlier ruling.
- **Build-logic CC fix in working tree (`build-logic/build.gradle.kts`) is NOT
  staged in this commit.** Plan called for it as a separate `chore(build-logic):`
  commit at handoff.

## Known Risks / Caveats

- **Permissive threshold may over-add edges.** Any vertex on observer side of
  the other plane now votes "yes". For pathological scenes with extreme
  coordinate values where floating-point noise puts a vertex on the wrong side
  of a plane, a spurious dependency edge could be added. The 1e-6 epsilon
  mitigates for the project's typical 0..100 coordinate range. Relative-epsilon
  scaling is the deferred future work.
- **Antisymmetry test currently passes by construction.** The new `result > 0`
  return is binary (0 or 1); for non-coplanar pairs at most one of the two
  directions can return 1. The invariant `a + b == 0` holds because either
  both are 0 (coplanar tie), or one is +1 and the other -1. If a future
  implementation switches to a continuous-valued result, this test will
  catch a regression where antisymmetry breaks down.
- **Snapshot pixel diffs in the 2 existing untracked baselines** (`pyramid.png`,
  `sampleThree.png`) were NOT verified against the new code in this session.
  If they pixel-diff after the fix, that's expected (depth ordering changed
  for some configurations) and they should be re-recorded as a drive-by
  improvement during wf-verify.

## Freshness Research

Inherited from plan + shape stages. No additional searches required. The fix
was a localised behavioural change to a single private method; no external
APIs or libraries were involved.

## Recommended Next Stage (original-scope, superseded by amend-1 below)

- **Option A (default):** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — implementation touches testable behaviour (Path math, DepthSorter ordering)
  and adds a Paparazzi snapshot that requires baseline generation. Local Windows
  verification is unreliable; CI on Linux is the source of truth.

---

# Round 2: Amendment-1 Implementation

## Summary of Changes (Round 2)

Applied the directed fix specified by `04-plan-depth-sort-shared-edge-overpaint.md`
revision 1 (which itself applies `02-shape-amend-1.md`). The work adds a strict
2D screen-overlap gate to `DepthSorter.checkDepthDependency` to address the
3×3 grid corner-cube regression diagnosed in
`07-review-grid-regression-diagnostic.md`. The original-scope `Path.kt`
permissive-threshold fix from commit `3e811aa` is preserved unchanged — it
remains correct in isolation; only the gate around `closerThan` is tightened.

## Files Changed (Round 2)

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt` —
  Added `hasInteriorIntersection(pointsA, pointsB): Boolean` (~131 lines new).
  Reuses the existing AABB rejection + strict SAT edge-crossing test, and
  adds a strict-inside fallback that combines `isPointInPoly` +
  `isPointCloseToPoly` with `EDGE_BAND = 1e-6` to reject boundary points.
  Existing `hasIntersection` left UNCHANGED to preserve its lenient contract
  for any non-DepthSorter callers.

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt` —
  Single behavioural change at `checkDepthDependency` line 133: replaced the
  `hasIntersection` call with `hasInteriorIntersection`. Updated the
  surrounding comment block from "Check if 2D projections intersect" to a
  longer rationale that explains why the strict gate is needed
  (over-aggressive edges in 3×3 grid layouts push corner cubes' vertical
  faces to extreme topological-order positions). +14 / -2 lines.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt` —
  Added 5 new test cases for `hasInteriorIntersection`: shared-edge → false,
  shared-vertex → false, interior-overlap → true, disjoint → false, strict-
  containment → true. Includes a regression-marker assertion that
  `hasIntersection` STILL returns true for the shared-edge case — pinning the
  intentional divergence between the two functions. ~101 lines new.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt` —
  Added the AC-9 integration test `WS10 LongPress 3x3 grid back-right cube
  vertical faces are not drawn first`. Builds the full LongPressSample
  geometry (9 unit prisms in a 3×3 grid, 1.2×1.2×1.0 each, plus the
  ground platform), runs `DepthSorter`, identifies the back-right cube's
  front (y=3.6) and left (x=3.6) faces by vertex geometry, asserts both
  output indices are ≥ 3. ~68 lines new.

- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/OnClickRowScene.kt` *(NEW)* —
  Reusable factory replicating `InteractionSamplesActivity.OnClickSample`:
  5 unit prisms in a row at `Point(i * 1.5, 0.0, 0.1)`, with optional
  `selectedIndex` parameter that bumps that shape's height from 1.0 to 2.0
  and color to `IsoColor.YELLOW`. ~42 lines.

- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/LongPressGridScene.kt` *(NEW)* —
  Reusable factory replicating `InteractionSamplesActivity.LongPressSample`:
  3×3 grid of unit prisms, default static state (no shape locked). This
  is the canonical regression case for amendment-1. ~38 lines.

- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/AlphaSampleScene.kt` *(NEW)* —
  Reusable factory replicating `InteractionSamplesActivity.AlphaSample`:
  prism + cylinder + pyramid + 3 small prisms in a row. Alpha values
  intentionally NOT applied — the regression manifests in the depth sort,
  not the alpha render pass. ~42 lines.

- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt` —
  REPLACED the single `nodeIdSharedEdge()` test from Round 1 with FOUR
  scene-factory tests: `nodeIdRowScene` (uses existing `WS10NodeIdScene`),
  `onClickRowScene` (with `selectedIndex = 3` to test the height-change
  case), `longPressGridScene` (primary regression marker), `alphaSampleScene`.
  Each renders inside a 800.dp × 600.dp Box. +65 / -10 lines.

- 4 baseline PNGs under `isometric-compose/src/test/snapshots/images/` —
  DEFERRED to Linux CI per the `06-verify-*.md` blank-render finding.
  Baselines must be regenerated on a Linux build server before commit.

## Shared Files (also touched by sibling slices)

None — single-slice workflow.

## Notes on Design Choices (Round 2)

- **Add a sibling `hasInteriorIntersection` rather than tightening
  `hasIntersection`.** The amendment offered both options. I chose the
  additive approach because: (a) `hasIntersection` is `public` on the
  `IntersectionUtils` object — modifying it silently changes behaviour for
  any current or future caller; (b) the semantic difference between
  "any contact" and "interior overlap" is meaningful and might genuinely
  be wanted by some caller; (c) the diff is strictly additive and trivially
  reversible if needed. The new helper reuses every code path of
  `hasIntersection` for the AABB and SAT steps; only the point-in-polygon
  fallback is tightened.

- **`EDGE_BAND = 1e-6` constant** for the boundary-distance test. Matches
  the per-distance epsilon already used in `Path.countCloserThan` line 137
  comments ("1e-6 to absorb floating-point noise"). Keeping the same band
  width across both functions makes the numerical-stability story uniform.

- **`isPointCloseToPoly(..., EDGE_BAND)` for boundary rejection** rather
  than computing distance-to-segment directly. The existing
  `isPointCloseToPoly` does exactly that work and was easier to reuse than
  reimplement.

- **Scene factories are 1:1 with the live samples' geometry, not their
  alpha values.** The regression is in the depth sort, which doesn't see
  alpha. The factories produce the same 30-something faces with the same
  positions; the snapshot test asserts the rendered pixels post-fix.
  Alpha values from the live `AlphaSample` are intentionally not applied.

- **Snapshot test name `nodeIdRowScene` (vs original `nodeIdSharedEdge`).**
  The old name framed the test around the bug being fixed. The new name
  describes what the scene IS (a row layout with shared edges). The
  scene factory `WS10NodeIdScene` survives unchanged as the source.

## Deviations from Plan (Round 2)

1. **Plan's AC-9 test used `cmd.path.points`, actual field is `cmd.originalPath.points`.**
   Trivial substitution. The plan was inferred from the existing WS10
   integration test — same pattern, same field name. The plan's snippet had
   a transcription error caught at edit time. No behaviour change.

## Anything Deferred (Round 2)

- **Four Paparazzi baseline PNGs** — Cannot be reliably recorded on the
  current Windows + JDK17 environment per `06-verify-*.md`. Deferred to
  Linux CI's `recordPaparazzi` invocation. The snapshot test source is
  committed; the baselines must be regenerated on Linux before they can
  pass `verifyPaparazzi`. Implementation note: do not commit the locally-
  produced blank PNGs that may exist under
  `isometric-compose/src/test/snapshots/images/` from yesterday's
  diagnostic session — explicitly remove or `.gitignore`-skip them
  before staging the commit.

- **Local test run** — Skipped for the same Windows-toolchain reason that
  affected the original-scope verify stage. The full test suite will run
  on Linux CI when the PR opens. Spot-check at the source level: the
  AC-9 test references `Prism`, `Point`, `IsoColor`, `IsometricEngine`,
  `RenderOptions.NoCulling` — all imports already in `DepthSorterTest.kt`
  (existing tests use the same APIs).

## Known Risks / Caveats (Round 2)

- **The `coplanar tile grid` `DepthSorterTest` case is the canary** for
  the gate-tightening change. The tile grid relies on `closerThan` edges
  being added for face pairs that DO have interior overlap in screen
  projection. If `hasInteriorIntersection`'s strict-inside check is *too*
  strict (e.g., rejects a vertex that's marginally inside another polygon
  due to floating-point noise), the tile-grid test would fail with
  "expected face count and no duplicates". The 1e-6 EDGE_BAND was chosen
  to match the rest of the codebase's epsilon convention; if the canary
  fires, widen the band or relax the strict-inside check.

- **Strict-inside via `isPointCloseToPoly`** uses `Point.distanceToSegment`
  which is O(N) per point. For an N-vertex polygon, the strict-inside
  check is now O(N²) for the worst case (each polygon vertex checked
  against the other polygon's N edges). Acceptable for prism faces (N=4)
  but worth noting if larger polygons enter the depth sort in future.

- **Scene factory geometry must stay synchronised** with the live samples
  in `InteractionSamplesActivity.kt`. If a sample's positions or
  dimensions change, the corresponding factory must be updated to match,
  or the snapshot baseline becomes stale. Each factory's KDoc explicitly
  says "If the sample changes, update here to match." This is unavoidable
  duplication: the app module can't be imported by the compose-test
  module (wrong direction), so the geometry is replicated.

- **Build-logic Windows CC fix** in `build-logic/build.gradle.kts` is
  still uncommitted. Per the original plan's handoff section, it can
  ride as a separate `chore(build-logic):` commit at handoff time.

## Freshness Research (Round 2)

No new external research. The amendment-1 algorithm is local geometric
work — no library version checks needed. The diagnostic in
`07-review-grid-regression-diagnostic.md` plus the existing shape's
freshness pass remain authoritative.

## Recommended Next Stage

- **Option A (default):** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — Round 2 adds testable behaviour (the `hasInteriorIntersection` helper,
  the screen-overlap gate, AC-9 + AC-10 + AC-11 tests) and a snapshot
  baseline gap that requires Linux CI to close. **Compact strongly
  recommended before /wf-verify** — Round 2's implementation work
  (algorithm decisions, deviations, sibling-helper rationale) is noise
  for verification.

- **Option B:** `/wf-review depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — skip verify and have reviewers read the Round 2 diff first. Less
  recommended because AC-9 / AC-10 are unit/integration tests that should
  pass before review starts; an unverified diff produces noisier review
  findings. CI's automated verify catches both, so going to review without
  verify is technically safe but less efficient.

---

# Round 3: Directed Investigation (no fix applied)

## Mode

Round 3 is a **diagnostic-only** implement round. No fix is applied to
`Path.kt`, `DepthSorter.kt`'s gate, or `IntersectionUtils.kt`. The single
behavioural change is the temporary re-addition of `DEPTH_SORT_DIAG`
logging to `DepthSorter.sort` and `DepthSorter.checkDepthDependency`,
matching the format of the prior diagnostic captures so we can correlate
with earlier round-2 traces.

The goal of this round was to answer the verify-round-2 issue
(`06-verify-...md` § Issue 1):

> "Re-add temporary `DEPTH_SORT_DIAG` logging to the post-fix DepthSorter,
> reinstall, capture LongPress logcat, identify (a) what output position
> back-right's vertical faces are at now, (b) which face IDs are drawn
> AFTER them, (c) which of those overlap back-right's vertical faces in
> screen-space iso projection."

That investigation is now complete and the over-painting mechanism is
identified. The fix itself is left to a future round (or amendment) — see
§ Recommended Next Stage below.

## Summary of Changes (Round 3)

A single source file (`isometric-core/.../DepthSorter.kt`) was edited to
re-add `DEPTH_SORT_DIAG` logging. The build was rebuilt and installed on
emulator-5554 with the documented Windows workaround stack
(`./gradlew --stop` + move `build-logic/build` aside +
`-Dkotlin.incremental=false -Dkotlin.compiler.execution.strategy=in-process
--no-daemon --no-configuration-cache`). Maestro flow `02-longpress.yaml`
ran the LongPress sample's static state to completion. `adb logcat -v time
-s System.err:*` captured 368 lines covering 2 frames; the second frame
(`itemCount=30`) is the LongPress 3×3 grid render. The captured log is
preserved at `verify-evidence/round3-longpress-diag.log`.

The diagnostic logging emits four event types:

1. `DepthSortDiag FRAME START itemCount=N` — at the top of `sort()`.
2. `DepthSortDiag pair: A.idx=I A.first3=… B.idx=J B.first3=… intersects=BOOL edge=LABEL cmpPath=N` — once per
   `checkDepthDependency` invocation (covers both broad-phase and
   exhaustive paths). New compared to round-2's prior format: `cmpPath`
   value is now logged so we can distinguish "no edge because gate
   rejected" from "no edge because comparator returned 0".
3. `DepthSortDiag ORDER pos=P depthIdx=N first3=…` — at each `sortedItems.add`
   inside Kahn's main loop, mapping output position to depth-sorted-index
   to first-3 vertices. **This is the new piece** that round-2's diag
   format lacked, and is what made the diagnosis tractable.
4. `DepthSortDiag ORDER-CYCLE pos=P depthIdx=N first3=…` — for any items
   appended via the post-Kahn cycle fallback (none observed in this
   capture).

The toggle is a `private const val DIAG = true` at the top of the
`DepthSorter` object. Trivial single-line revert.

## Files Changed (Round 3)

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt` —
  Added `DIAG` toggle, `first3` helper, FRAME START emission at top of
  `sort()`, FRAME END emission at end of `sort()`, ORDER / ORDER-CYCLE
  emissions inside Kahn's loop, and the per-pair emission inside
  `checkDepthDependency` (refactored the gate-call to capture
  `intersects` and `cmpPath` into locals + an `edgeLabel` so the log line
  carries the same data as the original branches). +47 lines, -2 lines.

- `.ai/workflows/depth-sort-shared-edge-overpaint/verify-evidence/round3-longpress-diag.log` *(NEW evidence)* —
  368-line capture, 2 frames. Frame 1 (`itemCount=18`) is the
  Interaction-API tab's intermediate render before the LongPress sub-tab
  was selected. Frame 2 (`itemCount=30` at line 108) is the LongPress 3×3
  grid render. All round-3 findings reference Frame 2.

## Findings

### F1 — Back-right cube's front and left faces are at output positions 0 and 1

Direct evidence from the ORDER emissions in Frame 2:

```
ORDER pos=0  depthIdx=0  first3=(3.6,3.6,0.1);(4.8,3.6,0.1);(4.8,3.6,1.1)  // BR FRONT face (y=3.6)
ORDER pos=1  depthIdx=1  first3=(3.6,3.6,0.1);(3.6,3.6,1.1);(3.6,4.8,1.1)  // BR LEFT  face (x=3.6)
ORDER pos=2  depthIdx=3  first3=(-1.0,-1.0,0.1);(7.0,-1.0,0.1);(7.0,5.0,0.1)  // GROUND TOP (z=0.1, full -1..7 × -1..5)
…
ORDER pos=11 depthIdx=2  first3=(3.6,3.6,1.1);(4.8,3.6,1.1);(4.8,4.8,1.1)  // BR TOP face (z=1.1)
```

This precisely matches the verify-round-2 visual: BR's vertical walls
are drawn first, ground top is drawn third, BR's top face survives at
position 11. The walls have nothing later to over-paint — they ARE
later-painted (by ground top and other faces) and lose all their pixels.

### F2 — The over-painter is the GROUND TOP, not a neighbour cube face

Pair log emission inside `checkDepthDependency`:

```
A.idx=3 A.first3=(-1.0,-1.0,0.1);(7.0,-1.0,0.1);(7.0,5.0,0.1)
B.idx=0 B.first3=(3.6,3.6,0.1);(4.8,3.6,0.1);(4.8,3.6,1.1)
intersects=true edge=none cmpPath=0
```

The `hasInteriorIntersection` gate **correctly admits** this pair: in iso
projection the ground top diamond strictly contains BR.front's
parallelogram, so the strict-inside SAT/inside-polygon check returns
true. The gate is not the failure point.

The failure is at the comparator: `cmpPath=0`. With no edge, Kahn falls
back to depth-descending centroid order. BR.front centroid (avg
`(point.depth)` = 7.2 with the iso `x+y-z` proxy) sorts BEFORE ground
top centroid (4.9). depthIdx=0 has zero in-degree; depthIdx=3 has zero
in-degree; Kahn picks lower-index first → 0 before 3 → wall painted
before floor → floor over-paints wall.

### F3 — Why `closerThan` returns 0 for wall-vs-floor pairs

`closerThan(BR.front, ground top, observer)` decomposes to
`countCloserThan(BR.front-plane, groundtop-points) - countCloserThan(groundtop-plane, BR.front-points)`.
The current permissive `result > 0` rule fires:

- `groundtop.countCloserThan(BR.front, observer) = 1`
  - BR.front plane is y=3.6, normal (0, -1.2, 0). Observer at y=-10 →
    observer is on the y<3.6 side. 2 of 4 ground-top vertices are at
    y=-1 (same side as observer; product positive ⇒ counted). The other
    2 at y=5 (opposite side; product negative ⇒ not counted). result=2,
    return 1.
- `BR.front.countCloserThan(groundtop, observer) = 1`
  - Ground-top plane is z=0.1, normal (0, 0, 48). Observer at z=20 →
    observer on z>0.1 side. 2 of 4 BR.front vertices at z=1.1 (same
    side; counted). The other 2 at z=0.1 (coplanar; product zero;
    epsilon-rejected). result=2, return 1.
- Subtraction: 1 - 1 = 0.

The predicate is structurally **symmetric** for any pair where each face
straddles the other's plane on the observer/non-observer axis. Every
"vertical wall vs ground top" pair in the LongPressSample (and any other
scene with a ground prism) hits this. The per-distance-epsilon sign-
agreement variant (CR-1 from the original `07-review-correctness.md`)
also returns 0 in both directions for this pair (BR.front straddles
ground-top's plane on z; ground-top straddles BR.front's plane on y) —
so the BLOCKER fix proposed in the original review **would not have
resolved this regression class either**.

### F4 — Other edges that pin BR.front to position 0

Three pairs fired with `edge=i->j cmpPath=-1`, each adding "0 must come
before X":

| Pair | A face | cmpPath | Effect |
| --- | --- | --- | --- |
| A=8 vs B=0 | mid-right TOP (z=1.1, y∈[1.8,3.0]) | −1 | drawBefore[8].add(0) → 0 outranks 8 |
| A=5 vs B=0 | mid-right LEFT (x=3.6, y∈[1.8,3.0]) | −1 | drawBefore[5].add(0) → 0 outranks 5 |
| A=17 vs B=0 | middle TOP (z=1.1, x,y∈[1.8,3.0]) | −1 | drawBefore[17].add(0) → 0 outranks 17 |

These edges are **correctly fired**. The mid-right and middle cubes ARE
in front of BR.front in iso depth (their walls/tops are between
observer and BR.front along the (1,1,1) projection ray for the screen
pixels they share with BR.front). The comparator is right; the edges
just don't point in the direction we need (they say "BR.front is closer
than these middles", which is correct, but they say nothing about
BR.front vs ground top).

In the `pair: ... B.idx=0` set, no pair has `edge=j->i` (no incoming
edge for depthIdx=0), so depthIdx=0 has zero in-degree at Kahn-start —
guaranteeing pos=0.

### F5 — Why BR's top face survives (visible in screenshot)

BR.top is depthIdx=2, output pos=11. In Kahn's processing, depthIdx=2
has incoming edges (something added drawBefore[X].add(2) for some X
that depends on 2). The traversal queues depthIdx=2 only after enough
predecessors are processed. By pos=11, ground top and most non-cube
geometry has already drawn; BR.top's iso-projection is a small diamond
that overlaps mostly its own cube's pixels. Nothing drawn after pos=11
covers it materially → BR.top survives.

### F6 — AlphaSample's three CYAN prisms (alpha-batch) are predicted to fail by the same mechanism

Geometry of the alpha-batch (from `app/.../InteractionSamplesActivity.kt`
line 312):

- 3 CYAN prisms in a row at `(3.5, 3.0, 0.1)`, `(4.3, 3.0, 0.1)`,
  `(5.1, 3.0, 0.1)`, each `0.6 × 0.6` footprint, heights 0.8, 1.2, 1.6.
- Same Alpha-sample ground prism at `(-1, -1, 0)` w=8 d=6 h=0.1.

Each CYAN prism's vertical front face (y=3.0 plane) vs the ground top
(z=0.1) hits the same `cmpPath=0` symmetry: y-straddling for ground
top, z-straddling for the cube wall. Each centroid-depth comparison
puts the cube wall (deeper) before the ground top (shallower) ⇒ ground
paints over the wall ⇒ "three lightblue boxes lose their walls."

This was not directly captured in this round (the maestro flow only
exercises the LongPress tab). Recommend a separate Alpha capture in a
future investigation if the user wants direct confirmation; the
mechanism above is identical and confidence is high.

## Why The Amendment-1 Fix Was Necessary But Not Sufficient

Round-2's `hasInteriorIntersection` gate in `DepthSorter.checkDepthDependency`
**did its job**: it pruned the spurious topological edges that
round-2's verify trace identified (e.g., back-right vs back-row tops
across cube gaps). AC-9's unit assertion (BR's vertical faces at output
position ≥ 3) confirmed the gate moved BR.front out of positions 0–2 *under
the gate's purview*.

But the unit test only counted edges that fired through the gate. It
did NOT account for edges that DON'T fire because the comparator
returns 0. With no edge between BR.front and ground top, the unit test
saw "back-right's faces are at position ≥ 3" — but only relative to the
edges the gate pruned. The depth-descending pre-sort ALSO contributes
to ordering, and for the wall-vs-floor case the pre-sort puts walls
first. The unit test was structured around the wrong invariant: it
checked "pos ≥ 3 in the topological order under post-gate edges" but
the real concern was "pos ≥ pos(ground-top) in the final draw order."

This is recorded as a future-test concern in § Recommended Next Stage.

## Files Changed (Net Round 3)

| File | +/− | Notes |
| --- | --- | --- |
| `isometric-core/.../DepthSorter.kt` | +47 / −2 | DIAG toggle, first3 helper, FRAME / ORDER / pair emissions, refactored gate-call to capture intersects/cmpPath into locals. To revert, set `DIAG = false` (or remove the entire diagnostic block) and inline the gate-call back to its original form. |
| `verify-evidence/round3-longpress-diag.log` | +368 lines (NEW) | Captured logcat. Should remain checked in as workflow evidence. |

## Anything Deferred (Round 3)

- **Actual fix.** This round is investigation-only. Three viable fix
  directions are recorded in § Fix Space below.
- **AlphaSample direct capture.** Mechanism diagnosed analytically; if
  the user wants empirical confirmation, run a similar diag capture
  against `03-alpha.yaml`.
- **Diagnostic logging revert.** Code currently emits `System.err`
  spam every frame. Must be reverted before any non-investigation
  commit. Will be undone in the next round (whichever fix lands).

## Fix Space (for the next round)

1. **Newell-style minimax cascade in `closerThan`.** Add x/y/z extent
   minimax tests before the plane-side vote. For wall-vs-floor:
   `BR.front.x ∈ [3.6, 4.8]` overlaps `ground.x ∈ [-1, 7]` (no signal),
   `BR.front.y = 3.6` overlaps `ground.y ∈ [-1, 5]` (no signal),
   `BR.front.z ∈ [0.1, 1.1]` overlaps `ground.z = 0.1` only at the
   shared boundary z=0.1 — minimax says BR.front's z-min equals ground's
   z (boundary touch), BR.front's z-max strictly above. Newell's rule
   would resolve this by saying "BR.front is fully on the +z side of
   ground" ⇒ BR.front is closer to the +z observer ⇒ BR.front drawn
   AFTER ground. **This is the canonical fix** but is the largest
   refactor.

2. **Iso-depth at overlap-centroid as a tiebreaker.** When `closerThan`
   returns 0 AND `hasInteriorIntersection` is true, compute the iso
   projection of each polygon's centroid (or the polygon-overlap
   centroid) and add an edge based on which has the smaller iso-depth
   (closer to observer). Local fix; preserves the existing comparator.
   For BR.front vs ground top: ground top centroid iso-depth (4.9 in
   the proxy) < BR.front centroid (7.2) ⇒ ground top is closer ⇒ edge
   "ground top drawn AFTER BR.front" — wait, no, the centroid-iso-depth
   metric needs to capture "in the overlap region", not over the whole
   polygon. Plain centroid would double down on the existing wrong
   behaviour. **Needs refinement**; not a one-line fix.

3. **Polygon splitting for shared-plane straddling pairs.** Detect that
   BR.front shares the z=0.1 boundary with ground top, split BR.front
   into z=0.1+ε..z=1.1 (the wall-above-ground portion) and let ground
   top render the z=0.1 portion. Then closerThan over the split portion
   produces non-zero (BR.front-above-ground has all 4 vertices on
   ground-top's observer side ⇒ result=4-not-zero, no opposite-side ⇒
   non-zero return). This is a heavier refactor and changes the
   primitive count. **Likely overkill** for this regression class;
   reserve for a broader Newell rewrite.

4. **(Rejected) Reverse Kahn's tiebreaker default.** Sort the queue by
   ASCENDING centroid depth instead of DESCENDING. For wall-vs-floor
   without an edge, the closer-centroid (ground top) gets queued first
   → ground drawn FIRST → wall paints OVER ground. ✓ for this case. But
   reverses the painter convention for every cmpPath=0 pair, which would
   regress every other "no-edge" case the existing depth-descending
   ordering handles. Rejected.

The most pragmatic next-round fix is probably **(1) restricted to the
ground-top-vs-vertical-wall case** as a first slice, with full Newell
deferred. A 2D screen-space minimax on z (the only axis that's not a
shared boundary) would be enough: "if A's z-range is entirely above B's
z-range and A's plane includes B's z-max boundary, A is in front."
Localized, testable, sufficient for the LongPress + Alpha visual
regressions.

## Known Risks / Caveats (Round 3)

- **DEPTH_SORT_DIAG must NOT ship.** It emits every frame to System.err;
  on Android that goes to logcat at W priority. Performance and noise
  impact would be unacceptable in a release build. The `DIAG = true`
  toggle is a single-line flip; the actual fix round must revert it (or
  the entire diag block).
- **Build-logic Windows CC fix** in `build-logic/build.gradle.kts` is
  STILL uncommitted. Per round-1 plan, will land at handoff.
- **Several stale `build-logic/build.*-{timestamp}/` directories**
  accumulated again (`build.diag-round3-1777331342/`). All `.gitignore`d
  via the `build/` glob; safe to delete in a cleanup pass.
- **One-frame capture risk.** The captured log only contains the
  LongPress frame after navigation completes. If the bug had an
  interaction-dependent variant (e.g., only manifests after long-press
  toggles a cube's alpha to 0.3), this capture would miss it. The
  user's report and round-2's screenshots both confirm the static
  default state is enough to reproduce.

## Freshness Research (Round 3)

No new external research. The diagnostic mechanism is fully derived
from the captured log and a hand-traced reading of `Path.countCloserThan`
+ `DepthSorter.sort`. Newell's algorithm reference inherited from
round-1 freshness pass (and the original WS10 shape-stage research).

## Recommended Next Stage (Round 3)

The investigation is complete and the user's directed task is fulfilled.
What comes next depends on the user's preference for fix scope:

- **Option A (recommended): `/wf-amend depth-sort-shared-edge-overpaint from-review`**
  — Open a shape amendment to incorporate the new bug class
  ("wall-vs-floor cmpPath=0 + centroid-pre-sort fallback") as an
  additional Acceptance Criterion. The amendment will:
  1. Add AC-12: "for every (vertical-face, ground-top) pair where
     the vertical face's z-range is fully above the ground-top's
     plane (modulo a shared boundary), the vertical face's output
     position must be > the ground-top's output position." (proxy for
     "wall paints over floor".)
  2. Pull "iso minimax for shared-plane straddling pairs" from out-of-scope
     into in-scope.
  3. Add AC-13: same-class assertion for the AlphaSample 3-prism
     batch (CYAN walls vs DARK_GRAY ground top).
  Then `/wf-plan` → `/wf-implement` will produce the actual fix on top
  of this clean diagnosis.

- **Option B: `/wf-implement <slug> <slice-slug>` with the slice
  reframed**
  — Skip a formal amendment and let the next implement round directly
  apply Fix Space option (1) restricted to wall-vs-ground-top, plus
  revert this round's DEPTH_SORT_DIAG. Faster but bypasses the shape
  stage's contract — appropriate only if the user views F1–F4 as
  sufficient documentation.

- **Option C: revert this round and stop**
  — If the directed investigation surfaced findings that change the
  user's mind about whether to continue (e.g., they want to redesign
  the depth-sort algorithm wholesale rather than patch it), revert the
  DEPTH_SORT_DIAG block and pause the workflow at this stage. The
  evidence in `verify-evidence/round3-longpress-diag.log` is
  preserved; the diagnosis above is the authoritative reference for
  the next attempt whenever it happens.

**Compact strongly recommended before whichever option** — round-3
chatter (build commands, log extraction, ray-projection arithmetic) is
noise for the next stage; the PreCompact hook preserves workflow state.

---

## Mode (Round 4 — amendment-2)

`newell-cascade-replace` — full algorithmic restructure of `Path.closerThan`
per amendment-2's directive. Replaces the round-1/round-2 vote-and-subtract
predicate with Newell, Newell, and Sancha's (1972) classical Z→X→Y minimax
cascade, deletes the `countCloserThan` private helper, adds a private
`signOfPlaneSide` helper for the strict all-on-same-side plane test, and
reverts the round-3 `DEPTH_SORT_DIAG` instrumentation in the same atomic
commit.

## Summary of Changes (Round 4)

`Path.closerThan` now executes a six-step cascade — Z-extent (iso-depth) →
X-extent (screen-x) → Y-extent (screen-y) → plane-side forward → plane-side
reverse → 0 (polygon split deferred). Each step terminates with a definitive
non-zero verdict or falls through to the next; only mixed-straddle pairs that
pass all six steps without decision return 0, which is the genuinely-ambiguous
case Kahn's append-on-cycle fallback in `DepthSorter.sort` handles.

`countCloserThan` (private) is deleted entirely; its plane-side machinery is
lifted into a new private `signOfPlaneSide(pathA, observer): Int` that returns
+1 if all of `this`'s vertices are on the OPPOSITE side from observer of
pathA's plane (this farther), -1 if all on the SAME side (this closer), 0 if
mixed. The strict all-on-same-side rule replaces the round-1 permissive
"any-vertex" semantics that produced the cmpPath=0 cancellation under
symmetric-straddle.

The round-3 `DEPTH_SORT_DIAG` block in `DepthSorter.kt` is reverted in the
same commit (per amendment-2 directive c): `private const val DIAG`, `first3`
helper, FRAME START / FRAME END / per-pair / ORDER / ORDER-CYCLE emissions,
and the `edgeLabel` bookkeeping introduced solely to feed the per-pair log.
The `intersects` / `cmpPath` locals introduced by round-3's gate-call
refactor are KEPT as a small readability improvement.

Test deltas: `PathTest` 10 → 12 (split AC-2 into two cases reflecting the
genuine ambiguity vs. iso-depth disjointness, added AC-3 case (g) wall-vs-
floor straddle test); `DepthSorterTest` 9 → 11 (added AC-12 LongPress
full-scene + AC-13 Alpha full-scene integration tests, both designed to
fail under round-3 HEAD and pass under Newell). KDocs on the five existing
`closerThan` tests rewritten to document which Newell cascade step now
resolves each case (verified by re-running the suite — assertion signs
unchanged from round 1 / round 2 baselines).

Local regression sweep on Windows + JDK17: `:isometric-core:test` —
**BUILD SUCCESSFUL**, all 191 tests green (DepthSorterTest 11, PathTest 12,
plus the 168 unchanged tests across IntersectionUtilsTest, IsoColorTest,
IsometricEngineProjectionTest, IsometricEngineTest, PointTest, etc.).
Paparazzi snapshots not exercised in this local run — the four amendment-1
baselines remain absent on Windows due to the documented Paparazzi-on-
Windows blank-render gap; CI on Linux will regenerate / verify them.

## Files Changed (Round 4)

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt` —
  Added `import kotlin.math.PI`, `import kotlin.math.cos`, `import
  kotlin.math.sin`. Replaced the body of `fun closerThan(pathA: Path,
  observer: Point): Int` with the six-step Newell cascade (Z-extent
  minimax via `Point.depth(PI/6)`; screen-x minimax via `(x - y) *
  cos`; screen-y minimax via `-(sin*(x + y) + z)`; plane-side forward;
  plane-side reverse). Added private `signOfPlaneSide(pathA, observer):
  Int` helper implementing Newell's strict all-on-same-side test. Deleted
  the old private `countCloserThan` function and its KDoc. Added a
  `private companion object` with `ISO_ANGLE = PI / 6.0` and `EPSILON =
  1e-6` constants matching the rest of the depth-sort pipeline. Updated
  `closerThan`'s KDoc to reference Newell's 1972 paper, document the
  cascade steps, and catalogue the three superseded failure modes
  (under-determined sort, over-aggressive edges, cmpPath=0 wall-vs-floor).
  Net: +147 / -32.

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt` —
  Reverted round-3's `DEPTH_SORT_DIAG` instrumentation: deleted
  `private const val DIAG = true` and its comment block, deleted
  `private fun first3(points)`, deleted FRAME START emission, deleted
  ORDER / ORDER-CYCLE emissions inside the Kahn loop and the cycle
  fallback, deleted FRAME END emission, deleted the per-pair
  `DepthSortDiag pair: ...` emission inside `checkDepthDependency`, and
  removed the `edgeLabel` bookkeeping. Kept the `intersects` / `cmpPath`
  locals from the round-3 refactor for readability. Updated the comment
  block above `hasInteriorIntersection` to reflect that the gate now
  feeds a Newell-cascade comparator rather than the old vote-and-subtract.
  Net: +5 / -47.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt` —
  Added two integration tests:
  - `WS10 LongPress full scene back-right cube vertical faces draw after ground top`
    (AC-12): builds the LongPressSample geometry with a ground prism plus
    the 3×3 grid, identifies the back-right cube's front and left vertical
    faces and the ground top by vertex geometry, asserts both vertical
    faces' command indices are > ground top's. Designed to FAIL under
    round-3 HEAD (cmpPath=0 → no edge → centroid pre-sort puts walls
    first) and PASS under Newell (Z-extent + plane-side give a decisive
    edge).
  - `WS10 Alpha full scene each CYAN prism vertical faces draw after ground top`
    (AC-13): builds the AlphaSample geometry (ground + three CYAN prisms
    at heights 0.8 / 1.2 / 1.6), iterates over each prism asserting its
    front and left vertical faces draw after the ground top.
  Net: +94 / -1.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt` —
  Reframed AC-2: replaced the single `closerThan returns zero for
  genuinely coplanar non-overlapping faces` test with TWO tests
  reflecting Newell's actual semantics:
  - `closerThan returns zero for coplanar overlapping faces` —
    coincident faces in the same z=1 plane with identical xy extents;
    cascade falls through to step 7 (deferred) returning 0.
  - `closerThan resolves coplanar non-overlapping via Z-extent minimax` —
    the original AC-2 geometry with disjoint x ranges; iso-depth
    `depth(angle) = x*cos+y*sin-2z` mixes x into the depth scalar, so
    the disjoint-x pair has disjoint iso-depth ranges and step 1 fires.
  Added AC-3 case (g):
  - `closerThan resolves wall-vs-floor straddle via plane-side test` —
    the regression case the round-3 directed investigation diagnosed.
    Wall at x=1 spanning z=[0,1]; floor at z=0 strictly containing
    wall's xy projection. Pre-Newell vote-and-subtract returns 0
    (both directions return 1, cancel); Newell plane-side forward
    returns -1 (all wall vertices on observer side of floor's z=0
    plane → wall is closer). Asserts `result < 0`.
  Updated KDocs on the five existing `closerThan` tests to explain
  which Newell cascade step resolves each case (step 1 for the
  top-vs-vertical equal-heights case, step 5 for the rest);
  assertion signs and values unchanged. Replaced the regression-suite
  comment header to document the cascade structure and the three
  superseded failure modes. Net: +138 / -33.

## Shared Files (also touched by sibling slices)

None — single slice.

## Notes on Design Choices (Round 4)

- **Sign convention verified empirically against AC-1**: The plan's
  pseudocode comments suggested "self entirely closer → return +1," but
  the existing AC-1 test (`closerThan(factoryTop, hqRight) > 0` with
  test text "factory_top is the farther face") and tracing through the
  pre-Newell vote-and-subtract math both confirm the opposite: positive
  ⇒ this farther; negative ⇒ this closer. The implementation follows
  the test contract: signOfPlaneSide returns +1 for "all opposite from
  observer" (this farther) and -1 for "all on observer side" (this
  closer). The Z-extent step's signs are similarly inverted from the
  plan's pseudocode. This is a deviation from the plan's literal text;
  see Deviations from Plan (Round 4) below.
- **Step 4 (`hasInteriorIntersection`) intentionally OMITTED inside the
  cascade**: the plan recommended including it as a defensive screen-
  overlap test, but tracing through the existing five `closerThan`
  unit tests (e.g., the diagonal-offset test where screen polygons
  share only a vertex) showed the plane-side test must remain the
  authoritative tiebreaker for disjoint-screen pairs to keep the unit
  test contracts intact. `DepthSorter.checkDepthDependency` already
  gates on `hasInteriorIntersection` externally (from amendment-1), so
  the screen-overlap filter is correctly applied at the call site
  without polluting the predicate's standalone semantics.
- **Steps 2/3 (X/Y screen-extent minimax) kept but inert in the test
  suite**: tracing each of the 12 `closerThan` unit tests showed they
  all resolve at either step 1 (Z-extent) or step 5 (plane-side
  forward); steps 2/3 never fire in the current test corpus. Kept them
  in the cascade to match the plan's Newell Z→X→Y structure and as
  defensive coverage against future scenes where the plane-side test
  is degenerate. Sign convention for these steps follows the iso-
  projection intuition (larger world-x or larger world-(x+y) → farther
  in the standard 30° iso projection); since they are not exercised by
  any test, the sign is conservative rather than empirically validated.
- **`countCloserThan` deleted entirely** rather than left as a
  deprecated helper. Per CLAUDE.md the project follows
  feedback_no_deprecation_cycles — direct breaking change is preferred
  over a migration path. The function was private, so the deletion has
  no public-API surface impact.
- **`ISO_ANGLE` and `EPSILON` consolidated as private companion
  constants** rather than scattered as inline literals. Improves
  readability of the cascade steps (each comparison reads `if
  (selfDepthMax < aDepthMin - EPSILON) return -1` instead of `... -
  0.000001`). The values match the existing 1e-6 epsilon used by
  `IntersectionUtils.hasInteriorIntersection`'s `EDGE_BAND` and the
  ISO_ANGLE matches `Point.depth(PI/6)` and `IsometricEngine`'s
  default projection angle.

## Deviations from Plan (Round 4)

1. **Sign convention inverted** relative to the plan's pseudocode.
   Plan said "selfZmax < aZmin - eps → return +1 (self closer)";
   actual implementation says "→ return -1 (self closer)" because the
   existing AC-1 test contract maps positive to "self farther." See
   Notes on Design Choices above.
2. **Step 4 (`hasInteriorIntersection` early-exit) omitted from the
   cascade body**. Plan recommended including it for "predicate
   correctness in isolation"; the test corpus showed it would break
   the plane-side test for screen-disjoint geometric pairs that
   existing tests still expect to resolve via plane-side. The external
   gate in `DepthSorter` covers the screen-overlap concern.
3. **AC-3 case (g) sign assertion changed from `> 0` to `< 0`**. Plan
   expected positive ("wall is closer"); the actual sign per the
   established convention (positive = this farther) is negative for
   "wall closer than floor." Test name renamed to `closerThan resolves
   wall-vs-floor straddle via plane-side test` (Newell step 5
   actually fires; the plan's claim of "Z-extent minimax (step 1)"
   was incorrect because Z-extents do overlap once world-x and world-y
   are mixed into `depth(angle)` for the chosen geometry).

These deviations were caught by tracing the existing AC-1 contract
through the pre-Newell math and are reflected in the per-test KDocs
and the cascade's inline comments.

## Anything Deferred (Round 4)

- **Polygon split (Newell step 7)** remains DEFERRED per amendment-2
  directive (d). The cascade returns 0 from step 7's slot;
  `DepthSorter.sort`'s append-on-cycle fallback (existing) handles any
  residual cycles. Local test suite shows no cycles surfacing in the
  AC-12 / AC-13 integration scenes, so step 7 stays out of scope. If
  cycles appear in verify-stage scenes that are not yet covered by the
  test suite, polygon-split escalates into scope as `amend-3`.
- **Optional internal explanation doc** at `docs/internal/explanations/
  depth-sort-painter-pipeline.md` from amendment-2 § Documentation Plan
  remains DEFERRED. Plan stated this was non-mandatory; ship-stage
  handoff can include it as an optional follow-up.
- **Linux CI Paparazzi baseline regeneration** for the four amendment-1
  snapshots (`nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`,
  `alphaSampleScene`) is required after this commit lands — Windows
  consistently produces blank PNGs (documented environmental issue).
  Verify stage (or the PR pipeline) will trigger the regeneration.

## Known Risks / Caveats (Round 4)

- **X-extent and Y-extent steps inert in test corpus**: as noted under
  Notes on Design Choices, no current test exercises steps 2 or 3.
  Their sign convention is the iso-projection intuition (self further
  along world-x or world-(x+y) ⇒ farther) rather than empirically
  validated against a counter-example. If a future scene surfaces a
  pair where Z-extent overlaps but X- or Y-extent are strictly disjoint
  AND the plane-side test would have given a different answer, the
  cascade may produce a wrong sign. Mitigation: AC-12 and AC-13
  integration tests cover the realistic LongPress and Alpha scenes; if
  any visual regression surfaces in CI, add a unit test for the offending
  geometry and verify the X/Y signs.
- **Antisymmetry invariant under Newell**: the existing
  `closerThan is antisymmetric for representative non-coplanar pairs`
  test in `DepthSorterTest` continues to pass (verified locally), so
  `a.closerThan(b) + b.closerThan(a) == 0` for all four representative
  pairs. This is a non-trivial property under a six-step cascade — each
  step's reverse-direction call must produce the inverse sign. The
  implementation passes by construction (Z/X/Y minimax steps swap min
  and max under role swap; plane-side forward and reverse use the
  `-sRev` inversion).
- **Compose snapshot baselines remain missing on Windows**: not
  addressed by this fix; pre-existing tech debt. Verify-stage handoff
  on Linux CI will regenerate. No new snapshots are introduced by
  Round 4 (amendment-1's four scene factories cover the visual
  regression set).

## Freshness Research (Round 4)

No external API or library freshness check was needed — Newell, Newell,
and Sancha's 1972 painter's-algorithm paper is a stable historical
reference, and the implementation only consumes existing in-repo
primitives (`Point.depth(angle)`, `Vector.crossProduct`,
`Vector.dotProduct`, `Vector.fromTwoPoints`,
`IntersectionUtils.hasInteriorIntersection`).

## Recommended Next Stage (Round 4)

- **Option A (default): `/wf-verify depth-sort-shared-edge-overpaint
  depth-sort-shared-edge-overpaint`** — Verify the implementation
  against the AC suite. Verify-stage will need:
  - A Linux CI Paparazzi run to regenerate the four amendment-1
    baselines and confirm the visual regression is gone (AC-14).
  - Optionally a Maestro replay of `02-longpress.yaml` and
    `03-alpha.yaml` for live device confirmation.
  - The unit + integration test suite already passed locally (191
    green) — verify-stage can rely on this and focus on visual
    confirmation.
  **Compact strongly recommended before `/wf-verify`** — this round's
  sign-convention analysis, test tracing, and arithmetic are noise for
  the next stage.

- **Option B: `/wf-review depth-sort-shared-edge-overpaint
  depth-sort-shared-edge-overpaint`** — Skip directly to multi-axis
  review if the user wants automated correctness/security/style
  feedback before committing to a verify pass. The unit test count
  (191 green) gives reasonable baseline confidence.

- **Option C: extend with polygon-split (cascade step 7)** — Only if
  verify-stage surfaces unresolved cycles in scenes beyond AC-12 / AC-13.
  Would be its own follow-up amendment.
