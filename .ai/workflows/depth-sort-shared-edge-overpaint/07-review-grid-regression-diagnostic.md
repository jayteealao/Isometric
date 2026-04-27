---
schema: sdlc/v1
type: diagnostic-findings
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
created-at: "2026-04-27T22:16:08Z"
updated-at: "2026-04-27T22:16:08Z"
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - 3x3-grid
  - regression
  - latent-since-3e811aa
refs:
  index: 00-index.md
  review-master: 07-review.md
  triggering-review: 07-review-correctness.md
evidence:
  - verify-evidence/maestro-longpress-after-press.png
  - verify-evidence/maestro-longpress-diag.log
  - verify-evidence/diag-three-samples.log
  - verify-evidence/current-state.png
---

# Diagnostic Findings — 3×3 Grid Regression in `3e811aa`

## Summary

The permissive `result > 0` threshold landed in commit `3e811aa` resolves the
WS10 NodeIdSample case (4 buildings in a row) cleanly, but **introduces a
latent regression visible in any 3×3 grid layout with adjacent prisms**. The
regression manifests as one or more corner cubes losing their vertical side
faces (top face only renders, sides are painted over). Reproduces in the
default static render of `LongPressSample` and `AlphaSample` — no user
interaction required. Does not reproduce in `OnClickSample` (5 cubes in a
row).

## Failure Mode

In the LongPress sample, the back-right cube of the 3×3 grid (shape index
i=8 at world position `(3.6, 3.6, 0.1)` with dimensions 1.2×1.2×1.0) has
its **front face (y=3.6)** and **left face (x=3.6)** pushed to output
positions 0 and 1 in the topologically-sorted draw order. Because they are
drawn first, all 28 subsequent face renders have the opportunity to paint
over them in any region where the iso-projected screen polygons overlap.
The result: only the cube's top face (drawn at output position ~11) remains
visible, producing a "yellow trapezoid floating without walls" appearance.

## Mechanism

The permissive predicate fires "this is closer" votes that don't reflect
actual screen-space occlusion. Concrete trace from `verify-evidence/maestro-longpress-diag.log`:

For the pair *back-right front face* (idx=0) vs *mid-right top face* (idx=8):
- `idx=0.countCloserThan(idx=8)`: idx=0 has 4 vertices at z∈{0.1, 1.1};
  idx=8's plane is z=1.1. Two vertices at z=0.1 are below the plane
  (opposite to observer at z>1.1). Two vertices at z=1.1 are coplanar.
  `result = 0` → returns 0.
- `idx=8.countCloserThan(idx=0)`: idx=8's 4 vertices all have y∈{1.8, 3.0};
  idx=0's plane is y=3.6. All 4 vertices are at y < 3.6, on the same side
  as the observer (also at y < 3.6). `result = 4` → returns 1.
- `closerThan(idx=8, idx=0) = 0 - 1 = -1`. DepthSorter writes
  `drawBefore[8].add(0)` → idx=0 must come before idx=8.

This edge **fires regardless of whether idx=0 and idx=8's iso-projected
screen polygons actually overlap**. In the 3×3 grid, the back-right cube's
front face accumulates such "must come before X" edges from many neighbour
top faces, neighbour left faces, and middle-row faces — each contributing
its asymmetric "all 4 vertices on observer-side of back-right-front's
plane" winning vote.

Topological sort respects all edges. With ~6+ "0 before X" edges, the
back-right front face gets placed at output position 0 — drawn first.
Subsequent faces (including the floor's top face, which extends across the
whole scene's screen footprint) paint over back-right's vertical faces
wherever projections overlap.

The geometric ground truth: most of these edges are unnecessary. The
back-right front face at y=3.6 is geometrically separated from mid-right's
top face at y∈[1.8, 3.0] by a gap of 0.6 units — they don't visually
overlap in iso projection (back-right front is up-and-right of mid-right
top in screen space). The algorithm, however, doesn't check actual 2D
overlap before adding the topological edge.

## Why The Original Tests Missed This

The plan's verification test set (PathTest closerThan unit cases,
DepthSorterTest WS10 four-building integration) all use **row layouts**
(buildings or prisms aligned on one axis). Row layouts are asymmetric in
the relevant geometric sense: each pair of adjacent faces has one direction
where 0 vertices are observer-side and the other direction where some
vertices are observer-side, producing a clean signed comparator and a
single, geometrically-meaningful edge. The 3×3 grid layout is fundamentally
different — every cube has neighbours along both axes plus diagonals,
producing a dense graph of asymmetric votes. None of the test scenes
exercised this configuration.

## Why This Was NOT Caught By the Earlier Review-Fix Attempts

Today's review-mode work attempted three changes:
- **CR-1 no-straddling rule**: `result > 0 AND resultOpposite == 0`. Broke
  the WS10 case (factory_top has 0 same-side vertices in hq_right's plane,
  hq_right has 2 same-side and 2 opposite-side in factory_top's plane —
  no-straddling rule returns 0 in both directions, restoring the original
  bug). Reverted same session.
- **RL-1 per-distance epsilon**: refactor of the threshold form. Did not
  change the algorithmic behaviour for this regression — the back-right
  cube's vertices are far from any neighbour's plane (distance 1.0+),
  well above any reasonable epsilon. The mechanism is the algorithmic
  result, not the epsilon.
- **MA-2 Boolean rename**: structural improvement. Algorithmically
  equivalent.

None of these proposals from the correctness/reliability/refactor-safety
reviewers identified the actual root cause because the reviewers traced
through the `OnClickSample` (row layout) and `LongPressSample` (3×3 grid)
without realising that the regression visible on LongPress was static, not
interaction-driven.

## Out-of-Scope Items From Original Shape That Are Now Relevant

The original `02-shape.md` § Out of Scope listed several deferred items.
Two of them are now directly load-bearing for a proper fix:

1. **AABB minimax pre-filter** in `IntersectionUtils.hasIntersection`
   (LeBron IsometricBlocks-style). Would reject non-overlapping prism face
   pairs at the broad-phase, preventing them from reaching `closerThan`
   at all. Most direct fix for the over-aggressive edge issue.
2. **Full Newell Z→X→Y minimax cascade** in `closerThan`. Stricter
   plane-side test using sign agreement on multiple axis projections.
   Larger algorithmic restructure.

A third option not in the original scope:

3. **Strict 2D screen-overlap check in `DepthSorter.checkDepthDependency`**
   before adding the topological edge. Currently the gate is just
   `IntersectionUtils.hasIntersection` on flat (x, y, 0) projections; this
   may not match the actual rendered iso-projected polygon overlap. Adding
   a stricter check (e.g., interior overlap via SAT or polygon intersection
   area > epsilon) would prevent the over-aggressive edge from firing.

## Recommended Next Step

Run `/wf-amend depth-sort-shared-edge-overpaint from-review` with this
diagnostic file as the trigger context. The amend should:

- Update `02-shape.md` to acknowledge the 3×3 grid case as an in-scope
  regression class, not just the row-layout WS10 case.
- Pull at least one of the three deferred items above back into scope.
- Add `LongPressSample` and `AlphaSample` static-state Paparazzi snapshots
  to the planned verification test set.
- Add an integration test asserting that no 3×3 grid corner cube has its
  vertical faces drawn at output position 0–2 (proxy for the bug).

## Working-Tree State After Revert

- `isometric-core/.../Path.kt`: restored to commit `3e811aa` state
  (`countCloserThan` returns `Int`, permissive `result > 0`, product
  epsilon `>= 0.000001`).
- `isometric-core/.../DepthSorter.kt`: restored to commit `3e811aa` state
  (no `DEPTH_SORT_DIAG`, no temp logging).
- `build-logic/build.gradle.kts`: still contains the uncommitted Windows
  CC workaround. Unchanged from prior days. Will land as separate
  `chore(build-logic):` commit at handoff per the original plan.
- All 07-review-*.md sub-reviews preserved as historical record.
- Several `build-logic/build.*-{timestamp}/` directories present from
  Windows toolchain workarounds — safe to delete; covered by `.gitignore`
  via `build/` glob.
