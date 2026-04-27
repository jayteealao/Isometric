---
schema: sdlc/v1
type: review
review-command: performance
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
result: pass
metric-findings-total: 4
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 1
metric-findings-low: 2
metric-findings-nit: 1
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - performance
  - allocation
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
  security-review: 07-review-security.md
---

# Review: performance

Scope: `countCloserThan` in `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
(lines 117-146) and its callers: `closerThan` (line 99-101) and
`DepthSorter.checkDepthDependency` (lines 124-150 of `DepthSorter.kt`).

Hot-path position: called O(faces²) times per frame (or O(intersecting pairs) with
broad-phase), once per frame re-projection. This is the innermost comparison kernel of
the depth sort.

## Findings

| ID   | Severity | Confidence | Location                                        | Summary                                                              |
|------|----------|------------|-------------------------------------------------|----------------------------------------------------------------------|
| PF-1 | MED      | High       | `Path.kt:133-135` — per-vertex OP allocation    | Each call allocates N `Vector` objects; no early-exit from the loop  |
| PF-2 | LOW      | High       | `Path.kt:117-146` — algorithmic shape           | New code is a marginal improvement over old; confirmed wash/speedup  |
| PF-3 | LOW      | High       | `DepthSorter.kt:133-136` — Point boxing in SAT  | `hasIntersection` call allocates 2×N `Point(x,y,0)` wrappers per pair |
| PF-4 | NIT      | Med        | `Path.kt:117-146` — epsilon cost                | `>= 0.000001` double compare — no cost concern; confirmed cheap      |

---

## Detailed Findings

### PF-1 — Per-vertex `Vector` allocation + missing early-exit (MED)

**Location:** `Path.kt` lines 133-135 (loop body inside `countCloserThan`)

```kotlin
for (point in points) {
    val OP = Vector.fromTwoPoints(Point.ORIGIN, point)  // allocation every iteration
    val pPosition = Vector.dotProduct(n, OP) - d
    if (observerPosition * pPosition >= 0.000001) {
        result++
    }
}
return if (result > 0) 1 else 0
```

**Allocation cost:**
`Vector.fromTwoPoints` allocates a new `Vector(x, y, z)` heap object on every iteration.
`Vector` is a plain Kotlin class (not a value class), so each construction is a heap
allocation + GC pressure. For a 4-vertex prism face, `countCloserThan` allocates 4
`Vector` objects per call. `closerThan` calls it twice (both directions), so 8 objects per
face pair. Over K checked pairs per frame (worst-case O(faces²/2) without broad-phase),
this yields 8K short-lived `Vector` allocations per frame. On a 100-face scene that is
~40 000 allocations per frame.

Note: `OP = Vector.fromTwoPoints(Point.ORIGIN, point)` is equivalent to
`Vector(point.x - 0, point.y - 0, point.z - 0)` = `Vector(point.x, point.y, point.z)`.
The `Point.ORIGIN` subtraction is redundant — the components of `point` are used
directly. This could be inlined to three `Double` locals with zero allocation.

**Early-exit opportunity:**
The return value is `if (result > 0) 1 else 0` — the loop only needs to find the first
passing vertex. Once one iteration increments `result`, the remaining iterations are
wasted work. For a 4-vertex face that passes (at least one vertex on observer side), on
average ~2 iterations are wasted. Breaking on first hit halves expected loop iterations
for the common passing case.

**Combined fix (no API change):**
```kotlin
for (point in points) {
    // Inline OP: equivalent to Vector.fromTwoPoints(Point.ORIGIN, point) but allocation-free
    val pPosition = n.x * point.x + n.y * point.y + n.z * point.z - d
    if (observerPosition * pPosition >= 0.000001) return 1   // early exit
}
return 0
```

This eliminates all N `Vector` allocations, inlines the dot-product arithmetic (3 muls +
2 adds, same as before), and exits on first passing vertex. Pre-computed `d` and `n`
remain unchanged outside the loop.

**Severity rationale:** MED — measurable GC pressure at moderate face counts, and the
early-exit removal is unambiguously missed work. Not a blocker because broad-phase
already prunes most pairs, and the fix is additive-only (no semantic change).

---

### PF-2 — Algorithmic shape comparison: new vs old (LOW)

**Old implementation (reconstructed from context):**
```kotlin
var result = 0
var result0 = 0
for (point in points) {
    val OP = Vector.fromTwoPoints(Point.ORIGIN, point)
    val pPosition = Vector.dotProduct(n, OP) - d
    if (observerPosition * pPosition >= 0.0) result++
    else result0++   // (or similar accumulation)
}
return (result + result0) / points.size   // integer division
```

**New implementation:** Same loop, but returns `if (result > 0) 1 else 0` instead of
the averaged integer-division result.

**Performance delta:**
- Both iterate all N vertices (no early exit in either version; the new version has the
  opportunity but does not yet take it — see PF-1).
- Old version incremented two counters and did an integer division after the loop.
  New version increments one counter and does a single conditional branch after the loop.
- Net: the new implementation is a marginal speedup — one fewer increment + one
  fewer integer division per call — but neither is measurable at this scale (both are
  single-cycle operations).

**Conclusion:** The algorithmic shape change is a wash to marginal improvement. No
regression introduced. The correctness fix (permissive "any vertex" vs truncating
division) was the primary motivation; the performance delta is negligible.

---

### PF-3 — `Point` boxing in `hasIntersection` pre-check (LOW)

**Location:** `DepthSorter.kt` lines 133-136

```kotlin
if (IntersectionUtils.hasIntersection(
        itemA.transformedPoints.map { Point(it.x, it.y, 0.0) },
        itemB.transformedPoints.map { Point(it.x, it.y, 0.0) }
    )
)
```

**Issue:** For every pair reaching the intersection check, two new `List<Point>` are
allocated with N+M fresh `Point` objects (each `Point(x, y, 0.0)` is a heap allocation).
These lists are immediately discarded after the SAT test. This is not introduced by the
current diff — it is a pre-existing cost in `checkDepthDependency`. However, it is the
dominant allocation site in the hot path, dwarfing the `Vector` allocations in
`countCloserThan`.

`IntersectionUtils.hasIntersection` could be refactored to accept `List<Point2D>` (or a
raw coordinate array) directly to eliminate this conversion. Flagged here because any
profiling of `countCloserThan` performance should first address this outer-loop cost.

**Severity:** LOW — pre-existing, not introduced by this diff. Mentioning for completeness
and to orient future profiling correctly.

---

### PF-4 — Epsilon comparison cost (NIT)

**Location:** `Path.kt` line 140 — `>= 0.000001`

The `0.000001` literal is a `Double` constant folded at compile time. The comparison
`observerPosition * pPosition >= 0.000001` is two `double` multiplications and one
comparison — three FPU operations. This is as cheap as possible for this predicate;
there is no cost concern here. The epsilon widening from `1e-9` to `1e-6` has no
performance impact.

---

## Topological sort workload analysis

**Question:** Does the permissive `countCloserThan` threshold increase the Kahn graph
edge count, increasing topological-sort work per frame?

**Analysis:**
The permissive change makes `closerThan` return non-zero for more pairs — specifically for
adjacent/shared-edge faces that the old code (truncating division) incorrectly returned
zero for. For those pairs, `checkDepthDependency` now adds an edge to `drawBefore`,
whereas before it added none (the `cmpPath == 0` path at line 145 intentionally skips
edge insertion).

So yes: for a scene with K shared-edge face pairs (adjacent prism faces), the new code
generates up to K additional directed edges that the old code suppressed. For a typical
prism grid, shared-edge pairs are at most O(faces) in count (each face has a bounded
number of neighbours), not O(faces²).

**Kahn's algorithm cost:** O(V + E) where E = total edges. Adding K edges increases
cost by O(K), which is O(faces) — negligible relative to the O(faces²) pairwise
comparison phase that precedes it. The CSR construction (lines 71-87 of `DepthSorter.kt`)
and queue processing (lines 99-111) are both linear in E; the marginal cost of K extra
edges is undetectable in practice.

**Conclusion:** The regression fix does increase edge count for shared-edge pairs, but
the Kahn phase impact is O(faces), not O(faces²). No performance concern.

---

## Summary

The change is **clean from a performance perspective.** No regression is introduced;
the new implementation is a marginal improvement over the old for the operations changed.
The four findings are:

- **PF-1 (MED):** Per-vertex `Vector` allocation inside the hot loop, plus a missed
  early-exit opportunity. Combined fix is a zero-allocation inline + `return 1` on first
  passing vertex — purely additive, no semantic change. Recommend as a follow-on.
- **PF-2 (LOW):** Algorithmic shape comparison confirmed as wash/marginal speedup.
  No action required.
- **PF-3 (LOW):** Pre-existing `Point` boxing in `hasIntersection` call site is the
  larger allocation hotspot in the enclosing loop. Not introduced here; noted for future
  profiling.
- **PF-4 (NIT):** Epsilon double-compare confirmed cheap. No action needed.

**Result: PASS.** No blockers. PF-1 (MED) is the only actionable item and is recommended
as a follow-on micro-optimization, not a requirement to ship this fix.
