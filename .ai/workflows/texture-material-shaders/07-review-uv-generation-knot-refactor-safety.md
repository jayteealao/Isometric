---
slice-slug: uv-generation-knot
review-command: refactor-safety
slug: texture-material-shaders
commit: e5cf72a
date: 2026-04-20
scope: diff
target: "git diff HEAD~1 HEAD"
focus-paths:
  - isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/PerFaceSharedApiTest.kt
  - isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoordProviderForShape.kt
tags: [refactor-safety]
related:
  slice: 03-slice-uv-generation-knot.md
  plan: 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-knot.md
---

# Review: refactor-safety — uv-generation-knot

**Slice:** uv-generation-knot  
**Commit:** e5cf72a  
**Date:** 2026-04-20  
**Reviewer:** Claude Code

---

## 0) Refactor Scope & Equivalence Constraints

**What was refactored / promoted:**

Two changes in this commit are refactors; all other changes are net-new feature code:

1. **`PerFaceSharedApiTest.kt` — test pivot** (`+16 / -4`): the
   `uvCoordProviderForShape returns null for shapes without per-face UV support` test had
   its body replaced. The old body called `assertNull(uvCoordProviderForShape(Knot()))`.
   The new body defines a local `CustomShape : Shape(listOf(Path(Point.ORIGIN, Point.ORIGIN,
   Point.ORIGIN)))` and calls `assertNull(uvCoordProviderForShape(CustomShape()))`.
   A new companion test `uvCoordProviderForShape returns non-null provider for Knot` was
   added in the same region.

2. **`UvCoordProviderForShape.kt` — KDoc rewrite** (`+8 / -7`): the `## Extension` section
   that listed `uv-generation-knot → is Knot` as a pending hook was removed and replaced with
   a prose paragraph describing the current terminal state of the `when` dispatch.

**Equivalence constraints:**

| Contract | Constraint |
|---|---|
| Input/Output | `uvCoordProviderForShape(anyStockShape)` must return non-null; `uvCoordProviderForShape(unknownShapeSubclass)` must return null |
| Side Effects | None — pure function; no side effects to preserve |
| Error | No exceptions expected or changed |
| Performance | O(1) `when` dispatch unchanged |
| API | `uvCoordProviderForShape` is `internal`; no public surface change |

**Allowed changes (documented in plan/implement docs):**

- `uvCoordProviderForShape(Knot())` changing from `null` → non-null is the explicit goal of
  the slice. This is not accidental drift; it is the feature.
- The `## Extension` KDoc section was explicitly described as a forward-looking placeholder.
  Its removal is the natural completion step once all planned shapes landed.
- The null-for-shapes test switching mechanism from "last un-landed shape" to "user-defined
  subclass" is explicitly designed and documented in `05-implement-uv-generation-knot.md`.

---

## 1) Executive Summary

**Safety Assessment:** SAFE

The two refactors preserve all relevant behavioral contracts. The test pivot maintains
coverage of the `else -> null` code path via the `CustomShape` fixture. The KDoc rewrite
deletes a forward-looking comment section that is now factually obsolete; no previously
documented behavioral invariant is omitted. No test name was changed. No call site outside
the test suite assumed `uvCoordProviderForShape(Knot()) == null`.

**Critical Drift (BLOCKER/HIGH):**  
None found.

**Overall Assessment:**

- Behavior Equivalence: Preserved (with intentional, documented inversion for Knot)
- Public API Safety: Safe (function is `internal`; no public surface changed)
- Side Effect Safety: N/A (pure function)
- Error Handling Safety: Preserved
- Performance Safety: Preserved

---

## 2) Findings Table

| ID | Severity | Confidence | Category | File:Line | Semantic Drift |
|----|----------|------------|----------|-----------|----------------|
| RS-1 | NIT | Med | Test Coverage | `PerFaceSharedApiTest.kt:313` | CustomShape fixture is degenerate — `quadBboxUvs` never invoked via `else` path, but this is not the code path being tested |
| RS-2 | NIT | High | KDoc | `UvCoordProviderForShape.kt:29` | Stale comment in `IsometricMaterialComposables.kt` still refers to "shapes without per-face UV support (currently everything except Prism)" — not updated |

**Findings Summary:**

- BLOCKER: 0
- HIGH: 0
- MED: 0
- LOW: 0
- NIT: 2

---

## 3) Findings (Detailed)

### RS-1: CustomShape Fixture — Degenerate Path Does Not Exercise `quadBboxUvs` [NIT]

**Location:** `isometric-shader/src/test/kotlin/.../PerFaceSharedApiTest.kt:313`

**Category:** Test Coverage

**Context:**

The review question is whether `CustomShape : Shape(listOf(Path(Point.ORIGIN, Point.ORIGIN,
Point.ORIGIN)))` genuinely covers the same code path as the original `assertNull(uvCoordProviderForShape(Knot()))`.

**Before:**
```kotlin
// PerFaceSharedApiTest.kt (HEAD~1), lines ~296-303
@OptIn(ExperimentalIsometricApi::class)
@Test
fun `uvCoordProviderForShape returns null for shapes without per-face UV support`() {
    // Shapes not yet wired by their uv-generation-<shape> slice still return null.
    // Octahedron, Pyramid, Cylinder, and Stairs dropped from this list as their
    // shape slices landed.
    assertNull(uvCoordProviderForShape(Knot()))
}
```

**After:**
```kotlin
// PerFaceSharedApiTest.kt (HEAD), lines ~308-315
@Test
fun `uvCoordProviderForShape returns null for shapes without per-face UV support`() {
    // All stock shapes (Prism, Octahedron, Pyramid, Cylinder, Stairs, Knot) now
    // dispatch to a dedicated UV generator. Shapes outside the stock set — including
    // user-defined Shape subclasses — fall through the when to `else -> null`, which
    // signals the renderer to skip UV computation for them.
    class CustomShape : Shape(listOf(Path(Point.ORIGIN, Point.ORIGIN, Point.ORIGIN)))
    assertNull(uvCoordProviderForShape(CustomShape()))
}
```

**Equivalence analysis:**

The old assertion tested a specific known-shape (`Knot`) that was not yet in the `when`
dispatch. Its purpose was to document that `else -> null` was reachable. The new assertion
tests a locally-defined class that cannot match any `is <StockShape>` arm — it correctly
reaches `else -> null` by the same structural mechanism (type mismatch on every arm).

The code path exercised is identical:

```kotlin
internal fun uvCoordProviderForShape(shape: Shape): UvCoordProvider? = when (shape) {
    is Prism     -> ...    // not matched
    is Octahedron -> ...   // not matched
    is Pyramid   -> ...    // not matched
    is Cylinder  -> ...    // not matched
    is Stairs    -> ...    // not matched
    is Knot      -> ...    // not matched
    else         -> null   // ← CustomShape reaches this
}
```

**Degenerate path concern:**

The question asks whether using `Path(Point.ORIGIN, Point.ORIGIN, Point.ORIGIN)` — three
coincident zero points — could mask a real bug. The answer is no for this specific test,
because `uvCoordProviderForShape` never calls `quadBboxUvs` or any UV math on its input —
it only checks the runtime type of `shape` via the `when` expression. The degenerate geometry
is never traversed. The `CustomShape()` is constructed (satisfying `Shape.init`'s non-empty
requirement and `Path.init`'s ≥3-points requirement), then passed to the `when` dispatch
which immediately falls through to `else -> null`.

The degenerate geometry would matter if the test also invoked `provider.provide(...)`. It
does not — the assertion stops at `assertNull(uvCoordProviderForShape(...))`. There is no
provider to invoke.

**Residual gap:** The degenerate fixture does not test that the `else` branch also produces a
working `null` return in a context where `provider?.provide(shape, faceIndex)` is called — but
that is not a regression from the previous design (the old Knot-based test also didn't call
`provide`, it just asserted `null`). This is a pre-existing coverage gap, not newly introduced.

**Verdict:** Not semantic drift. The fixture change is appropriate, safe, and covers the
correct code path.

**Severity:** NIT  
**Confidence:** High

---

### RS-2: Stale Comment in `IsometricMaterialComposables.kt` Not Updated [NIT]

**Location:** `isometric-shader/src/main/kotlin/.../shader/IsometricMaterialComposables.kt:57-59`

**Category:** KDoc / Comment drift

**Before (unchanged):**
```kotlin
// UV provider: dispatched by shape type through uvCoordProviderForShape(). Each
// shape slice adds its own branch there; shapes without per-face UV support
// (currently everything except Prism) return null and fall back to flat color.
```

**After (unchanged in this commit — stale):**

This comment was true many slices ago. With Knot now graduated, all stock shapes have
UV support. The parenthetical "(currently everything except Prism)" is now factually
incorrect — all six stock shapes have non-null providers.

**Semantic drift:** None. This is a comment inaccuracy, not a behavioral change. The
production logic at line 62 (`val uvProvider = if (needsUvs) uvCoordProviderForShape(geometry) else null`)
is correct. No caller is misled at runtime.

**Severity:** NIT  
**Confidence:** High

**Fix (optional):**
```diff
-// UV provider: dispatched by shape type through uvCoordProviderForShape(). Each
-// shape slice adds its own branch there; shapes without per-face UV support
-// (currently everything except Prism) return null and fall back to flat color.
+// UV provider: dispatched by shape type through uvCoordProviderForShape(). All
+// stock shapes (Prism, Octahedron, Pyramid, Cylinder, Stairs, Knot) return a
+// non-null provider; user-defined Shape subclasses return null and fall back to
+// flat color.
```

---

## 4) Specific Review Questions (from task scope)

### Q1: Does `CustomShape` genuinely cover the same code path as `Knot()`?

**Yes.** Both inputs reach `else -> null` in `uvCoordProviderForShape`'s `when` expression.
The mechanism is identical: neither `CustomShape` nor the pre-knot `Knot()` match any
named `is <StockShape>` arm. The degenerate geometry is irrelevant because no UV math is
invoked — the function exits at the type dispatch.

### Q2: Could a degenerate Path mask a real bug?

**No, for this test.** The `assertNull` check terminates before any UV computation. A
degenerate path would only matter if the test proceeded to call `provider.provide(shape, i)`,
which it does not. The concern would apply to a hypothetical future test that calls
`uvCoordProviderForShape(CustomShape())` and then asserts on computed UV values — but that
is not what this test does.

The `quadBboxUvs` helper handles zero-span paths safely (it collapses to `0` via the
`if (spanX > 0.0) ... else 0.0` guard). So even if a future extension passed a
degenerate path to `quadBboxUvs`, the output would be `[0,0,0,0,0,0,0,0]` rather than
a crash or NaN — a valid (if trivial) UV array.

### Q3: Does the KDoc rewrite preserve the contract but omit previously-documented invariants?

**No invariants were omitted.** The removed `## Extension` section documented a
**process/future-work obligation** ("each shape slice adds a `when` branch here"), not a
behavioral invariant of the function. The function's behavioral contract (the two `##
Contract` bullets about null semantics and the FloatArray size/order guarantee) is fully
preserved verbatim.

The replacement prose accurately describes the current terminal state:

> All stock shapes (`Prism`, `Octahedron`, `Pyramid`, `Cylinder`, `Stairs`, `Knot`)
> now return a non-null provider. Shapes outside this list — including any
> user-defined subclasses of [Shape] — return `null` and texturing is a no-op
> for them at the renderer level.

This is the correct documentation of the post-Knot state. The `null` fallback for unknown
shapes is explicitly stated, which is the key behavioral invariant for renderer consumers.

### Q4: Are there other call sites or tests that assumed `uvCoordProviderForShape(Knot()) == null`?

**No.** A full codebase search for `uvCoordProviderForShape.*Knot` returned only two hits —
both in test files (`UvGeneratorKnotTest.kt` and `PerFaceSharedApiTest.kt`) — and both were
written or updated in this commit to assert non-null. There are no production call sites
(the function is `internal`) and no other test files that tested the Knot null case. The
production caller (`IsometricMaterialComposables.kt:62`) never hardcoded a shape-type
assumption — it calls the factory and uses the result conditionally (`uvProvider?.`).

### Q5: Has the `## Extension` section's removal lost any extension-point documentation?

**No extension-point documentation is lost.** The `## Extension` section was an in-progress
work tracker ("these slices still need to add a branch here"), not a stable extension contract.
The information it contained is now superseded — there are no remaining pending branches.
Removing it is correct hygiene.

The extension pattern itself (adding an `is <Shape> -> ...` arm) is documented in the
sibling slice plans and in the implement documents. It does not need to live in the KDoc of
a stable, completed function.

### Q6: Did the refactor preserve existing test names?

**Yes.** The test `uvCoordProviderForShape returns null for shapes without per-face UV
support` retains its exact name. This is important because:

1. CI history for this test name continues unbroken — the old green result (Knot returned
   null) and the new green result (CustomShape returns null) both pass under the same test
   name, and both correctly represent the `else -> null` contract.
2. The new companion test `uvCoordProviderForShape returns non-null provider for Knot` is
   additive — it creates a new CI history entry and does not rename anything.

The `@OptIn(ExperimentalIsometricApi::class)` annotation that was previously on the
`returns null` test moved to the new `returns non-null provider for Knot` test, which is
the correct location (the Knot constructor requires opt-in). The `returns null` test with
the `CustomShape` fixture does not access any experimental API and correctly has no opt-in.

---

## 5) Equivalence Analysis

| Contract | Status | Notes |
|---|---|---|
| Input/Output | Preserved | `else -> null` path reachable and tested; Knot path now returns non-null (intentional) |
| Side Effects | N/A | Pure function |
| Error Handling | Preserved | No new exceptions; `require` in `forKnotFace` is net-new, not a refactor |
| Performance | Preserved | O(1) `when` dispatch; one extra arm added |
| API Contract | Preserved | `uvCoordProviderForShape` is `internal`; KDoc contract bullets unchanged |
| Test Names | Preserved | Existing test name retained; new test is additive |

---

## 6) Recommendations

No blocking or high-priority changes required.

**NIT — Optional fix for stale comment** (`IsometricMaterialComposables.kt:57-59`):
Update the inline comment to reflect that all stock shapes now have UV support, not just
Prism. Low effort (3 lines), improves accuracy for future maintainers.

**No equivalence tests needed:** The test pivot from `Knot()` to `CustomShape` correctly
preserves the behavioral assertion. The `UvGeneratorKnotTest` suite (11 new cases) provides
adequate coverage for the new Knot UV functionality.

---

## 7) Verdict

**SAFE to merge.**

Both refactors (test pivot and KDoc rewrite) are behavior-equivalent for their respective
roles. The `CustomShape` fixture correctly exercises the `else -> null` branch via type
dispatch without any UV math involved. The KDoc rewrite removes obsolete forward-looking
commentary and replaces it with accurate current-state documentation without omitting
any behavioral contract. No test names changed. No external call sites were broken. The
only finding worth noting is a stale inline comment in an unrelated file.

---

*Review completed: 2026-04-20*  
*Commit: e5cf72a*
