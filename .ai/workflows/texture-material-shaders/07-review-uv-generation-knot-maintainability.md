---
slice-slug: uv-generation-knot
review-command: maintainability
command: /review:maintainability
date: 2026-04-20
scope: diff
target: HEAD~1..HEAD (commit e5cf72a)
paths:
  - isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt
  - isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt
  - isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoordProviderForShape.kt
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/UvGeneratorKnotTest.kt
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/PerFaceSharedApiTest.kt
related:
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
  slice-def: 03-slice-uv-generation-knot.md
---

# Maintainability Review — uv-generation-knot

**Reviewed:** diff / commit e5cf72a (HEAD~1..HEAD)
**Date:** 2026-04-20
**Reviewer:** Claude Code

---

## 0) Scope, Intent, and Conventions

**What was reviewed:**
- Scope: diff (single implementation commit)
- Target: e5cf72a — feat(texture-material-shaders): implement uv-generation-knot
- Files: 5 files, +281 lines added, −11 lines removed
  - `Knot.kt` (+25): `sourcePrisms` val, class KDoc extension, drift-warning comment
  - `UvGenerator.kt` (+92): `forKnotFace`, `forAllKnotFaces`, `quadBboxUvs`
  - `UvCoordProviderForShape.kt` (+8 / −7): `is Knot` branch, `@OptIn`, KDoc prose update
  - `UvGeneratorKnotTest.kt` (+139): 11 new unit cases (new file)
  - `PerFaceSharedApiTest.kt` (+16 / −4): Knot provider assertion, CustomShape stub

**Intent:**
- Enable `textured()` materials on `Knot` shapes in Canvas and WebGPU render modes
- Reuse `forPrismFace` for the 18 sub-prism faces via newly-exposed `sourcePrisms`
- Use axis-aligned bounding-box planar projection for the 2 custom closing quads (indices 18–19)
- Explicitly document `perFace {}` as unsupported (silent fallback to `default`)

**Team conventions (inferred from codebase):**
- `UvGenerator` is `internal object`; every shape gets a `forXFace(shape, faceIndex)` / `forAllXFaces(shape)` pair
- `@ExperimentalIsometricApi` propagates from shapes to every API that touches them
- `@OptIn` granularity: function-level in `UvGenerator.kt`, file-level in `UvCoordProviderForShape.kt`, `@file:OptIn` in test files
- KDoc on public/internal symbols should explain "why" not just "what" — precedent set by `forCylinderFace` and `forPyramidFace`
- Regression-guard tests pin critical constant duplication

**Review focus (as specified by caller):**
1. Drift surface between `sourcePrisms` and `createPaths` — could duplication be eliminated?
2. `@OptIn` propagation strategy — function-level vs file-level consistency
3. Name clarity of `quadBboxUvs` — does it convey "axis-aligned bounding box planar projection"?
4. KDoc completeness on new public/internal symbols
5. Comment quality — WHY-vs-WHAT issues
6. Readability of face-range `when` branches

---

## 1) Executive Summary

**Merge Recommendation:** APPROVE_WITH_COMMENTS

**Rationale:**
The implementation is structurally sound, follows the established UV-generator shape pattern consistently, and ships correct regression-guard tests. The four maintainability issues below are all LOW or NIT — none blocks a merge. The highest-friction item (the `sourcePrisms` / `createPaths` duplication) is intentional and documented, but a small structural improvement could eliminate it entirely without changing any observable behavior. The `@OptIn` inconsistency between files is cosmetic but creates a predictable confusion point for the next editor.

**Top Maintainability Issues:**
1. **MA-1 [MED]** — `sourcePrisms` drift surface: the duplication could be fully eliminated by deriving paths in `createPaths` from `sourcePrisms`, turning the regression guard from a documentation artifact into a compile-time invariant.
2. **MA-2 [LOW]** — `@OptIn` granularity inconsistency: `UvGenerator.kt` uses function-level `@OptIn` while `UvCoordProviderForShape.kt` uses file-level `@OptIn` for the same annotation on the same class (`Knot`). The inconsistency creates a pattern ambiguity for future shape slices.
3. **MA-3 [LOW]** — `quadBboxUvs` name: the abbreviation `Bbox` is not obvious; the projection strategy (drop the smallest-span axis) is non-trivial and not visible at the three call sites or from the private helper's name alone.
4. **MA-4 [NIT]** — `forKnotFace` `when` dead branch: the `else -> throw` arm is unreachable because the preceding `require` already rejects any `faceIndex` outside `0 until knot.paths.size` (which is always 20), and the `in 0..17` + `18, 19` arms are exhaustive over `0..19`.

**Overall Assessment:**
- Cohesion: Excellent — each file has a single clear responsibility
- Coupling: Minimal — no new cross-layer dependencies introduced
- Complexity: Simple — `forKnotFace` is 14 lines; `quadBboxUvs` is 30 lines with one level of nesting inside the loop
- Consistency: Good — follows the sibling shape pattern except for the `@OptIn` granularity split
- Change Amplification: Low — adding a future `KnotFace` enum would touch only `UvGenerator.kt` and its test

---

## 2) Module Structure Analysis

| Module | Lines added | Responsibilities | Cohesion | Dependencies | Verdict |
|--------|-------------|------------------|----------|--------------|---------|
| `Knot.kt` | +25 | Shape geometry, `sourcePrisms` exposure | Focused | `Prism`, `Path`, `Point` | Good — clean single addition |
| `UvGenerator.kt` | +92 | UV math for all stock shapes | Focused | shape types only | Good — follows existing structure |
| `UvCoordProviderForShape.kt` | +8/−7 | UV provider factory dispatch | Focused | shape + generator | Good — one-liner branch completes the set |
| `UvGeneratorKnotTest.kt` | +139 | Knot UV unit tests | Focused | `UvGenerator`, `Knot` | Good — 11 cases, clear names |
| `PerFaceSharedApiTest.kt` | +16/−4 | Cross-shape provider contract | Focused | provider factory | Good — correct pivot from shape-subtraction to CustomShape |

**Observations:**
- All 5 files have clear single responsibility
- Zero mixed concerns
- No new god objects or utility dumping grounds

---

## 3) Coupling Analysis

```
isometric-core
  Knot.kt — references Prism, Path, Point (all core)

isometric-shader
  UvCoordProviderForShape.kt — references Knot (core), UvGenerator (shader-internal)
  UvGenerator.kt             — references Knot (core), Path (core)
```

**Cross-layer violations:** None found. `Knot` in `isometric-core` does not import anything from `isometric-shader`. `UvGenerator` imports `Knot` from core, which is the correct direction (shader → core).

**Circular dependencies:** None. The `sourcePrisms` property stores `Prism` objects (core → core). `UvGenerator.forKnotFace` consumes `knot.sourcePrisms` (shader consuming core public API).

**Hidden coupling:** The constant duplication between `sourcePrisms` and `createPaths` (see MA-1) is the closest thing to hidden coupling in this diff — the two definitions must stay in sync but there is no compile-time enforcement.

---

## 4) Findings Table

| ID | Severity | Confidence | Category | Location | Issue |
|----|----------|------------|----------|----------|-------|
| MA-1 | MED | High | Duplication / Change Amplification | `Knot.kt:35–53` | `sourcePrisms` and `createPaths` duplicate the same three Prism constants; drift is guarded by a test but not structurally prevented |
| MA-2 | LOW | High | Consistency (`@OptIn`) | `UvGenerator.kt:368,391` vs `UvCoordProviderForShape.kt:31` | Function-level `@OptIn` in `UvGenerator.kt`; file-level `@OptIn` in `UvCoordProviderForShape.kt` — inconsistent granularity for the same annotation on the same class |
| MA-3 | LOW | Med | Naming | `UvGenerator.kt:401` (private `quadBboxUvs`) | `Bbox` is an opaque abbreviation; the projection algorithm (drop-smallest-span axis) is a non-trivial choice not captured by the name |
| MA-4 | NIT | High | Complexity (dead code) | `UvGenerator.kt:381–384` | `else -> throw` in `forKnotFace when` is unreachable — `require` at line 371 and the exhaustive `in 0..17`/`18,19` arms cover the full `0..19` range |

**Findings Summary:**
- BLOCKER: 0
- HIGH: 0
- MED: 1
- LOW: 2
- NIT: 1

---

## 5) Findings (Detailed)

### MA-1: `sourcePrisms` / `createPaths` Constant Duplication [MED]

**Location:** `isometric-core/src/main/kotlin/.../shapes/Knot.kt:35–53`

**Evidence:**
```kotlin
// Knot.kt:35–39 — instance val
val sourcePrisms: List<Prism> = listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
)

// Knot.kt:51–53 — companion createPaths (identical constants)
allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)
```

**Issue:**
The same three Prism dimension tuples appear twice. The companion comment and the `sourcePrisms` KDoc both acknowledge this and direct editors to update both sites. The regression test `sourcePrisms dimensions match createPaths constants` (`UvGeneratorKnotTest.kt:107`) catches any drift at CI time, but does not prevent it at edit time.

The root cause is architectural: `createPaths` is a `private companion fun` called from `Shape(createPaths(position))` in the `Knot` class header, so it executes before the instance body — `sourcePrisms` cannot refer to the companion's locals, and the companion cannot refer to the instance val. However, the dependency direction is actually reversible: `createPaths` could read from `sourcePrisms` if `sourcePrisms` were moved to the companion as a `val`.

**Impact:**
- **Change scenario:** "Resize one of the Knot sub-prisms." An editor updating `createPaths` must also remember to update `sourcePrisms`. The regression test gives a fast-fail at CI, but the window between the edit and the CI run allows a misleading intermediate state in a local branch.
- **Comment maintenance cost:** Two comments (`// If you change the Prism constants below…` in companion; KDoc on `sourcePrisms`) must be kept in sync with the code they describe.

**Severity:** MED
**Confidence:** High
**Category:** Duplication (Configuration) + Change Amplification

**Smallest fix — move Prism definitions to companion and derive paths from them:**

```diff
--- a/isometric-core/src/main/kotlin/.../shapes/Knot.kt
+++ b/isometric-core/src/main/kotlin/.../shapes/Knot.kt

 class Knot(val position: Point = Point.ORIGIN) : Shape(createPaths(position)) {

-    @ExperimentalIsometricApi
-    val sourcePrisms: List<Prism> = listOf(
-        Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
-        Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
-        Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
-    )
+    /** @see Companion.SOURCE_PRISMS */
+    @ExperimentalIsometricApi
+    val sourcePrisms: List<Prism> get() = SOURCE_PRISMS

     companion object {
-        // If you change the Prism constants below, update `sourcePrisms` above to
-        // match — UV generation for Knot relies on the two being in lock-step.
+        @ExperimentalIsometricApi
+        val SOURCE_PRISMS: List<Prism> = listOf(
+            Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
+            Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
+            Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
+        )
+
         private fun createPaths(position: Point): List<Path> {
             val allPaths = mutableListOf<Path>()
-            allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
-            allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
-            allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)
+            SOURCE_PRISMS.forEach { allPaths.addAll(it.paths) }
             // …custom quads, scale, translate…
```

**Note on the alternative:** `SOURCE_PRISMS` would add one new public API symbol to `isometric-core.api`. If that is undesirable, a `private` companion val feeds `createPaths` without exposing the list, and `sourcePrisms` delegates to it. Either way the single source of truth is in the companion.

**Benefit:**
- The regression-guard test in `UvGeneratorKnotTest` can be simplified to a structural assertion (`Knot().sourcePrisms === Knot.SOURCE_PRISMS` or similar), or deleted entirely
- The two editorial comments directing "update both sites" become obsolete and can be removed
- Any future change to sub-prism dimensions touches exactly one location

**Caveat:** This is a binary-compatible API change (`sourcePrisms` remains a `val` of type `List<Prism>`) but it changes `sourcePrisms` from a per-instance `val` to a property backed by a companion `val`. The observable difference is that all `Knot` instances now share the same list object, which is safe because `List<Prism>` is already effectively immutable (Prism is a data-like class with no mutation surface). The regression-guard test in `UvGeneratorKnotTest.kt:107–132` does not need to change — it still reads `unitKnot.sourcePrisms`.

---

### MA-2: `@OptIn` Granularity Inconsistent Across Files [LOW]

**Location:**
- `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:368,391` (function-level)
- `isometric-shader/src/main/kotlin/.../shader/UvCoordProviderForShape.kt:31` (file-level)

**Evidence:**
```kotlin
// UvGenerator.kt — function-level, repeated on forKnotFace and forAllKnotFaces
@OptIn(ExperimentalIsometricApi::class)
@ExperimentalIsometricApi
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray { … }

@OptIn(ExperimentalIsometricApi::class)
@ExperimentalIsometricApi
fun forAllKnotFaces(knot: Knot): List<FloatArray> = …

// UvCoordProviderForShape.kt — file-level, covers the entire when expression
@OptIn(ExperimentalIsometricApi::class)
internal fun uvCoordProviderForShape(shape: Shape): UvCoordProvider? = when (shape) { … }
```

**Issue:**
`UvGenerator.kt` applies `@OptIn(ExperimentalIsometricApi::class)` at the individual function level; `UvCoordProviderForShape.kt` applies it at file level. Both patterns are correct Kotlin, but they are inconsistent in the same codebase for the same annotation. A future shape slice adding a new `forXxxFace` to `UvGenerator.kt` will need to know which convention to follow.

**Additional observation:** `forAllKnotFaces` also carries a redundant `@OptIn` — since it delegates entirely to `forKnotFace` (which itself is already `@ExperimentalIsometricApi` and thus requires no additional opt-in from within `UvGenerator`'s own scope), the `@OptIn` on `forAllKnotFaces` adds no suppression. The `@ExperimentalIsometricApi` on it is correct (propagates to callers), but the `@OptIn` is noise.

**Impact:**
- **Change scenario:** "Add `forXxxFace` for a new shape that is also experimental." An editor scanning the existing Knot functions as a template sees two `@OptIn` annotations on both `forKnotFace` and `forAllKnotFaces`. They are likely to copy both, even though `forAllKnotFaces`'s `@OptIn` is redundant. Inconsistency with `UvCoordProviderForShape.kt` means the pattern question recurs with every new slice.

**Severity:** LOW
**Confidence:** High
**Category:** Consistency (`@OptIn` granularity)

**Smallest fix:**

Option A — standardize `UvGenerator.kt` to file-level `@OptIn`, matching `UvCoordProviderForShape.kt`:
```diff
+@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)
 package io.github.jayteealao.isometric.shader

 // Remove the four per-function @OptIn annotations from forKnotFace and forAllKnotFaces
```

Option B — keep function-level in both files; remove the `@OptIn` from `forAllKnotFaces` (where it is redundant) and add a code comment explaining the split:
```diff
-    @OptIn(ExperimentalIsometricApi::class)
     @ExperimentalIsometricApi
     fun forAllKnotFaces(knot: Knot): List<FloatArray> = …
```

Option A is preferable if future knot-adjacent methods (e.g. a hypothetical `forKnotFaceRange`) also need opt-in, since it avoids annotation repetition at declaration sites.

**Benefit:**
- Establishes a single clear convention for any future experimental-shape slice
- Removes the redundant `@OptIn` on `forAllKnotFaces`

---

### MA-3: `quadBboxUvs` Name Does Not Convey Projection Strategy [LOW]

**Location:** `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:401`

**Evidence:**
```kotlin
// Line 401
private fun quadBboxUvs(path: Path): FloatArray {
    // …projects onto the two largest-extent axes…
```

The only caller is:
```kotlin
// Line 380
18, 19 -> quadBboxUvs(knot.paths[faceIndex])
```

**Issue:**
`quadBboxUvs` tells a reader:
- "quad" — the input is a quadrilateral (inferable from context)
- "Bbox" — bounding box (reasonable abbreviation, but `AABB` or `aabb` is more standard in 3D graphics)
- "Uvs" — output is UVs

What the name does not tell the reader:
- The projection strategy is "drop the smallest-span axis" (not e.g. flat XY-plane projection, or camera-facing projection, or spherical unwrap)
- The result is non-canonical — winding order is source-preserved, not sorted to `(0,0)(1,0)(1,1)(0,1)`

The block comment above the function (`// Axis-aligned bounding-box planar projection for a 4-vertex path.`) does explain the strategy, but that comment is not visible at call sites, and the method name itself is what a future reader searches for.

**Impact:**
- **Change scenario:** "Add UV support for a different shape that also has irregular quads." An engineer looking for an existing bounding-box projection helper might search for `aabb`, `axisAligned`, or `planarProject` — all miss `quadBboxUvs`. Conversely, a reader unfamiliar with "bbox" might not recognize it as a projection helper at all.
- The `private` visibility means this is low-stakes (no public API surface), but it does affect internal readability of `UvGenerator.kt` — a 500+ line file where names are the primary navigation aid.

**Severity:** LOW
**Confidence:** Med (naming is partly style preference)
**Category:** Naming

**Smallest fix — rename to express the projection strategy:**
```diff
-    private fun quadBboxUvs(path: Path): FloatArray {
+    /**
+     * Projects a 4-vertex path onto the UV plane by selecting the two axes with
+     * the largest bounding-box span (axis-aligned bounding-box / "AABB" projection).
+     * The smallest-span axis is discarded. Winding order is preserved from [path].
+     * Degenerate spans (extent ≈ 0) collapse to u/v = 0 to avoid division by zero.
+     */
+    private fun aabbPlanarProjectUvs(path: Path): FloatArray {
```

And update the three references (`forKnotFace:380`, the block comment above the function, and the `forKnotFace` KDoc at line 356).

**Alternative:** Keep `quadBboxUvs` but promote the block comment immediately above it to a standard KDoc `/** */`, so IDEs surface it at call sites. This is the minimum viable improvement with zero renaming risk.

**Benefit:**
- Name becomes searchable by engineers familiar with 3D graphics vocabulary
- Strategy is visible at the call site in `forKnotFace` without needing to navigate to the implementation

---

### MA-4: Dead `else -> throw` Branch in `forKnotFace` `when` [NIT]

**Location:** `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:381–384`

**Evidence:**
```kotlin
// Lines 371–384
require(faceIndex in knot.paths.indices) {          // (1) rejects anything outside 0..19
    "faceIndex $faceIndex out of bounds …"
}
return when (faceIndex) {
    in 0..17 -> { … }                               // covers 0..17
    18, 19 -> quadBboxUvs(knot.paths[faceIndex])    // covers 18 and 19
    else -> throw IllegalArgumentException(          // unreachable
        "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
    )
}
```

**Issue:**
The `require` at line 371 guarantees `faceIndex in 0 until knot.paths.size`. `Knot` always has 20 paths (`createPaths` produces exactly 20 in all code paths — the three sub-prism blocks of 6 each plus the two custom quads). The `in 0..17` and `18, 19` arms are thus exhaustive over `0..19`. The `else` arm can never execute.

This is the same pattern used in `forStairsFace`, which has a similar `require` + exhaustive `when` + `else -> throw`. The difference is that `forStairsFace` has a runtime-variable face count (depends on `stepCount`) so the `else` there has semantic value (it would fire if `fromPathIndex` returned an unexpected face). For `forKnotFace`, the count is fixed at 20 in all cases.

**Impact:**
- Minor readability confusion: a reader unfamiliar with the implementation might wonder what value the `else` branch is guarding against, and spend time tracing whether `knot.paths.size` can ever differ from 20.
- Compiler/IDE may flag this as unreachable in future Kotlin versions (currently no warning, but the `when` subject is not sealed).

**Severity:** NIT
**Confidence:** High
**Category:** Complexity (unreachable code)

**Smallest fix:**
Remove the `else` arm and let the `when` compile without it (Kotlin allows a non-exhaustive `when` as a statement, but since this is a `when` expression returning `FloatArray`, the compiler does need exhaustiveness — use `else -> error("unreachable")` or restructure):

```kotlin
return when {
    faceIndex in 0..17 -> {
        val prismIndex = faceIndex / 6
        val localFaceIndex = faceIndex % 6
        forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
    }
    else -> quadBboxUvs(knot.paths[faceIndex])  // faceIndex is 18 or 19 after require()
}
```

Switching to a `when` with boolean guards (no `in` subject) makes the exhaustiveness obvious — the `else` is now structurally the 18/19 case, not a separate error branch. A single comment (`// faceIndex is 18 or 19 after require()`) documents the invariant.

**Benefit:**
- Removes the misleading implication that some `faceIndex` value could reach the `else throw` at runtime
- Reduces the function's apparent cyclomatic complexity by one path

---

## 6) Change Amplification Analysis

### Scenario 1: Add a fourth sub-prism to Knot

**Files that would need changes:**
1. `Knot.kt` — add new Prism to `sourcePrisms` AND to `createPaths` (MA-1 amplification: 2 edits for 1 logical change)
2. `UvGenerator.kt` — extend `in 0..17` to `in 0..23`, update `18, 19` arms to `24, 25`
3. `UvGeneratorKnotTest.kt` — update delegation tests, `forAllKnotFaces` size assertion, regression-guard test

If MA-1 is fixed (single source of truth in companion): step 1 becomes a single-site edit.

### Scenario 2: Change UV projection for custom quads (indices 18–19)

**Files that would need changes:**
1. `UvGenerator.kt` — replace or modify `quadBboxUvs`
2. `UvGeneratorKnotTest.kt` — update range assertions for quads 18 and 19

Assessment: minimal amplification — the projection helper is private and localized to one function and one test block.

### Scenario 3: Add `KnotFace` enum (future feature)

**Files that would need changes:**
1. `isometric-core` — new `KnotFace` enum alongside `CylinderFace`, `StairsFace`
2. `Knot.kt` — remove `@ExperimentalIsometricApi` from `sourcePrisms` if Knot is promoted
3. `UvGenerator.kt` — refactor `forKnotFace when` to dispatch on `KnotFace`
4. `UvCoordProviderForShape.kt` — unchanged (already wired)
5. Several integration points in `IsometricNode`, `resolveForFace`, `GpuTextureManager` (mirrors the pattern established by Cylinder/Stairs slices)

Assessment: well-understood pattern; amplification is inherent and managed by the existing shape-slice template.

**Overall Change Amplification:** Low-to-Moderate. The only above-baseline amplification is the MA-1 dual-site update for sub-prism constant changes.

---

## 7) Positive Observations

- **Pattern discipline:** `forKnotFace` / `forAllKnotFaces` follows the established sibling shape pattern exactly (`forPyramidFace`/`forAllPyramidFaces`, `forCylinderFace`/`forAllCylinderFaces`). New engineers reading the codebase will not need to learn a special case for Knot.
- **Comprehensive KDoc on `forKnotFace`:** The face-range breakdown, delegation rationale (pre-transform dimensions), `@param`, `@return`, and `@throws` are all present. This is the highest-quality KDoc in the UV generator family — other `forXFace` functions have shorter docs.
- **Regression-guard test design:** `sourcePrisms dimensions match createPaths constants` (`UvGeneratorKnotTest.kt:107`) pins exact numeric values including position, width, depth, and height with `absoluteTolerance = 1e-9`. This is the right failure mode — CI catches drift immediately with a meaningful test name.
- **`PerFaceSharedApiTest` pivot:** Switching from "Knot is in the null list" to "CustomShape subclass returns null" keeps the contract test meaningful after graduation without needing a new test file. The `CustomShape` fixture using a degenerate-but-valid path is clean.
- **`quadBboxUvs` zero-span guard:** The `if (spanX > 0.0) … else 0.0` pattern for degenerate spans is correct and consistent across all three axis branches. No division-by-zero risk.
- **`sourcePrisms` KDoc on the instance val clearly references the regression guard:** "a regression guard pins this in `UvGeneratorKnotTest`" gives future editors a direct pointer to where the sync contract is enforced.

---

## 8) Recommendations

### Should Fix (MED)

1. **MA-1**: Eliminate the `sourcePrisms` / `createPaths` duplication by moving Prism definitions to a companion `val` and deriving paths from it.
   - Action: Move the three `Prism(…)` literals to `companion object { val SOURCE_PRISMS = listOf(…) }` (or a private equivalent); change `createPaths` to iterate `SOURCE_PRISMS.forEach { addAll(it.paths) }`; change `sourcePrisms` to delegate to the companion val.
   - Rationale: Turns a documented-and-tested invariant into a structural one. Eliminates both editorial comments about "update both sites". Simplifies or eliminates the regression-guard test.
   - Estimated effort: 10 minutes. Zero observable behavior change.

### Consider (LOW)

2. **MA-2**: Standardize `@OptIn` granularity — either promote `UvGenerator.kt` to file-level `@OptIn` (matching `UvCoordProviderForShape.kt`) or document the split explicitly with a comment in each file.
   - Remove the redundant `@OptIn` from `forAllKnotFaces` regardless of which convention is chosen.
   - Estimated effort: 5 minutes.

3. **MA-3**: Rename `quadBboxUvs` to `aabbPlanarProjectUvs` (or promote its block comment to KDoc so IDEs surface it at call sites).
   - Estimated effort: 5 minutes including updating the three references.

### Optional (NIT)

4. **MA-4**: Remove the dead `else -> throw` in `forKnotFace` by switching to `when { … else -> quadBboxUvs(…) }` form.
   - Estimated effort: 2 minutes.

### Overall Strategy

**If time is limited:** Address MA-1 only — it is the only finding with future-edit amplification above baseline.

**If time allows:** MA-1 + MA-2 + MA-3 in a single follow-up commit. Total effort ~20 minutes.

---

## 9) Refactor Cost/Benefit

| Finding | Cost | Benefit | Risk | Recommendation |
|---------|------|---------|------|----------------|
| MA-1 | Low (10 min) | Medium (eliminates dual-site invariant) | Low (binary-compatible, regression test validates) | Do in follow-up |
| MA-2 | Trivial (5 min) | Low (convention consistency) | None | Do in follow-up |
| MA-3 | Trivial (5 min) | Low (discoverability) | None | Consider |
| MA-4 | Trivial (2 min) | Low (readability) | None | Optional |

Total effort for MED + LOW fixes: ~20 minutes
Total benefit: Eliminates structural dual-site invariant, establishes consistent `@OptIn` convention for future shape slices

---

## 10) Conventions & Consistency

### `@OptIn` Granularity (new concern introduced by this slice)

| File | Granularity | Pattern |
|------|-------------|---------|
| `UvCoordProviderForShape.kt` | File-level `@OptIn` | Added by this slice |
| `UvGenerator.kt` | Function-level `@OptIn` | Added by this slice |
| Test files | `@file:OptIn` | Consistent across all test files |

Recommendation: standardize production sources to file-level `@OptIn` when the entire file's purpose is tied to an experimental API (as is the case for both `UvGenerator.kt` and `UvCoordProviderForShape.kt` at this point — both now have at least one non-experimental shape plus Knot, so function-level is defensible, but file-level removes future annotation fatigue).

### `forXFace` Pattern Consistency

| Shape | `forXFace` KDoc depth | `forAllXFaces` KDoc depth |
|-------|----------------------|--------------------------|
| Prism | Full (`@param`, `@return`, `@throws`) | One-liner |
| Octahedron | Full | One-liner |
| Pyramid | Full (with mutation contract section) | One-liner |
| Cylinder | Full (with seam and cap convention) | One-liner |
| Stairs | Full | One-liner |
| **Knot** | **Full (with delegation rationale)** | **One-liner** |

Knot matches or exceeds the sibling standard. No deficiency.

---

## 11) False Positives and Disagreements Welcome

- **MA-1 (duplication):** If the team prefers to keep `sourcePrisms` as an instance-level property for future flexibility (e.g., if a `Knot` variant with different proportions is planned), the duplication is a reasonable trade-off. The regression guard is adequate for a stable shape. In that case the companion comment and `sourcePrisms` KDoc should each be updated to say "three proportions are fixed; see `UvGeneratorKnotTest` for the regression guard."
- **MA-3 (naming):** `quadBboxUvs` is a common shorthand in game-engine codebases. If the team convention is to use abbreviated names in private methods, this is not a real finding. The block comment above the function is genuinely informative.
- **MA-4 (dead branch):** Some teams intentionally leave `else -> throw` in `when` expressions as a defensive guard against future compiler relaxation of exhaustiveness rules, or as documentation that "we thought about this." If that is the team convention, the NIT is not actionable.

---

*Review completed: 2026-04-20*
*Workflow: texture-material-shaders / uv-generation-knot*
