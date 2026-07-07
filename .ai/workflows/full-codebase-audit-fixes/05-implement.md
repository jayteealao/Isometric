---
schema: sdlc/v1
type: implement-index
slug: full-codebase-audit-fixes
status: in-progress
stage-number: 5
created-at: "2026-07-07T13:45:25Z"
updated-at: "2026-07-07T13:45:25Z"
slices-implemented: 1
slices-total: 7
metric-total-files-changed: 10
metric-total-lines-added: 433
metric-total-lines-removed: 45
tags: []
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes core-math"
---

# Implement Index

## Cross-Slice Integration Notes

- `core-math` slice (1/7) is complete. All nine audit findings (A1a, A1b, A2, A3, A4, A5,
  A7, F1, F2) are fixed with regression tests. The rotation matrices, the edge-only helper
  extraction, the lightness clamp, and the hashCode are all committed.
- Remaining slices (`gesture-coordination`, `compose-contracts`, `view-module`,
  `shape-geometry`, `docs-and-changelog`, `snapshot-sweep-gate`) have no dependency on
  `core-math` being verify-complete before they start — they depend on the underlying math
  being correct, which is now true.
- `shape-geometry` (B1/B2/B3) verifies its Octahedron geometry against `Point.rotateX/Y`
  — the rotation flip fix from this slice is load-bearing for that slice.
- `docs-and-changelog` (E-zone) will mirror the KDoc changes from this slice; no
  code change is needed in that slice for the changes already made here.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes core-math` — run the JVM
  test suite against the committed changes. 232 tests pass; 1 pre-existing unrelated
  failure in `IsometricEngineProjectionTest` is noted and excluded from this slice's gate.
- **Option B:** Begin `gesture-coordination` or `compose-contracts` in parallel while
  verify runs on `core-math` — those slices have no dependency on this one being
  verify-complete.
