---
schema: sdlc/v1
type: review
review-command: security
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T12:00:00Z"
updated-at: "2026-04-28T12:00:00Z"
result: pass
metric-findings-total: 5
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 0
metric-findings-low: 2
metric-findings-nit: 3
tags:
  - security
  - depth-sort
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: security

Scope: cumulative diff `97416ba..HEAD` on branch `feat/ws10-interaction-props`.
Four commits reviewed: `3e811aa` (permissive countCloserThan), `9cef055`
(hasInteriorIntersection + broad-phase gate), `2e29dc5` (DEPTH_SORT_DIAG instrumentation —
temporary diagnostic), `452b1fc` (Newell Z→X→Y minimax, removes countCloserThan, reverts
DEPTH_SORT_DIAG).

Primary files:
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`

## Findings

| ID    | Sev | Conf | File:Line | Issue |
|-------|-----|------|-----------|-------|
| SEC-1 | LOW | High | `Path.kt:149–166` — Step 1 depth minimax | NaN coordinates cause sentinel inversion: both `closerThan` directions return −1, antichain violation |
| SEC-2 | LOW | High | `Path.kt:149–166` — Step 1 depth minimax | `±Infinity` coordinates produce asymmetric ordering (Inf→farther, −Inf→closer) that silently propagates to Kahn graph |
| SEC-3 | NIT | High | `DepthSorter.kt` — HEAD | DEPTH_SORT_DIAG logging confirmed fully reverted; finding is CLEAN |
| SEC-4 | NIT | High | `DepthSorter.kt` + `Path.kt` | No new external dependencies, no reflection, no unsafe casts — confirmed CLEAN |
| SEC-5 | NIT | Med  | `IntersectionUtils.kt:302–303` — `min`/`max` helpers | Custom `min`/`max` are non-symmetric for NaN (order-dependent); consistent with JVM `Math.min` but worth noting |

---

## Detailed Findings

### SEC-1 — NaN coordinate causes sentinel inversion and violates closerThan antichain contract (LOW)

**Location:** `Path.kt` lines 149–166 (Step 1 iso-depth extent minimax).

**Description:**
`closerThan` initialises `selfDepthMin = Double.POSITIVE_INFINITY` and
`selfDepthMax = Double.NEGATIVE_INFINITY` as sentinels, then updates them in a loop:
```kotlin
val d = p.depth(ISO_ANGLE)
if (d < selfDepthMin) selfDepthMin = d
if (d > selfDepthMax) selfDepthMax = d
```
When a vertex's projected depth `d` is `NaN`, both comparisons evaluate to `false`
(IEEE 754), so neither sentinel is ever updated. For a fully-NaN polygon this leaves
`selfDepthMax = −∞` and `selfDepthMin = +∞`.

The cascade then tests:
```kotlin
if (selfDepthMax < aDepthMin - EPSILON) return -1  // line 164
```
`−∞ < (any finite value) − ε` is always `true`, so `NaN.closerThan(real)` returns `−1`
(NaN polygon reported as *closer* — draws after the real polygon).

When roles are reversed, `real.closerThan(NaN)` also returns `−1`: `aDepthMin = +∞`
(NaN polygon), so `selfDepthMax(real) < +∞ − ε` is also `true`, returning `−1` (real
polygon also reported as closer).

**Contract violation:** Both `A.closerThan(B) = −1` and `B.closerThan(A) = −1`
simultaneously. In `checkDepthDependency` only one direction is called per pair, so no
graph cycle results from a single NaN–real pair. However if the caller evaluates both
directions (e.g., in a unit test or future reflexive caller), a contradictory pair is
produced. The existing predicate contract documents that `−(A.closerThan(B))` should
equal `B.closerThan(A)` for non-zero results; NaN breaks this invariant.

**Practical impact:** The rendering result for a NaN-coordinate polygon is incorrect
(wrong depth position) but there is no crash, infinite loop, or stack overflow.
`Path` coordinates originate from the app developer's scene definitions; the library
does not validate individual Point coordinates for finiteness at construction time (no
`isNaN` check in `Path.init` or `Point` constructor). No external attack surface exists
that does not require the app developer's cooperation. The existing `DepthSorterTest`
antisymmetry invariant test does not exercise NaN inputs.

**Not introduced by this diff:** The prior `countCloserThan` code in `97416ba` had
identical sentinel inversion for NaN (same `< / >` loop pattern, same IEEE 754
behaviour). This finding pre-dates the Newell rewrite; the Newell cascade does not
worsen it.

**Fix note (HIGH+ only — not required here):** Not applicable at LOW severity.
For reference: adding `require(points.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })`
to `Path.init` would surface malformed input at the earliest possible moment (API guideline §7)
and eliminate all NaN/Infinity propagation throughout the cascade.

**Severity:** LOW — misrender only; no DoS, no crash, no cycle. Pre-existing condition;
not worsened by the diff.
**Confidence:** High.

---

### SEC-2 — ±Infinity coordinates produce asymmetric depth ordering (LOW)

**Location:** `Path.kt` lines 149–166 (same minimax sentinels).

**Description:**
For a polygon where all vertices have `depth = +∞` (e.g., a Point at `(Double.MAX_VALUE,
Double.MAX_VALUE, 0)` after a scale operation that overflows to `Inf`):
- `selfDepthMin = +∞`, `selfDepthMax = +∞`
- `aDepthMax(real) < selfDepthMin(+∞) − ε` is always `true` → returns `+1`
  (Inf polygon reported as *farther* than any real polygon — draws before all real polygons)

For a polygon where all vertices have `depth = −∞`:
- `selfDepthMax = −∞`
- `selfDepthMax(−∞) < aDepthMin(real) − ε` is always `true` → returns `−1`
  (−Inf polygon reported as *closer* — draws after all real polygons)

**Antisymmetry check:**
- `Inf.closerThan(real) = +1`; `real.closerThan(Inf)`: `aDepthMax(+∞) < selfDepthMin(real) − ε`
  is `false`; `selfDepthMax(real) < aDepthMin(+∞) − ε` is `true` → returns `−1`.
  So `Inf.closerThan(real) = +1` and `real.closerThan(Inf) = −1`. Sign is consistent
  (`−(Inf.closerThan(real)) = −1 = real.closerThan(Inf)`). Antisymmetry holds for ±Inf.

**Practical impact:** A single Infinity-coordinate polygon is consistently sorted to the
back (for +Inf) or front (for −Inf) of the scene. Cosmetic misrender only; no cycle.
Same pre-existing behaviour as in the old `countCloserThan` code.

**Severity:** LOW — misrender only; antisymmetry holds (unlike NaN). Pre-existing.
**Confidence:** High.

---

### SEC-3 — DEPTH_SORT_DIAG logging revert verification (NIT)

**Location:** `DepthSorter.kt` HEAD.

**Finding:** CLEAN.

Commit `2e29dc5` re-added `System.err` diagnostic emissions (`DEPTH_SORT_DIAG` toggle).
Commit `452b1fc` (HEAD) states it reverts that instrumentation. Confirmed by grep: no
`System.err`, `System.out`, `Log.`, `println`, or `DEPTH_SORT_DIAG` references appear
anywhere in `isometric-core/src/main/kotlin/` at HEAD. No diagnostic output reaches
logcat in any build variant. No information disclosure risk.

**Severity:** NIT — clean, listed only for completeness of the diag-revert audit.
**Confidence:** High.

---

### SEC-4 — Supply chain: no new external dependencies; no reflection or unsafe APIs (NIT)

**Finding:** CLEAN.

`git diff 97416ba..HEAD` over all `*.gradle.kts`, `*.gradle`, and `*.toml` files produces
no changes to dependency declarations. The three changed Kotlin files (`Path.kt`,
`DepthSorter.kt`, `IntersectionUtils.kt`) use only:
- `kotlin.math.{PI, cos, sin, floor}` (Kotlin stdlib)
- `kotlin.math.sqrt` (pre-existing in `IntersectionUtils`)
- No reflection (`KClass`, `Class.forName`, `getDeclaredField`, etc.)
- No `@Suppress("UNCHECKED_CAST")` or raw-type casts
- No JNI, native, or unsafe-memory APIs

**Severity:** NIT — no supply chain or unsafe-API issue.
**Confidence:** High.

---

### SEC-5 — Custom `min`/`max` helpers in IntersectionUtils are NaN-order-dependent (NIT)

**Location:** `IntersectionUtils.kt` lines 302–303.

```kotlin
private fun min(a: Double, b: Double) = if (a < b) a else b
private fun max(a: Double, b: Double) = if (a > b) a else b
```

**Description:**
These helpers are not symmetric for NaN inputs:
- `min(NaN, 5.0)` → `5.0` (NaN < 5.0 is false → b returned)
- `min(5.0, NaN)` → `NaN` (5.0 < NaN is false → NaN=b returned)

This behaviour is consistent with `java.lang.Math.min(double, double)` and IEEE 754, and
it does not create any new security issue beyond what SEC-1 already covers. In the AABB
loop where they are used, the iteration order over `pointsA` is deterministic (list
order), so the result is deterministic — though not commutative with NaN.

**Practical impact:** For all-NaN polygon AABB: `AmaxX = NaN`, `AminX = NaN` (the first
element is NaN; subsequent `min(NaN, x)` calls return `x`, but `maxX` initial assignment
is `pointsA[0].x = NaN`, then `max(NaN, x)` returns `x` — so both bounds get updated to
real values by subsequent points if they are real). For a fully-NaN polygon, all bounds
stay NaN, both AABB overlap checks return `false` (NaN < x is false), the polygon passes
the AABB gate, and all subsequent edge-crossing and point-in-poly tests yield `false` for
NaN inputs. `hasInteriorIntersection` returns `false` for fully-NaN polygons — no
draw-order edge is added. Outcome: NaN polygon placed by Kahn pre-sort order only.
No crash, no infinite loop.

**Severity:** NIT — no security issue introduced; noted for completeness.
**Confidence:** Med.

---

## Supply Chain Audit

`git log 97416ba..HEAD --stat` covers four commits affecting:
- `.ai/` workflow documents and evidence (no production code)
- `isometric-core/src/main/kotlin/` (production Kotlin — reviewed above)
- `isometric-compose/src/test/` (test-only scene factories and snapshot tests)
- `build-logic/` and version catalog files — diff produces no output (no changes)

No new Maven coordinates, no new Gradle plugins, no new `import` statements referencing
third-party libraries in the production source set.

---

## DoS / Complexity Analysis

**O(N²) face-count loop** (`sort()`, non-broad-phase path): N is user-controlled (no
limit on `SceneGraph._items.size`). This is inherent to the Painter's algorithm and
pre-dates this diff. No change in asymptotic complexity introduced by the diff.

**closerThan per-pair cost**: O(V) per pair where V = vertices per face. The Newell
rewrite iterates over `points` up to four times (depth min/max, screen-x/y min/max,
then `signOfPlaneSide`). The old `countCloserThan` iterated once. For typical faces
(V = 4), this is a constant-factor increase, not asymptotic. No infinite loops; no
recursion.

**Kahn's algorithm**: Uses an `IntArray(length)` ring buffer queue. Each of the N nodes
is enqueued at most once (`qTail ≤ N = length`); no array out-of-bounds is possible.
The CSR `depEdges` array is sized exactly `totalEdges`; fill indices are bounded by CSR
offsets.

**Broad-phase grid** (`buildBroadPhaseCandidatePairs`): Pre-existing code (present at
`97416ba`); not introduced by this diff. Noted for completeness: with extreme coordinates
(e.g., `±1e10`) and the default `cellSize = 100`, `floor(±1e10 / 100).toInt()` clamps
to `Int.MAX_VALUE` / `Int.MIN_VALUE`. The inner loop `for (col in minCol..maxCol)` would
then iterate ~4 billion times per item per row. This requires the app developer to pass
untrusted extreme coordinates without sanitisation and is a pre-existing deficiency, not
introduced by this diff.

---

## Summary

The cumulative diff `97416ba..HEAD` is **clean from a security perspective**. No new
external dependencies are introduced, no I/O or network code is touched, no reflection
or unsafe casts are used, and the `DEPTH_SORT_DIAG` logcat instrumentation added in
`2e29dc5` is fully reverted in `452b1fc` (HEAD).

The two LOW findings (SEC-1, SEC-2) describe the behaviour of the Newell minimax cascade
with NaN or ±Infinity coordinate inputs. Both conditions pre-date this diff — the
prior `countCloserThan` code had identical silent-failure behaviour for the same inputs.
The Newell rewrite does not worsen either finding; it is merely more code for these
edge cases to pass through. The practical impact of both is cosmetic misrender of the
affected polygon only; no crash, infinite loop, or DoS vector is introduced.

**Result: PASS.** No blockers, no high/medium findings. The fix may proceed to handoff.
