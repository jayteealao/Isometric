---
schema: sdlc/v1
type: implement-index
slug: depth-sort-shared-edge-overpaint
status: in-progress
stage-number: 5
created-at: "2026-04-26T20:12:32Z"
updated-at: "2026-04-27T23:23:23Z"
slices-implemented: 1
slices-total: 1
rounds: 3
round-3-mode: directed-investigation
metric-total-files-changed: 15
metric-total-lines-added: 866
metric-total-lines-removed: 21
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
next-command: wf-amend
next-invocation: "/wf-amend depth-sort-shared-edge-overpaint from-review"
---

# Implement Index

This is a single-slice plan; the index lists exactly one implementation record. The slice has been worked in three rounds:

| Slice | Status | Round | Commit | Files | +/- | Record |
|---|---|---|---|---|---|---|
| `depth-sort-shared-edge-overpaint` | complete | 1 (original) | `3e811aa` | 6 | +353 / -12 | [05-implement-depth-sort-shared-edge-overpaint.md](05-implement-depth-sort-shared-edge-overpaint.md) |
| `depth-sort-shared-edge-overpaint` | complete | 2 (amendment-1) | `9cef055` | 8 | +466 / -7 | (same file, Round 2 section) |
| `depth-sort-shared-edge-overpaint` | diagnostic-only | 3 (directed investigation) | `7b8649d` | 1 source + 1 evidence | +47 / -2 (source) + 368 (evidence) | (same file, Round 3 section) |

## Cross-Slice Integration Notes

None — single slice. Round 2 applies entirely on top of Round 1's commit; no rebase or rebasing-conflict concerns.

## Round 3 Highlights (directed investigation)

- Re-added `DEPTH_SORT_DIAG` System.err logging to `DepthSorter.kt` (FRAME START / pair / ORDER / FRAME END events). Single behavioural-difference: the per-pair log now also captures `intersects` and `cmpPath` so we can distinguish "no edge because gate rejected" from "no edge because comparator returned 0".
- Built and installed app on emulator-5554 with the documented Windows toolchain workaround stack. Maestro flow `02-longpress.yaml` ran successfully; `adb logcat` captured 368 lines (2 frames) into `verify-evidence/round3-longpress-diag.log`.
- **Identified the over-painter:** the GROUND TOP face (depthIdx=3, drawn at output pos=2) paints over the back-right cube's FRONT face (depthIdx=0, pos=0) and LEFT face (depthIdx=1, pos=1). The pair `intersects=true edge=none cmpPath=0` — the screen-overlap gate is firing CORRECTLY, but `closerThan` returns 0 because the predicate is symmetric for "vertical wall vs ground top" pairs (each face has some vertices on the observer side of the other's plane). Without an edge, Kahn's algorithm falls back to centroid-descending order, which puts walls first and ground later → ground paints over walls.
- **Same regression class predicted for AlphaSample's three CYAN prisms** (height 0.8 / 1.2 / 1.6 in a row at y=3.0): each prism's wall vs ground top hits the same cmpPath=0 fallback. Direct capture deferred (mechanism is identical and confidence is high).
- The amendment-1 fix (`hasInteriorIntersection`) is **necessary but not sufficient**: AC-9's unit assertion ("BR's verticals at output pos ≥ 3") was structured around the wrong invariant — it counted only edges that fired through the gate, not the depth-descending pre-sort fallback for cmpPath=0 pairs.
- **No fix applied this round.** Three fix directions recorded in `05-implement-depth-sort-shared-edge-overpaint.md` § Fix Space. The most-likely path is a Newell-style minimax cascade restricted to the wall-vs-ground-top case, with full Newell deferred.

## Round 2 Highlights

- Added `IntersectionUtils.hasInteriorIntersection` — strict screen-overlap gate that rejects boundary-only contact between iso-projected polygons.
- Wired the new gate in `DepthSorter.checkDepthDependency` (single-line behavioural change at the gate; algorithm in `Path.kt` UNCHANGED).
- AC-9 integration test: 3×3 grid back-right corner cube vertical faces are not at output positions 0–2.
- AC-10 unit tests (5 cases): boundary-only / interior-overlap / disjoint / containment behaviour for `hasInteriorIntersection`, plus a regression-marker that `hasIntersection` keeps its lenient contract.
- AC-11 Paparazzi snapshots (4): replaced the single `nodeIdSharedEdge` baseline with `nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`, `alphaSampleScene` — covering both bug classes (row layout + 3×3 grid + mixed geometry).
- 3 new scene factories (`OnClickRowScene`, `LongPressGridScene`, `AlphaSampleScene`) under `isometric-compose/src/test/.../scenes/`.

## Recommended Next Stage

- **Option A (recommended):** `/wf-amend depth-sort-shared-edge-overpaint from-review` — incorporate the round-3 diagnosis (cmpPath=0 + centroid-descending fallback for wall-vs-ground-top) as a new Acceptance Criterion + pull "iso minimax for shared-plane straddling pairs" from the original out-of-scope list into in-scope. Then `/wf-plan` → `/wf-implement` produces the actual fix.
- **Option B:** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — directly apply Fix Space option (1) restricted to wall-vs-ground-top + revert the DEPTH_SORT_DIAG block. Faster but bypasses the shape stage.
- **Option C:** revert the diag block and pause the workflow if the user wants to redesign the depth-sort algorithm wholesale before continuing. The captured log + Round 3 diagnosis remain as the authoritative reference for the next attempt.

**Compact strongly recommended before whichever option** — round-3 chatter (build commands, log extraction, ray-projection math) is noise for the next stage.
