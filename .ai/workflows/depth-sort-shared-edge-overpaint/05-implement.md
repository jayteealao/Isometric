---
schema: sdlc/v1
type: implement-index
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 5
created-at: "2026-04-26T20:12:32Z"
updated-at: "2026-04-28T09:01:50Z"
slices-implemented: 1
slices-total: 1
rounds: 4
round-3-mode: directed-investigation
round-4-mode: newell-cascade-replace
metric-total-files-changed: 19
metric-total-lines-added: 1358
metric-total-lines-removed: 134
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
  - newell-cascade
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Implement Index

This is a single-slice plan; the index lists exactly one implementation record. The slice has been worked in four rounds:

| Slice | Status | Round | Commit | Files | +/- | Record |
|---|---|---|---|---|---|---|
| `depth-sort-shared-edge-overpaint` | complete | 1 (original) | `3e811aa` | 6 | +353 / -12 | [05-implement-depth-sort-shared-edge-overpaint.md](05-implement-depth-sort-shared-edge-overpaint.md) |
| `depth-sort-shared-edge-overpaint` | complete | 2 (amendment-1) | `9cef055` | 8 | +466 / -7 | (same file, Round 2 section) |
| `depth-sort-shared-edge-overpaint` | diagnostic-only | 3 (directed investigation) | `2e29dc5` | 1 source + 1 evidence | +47 / -2 (source) + 368 (evidence) | (same file, Round 3 section) |
| `depth-sort-shared-edge-overpaint` | complete | 4 (amendment-2: Newell cascade) | _pending_ | 4 | +492 / -113 | (same file, Round 4 section) |

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

## Round 4 Highlights (amendment-2: Newell cascade adoption)

- **Replaced `Path.closerThan` with Newell, Newell, and Sancha's (1972) Z→X→Y minimax cascade.** Six-step decision tree (iso-depth extent → screen-x extent → screen-y extent → plane-side forward → plane-side reverse → 0). Each step terminates with a definitive sign or falls through; only mixed-straddle pairs return 0, which is the genuinely-ambiguous case Kahn's append-on-cycle fallback handles.
- **Deleted the private `countCloserThan`** entirely (per `feedback_no_deprecation_cycles`). Lifted its plane-side machinery into a new private `signOfPlaneSide(pathA, observer): Int` implementing Newell's strict all-on-same-side test (returns 0 unless every vertex agrees, no permissive "any vertex" voting).
- **Reverted round-3 DEPTH_SORT_DIAG instrumentation** in the same atomic commit (per amendment-2 directive c): const flag, helper, FRAME START/END, ORDER, ORDER-CYCLE, per-pair emissions, and `edgeLabel` bookkeeping. Kept the `intersects`/`cmpPath` locals for readability.
- **Test deltas**: PathTest 10 → 12 (split AC-2 + add wall-vs-floor straddle); DepthSorterTest 9 → 11 (add AC-12 LongPress full-scene + AC-13 Alpha full-scene integration tests). Local `:isometric-core:test` — **BUILD SUCCESSFUL**, 191 tests green.
- **Three deviations from the plan caught at implement time**: (1) sign convention inverted (positive = this farther per the empirical AC-1 contract, not "self closer" as the plan's pseudocode comments suggested); (2) cascade step 4 (`hasInteriorIntersection` early-exit) omitted from the cascade body to preserve unit-test contracts (DepthSorter still gates externally via amendment-1's gate); (3) AC-3 case (g) sign assertion changed from `> 0` to `< 0`. Documented in the per-slice Round 4 section.
- **Polygon split (Newell step 7) DEFERRED** per amendment-2 directive (d). No cycles surfaced in the local AC-12/AC-13 runs, so step 7 stays out of scope.

## Recommended Next Stage

- **Option A (default):** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — Verify the Round 4 implementation. Verify-stage will need: (a) Linux CI Paparazzi run to regenerate the four amendment-1 baselines and confirm the visual regression is gone (AC-14); (b) optionally a Maestro replay of `02-longpress.yaml` and `03-alpha.yaml` for live device confirmation. The unit + integration test suite already passed locally (191 green); verify-stage can rely on this and focus on visual confirmation.
- **Option B:** `/wf-review depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — Skip directly to multi-axis review. The 191-green baseline gives reasonable confidence to ask for automated correctness/security/style feedback before the verify pass.
- **Option C:** Extend with polygon-split (cascade step 7) — Only if verify-stage surfaces unresolved cycles in scenes beyond AC-12/AC-13. Would be its own follow-up amendment (`amend-3`).

**Compact strongly recommended before whichever option** — Round 4's sign-convention analysis, test tracing, and arithmetic are noise for the next stage; the PreCompact hook preserves workflow state.
