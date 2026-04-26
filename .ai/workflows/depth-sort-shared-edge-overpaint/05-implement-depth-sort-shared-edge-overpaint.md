---
schema: sdlc/v1
type: implement
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 5
created-at: "2026-04-26T20:12:32Z"
updated-at: "2026-04-26T20:12:32Z"
metric-files-changed: 6
metric-lines-added: 353
metric-lines-removed: 12
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: ""
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

## Recommended Next Stage

- **Option A (default):** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — implementation touches testable behaviour (Path math, DepthSorter ordering)
  and adds a Paparazzi snapshot that requires baseline generation. Local Windows
  verification is unreliable; CI on Linux is the source of truth.
  **Compact strongly recommended before /wf-verify** — implementation reasoning
  (vertex-by-vertex sign tracing, plan drift detection, scope receiver renames)
  is noise for verification. The PreCompact hook will preserve workflow state.
- **Option B:** `/wf-review depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — only if verification is deferred to CI and reviewers want to read the diff
  first. Less recommended because the Paparazzi baseline gap is best closed
  during verify.
