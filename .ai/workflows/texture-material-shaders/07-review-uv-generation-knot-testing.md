---
slice-slug: uv-generation-knot
review-command: testing
command: /review:testing
date: 2026-04-20
scope: diff
target: e5cf72a (HEAD~1..HEAD)
paths:
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/UvGeneratorKnotTest.kt
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/PerFaceSharedApiTest.kt
related:
  slice-def: 03-slice-uv-generation-knot.md
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
---

# Testing Review: uv-generation-knot

**Reviewed:** diff `e5cf72a` (HEAD~1..HEAD)
**Date:** 2026-04-20
**Reviewer:** Claude Code

---

## 0) Scope, Test Strategy, and Context

**What was reviewed:**
- Scope: diff (commit `e5cf72a`)
- Files changed: 3 source files (+118 lines), 2 test files (+155 lines, -4 lines)
- Test framework: kotlin-test (JVM unit tests)
- 11 new test cases in `UvGeneratorKnotTest.kt` (new file, 139 lines)
- 4 lines removed + 20 lines added in `PerFaceSharedApiTest.kt`

**Test strategy (from plan §Test/Verification Plan):**
- JVM unit tests only (pure math, no Android dependencies)
- Plan called for 9 cases; implementation delivered 11 (split custom-quad tests 18 and 19)
- Snapshot (`knotTextured()` Paparazzi) deferred — matches precedent of all sibling slices
- Interactive verification deferred to `/wf-verify` stage

**Changed behavior:**
1. `Knot.sourcePrisms: List<Prism>` — new public val (`@ExperimentalIsometricApi`)
2. `UvGenerator.forKnotFace(Knot, Int): FloatArray` — new experimental function
3. `UvGenerator.forAllKnotFaces(Knot): List<FloatArray>` — new experimental function
4. `quadBboxUvs(Path): FloatArray` — new private helper
5. `uvCoordProviderForShape` — new `is Knot` branch replacing `null` return

**Acceptance criteria (from plan):**
- All 18 sub-prism faces return correct 8-float arrays
- Faces 18–19 custom quads return 8 floats in [0,1]
- `forAllKnotFaces` returns 20 arrays
- Invalid indices throw `IllegalArgumentException`
- `sourcePrisms` dimensions stay in sync with `createPaths` constants

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

**Rationale:**
The 11 test cases reliably cover the structural and boundary-value requirements called out
in the plan. The delegation identity tests for faces 0, 6, and 12 give strong confidence
that the dispatch table is correct, and the regression guard pins the most dangerous silent
failure mode (dimension drift). The gaps identified below are all MED or LOW and do not
block merge — they would meaningfully strengthen the suite if addressed in the verify stage
or a follow-up.

**Critical Gaps:**
1. **TS-1** [MED]: `forAllKnotFaces[i]` is never compared element-by-element against
   `forKnotFace(_, i)` — the bulk accessor is only size-checked.
2. **TS-2** [MED]: Custom-quad UV values are range-checked but never compared against
   concrete expected coordinates, so a sign flip or axis-swap in `quadBboxUvs` would pass.
3. **TS-3** [MED]: Delegation identity is only tested at the *first* face of each Prism
   block (faces 0, 6, 12). Faces 5, 11, 17 (last in each block) and the mid-block local
   index boundary (e.g., face 7 → `prismIndex=1, localFaceIndex=1`) are untested.
4. **TS-4** [LOW]: No parameterized test across `Knot.position` values — all tests use
   `Knot(Point.ORIGIN)`, masking any position-sensitivity bug in `forKnotFace`.
5. **TS-5** [LOW]: `CustomShape` fixture in `PerFaceSharedApiTest` uses a degenerate
   3-collinear-point `Path`; the `when` branch being tested is robust to this, but the
   fixture is unnecessarily fragile — see detail below.

**Overall Assessment:**
- Coverage: Acceptable
- Test Quality: Good (clean, evidence-first assertions, no flakiness)
- Flakiness Risk: Low
- Determinism: Excellent

---

## 2) Coverage Analysis

### Changed Behavior Coverage

| Behavior | File:Line | Tested? | Test Level | Coverage |
|----------|-----------|---------|------------|----------|
| `sourcePrisms` val exists + correct dims | `Knot.kt:35-38` | ✅ Yes | Unit | Regression guard, all 3 prisms |
| `forKnotFace` dispatch 0..17 → sub-prisms | `UvGenerator.kt:368-374` | ⚠️ Partial | Unit | First face of each block only |
| `forKnotFace` dispatch 18,19 → `quadBboxUvs` | `UvGenerator.kt:375` | ⚠️ Partial | Unit | Range [0,1] only; no exact values |
| `forKnotFace` bounds check | `UvGenerator.kt:360-363` | ✅ Yes | Unit | Both ends (-1 and 20) |
| `forAllKnotFaces` count | `UvGenerator.kt:391-392` | ⚠️ Partial | Unit | Size only; no per-element equivalence |
| `quadBboxUvs` axis selection | `UvGenerator.kt:397-430` | ❌ No | — | No test for non-XY-dominant paths |
| `uvCoordProviderForShape` Knot branch | `UvCoordProviderForShape.kt:38` | ✅ Yes | Unit | Non-null + size=8 |
| `uvCoordProviderForShape` else→null | `UvCoordProviderForShape.kt:39` | ✅ Yes | Unit | CustomShape |

**Coverage Summary:**
- ✅ Fully tested: 3 behaviors
- ⚠️ Partially tested: 3 behaviors
- ❌ Not tested: 1 behavior (quadBboxUvs axis-selection logic under non-XY-dominant geometry)

### Test Level Distribution

| Level | Tests | Appropriate? |
|-------|-------|--------------|
| Unit (JVM) | 11 | ✅ Correct for pure math |
| Integration | 0 | ✅ None needed — no I/O |
| E2E / Snapshot | 0 | ⚠️ Deferred (matches precedent) |

---

## 3) Findings Table

| ID | Severity | Confidence | Category | File:Line | Issue |
|----|----------|------------|----------|-----------|-------|
| TS-1 | MED | High | Coverage Gap | `UvGeneratorKnotTest.kt:91-97` | `forAllKnotFaces` only size-checked; no per-element equivalence with `forKnotFace` |
| TS-2 | MED | High | Assertion Quality | `UvGeneratorKnotTest.kt:67-88` | Custom quad 18/19 check only [0,1] range; concrete UV coordinates not asserted |
| TS-3 | MED | High | Coverage Gap | `UvGeneratorKnotTest.kt:45-64` | Delegation identity tested at boundary first-face only (0,6,12); last-face (5,11,17) and mid-block unverified |
| TS-4 | LOW | High | Coverage Gap | `UvGeneratorKnotTest.kt:26` | All tests use `Knot(Point.ORIGIN)`; no test for non-origin position |
| TS-5 | LOW | Med | Fixture Quality | `PerFaceSharedApiTest.kt:313` | `CustomShape` fixture uses degenerate 3-collinear-point Path; fragile w.r.t. `Shape` constructor constraints |
| TS-6 | LOW | High | Coverage Gap | `UvGeneratorKnotTest.kt` | `quadBboxUvs` axis-selection branch: no test for a path where Y-Z span dominates over X |
| TS-7 | NIT | High | Assertion Quality | `UvGeneratorKnotTest.kt:38-43` | `all 18 sub-prism faces return 8-float arrays` test is redundant — covered by delegation tests + `forAllKnotFaces`; marginal signal |
| TS-8 | NIT | Med | Regression Guard | `UvGeneratorKnotTest.kt:114-125` | Regression guard does not verify the *path count* of each source Prism (always 6), only dimensions; a `Prism` constructor change that altered path count would go undetected |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 0
- MED: 3
- LOW: 3
- NIT: 2

---

## 4) Findings (Detailed)

### TS-1: `forAllKnotFaces` only size-checked, no per-element equivalence [MED]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt:91-97`

**Current test:**
```kotlin
@Test
fun `forAllKnotFaces returns 20 arrays in path order`() {
    val all = UvGenerator.forAllKnotFaces(unitKnot)
    assertEquals(20, all.size)
    for (i in all.indices) {
        assertEquals(8, all[i].size, "face $i should return 8 floats")
    }
}
```

**What's missing:**
The test verifies count and element sizes but does not verify that `forAllKnotFaces[i]` is
equal (element-by-element) to `forKnotFace(knot, i)`. The implementation delegates:
```kotlin
fun forAllKnotFaces(knot: Knot): List<FloatArray> =
    knot.paths.indices.map { forKnotFace(knot, it) }
```
This is a one-liner today, but if someone refactors `forAllKnotFaces` to cache or
independently compute UV arrays (e.g., adds a `forAllKnotFaces`-specific caching path
similar to the Cylinder single-slot identity cache pattern), a divergence would not be
caught by the current test.

**Why it matters:** The plan explicitly describes `forAllKnotFaces` as the consumer-facing
bulk API. A silent divergence between the per-face and bulk results would cause visual
inconsistency depending on which path the renderer takes.

**Severity:** MED
**Confidence:** High

**Suggested test:**
```kotlin
@Test
fun `forAllKnotFaces is equivalent to calling forKnotFace for each index`() {
    val all = UvGenerator.forAllKnotFaces(unitKnot)
    assertEquals(20, all.size)
    for (i in all.indices) {
        val single = UvGenerator.forKnotFace(unitKnot, faceIndex = i)
        assertContentEquals(single, all[i], "face $i: forAllKnotFaces vs forKnotFace mismatch")
    }
}
```

---

### TS-2: Custom quad UV values range-checked only; no concrete coordinates [MED]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt:67-88`

**Current tests:**
```kotlin
@Test
fun `custom quad 18 returns 8 floats all within 0 to 1`() {
    val uvs = UvGenerator.forKnotFace(unitKnot, faceIndex = 18)
    assertEquals(8, uvs.size)
    for (i in uvs.indices) {
        assertTrue(uvs[i] in 0.0f..1.0f, ...)
    }
}
// same for face 19
```

**What's missing:**
The known pre-transform coordinates of custom quad 18 are hard-coded in `Knot.createPaths`:
```kotlin
Path(
    Point(0.0, 0.0, 2.0),
    Point(0.0, 0.0, 1.0),
    Point(1.0, 0.0, 1.0),
    Point(1.0, 0.0, 2.0)
)
```
After the `/5` scale and `(-0.1, 0.15, 0.4)` + `position` translation, the final points
are deterministic. These are projected by `quadBboxUvs` which selects the two largest-span
axes. For quad 18 the X-span = 1/5 = 0.2 and Z-span = 1/5 = 0.2 and Y-span = 0. So the
algorithm drops Z (smallest span), projects onto X and Y — but Y-span is 0, making V = 0
for all vertices while U spans [0,1]. This means the range check [0,1] passes even with a
constant-zero V row, which is geometrically degenerate (the quad collapses to a line in UV
space). Neither the range check nor the existing delegation tests detect this edge case.

**Why it matters:** Risk 3 in the plan explicitly accepts non-canonical winding but still
expects a *valid planar projection*. A degenerate UV (all-zero V) is not a valid planar
projection — the face would render as a single-color line, not a texture. The plan's
decision to accept "range in [0,1]" as the test contract may be masking a real correctness
issue for quad 18.

**Severity:** MED
**Confidence:** High

**Suggested investigation + test:**
```kotlin
@Test
fun `custom quad 18 UV projection is non-degenerate (no axis collapses to constant)`() {
    val uvs = UvGenerator.forKnotFace(unitKnot, faceIndex = 18)
    // Collect U values (even indices) and V values (odd indices)
    val uValues = FloatArray(4) { uvs[it * 2] }
    val vValues = FloatArray(4) { uvs[it * 2 + 1] }
    // At least one axis must span a non-zero range
    val uSpan = uValues.max() - uValues.min()
    val vSpan = vValues.max() - vValues.min()
    assertTrue(
        uSpan > 0.001f || vSpan > 0.001f,
        "UV projection is degenerate: all U=$uSpan, all V=$vSpan"
    )
}
```
Note: if the span is genuinely zero (quad 18 is a planar face with one collapsed axis), the
plan's Risk 3 acceptance should be explicitly re-evaluated and the test should document the
intended behavior.

---

### TS-3: Delegation identity tested only at first face of each Prism block [MED]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt:45-64`

**Current tests verify:**
- face 0 → `forPrismFace(sourcePrisms[0], 0)`
- face 6 → `forPrismFace(sourcePrisms[1], 0)`
- face 12 → `forPrismFace(sourcePrisms[2], 0)`

**What's untested:**
- face 5 → `forPrismFace(sourcePrisms[0], 5)` — last face in block 0
- face 11 → `forPrismFace(sourcePrisms[1], 5)` — last face in block 1
- face 17 → `forPrismFace(sourcePrisms[2], 5)` — last face in block 2
- face 7 → `forPrismFace(sourcePrisms[1], 1)` — mid-block, tests `localFaceIndex = faceIndex % 6`

The dispatch arithmetic is `prismIndex = faceIndex / 6; localFaceIndex = faceIndex % 6`.
Testing only `faceIndex = 0, 6, 12` means `localFaceIndex` is always 0 in all three
delegation tests. A bug where `localFaceIndex` was ignored (e.g., always passing 0)
would not be caught.

**Severity:** MED
**Confidence:** High

**Suggested tests:**
```kotlin
@Test
fun `face 5 delegates to forPrismFace on sourcePrisms 0 with local index 5`() {
    val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 5)
    val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[0], faceIndex = 5)
    assertContentEquals(expected, actual)
}

@Test
fun `face 7 delegates to forPrismFace on sourcePrisms 1 with local index 1`() {
    val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 7)
    val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[1], faceIndex = 1)
    assertContentEquals(expected, actual)
}

@Test
fun `face 17 delegates to forPrismFace on sourcePrisms 2 with local index 5`() {
    val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 17)
    val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[2], faceIndex = 5)
    assertContentEquals(expected, actual)
}
```

---

### TS-4: All tests use `Knot(Point.ORIGIN)`; non-origin position untested [LOW]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt:26`

**Issue:**
```kotlin
private val unitKnot = Knot(Point.ORIGIN)
```
Every test in the file uses `unitKnot`. The plan's `Knot.position` invariant states:
> Sub-prism delegation uses the pre-transform `sourcePrisms` because the prism dimensions
> are required for UV normalisation, and those dimensions are not recoverable from the
> scaled+translated paths that `Knot.paths` exposes at runtime.

The delegation pattern explicitly uses `sourcePrisms` (pre-transform) for UV math, making
UV generation position-invariant. However, the custom-quad path `quadBboxUvs(knot.paths[18])`
operates on the post-transform paths which *do* include the position offset. A non-origin
`Knot` would shift the bounding-box min/max values and, if the implementation were
accidentally using wrong paths or reference frames, the position test would catch it.

**Severity:** LOW
**Confidence:** High

**Suggested test (minimal):**
```kotlin
@Test
fun `forKnotFace is position-independent for sub-prism faces`() {
    val movedKnot = Knot(Point(3.0, 2.0, 1.0))
    // Sub-prism UVs use sourcePrisms (pre-transform) so must equal unitKnot's
    for (i in 0..17) {
        assertContentEquals(
            UvGenerator.forKnotFace(unitKnot, i),
            UvGenerator.forKnotFace(movedKnot, i),
            "face $i: sub-prism UV should be position-independent"
        )
    }
}

@Test
fun `forKnotFace custom quads 18-19 remain in 0-1 range at non-origin position`() {
    val movedKnot = Knot(Point(10.0, -5.0, 3.0))
    for (face in 18..19) {
        val uvs = UvGenerator.forKnotFace(movedKnot, faceIndex = face)
        for (v in uvs) {
            assertTrue(v in 0.0f..1.0f, "face $face UV $v out of [0,1] at non-origin")
        }
    }
}
```

---

### TS-5: `CustomShape` fixture in `PerFaceSharedApiTest` uses degenerate Path [LOW]

**Location:** `isometric-shader/src/test/kotlin/.../PerFaceSharedApiTest.kt:313`

**Current fixture:**
```kotlin
class CustomShape : Shape(listOf(Path(Point.ORIGIN, Point.ORIGIN, Point.ORIGIN)))
```

**Issues:**
1. The three points are identical (`Point.ORIGIN, Point.ORIGIN, Point.ORIGIN`), forming a
   degenerate zero-area triangle. The `Shape` constructor only requires `listOf(...)` to
   be non-empty; it does not validate path geometry. The test passes today, but if
   `Shape` or `Path` ever gains a geometric-validity constraint (e.g., requiring non-zero
   area), this fixture will fail to construct the `CustomShape`, breaking the test for
   an unrelated reason.
2. A 3-point `Path` creates a `CustomShape` that models a triangle face, which is atypical
   for this library (most shapes use quads). The test's intent is to cover the `else -> null`
   branch in `uvCoordProviderForShape`, which is purely a `when (shape)` dispatch test —
   the path geometry is irrelevant. A minimal non-degenerate quad would be less surprising
   to future maintainers.
3. The comment explains the *intent* well but not why a degenerate path was chosen — the
   implementation note in `05-implement-uv-generation-knot.md` explains it was the minimal
   construction that satisfies `Shape`'s `require` guard. This reasoning belongs in a test
   comment, not just in the impl log.

**Severity:** LOW
**Confidence:** Med (degeneracy only matters if `Shape`/`Path` gains validation)

**Suggested improvement:**
```kotlin
// CustomShape with a minimal non-degenerate quad to satisfy Shape's require(paths.isNotEmpty())
// while covering the `else -> null` branch for user-defined Shape subclasses.
class CustomShape : Shape(
    listOf(Path(Point(0.0,0.0,0.0), Point(1.0,0.0,0.0), Point(1.0,1.0,0.0), Point(0.0,1.0,0.0)))
)
assertNull(uvCoordProviderForShape(CustomShape()))
```

---

### TS-6: `quadBboxUvs` axis-selection branches not fully exercised [LOW]

**Location:** `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:397-430`

**The algorithm has three branches:**
```kotlin
when {
    spanZ <= spanX && spanZ <= spanY -> project onto (X, Y)   // Z is smallest span
    spanY <= spanX ->                   project onto (X, Z)   // Y is smallest span
    else ->                             project onto (Y, Z)   // X is smallest span
}
```

Custom quad 18 (`x: [0,1/5], y: [0], z: [1/5, 2/5]`) has `spanY = 0 < spanX = spanZ`,
which may hit the first branch (spanZ <= spanX is 0.2 <= 0.2, true; spanZ <= spanY is
0.2 <= 0.0, false) — actually falls into the second branch (Y <= X: 0 <= 0.2 → true).
Custom quad 19 (`x: [0], y: [0, 1/5], z: [1/5, 2/5]`) has `spanX = 0`, hitting the
`else` branch.

So in practice, only branches 2 and 3 are exercised by quads 18 and 19 for `Point.ORIGIN`
knot. Branch 1 (Z is smallest span, project onto X-Y plane) is never exercised by the
current `unitKnot` fixture. A typo in the branch-1 logic would not be caught.

**Severity:** LOW
**Confidence:** High

**Suggested test (can be a direct `private`-accessible test of the logic, or use a crafted Knot):**
```kotlin
// If quadBboxUvs were accessible (or via forKnotFace with a test-crafted knot
// whose path 18 is replaced with an XY-dominant quad), this would target branch 1.
// Until then, consider at minimum a comment documenting which branch each quad hits.
```
Alternatively, expose `quadBboxUvs` as `internal` for testing in the same module (the
sibling `UvGeneratorStairsTest` has a `private fun assertUvAt` helper pattern — a similar
test-accessible wrapper could be added).

---

### TS-7: `all 18 sub-prism faces return 8-float arrays` is largely redundant [NIT]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt:37-43`

The test loops `0..17` and asserts `size == 8`. The delegation identity tests (TS-3 covers
faces 0, 6, 12 with `assertContentEquals`) already imply size = 8 for those faces. The
`forAllKnotFaces` test (lines 91-97) already asserts `size == 8` for all 20 faces.
This test adds a minor redundancy check on the sub-prism block but no information
not already available.

**Impact:** None (passing redundant test is not harmful); it does bulk-test that `forKnotFace`
doesn't throw for any sub-prism index, which has marginal value beyond the delegation tests.

**Severity:** NIT
**Confidence:** High

---

### TS-8: Regression guard does not pin source Prism path count [NIT]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt:114-125`

**Current guard:**
```kotlin
fun `sourcePrisms dimensions match createPaths constants`() {
    val prisms = unitKnot.sourcePrisms
    assertEquals(3, prisms.size, "Knot must expose exactly 3 source prisms")
    assertPrism(prisms[0], Point.ORIGIN, width = 5.0, depth = 1.0, height = 1.0)
    assertPrism(prisms[1], Point(4.0, 1.0, 0.0), width = 1.0, depth = 4.0, height = 1.0)
    assertPrism(prisms[2], Point(4.0, 4.0, -2.0), width = 1.0, depth = 1.0, height = 3.0)
}
```

The guard pins the 3 Prism constructor dimensions but does not assert
`prisms[i].paths.size == 6`. The dispatch arithmetic `faceIndex / 6` and `faceIndex % 6`
implicitly assumes each Prism contributes exactly 6 paths. If `Prism.createPaths` were
ever changed to produce a different path count (e.g., by a future `Prism(n-gon)` variant),
the math would silently produce wrong `localFaceIndex` values without a CI failure.

This is a lower-priority concern than dimension drift (the primary Risk 2 the guard was
designed for), but since the guard is already present, adding path-count checks is trivial.

**Severity:** NIT
**Confidence:** High

**One-line addition to existing test:**
```kotlin
// Each source Prism contributes exactly 6 paths; the dispatch arithmetic depends on this.
prisms.forEachIndexed { i, p ->
    assertEquals(6, p.paths.size, "sourcePrisms[$i] must have exactly 6 paths")
}
```

---

## 5) Coverage Gaps Summary

### Important Gaps (MED)

1. **TS-1** — `forAllKnotFaces` per-element equivalence vs `forKnotFace` not tested.
2. **TS-2** — Custom quad UV values checked only for range; degenerate projection undetected.
3. **TS-3** — Delegation `localFaceIndex` arithmetic exercised only at value 0.

### Edge Cases (LOW)

4. **TS-4** — Position-invariance of sub-prism UV; position-dependence of custom quad UV not verified.
5. **TS-6** — `quadBboxUvs` XY-projection branch never exercised.

---

## 6) Test Quality Assessment

### Flakiness (Risk: Low)

No flakiness sources identified:
- Pure math, no I/O, no threading
- No `Date.now()`, no randomness
- All inputs are deterministic constructor calls
- Each test is fully self-contained

### Brittleness (Risk: Low)

Tests assert behavior (output content via `assertContentEquals`, output shape via `assertEquals`)
rather than implementation calls. No mocking. No snapshot misuse.

The delegation identity tests are appropriately coupled to behavior: they verify that
`forKnotFace(_, 0) == forPrismFace(sourcePrisms[0], 0)`, which is the documented contract,
not an implementation detail.

### Determinism (Status: Excellent)

All tests use deterministic `Knot(Point.ORIGIN)` construction. No shared mutable state.
Tests are order-independent.

---

## 7) PerFaceSharedApiTest Pivot Assessment

The pivot from "assertNull(uvCoordProviderForShape(Knot()))" to the positive Knot test +
`CustomShape` fixture is correct and necessary. The structural change is well-executed.

One concern (TS-5 above): the `@OptIn(ExperimentalIsometricApi::class)` annotation was
added on the previous `fun` (line 296 in the diff) but the new positive Knot test is
`@OptIn`-annotated separately inside the file. The `@file:OptIn` at the top of
`UvGeneratorKnotTest.kt` is a cleaner pattern that avoids per-test annotation repetition
— it could be adopted in `PerFaceSharedApiTest.kt` for the Knot-touching tests, though
this is a stylistic preference and not a correctness issue.

---

## 8) Regression Guard Sufficiency: What Happens if a 4th Source Prism is Added?

**The user brief asked specifically:** *"Is the regression guard sufficient if someone adds
a 4th source Prism?"*

**Assessment: No — the guard would miss the new Prism silently.**

The regression guard currently asserts:
```kotlin
assertEquals(3, prisms.size, "Knot must expose exactly 3 source prisms")
```

If a future maintainer adds a 4th Prism to `createPaths` and updates `sourcePrisms`, the
guard would fail with `"expected 3 but was 4"` — that part is caught. However:

1. The guard does not assert that `knot.paths.size == 20`. If the 4th Prism adds 6 more
   paths, `forKnotFace` would silently allow indices 0..25, but the dispatch logic
   `faceIndex / 6` with a hardcoded `in 0..17` range would misroute faces 18–23 to
   `quadBboxUvs` instead of `forPrismFace(sourcePrisms[3], ...)`, producing wrong UVs
   with no error.
2. The `when` block in `forKnotFace` hardcodes `in 0..17` and `18, 19` — these literal
   ranges would not update automatically if `sourcePrisms.size` changes.

**The structural risk is that `forKnotFace` does not derive its dispatch ranges
dynamically from `sourcePrisms.size` — it hardcodes them.** The regression guard catches
dimension drift but does not catch the case where `sourcePrisms.size` and the hardcoded
dispatch ranges diverge.

**Suggested enhancement to existing guard:**
```kotlin
// Also pin total face count to tie the dispatch ranges to the source data.
assertEquals(20, unitKnot.paths.size,
    "Knot must have exactly 20 paths: 3×6 sub-prism + 2 custom quad")

// Derive expected sub-prism range from prisms.size, not a magic number.
val expectedSubPrismFaces = prisms.size * 6
assertEquals(18, expectedSubPrismFaces,
    "3 source prisms × 6 faces each must equal the 0..17 dispatch range in forKnotFace")
```

---

## 9) Missing Snapshot / Property-Based Tests

**Snapshot (`knotTextured()`):**
Deferred; matches the precedent of all sibling slices (cylinder, pyramid, stairs,
octahedron all deferred Paparazzi snapshots for the same compose→shader dependency
inversion reason). No finding raised — the deferral is intentional and documented.

**Property-based tests:**
The suite would benefit from a simple property: *for all face indices in `0 until 20`,
`forKnotFace` produces 8 floats each in `[0,1]`*. This is currently split across two
tests (`all 18 sub-prism faces return 8-float arrays` + two custom-quad tests). A single
property-based test replacing all three would also catch any future path-count regression
without needing a count update.

No finding raised — PBT is aspirational for this codebase and sibling slices don't use it.

---

## 10) Recommendations

### Should Fix (MED) — before or during verify stage

1. **TS-1**: Add `forAllKnotFaces[i] == forKnotFace(_, i)` equivalence assertion.
   Estimated effort: 5 minutes.

2. **TS-3**: Add delegation identity tests for faces 5, 11, 17 (last in each block)
   and face 7 (mid-block, tests `localFaceIndex % 6 == 1`).
   Estimated effort: 10 minutes.

3. **TS-2**: Investigate whether custom quad 18 produces a degenerate UV (collapsed V axis
   due to zero Y-span) before accepting the range-only contract. If degenerate, raise as a
   correctness issue on `quadBboxUvs`; if non-degenerate, add exact expected values.
   Estimated effort: 15 minutes (includes manual verification of transformed coordinates).

### Consider (LOW) — verify stage or follow-on

4. **TS-4**: Add position-invariance test for sub-prism faces and range test for custom
   quads at non-origin position.

5. **TS-8**: Add `prisms[i].paths.size == 6` to existing regression guard.

6. **Regression guard gap (4th Prism scenario)**: Add `assertEquals(20, unitKnot.paths.size)`
   to the regression guard test to tie the count sentinel to the dispatch ranges.

### Cosmetic (NIT)

7. **TS-5**: Replace degenerate-point `CustomShape` fixture with a minimal non-degenerate quad.
8. **TS-7**: Consider removing or folding the `all 18 sub-prism faces return 8-float arrays`
   bulk size check into the equivalence test (TS-1 fix).
9. **TS-8**: Add Prism path-count assertion to the dimension regression guard.

**Total effort for MED fixes:** ~30 minutes.

---

## 11) Positive Observations

- Clean test class structure with clear `@Test` separation and intent-expressing names.
- `assertPrism` helper function avoids 12 repeated dimension assertions — good DRY practice.
- `assertContentEquals` (not `assertEquals`) correctly used for FloatArray comparison.
- `assertFailsWith<IllegalArgumentException>` at both boundary extremes (-1 and 20) is
  thorough for the bounds-check contract.
- `@file:OptIn` at the file level is idiomatic — avoids per-test annotation noise.
- The `sourcePrisms` regression guard is the most important single test in the file; it
  correctly pins position *and* all three dimension fields with `absoluteTolerance = 1e-9`.
- The PerFaceSharedApiTest pivot comment is clear and accurate about what changed and why.

---

## 12) False Positives and Disagreements Welcome

1. **TS-2 (degenerate UV)**: I computed the transformed coordinates of quad 18 analytically.
   If the actual runtime path points differ from my analysis (e.g., due to a path ordering
   difference in `createPaths`), the UV projection may be non-degenerate. The suggested
   investigation test would confirm either way.
2. **TS-3 (delegation identity)**: The `faceIndex / 6` and `% 6` arithmetic is simple
   enough that human review may be considered sufficient. The suggestion is additive
   insurance.
3. **Regression guard (4th Prism)**: This is speculative — no immediate plan to add a 4th
   Prism exists. If Knot's geometry is considered frozen, the current guard is sufficient.

---

*Review completed: 2026-04-20*
*Workflow: texture-material-shaders / uv-generation-knot*
