---
schema: sdlc/v1
type: review
review-command: testing
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28"
updated-at: "2026-04-28"
reviewed-diff: "97416ba..HEAD"
merge-recommendation: REQUEST_CHANGES
tags: [testing, depth-sort, paparazzi]
refs:
  index: 00-index.md
  shape: 02-shape.md
  shape-amend-1: 02-shape-amend-1.md
  shape-amend-2: 02-shape-amend-2.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
---

# Testing Review: depth-sort-shared-edge-overpaint (cumulative, 97416ba..HEAD)

**Reviewed:** cumulative diff `97416ba..HEAD` on branch `feat/ws10-interaction-props`
**Final commit:** `452b1fc fix(depth-sort): replace closerThan with Newell Z->X->Y minimax cascade`
**Date:** 2026-04-28
**Reviewer:** Claude Code

---

## 0) Scope

**Test delta in diff:**
- `PathTest`: 10 → 12 (split AC-2 into coplanar-overlapping vs coplanar-non-overlapping; added AC-3 case (g) wall-vs-floor straddle)
- `DepthSorterTest`: 9 → 11 (added AC-12 LongPress full-scene + AC-13 Alpha full-scene)
- `IntersectionUtilsTest`: 3 → 8 (added 5 `hasInteriorIntersection` boundary cases)
- 4 new Compose scene factories: `AlphaSampleScene`, `LongPressGridScene`, `OnClickRowScene`, `WS10NodeIdScene`
- 4 new Paparazzi snapshot tests in `IsometricCanvasSnapshotTest` — **no baselines committed**

**Final state:** 191 isometric-core tests passing on Windows local.

---

## 1) Finding Table

| ID | Severity | Area | Finding |
|----|----------|------|---------|
| T-1 | HIGH | Paparazzi baselines (AC-14) | All 4 new snapshot tests (`nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`, `alphaSampleScene`) have no committed baselines. `verifyPaparazzi` will FAIL on first CI run. |
| T-2 | HIGH | Paparazzi baselines (AC-6 / existing 16) | All 16 committed baseline PNGs are blank 6917-byte Windows renders. Linux CI `verifyPaparazzi` will pixel-diff against blanks and FAIL for all 16 pre-existing tests too — not only the 4 new ones. |
| T-3 | MEDIUM | Cascade steps 2/3 untested as deciding step | No test exercises a pair where step 1 (iso-depth) is inconclusive AND step 2 (screen-X) or step 3 (screen-Y) decides. Step 3's inverted sign convention (`return 1` / `return -1` vs steps 1/2's `return -1` / `return 1`) cannot be regression-detected by the current suite. |
| T-4 | MEDIUM | AC-10 end-to-end gap | AC-10 says "no edge added for non-overlapping pairs regardless of `closerThan`." `IntersectionUtilsTest` tests the predicate in isolation; no `DepthSorterTest` case exercises the full pipeline where a screen-disjoint pair's gate rejection is the load-bearing assertion. |
| T-5 | LOW | Antisymmetry test — structural caveat | Under Newell ±1/0 return, `ab + ba == 0` is structurally guaranteed for steps 1–3 (range comparisons are symmetric). For steps 4–6 (plane-side) with a degenerate plane (zero normal), both directions return 0; sum = 0 satisfies the assertion but is not a genuine antisymmetry signal. The test cannot detect a sign-convention regression in steps 4–6 that happens to be symmetric. |
| T-6 | LOW | Coplanar non-overlapping: sign not asserted | `PathTest.closerThan resolves coplanar non-overlapping via Z-extent minimax` asserts `result != 0` but not the sign. Comment documents expected sign is negative; a `+1` regression would pass the test. |
| T-7 | LOW | Face identification brittleness | Integration tests identify faces via exact vertex coordinates (`abs(it.z - 2.1) < 1e-9`). A geometry refactor produces index=-1 failures (loud, not silent), but makes test failures hard to diagnose without reading the geometry setup. |
| T-8 | LOW | Degenerate geometry not covered | No test exercises 3 collinear vertices → zero normal → `signOfPlaneSide` all-zero path. Conservative 0 return is correct per EC-3 but is untested. |
| T-9 | LOW | Large-coordinate coverage absent | No test pins behaviour above the 1e-6 epsilon's 0–100 coordinate assumption (EC-8). |
| T-10 | LOW | Maestro flows not in CI | AC-14 (visual confirmation of 4 samples) was met only via hand-run Maestro on `emulator-5554`. No CI automation. Becomes a non-issue once real Paparazzi baselines (T-1) are recorded. |

---

## 2) Detailed Findings

### T-1 — Missing Paparazzi baselines for 4 new snapshot tests [HIGH]

**Evidence:** `ls isometric-compose/src/test/snapshots/images/` lists 16 files, none matching
`nodeIdRowScene`, `onClickRowScene`, `longPressGridScene`, or `alphaSampleScene`. The four
snapshot tests in `IsometricCanvasSnapshotTest` reference these factories (all of which exist
and compile correctly), but no baseline PNG exists for any of them.

**Impact:** `./gradlew :isometric-compose:verifyPaparazzi` will fail at first CI run because
Paparazzi 1.3.0 `verifyPaparazzi` requires a committed baseline to exist before it can
compare. The verify report (round 3) explicitly defers this to "Linux CI per the verify
report (Windows + JDK17 produces blank 6917-byte PNGs)" — that is the correct environmental
explanation, but it does not unblock CI. `verifyPaparazzi` is a hard failure, not a warning.

**Severity: HIGH** — not a blocker on the algorithmic correctness of the fix (AC-1 through
AC-13 are fully met), but a blocker on CI passing `verifyPaparazzi`. The branch cannot land
with this CI task required unless the task is excluded or baselines are recorded first.

**Recommended action:** Before merge, run `./gradlew :isometric-compose:recordPaparazzi` on
Linux (not Windows) and commit the four resulting baselines in an atomic follow-up commit.
Alternatively: exclude `verifyPaparazzi` from the required CI gates for this PR, land the
code, then add baselines in a second commit.

---

### T-2 — All 16 committed Paparazzi baselines are blank [HIGH]

**Evidence:** `wc -c isometric-compose/src/test/snapshots/images/*.png` shows total = 110672 = 16 × 6917.
Every committed PNG is 6917 bytes — the blank Windows/JDK17 render documented in round-1
verify. This includes pre-existing scenes (`pyramid`, `cylinder`, `sampleOne`, etc.) that
this workflow did not touch.

**Impact:** When Linux CI runs `verifyPaparazzi`, it renders real Compose scenes and compares
against these 6917-byte blanks. Every one of the 16 pre-existing tests will fail with a pixel
diff. This is pre-existing technical debt, not introduced by this workflow — but this workflow
committed 4 more snapshot tests on top of the broken baseline set without flagging the
accumulated debt as a CI gate risk.

**Severity: HIGH** — because the 16 existing tests are also broken, fixing T-1 alone (adding
4 new baselines) is insufficient. All 16 must be replaced with real Linux renders in one
atomic `recordPaparazzi` + commit operation.

**Recommended action:** Track this as a paired item with T-1. A single Linux `recordPaparazzi`
run replaces all 16 blanks and adds the 4 new baselines simultaneously. Until that commit
lands, `verifyPaparazzi` cannot be a required CI gate for any snapshot test in this module.

---

### T-3 — Cascade steps 2 and 3 (screen-X / screen-Y) are not exercised as the deciding step [MEDIUM]

**What the implementation does:**
`Path.closerThan` implements a 6-step Newell cascade. Steps 2 and 3 test iso screen-X and
screen-Y extents for disjointness. Critically, step 3's sign convention is **inverted** from
steps 1 and 2:

```kotlin
// Steps 1 and 2: "self entirely smaller metric → self closer → self draws after → -1"
if (selfDepthMax < aDepthMin - EPSILON) return -1
if (selfScreenXMax < aScreenXMin - EPSILON) return -1

// Step 3: INVERTED — smaller screen-Y means "lower on screen = farther into scene"
if (selfScreenYMax < aScreenYMin - EPSILON) return 1    // self entirely below → self FARTHER
if (aScreenYMax < selfScreenYMin - EPSILON) return -1   // self entirely above → self CLOSER
```

The KDoc comment explains this inversion correctly. However, no `PathTest` case is
constructed such that:
- step 1 (iso-depth range) does NOT fire (both ranges overlap), AND
- step 2 (screen-X range) does NOT fire, AND
- step 3 (screen-Y range) does fire as the deciding step.

Every `PathTest` case terminates at step 1 or at steps 5/6 (plane-side). The `closerThan
resolves coplanar non-overlapping via Z-extent minimax` test is named for step 1 but also
causes x-disjoint extents — step 1 fires first.

**Risk:** A developer could silently swap the two returns in step 3 (changing `return 1` to
`return -1` and vice versa), and no currently-passing test would detect the regression. The
inversion is the unusual part of the implementation and is the most likely place for a future
maintenance error.

**Recommended action:** Add one `PathTest` case where two faces have overlapping iso-depth
ranges but disjoint screen-Y extents. Construct so that step 1 cannot fire (e.g., two
horizontal faces at the same world-z but different world-x+y combinations that produce
overlapping iso-depth ranges). Assert the expected sign to pin step 3's convention.

---

### T-4 — AC-10 not exercised at the pipeline level [MEDIUM]

**AC-10 specification (02-shape-amend-1.md):**
> Given two prism faces whose 2D iso-projected polygons do NOT overlap, when
> `DepthSorter.checkDepthDependency` is called for the pair, then no topological edge is
> added regardless of what `closerThan` returns.

**What is tested:** `IntersectionUtilsTest` has five `hasInteriorIntersection` cases
(shared-edge → false, shared-vertex → false, disjoint → false, interior overlap → true,
strict-containment → true). These correctly pin the predicate.

**What is missing:** `DepthSorter.checkDepthDependency` is private. No `DepthSorterTest` case
verifies that a scene containing screen-disjoint face pairs produces the same command order
with or without `closerThan` being called for those pairs. The gate is tested only in isolation.

**Risk:** If `checkDepthDependency` is refactored to replace `hasInteriorIntersection` with
`hasIntersection` (the more permissive predicate), no test would catch the regression
directly at the integration level. AC-9's `>= 3` assertion is a downstream proxy but it
asserts relative position, not the gate's behaviour specifically.

**Recommended action (optional):** Add a `DepthSorterTest` case with two screen-disjoint
unit prisms (world-separated so iso projections cannot overlap) and assert the command order
is unchanged whether `enableBroadPhaseSort` is true or false — i.e., no spurious edge is
added. This would directly gate the "no false positives from the gate" invariant.

---

### T-5 — Antisymmetry test: structurally sound but cannot detect step 4–6 sign regression [LOW]

The antisymmetry test (`closerThan is antisymmetric for representative non-coplanar pairs`)
asserts `ab + ba == 0` for four pairs. Under Newell ±1/0 return, this property holds
structurally for all cascade paths:

- Steps 1–3 (range comparisons): if self has a strictly smaller range than pathA, self returns
  -1; the reverse call has pathA with a strictly smaller range, so it returns -1 too from
  step 1's pathA perspective, which means from self's perspective +1. Sum = 0.
- Steps 4–6 (plane-side): if all of self's vertices are on the opposite side, returns +1;
  reverse call returns -1 (self's vertices all-opposite → pathA's vertices are the subject,
  returning -(signOfPlaneSide) = -(-1) = ... need careful reading). The code inverts with
  `return -sRev`. Sum = 0 by construction.

**Caveat:** For a degenerate plane (3 collinear vertices → zero cross-product → zero normal
vector), `signOfPlaneSide` returns 0 for both directions (all `signed = 0 * 0 = 0`, no
`anySameSide` nor `anyOppositeSide` set). Both `sFwd` and `sRev` are 0. Fall-through returns 0
for both directions. Sum = 0. The antisymmetry assertion is satisfied, but the test cannot
distinguish this "silently degenerate" path from "genuine ambiguity." No action needed; note
for awareness.

---

### T-6 — Coplanar non-overlapping test: sign assertion too weak [LOW]

```kotlin
// isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt
@Test
fun `closerThan resolves coplanar non-overlapping via Z-extent minimax`() {
    ...
    assertTrue(
        result != 0,
        "Coplanar non-overlapping faces must produce a non-zero ordering signal ..."
    )
}
```

The inline comment documents the expected sign: "aTop has smaller depth → aTop is closer →
self=aTop is closer → closerThan returns negative." The assertion allows either sign. A sign
regression (returning +1 instead of -1) would change how coplanar non-overlapping faces are
ordered by DepthSorter, but the test would still pass.

**Risk:** Low. For coplanar non-overlapping faces, neither face occludes the other, so the
ordering is aesthetically irrelevant. But the test no longer pins "what the code does" vs
"what the algorithm should do."

**Recommended fix:** Change `assertTrue(result != 0, ...)` to `assertTrue(result < 0,
"coplanar non-overlapping: aTop (x=[0,1]) is closer than bTop (x=[2,3]) via iso-depth; got $result")`.

---

### T-7 — Face identification brittleness [LOW]

Integration tests in `DepthSorterTest` identify faces via exact vertex coordinate checks:
```kotlin
pts.all { abs(it.z - 2.1) < 1e-9 } && pts.all { it.x in 2.0..3.5 }
```
If prism vertex generation changes (e.g., floating-point base offsets, vertex ordering),
`indexOfFirst` returns -1 and the subsequent `assertTrue(index >= 0)` fails loudly with the
locator description. This is the correct loud-failure mode (not silent), but the error message
does not explain what changed. Risk: future maintainer confusion, not silent test pass.

---

### T-8 — Degenerate geometry (collinear vertices) untested [LOW]

No test constructs a `Path` with 3 collinear vertices and calls `closerThan` with it. Per
EC-3 in `02-shape.md`, the expected behaviour is 0 (conservative no-edge). `signOfPlaneSide`
computes `n = AB × AC`; if A, B, C are collinear, `n = (0,0,0)`, all dot products are 0, and
the function returns 0. This is correct but untested. Only relevant for custom shapes passing
collinear vertices — no built-in shape generator produces them.

---

### T-9 — Large-coordinate coverage absent [LOW]

No test exercises coordinates > 100. The 1e-6 epsilon is documented as appropriate for the
0–100 coordinate range (EC-8). No regression marker would fire if epsilon were accidentally
tightened or if a scene with coordinates ~1000 were introduced.

---

### T-10 — Maestro flows not in CI [LOW]

AC-14 (visual: no missing vertical faces in 4 samples) was met exclusively via hand-run
Maestro on `emulator-5554`. Flows `01-onclick.yaml`, `02-longpress.yaml`, `03-alpha.yaml`
exist but are not wired to any Gradle or CI task. Once T-1 is resolved (Linux Paparazzi
baselines committed), `verifyPaparazzi` will serve as the automated visual regression gate
and Maestro becomes a supplementary manual check. Until T-1 is resolved, Maestro is the
only visual gate — and it is manual-only.

---

## 3) AC Coverage Map

| AC | Automated test present | Pre-fix code would fail? | Notes |
|----|----------------------|--------------------------|-------|
| AC-1 | `PathTest.closerThan returns nonzero for hq-right vs factory-top shared-edge case` | Yes — returned 0 | Correctly pins original bug |
| AC-2a | `PathTest.closerThan returns zero for coplanar overlapping faces` | No | Genuinely-ambiguous coplanar case; behaviour unchanged |
| AC-2b | `PathTest.closerThan resolves coplanar non-overlapping via Z-extent minimax` | Yes — pre-Newell returned 0 | Sign assertion too weak (T-6) |
| AC-3 (a–f) | Four PathTest cases | Yes for cases triggering integer-division collapse | Steps 2/3 not exercised as deciding step (T-3) |
| AC-3 (g) | `PathTest.closerThan resolves wall-vs-floor straddle via plane-side test` | Yes — pre-Newell returned 0 | Sign assertion correct (`result < 0`) |
| AC-4 | `DepthSorterTest.WS10 NodeIdSample four buildings render in correct front-to-back order` | Yes | |
| AC-5/AC-14 | `IsometricCanvasSnapshotTest.nodeIdRowScene` et al. | Baselines missing (T-1); visual via Maestro only | CI gate deferred |
| AC-6 | 168 pre-existing tests; existing Paparazzi baselines all blank (T-2) | N/A | Snapshot-pixel side blocked by T-2 |
| AC-7 | `DepthSorterTest.closerThan is antisymmetric for representative non-coplanar pairs` | Structurally no (see T-5) | |
| AC-8 | Source diff (manual) | N/A | Not an automated test |
| AC-9 | `DepthSorterTest.WS10 LongPress 3x3 grid back-right cube vertical faces are not drawn first` | Yes — positions were 0–2 pre-fix | |
| AC-10 | `IntersectionUtilsTest` 5 cases | Yes for hasInteriorIntersection unit | No pipeline-level test (T-4) |
| AC-12 | `DepthSorterTest.WS10 LongPress full scene back-right cube vertical faces draw after ground top` | Yes — ground drew over wall pre-fix | |
| AC-13 | `DepthSorterTest.WS10 Alpha full scene each CYAN prism vertical faces draw after ground top` | Yes — same mechanism | |

---

## 4) Positive Observations

- Every regression class bug (round 1 integer-division, round 2 over-aggressive edges,
  round 3 cmpPath=0 wall-vs-floor) has a dedicated integration test that would fail on
  the pre-fix code. The regression coverage is comprehensive for the diagnosed failure modes.
- `DepthSorterTest` AC-12 and AC-13 correctly include the ground prism in the scene
  (the fix for the AC-9 insufficiency identified in amendment 2: the unit test without
  ground missed the cmpPath=0 fallback).
- `IntersectionUtilsTest` now covers all five meaningful boundary conditions for
  `hasInteriorIntersection`. The regression marker that pins the lenient/strict divergence
  (the `hasIntersection` contract preserved note) is a good practice.
- `PathTest` case (g) wall-vs-floor straddle correctly asserts `result < 0` (wall closer)
  — the sign assertion is precise, not just `!= 0`.
- All tests are deterministic (pure math, no I/O) and pass reliably on Windows local.
- The four scene factories are cleanly separated under `scenes/` with reuse-oriented KDoc
  noting geometry sync requirements with the live `InteractionSamplesActivity`.

---

## 5) Summary Verdict

The unit and integration test suite is **technically sound for algorithmic correctness**:
all three regression classes have dedicated tests that would fail on pre-fix code, and the
191 green tests on Windows local provide a high-confidence signal. The primary actionable
gaps are:

1. **T-1 (HIGH)** — 4 new Paparazzi snapshot tests have no committed baselines; CI
   `verifyPaparazzi` will fail. Blocking for that CI gate.
2. **T-2 (HIGH)** — All 16 existing Paparazzi baselines are blank Windows renders; Linux CI
   will fail `verifyPaparazzi` for ALL snapshot tests, not only the 4 new ones.
3. **T-3 (MEDIUM)** — Cascade steps 2 and 3 (screen-X / screen-Y) have no test that
   exercises them as the deciding step; step 3's inverted sign convention is undetectable
   by the current suite.
4. **T-4 (MEDIUM)** — AC-10's "no edge added for non-overlapping pairs" is tested at the
   predicate level only, not at the DepthSorter pipeline level.

Findings T-5 through T-10 are low-severity and do not block shipping the algorithmic fix.

**Merge recommendation:** REQUEST_CHANGES on T-1 + T-2 (must resolve before merging if
`verifyPaparazzi` is a required CI gate); SUGGEST addressing T-3 and T-6 before merge as
they are low-effort and close observable regression holes. T-4 and T-7 through T-10 are
acceptable to defer.

---

*Review completed: 2026-04-28*
*Diff reviewed: 97416ba..HEAD (4 rounds of depth-sort fixes, final Newell cascade commit 452b1fc)*
*Workflow: [depth-sort-shared-edge-overpaint](00-index.md)*
