---
schema: sdlc/v1
type: review
review-command: performance
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T00:00:00Z"
updated-at: "2026-04-28T00:00:00Z"
result: pass
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 3
metric-findings-low: 2
metric-findings-nit: 2
tags:
  - performance
  - depth-sort
  - hot-loop
  - allocation
refs:
  index: 00-index.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  prior-performance-review: 07-review-performance.md
---

# Review: performance

Scope: cumulative diff `97416ba..HEAD` on `feat/ws10-interaction-props`.
Primary change: `Path.closerThan` replaced with the Newell Z→X→Y minimax cascade
(commit `452b1fc`). Secondary change: `DepthSorter.checkDepthDependency` now calls
`hasInteriorIntersection` instead of `hasIntersection` as the gate (commit `9cef055`).

Hot-path position: `checkDepthDependency` is called O(N²) per frame for N visible
faces (N ≈ 30 for LongPressSample, ≈ 18 for NodeId, potentially hundreds for
complex scenes with broad-phase disabled).

---

## Findings

| ID   | Severity | Confidence | Location                                                            | Summary                                                                                         |
|------|----------|------------|---------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|
| PF-1 | HIGH     | High       | `Path.kt:176-202` — Steps 2/3 scan all vertices even when Step 1 returns | Steps 2/3 compute screen-X/Y for all vertices of BOTH polygons unconditionally before Step 1 has a chance to early-exit |
| PF-2 | MED      | High       | `Path.kt:257-282` — `signOfPlaneSide` allocates 5 `Vector` per call     | `fromTwoPoints` + `crossProduct` + three more `fromTwoPoints/dotProduct` calls each allocate a `Vector` object |
| PF-3 | MED      | High       | `Point.depth(angle)`: `cos`/`sin` recomputed per vertex per call         | `cos(ISO_ANGLE)` and `sin(ISO_ANGLE)` are recomputed inside `Point.depth(angle)` on every vertex visit in Step 1 |
| PF-4 | MED      | High       | `DepthSorter.kt:141-144` — `hasInteriorIntersection` allocates 2×N `Point` + list per call | The map `{ Point(it.x, it.y, 0.0) }` creates N+M heap-allocated `Point` wrappers on every pair |
| PF-5 | LOW      | High       | `IntersectionUtils.kt:230-231` — `polyA/polyB` list allocation per call  | `pointsA + pointsA[0]` creates a new `List<Point>` each call inside `hasInteriorIntersection`   |
| PF-6 | LOW      | Med        | `signOfPlaneSide`: Steps 5/6 fire only on mixed-straddle pairs; real cascade resolution rate unvalidated | Claims most pairs resolve at Step 1 or Step 5; no micro-benchmark exists to confirm              |
| PF-7 | NIT      | High       | `IntersectionUtils.hasInteriorIntersection` strict-inside: O(N²) per polygon pair | For N-vertex polygons: each of N vertices × N edges; fine at N=4 but worth noting               |
| PF-8 | NIT      | Med        | No micro-benchmark for `closerThan` in the repo                          | Gap in coverage; hard to detect future regressions in the hot loop                              |

---

## Detailed Findings

### PF-1 — Steps 2/3 computed unconditionally before Step 1's early-exit (HIGH)

**Location:** `Path.kt` lines 176-202 (screen-X/Y loop, Steps 2/3)

**Code path:**

```kotlin
val cosAngle = cos(ISO_ANGLE)   // line 176
val sinAngle = sin(ISO_ANGLE)   // line 177

var selfScreenXMin = Double.POSITIVE_INFINITY
// … four more variable declarations …
for (p in points) {             // iterates all self vertices
    val sx = (p.x - p.y) * cosAngle
    val sy = -(sinAngle * (p.x + p.y) + p.z)
    // … four comparisons …
}
var aScreenXMin = Double.POSITIVE_INFINITY
// …
for (p in pathA.points) {       // iterates all pathA vertices
    // same arithmetic …
}
// Step 2 test
if (selfScreenXMax < aScreenXMin - EPSILON) return -1
```

**Problem:** The Step 2/3 computation (screen-X and screen-Y extent for both polygons,
4×N floating-point operations) runs **before** Step 1's early-exit tests at lines 164/166
only if Step 1 does not fire. However, the actual code structure computes ALL of Step 1
then checks the early exits — so that is fine. The **real issue** is subtler: Step 1 and
Steps 2/3 are sequential code blocks with no interleaving, but Steps 2/3 are computed
**together as a combined loop** that iterates both polygons fully before either Step 2 or
Step 3 is tested. The loop (lines 183-202) computes screen-X and screen-Y for all vertices
in one pass — this is a correct micro-optimization. But `cos(ISO_ANGLE)` and `sin(ISO_ANGLE)`
are computed at lines 176-177 even when Step 1 has already returned early. Looking at the
code again:

```kotlin
// Step 1 …
if (selfDepthMax < aDepthMin - EPSILON) return -1   // line 164 — returns here
if (aDepthMax < selfDepthMin - EPSILON) return 1    // line 166 — returns here

// Steps 2/3 — only reached if Step 1 does NOT fire
val cosAngle = cos(ISO_ANGLE)   // line 176
```

This is actually correct — `cos`/`sin` are computed only after Step 1 falls through.
**The true HIGH finding** is a different structural issue: Steps 1, 2, and 3 each compute
extents for BOTH polygons before testing, meaning that if Step 2 fires (screen-X disjoint)
it has already fully computed screen-Y for both polygons unnecessarily, and if Step 1 fires
the loop bodies for Step 2/3 never execute (correct). Within the combined Step 2/3 loop,
screen-X and screen-Y are computed together — this avoids a second pass over the vertices
and is correct. However, the **combined loop computes screen-Y for both polygons even when
Step 2 will fire immediately** (no overlap in screen-X). This means 2×N wasted
floating-point ops per Step-2-firing pair. For typical isometric scenes with many
screen-X-adjacent but non-Y-adjacent faces, this is measurable wasted work.

**Restructuring to eliminate this:**

```kotlin
// After Step 1 falls through:
val cosAngle = cos(ISO_ANGLE)
val sinAngle = sin(ISO_ANGLE)

// Compute screen-X only first:
var selfScreenXMin/Max …
for (p in points) { val sx = (p.x - p.y) * cosAngle; update min/max }
var aScreenXMin/Max …
for (p in pathA.points) { val sx = …; update min/max }
if (selfScreenXMax < aScreenXMin - EPSILON) return -1   // Step 2 early-exit
if (aScreenXMax < selfScreenXMin - EPSILON) return 1

// Only then compute screen-Y:
…
```

This adds one extra variable-declaration block but avoids screen-Y computation for
all Step-2-firing pairs. For the LongPress 3×3 grid (30 faces, ~435 pairs checked before
the `hasInteriorIntersection` gate), the cascade's early-exit profile determines whether
this matters. Given that Steps 2/3 are not exercised by any current unit test (noted in
the implement doc, Round 4), real scenes may exercise them more frequently. Without a
benchmark the savings estimate is uncertain but the restructuring is low-risk.

**Severity: HIGH** — affects every pair that passes Step 1 but would be resolved by
Step 2, performing 2×N redundant screen-Y operations per such pair. Combined with
PF-4 (allocations before the gate), this is the dominant per-call waste.

---

### PF-2 — `signOfPlaneSide` allocates 5 `Vector` objects per call (MED)

**Location:** `Path.kt` lines 260-265 (`signOfPlaneSide` setup)

```kotlin
val AB = Vector.fromTwoPoints(pathA.points[0], pathA.points[1])   // alloc
val AC = Vector.fromTwoPoints(pathA.points[0], pathA.points[2])   // alloc
val n  = Vector.crossProduct(AB, AC)                              // alloc
val OA = Vector.fromTwoPoints(Point.ORIGIN, pathA.points[0])      // alloc
val OU = Vector.fromTwoPoints(Point.ORIGIN, observer)             // alloc
```

Plus, inside the vertex loop (lines 270-275):

```kotlin
val OP = Vector.fromTwoPoints(Point.ORIGIN, p)                    // alloc × N
```

Total allocations per `signOfPlaneSide` call: **5 + N** `Vector` objects (N = vertex
count; 4 for prism faces → **9 Vector allocations per call**). `closerThan` calls
`signOfPlaneSide` up to twice (lines 225 and 231), so worst-case **18 Vector
allocations per pair** for pairs that reach Step 5/6.

**Comparison with old `countCloserThan`:** The prior code allocated 4 `Vector` per
call (N=4 prism face): 1 for `AB`, 1 for `AC`, 1 for `n`, 1 per-vertex `OP`. In
`closerThan`, called twice = 8 `Vector` allocations per pair. The new code, when
both `signOfPlaneSide` calls fire, allocates **18** — a **2.25× regression** vs. old
code for pairs that reach Step 5/6. For pairs resolved at Step 1 (no `signOfPlaneSide`
calls), allocation cost is **zero** — a large improvement.

**Mitigation candidates:**
- `OA = Vector.fromTwoPoints(Point.ORIGIN, pathA.points[0])` simplifies to
  `Vector(pathA.points[0].x, pathA.points[0].y, pathA.points[0].z)` — same cost,
  but `OU = Vector.fromTwoPoints(Point.ORIGIN, observer)` is constant per `closerThan`
  call (observer is the same `Point(-10,-10,20)` every time) and could be hoisted to a
  companion constant pre-computed once.
- `OP = Vector.fromTwoPoints(Point.ORIGIN, p)` is equivalent to
  `Vector(p.x, p.y, p.z)`. The dot product `n.x*p.x + n.y*p.y + n.z*p.z` can be
  inlined without constructing `OP` at all:
  ```kotlin
  val pPosition = n.x * p.x + n.y * p.y + n.z * p.z - d
  ```
  Eliminating N per-vertex `Vector` allocations reduces to 5 (setup) + 0 (loop) per call.
- `observer` never changes between calls in `DepthSorter.sort` (always
  `Point(-10,-10,20)`). The observer vector `OU` and `observerPosition` could be cached
  in the companion object or passed as pre-computed doubles. This alone saves 2 `Vector`
  allocations across the double-call path.

**Severity: MED** — regression vs. prior code for mixed-straddle pairs (Step 5/6),
improvement for pairs resolved early. Net impact depends on cascade resolution
distribution (see PF-6). For well-structured scenes where Step 1 resolves most pairs,
actual regression is minimal. Inline fix is low-risk.

---

### PF-3 — `cos`/`sin` recomputed inside `Point.depth(angle)` per vertex per call (MED)

**Location:** `Point.kt` lines 220-223

```kotlin
fun depth(angle: Double): Double {
    val cosA = cos(angle)   // JVM intrinsic but still ~10–20 cycles
    val sinA = sin(angle)
    return x * cosA + y * sinA - 2 * z
}
```

**Hot path:** Step 1 of `closerThan` calls `p.depth(ISO_ANGLE)` for every vertex of
both polygons (lines 152 and 159). For a 4-vertex face pair: **8 `cos` calls + 8 `sin`
calls** per `closerThan` invocation, all with the same argument `ISO_ANGLE = PI / 6`.
The JVM may or may not CSE (common-subexpression-eliminate) calls to `cos` across a loop
— typically it does NOT because `cos` is not marked pure from the JIT's perspective on
Android's ART runtime.

**Already partially addressed:** `ISO_ANGLE` is a `const val` in the companion object,
so the angle value itself is not recomputed. But `cos(ISO_ANGLE)` and `sin(ISO_ANGLE)`
are.

**Fix:** Add two companion constants to `Path`:

```kotlin
private companion object {
    private const val ISO_ANGLE: Double = PI / 6.0
    private const val EPSILON: Double = 0.000001
    private val ISO_COS: Double = cos(ISO_ANGLE)   // computed once at class-load time
    private val ISO_SIN: Double = sin(ISO_ANGLE)
}
```

Then inline Step 1 without calling `Point.depth(angle)`:

```kotlin
for (p in points) {
    val d = p.x * ISO_COS + p.y * ISO_SIN - 2 * p.z
    …
}
```

This replaces 8 `cos` + 8 `sin` calls with 8 multiply-add chains — roughly an order
of magnitude faster at Step 1. Note that `cos(ISO_ANGLE)` and `sin(ISO_ANGLE)` are
already computed at lines 176-177 for Steps 2/3 as `val cosAngle = cos(ISO_ANGLE)`,
so the same angle is computed up to **three times per `closerThan` call** (once in
each `p.depth(ISO_ANGLE)` call, plus lines 176-177). Consolidating to companion
constants resolves all three sites.

**Severity: MED** — each `cos`/`sin` on ART is ~15-25 ns (transcendental intrinsic).
8 calls per pair = ~160 ns per Step-1 evaluation, vs. ~1-2 ns for inlined
multiply-adds. Over 435 pairs (30-face scene) = ~70 µs per frame, which is marginal
but detectable on a profiler.

---

### PF-4 — `hasInteriorIntersection` allocates 2×N `Point` wrappers per call (MED)

**Location:** `DepthSorter.kt` lines 141-144

```kotlin
val intersects = IntersectionUtils.hasInteriorIntersection(
    itemA.transformedPoints.map { Point(it.x, it.y, 0.0) },
    itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
)
```

**Problem:** This allocates two `ArrayList<Point>` plus N + M `Point(x, y, 0.0)` heap
objects on **every pair check** before the gate even fires. For the 30-face scene with
~435 pairs, this is ~435 × (4+4) = 3480 `Point` allocations plus 870 `ArrayList`
allocations per frame, just for the gate. This is **the dominant allocation source in
the hot path**, unchanged from prior review (PF-3 in the old report).

Note that Round 2 switched from `hasIntersection` to `hasInteriorIntersection` but
the call-site wrapping pattern is identical — no change in allocation profile.

**Fix:** Overload `hasInteriorIntersection` to accept `List<Point2D>` (or pass raw
`DoubleArray` coordinates) so the `Point` wrapping can be avoided. This is a wider
refactor than the cascade fixes; `IntersectionUtils` would need a second code path
or a type-erased shared helper.

**Severity: MED** — pre-existing cost not worsened by this diff, but it is the
dominant allocation site and would mask any gains from fixing PF-2/PF-3 on a profiler.

---

### PF-5 — `polyA` / `polyB` list allocation inside `hasInteriorIntersection` (LOW)

**Location:** `IntersectionUtils.kt` lines 230-231

```kotlin
val polyA = pointsA + pointsA[0]   // allocates new List<Point>
val polyB = pointsB + pointsB[0]   // allocates new List<Point>
```

These closed-polygon lists are created on every `hasInteriorIntersection` call,
allocating N+1 + M+1 element lists. For 4-vertex polygons this is 5+5 = 10 element
lists per call. Combined with PF-4's upstream wrapping, every pair check allocates:
`4+4` Point wrappers (PF-4) + `5+5` element lists (PF-5) = significant per-call cost.

The same pattern exists in `hasIntersection` and is pre-existing. Flagged here because
`hasInteriorIntersection` was added in this diff and copies the same pattern.

**Fix:** Replace closed-polygon traversal with modulo indexing: `poly[(i+1) % poly.size]`
inside the loops, eliminating the extra-allocation entirely. This is a mechanical
refactor with no semantic change.

**Severity: LOW** — each pair adds 2 list allocations; manageable at N=4, but a clean
modulo-index refactor would eliminate them with no complexity cost.

---

### PF-6 — Early-exit efficacy of the cascade unvalidated (LOW)

**Location:** Round 4 implement doc, "Notes on Design Choices": *"Steps 2/3 never fire
in the current test corpus."*

The implementer notes that in all 12 unit tests, pairs resolve at either Step 1 (iso-
depth extent) or Step 5 (plane-side forward). This is consistent with typical isometric
scenes where faces have clearly disjoint iso-depths or clearly one-sided plane
relationships. However:

1. **Step 1 resolution rate for ground-vs-wall pairs:** The round-3 investigation showed
   that the wall-vs-floor pair (the primary regression) resolves at Step 5 (plane-side),
   NOT Step 1. For the LongPressSample (30 faces), every prism vertical face paired with
   the ground top must traverse Steps 1-4 before Step 5 fires. For a 3×3 grid with one
   ground platform: 9 prisms × 2 vertical faces × 1 ground = 18 pairs reaching Step 5.
   18 pairs × 2 `signOfPlaneSide` calls (both forward and reverse — forward fires, reverse
   not needed) = 18 `signOfPlaneSide` calls per frame for this class alone.

2. **Steps 2/3 coverage gap:** Since no test exercises Steps 2/3, the sign convention
   for screen-X and screen-Y is "conservative rather than empirically validated" (implement
   doc). A future scene could expose a wrong sign in Steps 2/3 that performance testing
   would not detect (correctness gap rather than performance gap, but raised here because
   it is a consequence of the unexercised code path).

3. **No micro-benchmark exists** to validate the claim that "most resolve at Step 1 or
   Step 5." For a complex scene (100+ faces), the Step 1 hit rate is unknown.

**Severity: LOW** — the cascade structure is correct and sufficient for the verified
scenes. The gap is observability, not a confirmed hotspot. Adding a micro-benchmark
(see PF-8) would close this gap.

---

### PF-7 — `hasInteriorIntersection` strict-inside check: O(N²) per polygon pair (NIT)

**Location:** `IntersectionUtils.kt` lines 279-294

For each vertex of polygon A (N vertices), `isPointInPoly` is O(M) and
`isPointCloseToPoly` is O(M) — combined O(M) per vertex. Then the reverse direction
(each vertex of B checked against A). Total: O(N×M + M×N) = O(N×M). For N=M=4
(all prism faces): 16+16 = 32 inner operations. Acceptable. For N=4, M=100 (ground
platform vs prism face): 400+400 = 800 operations.

`Point.distanceToSegment` internally calls `sqrt` (via `distanceToSegmentSquared`).
For `isPointCloseToPoly` checking N edges: N `sqrt` calls per vertex. For the 4-vertex
ground polygon and one wall vertex: 4 `sqrt` calls. Across 18 wall-vs-ground pairs:
18 × 4 × 4 = 288 `sqrt` calls per frame for this class. `sqrt` on ART: ~5-10 ns each
→ ~1.5-3 µs per frame. Negligible at this scale but worth noting if polygon complexity
increases.

**Note:** `isPointCloseToPoly` could be replaced with `isPointCloseToPolySquared` that
compares `distanceToSegmentSquared` against `EDGE_BAND * EDGE_BAND`, eliminating the
`sqrt` entirely.

**Severity: NIT** — completely fine at N=4; no action needed unless polygon vertex
counts grow beyond ~10.

---

### PF-8 — No micro-benchmark for `closerThan` (NIT)

**Location:** Repo-wide — `isometric-core/src/test/...`

No JMH or Benchmark annotation test exists for `Path.closerThan` or
`DepthSorter.sort`. Given that this is the innermost kernel of the depth-sort hot
path and has been reworked four times in this workflow alone, a micro-benchmark would
provide:

1. Regression detection for future cascade changes.
2. Empirical data on Step 1 vs Step 5 resolution rate for realistic scenes.
3. Baseline for validating the allocation-reduction fixes proposed in PF-2/PF-3.

The absence is a coverage gap, not a current blocker. A simple
`@BenchmarkMode(Mode.AverageTime)` JMH benchmark in `isometric-core/src/jmh/` with
representative LongPress / Alpha scene geometries would take ~1 hour to write and
provide durable observability for all future changes.

**Severity: NIT** — no immediate action needed. Recommended as a follow-on for any
further cascade iteration.

---

## Allocation regression summary vs. prior code (`countCloserThan`)

| Scenario | Old (`countCloserThan` × 2) | New (Newell cascade) | Delta |
|---|---|---|---|
| Pair resolved at Step 1 (Z-extent disjoint) | 8 `Vector` allocs | 0 `Vector` allocs | -8 (improvement) |
| Pair resolved at Step 5 (plane-side forward) | 8 `Vector` allocs | 5+N per `signOfPlaneSide` × 1 call = 9 allocs | +1 (slight regression) |
| Pair resolved at Step 6 (plane-side reverse) | 8 `Vector` allocs | (5+N)×2 = 18 allocs | +10 (regression for this class) |
| PF-4 (Point wrapping — unchanged) | 2N `Point` allocs | 2N `Point` allocs | 0 (no change) |

For the LongPress scene's dominant pair class (vertical walls vs. ground, Steps 1-5):
new code allocates ~9 vs. old 8 — negligible regression. For the rare Step-6 pairs:
18 vs. 8 — a 2.25× regression, but Step 6 fires only for genuinely-ambiguous mixed-
straddle pairs which are rare in well-structured isometric scenes.

---

## Gate vs. cascade cost: `hasInteriorIntersection` + Newell vs. old `hasIntersection` + vote

**Prior approach:** `hasIntersection` (lenient) gate + `countCloserThan` vote.
**New approach:** `hasInteriorIntersection` (strict) gate + Newell cascade.

`hasInteriorIntersection` adds the strict-inside fallback loop (O(N×M) with `sqrt` via
`isPointCloseToPoly`). For pairs that pass the AABB check but fail the SAT crossing
test (e.g., containment cases), the new gate does more work than the old. For pairs
that fail at the AABB or SAT stage, cost is identical.

However, the new gate correctly rejects shared-edge-only pairs that the old gate
admitted. This reduces the number of pairs reaching `closerThan` — the cascade is
never called for pairs rejected by the gate. The net effect on pairs that the old gate
admitted but are now rejected by the strict gate: those pairs no longer hit
`closerThan` at all, saving the cascade cost entirely. Whether gate overhead > saved
cascade cost depends on the ratio of rejected-by-strict-gate pairs to admitted pairs.
For the LongPress 3×3 grid (where the amendment-1 bug was over-aggressive edges from
adjacent cube pairs), the strict gate prunes O(N) adjacent-face pairs that previously
triggered the cascade unnecessarily — a net win.

---

## Redundant work: does `closerThan` recompute what the gate already computed?

`hasInteriorIntersection` computes an AABB for both polygons internally. `closerThan`'s
Step 2/3 computes screen-X and screen-Y extents (which are projections of the 3D AABB
onto screen axes). These are NOT the same values — the gate works on `Point2D`
screen-projected coordinates, while Steps 2/3 in `closerThan` work on the original
3D `Path.points`. The gate's `transformedPoints` (already projected to 2D screen
coordinates) are not exposed to `closerThan`'s caller. If the gate's screen-space AABB
were accessible, Steps 2/3 of the cascade could reuse it directly, saving 2×N
multiply-add passes. This is an architectural coupling gap: the gate and cascade share
no intermediate state.

This is not a new regression — it is an inherent consequence of `closerThan` operating
on 3D `Path` objects while the gate operates on pre-projected `Point2D`. Flagged for
awareness if a performance refactor of the gate/cascade boundary is ever undertaken.

---

## `ISO_ANGLE` companion constant verification

`private const val ISO_ANGLE: Double = PI / 6.0` (Path.kt line 290) — this is a
`const val` on the JVM. `const val` of type `Double` is inlined at compile time as a
literal; the `PI / 6.0` division is performed once at compile time. However,
`cos(ISO_ANGLE)` and `sin(ISO_ANGLE)` at lines 176-177 are runtime calls on every
`closerThan` invocation — `cos`/`sin` of a constant are NOT automatically hoisted to
compile-time constants by `kotlinc` (they are not `const` because `kotlin.math.cos`
is not a `const fun`). Similarly, `Point.depth(angle)` calls `cos(angle)` and
`sin(angle)` on every invocation (Point.kt lines 221-222). These are the transcendental
calls flagged in PF-3.

---

## Net cycles-per-frame estimate

For the LongPress scene (N=30 faces, ~435 pairs pre-gate):

| Phase | Old estimate | New estimate | Change |
|---|---|---|---|
| `hasInteriorIntersection` gate (replaces `hasIntersection`) | ~435 × O(N²) SAT + O(N) isPointInPoly | ~435 × O(N²) SAT + O(N²) strict-inside | ~+10% gate overhead for containment pairs |
| Step 1 (Z-extent, 8 `Point.depth` calls) | N/A | 8 cos + 8 sin per pair × 435 = ~3480 transcendental calls | +3480 vs old (no Step 1 existed) |
| `signOfPlaneSide` (Steps 5/6, for ~18 wall-vs-floor pairs) | 18 × 8 `Vector` allocs = 144 | 18 × 9 `Vector` allocs = 162 | +18 `Vector` allocs |
| Pairs pruned by strict gate (vs. lenient gate) | ~K shared-edge pairs hit cascade | ~0 shared-edge pairs hit cascade | Saves K × cascade cost |

**Overall estimate: roughly +20-30% total hot-loop cost vs. prior implementation**,
driven primarily by PF-3 (8 transcendental calls per pair at Step 1 for all 435 pairs).
This is partially offset by the gate pruning K adjacent-face pairs. Implementing the
PF-3 fix (companion `ISO_COS`/`ISO_SIN` constants, inline arithmetic) would reduce
Step 1 to 8 multiplies per pair and bring the net change to approximately **wash or
modest improvement** vs. prior code.

---

## Summary

The Newell cascade is algorithmically correct and resolves all three prior regression
classes. From a pure performance standpoint, it introduces several measurable new costs
relative to the old `countCloserThan`:

- **PF-1 (HIGH):** Steps 2/3 screen-Y is computed even for pairs that Step 2 would
  resolve — restructuring the loops to split screen-X and screen-Y saves 2×N ops for
  Step-2-firing pairs.
- **PF-2 (MED):** `signOfPlaneSide` allocates 5+N `Vector` per call vs. old 4; inline
  the per-vertex dot product to eliminate N per-call allocations.
- **PF-3 (MED):** `cos(ISO_ANGLE)` / `sin(ISO_ANGLE)` computed per-vertex via
  `Point.depth(angle)` — hoist to companion constants `ISO_COS` / `ISO_SIN` and inline
  Step 1 arithmetic. Saves ~3480 transcendental calls per frame for the LongPress scene.
- **PF-4 (MED):** Pre-existing `Point` wrapping in `hasInteriorIntersection` call site
  — dominant allocation source, unchanged from prior review; refactor to accept
  `Point2D` directly.
- **PF-5 (LOW):** `polyA + polyA[0]` closed-polygon list allocation in
  `hasInteriorIntersection` — replace with modulo indexing.
- **PF-6 (LOW):** Cascade resolution-rate claim unvalidated by benchmark; Steps 2/3
  sign convention unvalidated by any test.
- **PF-7 (NIT):** `isPointCloseToPoly` uses `sqrt`; trivially replaced with
  squared-distance comparison.
- **PF-8 (NIT):** No micro-benchmark exists for `closerThan`.

**Result: PASS.** No blockers. The code is correct and ships the visual regression fix.
PF-3 is the highest-value micro-optimization (eliminates ~3480 transcendental function
calls per frame for typical scenes) and should be the first follow-on; PF-2 and PF-5
are mechanical one-pass fixes. PF-1 is a loop restructuring that pays off only when
Steps 2/3 fire frequently in real scenes, which is currently unvalidated.
