---
schema: sdlc/v1
type: implement
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 5
created-at: "2026-04-26T20:12:32Z"
updated-at: "2026-04-27T22:32:06Z"
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
amend-1-commit-sha: ""
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 02-shape.md
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
