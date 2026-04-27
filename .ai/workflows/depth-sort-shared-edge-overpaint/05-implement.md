---
schema: sdlc/v1
type: implement-index
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 5
created-at: "2026-04-26T20:12:32Z"
updated-at: "2026-04-27T22:32:06Z"
slices-implemented: 1
slices-total: 1
rounds: 2
metric-total-files-changed: 14
metric-total-lines-added: 819
metric-total-lines-removed: 19
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Implement Index

This is a single-slice plan; the index lists exactly one implementation record. The slice was implemented in two rounds:

| Slice | Status | Round | Commit | Files | +/- | Record |
|---|---|---|---|---|---|---|
| `depth-sort-shared-edge-overpaint` | complete | 1 (original) | `3e811aa` | 6 | +353 / -12 | [05-implement-depth-sort-shared-edge-overpaint.md](05-implement-depth-sort-shared-edge-overpaint.md) |
| `depth-sort-shared-edge-overpaint` | complete | 2 (amendment-1) | *(pending commit)* | 8 | +466 / -7 | (same file, Round 2 section) |

## Cross-Slice Integration Notes

None — single slice. Round 2 applies entirely on top of Round 1's commit; no rebase or rebasing-conflict concerns.

## Round 2 Highlights

- Added `IntersectionUtils.hasInteriorIntersection` — strict screen-overlap gate that rejects boundary-only contact between iso-projected polygons.
- Wired the new gate in `DepthSorter.checkDepthDependency` (single-line behavioural change at the gate; algorithm in `Path.kt` UNCHANGED).
- AC-9 integration test: 3×3 grid back-right corner cube vertical faces are not at output positions 0–2.
- AC-10 unit tests (5 cases): boundary-only / interior-overlap / disjoint / containment behaviour for `hasInteriorIntersection`, plus a regression-marker that `hasIntersection` keeps its lenient contract.
- AC-11 Paparazzi snapshots (4): replaced the single `nodeIdSharedEdge` baseline with `nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`, `alphaSampleScene` — covering both bug classes (row layout + 3×3 grid + mixed geometry).
- 3 new scene factories (`OnClickRowScene`, `LongPressGridScene`, `AlphaSampleScene`) under `isometric-compose/src/test/.../scenes/`.

## Recommended Next Stage

- **Option A (default):** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — Round 2 adds testable behaviour (`hasInteriorIntersection`, the gate change, AC-9 + AC-10 tests) and a Paparazzi baseline gap that Linux CI must close. Compact strongly recommended before invoking.
