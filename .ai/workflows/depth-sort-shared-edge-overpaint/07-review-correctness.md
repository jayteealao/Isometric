---
schema: sdlc/v1
type: review-command
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
review-command: correctness
status: complete
updated-at: "2026-04-28T09:25:00Z"
metric-findings-total: 4
metric-findings-blocker: 0
metric-findings-high: 0
result: issues-found
tags: [correctness, depth-sort, painter-algorithm, newell-cascade]
refs:
  review-master: 07-review.md
---

# Review: correctness (round 2 — post-Newell cascade)

> **Scope:** cumulative diff `97416ba..HEAD` on `feat/ws10-interaction-props`.
> Reviewed against commit `452b1fc` (the Newell-cascade replace commit).
> The previous `07-review-correctness.md` (round 1) covered the `countCloserThan` permissive-threshold
> code from `3e811aa`; its CR-1 BLOCKER was the root cause addressed by this rewrite.

## Findings

| ID | Sev | Conf | File:Line | Issue |
|----|-----|------|-----------|-------|
| CR-1 | MED | High | `Path.kt:248–250` | KDoc for `signOfPlaneSide` incorrectly states on-plane vertices "count as same side"; code treats them as neutral (zero product, not classified) |
| CR-2 | LOW | High | `Path.kt:204–214` | Steps 2 and 3 (screen-x / screen-y extent minimax) are dead code in the `DepthSorter` production path; no current unit test exercises them; signs in step 2 are inconsistent with the declared intuition for large-`y` faces |
| CR-3 | LOW | Med | `Path.kt:265–266` | `signOfPlaneSide` uses a product-based epsilon (`observerPosition × pPosition > EPSILON`) whose effective plane-proximity distance scales with observer–plane distance; the KDoc claims "within EPSILON of the plane" which is only accurate when `|observerPosition| ≈ 1` |
| CR-4 | LOW | High | `PathTest.kt` / `DepthSorterTest.kt` | Steps 2 and 3 of the cascade are not exercised by any test; antisymmetry test covers all four representative pairs but does not add a pair that would exercise step 2 or step 3 |

---

## Detailed Findings

### CR-1: KDoc for `signOfPlaneSide` incorrectly describes on-plane vertex treatment [MED]

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, lines 248–250

**Evidence:**

```kotlin
// KDoc says:
// "Vertices exactly on the plane (within [EPSILON]) count as 'same side' for this
//  purpose."

// Actual code (lines 265–276):
val signed = observerPosition * pPosition
if (signed > EPSILON) anySameSide = true
if (signed < -EPSILON) anyOppositeSide = true
```

**Issue:** For a vertex exactly on `pathA`'s plane, `pPosition = 0`, so `signed = observerPosition × 0 = 0`. The guard `0 > EPSILON` is false, so the vertex is NOT counted as `anySameSide`. It is NEUTRAL: neither flag is set.

The KDoc claim "count as same side" would imply the vertex contributes to `anySameSide = true`. It does not. For a face with ALL vertices on `pathA`'s plane, no flag is ever set, and the function returns `0` (the `else` branch), which is the CORRECT behavior for coplanar faces. Despite the wrong documentation, the code behavior is correct:

- All-coplanar face → returns 0 (genuinely ambiguous, polygon-split deferred). Correct.
- Partially on-plane, rest above (like the wall-vs-floor test: 2 vertices at z=0, 2 at z=1) → the above-plane vertices set `anySameSide`; on-plane vertices contribute nothing. Returns −1 (closer). Correct.

No behavioral bug exists, but the KDoc is wrong and will mislead future readers trying to understand the on-plane-vertex semantics.

**Fix:** Change the KDoc sentence to "Vertices exactly on the plane (within EPSILON of the plane, i.e. `|pPosition|` small) are treated as neutral and do not influence either flag."

**Severity:** MED — documentation error, no correctness impact. | **Confidence:** High

---

### CR-2: Steps 2 and 3 are dead code in the `DepthSorter` production path; step 2 sign is inconsistent with its declared intuition [LOW]

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, lines 204–214

**Evidence:**

```kotlin
// Step 2: screen-x extent minimax
if (selfScreenXMax < aScreenXMin - EPSILON) return -1
if (aScreenXMax < selfScreenXMin - EPSILON) return 1

// Step 3: screen-y extent minimax
if (selfScreenYMax < aScreenYMin - EPSILON) return 1
if (aScreenYMax < selfScreenYMin - EPSILON) return -1
```

**Issue — dead code in production path:**

`DepthSorter.checkDepthDependency` gates on `IntersectionUtils.hasInteriorIntersection` before calling `closerThan`. `hasInteriorIntersection` returns `true` only when the two 2D screen projections share a non-trivial interior overlap. Interior overlap requires both screen-x and screen-y extents to overlap (AABB overlap is step 1 of `hasInteriorIntersection`). Therefore, for any pair that reaches `closerThan` through `DepthSorter`, screen-x extents overlap and screen-y extents overlap. Steps 2 and 3 test for _disjoint_ extents, so they can never produce a non-zero result for pairs arriving via the gate.

Steps 2 and 3 are only reachable from direct unit-test calls to `closerThan` with screen-disjoint faces (such as the diagonal-offset test). The implementer explicitly documented this in the Round-4 notes but did not flag it as dead code — the intent was to keep the Newell structure "complete". This is a defensible design choice, but it means the steps are never exercised in production.

**Issue — step 2 sign inconsistency:**

The step-2 comment states: "Larger screen-x corresponds to larger world (x − y), which in the standard 30° iso projection points deeper into the scene." This is directionally correct when `x` dominates, but for large-`y` faces, `screenX = (x − y) × cos(angle)` is SMALLER (more negative) even though `iso-depth = x × cos + y × sin − 2z` is LARGER (face is farther). A face entirely to the left in screen-x (step 2 returns −1, "self closer") can have a larger average iso-depth than `pathA`.

Concrete example: face A at `(x=0..1, y=5..6, z=0)` has `screenX ∈ [−5.2, −3.5]` and `iso-depth ∈ [2.5, 3.9]`; face B at `(x=4..5, y=0..1, z=0)` has `screenX ∈ [2.6, 4.3]` and `iso-depth ∈ [3.5, 4.8]`. Depths overlap (step 1 does not fire). Step 2 fires: A is entirely to the left → returns −1 (A closer). A's iso-depth average (3.18) < B's average (4.15), so A IS actually closer. The step 2 result is correct here. However, the _justification_ given in the comment ("larger screen-x = deeper") fails for this geometry; A is correctly classified "closer" because its depth is smaller, not because of the screen-x intuition about the `x − y` world distance. For any geometry where step 2 would produce the WRONG sign (large-`y` face truly farther than a large-`x` face but both screen-disjoint), step 1 fires first because the large-`y` face has larger iso-depth and the iso-depth ranges become disjoint. The sign error in the comment is masked by step 1's earlier firing.

The practical consequence: no currently reachable call path exercises step 2 with a geometry that would trigger the sign error. But if step 2 were ever to fire in a future scene where step 1 failed to separate the depths, it could assign the wrong direction. This is a latent hazard, not an active bug.

**Severity:** LOW — no failing test case exists; steps 2/3 are structurally inert for gated DepthSorter pairs. | **Confidence:** High

---

### CR-3: Product-based epsilon in `signOfPlaneSide` has observer-distance-dependent effective threshold [LOW]

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, lines 265–266 and the KDoc claim at line 246

**Evidence:**

```kotlin
val signed = observerPosition * pPosition
if (signed > EPSILON) anySameSide = true
if (signed < -EPSILON) anyOppositeSide = true
```

**Issue:** The threshold `EPSILON = 1e-6` is applied to the **product** `observerPosition × pPosition`, not to `pPosition` directly. The effective plane-proximity tolerance for a vertex is `EPSILON / |observerPosition|`.

For the standard test scene observer at `(-10, -10, 20)` and `pathA` = floor at `z = 0`, the plane normal is `(0, 0, 4)` (from `AB × AC` of a 2×2 floor) and `observerPosition = 80`. Effective threshold: `1e-6 / 80 = 1.25e-8`. A vertex at `z = 1.25e-8` (practically on the plane) would be classified as `anySameSide = true` rather than neutral.

For a very large scene (coordinates 0..100, faces with normal magnitude ~100), `observerPosition` could reach ~10,000. Effective threshold: `1e-10`, which is within double-precision floating-point noise for unit-scale arithmetic. In this regime, a vertex that is numerically on the plane due to floating-point accumulation (e.g., the result of multiple rotations) could flip the `anySameSide` / `anyOppositeSide` decision.

For the project's typical 0..10 coordinate range, `|observerPosition|` stays below ~100 and the effective threshold (~1e-8) is above double-precision noise. No correctness failure is expected in the current test scenes, but the behavior is scale-dependent and the KDoc claim "within EPSILON of the plane" is misleading.

**Fix:** Either apply the epsilon to `pPosition` directly (`if (pPosition.absoluteValue > EPSILON) ...`) or document the product-based formulation explicitly: "classified as on the same side when `|pPosition| > EPSILON / |observerPosition|`". If changing to a direct `pPosition` threshold, note that the sign of the dot product still needs `observerPosition`'s sign to determine which side.

**Severity:** LOW — no correctness failure in current test range; threshold drift at extreme coordinates. | **Confidence:** Med

---

### CR-4: Steps 2 and 3 have no unit-test coverage; antisymmetry test does not add a step-2/3 pair [LOW]

**Location:** `PathTest.kt` (all `closerThan` tests), `DepthSorterTest.kt` lines 201–249

**Issue:** The implementer's Round-4 notes explicitly state: "steps 2/3 never fire in the current test corpus." No test in `PathTest` or `DepthSorterTest` contains a geometry pair where:
- iso-depth extents overlap (step 1 doesn't fire), AND
- screen-x extents are disjoint (step 2 fires), OR
- screen-x extents overlap AND screen-y extents are disjoint (step 3 fires).

The antisymmetry test in `DepthSorterTest` (`closerThan is antisymmetric for representative non-coplanar pairs`) verifies `a + b = 0` for four pairs, all of which resolve at step 1 (iso-depth minimax) or step 4 (plane-side forward). The test does not distinguish a step-2/3 firing from a step-4/5 firing.

The consequence: the sign conventions of steps 2 and 3 are unjustified empirically. As documented in CR-2, the step-2 sign comment is incorrect for large-`y` faces, but this has no test that would expose it.

Additionally: all eight `PathTest` closerThan tests resolve at either step 1 (`bLeft.closerThan(aTop)`) or step 4 (`factoryTop.closerThan(hqRight)`, `bTop.closerThan(aRight)`, `bTop.closerThan(aBack)`, `bTop_diag.closerThan(aRight_diag)`, `wall.closerThan(floor)`) or step 6 (coplanar overlapping). This was verified by tracing all eight cases through a Python implementation of the cascade.

**Fix (optional):** Add a `PathTest` case that constructs two faces with overlapping iso-depth but disjoint screen-x (e.g., a wide face spanning multiple depth bands vs a narrow face to the side), asserting that step 2 produces the expected sign. This would pin the step-2 behavior and catch the sign-inversion risk identified in CR-2.

**Severity:** LOW — all currently exercised cases are correct; only hypothetical future paths are unguarded. | **Confidence:** High

---

## Sign-Convention Consistency Verification

The Round-4 implement notes flagged a "sign convention inverted from plan" deviation. The implementation's signs were verified against all twelve PathTest cases and the DepthSorterTest antisymmetry invariant:

| Claim | Verified? |
|-------|-----------|
| `closerThan(self, pathA) < 0` ↔ `self` is closer (draws after `pathA`) | Yes — `wall.closerThan(floor) = −1`, `hqRight.closerThan(factoryTop) = −1`. Both match. |
| `closerThan(self, pathA) > 0` ↔ `self` is farther (draws before `pathA`) | Yes — `factoryTop.closerThan(hqRight) = +1`. Matches test assertion. |
| `closerThan(A, B) + closerThan(B, A) = 0` for all non-coplanar pairs | Yes — verified numerically for all four DepthSorterTest pairs and the AC-12 wall/floor pair. |
| `signOfPlaneSide` returns `+1` when all of `self` is on the OPPOSITE side from observer | Yes — factoryTop's vertices all at `x ≥ 2.0` are on the opposite side of hqRight's `x = 1.5` plane from observer at `x = −10`. Returns `+1`. |
| `signOfPlaneSide` returns `−1` when all of `self` is on the SAME side as observer | Yes — wall vertices at `z ≥ 0` (two strictly above floor's `z = 0` plane) are on the same side as observer at `z = 20`. Returns `−1`. |
| Step 6 (`closerThan` returns 0) for coplanar overlapping faces | Yes — both plane-side tests return 0 (all vertices strictly on the plane, signed = 0). |
| `DepthSorter` edge polarity: `cmpPath < 0` → `drawBefore[i].add(j)` (self draws after) | Yes — code at `DepthSorter.kt:148–152` correctly interprets the sign. |

All sign conventions are mutually consistent and match the test contract.

## Antisymmetry Trace (key pairs)

| Pair | `A.closerThan(B)` | step | `B.closerThan(A)` | step | sum |
|------|-------------------|------|-------------------|------|-----|
| hqRight / factoryTop | −1 | 5 (−sRev) | +1 | 4 (sFwd) | 0 ✓ |
| aRight / bTop | −1 | 4 | +1 | 4 | 0 ✓ |
| aBack / bTop | −1 | 4 | +1 | 4 | 0 ✓ |
| aTop / bLeft | −1 | 1 | +1 | 1 | 0 ✓ |
| wall / floor | −1 | 4 | +1 | 4 (step 5 gives 0, step 5 rev gives −1, outer returns +1) | 0 ✓ |

## New Test Case Effectiveness

| Test | Distinguishes new from old? | Notes |
|------|---------------------------|-------|
| AC-3(g) `wall.closerThan(floor) < 0` | YES — old permissive code returns 0 (both countCloserThan cancel); new returns −1 | Direct regression test for the symmetric-straddle class. |
| `closerThan returns zero for coplanar overlapping faces` | YES — split from the old AC-2 which tested non-overlapping; new test requires genuine zero (both faces on same plane, steps 1–5 all 0). | Exercises step 6 (deferred polygon-split). |
| `closerThan resolves coplanar non-overlapping via Z-extent minimax` | YES — old returns 0; new returns −1 (step 1 fires due to iso-depth `x*cos + y*sin` separating x-disjoint flat faces). | Tests step 1 for co-altitude but x-separated faces. |
| AC-12 LongPress full-scene back-right vertical faces after ground top | YES — old returns 0 for wall/floor pairs → no edge → centroid pre-sort puts wall first; new returns −1 → edge → topological sort orders correctly. | Integration test for the primary visual regression. |
| AC-13 Alpha full-scene CYAN prisms after ground top | YES — same mechanism as AC-12; three CYAN prisms each verified. | |

## Deleted `countCloserThan` — Load-Bearing for Other Callers?

`countCloserThan` was declared `private` in `Path.kt`. Confirmed no other callers exist in the diff or codebase (it was called only by `closerThan`, which now replaces both functions). The deletion is clean.

## `hasInteriorIntersection` Step-4 Omission — Safety

The implement notes flag that the plan recommended including a `hasInteriorIntersection` early-exit at step 4, but the implementation omits it. The rationale: `DepthSorter.checkDepthDependency` already gates externally on `hasInteriorIntersection`, so pairs from DepthSorter that reach step 4 have already been confirmed to interior-overlap in screen. For unit tests that call `closerThan` directly (e.g., the diagonal-offset test), the plane-side test should still decide even for screen-disjoint pairs. This omission is safe and correct.

---

## Summary

- Total findings: 4
- Blockers: 0
- High: 0
- Status: Issues Found (all LOW or MED severity; none block correctness in any verified scenario)

The Newell cascade replacement is algorithmically correct for all twelve PathTest cases and all eleven DepthSorterTest cases. The three load-bearing improvements (sign-convention correctness for hqRight/factoryTop, wall-vs-floor straddle resolution via plane-side forward, and coplanar-non-overlapping via iso-depth minimax) are verified by numerical trace. Antisymmetry holds for all tested pairs. The four findings are documentation inaccuracies (CR-1, CR-3) and untested-code risks (CR-2, CR-4) that carry no active correctness failure in the current test corpus. The highest practical risk is CR-2 (step 2 sign convention), which remains latent and could surface if a future scene produces screen-x-disjoint pairs that also have overlapping iso-depths — a geometry that does not appear in any existing test or integration scene.
