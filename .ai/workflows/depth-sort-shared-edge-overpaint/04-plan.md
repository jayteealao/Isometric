---
schema: sdlc/v1
type: plan-index
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 4
created-at: "2026-04-26T19:33:07Z"
updated-at: "2026-04-26T19:33:07Z"
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

- **Files to touch**: 5 source + 1 binary (3 modified, 2 new source, 1 new PNG).
  - Modify: `isometric-core/.../Path.kt`, `isometric-core/.../test/PathTest.kt`, `isometric-core/.../test/DepthSorterTest.kt`, `isometric-compose/.../test/IsometricCanvasSnapshotTest.kt`.
  - New: `isometric-core/.../test/IntersectionUtilsTest.kt`, `isometric-compose/.../test/scenes/WS10NodeIdScene.kt`, `isometric-compose/.../test/snapshots/images/...nodeIdSharedEdge.png`.
- **Strategy**: strict red-green TDD. Write failing `closerThan` test for the hq-right + factory-top pair (returns 0 today). Apply two-line fix in `Path.countCloserThan` (replace integer-division with permissive `result > 0`; widen epsilon 1e-9 → 1e-6). Confirm test goes green. Add layered tests (parameterised `closerThan`, `DepthSorter` integration, antisymmetry invariant, `IntersectionUtils` baseline). Extract `WS10NodeIdScene` factory in test sources. Add Paparazzi snapshot test using the factory; record baseline PNG; commit atomically.
- **Key risk**: permissive threshold (`result > 0`) accepts a single noisy vertex as evidence — in pathological scenes with extreme coordinates and high floating-point noise, this could create a spurious topological edge. Mitigated by widening epsilon to 1e-6. Relative-epsilon scaling is documented as deferred future work.

## Cross-Cutting Concerns

- **Build-logic Windows race** is still uncommitted in the working tree. Local Windows verification of `:isometric-compose:test` (which is required for the snapshot baseline) requires the documented `./gradlew --stop && mv build-logic/build aside` workaround. CI on Linux is unaffected. The build-logic CC fix can ride along as a separate `chore(build-logic):` commit at handoff if desired.
- **Paparazzi baselines not committed to git** is pre-existing tech debt (11 of 13 snapshot tests have no baseline on disk; the 2 that do are untracked). The new `nodeIdSharedEdge` baseline must be explicitly `git add`ed. Recommend a separate follow-up workflow to record + commit baselines for the existing 11 tests; not in scope here.

## Integration Points Between Slices

N/A — single slice.

## Recommended Implementation Order

1. `depth-sort-shared-edge-overpaint` — only one. Execute in the strict order specified in `04-plan-depth-sort-shared-edge-overpaint.md` § Step-by-Step Plan: pre-flight → red → green → layered tests → snapshot infrastructure → record baseline → atomic commit.

## Conflicts Found

0.

## Freshness Research

Inherited from shape's freshness pass. See `02-shape.md` § Freshness Research and `04-plan-depth-sort-shared-edge-overpaint.md` § Freshness Research. No new external research required at the plan stage.

## Recommended Next Stage

- **Option A (default):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — plan is execution-ready, single coherent unit, no blockers. **Run `/compact` first** to drop research history before invoking implement; the PreCompact hook preserves workflow state.
- **Option B:** `/wf-slice depth-sort-shared-edge-overpaint` — only if the fix turns out to need separable test scaffolding vs core fix (it doesn't, per analysis).
- **Option C:** `/wf-shape depth-sort-shared-edge-overpaint` — only if the threshold revision in PLAN Round 2 (permissive vs strict majority) reveals deeper spec issues. It doesn't.
