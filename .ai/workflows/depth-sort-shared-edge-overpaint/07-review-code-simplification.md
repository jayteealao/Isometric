---
schema: sdlc/v1
type: review
review-command: code-simplification
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T00:00:00Z"
updated-at: "2026-04-28T00:00:00Z"
result: pass
metric-findings-total: 14
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 5
metric-findings-low: 5
metric-findings-nit: 4
tags:
  - code-simplification
  - depth-sort
refs:
  index: 00-index.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: code-simplification

Scope: cumulative diff `97416ba..HEAD` — Rounds 1-4 of the Newell cascade implementation.
Key files reviewed:

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`
- `isometric-compose/src/test/kotlin/.../scenes/WS10NodeIdScene.kt`
- `isometric-compose/src/test/kotlin/.../scenes/OnClickRowScene.kt`
- `isometric-compose/src/test/kotlin/.../scenes/LongPressGridScene.kt`
- `isometric-compose/src/test/kotlin/.../scenes/AlphaSampleScene.kt`
- `isometric-compose/src/test/kotlin/.../IsometricCanvasSnapshotTest.kt`

---

## Findings

| ID    | Severity | Confidence | Location                                                      | Summary                                                                        |
|-------|----------|------------|---------------------------------------------------------------|--------------------------------------------------------------------------------|
| CS-1  | MED      | High       | `Path.kt` steps 1–3 (depth/screen extent loops)              | Four separate vertex-scan loops can collapse to two                            |
| CS-2  | MED      | High       | `IntersectionUtils.kt` — AABB block in `hasIntersection` vs `hasInteriorIntersection` | ~30 lines of AABB + edge-equation setup is copy-pasted verbatim |
| CS-3  | MED      | High       | `IntersectionUtils.kt` — edge-crossing inner loop in both functions | Identical SAT crossing loop body copy-pasted into `hasInteriorIntersection`    |
| CS-4  | MED      | High       | `Path.kt` — `EPSILON = 0.000001` vs `IntersectionUtils.EDGE_BAND = 1e-6` vs `0.000000001` in SAT | Three epsilon values, two styles, zero shared constant |
| CS-5  | MED      | Med        | `Path.kt` steps 2/3 — screen-extent minimax kept but inert   | Dead cascade branches with unvalidated sign convention committed to production |
| CS-6  | LOW      | High       | `IntersectionUtils.kt` — private `min`/`max` helpers         | Shadows `kotlin.math.min`/`max` for no reason                                 |
| CS-7  | LOW      | High       | `DepthSorter.kt` — `Point(-10.0,-10.0,20.0)` observer literal | Magic number repeated; should be a named constant                              |
| CS-8  | LOW      | High       | `DepthSorterTest.kt` — face-finder lambdas repeated verbatim across three test functions | Extract a `findFace` helper                               |
| CS-9  | LOW      | Med        | `Path.kt` `signOfPlaneSide` — `Vector.fromTwoPoints(Point.ORIGIN, p)` for every vertex | Equivalent to a direct `Vector(p.x, p.y, p.z)` if such a ctor exists; otherwise allocates a `Point.ORIGIN` for each vertex |
| CS-10 | LOW      | Med        | `Path.kt` companion object — `private` qualifier on `private companion object` constants redundant | Double-`private` |
| CS-11 | NIT      | High       | `Path.kt` `EPSILON = 0.000001` literal form                  | `1e-6` is the idiomatic form used everywhere else in the file's KDoc and in `IntersectionUtils` |
| CS-12 | NIT      | High       | All four scene factories — identical `@file:OptIn` + identical ground-prism opening shape | 5-line preamble repeated four times                |
| CS-13 | NIT      | Med        | `DepthSorterTest.kt` — `kotlin.math.abs` fully-qualified in every face-finder lambda | `import kotlin.math.abs` at top of file would clean up all call sites         |
| CS-14 | NIT      | Low        | `05-implement` notes "Steps 2/3 kept but inert" — no `TODO` or `@Suppress` annotation marks the dead branches in source | Reviewers reading only the source have no signal |

---

## Detailed Findings

---

### CS-1 — Four vertex-scan loops collapse to two (MED)

**Location:** `Path.kt` `closerThan`, lines 149–202.

**Current structure:**
1. Loop over `this.points` → compute `p.depth(ISO_ANGLE)` → accumulate `selfDepthMin/Max`.
2. Loop over `pathA.points` → compute `p.depth(ISO_ANGLE)` → accumulate `aDepthMin/Max`.
3. (Check step-1 condition.)
4. Loop over `this.points` again → compute `(p.x - p.y) * cosAngle` and `-(sinAngle * (p.x + p.y) + p.z)` → accumulate `selfScreenXMin/Max` and `selfScreenYMin/Max`.
5. Loop over `pathA.points` again → same projection → accumulate `aScreenXMin/Max` and `aScreenYMin/Max`.

Loops 1 and 4 iterate the same `this.points` list; loops 2 and 5 iterate `pathA.points`. Each vertex in `this.points` is visited twice. The depth scalar `p.depth(ISO_ANGLE)` computes `cos(ISO_ANGLE)*p.x + sin(ISO_ANGLE)*p.y - 2*p.z` (per `Point.depth`). The screen-extent projection adds `screenX = (p.x - p.y) * cosAngle` and `screenY = -(sinAngle * (p.x + p.y) + p.z)`. All three projections use the same `cosAngle`/`sinAngle`. Merging the two `this.points` loops and the two `pathA.points` loops yields two loops total and eliminates the redundant traversal:

```kotlin
val cosAngle = cos(ISO_ANGLE)
val sinAngle = sin(ISO_ANGLE)

var selfDepthMin = Double.POSITIVE_INFINITY; var selfDepthMax = Double.NEGATIVE_INFINITY
var selfScreenXMin = Double.POSITIVE_INFINITY; var selfScreenXMax = Double.NEGATIVE_INFINITY
var selfScreenYMin = Double.POSITIVE_INFINITY; var selfScreenYMax = Double.NEGATIVE_INFINITY
for (p in points) {
    val d = p.depth(ISO_ANGLE)
    if (d < selfDepthMin) selfDepthMin = d
    if (d > selfDepthMax) selfDepthMax = d
    val sx = (p.x - p.y) * cosAngle
    val sy = -(sinAngle * (p.x + p.y) + p.z)
    if (sx < selfScreenXMin) selfScreenXMin = sx
    if (sx > selfScreenXMax) selfScreenXMax = sx
    if (sy < selfScreenYMin) selfScreenYMin = sy
    if (sy > selfScreenYMax) selfScreenYMax = sy
}
// … similarly for pathA.points …
```

The early-exit after step 1 (which currently skips computing cosAngle/sinAngle for clearly-disjoint depth pairs) is mildly compromised — the merged loop always computes screen coords even when step 1 would have returned. However, steps 2/3 are currently inert (see CS-5), so there is no correctness impact and the early-exit benefit is largely notional for the current test corpus. If steps 2/3 are eventually removed (see CS-5), the merged structure is still the right baseline.

**Recommendation:** Merge loops 1+4 and loops 2+5. Move `cosAngle`/`sinAngle` computation before the first loop. Minor O(N) saving per `closerThan` call, more significant readability gain (four sets of identical boilerplate collapse into two).

---

### CS-2 — AABB + edge-equation setup copy-pasted between the two `IntersectionUtils` functions (MED)

**Location:** `IntersectionUtils.kt`:
- `hasIntersection` lines 75–133
- `hasInteriorIntersection` lines 205–254

The AABB accumulation block (~20 lines computing `AminX`, `AminY`, `AmaxX`, `AmaxY`, `BminX`, `BminY`, `BmaxX`, `BmaxY`), the polygon-close step (`pointsA + pointsA[0]`), the `lengthPolyA`/`lengthPolyB` values, and the `deltaAX`/`deltaAY`/`rA` / `deltaBX`/`deltaBY`/`rB` precomputation block (~18 lines) are all verbatim or near-verbatim copies. The only structural difference is the AABB-reject expression: `hasIntersection` uses a compound `if (!(… && …))` while `hasInteriorIntersection` uses two separate `if (AmaxX < BminX …) return false` guards — the latter is cleaner and should be the canonical form.

This duplication means any future change to the polygon-line-equation format (e.g., adding a numerical-stability fix) must be applied in two places.

**Recommendation:** Extract a private `preparePolygon(points: List<Point>): PolygonData` that returns the closed list, deltas, and r-values; and extract a private `aabbOverlap(pointsA, pointsB)` for the bounding-box check. `hasIntersection` and `hasInteriorIntersection` call these helpers and diverge only at the crossing-test threshold and the strict-inside fallback:

```kotlin
private data class PolygonData(
    val closed: List<Point>,
    val deltaX: DoubleArray,
    val deltaY: DoubleArray,
    val r: DoubleArray,
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double
)

private fun preparePolygon(points: List<Point>): PolygonData { … }
private fun aabbOverlaps(a: PolygonData, b: PolygonData): Boolean { … }
```

This is ~50 lines of extracted helpers against ~70 lines of saved duplication — a clear win.

---

### CS-3 — SAT crossing inner loop body duplicated (MED)

**Location:** `IntersectionUtils.kt` lines 137–151 (`hasIntersection`) and 259–270 (`hasInteriorIntersection`).

The inner `for (i … for (j …)` loop body is character-for-character identical across both functions:

```kotlin
if (deltaAX[i] * deltaBY[j] != deltaAY[i] * deltaBX[j]) {
    val side1a = deltaAY[i] * polyB[j].x - deltaAX[i] * polyB[j].y + rA[i]
    val side1b = deltaAY[i] * polyB[j + 1].x - deltaAX[i] * polyB[j + 1].y + rA[i]
    val side2a = deltaBY[j] * polyA[i].x - deltaBX[j] * polyA[i].y + rB[j]
    val side2b = deltaBY[j] * polyA[i + 1].x - deltaBX[j] * polyA[i + 1].y + rB[j]
    if (side1a * side1b < -0.000000001 && side2a * side2b < -0.000000001) {
        return true
    }
}
```

If CS-2's `PolygonData` helper is extracted, this crossing check becomes a single private function `hasStrictEdgeCrossing(a: PolygonData, b: PolygonData): Boolean` shared by both callers.

**Recommendation:** If CS-2 is addressed, the crossing check extracts for free. If CS-2 is deferred, extract this inner loop as a standalone `hasStrictEdgeCrossing(…): Boolean` regardless.

---

### CS-4 — Three epsilon values, two notational styles, no shared constant (MED)

**Location:**

| File | Value | Style | Meaning |
|------|-------|-------|---------|
| `Path.kt:298` | `0.000001` | decimal | plane-side EPSILON |
| `IntersectionUtils.kt:300` | `1e-6` | scientific | EDGE_BAND |
| `IntersectionUtils.kt:146,267` | `0.000000001` | decimal | SAT crossing threshold |

The `Path.kt` value and `IntersectionUtils.EDGE_BAND` are semantically aligned (both: "sub-pixel floating-point tolerance") but use different notational forms. The SAT-crossing threshold (`-0.000000001 = -1e-9`) is an order of magnitude stricter and unnamed.

**Issues:**
1. `Path.kt`'s `EPSILON` is module-private; `IntersectionUtils.EDGE_BAND` is file-private. They cannot reference each other. Two identically-valued constants drift independently.
2. The SAT epsilon is embedded as a decimal literal at two sites in `IntersectionUtils.kt` (one in `hasIntersection`, one in `hasInteriorIntersection`). Changing it requires finding both. Naming it `SAT_CROSSING_EPSILON` or `SAT_THRESHOLD` would make the intent clear and centralise the value.

**Recommendation:**
- Name the SAT threshold: `private const val SAT_CROSSING_EPSILON = 1e-9` in `IntersectionUtils` and replace both `0.000000001` literals with `-SAT_CROSSING_EPSILON`.
- Unify notational style: use `1e-6` (scientific) everywhere, consistent with the KDoc prose that already says "1e-6" throughout.
- Longer term: consider a shared `DepthSortConstants` internal object in `isometric-core` that holds all shared epsilon values. Not required now.

---

### CS-5 — Steps 2/3 (screen-x/y minimax) inert, with unvalidated sign conventions, in production code (MED)

**Location:** `Path.kt` lines 168–214 (the `cosAngle`/`sinAngle` block and the four extent checks).

The implementer explicitly noted in `05-implement-depth-sort-shared-edge-overpaint.md` (Round 4 Notes on Design Choices): "Steps 2/3 (X/Y screen-extent minimax) kept but inert in the test suite … their sign convention is the iso-projection intuition … rather than empirically validated against a counter-example."

Inert code in a production function carries concrete costs:

1. **Unvalidated sign.** The comment acknowledges the Y-extent sign (`selfScreenYMax < aScreenYMin - EPSILON) return 1`) follows "iso-projection intuition" not a passing test. There is no test that ever reaches step 2 or 3. If the sign is wrong, the cascade will silently misorder a future scene — and no test will catch it until a visual regression is filed.
2. **Unnecessary computation.** Even with CS-1 applied, `cosAngle` and `sinAngle` are computed unconditionally, and four sets of screen-extent bounds are accumulated for every `closerThan` call regardless of whether they are needed.
3. **Reader confusion.** A reader studying the cascade sees two "active" steps that the KDoc implies matter but that no test exercises. The implementer's self-note is only in the workflow doc, not the source.

**Options:**
- **Remove steps 2/3.** The plan introduced them as a "classical Z→X→Y structure" marker. The actual test corpus shows Z-extent (step 1) or plane-side (steps 4/5) always decides first. Removing steps 2/3 makes the code shorter, testable at every line, and eliminates the sign-correctness concern. The Newell paper does list X and Y steps, but the DepthSorter's `hasInteriorIntersection` gate already ensures that `closerThan` is only called for screen-overlapping polygons — which means screen-disjoint pairs handled by steps 2/3 never reach `closerThan` in practice.
- **Keep but annotate.** Add a `// TODO: steps 2/3 sign not validated by any test — verify before relying on` comment and a `@Suppress("UnusedVariable")` guard to signal intent to the next reader.
- **Keep and test.** Construct a test geometry where Z-extent overlaps but X-extent is strictly disjoint, assert the result, and validate the sign.

**Recommendation:** The cleanest path is removing steps 2/3. They are dead weight with an untested sign convention. If the full Newell structure is considered load-bearing documentation, at minimum annotate the sign as unvalidated and add a `TODO` for a future test case.

---

### CS-6 — Private `min`/`max` helpers shadow stdlib (LOW)

**Location:** `IntersectionUtils.kt` lines 302–303.

```kotlin
private fun min(a: Double, b: Double) = if (a < b) a else b
private fun max(a: Double, b: Double) = if (a > b) a else b
```

`kotlin.math.min` and `kotlin.math.max` exist and are available. These private wrappers predate Kotlin's stdlib availability in this module or were added defensively. They add two lines with no benefit and obscure the fact that the code uses standard comparisons.

**Recommendation:** Delete the private helpers and add `import kotlin.math.min; import kotlin.math.max` (as `IsoColor.kt` already does). Zero behavioural change.

---

### CS-7 — `observer = Point(-10.0, -10.0, 20.0)` magic observer literal (LOW)

**Location:** `DepthSorter.kt` line 37.

```kotlin
val observer = Point(-10.0, -10.0, 20.0)
```

The observer position is constructed inline inside `sort()` with no name explaining why `(-10, -10, 20)`. The same geometric position is implicitly assumed in `Path.signOfPlaneSide` (which receives observer as a parameter) and in several test assertions ("observer is on the y < 3.6 side" etc.). A named constant documents the convention:

```kotlin
/** Default eye position for the painter's-algorithm depth comparator. */
private val DEFAULT_OBSERVER = Point(-10.0, -10.0, 20.0)
```

**Recommendation:** Extract `DEFAULT_OBSERVER`. Also check whether this value should eventually be threaded through from `RenderOptions`; hardcoding it here means all depth comparisons assume a fixed observer position regardless of the actual camera angle.

---

### CS-8 — Face-finder lambdas repeated verbatim across test functions (LOW)

**Location:** `DepthSorterTest.kt` — three test functions that each identify the ground-top face and the back-right cube's front/left faces (AC-9, AC-12, AC-13). The ground-top finder lambda:

```kotlin
pts.size == 4 &&
    pts.all { kotlin.math.abs(it.z - 0.1) < 1e-9 } &&
    pts.any { kotlin.math.abs(it.x - (-1.0)) < 1e-9 } &&
    pts.any { kotlin.math.abs(it.x - 7.0) < 1e-9 }
```

appears at `DepthSorterTest.kt:387–389` and `DepthSorterTest.kt:455–457` — character-for-character identical. The back-right front-face finder (`pts.all { … y - 3.6 … } && pts.any { … x - 3.6 … } && pts.any { … x - 4.8 … }`) appears at lines 318–320 and 397–399. The back-right left-face finder similarly.

A local helper extension makes each test function one-liner readable:

```kotlin
private fun SceneCommand.isGroundTop() =
    originalPath.points.let { pts ->
        pts.size == 4 &&
            pts.all { abs(it.z - 0.1) < FACE_EPS } &&
            pts.any { abs(it.x - (-1.0)) < FACE_EPS } &&
            pts.any { abs(it.x - 7.0) < FACE_EPS }
    }
```

**Recommendation:** Extract named face-finder predicates. Reduces ~30 lines of duplicated filtering to ~10 lines of helpers + call-sites. Also makes the test intent self-documenting.

---

### CS-9 — `Vector.fromTwoPoints(Point.ORIGIN, p)` allocates a spurious ORIGIN point per vertex (LOW)

**Location:** `Path.kt` `signOfPlaneSide`, lines 261–272.

```kotlin
val OA = Vector.fromTwoPoints(Point.ORIGIN, pathA.points[0])
val OU = Vector.fromTwoPoints(Point.ORIGIN, observer)
// …
val OP = Vector.fromTwoPoints(Point.ORIGIN, p)
val pPosition = Vector.dotProduct(n, OP) - d
```

`Vector.fromTwoPoints(ORIGIN, p)` computes `Vector(p.x - 0, p.y - 0, p.z - 0) = Vector(p.x, p.y, p.z)`. If `Vector` has a direct constructor from coordinates, `Vector(p.x, p.y, p.z)` is both clearer (the semantic is "position vector of p") and avoids constructing `Point.ORIGIN` as an intermediate. This is called once per vertex in the loop over `this.points`, so N allocations of `Point.ORIGIN` are created and immediately discarded per `signOfPlaneSide` call.

**Recommendation:** Verify `Vector` has a `(x, y, z)` constructor; if so, replace all three `fromTwoPoints(ORIGIN, …)` calls with direct construction. Low priority — the JVM's escape analysis likely eliminates the allocation — but the direct form is also the more readable one.

---

### CS-10 — Double-`private` on companion object constants (LOW)

**Location:** `Path.kt` lines 284–299.

```kotlin
private companion object {
    private const val ISO_ANGLE: Double = PI / 6.0
    private const val EPSILON: Double = 0.000001
}
```

A `companion object` declared `private` already restricts visibility to the containing class. Marking the constants `private` inside the already-`private` companion is redundant. The `private` modifier on the constants themselves has no additional effect — the outer class scope is the accessibility boundary.

**Recommendation:** Remove the `private` modifier from the constants inside the `private companion object`. Makes the declaration consistent with idiomatic Kotlin companion usage.

---

### CS-11 — `EPSILON = 0.000001` decimal form vs ubiquitous `1e-6` (NIT)

**Location:** `Path.kt` line 298.

```kotlin
private const val EPSILON: Double = 0.000001
```

The KDoc on line 295 says "1e-6 plane-side tolerance". The `IntersectionUtils.EDGE_BAND` uses `1e-6`. The `0.000000001` SAT threshold in `IntersectionUtils` also uses a decimal literal (see CS-4). All surrounding prose and `Path.kt`'s own KDoc text use the `1e-6` scientific form. The decimal form requires counting zeros to confirm the magnitude; `1e-6` does not.

**Recommendation:** Replace `0.000001` with `1e-6` in the constant declaration. Trivial, zero risk.

---

### CS-12 — Four scene factories share a 5-line `@file:OptIn` + ground-prism preamble (NIT)

**Location:** `WS10NodeIdScene.kt`, `OnClickRowScene.kt`, `LongPressGridScene.kt`, `AlphaSampleScene.kt`.

All four files begin with:
```kotlin
@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)

package io.github.jayteealao.isometric.compose.scenes

import androidx.compose.runtime.Composable
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.Shape
import io.github.jayteealao.isometric.shapes.Prism
```

Three of the four also open with the same ground-prism shape: `Prism(Point(-1.0, -1.0, 0.0), …, 0.1)` (dimensions differ between `WS10NodeIdScene` (10×6) and `LongPressGridScene`/`OnClickRowScene` (8×6) / `AlphaSampleScene` (8×6)). The import preamble repetition is unavoidable at the file level in Kotlin, but a shared `@Composable fun IsometricScope.GroundPlatform(width, depth)` helper could reduce the body repetition.

**Why only NIT:** Four files is a small surface; the import block is boilerplate noise; the ground prism dimensions differ slightly, so the commonality is partial.

**Recommendation:** No structural change required. If the scene-factory count grows beyond ~6 or a fifth ground-prism variant appears, extract `GroundPlatform(width, depth)`.

---

### CS-13 — `kotlin.math.abs` fully-qualified in every face-finder lambda (NIT)

**Location:** `DepthSorterTest.kt` — ~20 occurrences of `kotlin.math.abs(it.x - …)` in the three integration tests added in this diff.

The file does not import `kotlin.math.abs`. Adding one import at the top of the file eliminates all full-qualification noise:

```kotlin
import kotlin.math.abs
```

Then every `kotlin.math.abs(…)` becomes `abs(…)`.

**Recommendation:** Add the import. The file already imports `kotlin.math.PI`; `abs` follows the same convention.

---

### CS-14 — Inert cascade steps lack in-source marker (NIT)

**Location:** `Path.kt` steps 2/3 (lines 168–214).

The implementer's choice to keep inert code is documented in the workflow doc but not in the source itself. A reader encountering the screen-extent block for the first time has no signal that:
- No test currently exercises it.
- The sign convention for Y-extent is "iso-projection intuition" rather than an empirically-validated assertion.

If CS-5 is not acted upon (i.e., steps 2/3 are kept), add inline documentation:

```kotlin
// NOTE: Steps 2/3 are currently inert — all test cases resolve at step 1 or steps 4/5.
// The sign convention below follows iso-projection intuition but has NOT been validated
// by a unit test. If a scene reaches this code, add a failing test first.
```

**Recommendation:** If steps 2/3 are retained (CS-5 deferred), add this comment block. If CS-5 is acted upon, no annotation is needed.

---

## `countCloserThan` Deletion — Orphan Reference Check

The implementer confirmed deletion rather than deprecation. Verification result:

- No reference to `countCloserThan` exists in any `.kt` production source file under `isometric-core/src/main/kotlin/`. **Clean.**
- References in `PathTest.kt` and `DepthSorterTest.kt` appear only in **comments** explaining the historical behaviour the Newell cascade replaced. These are documentation, not call sites. **Acceptable.**
- Reference in `Path.kt:127` is inside the `closerThan` KDoc bullet "countCloserThan integer division" — historical context only. **Acceptable.**

The deletion is complete. No orphaned call sites remain.

---

## Summary

The cumulative diff is a well-structured algorithmic replacement. No blockers or high-severity findings. The five MED findings are collectively the most actionable:

- **CS-1** (loop collapse) and **CS-2/CS-3** (AABB + SAT duplication) are the two most impactful simplifications — together they address the only material code duplication introduced by this diff and halve the number of vertex-scan passes in the hot `closerThan` path.
- **CS-4** (epsilon naming) and **CS-5** (inert cascade steps) are the next tier: CS-4 is a one-line fix per site; CS-5 requires a decision (remove vs annotate vs test the sign) and is the only finding with a correctness implication — an untested sign convention in a depth-sort predicate can produce silent visual regressions.

The LOW findings (CS-6 through CS-10) are each 1–3 line changes and collectively clean. The NITs (CS-11 through CS-14) are cosmetic.

**Prioritised action list:**

1. CS-4 — Name the `0.000000001` SAT threshold and unify epsilon notation. (5 min, zero risk.)
2. CS-5 — Decide fate of steps 2/3: remove or add validation test + annotation. (MED effort, MED correctness value.)
3. CS-1 — Merge the four vertex-scan loops into two. (15 min, improves readability.)
4. CS-2 / CS-3 — Extract AABB + SAT helpers in `IntersectionUtils`. (30 min, eliminates only material duplication.)
5. CS-6, CS-11, CS-13 — Delete private `min`/`max`; use `1e-6` form; import `abs`. (5 min combined.)
6. Remainder (CS-7, CS-8, CS-9, CS-10, CS-12, CS-14) — Low/NIT; address opportunistically.

**Result: PASS.** No blockers or high-severity findings. The implementation is correct and the simplifications above are improvements, not requirements to ship.
