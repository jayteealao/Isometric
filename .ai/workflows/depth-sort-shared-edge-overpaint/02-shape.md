---
schema: sdlc/v1
type: shape
slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 2
created-at: "2026-04-26T19:20:32Z"
updated-at: "2026-04-26T19:20:32Z"
docs-needed: true
docs-types: [reference, explanation]
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
refs:
  index: 00-index.md
  intake: 01-intake.md
  next: 03-slice.md
next-command: wf-plan
next-invocation: "/wf-plan depth-sort-shared-edge-overpaint"
---

# Shape — depth-sort shared-edge overpaint

## Problem Statement

`Path.countCloserThan` (`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, line 139) collapses a per-vertex plane-side vote into an integer fraction via integer division: `(result + result0) / points.size`. For a 4-vertex face where 2 vertices are on the observer side of the other plane, this evaluates to `2 / 4 = 0`, discarding a real signal and reporting an ambiguous tie. `Path.closerThan` then returns 0, `DepthSorter.checkDepthDependency` deliberately adds no `drawBefore` edge, and the back-to-front pre-sort by `Path.depth` becomes the sole determiner of order — for the WS10 NodeIdSample's HQ-right vs Factory-top face pair this produces a *farther* face being painted *over* a *closer* face at their shared screen-space corner.

The bug class is broader than the one observed pair: any axis-aligned prism scene with adjacent buildings of different heights can hit this exact failure when one prism's vertical side face overlaps another prism's horizontal top face in 2D screen space. The `(2/4 = 0)` collapse is the proximate cause; the secondary contributor is a 1e-9 epsilon at line 128 that is too tight for the project's typical 0–100 coordinate range.

## Primary Actor / User

- **Direct beneficiary:** developers building `IsometricScene`-based apps with mixed-height adjacent prisms (city builders, isometric dashboards, the WS10 alpha demo).
- **Indirect beneficiary:** anyone running the published 1.2.0-alpha.01 prerelease, since the WS10 sample is the public showcase for the new per-node interaction props.
- **Not a user**: the predicate is an internal `isometric-core` function with no documented public API, but its outputs surface as visible occlusion artefacts.

## Desired Behavior

`Path.countCloserThan(other)` returns a sign-preserving Int that reflects whether *most* of `this`'s vertices sit on the observer's side of `other`'s plane. Specifically:

- Return `1` when a **strict majority** of vertices land on the same side of `other`'s plane as the observer (`result > points.size / 2`).
- Return `0` only when no strict majority exists (genuine coplanar / ambiguous case).
- Return value is consumed unchanged by `Path.closerThan` (which subtracts both directions to get a signed comparator: `pathA.countCloserThan(this) - this.countCloserThan(pathA)`).

The epsilon at line 128 widens from `0.000000001` (1e-9) to `0.000001` (1e-6) — a flat absolute threshold appropriate for the project's typical coordinate magnitudes (0–100 world units).

A debug-only invariant assertion guards `closerThan` symmetry: for any non-coplanar pair `closerThan(A, B, obs) == -closerThan(B, A, obs)`. Cost zero in release builds.

## Acceptance Criteria

Each criterion is tagged with verification method.

- **AC-1** *(automated, unit)* — Given a pair of `Path` instances `A`, `B` constructed such that 2 of A's 4 vertices are on the observer side of B's plane and 2 are on the opposite side (the canonical orange-top-vs-blue-right shape), When `closerThan(A, B, observer)` is called, Then it returns a non-zero integer (specifically: positive iff A is closer per Newell semantics).

- **AC-2** *(automated, unit)* — Given a pair of `Path` instances that are genuinely coplanar (all vertices satisfy `|dot(n_other, v) - d_other| ≤ 1e-6`), When `closerThan` is called, Then it returns 0.

- **AC-3** *(automated, unit)* — Given a parameterised set of synthetic shared-edge / adjacent-face configurations covering: (a) two prisms sharing an X-face, (b) two prisms sharing a Y-face, (c) one prism's top face vs an adjacent prism's vertical side face, (d) two prisms of equal height adjacent on X, (e) two prisms of equal height adjacent on Y, When each pair is fed through `closerThan`, Then the returned sign matches the hand-computed expected value for the canonical `Point(-10.0, -10.0, 20.0)` observer.

- **AC-4** *(automated, integration)* — Given the four-building scene from `NodeIdSample` (heights `[3.0, 2.0, 1.5, 4.0]` at positions `Point(i*2.0, 1.0, 0.1)` with width=1.5, depth=1.5), When the scene is rendered through `DepthSorter.sort(...)` and the resulting command order is inspected, Then for every adjacent-building face pair where one face is closer to the observer than the other, the closer face's command appears later (drawn on top) in the command list.

- **AC-5** *(interactive, Paparazzi snapshot)* — Given the `NodeIdSample` rendered through `IsometricScene` at the standard test viewport, When the Paparazzi snapshot is captured, Then it shows no farther-face-over-closer-face artefact at any building boundary; specifically Factory's top face does not overlap HQ's right side at their shared corner. *Tool: Paparazzi 1.3.0 (currently pinned). Evidence: golden PNG checked in under `isometric-compose/src/test/snapshots/`.*

- **AC-6** *(automated, regression)* — Given the existing tile-grid Paparazzi snapshots and `DepthSorterTest` coplanar-prism / 3×3-grid / cycle-fallback cases, When the new fix is applied, Then all existing snapshots are byte-identical OR any pixel diff is investigated, explained, and re-baselined inside the same atomic `fix(...)` commit.

- **AC-7** *(automated, debug-only invariant)* — In debug builds (`assert` enabled), Given any pair of non-coplanar `Path` instances and an observer, When `closerThan(A, B, obs)` and `closerThan(B, A, obs)` are both evaluated, Then they return values whose sum is 0 (antisymmetric). In release builds the assertion is compiled out.

- **AC-8** *(manual, code review)* — `Path.countCloserThan` and `Path.closerThan` retain their existing public signatures and their documented semantics for non-shared-edge pairs (sign convention, observer parameter meaning, return type). No method renamed, no parameter added or removed.

## Non-Functional Requirements

- **Performance**: net-zero or net-positive. The replacement is `result > points.size / 2` (one comparison) vs `(result + result0) / points.size` (one addition + one division). Should be marginally faster. The optional `inline` modifier on `DepthSorter.checkDepthDependency` is included only if it lands without forcing other restructuring.
- **No new public API**. No new `RenderOptions` flag. No new dependency. No new test framework.
- **Test discipline**: kotlin.test + JUnit (existing infra). Paparazzi version stays at 1.3.0 — version bump is out of scope.
- **Build budget**: the new tests must run in CI within the existing test suite's wall-clock budget. The Paparazzi snapshot adds ~5–10 seconds; acceptable.
- **Numerical precision**: the loosened 1e-6 epsilon is appropriate for coordinates ≤100. Any future scenes with coordinates above ~1000 would warrant relative-epsilon scaling; documented as a known boundary in `## Out of Scope`.

## Edge Cases / Failure Modes

- **EC-1**: Two faces of the *same* prism (e.g., top face and side face). These are not adjacent in the depth-sort sense — `closerThan` is called on them only if their 2D projections overlap, which they never do for a single Prism. No behavioural change expected; AC-6 tile-grid snapshots verify.
- **EC-2**: A face with a vertex *exactly* on another face's plane (within 1e-6 epsilon). Counted in `result0` (the on-plane band). Strict-majority threshold ignores `result0` for the verdict — only `result` is compared to `points.size / 2`. This is intentional: on-plane vertices contribute no observer-side-or-not information.
- **EC-3**: A prism with a degenerate face (3 collinear vertices). `Path.init` already requires `points.size >= 3` and the predicate doesn't crash on degenerate planes (`n` would be the zero vector → all dot products are 0 → all vertices in `result0`); behaviour: returns 0, which means "no edge added," which is the correct conservative answer for an undefined plane.
- **EC-4**: A face with all vertices on the observer side of another plane. `result == points.size`, strict majority trivially holds, returns 1.
- **EC-5**: A face with all vertices on the *opposite* side. `result == 0`, no majority, returns 0. Combined in `closerThan` with the reverse direction (which would return 1), the signed comparator correctly identifies the other face as closer.
- **EC-6**: A scene where the new fix changes draw order but not visibly (e.g., two coincident faces). Pixel diff = 0; AC-6 holds without investigation.
- **EC-7**: A scene where the new fix changes draw order *and* changes visible pixels. Pixel diff > 0; AC-6 requires investigation. Expected outcome: the new render is correct; re-baseline atomically.
- **EC-8**: Coordinate magnitudes above ~1000 where the 1e-6 epsilon could become relatively too tight. Out of scope; documented for future relative-epsilon work.

## Affected Areas

- **`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`** — `countCloserThan` (line 139, integer division → strict majority threshold; line 128, epsilon 1e-9 → 1e-6). Optionally `closerThan` (KDoc update on the changed semantics).
- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`** — extend with shared-edge `closerThan` unit cases (AC-1, AC-2, AC-3).
- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`** — extend with the four-building integration case (AC-4) and the antisymmetry invariant test (AC-7). Existing coplanar/3×3 grid tests gate AC-6 regression.
- **`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`** *(NEW)* — currently no standalone test file exists for `IntersectionUtils.hasIntersection`. Add a thin file covering the adjacency cases that the bug exposes (even though the fix doesn't touch `IntersectionUtils` itself, this baseline lets future AABB-minimax work measure improvement).
- **`isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`** — add a Paparazzi snapshot for a minimal-repro scene (or a hand-built equivalent of NodeIdSample geometry without the InteractionSamplesActivity dependency, since `:isometric-compose` should not reach into `:app`). Captures AC-5.
- **Optionally `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`** — add `inline` modifier to `checkDepthDependency` only if it lands cleanly without restructuring.

Estimated total: **5 files** (4 in isometric-core, 1 in isometric-compose). Comfortably under the medium appetite ceiling.

## Dependencies / Sequencing Notes

- **Independent of `97416ba`** (the long-press hotfix). Different code path; no merge conflicts expected.
- **Coordinate with `feat/texture`** at eventual integration time — that branch carries texture-material-shader work which has its own painter changes (per intake research). Whoever ships first may need rebase work; this workflow can proceed independently.
- **No external dependency changes**. Paparazzi stays at 1.3.0; no Kotest addition. The fix uses only the existing Kotlin/JVM/JUnit stack.
- **Build-logic CC fix from earlier session** is unrelated and orthogonal — uncommitted in this working tree but does not gate this work.

## Questions Asked This Stage

20 questions across 5 rounds:

- **Round 1 (what does the fix do):** test geometry; activation in predicate chain; correctness definition for shared-edge case; diagnostic step intensity.
- **Round 2 (how does the fix behave):** fix shape (minimal vs Newell vs other); flag for emergency disable; cross-cutting test coverage; snapshot diff policy.
- **Round 3 (what does the fix look like):** primary fix location; perf tweak inclusion; visibility (CHANGELOG / KDoc); test layering.
- **Round 4 (what can go wrong):** threshold semantics; epsilon choice; defensive guards; telemetry on activation.
- **Round 5 (where are the boundaries):** out-of-scope confirmation; snapshot re-baseline commit policy; follow-up workflow spawning; accept gate.

## Answers Captured This Stage

See `po-answers.md` Rounds 1–5 for the full log. Headlines:

- **Diagnosis**: confirmed by sub-agent 1 — `closerThan` returns 0 due to integer division `(result + result0) / points.size = 2/4 = 0`.
- **Fix shape**: surgical replacement of integer-division collapse with strict-majority threshold (`result > points.size / 2`), plus epsilon loosening from 1e-9 to 1e-6.
- **No new flags**, no telemetry, no new dependencies.
- **Test layering**: `countCloserThan` unit + `closerThan` unit + `DepthSorter` integration + Paparazzi snapshot.
- **Snapshot policy**: strict zero-diff; re-baseline atomically with the fix commit.
- **Out of scope**: AABB minimax, full Newell minimax, polygon splitting, Shewchuk predicates, perf restructuring, version bumps.

## Out of Scope

Explicitly deferred to future workflows (no automatic spawning at handoff per Round 5):

- **AABB minimax pre-filter** in `IntersectionUtils.hasIntersection` (LeBron IsometricBlocks-style). Would prevent adjacent non-overlapping faces from reaching `closerThan` at all. Future robustness work.
- **Full Newell Z→X→Y minimax cascade** in `closerThan`. Larger algorithmic restructure; current threshold + epsilon fix is sufficient for the diagnosed and predicted cases.
- **Polygon splitting on cyclic dependencies**. Current Kahn fallback handles cycles by appending leftover items; no observed cycles in practice.
- **Shewchuk robust geometric predicates** with adaptive multi-precision arithmetic. Heavy adoption; out of scope for one-line fix.
- **Relative-epsilon scaling** (`eps = ulpFactor * |normal| * maxCoordinateMagnitude`). Future numerical-robustness work for scenes with coordinates ≫100.
- **Performance restructuring** beyond the optional `inline` of `checkDepthDependency`. Profiling, hot-path optimisation, `DoubleArray` migration: all separate.
- **Paparazzi version bump** (1.3.0 → 1.3.5 → 2.0.0-alpha). Orthogonal; do separately.
- **Kotest property-based testing infrastructure**. Adding a new test framework dep is out per medium appetite.
- **CHANGELOG manual edit**. git-cliff handles it from the conventional commit message.
- **Tile-grid sort algorithm changes**. The `a685620` pre-sort logic stays; this fix only changes how the predicate disambiguates pairs that pass `hasIntersection`.

## Definition of Done

1. All 8 acceptance criteria pass (AC-1 through AC-8).
2. CI green: existing test suite + new tests + Paparazzi snapshot suite all pass.
3. Single atomic conventional `fix(core):` commit on `feat/ws10-interaction-props` containing: source change(s), new tests, any re-baselined snapshot fixtures, and a body that explains the integer-division collapse and the threshold replacement (so git-cliff can publish it under Fixed at next release).
4. KDoc on `Path.countCloserThan` updated to explain the strict-majority semantics and the epsilon choice.
5. No new public API surface; signatures of `closerThan` and `countCloserThan` unchanged.
6. PO has approved the diff (PR review or chat sign-off).

## Verification Strategy

**Automated checks** (CI/test suite runs these unattended):

- `closerThan` and `countCloserThan` unit tests in `PathTest.kt` covering AC-1, AC-2, AC-3 (parameterised over the canonical adjacency pairings).
- `DepthSorter` integration test in `DepthSorterTest.kt` covering AC-4 (four-building scene → command-order inspection) and AC-7 (antisymmetry invariant in debug mode).
- Paparazzi snapshot test in `IsometricCanvasSnapshotTest.kt` covering AC-5 (NodeIdSample-equivalent rendered scene → byte-compare against golden PNG).
- Existing `DepthSorterTest` cases (coplanar adjacent prisms, 3×3 grid, broad-phase parity, cycle fallback) stay green → AC-6 regression gate.

**Interactive verification** (requires running the app):

- Platform: Android emulator (`emulator-5554`).
- Tool: Maestro flow — reuse `.ai/workflows/hotfix-long-press-node-id-sort/maestro-verify-nodeid.yaml` (or copy-modify for this workflow's screenshots dir) to navigate Sample app → Interaction API → Node ID tab.
- What to verify: the rendered scene matches the new Paparazzi golden — no Factory-top-over-HQ-right artefact at any building boundary.
- Evidence capture: screenshot pulled via `adb shell screencap` and saved under `.ai/workflows/depth-sort-shared-edge-overpaint/screenshots/`. Compared against the post-hotfix baseline (`hotfix-long-press-node-id-sort/screenshots/04-nodeid-fixed.png`) which still exhibits the bug.
- One-shot during verify; no permanent CI Maestro gate added in this workflow.

**Human-in-the-loop checks**:

- PR review of the diff to confirm: signature stability (AC-8), KDoc clarity, conventional commit message body.
- Eyeball the Paparazzi diff in the PR (if any snapshot changed) to confirm the new render is correct, not a subtle regression.

## Documentation Plan

Diátaxis classification — this fix touches an internal predicate with no public API surface, so the documentation footprint is small:

- **Reference (KDoc)** — Required.
  - **Type**: reference / KDoc on `Path.countCloserThan` and possibly `Path.closerThan`.
  - **Audience**: maintainers / contributors reading the source.
  - **Must cover**: the strict-majority threshold semantics, the epsilon choice and its 0–100 coordinate-range assumption, why integer division was wrong (one-sentence historical note).
  - **Must NOT cover**: end-user usage examples (the function is internal); algorithmic theory (link to Newell wiki, don't restate); details of the test scaffolding.
  - **Target location**: in-source KDoc on the changed functions in `Path.kt`.

- **Explanation (internal)** — Optional, recommended.
  - **Type**: explanation / internal architecture note.
  - **Audience**: maintainers extending the painter pipeline.
  - **Must cover**: why the painter uses centroid pre-sort + Kahn topological refinement + plane-side test; the integer-division collapse incident as a worked example; the explicit deferred items (AABB minimax, Newell cascade, etc.) and why they were not pursued in this fix.
  - **Must NOT cover**: how to use `IsometricScene` (that's user-facing how-to, not painter internals).
  - **Target location**: `docs/internal/explanations/depth-sort-painter-pipeline.md` (new file). Link from this workflow's `00-index.md` and from the changed function's KDoc.

- **How-to / Tutorial / README**: None. The fix is internal; no user-facing capability changes.

- **CHANGELOG**: Auto-generated by git-cliff. The conventional commit body must clearly state what changed (`fix(core): strict-majority threshold in countCloserThan resolves shared-edge sort ambiguity`) so the generated entry is informative without manual edit.

## Freshness Research

Combined from sub-agent 2's full report (see po-answers.md Round 2 timeline) plus intake-stage research. Key sources:

- Source: [Newell's algorithm — Wikipedia](https://en.wikipedia.org/wiki/Newell%27s_algorithm)
  Why it matters: documents the canonical 5-test cascade (Z, X, Y minimax → plane-side P-vs-Q → plane-side Q-vs-P → split). Confirms our predicate is roughly the plane-side test in isolation; the diagnosed bug is *within* that test, not from missing the cascade.
  Takeaway: the cascade is a future robustness layer; the immediate fix is to make the plane-side test return its real signal.

- Source: [shaunlebron.github.io/IsometricBlocks](https://shaunlebron.github.io/IsometricBlocks/)
  Why it matters: real-world isometric library with the same axis-aligned-prism domain. Uses AABB minimax instead of plane-side dot products entirely.
  Takeaway: alternative algorithm shape worth tracking as a future workflow; not adopted now to avoid scope expansion and breaking changes.

- Source: [Paparazzi changelog](https://cashapp.github.io/paparazzi/changelog/)
  Why it matters: project pinned at 1.3.0; current stable is 1.3.5; 2.0.0-alpha hides system UI by default (potentially breaking). Bump is orthogonal to this fix.
  Takeaway: do not combine this fix with a Paparazzi version bump.

- Source: [Kotlin sortedByDescending — stdlib docs](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/sorted-by-descending.html)
  Why it matters: confirms `sortedByDescending` is stable on JVM (TimSort), so equal-depth ties preserve insertion order deterministically. Our pre-sort fallback behaviour is reliable; the bug is upstream in the predicate not in the sort.
  Takeaway: no need to revisit the pre-sort; focus stays on `countCloserThan`.

- Source: [Shewchuk's robust predicates](https://www.cs.cmu.edu/~quake/robust.html)
  Why it matters: gold-standard reference for numerical-robustness in geometric predicates. Confirms the "fast IEEE check, exact-arithmetic fallback" pattern; we're not adopting it but it's the reference for any future work.
  Takeaway: relevant for the deferred relative-epsilon and AABB minimax workflows; not for this fix.

## Recommended Next Stage

- **Option A (default):** `/wf-plan depth-sort-shared-edge-overpaint` — the spec is a single coherent unit (one function fix in one file plus paired tests in 4 files; ≤5 files total) with one acceptance path and one delivery unit. Skip slicing; PLAN can directly draft the diff and verification steps.

- **Option B:** `/wf-slice depth-sort-shared-edge-overpaint` — only justified if you want to land the test scaffolding (new `IntersectionUtilsTest.kt`, parameterised `PathTest.kt` cases) in a separate prior commit before the actual fix. Marginally cleaner audit trail; slightly more ceremony than the appetite warrants.

- **Option C:** `/wf-intake depth-sort-shared-edge-overpaint` — not applicable; intake stands. Diagnosis and PO answers fully resolved the open intake questions.
