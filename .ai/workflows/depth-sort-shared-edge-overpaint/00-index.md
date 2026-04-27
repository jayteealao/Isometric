---
schema: sdlc/v1
type: index
slug: depth-sort-shared-edge-overpaint
title: "Depth-sort shared-edge overpaint: factory top paints over hq right side in WS10 NodeIdSample"
status: active
current-stage: review
stage-number: 7
created-at: "2026-04-26T17:52:49Z"
updated-at: "2026-04-27T22:32:06Z"
selected-slice: depth-sort-shared-edge-overpaint
branch-strategy: shared
branch: "feat/ws10-interaction-props"
base-branch: "feat/ws10-interaction-props"
pr-url: ""
pr-number: 0
open-questions:
  - "Paparazzi rendered all 16 IsometricCanvasSnapshotTest baselines as identical 6917-byte blank PNGs on Windows + JDK17 in this session. Pre-existing tests (pyramid, cylinder, etc.) also blank — environmental, not slice-related. AC-5 visual confirmation must come from Linux CI; do NOT commit the locally-generated blank PNGs."
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
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
review-verdict: dont-ship
implement-attempt-status: reverted-source-restored-to-3e811aa
diagnostic-finding: "Permissive result>0 threshold over-adds topological edges in 3x3 grid layouts; latent regression in 3e811aa visible on LongPress + Alpha samples (corner cube loses vertical faces). Row layouts unaffected. See 07-review-grid-regression-diagnostic.md."
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
  - po-answers.md
progress:
  intake: complete
  shape: complete
  plan: complete
  slice: not-started
  implement: complete
  verify: partial
  review: complete-dont-ship
  handoff: not-started
  ship: not-started
  retro: not-started
---
