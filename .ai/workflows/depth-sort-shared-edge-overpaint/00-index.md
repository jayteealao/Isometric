---
schema: sdlc/v1
type: index
slug: depth-sort-shared-edge-overpaint
title: "Depth-sort shared-edge overpaint: factory top paints over hq right side in WS10 NodeIdSample"
status: active
current-stage: implement
stage-number: 5
created-at: "2026-04-26T17:52:49Z"
updated-at: "2026-04-26T20:12:32Z"
selected-slice: depth-sort-shared-edge-overpaint
branch-strategy: shared
branch: "feat/ws10-interaction-props"
base-branch: "feat/ws10-interaction-props"
pr-url: ""
pr-number: 0
open-questions:
  - "Paparazzi baseline PNG for nodeIdSharedEdge() was not generated this session (Windows build-logic race). Must be recorded during /wf-verify on Linux CI or with the local --stop dance."
  - "Build-logic CC fix is still uncommitted in working tree; can ride as separate chore(build-logic) commit at handoff or stay local."
  - "11 existing Paparazzi baselines remain missing from git (pre-existing tech debt). Track as future workflow."
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
next-command: wf-verify
next-invocation: "/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
workflow-files:
  - 00-index.md
  - 01-intake.md
  - 02-shape.md
  - 04-plan.md
  - 04-plan-depth-sort-shared-edge-overpaint.md
  - 05-implement.md
  - 05-implement-depth-sort-shared-edge-overpaint.md
  - po-answers.md
progress:
  intake: complete
  shape: complete
  plan: complete
  slice: not-started
  implement: complete
  verify: not-started
  review: not-started
  handoff: not-started
  ship: not-started
  retro: not-started
---
