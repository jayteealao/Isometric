---
schema: sdlc/v1
type: plan-index
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 4
created-at: "2026-04-26T19:33:07Z"
updated-at: "2026-04-27T22:32:06Z"
planning-mode: single
slices-planned: 1
slices-total: 1
implementation-order:
  - depth-sort-shared-edge-overpaint
conflicts-found: 0
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  slice-index: ""
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Plan Index — depth-sort shared-edge overpaint

Single-plan mode. No slicing. One coherent fix in one source file plus paired tests across 4 test files + 1 binary snapshot baseline.

## Slice Plan Summaries

### `depth-sort-shared-edge-overpaint`

**Original-scope (executed in commit `3e811aa`)**:

- **Files touched**: 5 source + 1 binary (3 modified, 2 new source, 1 new PNG — the `nodeIdSharedEdge.png` baseline was generated locally but not yet committed; superseded by amendment-1 below).
  - Modified: `Path.kt`, `PathTest.kt`, `DepthSorterTest.kt`, `IsometricCanvasSnapshotTest.kt`.
  - New: `IntersectionUtilsTest.kt`, `WS10NodeIdScene.kt`.
- **Strategy executed**: strict red-green TDD. Failing `closerThan` test for hq-right + factory-top → permissive `result > 0` fix in `Path.countCloserThan` + epsilon 1e-9 → 1e-6 → layered tests + snapshot infrastructure.
- **Result**: WS10 NodeIdSample case fixed; latent regression in 3×3 grid layouts surfaced post-merge on emulator (LongPressSample, AlphaSample). Documented in `07-review-grid-regression-diagnostic.md`.

**Amendment-1 scope (un-executed, the next target of `/wf-implement`)**:

- **Additional files**: 9 (3 modified, 6 new) + 4 binary baselines.
  - Modify: `IntersectionUtils.kt` (add `hasInteriorIntersection`), `DepthSorter.kt` (wire the new gate), `IntersectionUtilsTest.kt` (add AC-10 cases), `DepthSorterTest.kt` (add AC-9), `IsometricCanvasSnapshotTest.kt` (replace single snapshot with four).
  - New: `OnClickRowScene.kt`, `LongPressGridScene.kt`, `AlphaSampleScene.kt`, plus four new baseline PNGs.
- **Strategy**: directed fix overlaid on `3e811aa`. Add a stricter sibling helper rather than modifying existing `hasIntersection` (preserves contract for any non-DepthSorter callers). Wire `DepthSorter.checkDepthDependency` to call the new helper. Add AC-9 (3×3 grid integration test) and AC-10 (boundary-only hasIntersection cases). Replace the single `nodeIdSharedEdge` Paparazzi snapshot with four scene-factory snapshots.
- **Key risk (amendment-1)**: the stricter gate could break the existing `coplanar tile grid` test — the tile-grid test relies on edges being added for tile-grid face pairs that DO have interior overlap in screen projection. The new `hasInteriorIntersection` must still return true for those genuine-overlap cases. Plan step A5 explicitly re-runs the full test suite to catch this.

## Cross-Cutting Concerns

- **Build-logic Windows race** is still uncommitted in the working tree. Local Windows verification of `:isometric-compose:test` (which is required for the snapshot baseline) requires the documented `./gradlew --stop && mv build-logic/build aside` workaround plus killing leftover `java.exe` processes (`taskkill /F /IM java.exe`) when file locks persist across runs. CI on Linux is unaffected. The build-logic CC fix can ride along as a separate `chore(build-logic):` commit at handoff if desired.
- **Paparazzi baselines on Windows render blank** in this environment — every `IsometricCanvasSnapshotTest` baseline produced 6917-byte empty frames during verify. CI on Linux produces real baselines. The amendment-1 work expects to record the four new baselines on Linux CI; do not commit blank PNGs.
- **Pre-existing Paparazzi tech debt** (11 of 13 snapshot tests have no baseline on disk; the 2 that did were untracked) is unchanged by this work. Track separately.
- **Amendment-1 reverted today's reviews-mode source changes** in the working tree (Path.kt and DepthSorter.kt). The DEPTH_SORT_DIAG temp logging was reverted as part of that. Diagnostic logs are preserved under `verify-evidence/`.

## Integration Points Between Slices

N/A — single slice.

## Recommended Implementation Order

1. `depth-sort-shared-edge-overpaint` — only one. The original-scope work (steps 1–9) is already committed in `3e811aa`. The amendment-1 work (steps A1–A8) is the next implementation target. Execute amendment-1 steps in the strict order specified in `04-plan-depth-sort-shared-edge-overpaint.md` § Step-by-Step Plan: pre-flight → red AC-9 → red AC-10 → implement `hasInteriorIntersection` → wire gate in DepthSorter → replace Paparazzi snapshot test + add three new scene factories → re-run maestro flows for visual confirmation → atomic commit `fix(depth-sort): screen-overlap gate for 3x3 grid edge-cases`.

## Conflicts Found

0.

## Freshness Research

Inherited from shape's freshness pass. See `02-shape.md` § Freshness Research and `04-plan-depth-sort-shared-edge-overpaint.md` § Freshness Research. No new external research required at the plan stage.

## Recommended Next Stage

- **Option A (default):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — plan is execution-ready, single coherent unit, no blockers. **Run `/compact` first** to drop research history before invoking implement; the PreCompact hook preserves workflow state.
- **Option B:** `/wf-slice depth-sort-shared-edge-overpaint` — only if the fix turns out to need separable test scaffolding vs core fix (it doesn't, per analysis).
- **Option C:** `/wf-shape depth-sort-shared-edge-overpaint` — only if the threshold revision in PLAN Round 2 (permissive vs strict majority) reveals deeper spec issues. It doesn't.
