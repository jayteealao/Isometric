---
schema: sdlc/v1
type: shape-amendment
slug: depth-sort-shared-edge-overpaint
amendment-number: 1
created-at: "2026-04-27T22:16:08Z"
amends: 02-shape.md
source: from-review
source-ref: 07-review-grid-regression-diagnostic.md
affected-slices:
  - depth-sort-shared-edge-overpaint
plan-needs-update: true
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - 3x3-grid
  - regression
  - latent-since-3e811aa
refs:
  index: 00-index.md
  original-shape: 02-shape.md
  diagnostic: 07-review-grid-regression-diagnostic.md
  review: 07-review.md
  triggering-review-cmd: 07-review-correctness.md
---

# Shape Amendment 1: depth-sort-shared-edge-overpaint

## What Changed

The original shape's Problem Statement, Acceptance Criteria, Edge Cases, and
Out of Scope were all framed around the WS10 NodeIdSample bug class —
**row-layout adjacent prisms with shared edges**. Verification on emulator
(post-merge of `3e811aa`) revealed a second, structurally different bug
class: **3×3 grid layouts where one or more corner cubes lose their
vertical side faces**. The latent regression is in `3e811aa` itself; the
original shape acknowledged the risk in passing (Round 4: "permissive
threshold may over-add edges in pathological scenes") but did not fold it
into the Acceptance Criteria, so no test caught it.

This amendment:

1. Expands `## Problem Statement` to cover both bug classes (row-layout
   under-determined sort + grid-layout over-aggressive edges).
2. Adds three new Acceptance Criteria (AC-9, AC-10, AC-11) covering grid
   layouts and the screen-overlap gate.
3. Adds two new Edge Cases (EC-9, EC-10) covering the over-aggressive
   edge accumulation pattern.
4. Moves "strict 2D screen-overlap gate in DepthSorter.checkDepthDependency"
   from `## Out of Scope` to `## Affected Areas`.
5. Confirms the existing AC-1 through AC-8 are still required (no
   regression on row-layout cases).
6. Adds Paparazzi snapshots for `LongPressSample`, `AlphaSample`,
   `OnClickSample`, and `NodeIdSample` to the verification plan, replacing
   the single `nodeIdSharedEdge` snapshot from the original shape.

## Original (from 02-shape.md)

> ## Problem Statement
> `Path.countCloserThan` … collapses a per-vertex plane-side vote into an
> integer fraction via integer division. … For the WS10 NodeIdSample's
> HQ-right vs Factory-top face pair this produces a *farther* face being
> painted *over* a *closer* face at their shared screen-space corner.
>
> The bug class is broader than the one observed pair: any axis-aligned
> prism scene with adjacent buildings of different heights can hit this
> exact failure when one prism's vertical side face overlaps another
> prism's horizontal top face in 2D screen space.

The original Problem Statement is correct as far as it goes, but the bug
class is broader than just adjacent-buildings-of-different-heights. The
permissive `result > 0` fix introduces a SECOND bug class for grid layouts.

## Corrected (Problem Statement, expanded)

`Path.countCloserThan`'s integer-division collapse caused a tied
comparator (closerThan = 0) for shared-edge face pairs in **row layouts**
of adjacent prisms with different heights — the WS10 NodeIdSample case.
Commit `3e811aa` fixed this by replacing the integer-division collapse
with a permissive `result > 0` threshold, restoring the signed comparator's
ability to distinguish closer from farther faces along one geometric axis.

However, **the permissive threshold over-fires in 3×3 grid layouts**:
because each face has neighbours along multiple axes, the predicate
accumulates many "this is closer" votes from face pairs whose 2D
screen-projected polygons do **not** actually overlap. Each spurious vote
adds a topological edge in `DepthSorter.checkDepthDependency`. The
topological sort respects all edges, pushing some corner cubes' vertical
faces to output positions 0–2 (drawn first), where they get painted over
by faces drawn afterward. Visible regression on the LongPress and Alpha
samples: the 3×3 grid's back-right cube has only its top face visible,
sides missing. Documented in detail with vertex traces in
`07-review-grid-regression-diagnostic.md`.

The bug class is therefore TWO distinct failure modes of the same
predicate-and-edge pipeline:

1. **Under-determined sort** (pre-`3e811aa` behaviour): the integer-
   division collapse returned 0 in both directions for shared-edge cases,
   producing a tied comparator that DepthSorter could not resolve into an
   edge. Centroid pre-sort then chose the wrong order.
2. **Over-aggressive edges** (post-`3e811aa` behaviour, this amendment's
   target): the permissive `result > 0` returns 1 even when the pair's
   2D screen polygons don't overlap. Adding the resulting topological
   edges pushes faces to extreme order positions where they get painted
   over by other faces in regions where THOSE projections do overlap.

The fix must address both: keep `3e811aa`'s permissive predicate (it's
correct in isolation for asymmetric pairs) AND gate the edge insertion in
`DepthSorter.checkDepthDependency` on actual interior overlap of the two
faces' 2D iso-projected polygons.

## Original (Acceptance Criteria — relevant subset)

> - **AC-3** *(automated, unit)* — Given a parameterised set of synthetic
>   shared-edge / adjacent-face configurations covering: (a) two prisms
>   sharing an X-face, (b) two prisms sharing a Y-face, (c) one prism's
>   top face vs an adjacent prism's vertical side face, (d) two prisms of
>   equal height adjacent on X, (e) two prisms of equal height adjacent
>   on Y, …
>
> - **AC-4** *(automated, integration)* — Given the four-building scene
>   from `NodeIdSample` (heights `[3.0, 2.0, 1.5, 4.0]` …)
>
> - **AC-5** *(interactive, Paparazzi snapshot)* — Given the
>   `NodeIdSample` rendered through `IsometricScene` …
>
> - **AC-6** *(automated, regression)* — Given the existing tile-grid
>   Paparazzi snapshots and `DepthSorterTest` coplanar-prism /
>   3×3-grid / cycle-fallback cases, When the new fix is applied, Then
>   all existing snapshots are byte-identical OR any pixel diff is
>   investigated, explained, and re-baselined inside the same atomic
>   `fix(...)` commit.

AC-3, AC-4, AC-5 are scoped to row-layout shared-edge adjacency only.
AC-6 mentions a "3×3-grid" `DepthSorterTest` case but actually refers to
the existing coplanar-tile-grid test (all faces at z=0) — a fundamentally
different geometry from the 3×3 stacked-prism grid that exhibits this
bug.

## Corrected (Acceptance Criteria — additions)

The original AC-1 through AC-8 are RETAINED as-is. The amendment adds:

- **AC-9** *(automated, integration — NEW)* — Given a synthetic 3×3 grid
  of unit prisms (positions `(col*1.8, row*1.8, 0.1)` for col,row ∈ {0,1,2},
  width=depth=1.2, height=1.0), When the scene is rendered through
  `DepthSorter.sort(...)` and the resulting command order is inspected,
  Then no cube's vertical side face appears at output positions 0, 1, or
  2 (proxy for the over-aggressive-edge regression). Specifically the
  back-right cube's front face (y=3.6 plane) and left face (x=3.6 plane)
  must not be at output positions 0–2.

- **AC-10** *(automated, unit — NEW)* — Given two prism faces whose 2D
  iso-projected polygons do NOT overlap (separated by a non-zero gap or
  only sharing a boundary touch with no interior intersection), When
  `DepthSorter.checkDepthDependency` is called for the pair, Then no
  topological edge is added regardless of what `closerThan` returns. The
  gate must accept faces whose projections share a single edge or vertex
  with zero interior overlap as "non-overlapping" for edge-insertion
  purposes.

- **AC-11** *(interactive, Paparazzi snapshot — NEW, expansion of AC-5)* —
  Given the rendered output of `LongPressSample`, `AlphaSample`,
  `OnClickSample`, and `NodeIdSample` in their default static states (no
  user interaction), When captured via Paparazzi, Then no cube exhibits
  missing vertical faces. The original AC-5's `nodeIdSharedEdge` baseline
  is REPLACED by these four scene-specific baselines.

The verification plan also ADDS:

- **AC-3 extension**: parameterised case (f) — two prisms separated by a
  non-zero gap in screen-space (so `hasIntersection` returns false), the
  closerThan return is irrelevant, no edge is added. This locks the
  screen-overlap gate's behaviour at the unit level.

## Original (Edge Cases — relevant subset)

> - **EC-1**: Two faces of the *same* prism …
> - **EC-2**: A face with a vertex *exactly* on another face's plane …
> - **EC-3**: A prism with a degenerate face …
> - **EC-4**: A face with all vertices on the observer side …
> - **EC-5**: A face with all vertices on the *opposite* side …
> - **EC-6**: A scene where the new fix changes draw order but not
>   visibly …
> - **EC-7**: A scene where the new fix changes draw order *and* changes
>   visible pixels …
> - **EC-8**: Coordinate magnitudes above ~1000 …

The amendment retains EC-1 through EC-8 unchanged and adds two new edge
cases that the screen-overlap gate must handle:

## Corrected (Edge Cases — additions)

- **EC-9** *(NEW)* — Two prism faces whose 2D bounding boxes overlap but
  whose actual polygon interiors do NOT (e.g., one polygon is a
  parallelogram entirely inside the other's bounding box but in a
  non-overlapping region). The gate must use polygon-interior-overlap
  test, not bounding-box overlap.

- **EC-10** *(NEW)* — Two prism faces whose 2D iso-projected polygons
  share exactly one screen-space edge (boundary touch, zero interior
  area). The gate must treat this as non-overlapping. Boundary-touch
  detection threshold: interior-overlap area ≥ ε for some implementation-
  defined small ε (e.g., 1e-9 in screen units).

## Affected Areas (additions)

The original `## Affected Areas` listed five files. The amendment adds:

- **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`** —
  modify `checkDepthDependency` to gate the topological-edge insertion on
  a stricter screen-overlap test. Currently the gate is
  `IntersectionUtils.hasIntersection` on flat-XY-projected polygons; the
  amendment requires this to be either replaced or augmented with an
  interior-overlap check that returns false for boundary-only or
  non-overlapping pairs.

- **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`** —
  if `hasIntersection`'s current semantics are too permissive (returns
  true for boundary-only contact), tighten or add a sibling helper. The
  plan stage will determine which file actually changes.

- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** —
  add tests for boundary-only and non-overlapping cases (the original
  amendment's deferred case from implement § Deviations is now
  load-bearing).

- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** —
  add the 3×3 grid integration test for AC-9.

- **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/`** —
  add reusable scene factories for `LongPressSample`, `AlphaSample`,
  `OnClickSample` (the existing `WS10NodeIdScene` covers `NodeIdSample`).

- **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** —
  replace the single `nodeIdSharedEdge` snapshot with four snapshots
  covering the four sample states.

Estimated additional file count over original shape: **+3 files** (1 new
test, 1 new util test expansion, 3 new scene factories minus 1 unchanged).

## Original (Out of Scope — relevant subset)

> Explicitly deferred to future workflows (no automatic spawning at
> handoff per Round 5):
>
> - **AABB minimax pre-filter** in `IntersectionUtils.hasIntersection`
>   (LeBron IsometricBlocks-style). Would prevent adjacent non-overlapping
>   faces from reaching `closerThan` at all. Future robustness work.
> - **Full Newell Z→X→Y minimax cascade** in `closerThan`. Larger
>   algorithmic restructure; current threshold + epsilon fix is sufficient
>   for the diagnosed and predicted cases.

These two items remain deferred. The third item the diagnostic surfaced
— "strict 2D screen-overlap gate in `DepthSorter.checkDepthDependency`"
— was NOT in the original Out of Scope list because the original shape
did not anticipate it. The amendment adds it to Affected Areas (above) as
in-scope work.

## Corrected (Out of Scope — adjusted note)

The original Out of Scope list is RETAINED unchanged for items that
remain deferred. The amendment adds a clarifying note:

> **Note (amendment 1):** The "strict 2D screen-overlap gate" mentioned
> below is NOT a separate algorithmic redesign like AABB minimax or
> Newell cascade — it's a precision improvement to the existing
> `hasIntersection`-based gate, scoped within the current architecture.
> AABB minimax and Newell cascade remain deferred; they would replace
> the current closerThan-driven approach entirely and are a much larger
> change.

## Rationale

The original shape stage acknowledged the risk in Round 4:

> "Permissive threshold may over-add edges. Any vertex on observer side
> of the other plane now votes 'yes'. For pathological scenes with
> extreme coordinate values where floating-point noise puts a vertex on
> the wrong side of a plane, a spurious dependency edge could be added."

But framed the risk as *numerical noise in pathological coordinates*, not
*structural over-aggressiveness in normal grid layouts*. The diagnostic
showed the real mechanism is geometric, not numerical — every cube in a
grid has neighbour faces whose 4 vertices all sit on the observer side of
its perpendicular planes, producing legitimate "winning votes" in
`closerThan` regardless of whether the screen projections overlap. No
amount of epsilon tuning would fix this; the gate must move to
`DepthSorter.checkDepthDependency`.

The original shape stage's PO answers (Round 5) explicitly chose to defer
all three deferred items "from this workflow," but predicated that
decision on the assumption that the diagnosed case (WS10) was the full
bug class. Once the second bug class became visible on emulator, the
deferral basis no longer held. This amendment is the corrective response.

## Impact on Slices

There is one planned slice (`depth-sort-shared-edge-overpaint`) and no
separate `03-slice-*.md` file (slicing was skipped in favour of single-plan
mode per shape's Recommended Next Stage). The amendment affects this
single slice as follows:

- **Goal**: extended from "fix the WS10 shared-edge case" to "fix both
  the WS10 shared-edge case AND the 3×3 grid corner-face case via a
  combined permissive-predicate + screen-overlap-gate approach."
- **Acceptance criteria**: AC-1 through AC-8 retained. AC-9, AC-10, AC-11
  added. AC-3 extended with case (f). AC-5's single snapshot replaced by
  AC-11's four snapshots.
- **Scope**: expanded to include `DepthSorter.checkDepthDependency`,
  `IntersectionUtils.hasIntersection` (or a sibling helper), and three
  additional scene factories.
- **Plan**: `04-plan-depth-sort-shared-edge-overpaint.md` was completed
  for the original scope. It needs a directed re-plan for the amendment.

The existing implementation in commit `3e811aa` is **partially valid**:

- **Path.kt's permissive `result > 0` predicate**: KEEP unchanged. The
  amendment confirms it's correct in isolation; only the edge-insertion
  gate around it needs improving.
- **Path.kt's 1e-6 epsilon**: KEEP unchanged. The amendment confirms it's
  appropriate for typical coordinate ranges (RL-1's per-distance vs
  product distinction is now a separate concern, not blocking).
- **Existing AC-1 through AC-8 tests**: KEEP all green; no behaviour
  change required for row-layout cases.
- **AC-5's `nodeIdSharedEdge` snapshot**: REPLACED by AC-11's four-scene
  snapshot set. The `WS10NodeIdScene` factory and the snapshot test
  source are reusable; only the baseline PNG is regenerated.
- **The IntersectionUtilsTest**: existing 3 cases retained; new
  boundary-only and non-overlapping cases added.

## Verification Strategy (additions to original)

The original `## Verification Strategy` is RETAINED. The amendment adds:

- **AC-9**: synthetic 3×3 grid integration test in `DepthSorterTest`,
  asserting no corner-cube vertical face is at output position 0–2.
- **AC-10**: parametrised unit test in `IntersectionUtilsTest` (or a new
  `DepthSorterEdgeGateTest`) for boundary-only / non-overlapping pairs.
- **AC-11**: four Paparazzi snapshots — `longPressSample`, `alphaSample`,
  `onClickSampleStaticAndSelected`, `nodeIdSample` — replacing the
  original single `nodeIdSharedEdge` snapshot.
- **Maestro flows** at `.ai/workflows/depth-sort-shared-edge-overpaint/maestro/`
  (`01-onclick.yaml`, `02-longpress.yaml`, `03-alpha.yaml`) are reusable
  for interactive verification on emulator at the verify stage.

## Documentation Plan (additions to original)

The original `## Documentation Plan` is RETAINED. The amendment adds:

- The `docs/internal/explanations/depth-sort-painter-pipeline.md` file
  (originally listed as "optional, recommended") becomes RECOMMENDED
  given the now-bigger surface area. Should explain: centroid pre-sort,
  Kahn topological refinement, plane-side `closerThan` test, AND the new
  screen-overlap gate. Reference both bug classes (under-determined sort,
  over-aggressive edges) as historical context.

## Freshness Research (no new searches required)

The original shape's freshness research remains authoritative for the
algorithmic landscape (Newell, painter, IsometricBlocks, Paparazzi
changelog, Shewchuk predicates). The diagnostic in
`07-review-grid-regression-diagnostic.md` provides the in-repo failure-mode
documentation that supersedes hypothetical reasoning about the bug.
