---
schema: sdlc/v1
type: review
review-command: reliability
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T00:00:00Z"
updated-at: "2026-04-28T00:00:00Z"
result: pass-with-concerns
diff-base: 97416ba
diff-head: HEAD
metric-findings-total: 9
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 3
metric-findings-low: 2
metric-findings-nit: 2
tags:
  - reliability
  - numerical-robustness
  - depth-sort
  - floating-point
refs:
  index: 00-index.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  shape: 02-shape.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: reliability — Newell cascade rewrite (`97416ba..HEAD`)

Scope: cumulative diff `97416ba..452b1fc` on `feat/ws10-interaction-props`.
Primary change: `Path.closerThan` rewritten as Newell Z→X→Y minimax cascade;
`countCloserThan` deleted; `signOfPlaneSide` added; round-3 `DEPTH_SORT_DIAG`
reverted. Secondary changes: `hasInteriorIntersection` gate in
`DepthSorter.checkDepthDependency`; two integration tests (AC-12, AC-13).

Files read:
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Point.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Vector.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`
- `.ai/workflows/depth-sort-shared-edge-overpaint/05-implement-depth-sort-shared-edge-overpaint.md`
- `.ai/workflows/depth-sort-shared-edge-overpaint/02-shape.md`

---

## Findings

| ID    | Severity | Confidence | Location                                        | Summary                                                                                    |
|-------|----------|------------|-------------------------------------------------|--------------------------------------------------------------------------------------------|
| RL-1  | HIGH     | High       | `Path.kt:273-275` — product threshold           | EPSILON applied to a **product** of two signed distances, not to each individually; this is a scale-dependent threshold that silently misbehaves as coordinates grow beyond ~100 units |
| RL-2  | HIGH     | High       | `Path.kt:257-282` — `signOfPlaneSide` degenerate | Zero-normal plane (3 collinear vertices in pathA) sets `n = (0,0,0)`: `observerPosition = 0`, `d = 0`, every `pPosition = 0`. Product `0 * 0 = 0`, fails `> EPSILON` and `< -EPSILON`; returns 0. Correct result, but also means NaN-poisoned normals would silently fall through to 0 rather than crash — the NaN path is unguarded |
| RL-3  | MED      | High       | `Path.kt:185-214` — screen-y sign convention    | Step 3 sign convention is **not exercised by any test**; the comment says "larger world (x+y) → farther → self entirely-below is farther → +1", but the direction is the inverse of step 1 without empirical validation. A wrong sign here produces a silent incorrect edge for screen-y-disjoint pairs that never reach plane-side |
| RL-4  | MED      | High       | `Path.kt:164-166` — EPSILON not scale-relative  | `EPSILON = 1e-6` is compared against `selfDepthMax - aDepthMin`, a raw difference of `Point.depth(angle)` values. For coordinates in [0,100], depth values reach ~87 units; the 1e-6 margin becomes sub-pixel noise-safe at small scale but would trivially pass at large scale. The opposite direction: at coord scale 0.001, the 1e-6 threshold would swallow real separations. EC-8 (coords >1000) is flagged as out-of-scope but the transition scale for step 1 is as low as coord ~10 for the "too tight" direction and coord ~1000 for the "too loose" direction |
| RL-5  | MED      | Medium     | `Path.kt:264-266` — NaN silent fall-through     | `<` and `>` comparisons against NaN always return false. If any vertex coordinate is NaN (e.g., after a divide-by-zero in a caller's transform chain), all minimax loops end with their initial `POSITIVE_INFINITY`/`NEGATIVE_INFINITY` sentinels intact; the disjoint-extent tests fire against ±Infinity and may return spurious ±1 rather than 0. No guard or `isFinite` check is present |
| RL-6  | LOW      | High       | `Path.kt:260-265` — first-three-point assumption | `signOfPlaneSide` always uses `pathA.points[0..2]` for the plane; for a nearly-degenerate face where only points 0–2 are near-collinear but point 3 is not, the plane normal is computed from the worst possible triangle. A more robust choice is the Newell polygon normal (average of per-triangle normals over all edges), which degrades gracefully for skewed quads |
| RL-7  | LOW      | High       | `DepthSorter.kt:114-119` — cycle fallback ordering | When cycles are present, `inDegree[i] > 0` nodes are appended in **depth-sorted index order** (outer loop `for i in 0 until length`). This is deterministic but may produce a visually poor fallback: the items stuck in cycles are not resorted by any depth metric among themselves. If polygon-split (step 7 deferred) eventually introduces cycles, the cycle-bucket fallback order becomes load-bearing and the current first-in-index-order behaviour is undocumented |
| RL-8  | NIT      | High       | `Path.kt:284-299` — ISO_ANGLE hardcoded         | `ISO_ANGLE = PI / 6.0` is baked into the cascade. If the caller ever uses a non-30° engine angle, the screen-x/y extent steps compute extents in a different projection than the engine renders. There is no mechanism to pass the engine angle to `closerThan`; the mismatch is currently invisible because `DepthSorter` hardcodes the same 30° observer, but it is a latent coupling |
| RL-9  | NIT      | High       | `DepthSorter.kt:37` — observer hardcoded        | `val observer = Point(-10.0, -10.0, 20.0)` inside `sort()` is not passed from `RenderOptions` or `IsometricEngine`. If the library ever exposes a configurable observer / camera position, the hardcoded value becomes a hidden divergence between the sort order and the visual projection |

---

## Detailed Findings

### RL-1 — EPSILON applied to a product, not to each distance independently (HIGH)

**Location:** `Path.kt:273-275`

```kotlin
val signed = observerPosition * pPosition
if (signed > EPSILON) anySameSide = true
if (signed < -EPSILON) anyOppositeSide = true
```

**Description:**

`EPSILON = 1e-6` is applied to the **product** `observerPosition * pPosition`, not to
each distance independently. This was identified as a pre-existing issue in round-1
review (`07-review-correctness.md § CR-1`) and is **still present** in the Newell
rewrite — the `countCloserThan` product check was carried over verbatim into
`signOfPlaneSide`.

The semantic intent is "vertex and observer are on the same side AND neither is
coplanar." The product threshold fuses both concerns onto a single scalar, creating
scale-dependent behaviour:

- `observerPosition = 1e-3`, `pPosition = 1e-3` → product = 1e-6, **passes** (both
  genuinely on same side, ~1 mm from plane in a typical scene — should count as
  "same side").
- `observerPosition = 1e-3`, `pPosition = 9e-4` → product = 9e-7, **fails** (both
  genuinely on same side, but product just below threshold — incorrectly rejected as
  coplanar).
- `observerPosition = 100.0`, `pPosition = 1e-8` → product = 1e-6, **passes** (vertex
  is 10 nm from the plane — should be coplanar — but observer is far away so the
  product is inflated to threshold).

For the current WS10 geometry (coords ~0..10, observer at ~(-10,-10,20)):
- `observerPosition` is typically O(100..3000) (cross-product magnitude ~O(1..10²),
  times observer distance ~O(20..30)).
- `pPosition` for a non-coplanar vertex is typically O(1..100).
- Product typically O(100..300000) — far above 1e-6. The bug is inert in the nominal
  range.

The bug fires when both values are simultaneously small — e.g., a thin face (small
normal magnitude due to nearly-collinear first three points) combined with a vertex
close to but not on the reference plane. In that case the product under-shoots 1e-6
even though both signed distances have the same sign and are geometrically meaningful.
Result: `anySameSide` remains false, `signOfPlaneSide` returns 0 instead of -1, the
cascade falls through to step 7 and returns 0, and DepthSorter adds no edge.

**Consequence:** For the nominal coordinate range this is a latent bug, not an
active regression. It will surface in scenes with thin/flat faces (e.g., decal quads
with z-extent < 0.01) or scenes where pathA's first three points are nearly collinear
(small normal), reducing the product scale.

**Recommended fix:** Check each distance independently, lifting the observer check
out of the loop:

```kotlin
val obsAbs = kotlin.math.abs(observerPosition)
if (obsAbs < EPSILON) return 0          // pathA's plane degenerate or observer coplanar

for (p in points) {
    val OP = Vector.fromTwoPoints(Point.ORIGIN, p)
    val pPosition = Vector.dotProduct(n, OP) - d
    when {
        pPosition > EPSILON  -> if (observerPosition > 0) anySameSide = true else anyOppositeSide = true
        pPosition < -EPSILON -> if (observerPosition > 0) anyOppositeSide = true else anySameSide = true
        // else: coplanar — skip
    }
}
```

This separates the two concerns and is sign-correct regardless of the magnitude of
`observerPosition`.

---

### RL-2 — Degenerate plane: zero normal is safe but NaN-poisoned coordinates are not (HIGH)

**Location:** `Path.kt:257-282`

**Description:**

**Zero-normal case (collinear first three points of pathA):**

- `AB` and `AC` are parallel → `n = Vector(0, 0, 0)`.
- `d = dotProduct(n, OA) = 0`.
- `observerPosition = dotProduct(n, OU) - d = 0`.
- Every `pPosition = 0`.
- `signed = 0 * 0 = 0`; neither `> EPSILON` nor `< -EPSILON`.
- `anySameSide = false`, `anyOppositeSide = false` → returns 0.

This is **correct and safe**: a degenerate plane yields no ordering, which is the
conservative result. No division occurs in the entire `signOfPlaneSide` body, so no
divide-by-zero. The EC-3 claim from 02-shape.md holds under the Newell rewrite.

**NaN-coordinate case (unguarded):**

If any vertex in `pathA.points[0..2]` has a NaN coordinate, the cross-product produces
NaN components. NaN comparisons (`<`, `>`) always return false in IEEE 754. The
consequence varies by step:

- **Steps 1–3 (minimax loops):** `if (d < selfDepthMin)` with `d = NaN` → false; the
  sentinel `POSITIVE_INFINITY`/`NEGATIVE_INFINITY` is never updated. After the loop,
  `selfDepthMax = NEGATIVE_INFINITY` and `selfDepthMin = POSITIVE_INFINITY` for the
  NaN path. Then `selfDepthMax < aDepthMin - EPSILON` becomes
  `NEGATIVE_INFINITY < something` → **true** → returns -1 spuriously. The cascade
  returns a definitive but wrong answer instead of 0.

- **Steps 5–6 (signOfPlaneSide with NaN normal):** `d = NaN`, `observerPosition = NaN`.
  `NaN > EPSILON` → false; `NaN < -EPSILON` → false. Returns 0. This is safe.

The minimax steps (1–3) are therefore more dangerous than the plane-side steps when
NaN coordinates are present: they will produce spurious definitive verdicts (-1 or +1)
because the sentinel initialisation is not updated by NaN inputs. The effect in
DepthSorter is a spurious draw-before edge for a face with NaN coordinates, leading to
undefined rendering order.

**Likelihood:** NaN coordinates can arise from `rotateX/Y/Z` if the angle is NaN
(e.g., computed from `asin` of an out-of-range input), or from `scale(..., 0.0)` if
followed by a divide (though `Path` itself contains no division). Currently no guard
exists in `Path.closerThan` or `DepthSorter.sort`. Since `Path` validates only
`points.size >= 3` (not coordinate finiteness), NaN can enter silently.

**Recommended fix (defensive):** At the top of `closerThan`, a single check:

```kotlin
// Guard: degenerate or NaN inputs fall through to 0 rather than producing spurious edges.
if (points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return 0
if (pathA.points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return 0
```

This is O(N) per call (same as the existing minimax loops) and eliminates the silent
spurious-verdict risk. Alternatively, validate at `Path.init` with a `require` — this
would be the earlier detection the api-design-guideline §7 recommends.

---

### RL-3 — Screen-y extent (step 3) sign convention untested and potentially inverted (MED)

**Location:** `Path.kt:212-214`

```kotlin
// Step 3: screen-y. screenY = -(sin*(x+y) + z). Smaller (more negative)
// screen-y means lower on screen — in iso this dominantly tracks
// "deeper into the scene" (larger world (x+y) → larger iso depth →
// farther). Self entirely-below pathA on screen → self farther → +1.
if (selfScreenYMax < aScreenYMin - EPSILON) return 1
if (aScreenYMax < selfScreenYMin - EPSILON) return -1
```

**Description:**

The comment's reasoning chain is:
1. `screenY = -(sin*(x+y) + z)` — so lower screen-y (more negative) = larger `(x+y)+z`.
2. Larger world `(x+y)` = larger iso depth = farther from observer.
3. Therefore: "self entirely below pathA" (selfScreenYMax < aScreenYMin) → self is
   farther → return +1.

Step (2) is incomplete: `depth(angle) = x*cos + y*sin - 2z`, not `x+y-2z`. For the
30° projection, `cos ≈ 0.866`, `sin = 0.5`. A low screen-y (more negative) could be
caused by a large z (which contributes **positively** to iso-depth, making the polygon
**closer**, not farther). The comment ignores the z-contribution to screen-y.

Example: polygon A is a floor tile at `z=0`, large `(x+y)=20`, screenY ≈ -10.
Polygon B is a ceiling tile at `z=10`, small `(x+y)=2`, screenY ≈ -(0.5*2 + 10) = -11.
B has lower screen-y but is at `z=10` → B is **closer** to the observer (iso-depth
= 2*0.866 + 2*0.5 - 20 = -16.27), while A has iso-depth = 20*0.866 + 20*0.5 - 0 ≈ 27.3.
So B is closer (smaller depth) — but the current code would return `+1` for self=B vs
pathA=A (self entirely below) claiming self=B is farther, which is **wrong**.

**Severity caveat:** The implement notes (Round 4) acknowledge "Steps 2/3 never fire
in the current test corpus" and "sign convention is iso-projection intuition rather
than empirically validated." The Round 4 notes explicitly flag this as a known risk.
It is recorded here at MED because the fix path is clear and the wrong sign could
produce a silent ordering regression for any screen-y-disjoint face pair that happens
to resolve at step 3 before reaching the plane-side test.

**Recommended fix:** Reverse the step 3 signs so that "self entirely below pathA on
screen" maps to self being closer (not farther), or remove step 3 entirely and let
the plane-side steps decide. Alternatively, add a unit test with a screen-y-disjoint
pair and verify the sign empirically before relying on this step.

---

### RL-4 — EPSILON not relative to coordinate scale; EC-8 transition point is lower than documented (MED)

**Location:** `Path.kt:298` (`EPSILON = 0.000001`) and the cascade comparisons at
lines 164-166, 206-207, 213-214.

**Description:**

Every cascade comparison uses `< aDepthMin - EPSILON` / `> selfDepthMin - EPSILON`
where the quantities are raw projection values (iso-depth, screen-x, screen-y) in
world units. For the standard 30° projection:

- `depth(PI/6) = x*cos(30°) + y*sin(30°) - 2z ≈ 0.866x + 0.5y - 2z`.
- For coordinates in [0, C], depth values span roughly `[−2C, 1.37C]`.

The EPSILON = 1e-6 threshold means the cascade calls a "strict separation" whenever
the gap between two extents exceeds 1 micron in world coordinates. This is:

- **For C = 10 (current WS10 range):** separation > 1e-6 world units ≈ safely
  above FP noise. No false separations from FP rounding.
- **For C = 100:** same analysis — still safe.
- **For C = 10000 (EC-8):** FP rounding on a 10000-unit depth sum (`0.866*10000`)
  is approximately `8660 * 2^-52 ≈ 1.9e-12` per vertex; over 4 vertices summed for
  a depth value, absolute rounding error is ~O(1e-11). Still below 1e-6. Safe
  direction: the threshold does not collapse.
- **Coplanar vertex risk at large scale:** The product-threshold issue in RL-1
  could cause "too permissive" classifications at large scale, but the raw extent
  EPSILON used in steps 1-3 is a difference, not a product, so it does not share
  the product-scale problem. Steps 1-3 EPSILON is safe up to very large coordinates.

However, the documented EC-8 concern is about the **plane-side product** (RL-1),
not the extent EPSILON. The 02-shape.md EC-8 note "coordinates above ~1000 would
warrant relative-epsilon scaling" refers to the product in `signOfPlaneSide`, not
to the extent comparisons. The extent comparisons are safe at all practical scales.

**Net assessment:** EPSILON = 1e-6 in the extent steps (1–3) is sound for any
reasonable coordinate range. The scale concern belongs to RL-1 (the product in
step 5). This finding is downgraded from what a raw reading of EC-8 might suggest.

**Recommendation:** Add a comment in the `EPSILON` companion declaration distinguishing
its role in steps 1-3 (extent gap, scale-safe) from its role in steps 5-6 (via
product, scale-dependent per RL-1).

---

### RL-5 — NaN propagation through minimax sentinels produces spurious definitive results (MED)

*(Partially covered in RL-2; this entry isolates the minimax-specific failure mode.)*

**Location:** `Path.kt:149-202` — the four minimax loops.

**Description:**

Each minimax loop initialises sentinels at `±INFINITY` and updates them via `<` and `>`
comparisons. IEEE 754 specifies that all ordered comparisons involving NaN return false.
If any vertex `p` returns `NaN` from `p.depth(ISO_ANGLE)` or from the screen projection
`(p.x - p.y) * cosAngle`, the sentinel is never updated for that vertex.

For self's loop: if **all** of self's vertices produce NaN depths, `selfDepthMin =
+INFINITY` and `selfDepthMax = -INFINITY`. Then:
- `selfDepthMax < aDepthMin - EPSILON` → `-INFINITY < (finite value)` → **true** →
  returns -1 (self declared "entirely closer").

This is a spurious conclusion from all-NaN input. The result is a false dependency
edge in DepthSorter, which may cause one face to always be drawn before another
regardless of actual geometry.

For partial NaN (some vertices NaN, some finite): the NaN vertices silently skip
the sentinel update, so the loop computes min/max over only the finite vertices.
This is a silent data corruption — the computed extent is smaller than the true
extent, increasing the chance of a false "disjoint" conclusion.

**Combined with RL-2 recommendation:** a single `isFinite` guard at the top of
`closerThan` eliminates both this and RL-2's NaN risk path.

---

### RL-6 — First-three-vertex plane is fragile for skewed quads (LOW)

**Location:** `Path.kt:260-262`

```kotlin
val AB = Vector.fromTwoPoints(pathA.points[0], pathA.points[1])
val AC = Vector.fromTwoPoints(pathA.points[0], pathA.points[2])
val n = Vector.crossProduct(AB, AC)
```

**Description:**

The plane normal is computed from three points: index 0, 1, 2. For axis-aligned
prism faces (the dominant case in this library), these three points always span
the full face and the normal is exact. However:

1. If the first three points happen to be nearly collinear (e.g., a face where
   points are listed 0°, 0.1°, 180°, 270° around the face), the computed normal
   has very small magnitude, amplifying the product-scale problem in RL-1.
2. For a non-planar quad (four points not exactly coplanar due to FP rounding),
   using only the first triangle may give a slightly different normal than using
   the diagonally opposite triangle.

**Consequence:** For the current codebase's axis-aligned prism faces, points[0..2]
always form a right angle and the normal is correct. This is a latent robustness
concern for any caller that passes non-standard polygon winding.

**Recommendation:** Document in `signOfPlaneSide`'s KDoc that the plane is defined
by `points[0..2]` and that callers must ensure these are non-collinear. Alternatively,
compute the Newell polygon normal (sum of cross products of consecutive edges) for
better robustness with arbitrary polygons, at the cost of an O(N) instead of O(1)
normal computation.

---

### RL-7 — Cycle fallback ordering is undocumented and depth-unordered (LOW)

**Location:** `DepthSorter.kt:114-119`

```kotlin
// Append any remaining items (circular dependencies — fallback)
for (i in 0 until length) {
    if (inDegree[i] > 0) {
        sortedItems.add(depthSorted[i])
    }
}
```

**Description:**

When cycles are present in the dependency graph, items stuck in cycles are appended
in `depthSorted` index order (i.e., in descending `path.depth` order, the pre-sort).
This is deterministic but is not a topologically valid ordering for the cycle members
among themselves.

For the current codebase, the implement notes state "local test suite shows no
cycles surfacing in the AC-12/AC-13 integration scenes." The fallback is therefore
never exercised by any test. If polygon-split (cascade step 7) is deferred
indefinitely, the only observable cycle trigger is a pair where `closerThan` returns
0 and DepthSorter's no-edge behaviour happens to leave cycles. The implement notes
say "DepthSorter's append-on-cycle fallback handles any residual cycles" — but the
fallback's ordering quality is never tested.

**Symptom if cycles surface:** Faces in the cycle will appear in their initial
depth-sorted order, which is typically back-to-front. This is visually correct
for non-overlapping faces and only wrong if two cycle-member faces actually overlap
in screen space. Graceful degradation, not a crash.

**Recommendation:** Add a `// TODO: polygon-split (cascade step 7) is deferred;
// when implemented, this fallback should be unreachable for non-pathological scenes`
comment to document the expected state of this branch.

---

### RL-8 — ISO_ANGLE hardcoded in cascade; engine angle coupling (NIT)

**Location:** `Path.kt:290` and cascade steps 1–3

```kotlin
private const val ISO_ANGLE: Double = PI / 6.0
```

**Description:**

`ISO_ANGLE` is a compile-time constant. `DepthSorter.sort` uses the same hardcoded
30° projection via `Point.depth()` (which also uses `x+y-2z` internally — not even
the same formula as `Point.depth(PI/6)`, per the KDoc on `Point.depth()`). The
cascade's steps 1–3 call `p.depth(ISO_ANGLE)` which is `x*cos(30°)+y*sin(30°)-2z`,
while `path.depth` (the pre-sort at `DepthSorter.kt:34`) calls `p.depth()` which
is `x+y-2z` — a different formula. These two depth metrics are in agreement for
the sign ordering in practice (both have `x`, `y`, and `-2z` contributions), but
they are not identical. If the depth pre-sort and the cascade's extent tests disagree
on which polygon is "farther," the Kahn tiebreaker could pick a suboptimal initial
ordering.

The coupling is currently invisible because `IsometricEngine` is also hardcoded at
30°. If the engine ever exposes a variable angle, `closerThan` would silently use
the wrong projection.

---

### RL-9 — Observer hardcoded in `DepthSorter.sort`; not passed from `RenderOptions` (NIT)

**Location:** `DepthSorter.kt:37`

```kotlin
val observer = Point(-10.0, -10.0, 20.0)
```

**Description:**

Same structural concern as RL-8 but for the observer position. The observer is
hardcoded inside `sort()` rather than derived from `RenderOptions` or the engine
configuration. `Path.closerThan` takes `observer` as a parameter and is therefore
testable with any observer, but the production call site pins it at `(-10,-10,20)`
with no override mechanism.

This is a latent API-evolution concern. If `RenderOptions` grows a camera or
observer field, the `DepthSorter` hardcode becomes a silent divergence.

---

## Antisymmetry under the Newell cascade

The antisymmetry property `closerThan(A,B) + closerThan(B,A) == 0` holds by
construction for all six steps:

- **Steps 1–3 (extent minimax):** for each axis, `selfMax < aMin - ε` and
  `aMax < selfMin - ε` are mutually exclusive (they cannot both be true simultaneously
  because if selfMax < aMin then aMax > aMin > selfMax, so `aMax < selfMin` would
  require `aMax < selfMin <= selfMax < aMin <= aMax`, a contradiction). So at most one
  of the two role-swapped calls returns non-zero. If one returns -1, the other returns
  nothing from this step (falls through), and later steps will also be symmetric.
- **Steps 5–6 (plane-side):** step 5 calls `signOfPlaneSide(pathA, observer)` and
  returns `sFwd`; step 6 calls `pathA.signOfPlaneSide(this, observer)` and returns
  `-sRev`. Under role swap, the step-5 of the swapped call is the step-6 of the
  original (with sign negated), so antisymmetry holds structurally.
- **Step 7:** both return 0 symmetrically.

The antisymmetry invariant test passes by construction. **Hard cycles cannot be added
to the dependency graph by this predicate.**

---

## DEPTH_SORT_DIAG revert verification

Round 3 added `private const val DIAG = true` and extensive `System.err` emissions.
Round 4 claims to revert these. Current `DepthSorter.kt` source (read above) contains:

- No `DIAG` constant or `DEPTH_SORT_DIAG` string.
- No `first3` helper.
- No `System.err` calls.
- The `intersects` and `cmpPath` locals (lines 141-147) are retained from round 3
  as a readability improvement; these are read-only locals that feed no I/O path.

**Revert is clean.** The retained `intersects`/`cmpPath` locals are benign.

---

## Kahn fallback: graceful degradation under deferred step 7

If `closerThan` returns 0 for a pair that genuinely needs ordering (a cycle
participant), `DepthSorter` adds no edge and Kahn processes the pair in
depth-sorted pre-order (back-to-front by centroid depth). The visual symptom
is that faces in the unresolved pair are drawn in centroid-depth order, which
is usually correct for non-overlapping faces. For overlapping faces (which passed
the `hasInteriorIntersection` gate), a wrong centroid order produces over-painting
where the farther face appears on top. This is visible as a z-fighting artefact but
does not crash the renderer. The `ORDER-CYCLE` diagnostic log path from round 3 was
the intended observability tool; its removal means a future operator would need to
add new diagnostic logging to detect cycle members.

**Recommended observability:** A `if (BuildConfig.DEBUG) Log.w("DepthSorter", "cycle fallback for face N")` (or equivalent `kotlin.assert`) at line 116 would surface
cycles in debug builds without impacting release performance.

---

## Summary

The Newell cascade rewrite is structurally sound and the antisymmetry invariant is
guaranteed by construction. The DEPTH_SORT_DIAG revert is clean. The most actionable
reliability concerns are:

1. **RL-1 (HIGH):** EPSILON applied to the product `observerPosition * pPosition` in
   `signOfPlaneSide` — a dimension-mixing threshold that becomes unreliable for thin
   faces or near-degenerate normals. The fix is to test each distance independently
   with individual per-distance comparisons.
2. **RL-2/RL-5 (HIGH/MED combined):** No `isFinite` guard in `closerThan` or `Path.init`;
   NaN vertex coordinates produce spurious definitive verdicts from the minimax sentinels
   (returning ±1 instead of 0), and an all-zero normal falls through to 0 safely but
   NaN normals do not.
3. **RL-3 (MED):** The screen-y step 3 sign convention is untested and the inline
   comment's reasoning ignores the z-contribution to screen-y, suggesting the sign is
   inverted for faces where z dominates the screen-y range.

No blockers. **Result: PASS-WITH-CONCERNS.**
