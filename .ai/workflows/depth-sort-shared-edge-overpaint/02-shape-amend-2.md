---
schema: sdlc/v1
type: shape-amendment
slug: depth-sort-shared-edge-overpaint
amendment-number: 2
created-at: "2026-04-27T23:31:32Z"
amends: 02-shape.md
source: from-review
source-ref: 05-implement-depth-sort-shared-edge-overpaint.md#round-3-directed-investigation
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
  - alpha-sample
  - wall-vs-floor
  - newell-minimax
  - newell-full
  - cmpPath-zero-fallback
  - algorithmic-restructure
refs:
  index: 00-index.md
  original-shape: 02-shape.md
  prior-amendment: 02-shape-amend-1.md
  diagnostic: 05-implement-depth-sort-shared-edge-overpaint.md
  triggering-investigation: verify-evidence/round3-longpress-diag.log
  prior-review: 07-review.md
---

# Shape Amendment 2: depth-sort-shared-edge-overpaint

## What Changed

Amendment 1 (`02-shape-amend-1.md`) addressed the over-aggressive
topological edges in 3×3 grid layouts by introducing a strict screen-
overlap gate (`hasInteriorIntersection`) in
`DepthSorter.checkDepthDependency`. Round-2 verify confirmed the gate
worked structurally (AC-9, AC-10 unit tests passed) but **the visual
regression on the LongPress sample persisted** (AC-11 NOT MET). Round-3's
directed investigation (`05-implement-...md` § Round 3, log
`verify-evidence/round3-longpress-diag.log`) traced the residual failure
to a structurally different mechanism that amendment-1 did not address:

> The amendment-1 gate **correctly admits** the (back-right cube's
> vertical wall, ground top) face pair (`intersects=true`), but
> `Path.closerThan` returns `0` because the predicate is **symmetric**
> for this geometry — each face has some vertices on the observer side
> of the other's plane, so both `countCloserThan` directions return 1
> and they cancel. With no topological edge added, Kahn's algorithm
> falls back to the depth-descending centroid pre-sort, which places
> the wall (centroid depth ~7.2) before the ground top (centroid depth
> ~4.9). The painter then paints ground over wall.

This is **not** the over-aggressive-edge bug class amendment 1 fixed;
it is the **inverse**: the comparator returns 0 when it should
discriminate, and the centroid-pre-sort fallback gets the order wrong.
The two are orthogonal failures of the same predicate-and-edge pipeline.

The original shape's `## Out of Scope` (line 135) explicitly listed
"Full Newell Z→X→Y minimax cascade in `closerThan`" as deferred to
future workflows, with the rationale "current threshold + epsilon fix
is sufficient for the diagnosed and predicted cases." That rationale
was based on the WS10 row-layout case alone. Round-3 conclusively
demonstrates a class of pairs (vertical-wall vs horizontal-floor where
each face straddles the other's plane) where the threshold + epsilon
approach **cannot** produce a non-zero return regardless of tuning, and
where the screen-overlap gate (amendment 1) cannot help either because
the gate is upstream of the comparator. This amendment pulls the
Newell-style minimax approach (or a localized restricted variant) into
scope.

This amendment:

1. Adds two new Acceptance Criteria (AC-12, AC-13) covering the
   wall-vs-floor cmpPath=0 case for both LongPress and Alpha samples.
2. Adds one new Edge Case (EC-11) covering the structural symmetry
   that produces cmpPath=0.
3. Moves "Full Newell Z→X→Y minimax cascade in `closerThan`" from
   `## Out of Scope` into `## Affected Areas` — **adopted in full**,
   not as a restricted localized helper. Per user direction: the
   entire `closerThan` predicate is replaced by Newell's algorithm.
   The existing permissive `result > 0` vote-and-subtract logic is
   superseded; the new comparator follows Newell's overlap-then-
   minimax-cascade pipeline (z-extent overlap → x-extent overlap →
   y-extent overlap → screen-projection overlap → plane-side test
   for both directions → optional polygon split on cyclic dependency).
4. Notes that the existing AC-1 through AC-11 are still required (no
   regression on row-layout WS10 or 3×3 grid neighbour-cube cases).
5. Records that the round-3 DEPTH_SORT_DIAG instrumentation in
   `DepthSorter.kt` (commit `2e29dc5`) is diagnostic-only and the next
   implement round must revert the `DIAG = true` toggle alongside the
   actual fix.

## Original (from 02-shape.md § Out of Scope)

> Explicitly deferred to future workflows (no automatic spawning at
> handoff per Round 5):
>
> - **AABB minimax pre-filter** in `IntersectionUtils.hasIntersection`
>   (LeBron IsometricBlocks-style). Would prevent adjacent non-overlapping
>   faces from reaching `closerThan` at all. Future robustness work.
> - **Full Newell Z→X→Y minimax cascade** in `closerThan`. Larger
>   algorithmic restructure; current threshold + epsilon fix is sufficient
>   for the diagnosed and predicted cases.
> - **Polygon splitting on cyclic dependencies**. Current Kahn fallback
>   handles cycles by appending leftover items; no observed cycles in
>   practice.
> - **Shewchuk robust geometric predicates** …
> - **Relative-epsilon scaling** …

## Corrected (Out of Scope — adjusted)

The original Out of Scope list is **partially superseded**. AABB minimax,
Shewchuk predicates, and relative-epsilon scaling remain deferred. Two
items move into scope:

> **Full Newell Z→X→Y minimax cascade in `closerThan`** — was deferred,
> NOW IN SCOPE in full. Per user direction (amendment-2 directive):
> the entire `closerThan` body is replaced by Newell's algorithm —
> the existing permissive vote-and-subtract approach is superseded.
> This is an algorithmic restructure, not a localized helper.

> **Polygon splitting on cyclic dependencies** — was deferred,
> NOW IN SCOPE as Newell's optional final step. Newell's classical
> formulation handles cycles by splitting one polygon along the
> other's plane and re-sorting the resulting fragments. The
> implementation may defer this step and continue to use Kahn's
> existing append-on-cycle fallback if cycles are not observed in
> the diagnosed test scenes; if AC-12 / AC-13 / AC-14 reveal
> cycles in the wall-vs-floor case, polygon splitting becomes
> mandatory.

The amendment adds a clarifying note to the remaining Out of Scope:

> **Note (amendment 2):** "Full Newell minimax cascade" and
> "Polygon splitting on cyclic dependencies" are no longer in this
> list. The remaining three items (AABB minimax pre-filter,
> Shewchuk robust predicates, relative-epsilon scaling) are still
> deferred. AABB minimax pre-filter is now redundant with Newell's
> first cascade step (z-extent overlap is itself an axis-aligned
> bounding box rejection on the z-axis); the explicit AABB pre-
> filter in `IntersectionUtils.hasIntersection` remains deferred
> as a separate broad-phase optimization.

## Original (from 02-shape.md § Acceptance Criteria — relevant subset)

The original 02-shape.md defined AC-1 through AC-8. Amendment 1
extended these with AC-9, AC-10, AC-11 covering the 3×3 grid + screen-
overlap gate. AC-9 specifically asserts:

> **AC-9** — Given a synthetic 3×3 grid of unit prisms (positions
> `(col*1.8, row*1.8, 0.1)` for col,row ∈ {0,1,2}, width=depth=1.2,
> height=1.0), When the scene is rendered through `DepthSorter.sort(...)`
> and the resulting command order is inspected, Then no cube's
> vertical side face appears at output positions 0, 1, or 2 (proxy
> for the over-aggressive-edge regression). Specifically the back-right
> cube's front face (y=3.6 plane) and left face (x=3.6 plane) must not
> be at output positions 0–2.

Round-3 verified this AC passes structurally — the new gate did move
back-right's vertical faces to output positions ≥ 3 (specifically pos=3
in the unit test). **But this was the wrong invariant**: the visual
failure on real hardware shows back-right's vertical faces at output
positions 0 and 1 in the live render, because the live render's
`DepthSorter` includes the ground-prism's faces (3 visible) while the
unit test's `DepthSorter.sort` invocation did not. The unit test was
constructed against `(9 cubes × 6 faces) = 54` items; the live render
includes the ground prism's 6 faces too, and the centroid-depth
ordering of the ground top (depth ~4.9) sits BELOW the cube vertical
faces (depths ~6–8) in the depth-descending sort, so the cube
verticals get index 0 and 1 in `depthSorted` and Kahn picks them
first.

AC-9 should still pass — it covers a real invariant — but it is **not
sufficient**. AC-12 and AC-13 below close the gap.

## Corrected (Acceptance Criteria — additions)

The original AC-1 through AC-8 and amendment-1's AC-9, AC-10, AC-11
are RETAINED unchanged. The amendment adds:

- **AC-12** *(automated, integration — NEW)* — Given the LongPressSample
  scene rendered through `DepthSorter.sort(...)` (3×3 grid of unit
  prisms + ground prism at `(-1, -1, 0)` w=8 d=6 h=0.1), When the
  resulting command order is inspected, Then for the back-right cube
  (i=8 at world `(3.6, 3.6, 0.1)..(4.8, 4.8, 1.1)`), each of its
  visible vertical-face commands (front face at y=3.6 plane, left face
  at x=3.6 plane) MUST be at an output position GREATER than the
  ground-top face's output position. Equivalently: the depth comparator
  for any (back-right vertical-face, ground-top) pair MUST produce a
  non-zero `closerThan` result that adds a topological edge forcing
  the ground top to be drawn first; falling back to centroid pre-sort
  is forbidden for these pairs.

- **AC-13** *(automated, integration — NEW)* — Given the AlphaSample
  scene rendered through `DepthSorter.sort(...)` (ground prism at
  `(-1, -1, 0)` w=8 d=6 h=0.1 + a row of three CYAN prisms at
  `(3.5, 3.0, 0.1)`, `(4.3, 3.0, 0.1)`, `(5.1, 3.0, 0.1)` with
  heights 0.8, 1.2, 1.6 respectively), When the resulting command
  order is inspected, Then for each of the three CYAN prisms, its
  visible vertical-face commands MUST be at output positions GREATER
  than the ground-top face's output position. Same invariant as AC-12,
  applied to the row-layout case with mixed heights.

- **AC-14** *(interactive, Paparazzi — UPGRADED from AC-11)* — Given
  the rendered output of `LongPressSample`, `AlphaSample`,
  `OnClickSample`, and `NodeIdSample` in their default static states,
  When captured via Paparazzi, Then no cube renders with missing
  vertical faces. Specifically: the LongPress sample's back-right
  cube must show its top, front, and left faces all visible; the
  Alpha sample's three CYAN prisms must each show their top, front,
  and left faces; the existing OnClick and NodeId row-layout invariants
  from AC-11 are unchanged. AC-11 is **superseded** by AC-14 (AC-14 is
  a strict tightening: AC-11 said "no cube exhibits missing vertical
  faces" — vague — AC-14 enumerates the specific cubes that the
  diagnostics confirmed are at risk).

The verification plan also adds:

- **AC-3 extension (case g):** parameterised unit-test case for a
  vertical face vs a horizontal face where the horizontal face's
  z-coordinate equals the vertical face's z-min and the horizontal
  face's screen polygon strictly contains the vertical face's screen
  polygon (the structural pattern of "wall standing on floor"). The
  corrected `closerThan` MUST return a non-zero value indicating the
  vertical face is closer to observer (because all of its vertices
  with z > z_floor are on the observer side of the floor's plane,
  while the floor's vertices straddle the wall's plane on the y-axis).

## Original (from 02-shape.md § Edge Cases / Failure Modes)

> - **EC-1**: Two faces of the *same* prism …
> - **EC-2**: A face with a vertex *exactly* on another face's plane …
> - **EC-3**: A prism with a degenerate face …
> - **EC-4**: A face with all vertices on the observer side …
> - **EC-5**: A face with all vertices on the *opposite* side …
> - **EC-6**: A scene where the new fix changes draw order but not
>   visibly …
> - **EC-7**: A scene where the new fix changes draw order *and*
>   changes visible pixels …
> - **EC-8**: Coordinate magnitudes above ~1000 …

Amendment 1 added EC-9, EC-10. Both retained unchanged.

## Corrected (Edge Cases — additions)

The amendment adds:

- **EC-11** *(NEW)* — A face pair (A, B) where each face has at least
  one vertex on the observer side of the other's plane AND at least
  one vertex on the opposite side. Both `countCloserThan` directions
  return 1 under the permissive `result > 0` threshold (and would
  return 0 under the rejected CR-1 no-straddling rule), so
  `closerThan = 1 - 1 = 0`. The amended comparator MUST resolve this
  case via iso minimax: project both polygons to screen-space iso
  coordinates and compare their iso-z (or iso-(x+y-z)) ranges; if one
  range is fully greater than the other's range (modulo a shared
  boundary), return non-zero indicating the higher-iso-z face is
  drawn after. If the iso ranges interleave (genuine ambiguity), fall
  back to 0 — but this case must be rare in practice and is acceptable
  to leave to centroid-pre-sort.

## Affected Areas (additions)

The original `## Affected Areas` (in 02-shape.md) listed five files;
amendment 1 added six more. The amendment-2 affected files:

- **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`** —
  **`closerThan` is replaced in full by Newell's Z→X→Y minimax
  cascade.** The existing permissive `result > 0` vote-and-subtract
  logic in `countCloserThan` is removed; `countCloserThan` itself
  may be deleted or repurposed as a step inside Newell's plane-side
  test. The new pipeline:

  1. **Z-extent test** — if `A.z_max ≤ B.z_min` (with epsilon),
     A is unambiguously farther in iso-z; return positive (B closer).
     Symmetric reverse case returns negative. Equal extents → next
     step.
  2. **X-extent test** — same shape, on the iso-x screen-projected
     range. Returns non-zero if X-extents are disjoint.
  3. **Y-extent test** — same shape, on the iso-y screen-projected
     range.
  4. **Screen-projection overlap test** — equivalent of the existing
     `hasInteriorIntersection` (or a Newell-internal SAT check). If
     polygons don't interior-overlap in screen, no edge regardless
     of plane-side; return 0.
  5. **Plane-side test (forward)** — all of A's vertices on B's
     opposite-from-observer side ⇒ A drawn first ⇒ return positive.
     All-on-same-side as observer ⇒ A drawn after ⇒ return negative.
     Mixed ⇒ next step.
  6. **Plane-side test (reverse)** — same with A and B swapped.
  7. **Polygon split (optional, per Out-of-Scope adjustment above)** —
     if both forward and reverse plane-side tests are ambiguous AND
     the pair forms a cycle in the topological graph, split one
     polygon along the other's plane and recurse. Implementation may
     defer to Kahn's existing append-on-cycle fallback if no cycles
     are observed in the diagnosed scenes.

  KDoc on the new `closerThan` must reference Newell's 1972 paper
  (or the canonical "Painter's Algorithm" wikipedia summary) and
  briefly catalogue the three pre-Newell failure modes (under-
  determined sort fixed in round 1, over-aggressive edges fixed in
  round 2 via the gate, cmpPath=0 fallback fixed in round-3-amended
  via Newell). The internal `countCloserThan` historical context
  may be moved to the optional explanation doc rather than carried
  forward in the source.

- **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`** —
  REVERT the round-3 DEPTH_SORT_DIAG instrumentation (the
  `private const val DIAG = true` toggle, the `first3` helper, FRAME
  START / FRAME END / ORDER / ORDER-CYCLE emissions, and the per-pair
  log inside `checkDepthDependency`). The gate-call refactor that
  captures `intersects` and `cmpPath` into locals can either be kept
  (slight readability win, no behavioural difference) or rolled back
  to inline form — plan stage decides.

- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`** —
  Add the AC-3 extension case (g) for vertical-vs-horizontal straddle
  pair.

- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** —
  Add AC-12 (LongPressSample full-scene + ground top ordering) and
  AC-13 (AlphaSample full-scene + ground top ordering) integration
  tests. These differ from the existing AC-9 test in that they
  include the ground-prism's 6 faces in the `DepthSorter.sort`
  invocation, not just the cubes. Without the ground prism in the
  test scene, AC-9-style assertions miss the cmpPath=0 regression.

- **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** —
  Existing four snapshots from amendment 1 stay; their baselines must
  be regenerated on Linux CI after the fix lands. AC-14 supersedes
  AC-11 as the visual confirmation; the test source is unchanged
  (the same four snapshot tests cover the AC-14 invariant).

Estimated additional file count over amendment 1: **+0 NEW files**
(only existing files are extended).

## Rationale

The original shape's `## Verification Strategy` and `## Acceptance
Criteria` were structured around the WS10 NodeIdSample row-layout
bug, with the implicit assumption that the `closerThan` predicate
needed to be made *more permissive* to handle shared-edge cases.
Round 1 implemented that (the permissive `result > 0` threshold).
Round 2 caught one failure mode of that permissiveness (over-
aggressive edges in 3×3 grids) and added the screen-overlap gate.

Round 3's directed investigation revealed a **second** failure mode
that is structurally different: the predicate returns 0 — not too
many edges, not too few; **the wrong number of edges entirely** —
for any face pair where each face straddles the other's plane on
its respective observer-axis. This is a structural symmetry, not a
numerical-precision artifact. No epsilon tuning, no straddling-rule
adjustment (CR-1, RL-1 from the original review), no gate-tightening
can resolve it. The comparator needs to consult a different
geometric signal — iso-axis minimax — to break the symmetry.

The original shape's PO-stage (Round 5) deferral of "Full Newell
Z→X→Y minimax cascade" was predicated on "the current threshold +
epsilon fix is sufficient for the diagnosed and predicted cases."
The diagnosed cases were row-layout shared-edge pairs only. Round
2 expanded "diagnosed" to include 3×3 grid pairs. Round 3 expanded
"diagnosed" to include wall-vs-floor pairs. The deferral basis
("threshold + epsilon is sufficient") no longer holds.

**The full algorithm-wide Newell adoption IS pulled in** (per
amendment-2 directive). The reasoning: three rounds of patching the
permissive vote-and-subtract approach have produced three distinct
regression classes (under-determined sort, over-aggressive edges,
cmpPath=0 symmetric fallback), and there is no a priori reason to
believe a localized fourth patch would be the last. Newell's
algorithm is the canonical, well-understood solution for painter's-
algorithm depth sorting; replacing the ad-hoc predicate with the
canonical algorithm bounds the future regression-discovery loop.

The trade-off — a larger code change with a higher risk of
near-term regression on existing baselines — is accepted in
exchange for a stronger correctness foundation. The existing
test suite (AC-1 through AC-11) acts as the regression gate: any
case currently passing must continue to pass under Newell. The
new AC-12, AC-13, AC-14 close the gap that motivated this
amendment.

## Impact on Slices

There is one planned slice (`depth-sort-shared-edge-overpaint`) and
no separate `03-slice-*.md` file (slicing was skipped in favour of
single-plan mode per the original shape's Recommended Next Stage).
The amendment affects this single slice as follows:

- **Goal**: extended from "fix the WS10 shared-edge case AND fix the
  3×3 grid corner-face case" to "**replace the ad-hoc closerThan
  predicate with Newell's canonical Z→X→Y minimax cascade**, which
  resolves all three sub-cases of the predicate-and-edge pipeline
  failure (row-layout shared-edge from round 1, 3×3 grid over-edges
  from round 2, wall-vs-floor cmpPath=0 from round 3) by construction.
  The existing screen-overlap gate
  (`IntersectionUtils.hasInteriorIntersection`) is retained as a
  broad-phase pre-filter feeding the new Newell-based comparator."

- **Acceptance criteria**: AC-1 through AC-10 retained. AC-11
  superseded by AC-14. AC-12, AC-13, EC-11 added. AC-3 extension
  case (g) added.

- **Scope**: expanded to include `Path.closerThan` (or a new
  iso-minimax helper called from it) and the round-3 DEPTH_SORT_DIAG
  revert.

- **Plan**: `04-plan-depth-sort-shared-edge-overpaint.md` (revision
  1, applied amendment 1) needs a directed re-plan for amendment 2.

## Impact on Implementation

The existing implementation across rounds 1, 2, 3 is:

- **Round 1 (`Path.countCloserThan` permissive predicate):**
  **SUPERSEDED.** The permissive `result > 0` rule is replaced by
  Newell's plane-side test (which uses sign-agreement on all
  vertices, not "any vertex on observer side"). The `countCloserThan`
  function may be deleted or repurposed as Newell's plane-side step.
  Round-1's `IntersectionUtilsTest` baseline cases (disjoint /
  overlapping / outer-contains-inner) STILL VALID.

- **Round 1 + 2 tests (PathTest, DepthSorterTest WS10, AC-9 grid
  test, AC-10 hasInteriorIntersection cases):** STILL VALID **as
  acceptance gates**. The Newell-based `closerThan` must continue
  to satisfy every existing assertion. Some PathTest cases that
  asserted specific permissive-threshold semantics (e.g., AC-2's
  "returns 0 for genuinely coplanar non-overlapping faces") may
  need their assertions reframed in Newell terms (Newell returns 0
  via the screen-projection-overlap step, not via the vote-and-
  subtract returning 0). The intent of each test is preserved; the
  exact assertion text may need a one-line update per test as the
  plan stage works through them.

- **Round 2 (`IntersectionUtils.hasInteriorIntersection` +
  `DepthSorter.checkDepthDependency` gate):** STILL VALID. The gate
  remains the right edge-insertion gate; amendment 2 changes what
  happens *after* the gate admits a pair, not the gate itself.

- **Round 2 scene factories + 4 snapshots:** STILL VALID. The
  snapshots cover the right scenes; their baselines must be regenerated
  on Linux CI after the fix lands.

- **Round 3 DEPTH_SORT_DIAG instrumentation in `DepthSorter.kt`
  (commit `2e29dc5`):** **MUST BE REVERTED** in the next implement
  round. The `DIAG = true` toggle, the `first3` helper, all four
  emission sites (FRAME START, FRAME END, ORDER, ORDER-CYCLE,
  per-pair), and the gate-call refactor that captures intersects/
  cmpPath into locals — all must come out (or, for the gate-call
  refactor, may stay if plan stage prefers the readability).

The remaining concern: **the original shape's `## Acceptance Criteria`
text in `02-shape.md` directly contradicts amendment 2's AC-12 and
AC-13.** Specifically, the original shape's AC-1 through AC-7 do not
reference the wall-vs-floor case at all. This is not a contradiction
that needs resolving in source; it's a normal state under the
amendment lifecycle. Future readers should consult amendment 2 + 1
+ original shape together; the cumulative spec is the authoritative
slice definition.

## Verification Strategy (additions to amendment 1's additions)

The amendment 1 verification additions are RETAINED. Amendment 2 adds:

- **AC-12**: synthetic full-scene LongPressSample (9 cubes + ground
  prism) integration test in `DepthSorterTest`, asserting back-right
  vertical-face positions > ground-top position.
- **AC-13**: synthetic full-scene AlphaSample (3 CYAN prisms +
  ground prism) integration test in `DepthSorterTest`, asserting
  each CYAN prism's vertical-face positions > ground-top position.
- **AC-3 extension case (g)**: parametric PathTest case for the
  vertical-vs-horizontal straddle pair.
- **AC-14**: visual confirmation via the same 4 Paparazzi snapshots
  from amendment 1 (only the AC labels change; baselines regenerate
  on Linux CI).
- **Maestro flow `02-longpress.yaml` and `03-alpha.yaml`** at
  `.ai/workflows/depth-sort-shared-edge-overpaint/maestro/` remain
  reusable for interactive re-verification on emulator after the fix.

## Documentation Plan (additions to amendment 1's additions)

The amendment 1 additions are RETAINED. Amendment 2 adds nothing
material — the optional internal explanation
(`docs/internal/explanations/depth-sort-painter-pipeline.md`) becomes
even more strongly recommended given the now-three-failure-mode
history (under-determined sort, over-aggressive edges, cmpPath=0
fallback). Should the explanation be written, it must reference all
three failure modes and the full mitigation chain (permissive
threshold, screen-overlap gate, iso minimax for straddling pairs).

## Freshness Research (no new searches required)

The original shape's freshness research (Newell, painter, Paparazzi,
Shewchuk, IsometricBlocks AABB minimax) remains authoritative for
the algorithmic landscape. Amendment 1's diagnostic
(`07-review-grid-regression-diagnostic.md`) and round-3's directed
investigation (`05-implement-...md` § Round 3, captured log
`verify-evidence/round3-longpress-diag.log`) provide the in-repo
failure-mode documentation that supersedes hypothetical reasoning
about the bug. No external lookup is needed for amendment 2.
