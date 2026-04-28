---
schema: sdlc/v1
type: index
slug: depth-sort-shared-edge-overpaint
title: "Depth-sort shared-edge overpaint: factory top paints over hq right side in WS10 NodeIdSample"
status: active
current-stage: implement
stage-number: 5
created-at: "2026-04-26T17:52:49Z"
updated-at: "2026-04-28T13:25:18Z"
selected-slice: depth-sort-shared-edge-overpaint
branch-strategy: shared
branch: "feat/ws10-interaction-props"
base-branch: "feat/ws10-interaction-props"
pr-url: ""
pr-number: 0
open-questions:
  - "Round 3 directed investigation identified the over-painter as the GROUND TOP face (depthIdx=3, output pos=2) painting over back-right cube's vertical walls (pos=0/1). Mechanism: closerThan returns 0 for wall-vs-floor pairs (predicate symmetric); no edge added; Kahn falls back to depth-descending centroid which puts walls first → ground over walls. Amendment-2 (2026-04-27) responds by replacing closerThan in full with Newell's Z→X→Y minimax cascade — algorithmic restructure, not a localized patch."
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
next-command: wf-verify
next-invocation: "/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
verify-round-2-result: partial
verify-round-2-blocker: "AC-11 NOT MET: visual regression on LongPress sample (back-right cube renders only top face) persists despite passing AC-9/AC-10 unit tests. Screen-overlap gate is necessary but not sufficient."
verify-round-3-result: pass
verify-round-3-mode: "maestro-visual-postnewell"
verify-round-3-against-commit: "452b1fc"
verify-round-3-acs-met: "17/17"
verify-round-3-evidence:
  - "verify-evidence/maestro-longpress-before.png"
  - "verify-evidence/maestro-longpress-after-press.png"
  - "verify-evidence/maestro-alpha-default.png"
  - "verify-evidence/maestro-onclick-before.png"
  - "verify-evidence/maestro-onclick-after-tap.png"
  - "verify-evidence/maestro-nodeid-default.png"
review-verdict: ship-with-caveats
review-round-2-verdict: ship-with-caveats
review-round-2-against-commit: "452b1fc"
review-round-2-findings-total: 25
review-round-2-findings-blocker: 0
review-round-2-findings-high: 6
review-round-2-findings-med: 11
review-round-2-findings-low: 8
review-round-2-fix-count: 16
review-round-2-defer-count: 1
review-round-2-deferred:
  - "F-1: Paparazzi baseline regeneration on Linux CI (not a code defect)"
review-round-2-fix-list:
  - "F-2: EPSILON applied to product, not per-distance (signOfPlaneSide)"
  - "F-3: NaN/Infinity propagation; missing isFinite guards"
  - "F-4: Hot-loop perf — hoist cos/sin, split step 2/3 loop, inline signOfPlaneSide"
  - "F-5: Cascade step-numbering mismatch across KDoc/inline/PathTest header"
  - "F-6: Public concept docs describe wrong gate (hasIntersection vs hasInteriorIntersection)"
  - "F-7: Cascade steps 2/3 dead under gate — delete or test+validate signs"
  - "F-8: Epsilon notation drift (0.000001 / 1e-6 / unnamed 0.000000001)"
  - "F-9: signOfPlaneSide name+KDoc inversion vs return semantics"
  - "F-10: Workflow vocabulary leakage in source/public test API (slug, WS10, amendment)"
  - "F-11: AABB+SAT body copy-pasted between hasIntersection and hasInteriorIntersection"
  - "F-12: Pre-existing 2×N Point wrapper allocations in hasInteriorIntersection"
  - "F-13: AlphaSampleScene divergence from live Batch composable"
  - "F-14: ISO_ANGLE hardcoded PI/6 independent of IsometricEngine instance"
  - "F-15: Four vertex-scan loops in closerThan could collapse to two"
  - "F-16: AC-10 only at predicate level, not end-to-end through DepthSorter"
  - "F-17: Coplanar non-overlap test asserts != 0 instead of specific sign"
implement-attempt-status: round-4-newell-cascade-complete
implement-round-3-mode: directed-investigation
implement-round-3-fix-applied: false
implement-round-3-diagnostic-overpainter: "GROUND TOP face (depthIdx=3, output pos=2) paints over back-right cube's FRONT face (pos=0) and LEFT face (pos=1). Mechanism: closerThan returns 0 for wall-vs-floor pairs (symmetric predicate), no edge added, Kahn falls back to depth-descending centroid order which puts walls first."
implement-round-4-mode: newell-cascade-replace
implement-round-4-fix-applied: true
implement-round-4-test-suite-result: "all green: 191 isometric-core tests pass locally"
implement-round-4-test-deltas:
  - "PathTest: 10 -> 12 (split AC-2 + added AC-3 case g)"
  - "DepthSorterTest: 9 -> 11 (added AC-12 LongPress + AC-13 Alpha integration tests)"
diagnostic-finding: "Round 1 diagnostic (07-review-grid-regression-diagnostic.md): permissive result>0 threshold over-adds topological edges in 3x3 grid layouts. Round 3 diagnostic (05-implement-...md § Round 3): the over-painter is the GROUND TOP, not a neighbour-cube face. closerThan symmetric returns 0 for any (vertical-face, ground-top) pair where each face straddles the other's plane on its respective observer-axis. The amendment-1 hasInteriorIntersection gate is structurally correct but the failure is downstream at the Kahn-tiebreaker / centroid-pre-sort fallback. The CR-1 no-straddling rule from the original review would not fix this regression class either."
amendments:
  - 02-shape-amend-1.md
  - 02-shape-amend-2.md
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
  - 02-shape-amend-2.md
  - maestro/01-onclick.yaml
  - maestro/02-longpress.yaml
  - maestro/03-alpha.yaml
  - verify-evidence/round3-longpress-diag.log
  - po-answers.md
progress:
  intake: complete
  shape: complete
  plan: complete-revision-2-amendment-2-applied
  slice: not-started
  implement: round-5-review-fixes-complete
  verify: round-3-pass-maestro-visual-against-452b1fc
  review: complete-ship-with-caveats-round-2
  handoff: not-started
  ship: not-started
  retro: not-started
plan-revision-2:
  applied-amendment: 2
  cascade-entry-point: in-place-replace-closerThan
  diag-revert-bundling: same-commit-as-newell
  polygon-split-status: deferred-unless-cycles-observed
  test-reframings:
    - "AC-2 split into coplanar-overlapping (returns 0 via deferred step 7) and coplanar-non-overlapping (returns non-zero via X-extent step 2)"
    - "AC-3 case (g) added: wall-vs-floor straddle test, expects non-zero from Z-extent step"
    - "AC-12 added: LongPress full-scene + ground top ordering integration test"
    - "AC-13 added: Alpha full-scene + ground top ordering integration test"
    - "Five existing closerThan PathTest cases: KDoc-only updates documenting which Newell step resolves each case"
---
