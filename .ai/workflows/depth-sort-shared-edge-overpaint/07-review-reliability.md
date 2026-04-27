---
schema: sdlc/v1
type: review
review-command: reliability
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
result: pass-with-concerns
metric-findings-total: 5
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 2
metric-findings-low: 1
metric-findings-nit: 1
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - numerical-robustness
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  security-review: 07-review-security.md
---

# Review: reliability

Scope: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
lines 99–146 — `closerThan` and its private helper `countCloserThan`. Reading also
covers `Vector.kt` (cross/dot product implementations) and
`DepthSorter.kt` (`checkDepthDependency`, `sort`).

## Findings

| ID   | Severity | Confidence | Location                              | Summary                                                                                 |
|------|----------|------------|---------------------------------------|-----------------------------------------------------------------------------------------|
| RL-1 | HIGH     | High       | `Path.kt:140` — product threshold     | Product check mixes distance scales; both distances can be 1e-3 and still pass — semantically ambiguous |
| RL-2 | MED      | High       | `Path.kt:117-146` — antisymmetry      | Near-coplanar noise can cause `countCloserThan(A,B)=1` AND `countCloserThan(B,A)=1`, adding a cycle |
| RL-3 | MED      | High       | `Path.kt:117-121` — degenerate face   | Collinear first three points yield zero normal; `observerPosition = 0 - 0 = 0`; EC-3 claim is correct but the proof path needs documenting |
| RL-4 | LOW      | Med        | `Path.kt:140` — epsilon tightness     | 1e-6 absolute threshold is tight enough for 0..10 range but was documented as 0..100; the epsilon is borderline for the upper end |
| RL-5 | NIT      | High       | `DepthSorter.kt:145` — `result0` use  | `result0` (boundary tally) was never read by `DepthSorter`; its removal is safe and confirmed |

---

## Detailed Findings

### RL-1 — Product threshold is semantically ambiguous (HIGH)

**Location:** `Path.kt` line 140

```kotlin
if (observerPosition * pPosition >= 0.000001)
```

**Description:**

The predicate checks whether the *product* of two signed distances is ≥ 1e-6. The
intended meaning is "observer and vertex are on the same side of the plane AND not
coplanar". Using the product conflates two independent axes:

1. **Same-side check:** the product is positive iff the two distances have the same
   sign — correct.
2. **Not-coplanar guard:** the product magnitude is used to filter out near-zero
   distances.

The problem with (2) is that the product magnitude depends on *both* factors
multiplicatively. Consider:

- `observerPosition = 1e-3`, `pPosition = 1e-3` → product = 1e-6, passes.
- `observerPosition = 1.0`, `pPosition = 1e-6` → product = 1e-6, passes.
- `observerPosition = 1e-3`, `pPosition = 9.9e-4` → product = 9.9e-7, **fails**,
  even though neither distance is near-coplanar in absolute terms.

In the last case, a vertex that is clearly on the same side as the observer (both
distances ~1e-3, i.e., ~0.1% of a unit away from the plane) is incorrectly excluded.
The fix's intent was to guard against *true* coplanar vertices (distance ≈ 0), but the
guard also fires for pairs where *both* distances are small but non-zero and same-sign.

**For the 0..10 coordinate range:** `Vector.crossProduct` on unit-scale vectors produces
a normal with magnitude O(1). The observer is at `(-10, -10, 20)` and typical face
centroids sit in 0..10; observer distances are typically O(10..30). Vertex distances for
non-coplanar faces are typically O(1..10). The product is O(10..300), comfortably above
1e-6. So for *typical* scenes the semantic ambiguity does not fire.

However, for thin prisms with depth << 1 (e.g., a floor tile with z-extent 0.1), a
vertex on an adjacent face could sit only 0.05 units from the reference plane, yielding
a product of `~20 * 0.05 = 1.0` — still fine. The failure mode only matters when *both*
distances are simultaneously small (< ~1e-3 each), which is geometrically unusual in
isometric scenes.

**Recommended fix (non-blocking):** Check each distance independently:

```kotlin
if (observerPosition * pPosition > 0.0 &&
    kotlin.math.abs(observerPosition) >= 1e-6 &&
    kotlin.math.abs(pPosition) >= 1e-6) {
    result++
}
```

This is semantically cleaner: same side (product positive) AND neither is coplanar.
The observer distance check can be lifted out of the loop since `observerPosition` is
loop-invariant.

**This does not explain the reported yellow-box regression** (missing faces). The
regression is more likely RL-2 (spurious cycle).

---

### RL-2 — Near-coplanar noise can break antisymmetry and add a dependency cycle (MED)

**Location:** `Path.kt:117-146`

**Description:**

`closerThan(pathA, observer)` returns:

```
countCloserThan(this, pathA, observer) - countCloserThan(pathA, this, observer)
```

Where each `countCloserThan` returns 0 or 1. The possible return values are −1, 0, +1.

The implement note claims "antisymmetry holds because for non-coplanar pairs at most
one direction can return 1." This is true for pairs with a geometrically clear
separation. But consider two faces that are nearly coplanar (e.g., the top face and an
adjacent side face of the same prism, sharing an edge):

- Each face has some vertices strictly on one side of the other's plane.
- Due to floating-point cancellation in the cross-product and dot-product chain, a
  vertex that *should* be exactly on the plane (shared edge vertex) may be pushed to
  ±1e-7 of the plane.
- With a 1e-6 epsilon (product threshold), a vertex at distance 1e-7 from pathA and an
  observer at distance ~20 produces a product of ~2e-6 > 1e-6 → **passes**.
- This same spurious-pass can occur in *both* directions simultaneously.

**Concrete failure path:**

Let face A and face B share an edge. Both have two shared-edge vertices (distance ≈ 0
from the other's plane, possibly ±1e-7 after FP) and two private vertices clearly on
one side. With the product threshold at 1e-6:

- The private vertices of A clearly pass `countCloserThan(A, B)` → 1.
- The private vertices of B clearly pass `countCloserThan(B, A)` → 1.
- `closerThan` returns `1 - 1 = 0`.

That case is handled correctly (0 → no edge added). But:

- If the shared-edge vertices of A are at distance +1.1e-7 (FP drift) and observer is
  at distance ~20: product = 2.2e-6 > 1e-6 → they vote for `countCloserThan(A, B) = 1`
  even when the private vertices also vote. `countCloserThan` is already 1 from the
  private vertices, so the shared-edge-vertex drift adds no *new* votes — `result` only
  becomes 1 once.

Wait — re-reading line 145: `return if (result > 0) 1 else 0`. The `result` counter
increments per vertex, but the return is clamped to {0, 1}. So shared-edge drift cannot
increase the return value beyond 1.

**Revised assessment:** The clamping `result > 0 → 1` means antisymmetry is:

- `countCloserThan(A, B)` ∈ {0, 1}
- `countCloserThan(B, A)` ∈ {0, 1}
- `closerThan` ∈ {-1, 0, +1}

For `closerThan(A, B) = 1` (A is closer): `countCloserThan(A, B) = 1` AND
`countCloserThan(B, A) = 0`. For the second to be 0, **none** of B's vertices must pass
the threshold relative to A's plane. But if B has a private vertex clearly on B's side
of A's plane, `countCloserThan(B, A) = 1` and `closerThan` would return 0, not ±1.

**The actual cycle risk is:** When floating-point drift causes
`countCloserThan(A, B) = 1` AND `countCloserThan(B, A) = 1` simultaneously — both
returning 1 — `closerThan(A, B) = 0` and `closerThan(B, A) = 0`. No edge is added in
`checkDepthDependency` (line 145 of DepthSorter). So the "cycle" is actually handled
as a tie, not a bidirectional dependency edge. **No hard cycle can be added** by the
binary clamping.

However, **the reported regression (missing faces on yellow box)** is consistent with
the tie-resolution pathway: when `closerThan` returns 0 for a pair that *should* have
a clear ordering, `DepthSorter` falls back to the pre-sort depth order (line 34,
`sortedByDescending { path.depth }`). If the average-depth metric agrees with the wrong
order for some face pair (e.g., the yellow box's right face has a slightly higher average
depth than an adjacent face of another prism), the fallback produces the wrong paint order.

The regression appears to be a case where the permissive threshold (intended to fix the
original bug) is now causing the opposite mis-classification for the yellow box geometry:
a face that should clearly be "closer" returns `countCloserThan = 0` because no vertex
passes the product threshold. This would happen if the yellow box has a face with all
vertices nearly coplanar with the reference plane AND the product threshold of 1e-6
rejects them all.

**Recommended investigation:** Log `observerPosition`, `pPosition`, and their product
for the yellow box's affected face pair. If any product falls in the [0, 1e-6) band for
vertices that should be classified as "closer", the threshold is too tight for that
geometry.

---

### RL-3 — Degenerate face (zero normal): EC-3 claim verification (MED)

**Location:** `Path.kt:117-121`

**Description:**

The implement note (§ EC-3) claims: "collinear first three points → cross product is
zero vector → n is zero → all dot products are 0 → observerPosition = 0 - d where
d = 0 → returns 0, which is the correct conservative answer."

**Tracing the math:**

1. Points[0], [1], [2] are collinear.  
   `AB = points[1] - points[0]`, `AC = points[2] - points[0]` are parallel.  
   `n = crossProduct(AB, AC) = Vector(0, 0, 0)`.

2. `d = dotProduct(n, OA) = 0*OA.x + 0*OA.y + 0*OA.z = 0`.

3. `observerPosition = dotProduct(n, OU) - d = 0 - 0 = 0`.

4. Inner loop: for each vertex P,  
   `pPosition = dotProduct(n, OP) - d = 0 - 0 = 0`.

5. `observerPosition * pPosition = 0 * 0 = 0 >= 0.000001` → **false**. `result`
   stays 0.

6. Returns `if (0 > 0) 1 else 0` = **0**.

EC-3 claim is **correct**. A degenerate face returns 0, no edge is added. The math
traces cleanly. No NaN or Infinity is produced (0/0 is avoided — no division occurs in
the cross/dot chain). The behaviour is safely conservative.

**Note:** The degenerate case also affects the *caller's* face when it is pathA:
`countCloserThan(pathA=degenerate, this, observer)` likewise returns 0 for the same
reason. `closerThan` returns 0 - 0 = 0. No edge. Conservative and correct.

**Recommendation:** Add an inline comment at line 121 or in the KDoc noting this
invariant explicitly. Currently the degenerate-face behaviour is only documented in the
implement note, not in source.

---

### RL-4 — 1e-6 absolute epsilon: adequacy for 0..10 vs 0..100 coordinate range (LOW)

**Location:** `Path.kt:137-139` (comment) and line 140

**Description:**

The KDoc states the typical coordinate range is "0..100" and the epsilon is 1e-6. The
actual scene in `DepthSorter.kt` line 37 shows the observer at `(-10, -10, 20)`, and
the WS10 sample uses building dimensions in roughly 1..5 units. The coordinate range in
practice appears to be 0..10, not 0..100.

For the cross-product/dot-product chain with inputs of magnitude O(10):
- `AB`, `AC` vectors have components O(10).
- `n = crossProduct(AB, AC)` has components O(100) (product of two O(10) values).
- `d = dotProduct(n, OA)` has magnitude O(1000).
- `observerPosition = dotProduct(n, OU) - d` has magnitude O(1000).
- `pPosition` for a vertex 1 unit from the plane has magnitude O(100).
- Product: O(100 * 1000) = O(100,000) >> 1e-6. Very safe.

For a vertex *intended* to be exactly on the plane (e.g., shared-edge vertex), FP
error in the chain accumulates from subtractions of O(1000) values. IEEE 754 double
has ~15 decimal digits of precision; for O(1000) values, absolute error is ~1000 *
2^-52 ≈ 2.2e-13. The product of observer-distance O(1000) and FP-error O(2.2e-13) is
~2.2e-10, well below 1e-6. So truly-coplanar vertices are correctly rejected.

**The epsilon is safe for 0..10 coordinates.** For 0..100, the same analysis scales
by 10x in each dimension: cross-product components O(10,000), d O(1,000,000), FP error
O(2.2e-10), product O(2.2e-4). This is still below 1e-6 for the product threshold...
wait — product would be O(1,000,000 * 2.2e-10) = O(2.2e-4) > 1e-6. A truly-coplanar
vertex in the 0..100 range would have a product of ~2.2e-4, which **exceeds 1e-6** and
would be incorrectly classified as "closer".

**Revised assessment:** For 0..100 coordinates, the 1e-6 epsilon is *too permissive*:
coplanar vertices that should be excluded will pass the threshold. The KDoc comment
says "typical 0..100 range" but the epsilon was only validated (implicitly) for the
0..10 range of the WS10 scene. If any scene uses coordinates in the tens-of-units range,
false-positive coplanar-vertex classifications are expected.

**Recommendation:** Either correct the KDoc to say "0..10 range" to match actual usage,
or derive a scene-scale-relative epsilon. This is the same deferred work flagged in the
implement note.

---

### RL-5 — `result0` removal: downstream usage confirmed absent (NIT)

**Location:** `DepthSorter.kt:124-149` (`checkDepthDependency`)

**Description:**

The old `countCloserThan` returned `(result + result0) / points.size` as a normalized
integer. The removal question is whether `result0` (the boundary-coplanar tally) was
ever consumed by `DepthSorter`.

**Confirmed not consumed.** `checkDepthDependency` reads `closerThan` (which calls
`countCloserThan` internally) and branches only on `< 0`, `> 0`, `== 0`. The *value*
of the `result0` tally was only ever visible through the integer division return of the
old `countCloserThan` — it affected the magnitude of the returned integer (e.g.,
whether 2/4 vertices coplanar made the result 1 or 0). `DepthSorter` never received the
raw `result0` count and never needed it. The removal is safe.

**Additionally:** The old `(result + result0) / points.size` return could produce values
> 1 for paths with many vertices (e.g., `result=3, result0=0, size=4` → returns 0, but
`result=3, result0=1, size=4` → returns 1). The new binary clamp `if (result > 0) 1 else 0`
is strictly simpler and more predictable. No downstream code depended on the multi-valued
return.

---

## Antisymmetry proof under noise (summary for RL-2)

The new implementation is **structurally antisymmetric** by construction:

- `countCloserThan` returns {0, 1} — clamped binary.
- `closerThan(A, B) = count(A→B) - count(B→A)` ∈ {-1, 0, +1}.
- `closerThan(A, B) + closerThan(B, A) = (count(A→B) - count(B→A)) + (count(B→A) - count(A→B)) = 0`.

This is a mathematical identity, not an assumption about the inputs. For **any** inputs
(including degenerate or noisy), `closerThan(A, B) + closerThan(B, A) = 0` exactly.
The antisymmetry test is therefore trivially guaranteed by the formula, not by the
numerical properties of the plane-side predicate.

**Implication:** The antisymmetry invariant cannot be broken by the new implementation.
The concern in the critical context is not applicable. Spurious edges are impossible (no
cycle can be added). The risk is the opposite: a tie (0) when the ordering should be
clear, leading to depth-fallback producing the wrong order.

---

## Summary

The numerical analysis confirms the fix is structurally sound for the typical WS10
geometry. The antisymmetry concern is provably resolved. The EC-3 degenerate-face
behaviour is correct. The reported yellow-box regression most likely originates from the
product threshold being *too tight* for a specific face pair (RL-2 tie-instead-of-order
pathway), or from the epsilon being borderline for 0..100-range coordinates (RL-4). The
highest-priority action is diagnostic logging to measure actual `pPosition` values for
the affected face pair.

**Result: PASS-WITH-CONCERNS.** No blockers. RL-1 (product semantics) and RL-2
(tie→fallback regression path) are the most actionable findings for the yellow-box
regression investigation.
