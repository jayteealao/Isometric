---
schema: sdlc/v1
type: review-command
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
review-command: correctness
status: complete
updated-at: "2026-04-26T20:44:25Z"
metric-findings-total: 5
metric-findings-blocker: 1
metric-findings-high: 2
result: blockers-found
tags: []
refs:
  review-master: 07-review.md
---

# Review: correctness

## Findings

| ID | Sev | Conf | File:Line | Issue |
|----|-----|------|-----------|-------|
| CR-1 | BLOCKER | High | `Path.kt:140-145` | Permissive "any vertex" threshold adds spurious wrong-direction dependency edges for tall faces that straddle a farther horizontal face's plane — confirmed to overwrite the visible side of a selected (yellow) box |
| CR-2 | HIGH | High | `Path.kt:99-101` | `closerThan` can now return `(1, 0)` in both the correct case and the regression case; antisymmetry (`a + b = 0`) holds but does NOT distinguish semantic correctness — existing antisymmetry test is necessary but not sufficient |
| CR-3 | HIGH | High | `PathTest.kt:81-85` | `closerThan returns nonzero for hq-right vs factory-top` asserts `result > 0` but the hq-right/factory-top configuration also produces `(1, 0)` via the same straddling pattern as the regression; the test validates a sign that is now also produced incorrectly for other pairs |
| CR-4 | MED | Med | `Path.kt:140` | Widened epsilon from 1e-9 to 1e-6 is undocumented in terms of scale-relative safety margin; for the specific LongPress/OnClick scenes the epsilon change is not the regression cause, but for scenes with coordinate values approaching 1000 units the threshold could silently misclassify near-coplanar vertices |
| CR-5 | LOW | High | `DepthSorterTest.kt:200-248` | Antisymmetry invariant test asserts `a + b = 0` over four pairs; this property holds for the new binary `{0, 1}` return by construction and does not verify semantic correctness of any individual ordering decision |

## Detailed Findings

---

### CR-1 — Spurious wrong-direction dependency edge for tall-face-straddles-horizontal-plane

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, lines 140–145

**Evidence:**

```kotlin
// NEW code — line 140
if (observerPosition * pPosition >= 0.000001) {
    result++
}
return if (result > 0) 1 else 0
```

**Concrete case (OnClickSample / CombinedSample "yellow box"):**

- `Selected.right`: vertical face at `x = 4.0`, `y ∈ [0, 1]`, `z ∈ [0.1, 2.1]`  (selected prism, `height = 2.0`)
- `Neighbor.top`: horizontal face at `z = 1.1`, `x ∈ [4.5, 5.5]`, `y ∈ [0, 1]` (adjacent normal prism, `height = 1.0`)
- Observer: `(-10, -10, 20)`

**Plane-side computation for `Selected.right.countCloserThan(Neighbor.top)`:**

`Neighbor.top`'s plane: `z = 1.1`, normal `n = (0, 0, 1)`, `d = 1.1`.  
Observer position relative to plane: `20 - 1.1 = 18.9` (observer is ABOVE, positive).

Vertices of `Selected.right`:
- `(4.0, 1.0, 0.1)`: `pPos = 0.1 - 1.1 = -1.0`, product = `18.9 × (−1.0) = −18.9` → NOT counted
- `(4.0, 1.0, 2.1)`: `pPos = 2.1 - 1.1 = 1.0`, product = `18.9 × 1.0 = 18.9 ≥ 0.000001` → **COUNTED**
- `(4.0, 0.0, 2.1)`: same → **COUNTED**
- `(4.0, 0.0, 0.1)`: same as first → NOT counted

`result = 2`, returns **1**.

**Plane-side computation for `Neighbor.top.countCloserThan(Selected.right)`:**

`Selected.right`'s plane: `x = 4.0`, `n = (2, 0, 0)`, `d = 8.0`.  
Observer position: `2×(−10) − 8 = −28` (observer LEFT of `x=4`, negative).

Vertices of `Neighbor.top` (all at `x ∈ {4.5, 5.5}`):
- `pPos = 2 × 4.5 − 8 = 1.0`, product = `(−28) × 1.0 = −28` → NOT counted  
- All four vertices give negative product.

Returns **0**.

**Result:**

```
countCloserThan(Selected.right, Neighbor.top) = 1
countCloserThan(Neighbor.top, Selected.right) = 0
closerThan(Selected.right, Neighbor.top) = 1 − 0 = +1
```

In `DepthSorter.checkDepthDependency`, `depthSorted` is descending by depth.  
`depth(Selected.right) = 2.30` vs `depth(Neighbor.top) = 3.30`.  
`Neighbor.top` (depth 3.30) is at lower index `j`; `Selected.right` (depth 2.30) is at higher index `i`.  
The call is `checkDepthDependency(itemA = Selected.right, itemB = Neighbor.top, i, j)`.

`cmpPath = 1 > 0` → `drawBefore[j(Neighbor.top)].add(i(Selected.right))`  
→ `Selected.right` is drawn **before** `Neighbor.top`.

`Neighbor.top` (farther, depth 3.30) is drawn **after** `Selected.right` and overwrites it.

**Visual artifact:** The right face of the selected (yellow) box is overdrawn by the neighbor's top face. The right side of the yellow box disappears — exactly matching the user-reported "missing faces/sides" regression.

**Why this is wrong:**  
`Selected.right` is a tall vertical face that **straddles** `Neighbor.top`'s plane: its `z = 0.1` vertices are below `z = 1.1` and its `z = 2.1` vertices are above. The "any vertex on observer side" heuristic fires for the `z = 2.1` vertices, but that signal is geometrically meaningless for depth ordering — it only means the face is tall, not that it is farther from the observer. `Selected.right` (the closer wall at `x = 4.0`) should be drawn **after** `Neighbor.top` (the farther horizontal face at `x ∈ [4.5, 5.5]`).

**Same pattern applies in LongPressSample** for `Ground.front` vs tile right/left faces: the ground's front face (y = −1, thin strip) spans the projection space and the new threshold adds wrong-direction edges between the ground slab's side faces and tile side faces.

**2D projection overlap confirmed:** `Selected.right` projects to `x ∈ [182, 243]`, `y ∈ [−322, −147]`; `Neighbor.top` projects to `x ∈ [212, 333]`, `y ∈ [−304, −234]`. The bounding boxes overlap, so `hasIntersection` returns `true` and `checkDepthDependency` does call `closerThan` for this pair.

**Fix suggestion:**  
The `result > 0` heuristic must not fire when the face being tested **straddles** the reference plane. A correct implementation should return 1 only when `result > 0 AND result_opposite == 0` (i.e., all vertices that were tested fell on a single side). When `result > 0 AND result_opposite > 0` (the face straddles), return 0 and let the depth pre-sort decide. Concretely:

```kotlin
// Track vertices on the OPPOSITE side too
var resultOpposite = 0

for (point in points) {
    val OP = Vector.fromTwoPoints(Point.ORIGIN, point)
    val pPosition = Vector.dotProduct(n, OP) - d
    val product = observerPosition * pPosition
    if (product >= 0.000001) {
        result++
    } else if (product <= -0.000001) {
        resultOpposite++
    }
}

// Only assert a side if the face does NOT straddle the plane.
// If it straddles (vertices on both sides), the plane-side test is ambiguous.
return if (result > 0 && resultOpposite == 0) 1 else 0
```

This preserves the fix for the original bug (hq-right has `result = 2`, `resultOpposite = 2`, straddles → returns 0 via old broken int-division, but the CORRECT case was actually `factory_top.countCloserThan(hq_right)` returning 0 while `hq_right.countCloserThan(factory_top)` was the broken one). See the analysis in CR-2 for the full semantic chain.

**Severity:** BLOCKER — confirmed visual regression on emulator, root-caused with coordinates.  
**Confidence:** High — verified by numerical trace matching the reported symptom.

---

### CR-2 — `closerThan` returns `+1` in wrong direction; antisymmetry passes but semantics are wrong

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, lines 99–101 and 140–145

**Issue:**

`closerThan(this, pathA)` now returns only `{−1, 0, +1}`, where `−1` and `+1` are always `±(1 − 0)`. The antisymmetry invariant `closerThan(A, B) + closerThan(B, A) = 0` holds by construction — if one direction returns 1 the other returns −1. However, the **sign** of the non-zero result is no longer guaranteed to be semantically correct.

The original pre-fix algorithm returned non-zero only when **all** vertices of the tested face were on the observer's side of the reference plane. This was conservative but had predictable semantics: non-zero always meant "the face is definitively behind/in-front". The new algorithm returns 1 whenever **any** vertex lands on the observer side, which fires for faces that merely **extend past** the reference plane in the z-direction.

When `result > 0 AND result_opposite > 0` (face straddles the reference plane), the single-side vote is not a valid depth signal. In those cases the old integer-division code produced 0 (tie) and the depth pre-sort made the call. The new code produces 1 and adds an edge that can point in the wrong direction.

**Severity:** HIGH — underlying cause of the BLOCKER finding CR-1.  
**Confidence:** High.

---

### CR-3 — Acceptance-criteria test asserts `result > 0` but cannot distinguish correct from spurious signals

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`, lines 63–85

**Evidence:**

```kotlin
val result = factoryTop.closerThan(hqRight, observer)
assertTrue(
    result > 0,
    "closerThan must return positive for factory_top vs hq_right (factory_top is the farther face); got $result"
)
```

**Issue:**

The test verifies that `factoryTop.closerThan(hqRight) > 0`. With the new code, this passes because:

- `hqRight.countCloserThan(factoryTop)` = 1 (hq-right's `z = 3.1` vertices are above factory-top's `z = 2.1` plane — a straddle case).
- `factoryTop.countCloserThan(hqRight)` = 0 (all of factory-top is to the right of hq-right's `x = 1.5` plane).
- `closerThan(factoryTop, hqRight) = 1 − 0 = 1`. Test passes.

The **regression case** (CR-1) produces the exact same arithmetic structure: `(1, 0) → +1`. Both the fixed case and the regressed case pass through the same code path and produce the same return value. The test therefore cannot distinguish a correct fix from a spurious edge.

A test that would catch the regression: assert that `selectedRight.closerThan(neighborTop, observer) <= 0` for the OnClickSample geometry, OR assert that `selectedRight.closerThan(neighborTop) + neighborTop.closerThan(selectedRight) == 0 AND neighborTop.closerThan(selectedRight) >= 0` (neighbor's top must be drawn before, not after, the selected right wall).

**Severity:** HIGH — test suite passes while a confirmed visual regression is present.  
**Confidence:** High.

---

### CR-4 — Widened epsilon lacks a scale-relative justification

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, line 140

**Evidence:**

```kotlin
// Epsilon widened from 1e-9 to 1e-6 to absorb floating-point noise for the
// project's typical 0..100 coordinate range
if (observerPosition * pPosition >= 0.000001) {
```

**Issue:**

The threshold `0.000001` is applied to the **product** `observerPosition × pPosition`, not to `pPosition` directly. For the typical 0..100 coordinate range, `observerPosition` can be `~20 × N` where `N` is the cross-product magnitude (itself proportional to `area ∝ scale²`). For a 10 × 10 face, `N ≈ 100`, `observerPosition ≈ 2000`, and `pPosition` needs to be only `5 × 10⁻¹⁰` to exceed the threshold — even smaller than the old 1e-9 threshold for the product. The epsilon therefore does not uniformly represent a "within 1e-6 of the plane" distance.

The KDoc states "a vertex within 1e-6 of pathA's plane is treated as coplanar". This is only true when `|observerPosition| ≈ 1`, which does not hold in general. The comment is misleading.

For the LongPressSample and OnClickSample scenes analyzed, this epsilon discrepancy does not affect the result because no vertex is close enough to any reference plane for it to matter — the epsilon change is not the regression cause. However, for scenes with large N (wide/tall faces) or very small coordinate differences, the effective plane-proximity threshold is unexpectedly small.

**Fix suggestion:** Either use `pPosition.absoluteValue >= 1e-6` (independent of `observerPosition`) or document the product-based threshold with a formula showing its equivalence.

**Severity:** MED — does not cause the reported regression but the comment is incorrect and the threshold is fragile.  
**Confidence:** Med.

---

### CR-5 — Antisymmetry test is insufficient as a correctness guard

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`, lines 200–248

**Issue:**

The test verifies `closerThan(A, B) + closerThan(B, A) == 0` for four pairs. With the new binary `{0, 1}` return, antisymmetry holds by construction: if `countCloserThan` returns 1 in one direction and 0 in the other, `closerThan` produces `+1` in one direction and `−1` in the other. `1 + (−1) = 0`.

The confirmed regression pair (`Selected.right` vs `Neighbor.top`): `closerThan(Sel.right, Nei.top) = 1` and `closerThan(Nei.top, Sel.right) = −1`. Sum = 0. **Test passes despite the wrong edge.**

The antisymmetry property is a necessary mathematical property for a comparator but is not sufficient to ensure semantic correctness. A test asserting the **direction** of the non-zero result for a geometry where depth-ordering is unambiguous (such as the `Selected.right` vs `Neighbor.top` pair) would catch the regression.

**Severity:** LOW — test infrastructure is present but the invariant chosen is too weak.  
**Confidence:** High.

---

## Summary

| Sev | Count |
|-----|-------|
| BLOCKER | 1 (CR-1) |
| HIGH | 2 (CR-2, CR-3) |
| MED | 1 (CR-4) |
| LOW | 1 (CR-5) |

**Status: blockers-found**

The permissive "any vertex on observer side" threshold in `Path.countCloserThan` (commit `3e811aa`) correctly addresses the original `hq_right` / `factory_top` shared-edge overpaint bug but introduces a symmetric failure mode: when a tall vertical face **straddles** a farther horizontal face's plane, the same pattern (`result=2, result_opposite=2`) fires the threshold and adds a dependency edge pointing in the **wrong direction**. The selected (yellow) box's right side face is drawn before the neighbor's top face, which then overwrites it — matching the user-reported "missing faces/sides" regression on emulator-5554.

The fix required before merge is:

1. Add `result_opposite` tracking in `countCloserThan` and return `1` only when `result > 0 AND resultOpposite == 0` (no straddling).
2. Add a regression test asserting `closerThan(selected_right, neighbor_top) <= 0` for the OnClickSample geometry (tall selected box, normal adjacent box).
3. Update the antisymmetry test or add a companion semantic test for the `Selected.right` / `Neighbor.top` pair to assert the correct direction.
