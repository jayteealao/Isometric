---
command: /review:correctness
slice-slug: uv-generation-knot
review-command: correctness
workflow: texture-material-shaders
date: 2026-04-20
scope: diff
target: "git diff HEAD~1 HEAD (commit e5cf72a)"
paths:
  - isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt
  - isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt
  - isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoordProviderForShape.kt
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/PerFaceSharedApiTest.kt
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/UvGeneratorKnotTest.kt
related:
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
  verify: 06-verify-uv-generation-knot.md
  slice-def: 03-slice-uv-generation-knot.md
---

# Correctness Review Report

**Reviewed:** commit `e5cf72a` — `feat(texture-material-shaders): implement uv-generation-knot`
**Date:** 2026-04-20
**Reviewer:** Claude Code

---

## 0) Scope, Intent, and Invariants

**What was reviewed:**
- Scope: diff (single implementation commit)
- Target: `git diff HEAD~1 HEAD` (e5cf72a)
- Files: 5 files, +281 added, −11 removed

**Intended behavior:**
- `Knot` (experimental, bag-of-primitives shape) gains UV coordinate support via `UvGenerator.forKnotFace`
- Faces 0–17 delegate to `forPrismFace` using the corresponding `sourcePrisms[i/6]` pre-transform Prism and local face index `i%6`
- Faces 18–19 (custom closing quads) project onto the two largest-extent axes via `quadBboxUvs` (bbox planar projection)
- `uvCoordProviderForShape(Knot)` returns a non-null provider; every stock shape is now wired

**Must-hold invariants:**
1. **Face-range completeness** — Every valid face index (0..19) is dispatched to exactly one code path; no face can fall through unhandled
2. **Sub-prism delegation identity** — `forKnotFace(knot, i)` for `i in 0..17` must produce bit-identical results to `forPrismFace(knot.sourcePrisms[i/6], i%6)`; any divergence silently corrupts UV
3. **`sourcePrisms` ↔ `createPaths` lock-step** — The three Prism constructor calls in `Knot.sourcePrisms` and `Knot.createPaths()` must be identical; drift produces UV coordinates computed against wrong dimensions
4. **No divide-by-zero** — `quadBboxUvs` must not crash on degenerate spans; outputs must be finite floats
5. **Output domain [0,1]** — All UV coordinates must remain in the normalised [0.0, 1.0] range for valid faces

**Key constraints:**
- `Knot` path count is a compile-time constant: exactly 20 (18 prism + 2 custom)
- `sourcePrisms` must be pre-transform (not world-space translated); UV normalisation requires the original Prism dimensions
- `quadBboxUvs` operates in post-transform path space (world-space paths including the 1/5 scale + offset translation)
- `@ExperimentalIsometricApi` annotation must propagate through all entry points

**Known edge cases from plan:**
- Custom quad faces 18 and 19 lie in a single plane (one axis has zero span); the axis-selection algorithm must exclude the zero-span axis
- Non-canonical UV winding on custom quads is accepted (affine mapping is winding-agnostic)
- Translated `Knot` (non-ORIGIN position): UV should be position-independent; bbox projection normalises relative spans

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

**Rationale:**
The core dispatch logic (face-range boundaries, prism-index math, zero-span guards) is correct for all reachable inputs. The `else` branch in `forKnotFace` is unreachable dead code — benign but worth noting. Two LOW-severity test-coverage gaps exist at prism-block boundary faces (5, 11, 17) and position-translated Knots. No BLOCKER or HIGH correctness issues were found.

**Critical Issues (BLOCKER/HIGH):**
None.

**Overall Assessment:**
- Correctness: Excellent
- Error Handling: Robust (require() gate + zero-span guards cover all edge cases)
- Edge Case Coverage: Good (ties and degenerate spans handled correctly; boundary-face delegation not explicitly asserted)
- Invariant Safety: Protected

---

## 2) Findings Table

| ID | Severity | Confidence | Category | File:Line | Failure Scenario |
|----|----------|------------|----------|-----------|------------------|
| CR-1 | NIT | High | Dead Code | `UvGenerator.kt:381-383` | `else` branch unreachable after `require()` gate |
| CR-2 | LOW | Med | Boundary Conditions | `UvGeneratorKnotTest.kt` | Off-by-one at prism-block boundaries (faces 5, 11, 17) not identity-tested |
| CR-3 | LOW | Low | Boundary Conditions | `UvGeneratorKnotTest.kt` | UV invariance under Knot translation not tested |
| CR-4 | NIT | High | Documentation | `UvGenerator.kt:397` | Comment "two largest-extent axes" inaccurate for Case 7 tie (spanY==spanZ < spanX) |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 0
- MED: 0
- LOW: 2
- NIT: 2

---

## 3) Findings (Detailed)

### CR-1: Dead `else` Branch in `forKnotFace` [NIT]

**Location:** `isometric-shader/src/main/kotlin/.../UvGenerator.kt:374-385`

**Invariant Involved:**
- Knot always has exactly 20 paths (constant from `createPaths`)
- `require(faceIndex in knot.paths.indices)` guards faceIndex to 0..19 before the `when`
- The two when branches cover `in 0..17` (18 values) and `18, 19` (2 values) = all of 0..19

**Evidence:**
```kotlin
// UvGenerator.kt:371-385
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) {   // gates 0..19
        "faceIndex $faceIndex out of bounds..."
    }
    return when (faceIndex) {
        in 0..17 -> { ... }                      // covers 0..17
        18, 19   -> quadBboxUvs(knot.paths[faceIndex])  // covers 18, 19
        else     -> throw IllegalArgumentException(     // UNREACHABLE
            "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
        )
    }
}
```

**Failure Scenario:**
Not a failure — the branch is unreachable. The `require()` throws before any input in `0..19` could reach `else`, and the two when arms collectively cover 0..19.

**Impact:**
None — dead code, not executed. No correctness risk.

**Severity:** NIT
**Confidence:** High
**Category:** Dead Code / Defensive Programming

**Note:** The `else` is a reasonable defensive pattern (it would produce a clear error if Knot's path count ever changed without updating the when dispatch). It could be replaced by `else -> error("unreachable")` to make the intent explicit, but this is style-only.

---

### CR-2: No Delegation-Identity Test for Prism-Block Boundary Faces 5, 11, 17 [LOW]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt`

**Invariant Involved:**
- Sub-prism delegation identity: `forKnotFace(knot, i)` must equal `forPrismFace(knot.sourcePrisms[i/6], i%6)` for all `i in 0..17`

**Evidence:**
The test suite asserts delegation identity at three points:
```kotlin
// Tests exist for these:
face 0  → forPrismFace(sourcePrisms[0], 0)   // first of prism block 0
face 6  → forPrismFace(sourcePrisms[1], 0)   // first of prism block 1
face 12 → forPrismFace(sourcePrisms[2], 0)   // first of prism block 2

// NOT tested:
face 5  → forPrismFace(sourcePrisms[0], 5)   // LAST of prism block 0
face 11 → forPrismFace(sourcePrisms[1], 5)   // LAST of prism block 1
face 17 → forPrismFace(sourcePrisms[2], 5)   // LAST of prism block 2
```

**Failure Scenario:**
A hypothetical off-by-one in the dispatch boundary `in 0..17` (e.g., if it were `in 0..16`) would cause face 17 to fall through to the `else` throw. The `all 18 sub-prism faces return 8-float arrays` test would catch the crash, but an incorrect delegation (e.g., if the boundary were `in 0..17` but `17/6=2, 17%6=5` were somehow computing wrong) would not be caught without an identity check.

**Actual Risk Assessment:**
The current dispatch `in 0..17` combined with integer division `faceIndex/6` and modulo `faceIndex%6` is mathematically correct (verified: 17/6=2, 17%6=5). The `all 18 sub-prism faces return 8-float arrays` loop does execute `forKnotFace` for faces 5, 11, 17 and would catch any crash. The missing piece is identity assertion at the boundaries — a refactor that accidentally changed the dispatch would not be caught until visual regression.

**Severity:** LOW
**Confidence:** Med
**Category:** Test Coverage Gap

**Patch Suggestion:**
Add boundary delegation tests:

```kotlin
@Test
fun `face 5 delegates to forPrismFace on sourcePrisms 0 local 5`() {
    val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 5)
    val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[0], faceIndex = 5)
    assertContentEquals(expected, actual)
}

@Test
fun `face 11 delegates to forPrismFace on sourcePrisms 1 local 5`() {
    val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 11)
    val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[1], faceIndex = 5)
    assertContentEquals(expected, actual)
}

@Test
fun `face 17 delegates to forPrismFace on sourcePrisms 2 local 5`() {
    val actual = UvGenerator.forKnotFace(unitKnot, faceIndex = 17)
    val expected = UvGenerator.forPrismFace(unitKnot.sourcePrisms[2], faceIndex = 5)
    assertContentEquals(expected, actual)
}
```

---

### CR-3: No Test for UV Invariance Under Knot Translation [LOW]

**Location:** `isometric-shader/src/test/kotlin/.../UvGeneratorKnotTest.kt`

**Invariant Involved:**
- Texture UV should not depend on world-space position of the Knot (texture tiles correctly on any placed Knot)

**Evidence:**
All 11 test cases use `unitKnot = Knot(Point.ORIGIN)`. No test creates a translated Knot and verifies UV output is identical.

**Analysis:**
For sub-prism faces (0..17): `forKnotFace` delegates to `forPrismFace(sourcePrisms[i], localFace)` where `sourcePrisms` are at fixed pre-transform positions (independent of `knot.position`). UV is position-independent by construction.

For custom quad faces (18..19): `quadBboxUvs(knot.paths[faceIndex])` receives post-transform vertices which include `knot.position` in their absolute coordinates. However, `quadBboxUvs` normalises by `(pt.x - minX) / spanX` — a relative, not absolute, computation. A translation of all points by a constant shifts `minX` by the same constant; `(pt.x + dx) - (minX + dx) = pt.x - minX`. UV is invariant under translation.

**Actual Risk:**
Low. The invariance is provable from the code. The missing test means a future refactor of `quadBboxUvs` to use absolute coordinates (a regression) would not be caught until visual regression. Not a current defect.

**Severity:** LOW
**Confidence:** Low (speculative future regression path)
**Category:** Test Coverage Gap

**Test Suggestion:**
```kotlin
@Test
fun `UV results are identical for Knot at non-origin position`() {
    val translatedKnot = Knot(Point(3.0, -2.0, 1.5))
    for (i in 0..19) {
        val uvOrigin = UvGenerator.forKnotFace(unitKnot, i)
        val uvTranslated = UvGenerator.forKnotFace(translatedKnot, i)
        assertContentEquals(uvOrigin, uvTranslated, "face $i UV should be position-independent")
    }
}
```

---

### CR-4: `quadBboxUvs` Comment Imprecise for Tie Case 7 (spanY==spanZ < spanX) [NIT]

**Location:** `isometric-shader/src/main/kotlin/.../UvGenerator.kt:396-400`

**Evidence:**
```kotlin
// Axis-aligned bounding-box planar projection for a 4-vertex path. Projects
// onto the two largest-extent axes; winding order is preserved from the
// source path...
```

**Analysis:**
The comment says "projects onto two largest-extent axes". This is accurate in 6 of 7 ordering cases. In Case 7 (spanY == spanZ < spanX): X is the unique largest; Y and Z are tied as second-largest. Branch 1 fires (`spanZ <= spanX` AND `spanZ <= spanY` both true since Y==Z), projecting X,Y. Z has the same span as Y but is dropped. The algorithm keeps X (largest) and Y (one of the tied second), dropping Z (also tied second). The projection is onto X and Y, both of which are in the "two largest" set — Z is in the "two largest" set too, but is not chosen.

**Impact:**
No correctness issue. The axis selection in all 7 cases (including ties) selects a valid projection plane that covers maximum variance. The tie-breaking is deterministic and consistent. The comment's imprecision could confuse a future maintainer who expects Y==Z to always produce a Y,Z projection when X is dominant.

**Severity:** NIT
**Confidence:** High
**Category:** Documentation

**Patch Suggestion:**
```diff
-    // Axis-aligned bounding-box planar projection for a 4-vertex path. Projects
-    // onto the two largest-extent axes; winding order is preserved from the
+    // Axis-aligned bounding-box planar projection for a 4-vertex path. Drops the
+    // smallest-extent axis and projects onto the remaining two. In tie cases where
+    // two spans share the minimum value, the tie is broken deterministically by the
+    // when-branch ordering (Z tie → kept; Y tie → Z wins; X tie → Y wins).
+    // Winding order is preserved from the
```

---

## 4) Invariants Coverage Analysis

| Invariant | Enforcement | Status |
|-----------|-------------|--------|
| Face-range completeness (all 0..19 dispatched) | `require()` gate + when branches covering 0..17 and 18,19 | Protected |
| Sub-prism delegation identity | Direct delegation with integer arithmetic (verified correct) | Protected |
| `sourcePrisms` ↔ `createPaths` lock-step | Regression guard test pins all 3 prisms' positions and dimensions | Protected |
| No divide-by-zero in `quadBboxUvs` | `if (span > 0.0)` guard before each division | Protected |
| Output domain [0,1] | Tests assert `[0,1]` for custom quads; sub-prism delegation inherits Prism UV correctness | Protected |
| Position-independence of UV | Provable from relative-normalization math; not explicitly tested | Vulnerable (test gap only) |
| Delegation at prism-block boundaries | Not explicitly identity-tested (faces 5, 11, 17) | Vulnerable (test gap only) |

---

## 5) Edge Cases Coverage

| Edge Case | Handled? | Evidence |
|-----------|----------|----------|
| Negative face index | Yes | `require()` gate; test `negative face index throws` |
| Face index = 20 (one past last) | Yes | `require()` gate; test `face index beyond 19 throws` |
| Custom quad with zero Y-span (face 18) | Yes | `quadBboxUvs` branch 2 fires (`spanY=0 <= spanX`), projects X,Z |
| Custom quad with zero X-span (face 19) | Yes | `quadBboxUvs` else fires, projects Y,Z |
| All spans equal (cube face) | Yes | Branch 1 fires, projects X,Y (deterministic) |
| Translated Knot | Implicitly correct (relative normalization) | Not tested |
| `forAllKnotFaces` completeness | Yes | Returns 20 arrays; each 8 floats |
| `sourcePrisms` dimension drift | Yes | Regression guard test pins all constants |
| Boundary prism-block faces (5, 11, 17) | Crash-caught only | No identity assertion at these boundaries |

---

## 6) `quadBboxUvs` Axis-Selection Full Verification

All 7 ordering cases for (spanX, spanY, spanZ) have been verified:

| Case | Condition | Branch Fired | Projection | Correct? |
|------|-----------|--------------|------------|----------|
| Z unique min | spanZ < spanX, spanZ < spanY | Branch 1 | X,Y (drop Z) | Yes |
| Y unique min, Z ≤ X | spanY < spanX, spanY < spanZ, spanZ ≤ spanX | Branch 2 | X,Z (drop Y) | Yes |
| Y unique min, X < Z | spanY < spanX, spanY < spanZ, spanX < spanZ | Branch 2 | X,Z (drop Y) | Yes |
| X unique min | spanX < spanY, spanX < spanZ | Else | Y,Z (drop X) | Yes |
| X==Y (tied), Z largest | spanX==spanY < spanZ | Branch 2 (`spanY<=spanX` equal) | X,Z (drop Y) | Yes — Z is largest |
| X==Z (tied), Y largest | spanX==spanZ < spanY | Else | Y,Z (drop X) | Yes — Y is largest |
| Y==Z (tied), X largest | spanX > spanY==spanZ | Branch 1 (`spanZ<=spanY` equal) | X,Y (drop Z) | Yes — X is largest; Y,Z tied |
| All equal | spanX==spanY==spanZ | Branch 1 | X,Y | Yes — arbitrary, consistent |
| All zero (degenerate) | 0==0==0 | Branch 1 | X,Y; guards return 0.0 | Yes — no div-by-zero |

The axis-selection logic is correct for all cases. The zero-divisor guards (`if (span > 0.0)`) prevent divide-by-zero in all degenerate cases.

---

## 7) `sourcePrisms` vs `createPaths` Invariant Verification

The constants in both locations were verified to be byte-for-byte identical:

| Index | `createPaths` | `sourcePrisms` |
|-------|---------------|----------------|
| 0 | `Prism(Point.ORIGIN, 5.0, 1.0, 1.0)` | `Prism(Point.ORIGIN, 5.0, 1.0, 1.0)` |
| 1 | `Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0)` | `Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0)` |
| 2 | `Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0)` | `Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0)` |

The regression guard test in `UvGeneratorKnotTest.sourcePrisms dimensions match createPaths constants` pins all three positions and dimensions, which will catch any future drift in CI.

---

## 8) Dispatch Boundary Off-by-One Analysis

```
Face:  0  1  2  3  4  5 | 6  7  8  9 10 11 | 12 13 14 15 16 17 | 18 19
       ←─── prism[0] ───→ ←─── prism[1] ───→ ←─── prism[2] ───→ ← custom →
        faceIndex/6 = 0    faceIndex/6 = 1    faceIndex/6 = 2    else-arm
        faceIndex%6: 0–5   faceIndex%6: 0–5   faceIndex%6: 0–5
```

- `in 0..17` is inclusive on both ends. Face 17: `17/6 = 2`, `17%6 = 5` → `forPrismFace(sourcePrisms[2], 5)`. Correct.
- Face 18: falls to `18, 19 ->` arm. Correct.
- The `in 0..17` boundary does not accidentally include 18 (Kotlin `in 0..17` = `0 <= x && x <= 17`).
- No off-by-one at any boundary.

---

## 9) Error Handling Assessment

**Good patterns:**
- `require()` gate on entry provides clear `IllegalArgumentException` with context before any computation
- `quadBboxUvs` zero-divisor guards prevent silent NaN propagation
- Double-to-Float cast is safe (all values in [0,1] range)

**No issues found:**
- No swallowed exceptions
- No silent degradation paths
- No error context lost

---

## 10) Concurrency Assessment

`forKnotFace` and `quadBboxUvs` are pure functions with no shared mutable state:
- `knot.paths` and `knot.sourcePrisms` are immutable `val`s
- `quadBboxUvs` allocates and returns a fresh `FloatArray` per call (no caching, no shared state)
- `forPrismFace` has a single-slot identity cache for Pyramid (not relevant here); Prism delegation has no cache

Thread-safe by construction. No concurrency concerns.

---

## 11) Test Coverage Gaps Summary

**Missing but non-blocking:**
- [ ] Delegation identity for boundary faces 5, 11, 17 (CR-2)
- [ ] UV invariance for translated Knot at non-ORIGIN position (CR-3)
- [ ] `forAllKnotFaces` cross-checked against per-face `forKnotFace` calls for consistency

**Not missing (intentionally omitted per plan):**
- Specific UV values for custom quads 18 and 19 — non-canonical winding accepted

---

## 12) Recommendations

### Consider (LOW)

1. **CR-2**: Add delegation-identity tests at prism-block boundary faces (5, 11, 17)
   - Estimated effort: ~10 lines (3 test functions)
   - Rationale: Makes the dispatch boundary explicitly regression-guarded

2. **CR-3**: Add UV position-invariance test for translated Knot
   - Estimated effort: ~12 lines (1 test function)
   - Rationale: Makes the bbox-relative-normalisation invariant explicit in the test suite

### Optional (NIT)

3. **CR-4**: Clarify `quadBboxUvs` comment about tie-breaking behavior

4. **CR-1**: Replace `else -> throw` with `else -> error("unreachable")` if defensive intent is desired, or remove it and rely on the `require()` guard alone

### Overall Strategy

All issues are documentation or test-gap level. The implementation is correct and well-structured. Safe to merge; the two LOW findings are hardening suggestions appropriate for a follow-up.

---

*Review completed: 2026-04-20*
*Commit reviewed: `e5cf72a`*
*Slice: `uv-generation-knot` / Workflow: `texture-material-shaders`*
