---
schema: sdlc/v1
type: review
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T21:11:17Z"
updated-at: "2026-04-26T21:11:17Z"
verdict: dont-ship
commands-run:
  - correctness
  - security
  - code-simplification
  - testing
  - maintainability
  - reliability
  - performance
  - refactor-safety
  - docs
  - style-consistency
metric-commands-run: 10
metric-findings-total: 25
metric-findings-raw: 42
metric-findings-blocker: 2
metric-findings-high: 3
metric-findings-med: 7
metric-findings-low: 7
metric-findings-nit: 6
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - bug
  - numerical-robustness
  - regression
refs:
  index: 00-index.md
  slice-def: 03-slice-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  sub-reviews:
    - 07-review-correctness.md
    - 07-review-security.md
    - 07-review-code-simplification.md
    - 07-review-testing.md
    - 07-review-maintainability.md
    - 07-review-reliability.md
    - 07-review-performance.md
    - 07-review-refactor-safety.md
    - 07-review-docs.md
    - 07-review-style-consistency.md
next-command: wf-implement
next-invocation: "/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint"
---

# Review

## Verdict

**Don't Ship.**

The fix correctly resolves the original WS10 NodeIdSample bug (verified at
the unit level by 6 of 8 acceptance criteria), but introduces a new
regression: the permissive `result > 0` threshold over-adds a
wrong-direction dependency edge for tall faces straddling a farther
horizontal plane. The correctness reviewer traced the failure through
`OnClickSample` geometry vertex-by-vertex and identified the exact
mechanism — the same geometric pattern that drives the user-observed
"yellow box has missing faces/sides" symptom in the LongPress sample.
The testing reviewer flagged the complementary coverage gap (no test
exercises dynamic height-change adjacency, even though that is the
geometry the regression manifests in). Both BLOCKERS must be addressed
before merge.

A cluster of HIGH/MED findings around the predicate's epsilon semantics
(`>= 1e-6` is a product threshold, not a per-distance threshold) and a
vacuous antisymmetry test were independently surfaced by 3-4 reviewers
each — strong triangulation that these are real, not noise.

## Domain Coverage

| Domain | Command | Status |
|---|---|---|
| Algorithm correctness | `correctness` | **Blockers** |
| Test coverage | `testing` | **Blockers** |
| Numerical robustness | `reliability` | **High** |
| API/contract change | `refactor-safety` | Clean |
| Source readability | `maintainability` | Issues (MED) |
| In-source documentation | `docs` | Issues (MED) |
| Render-loop perf | `performance` | Issues (MED) |
| Code simplification | `code-simplification` | NIT only |
| Security | `security` | Clean |
| Style consistency | `style-consistency` | NIT only |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|---|---|---|---|---|---|
| CR-1 | **BLOCKER** | High | correctness | `Path.kt:131-145` | Permissive `result > 0` over-adds wrong-direction edge for tall faces straddling a farther horizontal plane (LongPress yellow-box regression) |
| TS-1 | **BLOCKER** | High | testing | (test gap) | No test covers dynamic height-change adjacency (`height = if (isSelected) 2.0 else 1.0`) — exact regression structural pattern |
| RL-1 | **HIGH** | High | reliability + correctness + docs + maint + style | `Path.kt:140` | Epsilon `>= 1e-6` is on the *product* `observerPosition * pPosition`, not on each individual signed distance. KDoc misleads. (Merges CR-4, DC-1, partial ST-1, partial MA-3.) |
| TS-2 | **HIGH** | High | testing + correctness + reliability | `DepthSorterTest.kt` (antisymmetry test) | Antisymmetry test (`a + b == 0`) is vacuous — binary 0/1 return forces sum to 0 by construction; would have passed even with pre-fix bug. (Merges CR-5, RL-5.) |
| TS-3 | **HIGH** | High | testing | `IntersectionUtilsTest.kt` | Plan's shared-edge-only `hasIntersection` case was deferred; this is the gate that fires `closerThan`. If it returns false for screen-projected shared edges, the entire fix is silently bypassed. |
| MA-1 | MED | High | maintainability | `Path.kt:103-115` | KDoc conflates contract with bug history; omits coplanar / observer-on-plane edge cases and the invariant `closerThan` relies on |
| MA-2 | MED | Med | maintainability | `Path.kt:117` | `countCloserThan` name implies cardinality but returns binary {0,1}; the implicit contract with `closerThan`'s subtraction is fragile and undocumented. Recommend rename or `Boolean` return. |
| PF-1 | MED | High | performance | `Path.kt:131-145` | Hot loop allocates N `Vector` per call and lacks early-exit. Inline OP arithmetic to 3 doubles + return on first hit eliminates allocations and halves expected iterations. |
| RL-2 | MED | Med | reliability | (analytical) | **Deferred — superseded.** Alternative regression theory (threshold too tight → tie → fallback to wrong order). CR-1's concrete vertex trace contradicts: failure is over-aggressive, not too tight. Recorded for historical context. |
| DC-2 | MED | High | docs | `Path.kt:95-98` | `closerThan`'s public-ish KDoc was not updated; never explains the permissive threshold, the subtraction pattern, or shared-edge rationale |
| TS-4 | MED | High | testing | (coverage gap) | No integration test for dynamic height-change scenes through `DepthSorter` (folds into TS-1's fix scope) |
| TS-5 | MED | Med | testing | (coverage gap) | No test pins the 1e-6 epsilon at its boundary; future tightening or widening goes unnoticed |
| SEC-1 | LOW | Med | security | `Path.kt:140` | NaN/Infinity in vertex coordinates silently pass/fail the product comparison. Pre-existing — not introduced by this diff. |
| MA-3 | LOW | High | maintainability + style | `Path.kt:140` | `0.000001` is a magic literal; sibling files use `1e-6` scientific notation and named constants would expose the coordinate-range dependency. (Overlaps ST-1 NIT.) |
| MA-4 | LOW | High | maintainability | `Path.kt:129, 135` | `observerPosition` is a signed plane-distance, not a position; renaming to `observerPlaneDist` / `vertexPlaneDist` would make the sign-agreement test self-explanatory |
| PF-3 | LOW | Med | performance | (upstream) | Pre-existing `Point` boxing in `hasIntersection` upstream is a larger allocation hotspot — not this diff |
| RL-4 | LOW | High | reliability | `Path.kt:KDoc` | KDoc claims 0..100 coordinate range works but FP error product ~2.2e-4 dominates 1e-6 above ~10. Only validated for 0..10 range. |
| DC-3 | LOW | High | docs + security | `Path.kt:115` | Internal workflow slug `depth-sort-shared-edge-overpaint` in KDoc is dead reference (no path, not navigable). (Merges SEC-2 NIT.) |
| RS-LOW | LOW | Med | refactor-safety | (test gap) | Missing degenerate-coplanar unit test |
| SEC-2 | NIT | High | security | `Path.kt:115` | Workflow vocabulary in source code KDoc — see DC-3 above (merged) |
| CS-2 | NIT | High | code-simplification | `Path.kt:103-115` | KDoc history paragraph is changelog content in private function; trim to one sentence |
| CS-4 | NIT | Med | code-simplification | `WS10NodeIdScene.kt` | Hardcoded geometry duplicates `NodeIdSample` (intentional — wrong module direction to share); a one-line drift-warning comment is all that's missing |
| MA-5 | NIT | Med | maintainability | `Path.kt:103-115` | KDoc doesn't justify "any vertex" over strict-majority or all-but-one alternatives; future tuner can't distinguish minimal fix from deliberate choice |
| DC-4 | NIT | High | docs | `02-shape.md` | Spec says "strict majority"; implementation chose "any vertex". Spec now contradicts source. |
| DC-5 | NIT | Med | docs | (missing file) | `docs/internal/explanations/depth-sort-painter-pipeline.md` was never created (shape stage marked it optional/recommended) |
| ST-2 | NIT | Med | style-consistency | `WS10NodeIdScene.kt` | `WS10` milestone prefix on a scene-factory file (only prior precedent is `WS6EscapeHatchesTest.kt`); functionally fine |

**Total:** BLOCKER: **2** | HIGH: **3** | MED: **7** | LOW: **7** | NIT: **6** = **25 deduplicated** (from 42 raw across 10 commands).

## Findings (Detailed)

### CR-1: Permissive threshold over-adds wrong-direction edges [BLOCKER]

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt:131-145`
**Source:** `correctness`

**Evidence:**

Numerical trace from the correctness reviewer for the OnClickSample geometry — `Selected` (a long-pressed shape, height bumped from 1.0 to 2.0) adjacent to `Neighbor` (height 1.0):

```
Selected.right face (vertical, x = constant, z ∈ [0.1, 2.1]):
  4 vertices, 2 above and 2 below Neighbor.top's z = 1.1 plane.
Neighbor.top face (horizontal at z = 1.1):
  4 vertices, all on its own plane.

countCloserThan(Selected.right, Neighbor.top):
  observer is well above z = 1.1 → observerPosition > 0
  2 of 4 vertices have pPosition > 0 (above the plane, same side as observer)
  result = 2  →  return 1   ← spurious "Selected.right is closer" vote
countCloserThan(Neighbor.top, Selected.right):
  by symmetry result = 0   →  return 0
closerThan(Selected.right, Neighbor.top) = 0 - 1 = -1
DepthSorter then forces Neighbor.top to be drawn AFTER Selected.right
→ Neighbor.top paints over Selected.right's contributing pixels
→ Yellow box (Selected) loses its right wall.

2D projections confirm hasIntersection fires:
  Selected.right x ∈ [182, 243]  vs  Neighbor.top x ∈ [212, 333]
```

**Issue:** The permissive `result > 0` threshold treats *any* vertex on the
observer side as evidence that the entire face is closer. For a tall vertical
face that straddles a horizontal face's plane, this is wrong — half the
vertical face is above the horizontal, half is below. Neither face is
unambiguously closer; the predicate should return 0 (no edge) and let
the centroid pre-sort and `hasIntersection` broad-phase resolve it.

**Fix:** Require both `result > 0` AND `resultOpposite == 0` (no vertex
on the *opposite* side of the plane). This restores the original
implementation's "all vertices observer-side" intent that was lost when
`result0` was removed. The hq-right/factory-top WS10 case still passes
because *all four* of factory-top's vertices are on the observer side
of hq-right's plane (factory-top is geometrically entirely above hq-right's
right face's plane in observer space).

```kotlin
// Proposed
var result = 0
var resultOpposite = 0
for (point in points) {
    val pPosition = ...
    if (observerPosition * pPosition >= 1e-6) result++
    else if (observerPosition * pPosition <= -1e-6) resultOpposite++
}
return if (result > 0 && resultOpposite == 0) 1 else 0
```

This was effectively the old algorithm's intent (the `(result + result0) / points.size`
integer-division fraction equalled 1 only when every vertex was observer-side
or coplanar) — but expressed correctly without the integer-division collapse.

**Severity:** BLOCKER | **Confidence:** High

---

### TS-1: No test exercises dynamic height-change adjacency [BLOCKER]

**Location:** `isometric-core/src/test/kotlin/.../PathTest.kt`, `DepthSorterTest.kt`
**Source:** `testing`

**Evidence:** The `WS10NodeIdScene` factory and the `DepthSorterTest.WS10
NodeIdSample four buildings render in correct front-to-back order` test
both lock the heights at static values (3.0, 2.0, 1.5, 4.0). The actual
regression manifests in `OnClickSample` and `LongPressSample`, where
`height = if (isSelected) 2.0 else 1.0` — a height of 2 next to a
neighbour height of 1 is precisely the geometry CR-1 traces through.
None of the new PathTest cases exercises this configuration.

**Issue:** The slice's test layering (closerThan unit + DepthSorter
integration + Paparazzi snapshot) was scoped against the originally
diagnosed bug case but did not generalise to other adjacent-prism
configurations the fix could affect. A green test suite while a visual
regression exists is the worst kind of false confidence.

**Fix:** Add at minimum:

1. `PathTest.\`closerThan returns zero for tall vertical face straddling shorter neighbour's top\`()` — reproduces the OnClickSample geometry (Selected height=2 vs Neighbor height=1) and asserts `closerThan == 0` (or whatever the corrected predicate returns) so neither face is forced ahead of the other.
2. `DepthSorterTest.\`OnClickSample selection toggle preserves correct face ordering\`()` — builds the scene with one shape selected, runs `DepthSorter`, asserts that for every adjacent face pair the closer face's command appears later. Repeat with selection off. Pin both states.
3. Optionally a Paparazzi snapshot of the LongPress sample with one shape selected.

**Severity:** BLOCKER | **Confidence:** High

---

### RL-1: Epsilon is on the product, not the per-distance [HIGH]

**Location:** `Path.kt:140` (and KDoc lines 103-115, 137-138)
**Source:** `reliability` (HIGH) + `correctness` CR-4 (MED) + `docs` DC-1 (MED) + partial `style-consistency` ST-1 + partial `maintainability` MA-3 — **four reviewers triangulated**

**Evidence:**

```kotlin
// Line 140 — current
if (observerPosition * pPosition >= 0.000001) {
    result++
}
```

KDoc claims "within a 1e-6 epsilon to absorb floating-point noise" —
implying the epsilon is a per-distance threshold. But the inequality is
on the *product*. Two same-side distances of 1e-3 each multiply to 1e-6,
which barely passes. Two same-side distances of 5e-4 each multiply to
2.5e-7, which fails (vertex skipped despite being non-coplanar).
The geometric meaning of the threshold is non-obvious from the code
and the KDoc misleads any reader trying to reason about numerical
robustness.

**Issue:** The threshold semantics are confusing and don't match the
KDoc. RL-1 also notes that for the project's claimed 0..100 coordinate
range, FP error products dominate at ~2.2e-4 — well above 1e-6 — so the
epsilon is too tight there and misclassifies true-coplanar vertices as
on-plane (returning 0). The code is only validated for 0..10.

**Fix:** Either of:

- **Option A (recommended):** Change to per-distance epsilon with sign agreement:
  ```kotlin
  val sameSign = (observerPosition >= 1e-6 && pPosition >= 1e-6) ||
                 (observerPosition <= -1e-6 && pPosition <= -1e-6)
  if (sameSign) result++
  ```
  Restores the geometric meaning the KDoc describes.

- **Option B:** Update the KDoc to honestly describe the product
  threshold and document the 0..10 validated range, deferring the
  per-distance fix to a future numerical-robustness workflow.

This finding stands independent of CR-1's algorithmic fix; both
threshold issues should be resolved together.

**Severity:** HIGH | **Confidence:** High

---

### TS-2: Antisymmetry invariant test is vacuous [HIGH]

**Location:** `isometric-core/src/test/kotlin/.../DepthSorterTest.kt` — `closerThan is antisymmetric for representative non-coplanar pairs`
**Source:** `testing` (HIGH) + `correctness` CR-5 (LOW) + `reliability` RL-5 (NIT) — **three reviewers**

**Evidence:** With the new binary 0/1 return, `closerThan(A, B) =
countCloserThan(A, B) - countCloserThan(B, A)` ∈ {−1, 0, +1}. For any
non-coplanar pair `closerThan(A, B) + closerThan(B, A) == 0` is
mathematically forced — at most one direction can return +1, and if it
does, the other returns 0 or +1 (in which case both directions
disagreeing about who is closer is impossible by the geometry). The
implement note acknowledges this: "Antisymmetry test currently passes
by construction." It would have passed with the pre-fix integer-division
bug too.

**Issue:** The test gives false confidence — it claims to guard an
invariant that the new return type makes trivially true.

**Fix:** Strengthen with explicit magnitude assertions on a hand-picked
pair set known to produce unambiguous orderings:

```kotlin
@Test
fun `closerThan returns nonzero magnitudes for unambiguous non-coplanar pairs`() {
    val pairs = listOf(/* hq_right + factory_top, plus 3-4 unambiguous pairs */)
    pairs.forEach { (a, b) ->
        val ab = a.closerThan(b, observer)
        val ba = b.closerThan(a, observer)
        assertEquals(0, ab + ba, "antisymmetric")
        assertTrue(abs(ab) >= 1, "magnitude non-zero for $a vs $b")
        assertTrue(abs(ba) >= 1, "magnitude non-zero for $a vs $b")
    }
}
```

**Severity:** HIGH | **Confidence:** High

---

### TS-3: Deferred shared-edge `hasIntersection` case is load-bearing [HIGH]

**Location:** `isometric-core/src/test/kotlin/.../IntersectionUtilsTest.kt`
**Source:** `testing`

**Evidence:** The plan called for a "shared-edge-only behaviour TBD"
test case in the new `IntersectionUtilsTest`. The implementation (per
implement § Deviations) replaced it with "outer contains inner" because
the shared-edge behaviour was unclear without running the test.
`IntersectionUtils.hasIntersection` is the broad-phase gate — it
determines whether `closerThan` is called for a face pair at all. If
`hasIntersection` returns `false` for two 2D-projected polygons that
share only an edge (no interior overlap), the entire `closerThan` fix
is bypassed for that pair.

**Issue:** A core assumption of the fix (that `closerThan` will run on
shared-edge polygon pairs in screen space) is unverified. If
`hasIntersection`'s `isPointInPoly` fallback rejects shared-edge cases,
the regression class is wider than diagnosed.

**Fix:** Add the deferred test case — either (a) two screen-projected
polygons that share exactly one edge with no interior overlap and
assert the actual current behaviour (true or false, with an inline
comment documenting the choice), or (b) two polygons sharing a vertex
only. Either way, lock down what the broad phase does for the geometry
class this fix targets.

**Severity:** HIGH | **Confidence:** High

---

### MED findings (selected for fix)

#### MA-1: KDoc conflates contract with bug history [MED]
`Path.kt:103-115`. KDoc reads as a changelog. Trim to: (1) one sentence
on contract, (2) one sentence on coplanar handling, (3) one sentence
referencing where the historical bug context lives (e.g.,
`docs/internal/explanations/depth-sort-painter-pipeline.md` once
DC-5 is created).

#### MA-2: `countCloserThan` name implies cardinality [MED]
`Path.kt:117`. Function returns binary {0, 1} but is named like a
count. Two acceptable resolutions:
- Rename to `hasAnyVertexCloserThan` returning `Boolean`; update
  `closerThan` to subtract `Int` conversions.
- Keep `Int` and document the {0, 1} contract explicitly in KDoc.
Either makes the implicit contract with `closerThan`'s subtraction
explicit.

#### PF-1: Hot loop allocates + lacks early-exit [MED]
`Path.kt:131-145`. Per call, allocates N `Vector` instances and iterates
all N vertices even after `result` first goes positive. Inline the OP
arithmetic to three Double locals and `return 1` on the first hit.
Saves O(faces²) allocations per frame; not a measurable user-visible
change today but compounds with PF-3's pre-existing upstream boxing.

#### DC-2: Public `closerThan` KDoc not updated [MED]
`Path.kt:95-98`. The current text ("It is closer if one of its vertices
and the observer are on the same side") is *coincidentally* correct for
the new permissive predicate but doesn't acknowledge the threshold
change or shared-edge rationale. Update to match the corrected
predicate from CR-1.

#### TS-5: 1e-6 epsilon not pinned at boundary [MED — DEFERRED]
**Triage decision: defer.** Resolved more naturally by RL-1's per-distance
fix; revisit after CR-1 + RL-1 land.

Wait — TS-5 was triaged "Fix" not "Defer". Recording correctly:

#### TS-5: 1e-6 epsilon not pinned at boundary [MED]
**Triage decision: fix.** Add a parametric PathTest case where the
per-distance product (or, after RL-1's fix, individual distance) is at
the ±1e-6 boundary. Locks the threshold semantics so future tweaks
surface as test failures rather than silent behavioural drift.

#### RL-2: Alternative regression theory [MED — DEFERRED]
The reliability reviewer hypothesised the regression came from
threshold being too tight (returning 0 → fallback to centroid sort →
wrong order). CR-1's concrete vertex trace contradicts this. Recording
as superseded; if CR-1's fix doesn't fully resolve the LongPress
artefact, revisit RL-2 as the alternative hypothesis.

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|---|---|---|---|---|
| CR-1 | BLOCKER | correctness | **fix** | Land alongside TS-1 in next /wf-implement |
| TS-1 | BLOCKER | testing | **fix** | Same implement pass as CR-1 |
| RL-1 | HIGH | reliability + 3 others | **fix** | Option A (per-distance epsilon with sign agreement) preferred |
| TS-2 | HIGH | testing + 2 others | **fix** | Strengthen with explicit magnitude assertions |
| TS-3 | HIGH | testing | **fix** | Land deferred shared-edge case |
| MA-1 | MED | maintainability | **fix** | KDoc rewrite |
| MA-2 | MED | maintainability | **fix** | Rename or document binary contract |
| PF-1 | MED | performance | **fix** | Inline arithmetic + early-exit |
| DC-2 | MED | docs | **fix** | Update public `closerThan` KDoc |
| TS-5 | MED | testing | **fix** | Boundary parametric test |
| RL-2 | MED | reliability | **defer** | Superseded by CR-1; record as historical hypothesis |
| TS-4 | MED | testing | (folded into TS-1) | Same fix scope |
| SEC-1 | LOW | security | untriaged | Pre-existing, separate concern |
| MA-3 | LOW | maintainability + style | untriaged | Folded under RL-1 (literal style + named constant) |
| MA-4 | LOW | maintainability | untriaged | Variable naming polish |
| PF-3 | LOW | performance | untriaged | Pre-existing upstream alloc |
| RL-4 | LOW | reliability | untriaged | KDoc range claim |
| DC-3 | LOW | docs + security | untriaged | Workflow vocab in KDoc — see saved feedback `feedback_no_slice_vocab_in_pr.md` |
| RS-LOW | LOW | refactor-safety | untriaged | Missing degenerate-coplanar test |
| SEC-2, CS-2, CS-4, MA-5, DC-4, DC-5, ST-2 | NIT | various | untriaged | Polish-grade; consider during implement |

## Recommendations

### Must Fix (BLOCKERs + HIGHs triaged "fix")

1. **CR-1** — predicate fix: require `result > 0 AND resultOpposite == 0`
   (no straddling). Restore "all vertices observer-side" intent without
   integer-division collapse. **Effort: ~10 lines in Path.kt.**
2. **TS-1** — add height-change adjacency tests (PathTest unit + DepthSorter
   integration). Pin both selection states. **Effort: ~50 lines new tests.**
3. **RL-1** — per-distance epsilon with sign agreement OR honest KDoc.
   Recommended: per-distance fix. **Effort: ~5 lines in Path.kt + KDoc rewrite.**
4. **TS-2** — strengthen antisymmetry test with magnitude assertions.
   **Effort: ~10 lines extra in DepthSorterTest.**
5. **TS-3** — land deferred shared-edge `hasIntersection` test case.
   **Effort: ~15 lines new in IntersectionUtilsTest.**

### Should Fix (MEDs triaged "fix")

6. **MA-1, MA-2, DC-2** — KDoc rewrites + name/return clarification on
   `countCloserThan`. **Effort: ~30 lines KDoc total + decision on rename.**
7. **PF-1** — inline OP arithmetic + early-exit. **Effort: ~15 lines.**
8. **TS-5** — epsilon-boundary parametric test. **Effort: ~10 lines.**

### Deferred (triaged "defer")

- **RL-2** — superseded by CR-1; keep as historical hypothesis if CR-1 fix
  doesn't fully resolve the visual regression on emulator.

### Dismissed

None.

### Consider (LOW/NIT — not triaged)

- **DC-3 / SEC-2** — workflow slug `depth-sort-shared-edge-overpaint` in
  KDoc violates the saved user preference (`feedback_no_slice_vocab_in_pr.md`).
  Recommend dropping the reference during the same KDoc rewrite that
  resolves MA-1.
- **DC-5** — create `docs/internal/explanations/depth-sort-painter-pipeline.md`
  per shape-stage Documentation Plan; gives future readers a single source
  for "why centroid pre-sort + Kahn + plane-side test" rationale.
- **MA-3 + ST-1** — change `0.000001` literal to `1e-6` and consider a
  named constant.
- **MA-4** — rename `observerPosition` / `pPosition` to make plane-distance
  semantics explicit.
- **DC-4** — `02-shape.md` "strict majority" wording contradicts source
  (after CR-1 fix this becomes "all vertices observer-side"); update spec
  to match.
- **CS-2, CS-4, MA-5** — KDoc/comment trims.
- **ST-2** — drop `WS10` milestone prefix on the scene factory file name.
- **SEC-1, PF-3** — pre-existing, out of scope for this fix.
- **RS-LOW** — degenerate-coplanar regression test for completeness.

## Recommended Next Stage

- **Option A (recommended):** `/wf-implement depth-sort-shared-edge-overpaint depth-sort-shared-edge-overpaint`
  — fix the BLOCKERs (CR-1, TS-1) and HIGHs (RL-1, TS-2, TS-3) plus the
  selected MEDs (MA-1, MA-2, PF-1, DC-2, TS-5). This is the same slice
  re-implemented; not a new slice.
  **Compact recommended before invoking** — review chatter (10 sub-agents,
  triage Q&A, dedup) is noise for the implement pass; the PreCompact hook
  preserves workflow state, and triage decisions are persisted in this file.

- **Option B:** `/wf-amend depth-sort-shared-edge-overpaint from-review`
  — only if the PO disagrees with the proposed CR-1 fix and wants to
  reconsider the threshold semantics from scratch (e.g., adopt the
  AABB-minimax alternative from shape-stage out-of-scope list). Not
  recommended; CR-1's fix is a small additive change to the current
  approach.

- **Option C:** `/wf-handoff depth-sort-shared-edge-overpaint` — **not
  applicable**. Verdict is Don't Ship; cannot hand off a known regression.

- **Option D:** `/wf-extend depth-sort-shared-edge-overpaint from-review`
  — could spawn separate slices for the deferred items (RL-2 as alternative
  hypothesis investigation, PF-3 upstream allocation, DC-5 internal docs).
  Better done after the BLOCKERs are resolved; not the immediate next step.
