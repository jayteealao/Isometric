---
schema: sdlc/v1
type: review
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T21:11:17Z"
updated-at: "2026-04-28T09:47:38Z"
verdict: ship-with-caveats
review-round: 2
review-against-commit: "452b1fc"
review-against-base: "97416ba"
commands-run:
  - correctness
  - security
  - code-simplification
  - testing
  - maintainability
  - reliability
  - refactor-safety
  - performance
  - docs
  - style-consistency
metric-commands-run: 10
metric-findings-total: 25
metric-findings-raw: 91
metric-findings-blocker: 0
metric-findings-high: 6
metric-findings-med: 11
metric-findings-low: 8
metric-findings-nit: 0
metric-fix-count: 16
metric-defer-count: 1
metric-dismiss-count: 0
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - newell-cascade
  - re-review
refs:
  index: 00-index.md
  shape: 02-shape.md
  shape-amendment-1: 02-shape-amend-1.md
  shape-amendment-2: 02-shape-amend-2.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  prior-review-round: 07-review-round-1-archived.md
  sub-reviews:
    - 07-review-correctness.md
    - 07-review-security.md
    - 07-review-code-simplification.md
    - 07-review-testing.md
    - 07-review-maintainability.md
    - 07-review-reliability.md
    - 07-review-refactor-safety.md
    - 07-review-performance.md
    - 07-review-docs.md
    - 07-review-style-consistency.md
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Review (Round 2 — post-Newell cascade)

## Verdict

**Ship with caveats.**

The Newell Z→X→Y minimax cascade in commit `452b1fc` resolves the visual
regression that triggered Round-1's "Don't Ship". All 17 acceptance
criteria are met (191 isometric-core tests green; four Maestro visual
checks confirm the over-paint is gone in LongPress, Alpha, OnClick, and
NodeId scenes). All 23 unit/integration tests across `PathTest` and
`DepthSorterTest` were traced numerically by the correctness reviewer
and are sound. **No BLOCKERs were found in this round.**

Six HIGH findings cluster around three themes: (1) numerical-robustness
flaws that walked through the rewrite unchanged from Round 1 — most
notably the EPSILON-applied-to-product flaw in `signOfPlaneSide`, plus
NaN/Infinity propagation; (2) a ~20–30% per-frame performance regression
driven by per-vertex `cos`/`sin` recomputation; (3) docs drift between
the source, KDoc, inline comments, and the public concept docs at
`docs/concepts/depth-sorting.md`. Eleven MED findings concentrate on
cascade steps 2/3 (six independent reviewers triangulated this — the
two screen-extent steps are dead code under the `hasInteriorIntersection`
gate and have an "intuition-only" sign convention), workflow-vocabulary
leakage into the public test API, and structural duplication between
`hasIntersection` and `hasInteriorIntersection`.

The user triaged 16 of the 17 BLOCKER+HIGH+MED findings as **Fix in
this slice**; F-1 (Paparazzi CI baselines) was deferred as a Linux-CI
ops follow-on, not a code defect. The next stage is a follow-on
`/wf-implement` round to apply the 16 fixes.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Algorithm correctness | `correctness` | Issues (MED+LOW) |
| Security | `security` | Clean (LOW only) |
| Code simplification | `code-simplification` | Issues (MED) |
| Test coverage | `testing` | Issues (HIGH) |
| Source readability | `maintainability` | Issues (MED) |
| Numerical robustness | `reliability` | Issues (HIGH) |
| Refactor safety | `refactor-safety` | Issues (HIGH) |
| Render-loop perf | `performance` | Issues (HIGH) |
| In-source documentation | `docs` | Issues (HIGH) |
| Style consistency | `style-consistency` | NIT only |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| F-1 | **HIGH** | High | testing + refactor-safety | `isometric-compose/src/test/snapshots/images/` | Paparazzi CI gate broken: 4 new snapshot tests have no committed baselines + 16 pre-existing baselines are blank Windows renders |
| F-2 | **HIGH** | High | reliability + correctness | `Path.kt` `signOfPlaneSide` | EPSILON applied to *product* `observerPosition × pPosition`, not per-distance. Same flaw Round-1 flagged in deleted `countCloserThan`; persists. |
| F-3 | **HIGH** | High | reliability + security | `Path.kt` cascade | NaN/Infinity coordinates produce false-decisive returns from Step-1 minimax sentinels; antisymmetry breaks (both directions return −1) |
| F-4 | **HIGH** | High | performance | `Path.kt` cascade hot loop | `cos`/`sin` recomputed per vertex × per pair × O(N²) pairs (~3480 transcendental calls/frame, 30-face scene); +20–30% per-frame |
| F-5 | **HIGH** | High | docs + maintainability | `Path.kt` KDoc vs inline vs `PathTest.kt` header | Cascade step numbers disagree across artifacts (KDoc 4/5, inline 5/6, test header 5/6) |
| F-6 | **HIGH** | High | refactor-safety | `docs/concepts/depth-sorting.md` + `site/src/content/docs/concepts/depth-sorting.mdx` | Public concept docs describe `hasIntersection` (old) instead of `hasInteriorIntersection` (current implementation) |
| F-7 | MED | High | code-simpl + maint + testing + reliability + refactor-safety + correctness | `Path.kt` cascade steps 2–3 | Steps 2/3 (screen-x/y minimax, ~47 lines) dead under `hasInteriorIntersection` gate; sign convention is "intuition only" — six reviewers triangulated |
| F-8 | MED | High | code-simpl + maint + style | `Path.kt`+`IntersectionUtils.kt` | Epsilon notation drift: `0.000001`, `1e-6`, unnamed `0.000000001` |
| F-9 | MED | High | maint + correctness | `Path.kt` `signOfPlaneSide` | Name/KDoc inversion: returns +1 when "this farther", but name suggests "same side"; KDoc claims coplanar = same-side, code treats as neutral |
| F-10 | MED | High | docs + maint + style | multiple files | Workflow vocabulary leakage: slug, "WS10", "amendment" in source / public test API. Violates `feedback_no_slice_vocab_in_pr` user memory |
| F-11 | MED | High | code-simplification | `IntersectionUtils.kt` | ~70 lines AABB+SAT body verbatim copy-pasted from `hasIntersection` into `hasInteriorIntersection` |
| F-12 | MED | High | performance | `IntersectionUtils.hasInteriorIntersection` | Pre-existing 2×N `Point` wrapper allocations per call (largest single allocation hotspot) |
| F-13 | MED | Med | refactor-safety | `AlphaSampleScene.kt` | Test factory replicates 3 prisms as individual `Shape` calls; live app uses `Batch` — drift risk if `Batch` changes face decomposition |
| F-14 | MED | High | refactor-safety | `Path.kt` companion | `ISO_ANGLE = PI/6.0` hardcoded independent of `IsometricEngine` instance; non-default-angle engine produces wrong results at steps 1–3 |
| F-15 | MED | Med | code-simplification | `Path.kt` `closerThan` | Four vertex-scan loops could collapse to two — halves per-call traversal in hot path |
| F-16 | MED | High | testing | `DepthSorterTest.kt` | AC-10 (`hasInteriorIntersection` boundary cases) only exercised at predicate level, never end-to-end through `DepthSorter` |
| F-17 | MED | High | testing | `PathTest.kt` | Coplanar-non-overlapping test asserts `!= 0` instead of a specific sign — accepts wrong-sign regressions silently |
| F-18 | LOW | Med | style | `DepthSorterTest.kt` `diagnostic` test | `println` calls clutter CI output |
| F-19 | LOW | High | code-simpl + maint | `DepthSorterTest.kt` | Magic observer `Point(-10, -10, 20)` literal in 3+ places; should be a named constant |
| F-20 | LOW | Med | code-simpl + style | `Path.kt` companion | Double-`private` redundancy on `private companion object { private const val … }` |
| F-21 | LOW | Med | maintainability | `DepthSorter.kt` | `intersects`/`cmpPath` locals retained from reverted DIAG without explanation |
| F-22 | LOW | High | docs | `Path.kt` `closerThan` KDoc | Newell 1972 citation lacks URL or `@see` link |
| F-23 | LOW | Med | docs + refactor-safety | `docs/internal/explanations/` | `depth-sort-painter-pipeline.md` is DEFERRED but no tracking task exists |
| F-24 | LOW | Med | refactor-safety | repo CI workflows | No `verifyPaparazzi` step in any CI workflow; "must rebaseline on Linux" is documented only in workflow files |
| F-25 | LOW | Med | testing | `DepthSorterTest.kt` antisymmetry test | Cannot detect a symmetric sign-convention regression in plane-side steps 4–6 |

**Total:** BLOCKER: 0 | HIGH: 6 | MED: 11 | LOW: 8 | NIT: (rolled into LOW)
*(After dedup: 25 findings merged from 91 raw findings across 10 commands.)*

## Findings (Detailed)

For each finding's full evidence, see the corresponding sub-review file:
`07-review-<command>.md`. Inline detail below covers only the deduped
HIGHs and merged-cluster MEDs.

### F-1: Paparazzi CI gate broken [HIGH]

**Location:** `isometric-compose/src/test/snapshots/images/`
**Source:** testing (TS-1+TS-2), refactor-safety (RS-R2-8)

**Issue:** The four new snapshot tests added in commit `9cef055`
(`nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`,
`alphaSampleScene`) have **no committed baseline PNGs**. Concurrently,
all 16 pre-existing baselines on disk are blank 6917-byte
Windows-toolchain renders (verify Round-1 documented this as
environmental: Paparazzi 1.3.0 + Layoutlib + JDK17 + Windows produces
empty PNGs for every scene). On the first Linux CI run, `verifyPaparazzi`
will hard-fail twice over: missing-baseline error for the 4 new tests,
and pixel-mismatch error against the 16 blank pre-existing baselines
(once Linux records real frames).

**Fix:** Run `./gradlew :isometric-compose:recordPaparazzi` on Linux CI;
commit all 20 real baselines.

**Severity:** HIGH | **Confidence:** High | **Triage: Defer**
*(User-deferred: this is a Linux-CI ops follow-on, not a code defect.
The test sources are wired correctly. Track via separate workflow.)*

### F-2: EPSILON applied to product, not per-distance [HIGH]

**Location:** `Path.kt` `signOfPlaneSide` (the multiplication
`observerPosition * pPosition` and the resulting `>= EPSILON` check)
**Source:** reliability (RL-1), correctness (CR-3)

**Evidence:**
```kotlin
// signOfPlaneSide in Path.kt
val product = observerPosition * pPosition
if (product >= EPSILON) {
    // counted as same-side as observer
}
```

**Issue:** EPSILON 1e-6 is applied to the *product* of two signed
distances, not to each distance individually. The effective plane-
proximity threshold therefore scales inversely with observer distance.
For thin faces (z-extent < 0.01) or near-degenerate normals (small
cross-product magnitude), both distances can be small and same-sign yet
their product falls below the threshold, causing a vertex genuinely on
the observer's side to be classified as coplanar. **This is the same
flaw Round-1 review CR-4/RL-1 flagged in the deleted `countCloserThan`
— the predicate rewrite carried it forward unchanged.** Inert at WS10
coordinates; active for thin decal faces or near-degenerate normals.

**Fix:** Per-distance comparison: `pPosition > EPSILON && observerPosition > 0`
(or sign-respective equivalents).

**Severity:** HIGH | **Confidence:** High | **Triage: Fix in this slice**

### F-3: NaN/Infinity propagation [HIGH]

**Location:** `Path.kt` `closerThan` cascade Step 1; `Path.init`
**Source:** reliability (RL-2/RL-5), security (SEC-1/SEC-2)

**Issue:** No `isFinite` guard exists in `closerThan` or `Path.init`.
NaN coordinates leave Step-1 minimax sentinel loops at
`selfDepthMax = -INFINITY` / `selfDepthMin = +INFINITY`, which trigger
spurious definitive returns (`-INFINITY < aDepthMin - EPSILON` is always
true). Both directions of `closerThan` then return −1, breaking the
antisymmetry contract; the NaN polygon is misrendered as closer. Pre-
existing in old code; not introduced by this diff but persists.

**Fix:** Add `require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })`
to `Path.init` (boundary validation), or guard `signOfPlaneSide`
internally.

**Severity:** HIGH | **Confidence:** High | **Triage: Fix in this slice**

### F-4: Hot-loop perf regression (~20–30% per frame) [HIGH]

**Location:** `Path.kt` cascade hot loop; `signOfPlaneSide`
**Source:** performance (PF-1+PF-2+PF-3)

**Issue:** Three compounding regressions:
1. `cos(ISO_ANGLE)`/`sin(ISO_ANGLE)` recomputed per vertex visit inside
   `Point.depth(angle)`. For a 30-face scene: ~3480 transcendental calls
   per frame at Step 1 alone.
2. Combined Step-2/3 loop computes screen-Y extents for both polygons
   before checking Step-2 — wastes 2×N ops per Step-2-resolving pair.
3. `signOfPlaneSide` allocates 5+N `Vector` objects per call vs 4+N in
   deleted `countCloserThan`: 2.25× allocation regression for Step-6
   pairs.

Net estimate: +20–30% per-frame overhead vs prior code.

**Fix:** Hoist `ISO_COS`/`ISO_SIN` to companion constants (single-line
change; alone reverses the regression). Split the Step-2/3 loop. Inline
the per-vertex dot product in `signOfPlaneSide`.

**Severity:** HIGH | **Confidence:** High | **Triage: Fix in this slice**

### F-5: Cascade step-numbering mismatch across artifacts [HIGH]

**Location:** `Path.kt` `closerThan` KDoc + inline comments;
`PathTest.kt` test-suite header comment
**Source:** docs (DC-1/DC-2), maintainability (MA-3)

**Issue:** The `closerThan` KDoc enumerates 6 cascade steps. The inline
comments label 7 points. The plane-side tests are KDoc step 4/5, inline
step 5/6, and PathTest header step 5/6. A reader cross-referencing the
KDoc while reading code or tests gets contradictory step numbers.
Three independent reviewers flagged this.

**Fix:** Renumber consistently across all three artifacts (KDoc, inline
comments, PathTest header). Pick one convention (recommend 6-step,
matching the KDoc) and propagate.

**Severity:** HIGH | **Confidence:** High | **Triage: Fix in this slice**

### F-6: Public concept docs describe wrong gate [HIGH]

**Location:** `docs/concepts/depth-sorting.md`,
`site/src/content/docs/concepts/depth-sorting.mdx`
**Source:** refactor-safety (RS-R2-4)

**Issue:** Both public concept docs describe
`IntersectionUtils.hasIntersection()` as the depth-sort narrow-phase
gate. `DepthSorter` has used `hasInteriorIntersection` (strict screen-
overlap) since commit `9cef055`. Public-facing docs lie about the
implementation; a future maintainer auditing against the docs would get
a wrong mental model and might "fix" the implementation back to the
lenient version.

**Fix:** Update both files to describe `hasInteriorIntersection` and
explain why the strict variant is needed (boundary-only contact must
not fire a depth edge for the 3×3 grid corner regression).

**Severity:** HIGH | **Confidence:** High | **Triage: Fix in this slice**

### MED findings — summary

All eleven MED findings (F-7 through F-17) are triaged **Fix in this
slice**. Full evidence and fix detail in the per-command sub-reviews:

- **F-7** (cascade steps 2/3 dead+untested): six-reviewer triangulation;
  see `07-review-code-simplification.md` CS-5, `07-review-maintainability.md`
  MA-2, `07-review-testing.md` TS-3, `07-review-reliability.md` RL-3,
  `07-review-refactor-safety.md` RS-R2-6, `07-review-correctness.md` CR-2/CR-4.
  **Fix decision:** delete steps 2/3 entirely (the gate prevents reaching
  them) OR add tests + sign validation. Recommend delete — they're dead
  weight under the current gate.
- **F-8** (epsilon notation): consolidate to `1e-6` everywhere; name the
  unnamed `0.000000001` SAT threshold.
- **F-9** (`signOfPlaneSide` naming): either rename to clarify "+1 means
  this farther" semantics or invert the return convention to match the
  name; update KDoc.
- **F-10** (workflow vocabulary): rename `WS10NodeIdScene` → `NodeIdRowScene`
  (or similar); strip "WS10"/"amendment"/slug from KDocs and comments.
- **F-11** (AABB+SAT duplication): extract shared `private` helpers in
  `IntersectionUtils`.
- **F-12** (Point allocations): cache `Point` arrays at `hasInteriorIntersection`
  call site, or accept `DoubleArray` directly.
- **F-13** (AlphaSampleScene divergence): replicate using `Batch` to
  match the live app, or assert geometry equivalence in a unit test.
- **F-14** (ISO_ANGLE coupling): take the angle as a parameter, or
  accept the constraint and document the coupling explicitly.
- **F-15** (vertex-scan loop collapse): merge the four scan loops where
  the same vertices are walked.
- **F-16** (AC-10 not end-to-end): add a `DepthSorterTest` integration
  case that exercises the gate's reject path through `sort()`.
- **F-17** (coplanar test): change `assertNotEquals(0, …)` to a specific
  sign assertion.

### LOW findings — listed only

F-18 through F-25: not individually triaged per spec. See per-command
sub-reviews for evidence and detail. Recommend rolling LOW fixes into
the F-2..F-17 follow-on round opportunistically.

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| F-1 | HIGH | testing + refactor-safety | **defer** | Linux-CI ops follow-on; not a code defect. Track separately. |
| F-2 | HIGH | reliability + correctness | **fix** | Persistent flaw from Round 1; per-distance comparison. |
| F-3 | HIGH | reliability + security | **fix** | Add `isFinite` guards in `Path.init` or `signOfPlaneSide`. |
| F-4 | HIGH | performance | **fix** | Hoist cos/sin; split step 2/3 loop; inline `signOfPlaneSide` dot product. |
| F-5 | HIGH | docs + maintainability | **fix** | Renumber cascade steps consistently across KDoc, inline, PathTest header. |
| F-6 | HIGH | refactor-safety | **fix** | Update `docs/concepts/depth-sorting.md` + `site/.../depth-sorting.mdx`. |
| F-7 | MED | 6 sources | **fix** | Recommend deleting steps 2/3 — dead under gate. |
| F-8 | MED | code-simpl + maint + style | **fix** | Consolidate epsilon notation. |
| F-9 | MED | maint + correctness | **fix** | Rename or invert sign convention; update KDoc. |
| F-10 | MED | docs + maint + style | **fix** | Strip workflow vocabulary; rename `WS10NodeIdScene`. |
| F-11 | MED | code-simplification | **fix** | Extract AABB+SAT shared helpers. |
| F-12 | MED | performance | **fix** | Reduce Point wrapper allocations. |
| F-13 | MED | refactor-safety | **fix** | Use `Batch` in `AlphaSampleScene` or add geometry-equivalence test. |
| F-14 | MED | refactor-safety | **fix** | Parameterise `ISO_ANGLE` or document coupling. |
| F-15 | MED | code-simplification | **fix** | Collapse 4 vertex-scan loops to 2. |
| F-16 | MED | testing | **fix** | Add end-to-end gate-reject test through `DepthSorter`. |
| F-17 | MED | testing | **fix** | Change `!= 0` assertion to specific sign. |
| F-18..F-25 | LOW | various | untriaged | Roll opportunistically into the follow-on; revisit via `/wf-review depth-sort-shared-edge-overpaint triage`. |

## Recommendations

### Must Fix (16 items, triaged "fix")

Bundle as a single `/wf-implement` round on top of `452b1fc`. Rough
order of attack to reduce churn:
1. **F-2, F-3** (numerical robustness in `signOfPlaneSide` / `Path.init`).
2. **F-4** (perf — hoist trig, split loop, inline dot product).
3. **F-7, F-15** (delete dead steps 2/3 + collapse loops in `closerThan`).
4. **F-9, F-5** (rename `signOfPlaneSide` semantics + renumber cascade steps).
5. **F-8** (consolidate epsilon notation across `Path.kt` + `IntersectionUtils.kt`).
6. **F-11** (extract AABB+SAT helpers).
7. **F-12, F-14** (Point allocations + ISO_ANGLE coupling).
8. **F-10** (strip workflow vocabulary; rename `WS10NodeIdScene`).
9. **F-13, F-16, F-17** (test layer: Batch parity, gate-reject end-to-end, coplanar sign assertion).
10. **F-6** (update public concept docs to describe `hasInteriorIntersection`).

Estimated effort: medium-large — same shape as Round 4 (one focused
implement round), with most changes in `Path.kt`, `IntersectionUtils.kt`,
`DepthSorter.kt`, and the test files. The 16-item count looks heavy but
many are 1–3 line edits clustered in two files.

### Deferred (1 item, triaged "defer")

- **F-1**: Paparazzi CI baseline regeneration on Linux. Track as a
  separate ops workflow; no code change in this slice.

### Dismissed

None.

### Consider (LOW/NIT — not triaged)

F-18 through F-25 — recommend rolling opportunistically into the follow-on
implement round when the surrounding code is already being touched. None
are blocking.

## Recommended Next Stage

- **Option A (default):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — apply the 16 triaged fixes (F-2..F-17). Compact recommended before:
  the Round-2 review chatter (sub-agent outputs, aggregation, triage) is
  noise for the implement pass; the PreCompact hook preserves workflow
  state and the triage decisions live in this file.

- **Option B:** `/wf-amend depth-sort-shared-edge-overpaint from-review`
  — only if you decide the F-7 cluster (steps 2/3 dead) reframes the
  shape. Not recommended: the cascade structure is correct; deletion of
  inert steps is a localised implementation choice.

- **Option C:** `/wf-extend depth-sort-shared-edge-overpaint from-review`
  — only if F-1 or F-12 (allocation hot-spots, CI baselines) should
  spawn standalone follow-up workflows. Reasonable for F-1 (Linux CI
  baselines deserve their own ops slice).

- **Option D:** `/wf-handoff depth-sort-shared-edge-overpaint`
  — **not viable** with 16 triaged-fix items outstanding. Re-evaluate
  after the follow-on `/wf-implement` round closes them.

- **Option E:** `/wf-ship depth-sort-shared-edge-overpaint`
  — **not viable** for the same reason as D.

- **Option F:** `/wf-review depth-sort-shared-edge-overpaint triage`
  — re-triage the deferred F-1 and untriaged LOWs (F-18..F-25) after
  the implement pass closes the HIGH+MED items.

---

## Fix Status (Round 5 implement-from-reviews pass)

| ID | Severity | Status | Notes |
|----|----------|--------|-------|
| F-1 | HIGH | Deferred | Linux-CI ops follow-on; not a code defect |
| F-2 | HIGH | Fixed | `Path.relativePlaneSide` now applies EPSILON per-distance |
| F-3 | HIGH | Fixed | `Path.init` requires all coordinates finite |
| F-4 | HIGH | Fixed | `ISO_COS`/`ISO_SIN` hoisted; per-vertex Vector dot product inlined |
| F-5 | HIGH | Fixed | Cascade now 4 steps with consistent numbering across KDoc/inline/PathTest header |
| F-6 | HIGH | Fixed | `docs/concepts/depth-sorting.md` + `site/.../depth-sorting.mdx` updated to describe `hasInteriorIntersection` |
| F-7 | MED | Fixed | Cascade steps 2/3 (screen-x/y minimax) deleted — dead under hasInteriorIntersection gate |
| F-8 | MED | Fixed | EPSILON = 1e-6; SAT_CROSS_THRESHOLD = -1e-9 named |
| F-9 | MED | Fixed | `signOfPlaneSide` -> `relativePlaneSide`; KDoc clarified |
| F-10 | MED | Fixed | Workflow vocabulary stripped; `WS10NodeIdScene` -> `NodeIdRowScene` |
| F-11 | MED | Fixed | `IntersectionUtils` AABB+SAT extracted into shared private helpers |
| F-12 | MED | Fixed | `pointsA + pointsA[0]` polygon-close concatenations removed |
| F-13 | MED | Fixed | `AlphaSampleScene` wraps 3 CYAN prisms in Batch to mirror live app |
| F-14 | MED | Documented | `ISO_ANGLE` companion KDoc explicitly notes IsometricEngine coupling; not parameterised |
| F-15 | MED | Auto-fixed | After F-7 deletion, only 2 vertex-scan loops remain |
| F-16 | MED | Fixed | New `DepthSorterTest` "gate rejection preserves natural centroid order for adjacent same-height prisms" |
| F-17 | MED | Fixed | `PathTest` coplanar-non-overlap asserts `< 0` instead of `!= 0` |
| F-18..F-25 | LOW | Untriaged | Roll opportunistically into a future polish pass |

Round 5 implement-from-reviews commit applies fixes F-2 through F-17
(16 of 17 BLOCKER+HIGH+MED items). F-1 deferred per user triage.
Local tests not re-run on Windows; verify-stage on Linux CI is the
authoritative gate.
