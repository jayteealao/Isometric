---
schema: sdlc/v1
type: verify-index
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 6
created-at: "2026-04-26T20:32:14Z"
updated-at: "2026-04-27T22:58:16Z"
slices-verified: 1
slices-total: 1
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
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Verify Index

Single-slice workflow, two verification rounds (matching the two implement rounds).

- [`06-verify-depth-sort-shared-edge-overpaint.md`](06-verify-depth-sort-shared-edge-overpaint.md) —
  - **Round 1** (against commit `3e811aa`): result **partial**. 6/8 ACs met,
    1 partial (AC-6 snapshot-side), 1 unverified (AC-5 — Paparazzi blank-render
    environment issue). Core/unit/integration verification fully clean
    (183/183 tests green); visual snapshot confirmation deferred to Linux CI.
    No fix issues found at the time.
  - **Round 2** (against commit `9cef055`): result **partial**. 8/10 ACs met
    (AC-5 superseded by AC-11 per amend-1; AC-9, AC-10 NEW and met; AC-1..8
    still pass), 1 NOT MET (AC-11 — visual regression on LongPress sample
    PERSISTS despite the screen-overlap gate). 189/189 tests green; canary
    `coplanar tile grid` survived. Algorithm is structurally correct but
    insufficient — back-right cube of 3×3 grid still painted over by an
    unidentified face. Needs a fresh diagnostic.

## Recommended Next Stage

- **Option A (default):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — directed investigation: re-add `DEPTH_SORT_DIAG` logging to the post-fix
  code, capture LongPress emulator logcat, identify the specific face still
  over-painting back-right's vertical faces. The amendment-1 gate is
  necessary but not sufficient.

- **Option B:** `/wf-amend depth-sort-shared-edge-overpaint from-review`
  — only if Option A's diagnostic reveals the screen-overlap gate approach
  is fundamentally insufficient and a deeper algorithmic redesign (AABB
  minimax, Newell cascade) is required.

- **Option C:** `/wf-review depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — would route to Don't-Ship verdict because AC-11 remains NOT MET. Only
  useful to validate the algorithmic correctness of the gate in isolation;
  doesn't move the workflow forward.

- **Option D:** `/wf-handoff depth-sort-shared-edge-overpaint`
  — **NOT VIABLE**. AC-11 NOT MET means the user-visible regression
  remains.
