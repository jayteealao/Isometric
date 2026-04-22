---
slice-slug: uv-generation-knot
review-command: architecture
schema: sdlc/v1
type: review
slug: texture-material-shaders
commit: e5cf72a
reviewer: Claude Architecture Review Agent
completed: "2026-04-20"
tags: [architecture]
severity-breakdown:
  BLOCKER: 0
  HIGH: 1
  MED: 3
  LOW: 2
  NIT: 1
---

# Architecture Review: uv-generation-knot

**Scope:** `git diff HEAD~1 HEAD` — commit `e5cf72a`, slice `uv-generation-knot`
**Reviewer:** Claude Architecture Review Agent
**Date:** 2026-04-20

## Summary

The uv-generation-knot implementation is architecturally sound within the established
cross-shape dispatch pattern. No circular dependencies, no layer violations, no boundary
breaches. The implementation follows the exact "add one arm per dispatch site" convention
set by cylinder / pyramid / stairs / octahedron.

This commit resolves the final built-in shape — `Knot` is now the last entry in the
`uvCoordProviderForShape` `when` chain before `else -> null`. That terminal-state fact
raises four architectural questions with different urgency levels:

1. **Constants duplicated, guarded by test** — acceptable short-term, carries a real
   drift risk that the regression guard alone cannot fully mitigate. LOW/MED.
2. **Bag-of-primitives delegation** — correctly implemented; the architecture is missing
   a documented convention that would guide future compound shapes. LOW.
3. **`when` extension terminal state** — the factory is now closed for all built-in
   shapes; the `else -> null` arm exists only for user-defined `Shape` subclasses. This
   is the right architecture at this scale. The inversion threshold (visitor/registry)
   is not yet reached, but the stale doc language ("extension points") must be updated.
   MED.
4. **KDoc shift from "extension points" to "terminal state"** — premature closure
   framing is unnecessary; the doc should reflect correctness, not anxiety. NIT.
5. **`@OptIn` propagation strategy** — the two-annotation pattern used in `UvGenerator`
   (`@OptIn` + `@ExperimentalIsometricApi`) is correct and consistent. The single
   `@OptIn` on `uvCoordProviderForShape` (internal function, no public signature impact)
   is also correct. No gaps. LOW.

The HIGH finding from the stairs review (A-01: 4 dispatch sites touched per shape) still
applies and is now at its final scale: 6 shapes × 4 sites = 24 arms. However, this
commit correctly completes only DISPATCH SITE 2 (`uvCoordProviderForShape`). The absence
of a Knot arm at DISPATCH SITE 1 (`IsometricNode`) and DISPATCH SITE 4
(`GpuTextureManager`) is intentional and documented — Knot has no named face taxonomy
and `perFace {}` is explicitly unsupported. This reduces the HIGH severity: the
architectural warning still stands for future shapes, but Knot does not worsen the
completeness problem.

**Architectural Style:** Layered (isometric-core → isometric-compose → isometric-shader
→ isometric-webgpu), with functional dispatch via closed `when` chains.

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 1
- MED: 3
- LOW: 2
- NIT: 1

**Key Metrics:**
- Circular dependencies detected: 0
- God objects (>5 responsibilities): 0
- High coupling modules (fan-out >10): 0
- Layer violations: 0
- Dispatch sites touched by this slice: 1 (SITE 2 only — correct for Knot)

---

## Architectural Map

```
isometric-core
  Shape (open class)
    Prism, Pyramid, Cylinder, Stairs, Knot, Octahedron   ← all built-ins
  FaceIdentifier (sealed interface)
    PrismFace, PyramidFace, CylinderFace, StairsFace, OctahedronFace
    (no KnotFace — intentional; Knot has no named face taxonomy)
  Knot.sourcePrisms: List<Prism>   ← new in this commit; pre-transform sub-Prism refs
  Knot.createPaths()               ← companion private; constants duplicated in sourcePrisms

isometric-compose
  ShapeNode.renderTo()
    → when (shape) { is Prism/Octahedron/Pyramid/Cylinder/Stairs -> XFace.fromPathIndex }
      (no Knot arm — faceType = null for all Knot commands; correct)      DISPATCH SITE 1

isometric-shader
  uvCoordProviderForShape(shape)
    → when (shape) { is Prism/Octahedron/Pyramid/Cylinder/Stairs/Knot -> UvCoordProvider }
      else -> null  ← now exclusively for user-defined Shape subclasses   DISPATCH SITE 2 (TERMINAL)
  UvGenerator (internal object)
    forPrismFace / forOctahedronFace / forPyramidFace / forCylinderFace
    forStairsFace / forKnotFace    ← new; delegates to forPrismFace + quadBboxUvs
    quadBboxUvs (private helper)   ← new; axis-aligned bbox projection for custom quads
  IsometricMaterial.PerFace (sealed; 5 variants: Prism/Octahedron/Pyramid/Cylinder/Stairs)
    (no PerFace.Knot — intentional)

isometric-webgpu
  GpuTextureManager.collectTextureSources()
    → when (m) { is PerFace.Prism/Octahedron/Pyramid/Cylinder/Stairs -> collect slots }
      (no Knot arm — PerFace.Knot does not exist; else -> {} handles it)  DISPATCH SITE 4

Dependency direction (verified):
  isometric-core ← isometric-compose ← isometric-shader ← isometric-webgpu
  No reverse edges introduced by this commit.
```

---

## Findings

### A-KNOT-01: `createPaths()` constants and `sourcePrisms` are parallel truths; regression guard does not prevent runtime divergence in the same commit [HIGH]

**Location:**
- `isometric-core/src/main/kotlin/.../shapes/Knot.kt:35–39` (`sourcePrisms`)
- `isometric-core/src/main/kotlin/.../shapes/Knot.kt:51–53` (`createPaths` constants)
- `isometric-shader/src/test/kotlin/.../shader/UvGeneratorKnotTest.kt:114–125` (guard)

**Category:** Data duplication / single-source-of-truth violation

**Severity:** HIGH | **Confidence:** High

**Issue:**

`sourcePrisms` and `createPaths` contain identical Prism dimension constants:

```kotlin
// Knot.kt:35–39 — sourcePrisms
val sourcePrisms: List<Prism> = listOf(
    Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
    Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
    Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
)

// Knot.kt:51–53 — createPaths (same numbers)
allPaths.addAll(Prism(Point.ORIGIN, 5.0, 1.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0).paths)
allPaths.addAll(Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0).paths)
```

The KDoc on `createPaths` says: "If you change the Prism constants below, update
`sourcePrisms` above to match." The regression guard in `UvGeneratorKnotTest` pins
the values. However, the guard lives in the **shader module's test sources**, not in
core. A change to `Knot.kt` constants that passes `isometric-core` tests in isolation
would not trigger the shader guard unless both modules are tested together. In a CI
split-module build or during a partial local build, drift is detectable only at full
integration.

The underlying fix is straightforward: `createPaths` should build paths from the
`sourcePrisms` val, eliminating the duplication entirely.

**Proposed fix:**

```kotlin
@ExperimentalIsometricApi
class Knot(val position: Point = Point.ORIGIN) : Shape(createPaths(position)) {

    @ExperimentalIsometricApi
    val sourcePrisms: List<Prism> = listOf(
        Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
        Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
        Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
    )

    companion object {
        private fun createPaths(position: Point): List<Path> {
            // Use a temporary list of canonical prisms so constants live in one place.
            // The actual Knot instance's sourcePrisms carries the same objects at runtime.
            val canonicalPrisms = listOf(
                Prism(Point.ORIGIN, 5.0, 1.0, 1.0),
                Prism(Point(4.0, 1.0, 0.0), 1.0, 4.0, 1.0),
                Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0),
            )
            val allPaths = mutableListOf<Path>()
            for (p in canonicalPrisms) allPaths.addAll(p.paths)
            // ... custom quads + scale/translate
        }
    }
}
```

The cleanest resolution is to make `sourcePrisms` a `companion object val` (or a
file-level constant list) and reference it from both `createPaths` and the instance
`val`. However, `sourcePrisms` is an instance `val` carrying `@ExperimentalIsometricApi`
and is part of the public API; making it a companion `val` would shift the annotation
placement. The safest single change: extract a private `CANONICAL_PRISMS` list in the
companion, reference it from both `createPaths` and `sourcePrisms`.

**Impact:**
- Affects only `Knot.kt`. No API surface change.
- The regression guard test would become redundant but harmless (it could remain as a
  documentation-by-test of the expected values).
- Eliminates the cross-module guard dependency.

**Refactoring steps:**
1. Add `private val CANONICAL_PRISMS = listOf(...)` to `Knot.Companion`.
2. Change `sourcePrisms` initializer to reference `CANONICAL_PRISMS`.
3. Change `createPaths` Prism constructions to iterate `CANONICAL_PRISMS`.
4. Update the KDoc comment on `createPaths` (no longer needs the "update sourcePrisms"
   warning).
5. Remove or relax the constants check in `UvGeneratorKnotTest` (it becomes a tautology).

---

### A-KNOT-02: `uvCoordProviderForShape` `when` is now terminal for all built-ins; the KDoc still uses "extension points" language that no longer applies [MED]

**Location:**
- `isometric-shader/src/main/kotlin/.../shader/UvCoordProviderForShape.kt:14–29` (KDoc)

**Category:** Documentation / architectural clarity

**Severity:** MED | **Confidence:** High

**Issue:**

The file-level KDoc reads:

```kotlin
/**
 * Returns a [UvCoordProvider] that generates per-face UVs for [shape], or `null` if
 * per-face texturing is not yet supported for this shape type.
 * ...
 * All stock shapes (`Prism`, `Octahedron`, `Pyramid`, `Cylinder`, `Stairs`, `Knot`)
 * now return a non-null provider. Shapes outside this list — including any
 * user-defined subclasses of [Shape] — return `null` and texturing is a no-op
 * for them at the renderer level.
 */
```

The phrase "not yet supported for this shape type" (line 16) is now semantically wrong
for any stock shape — all stock shapes are supported. For user-defined subclasses, "not
yet supported" implies future work that will never happen through this mechanism.

Additionally, the existing `07-review-uv-generation-stairs-architecture.md` (A-01)
raised the question of whether this `when` needs to be inverted into a visitor or
registry pattern. As of this commit the answer is: **no**. The `when` is closed over the
library's own shapes; user-defined shapes always fall through to `null`. A registry
would only be needed if the library offered a public registration API for third-party UV
providers — and this design deliberately does not.

The architectural decision should be stated explicitly in the KDoc so future maintainers
do not re-examine the same question.

**Proposed KDoc update:**

```kotlin
/**
 * Returns a [UvCoordProvider] that generates per-face UVs for [shape], or `null` if
 * the shape type is not a stock library shape.
 *
 * ## Contract
 *
 * - Returning `null` means the renderer falls back to flat-color rendering even if the
 *   material is [IsometricMaterial.Textured] or [IsometricMaterial.PerFace].
 * - Returning non-null commits to producing a [FloatArray] of `2 * faceVertexCount`
 *   floats per face in `[u0,v0, u1,v1, ...]` order matching the shape's path
 *   vertex order.
 *
 * ## Closed dispatch
 *
 * All stock shapes (`Prism`, `Octahedron`, `Pyramid`, `Cylinder`, `Stairs`, `Knot`)
 * return a non-null provider. The `else -> null` arm handles user-defined [Shape]
 * subclasses — no registration mechanism exists for third-party shapes. This is
 * intentional: the UV generation contract depends on shape-internal geometry that
 * cannot be generalized at the library boundary.
 */
```

**Impact:** Documentation only. No code change. Prevents future maintainers from
re-opening the visitor/registry question unnecessarily.

---

### A-KNOT-03: Bag-of-primitives delegation is correctly implemented but lacks a documented architectural convention for future compound shapes [MED]

**Location:**
- `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:345–394` (`forKnotFace` KDoc + implementation)

**Category:** Missing architectural convention / undocumented pattern

**Severity:** MED | **Confidence:** High

**Issue:**

`forKnotFace` correctly implements the "bag-of-primitives" delegation pattern:
sub-prism face blocks delegate to `forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)`,
and custom quads fall back to `quadBboxUvs`. The KDoc describes the decomposition
accurately for this specific shape. However, the pattern is not documented as a
**convention** — if a future compound shape (e.g. a merged or interlocked geometry) is
added to the library, the author has no canonical reference for how to approach UV
generation.

The pattern generalizes as:

> For a composite shape built by concatenating `n` simpler shapes' path lists, expose
> the source shapes as a `sourceParts: List<SimpleShape>` val. In `UvGenerator`, map
> `faceIndex` to `(partIndex, localFaceIndex)` and delegate to the appropriate
> `forSimpleShapeFace` method. Irregular faces (not derived from a simpler shape) use
> the `quadBboxUvs` fallback or an equivalent per-face projection.

This convention is currently implicit, known only to maintainers who read the Knot
implementation. It should be documented once — either in a brief architecture ADR or in
the UvGenerator's class-level KDoc.

**Proposed addition to `UvGenerator` class KDoc:**

```kotlin
/**
 * ...existing KDoc...
 *
 * ## Compound-shape convention
 *
 * For shapes composed of simpler shapes (e.g. a union of Prisms), expose the source
 * shapes as a `sourceParts: List<T>` val on the shape class, annotated with the same
 * `@ExperimentalIsometricApi` opt-in as the shape itself. In `forXFace`, map
 * `faceIndex` to `(partIndex, localFaceIndex)` and delegate to `forTFace`. Faces not
 * derived from a simpler shape should use `quadBboxUvs` (for 4-vertex quads) or an
 * equivalent bounding-box projection. See [forKnotFace] as the canonical example.
 */
```

**Impact:** Documentation only. Prevents ad-hoc divergence in future compound shape
slices.

---

### A-KNOT-04: `UvGenerator.forKnotFace` `when` hard-codes face count as `else -> throw` without deriving the constraint from shape API [MED]

**Location:**
- `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:374–385`

**Category:** Fragile constant / invariant not enforced by type system

**Severity:** MED | **Confidence:** Med

**Issue:**

```kotlin
return when (faceIndex) {
    in 0..17 -> { ... }
    18, 19 -> quadBboxUvs(knot.paths[faceIndex])
    else -> throw IllegalArgumentException(
        "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
    )
}
```

The constant `17`, `18`, `19` in the `when` branch hard-code the Knot face count.
The `require` guard above already validates `faceIndex in knot.paths.indices`, making
the `else -> throw` branch unreachable in practice (a valid `faceIndex` in `0..19`
always hits one of the first two arms; an invalid one is caught by the `require`).

The unreachable `else` branch is not a logic error — it is a defense-in-depth guard.
However, the three magic numbers (`17`, `18`, `19`) are disconnected from `sourcePrisms.size`
(which is 3, giving 3 × 6 = 18 sub-prism faces) and from `knot.paths.size` (20). If
`sourcePrisms` ever gains a fourth entry, the `in 0..17` bound would be stale.

The fix is to derive the boundary dynamically:

```kotlin
val subPrismFaceCount = knot.sourcePrisms.size * 6  // 3 * 6 = 18
return when {
    faceIndex < subPrismFaceCount -> {
        val prismIndex = faceIndex / 6
        val localFaceIndex = faceIndex % 6
        forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
    }
    faceIndex < knot.paths.size -> quadBboxUvs(knot.paths[faceIndex])
    else -> throw IllegalArgumentException(
        "Knot has exactly ${knot.paths.size} faces (indices 0..${knot.paths.size - 1}); got $faceIndex"
    )
}
```

This couples the boundary to `sourcePrisms.size` and `knot.paths.size` directly,
ensuring that adding a fourth sub-prism (with corresponding paths) automatically
extends the delegation range.

**Note:** Given that Knot is marked `@ExperimentalIsometricApi` and its geometry is
described as having a known depth-sort bug, geometry evolution is plausible. Keeping
the boundary derived is a pragmatic safeguard.

**Impact:** Only `UvGenerator.kt`. No API surface change. The regression guard test in
`UvGeneratorKnotTest` would still pass (the logic is equivalent for the current 3-prism
geometry).

---

### A-KNOT-05: `@OptIn` propagation is correct but the two-annotation pattern on `UvGenerator` members is not documented as the standard [LOW]

**Location:**
- `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:368–369, 391–392`

**Category:** Undocumented convention / @OptIn propagation

**Severity:** LOW | **Confidence:** High

**Issue:**

`forKnotFace` and `forAllKnotFaces` use the two-annotation pattern:

```kotlin
@OptIn(ExperimentalIsometricApi::class)   // silences the usage of Knot inside the body
@ExperimentalIsometricApi                  // propagates the requirement to callers
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray
```

This is the correct Kotlin pattern. `@OptIn` suppresses the warning for the function
body (which references `Knot` and `knot.sourcePrisms`, both experimental); the function
itself is annotated `@ExperimentalIsometricApi` so callers must opt in. The single
`@OptIn(ExperimentalIsometricApi::class)` on `uvCoordProviderForShape` (an `internal`
function — not public API) is also correct: the opt-in is local to that file and does
not propagate to consumers.

The concern is the absence of any documented guidance. A maintainer adding a new
experimental shape to `UvGenerator` might apply only one annotation (creating a
warning-free body without propagating the requirement to callers, or vice versa). This
is more a documentation gap than an active bug.

**Proposed action:** Add a one-line comment above the first occurrence in `UvGenerator`:

```kotlin
// Two-annotation pattern: @OptIn suppresses usage warnings in the body;
// @ExperimentalIsometricApi propagates the opt-in requirement to callers.
@OptIn(ExperimentalIsometricApi::class)
@ExperimentalIsometricApi
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
```

---

### A-KNOT-06: Cross-module dispatch completeness has no compiler enforcement; the "intentional absence" of a Knot arm in `IsometricNode` is invisible to the type system [LOW]

**Location:**
- `isometric-compose/src/main/kotlin/.../runtime/IsometricNode.kt:303–309` (DISPATCH SITE 1)

**Category:** Latent maintenance risk / absence of dispatch arm

**Severity:** LOW | **Confidence:** High

**Issue:**

Per the plan, Knot intentionally has no arm at DISPATCH SITE 1 (`IsometricNode.faceType`
assignment) because Knot has no named face taxonomy. This is the correct decision. The
problem is that this intention is not expressed in code — the absence of `is Knot`
in the `when` is indistinguishable from an accidental omission.

When a future maintainer audits the dispatch sites (as raised in A-01 of the stairs
review), they will see:

```kotlin
faceType = when (shape) {
    is Prism -> PrismFace.fromPathIndex(index)
    is Octahedron -> OctahedronFace.fromPathIndex(index)
    is Pyramid -> PyramidFace.fromPathIndex(index)
    is Cylinder -> CylinderFace.fromPathIndex(index)
    is Stairs -> StairsFace.fromPathIndex(index, (shape as Stairs).stepCount)
    else -> null
},
```

`Knot` falls through to `else -> null`, which happens to be correct, but there is no
comment explaining why. The same `else -> null` handles user-defined shapes — both
user shapes and Knot are intentionally null, but for different reasons.

**Proposed fix:** Add an inline comment:

```kotlin
faceType = when (shape) {
    is Prism -> PrismFace.fromPathIndex(index)
    is Octahedron -> OctahedronFace.fromPathIndex(index)
    is Pyramid -> PyramidFace.fromPathIndex(index)
    is Cylinder -> CylinderFace.fromPathIndex(index)
    is Stairs -> StairsFace.fromPathIndex(index, (shape as Stairs).stepCount)
    // Knot intentionally absent: no named face taxonomy; perFace {} is unsupported.
    // User-defined Shape subclasses also fall through here.
    else -> null
},
```

**Impact:** `IsometricNode.kt` comment only. No behavior change.

---

### A-KNOT-07: `else -> throw` in `forKnotFace` is dead code under the `require` guard [NIT]

**Location:**
- `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt:381–384`

**Category:** Dead code / redundant branch

**Severity:** NIT | **Confidence:** High

**Issue:**

The `require(faceIndex in knot.paths.indices)` on line 371 guarantees that `faceIndex`
is in `0..19` before the `when` is reached. The `when` covers `0..17`, `18`, and `19`
exhaustively, making the `else -> throw` branch unreachable. This is acceptable as a
defense-in-depth guard, but it creates a coverage gap — the branch can never be hit by
any test. The comment in `quadBboxUvs` acknowledges the non-canonical winding; a similar
acknowledgment of the unreachable `else` would improve readability.

If finding A-KNOT-04 is accepted (deriving boundary from `sourcePrisms.size`), the
`else -> throw` becomes reachable in the rare scenario where paths and sourcePrisms
diverge — which is exactly the regression case the guard should catch. Accept A-KNOT-04
and A-KNOT-07 together.

---

## Consolidated Decisions Required

### Decision 1: Accept or defer A-KNOT-01 (single-source constants)

**Recommendation:** Accept. This is a one-function refactor in `Knot.kt` that eliminates
the dual maintenance burden. The regression guard test in `UvGeneratorKnotTest` can be
kept as-is or relaxed. Cost: ~15 lines of Kotlin. Risk: low (no public API change).

### Decision 2: Accept or defer A-KNOT-02 (KDoc terminal state)

**Recommendation:** Accept. Documentation-only change. Resolves the open question from
the stairs architecture review (A-01) about when to invert to a visitor/registry pattern.
The answer is now clear: never for the current design. Document that decision.

### Decision 3: Accept or defer A-KNOT-03 (bag-of-primitives convention)

**Recommendation:** Accept. One-paragraph KDoc addition to `UvGenerator`. Prevents
pattern drift in future compound shape slices.

### Decision 4: Accept or defer A-KNOT-04 (dynamic boundary derivation)

**Recommendation:** Accept. Low-risk, makes the face-count contract self-enforcing.
Especially valuable given Knot's experimental status and the acknowledged depth-sort
instability.

---

## Dependency Graph

```
Knot.kt (isometric-core)
  → Prism (isometric-core) ← via sourcePrisms and createPaths

UvGenerator.kt (isometric-shader)
  → Knot (isometric-core)
  → Prism (isometric-core)  ← via forPrismFace delegation
  → Path (isometric-core)   ← via quadBboxUvs

UvCoordProviderForShape.kt (isometric-shader)
  → UvGenerator (isometric-shader)
  → Knot, Prism, Octahedron, Pyramid, Cylinder, Stairs (isometric-core)
  → UvCoordProvider (isometric-compose)

IsometricNode.kt (isometric-compose)
  → Shape, Knot (isometric-core) — Knot absent from when; falls to else -> null

GpuTextureManager.kt (isometric-webgpu)
  → IsometricMaterial.PerFace variants (isometric-shader)
  — No PerFace.Knot variant; Knot perFace falls to else -> {} (correct)
```

No circular edges. Dependency direction is uniformly downstream.

---

## Metrics

| Metric | Value | Threshold | Status |
|--------|-------|-----------|--------|
| Circular dependencies | 0 | 0 | PASS |
| God objects (>5 responsibilities) | 0 | 0 | PASS |
| Layer violations | 0 | 0 | PASS |
| High coupling modules (fan-out >10) | 0 | <10 | PASS |
| Max file size (UvGenerator.kt) | 479 lines | <1000 | PASS |
| Parallel constant lists (sourcePrisms / createPaths) | 1 | 0 | WARN |
| Dispatch sites with Knot arm | 1/4 | intentional | NOTE |

*Review completed: 2026-04-20*
