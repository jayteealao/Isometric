---
schema: sdlc/v1
type: index
slug: depth-sort-shared-edge-overpaint
title: "Depth-sort shared-edge overpaint: factory top paints over hq right side in WS10 NodeIdSample"
status: active
current-stage: implement
stage-number: 5
created-at: "2026-04-26T17:52:49Z"
updated-at: "2026-04-27T23:23:23Z"
selected-slice: depth-sort-shared-edge-overpaint
branch-strategy: shared
branch: "feat/ws10-interaction-props"
base-branch: "feat/ws10-interaction-props"
pr-url: ""
pr-number: 0
open-questions:
  - "Round 3 directed investigation identified the over-painter as the GROUND TOP face (depthIdx=3, output pos=2) painting over back-right cube's vertical walls (pos=0/1). Mechanism: closerThan returns 0 for wall-vs-floor pairs (predicate symmetric); no edge added; Kahn falls back to depth-descending centroid which puts walls first → ground over walls. Fix space recorded; not applied this round."
  - "Round 3 source change is DIAGNOSTIC ONLY (DEPTH_SORT_DIAG re-enabled). MUST be reverted before any non-investigation commit. The next round's fix should land alongside the revert."
  - "AlphaSample's three CYAN prisms (alpha-batch) are predicted to fail by the same mechanism but not directly captured in this round. Run 03-alpha.yaml diag capture in a future round if direct empirical confirmation is wanted."
  - "Paparazzi rendered all 16 IsometricCanvasSnapshotTest baselines as identical 6917-byte blank PNGs on Windows + JDK17 in earlier rounds. Pre-existing tests (pyramid, cylinder, etc.) also blank — environmental, not slice-related. AC-11 visual confirmation must come from Linux CI; do NOT commit the locally-generated blank PNGs."
  - "Build-logic CC fix is still uncommitted in working tree; can ride as separate chore(build-logic) commit at handoff or stay local."
  - "11 existing Paparazzi baselines remain missing from git (pre-existing tech debt). Track as future workflow."
  - "Several build-logic/build.* directories were created applying the documented Windows IC-cache-corruption workaround. Safe to delete; ignored by .gitignore via build/ glob."
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
next-command: wf-amend
next-invocation: "/wf-amend depth-sort-shared-edge-overpaint from-review"
verify-round-2-result: partial
verify-round-2-blocker: "AC-11 NOT MET: visual regression on LongPress sample (back-right cube renders only top face) persists despite passing AC-9/AC-10 unit tests. Screen-overlap gate is necessary but not sufficient."
review-verdict: dont-ship
implement-attempt-status: round-3-directed-investigation-complete
implement-round-3-mode: directed-investigation
implement-round-3-fix-applied: false
implement-round-3-diagnostic-overpainter: "GROUND TOP face (depthIdx=3, output pos=2) paints over back-right cube's FRONT face (pos=0) and LEFT face (pos=1). Mechanism: closerThan returns 0 for wall-vs-floor pairs (symmetric predicate), no edge added, Kahn falls back to depth-descending centroid order which puts walls first."
diagnostic-finding: "Round 1 diagnostic (07-review-grid-regression-diagnostic.md): permissive result>0 threshold over-adds topological edges in 3x3 grid layouts. Round 3 diagnostic (05-implement-...md § Round 3): the over-painter is the GROUND TOP, not a neighbour-cube face. closerThan symmetric returns 0 for any (vertical-face, ground-top) pair where each face straddles the other's plane on its respective observer-axis. The amendment-1 hasInteriorIntersection gate is structurally correct but the failure is downstream at the Kahn-tiebreaker / centroid-pre-sort fallback. The CR-1 no-straddling rule from the original review would not fix this regression class either."
amendments:
  - 02-shape-amend-1.md
workflow-files:
  - 00-index.md
  - 01-intake.md
  - 02-shape.md
  - 04-plan.md
  - 04-plan-depth-sort-shared-edge-overpaint.md
  - 05-implement.md
  - 05-implement-depth-sort-shared-edge-overpaint.md
  - 06-verify.md
  - 06-verify-depth-sort-shared-edge-overpaint.md
  - 07-review.md
  - 07-review-correctness.md
  - 07-review-security.md
  - 07-review-code-simplification.md
  - 07-review-testing.md
  - 07-review-maintainability.md
  - 07-review-reliability.md
  - 07-review-performance.md
  - 07-review-refactor-safety.md
  - 07-review-docs.md
  - 07-review-style-consistency.md
  - 07-review-grid-regression-diagnostic.md
  - 02-shape-amend-1.md
  - maestro/01-onclick.yaml
  - maestro/02-longpress.yaml
  - maestro/03-alpha.yaml
  - verify-evidence/round3-longpress-diag.log
  - po-answers.md
progress:
  intake: complete
  shape: complete
  plan: complete
  slice: not-started
  implement: round-3-directed-investigation-complete
  verify: partial
  review: complete-dont-ship
  handoff: not-started
  ship: not-started
  retro: not-started
---
