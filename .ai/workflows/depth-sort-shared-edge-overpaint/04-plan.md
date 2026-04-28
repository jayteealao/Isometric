---
schema: sdlc/v1
type: plan-index
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 4
created-at: "2026-04-26T19:33:07Z"
updated-at: "2026-04-28T08:15:48Z"
planning-mode: single
slices-planned: 1
slices-total: 1
applies-amendment: 2
prior-amendments-applied: [1]
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
  - newell-minimax
  - newell-full
  - cmpPath-zero-fallback
  - algorithmic-restructure
refs:
  index: 00-index.md
  slice-index: ""
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Plan Index — depth-sort shared-edge overpaint

Single-plan mode. No slicing. One coherent fix delivered across three rounds: round 1 (`3e811aa`) replaced the integer-division collapse in `countCloserThan`; round 2 (`9cef055`) added the `hasInteriorIntersection` screen-overlap gate; round 3 (`2e29dc5`) is diagnostic-only DEPTH_SORT_DIAG instrumentation. Round 4 (this plan revision, applies amendment-2) replaces the entire `closerThan` predicate with Newell's canonical Z→X→Y minimax cascade and reverts the round-3 DIAG instrumentation in a single atomic commit.

## Slice Plan Summaries

### `depth-sort-shared-edge-overpaint`

**Amendment-2 scope (un-executed, the next target of `/wf-implement`)**:

- **Files touched**: 4 modified, 0 new (existing scaffolding is reused).
  - Modify: `Path.kt` (replace `closerThan` body with Newell cascade; delete `countCloserThan`; add `signOfPlaneSide` private helper).
  - Modify: `DepthSorter.kt` (revert round-3 DEPTH_SORT_DIAG instrumentation from commit `2e29dc5` — `DIAG` toggle, `first3` helper, FRAME / ORDER / per-pair emissions).
  - Modify: `PathTest.kt` (split AC-2 into "coplanar overlapping returns 0" + "coplanar non-overlapping returns non-zero via X-extent minimax"; add AC-3 case (g) wall-vs-floor straddle test; update KDocs on five existing closerThan tests).
  - Modify: `DepthSorterTest.kt` (add AC-12 LongPress full-scene + ground top ordering; add AC-13 Alpha full-scene + ground top ordering).
- **Strategy**: directed fix overlaid on `2e29dc5` HEAD. Replace `closerThan`'s body in place (preserves AC-8 signature stability). Use Newell's canonical Z→X→Y minimax cascade: iso-z extent → screen-x extent → screen-y extent → screen polygon overlap → strict plane-side forward → strict plane-side reverse. Polygon split (cascade step 7) is DEFERRED unless cycles surface in AC-12/AC-13 verification — Kahn's append-on-cycle fallback handles residual cycles. Bundle the round-3 DIAG revert into the same atomic commit.
- **Key risks (amendment-2)**:
  1. Sign-convention drift in the cascade — `Point.depth(angle)` returns LARGER values for FARTHER faces, so the Z-extent comparison must invert intuition. Implementer's first run on AC-1 catches this.
  2. Strict plane-side test (Newell's all-on-same-side rule) is structurally stricter than the round-1 permissive `result > 0` rule. Five existing PathTest cases pass under permissive; they should pass under Newell because the Z/X/Y minimax steps decide before the plane-side step is reached. If any flips, debug the sign convention, not the test.
  3. Polygon-split deferral risk — if AC-12/AC-13 surface a Kahn cycle, escalate to amend-3.
  4. Linux CI is authoritative for AC-14 visual confirmation; Windows render-blank is environmental.

**Amendment-1 scope (executed in commit `9cef055`)**:

- **Files touched**: 9 (3 modified, 6 new) + 4 binary baselines.
  - Added `IntersectionUtils.hasInteriorIntersection`, wired in `DepthSorter.checkDepthDependency`, AC-9 + AC-10 tests, four scene-factory snapshot tests + baselines.
- **Result**: Gate works structurally (AC-9, AC-10 unit tests green) but AC-11 visual on LongPress remained NOT MET — round-3 directed investigation traced the residual failure to the cmpPath=0 wall-vs-floor symmetric-straddle case, which amendment-2 resolves.

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

1. `depth-sort-shared-edge-overpaint` — only one. Original-scope (steps 1–9) is committed in `3e811aa`; amendment-1 (steps A1–A8) is committed in `9cef055`; round-3 DIAG instrumentation is committed in `2e29dc5` (diagnostic-only). The amendment-2 work (steps R1–R8) is the next implementation target. Execute in strict order per `04-plan-depth-sort-shared-edge-overpaint.md` § Step-by-Step Plan: pre-flight at HEAD `2e29dc5` → red AC-12 (LongPress full scene) → red AC-13 (Alpha full scene) → red AC-2 split + AC-3 case (g) → green: replace `closerThan` body with Newell Z→X→Y minimax cascade → green: revert round-3 DEPTH_SORT_DIAG → regression sweep + Linux Paparazzi regen + Maestro live capture → atomic commit `fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade`.

## Conflicts Found

0.

## Freshness Research

Inherited from shape's freshness pass. See `02-shape.md` § Freshness Research and `04-plan-depth-sort-shared-edge-overpaint.md` § Freshness Research. No new external research required at the plan stage.

## Recommended Next Stage

- **Option A (default):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — plan revision 2 is execution-ready, single coherent unit, no blockers. **Run `/compact` first** to drop the round-3 directed-investigation history before invoking implement; the PreCompact hook preserves workflow state.
- **Option B:** `/wf-amend depth-sort-shared-edge-overpaint from-implement` — invoke ONLY if R7 regression sweep surfaces a Kahn cycle (AC-12/AC-13 fails because the topological sort cycled). Triggers `amend-3` to pull cascade step 7 (polygon-split) into scope.
- **Option C:** `/wf-verify depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint` — only if the user wants to re-verify the current `2e29dc5` state without applying amendment-2. Not recommended.
