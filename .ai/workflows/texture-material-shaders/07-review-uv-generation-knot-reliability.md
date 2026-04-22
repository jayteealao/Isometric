---
schema: sdlc/v1
type: review-sub
slug: texture-material-shaders
slice-slug: uv-generation-knot
review-command: reliability
status: complete
stage-number: 7
created-at: "2026-04-20T22:30:00Z"
commit: e5cf72a
scope: diff
source-files:
  - isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt
  - isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt
tags: [review, reliability, knot, uv-generation]
---

# Reliability Review: uv-generation-knot

**Scope:** `git diff HEAD~1 HEAD` — commit `e5cf72a`, slice `uv-generation-knot`
**Reviewer:** Claude Reliability Review Agent
**Date:** 2026-04-20
**Sources reviewed:** `UvGenerator.kt` (forKnotFace, forAllKnotFaces, quadBboxUvs),
`Knot.kt` (sourcePrisms val), `UvGeneratorKnotTest.kt` (11 tests)

---

## Summary

This is a pure math / pure Kotlin slice with no external dependencies, no I/O, no
threading, and no state mutation. The reliability risk profile is therefore low compared
to web services or I/O pipelines. The standard checklist categories (retry logic, timeouts,
circuit breakers, connection pools, rate limiting) are not applicable.

The relevant reliability surface for this slice is:

1. **Error handling consistency with sibling generators** — does `forKnotFace` follow the
   `forPrismFace` wrap-and-rethrow pattern?
2. **Arithmetic safety in `quadBboxUvs`** — can NaN / Infinity propagate from degenerate
   inputs?
3. **Structural guarantees on `sourcePrisms`** — is the mutable `List<Prism>` externally
   replaceable or driftable at runtime?
4. **Bound-check robustness** — does `Int.MIN_VALUE` faceIndex behave correctly?
5. **Interaction with the depth-sort bug** — does any error message incorrectly reference
   or obscure the known depth-sort issue?

**Severity Breakdown:**
- BLOCKER: 0
- HIGH: 1 (error-handling inconsistency with sibling `forPrismFace`)
- MED: 2 (NaN/Infinity propagation through `quadBboxUvs`; `sourcePrisms` drift
  detectable only at test time, not at construction time)
- LOW: 2 (degenerate-all-zero UV silent return; `else` branch in `forKnotFace` is
  unreachable dead code after the leading `require`)
- NIT: 1 (no `@Throws` annotation to document the IAE contract)

**Merge Recommendation:** REQUEST_CHANGES (HIGH finding REL-K-01 is a correctness gap
in failure attribution; can be addressed with a small patch before shipping)

---

## Findings

### REL-K-01: `forKnotFace` does not wrap sub-prism delegation in try/catch [HIGH]

**Location:** `UvGenerator.kt:374–385` (the `forKnotFace` body)

**Severity:** HIGH — Confidence: High

**Issue:**
`forPrismFace` (lines 38–51) wraps its entire body in `try { … } catch (e: Exception)` and
re-throws as `IllegalArgumentException` with context: prism position, faceIndex, and the
original cause. `forKnotFace` calls `forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)`
without any try/catch wrapper. If the inner `forPrismFace` throws (e.g., because
`sourcePrisms` has drifted from `createPaths` and the delegated localFaceIndex is out of
range for the sub-prism's actual path count, or because `computeUvs` encounters
unexpected geometry), the exception propagates with `forPrismFace`'s message:

```
UV generation failed for Prism at (0.0, 0.0, 0.0), faceIndex=0
```

The caller sees a Prism error with no mention of the originating Knot or the Knot
faceIndex (0..17) that triggered it. This makes debugging a drift regression harder:
the stack trace points into `forPrismFace`, and the message names a sub-Prism, not the
Knot.

`forStairsFace` (lines 287–335) also has no wrapper — but Stairs does not delegate to
another generator, so there is no "loss of context" problem. Knot is unique in the UV
generator family because it is the only generator that calls another generator
recursively. That delegation pattern requires a wrapper to preserve the call context.

**Evidence:**
```kotlin
// UvGenerator.kt:374–385
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) { /* ... */ }
    return when (faceIndex) {
        in 0..17 -> {
            val prismIndex = faceIndex / 6
            val localFaceIndex = faceIndex % 6
            forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)  // NO wrapper
        }
        18, 19 -> quadBboxUvs(knot.paths[faceIndex])
        else -> throw IllegalArgumentException(
            "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
        )
    }
}
```

Compare with `forPrismFace` (lines 42–50):
```kotlin
return try {
    val face = PrismFace.fromPathIndex(faceIndex)
    val path = prism.paths[faceIndex]
    computeUvs(prism, face, path)
} catch (e: Exception) {
    throw IllegalArgumentException(
        "UV generation failed for Prism at ${prism.position}, faceIndex=$faceIndex", e
    )
}
```

**Failure Scenario:**
1. A future edit changes `createPaths()` Prism dimensions but omits updating
   `sourcePrisms` (the drift the regression test guards against).
2. If the drift causes `sourcePrisms[i].paths.size` to differ from 6, calling
   `forPrismFace(sourcePrisms[i], localFaceIndex)` for a localFaceIndex ≥ the new
   size will throw `IllegalArgumentException("faceIndex N out of bounds for Prism...")`.
3. The stack trace names the sub-Prism, not the Knot faceIndex (e.g., 13) that the
   caller passed. The developer must manually back-calculate which Knot face triggered
   the failure.

**Impact:**
- Debugging impact: moderate; the drift is caught by the unit test, but if the test
  is bypassed or a different path triggers the exception (e.g., malformed custom Prism
  subclass), attribution is lost.
- User impact: none beyond the crash itself.

**Fix:**
```kotlin
fun forKnotFace(knot: Knot, faceIndex: Int): FloatArray {
    require(faceIndex in knot.paths.indices) { /* ... */ }
    return try {
        when (faceIndex) {
            in 0..17 -> {
                val prismIndex = faceIndex / 6
                val localFaceIndex = faceIndex % 6
                forPrismFace(knot.sourcePrisms[prismIndex], localFaceIndex)
            }
            18, 19 -> quadBboxUvs(knot.paths[faceIndex])
            else -> throw IllegalArgumentException(
                "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
            )
        }
    } catch (e: Exception) {
        throw IllegalArgumentException(
            "UV generation failed for Knot at ${knot.position}, faceIndex=$faceIndex", e
        )
    }
}
```

This mirrors `forPrismFace` exactly, chains the original cause, and adds the Knot
position for diagnostics (matching the "Prism at ${prism.position}" pattern).

---

### REL-K-02: NaN / Infinity inputs to `quadBboxUvs` silently propagate to GPU [MED]

**Location:** `UvGenerator.kt:401–433` (`quadBboxUvs`)

**Severity:** MED — Confidence: Med

**Issue:**
`quadBboxUvs` correctly guards against divide-by-zero when all points share an axis
value (`span == 0.0 → return 0.0`). However, it performs no guard against NaN or
Infinity in the input points. If any vertex coordinate is `Double.NaN` or
`Double.POSITIVE_INFINITY` (which Kotlin's `Double` type permits), the following
silent failures occur:

- `pts.minOf { it.x }` with a NaN input returns NaN. All subsequent comparisons
  involving NaN evaluate to `false`, meaning `spanZ <= spanX` and `spanZ <= spanY`
  are both false, and `spanY <= spanX` is also false. The `else` branch is taken:
  `(pt.y - minY) / spanY`, which is `NaN / NaN = NaN`. The result array is filled
  with NaN casted to `Float.NaN` (IEEE 754 NaN survives `toFloat()`). The GPU
  receives NaN UV coordinates, producing undefined fragment behavior — typically
  rendering as solid black or magenta, depending on driver, with no error logged.

- With Infinity inputs, `minOf` / `maxOf` return ±Infinity, spans become Infinity,
  and `(pt.x - minX) / spanX = Infinity / Infinity = NaN` — same outcome.

The custom quads at indices 18–19 use hard-coded `Point` literals in `Knot.createPaths`
so the immediate paths cannot carry NaN in the current codebase. However, if `Knot`
is ever extended (subclassed or the `createPaths` logic revised) this silent path opens.
The sibling generators (`computeUvs`, `computePyramidBaseUvs`, `cylinderSideUvs`, etc.)
all have the same absence of NaN guards — this is a class-wide issue, not unique to
Knot. For this slice, the risk is low because custom quad paths are fully controlled.

**Evidence:**
```kotlin
// quadBboxUvs — no NaN guard before minOf/maxOf
val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
// If any pt.x is NaN → minX = NaN, maxX = NaN → spanX = NaN
// → `if (spanX > 0.0) ... else 0.0` → branch taken is `else 0.0`
//   Wait — spanX = NaN > 0.0 is false → returns 0.0 for all UV
//   BUT: for the axis that IS selected by the when branch, it divides by spanX:
//   (pt.x - minX) / spanX = NaN/NaN = NaN → Float.NaN in result
```

**Note on degenerate-all-zero case:** When ALL three spans are zero (a point degenerate
quad — all 4 vertices identical), the `when` condition `spanZ <= spanX && spanZ <= spanY`
is `0 <= 0 && 0 <= 0 = true`. Both `spanX > 0.0` and `spanY > 0.0` are false, so
both guards collapse to `0.0`. The result is a valid `FloatArray(8)` of all-zeros —
every UV is `(0.0f, 0.0f)`. This maps all vertices to texture coordinate (0,0),
which produces correct but maximally degenerate texture sampling (a single texel color
tiled over the entire face). It is a silent degradation but not a crash.

**Impact:**
- Current impact: none — the two custom-quad paths in `Knot.createPaths` use literal
  finite Point values.
- Future impact: if `Knot` is subclassed, or a future refactor passes an arbitrary
  `Path` to `quadBboxUvs`, NaN GPU values are hard to debug.

**Fix (Low-priority defensive guard):**
Add a `require` at the top of `quadBboxUvs`:
```kotlin
private fun quadBboxUvs(path: Path): FloatArray {
    val pts = path.points
    require(pts.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }) {
        "quadBboxUvs: path contains non-finite vertex coordinates"
    }
    // ... rest unchanged
}
```
Alternatively, add the guard only to `forKnotFace` before the `quadBboxUvs` call,
mirroring how `forPrismFace` wraps errors with context.

---

### REL-K-03: `sourcePrisms` is `List<Prism>` not a read-only snapshot — drift is
detectable only by unit test, not at runtime [MED]

**Location:** `Knot.kt:35–39` (`val sourcePrisms: List<Prism>`)

**Severity:** MED — Confidence: Med

**Issue:**
`sourcePrisms` is declared as `val sourcePrisms: List<Prism> = listOf(...)`. The `val`
prevents reassignment of the reference, and `listOf()` returns an unmodifiable list,
so the **list itself** cannot be mutated by external callers (no `add`, `set`, etc.).

However, three separate reliability concerns remain:

1. **`Prism` mutability:** The list elements are `Prism` instances. If `Prism` is a
   mutable class (or has mutable fields), callers who obtain a reference via
   `knot.sourcePrisms[0]` could mutate the prism's internal state, silently corrupting
   subsequent UV generation. Reading `Prism.kt` confirms `Prism` uses `val` fields
   constructed at init time and has no public setters — so in practice prisms ARE
   effectively immutable. But this is a behavioral contract not enforced by the type
   system (e.g., `Prism` is not `data class` with `copy()` only, and there is no
   `@Immutable` annotation).

2. **`createPaths` / `sourcePrisms` drift is a silent UV error.** If `createPaths`
   is edited to use different Prism dimensions without updating `sourcePrisms`, UV
   generation silently uses wrong normalisation factors (producing stretched or
   compressed UVs without any exception). The only guard is the unit test in
   `UvGeneratorKnotTest.sourcePrisms dimensions match createPaths constants`.
   There is no runtime invariant check. In a large codebase, a `createPaths` edit
   could plausibly skip the companion's drift-warning comment.

3. **`@ExperimentalIsometricApi` on `sourcePrisms` but `Knot` itself is also
   `@ExperimentalIsometricApi`.** The redundant annotation on the property is harmless
   but could mislead future maintainers into thinking the property's stability is
   *more* experimental than the class itself — both have the same opt-in requirement.

**Impact:**
- Prism mutability: none in practice given current `Prism` implementation.
- Drift: produces wrong UV coordinates with no runtime signal. Unit test catches it
  if run; CI would catch it if the test suite is comprehensive. Risk is low in a
  greenfield library but worth documenting as a maintenance burden.

**Fix:**
No code change required for current correctness. For long-term reliability, consider
adding a `companion object` invariant check:
```kotlin
init {
    check(sourcePrisms.size == 3) { "Knot.sourcePrisms must have exactly 3 entries" }
    // Optional: pin the dimension constants here to fail at Knot construction time
    // rather than at UV generation time. Preferred over a test-only guard if this
    // class will be frequently edited.
}
```
Or document the risk explicitly in the KDoc of `createPaths` (beyond the existing
comment) with a link to the failing test.

---

### REL-K-04: Dead `else` branch in `forKnotFace` is unreachable after the leading
`require`, masking the real error contract [LOW]

**Location:** `UvGenerator.kt:381–384`

**Severity:** LOW — Confidence: High

**Issue:**
`forKnotFace` first does:
```kotlin
require(faceIndex in knot.paths.indices) { "faceIndex $faceIndex out of bounds..." }
```
Since `knot.paths.size == 20`, this passes only for `faceIndex in 0..19`. The `when`
then covers `0..17` and `18, 19`, which together cover all of `0..19`. The `else`
branch:
```kotlin
else -> throw IllegalArgumentException(
    "Knot has exactly 20 faces (indices 0..19); got $faceIndex"
)
```
is therefore **unreachable by construction**. This is not a runtime risk, but it creates
a maintenance confusion: the `else` message says "Knot has exactly 20 faces" which
echoes documentation language rather than serving as a real guard. A future reader
might wonder if there is a path through the `when` that the `require` doesn't cover.

The sibling generators (`forPrismFace`, `forStairsFace`, `forCylinderFace`) do not have
this pattern — they either dispatch completely (every branch covered) or use `require`
alone. Only `forKnotFace` has both. The `else` branch has the cosmetic effect of
suppressing Kotlin's exhaustiveness warning on the `when` expression, which was
presumably the motivation — but since `when (faceIndex)` over `Int` is not a sealed
type, Kotlin never requires exhaustiveness here anyway.

**Impact:** None at runtime. Mild confusion for future maintainers.

**Fix options:**
- Remove the `else` branch entirely (the `require` makes it dead). The `when` becomes
  a non-exhaustive expression, which is fine for `Int`.
- Or replace it with a comment: `// unreachable — require() above ensures 0..19`.

---

### REL-K-05: Depth-sort bug not mentioned in any error message or KDoc for
UV-generation code path [LOW]

**Location:** `UvGenerator.kt:345–385` (`forKnotFace` KDoc and body)

**Severity:** LOW — Confidence: Low

**Issue:**
`Knot.kt` (lines 11–20) documents the depth-sort bug prominently in the class-level
KDoc. The `forKnotFace` KDoc in `UvGenerator.kt` (lines 349–367) describes the
bag-of-primitives delegation model but does not cross-reference the depth-sort issue.
This is a weak reliability concern: a developer debugging visual artifacts on a Knot
might look at `forKnotFace` docs expecting a hint about known rendering caveats, find
none, and spend time investigating UV math before discovering the depth-sort issue.

The verify pass confirms this: the `06-verify-uv-generation-knot.md` entry documents
"Pre-existing: depth-sort artifact in every screenshot — UV-correct on visible arms,
sort-occluded elsewhere." The absence of this caveat in `forKnotFace`'s KDoc is a
documentation reliability gap, not a code reliability gap.

**Impact:** Developer experience only. No runtime effect. The `Knot` class-level KDoc
is the authoritative location for the caveat; the UV generator is a secondary location.

**Fix:**
Add a `@see Knot` reference or a brief `@note` line in `forKnotFace` KDoc:
```kotlin
/**
 * ...
 *
 * **Note:** [Knot] has a known depth-sorting issue that may cause some faces to
 * render in incorrect order regardless of UV correctness. See [Knot] KDoc.
 */
```

---

### REL-K-NIT-01: No `@Throws` annotation on `forKnotFace` / `forAllKnotFaces` [NIT]

**Location:** `UvGenerator.kt:368–394`

**Severity:** NIT — Confidence: High

**Issue:**
`forPrismFace` (line 36–37 KDoc) documents `@throws IllegalArgumentException ...` in
its KDoc. `forKnotFace` documents the throw in prose (`@throws IllegalArgumentException
if faceIndex is outside...`) but does NOT annotate it with `@Throws(IllegalArgumentException::class)`.
For a Kotlin `internal` function called only from Kotlin, `@Throws` is not required for
Java interop. However, the sibling `forPrismFace` and `forStairsFace` do not have
`@Throws` either — so the inconsistency is class-wide and pre-dates this slice. This
slice does not introduce a new inconsistency.

**Impact:** None.

---

## Dependency Analysis

**External Dependencies:** None — `UvGenerator` is pure Kotlin math; no network, no
disk, no platform APIs.

**Single Points of Failure:** None at the UV generation layer.

**State dependencies:**
- `knot.sourcePrisms` — list reference fixed at construction, list unmodifiable. Safe.
- `knot.paths` — parent-class `List<Path>` set at Shape construction. Safe (same
  pattern as all sibling generators).
- Delegation to `forPrismFace` — shares `UvGenerator` singleton's Pyramid/Cylinder
  caches. The `@Volatile` caches are keyed by identity on their own shape types and
  are not touched by Knot logic. No cross-shape cache interference.

---

## Error Handling Coverage

| Path | Has guard | Has try/catch | Context preserved | Risk |
|------|-----------|---------------|-------------------|------|
| `forKnotFace` — bounds check | `require` | No | Partial (faceIndex in message) | HIGH (REL-K-01) |
| `forKnotFace` → `forPrismFace` | Delegated | No | Lost — shows Prism context | HIGH (REL-K-01) |
| `forKnotFace` → `quadBboxUvs` | None | No | N/A (no IAE thrown) | MED (REL-K-02) |
| `quadBboxUvs` — degenerate spans | span>0 guard | No | Silent 0.0 | LOW (acceptable) |
| `quadBboxUvs` — NaN inputs | None | No | Silent NaN→GPU | MED (REL-K-02) |
| `forAllKnotFaces` | Via forKnotFace | No | Inherits above | HIGH (REL-K-01) |
| `sourcePrisms` drift | Test only | No | No runtime signal | MED (REL-K-03) |

---

## Answers to Targeted Questions

### 1. Error-handling consistency with `forPrismFace` (wrap-and-rethrow pattern)

**No, `forKnotFace` does NOT match `forPrismFace`'s wrap-and-rethrow pattern.**
`forPrismFace` wraps its entire body in `try { … } catch (e: Exception)` and rethrows
with Prism position + faceIndex context. `forKnotFace` calls `forPrismFace` directly
without any wrapper, meaning any exception from the delegation path surfaces with
sub-Prism context rather than Knot context. This is the HIGH finding REL-K-01.

### 2. `quadBboxUvs` when ALL three spans are 0 (fully degenerate quad)

When all four points are identical (all spans = 0), the `when` condition
`spanZ <= spanX && spanZ <= spanY` evaluates to `0.0 <= 0.0 && 0.0 <= 0.0 = true`
(first branch). Both span guards fire: `if (spanX > 0.0) … else 0.0` and
`if (spanY > 0.0) … else 0.0`. Result: a valid `FloatArray(8)` of all-zeros. No
divide-by-zero, no NaN, no crash. Every vertex maps to UV `(0.0, 0.0)` — silent
texture collapse to a single texel. This is the documented "degenerate spans collapse
to 0" behavior and is the correct behavior for this edge case.

### 3. Behavior if `sourcePrisms` is mutated externally

`sourcePrisms` is `val` and backed by `listOf()` (returns `java.util.Arrays$ArrayList`,
which is unmodifiable — `add()`/`set()` throws `UnsupportedOperationException`).
The list reference cannot be reassigned (`val`), and the list elements cannot be
replaced by index. External callers can obtain a `Prism` reference from the list and
call methods on it, but `Prism`'s properties (`position`, `width`, `depth`, `height`,
`paths`) are all `val` fields constructed at init time — Prism is effectively immutable.
The practical answer: external mutation of `sourcePrisms` that would corrupt UV
generation is not possible with the current `Prism` implementation. Risk is MED only
if `Prism` is later changed to have mutable state (REL-K-03).

### 4. Behavior with NaN or Infinity vertex coordinates in a face path

For faces 0..17 (sub-prism delegation): `forPrismFace` → `computeUvs` performs
`(pt.x - ox) / w` arithmetic. If `pt.x` is NaN, the result is NaN, cast to
`Float.NaN`, and written to the result array. The GPU receives `Float.NaN` UV
coordinates. IEEE 754 defines `NaN` as a quiet propagation — no exception, but
GPU behavior is driver-defined (black texel, undefined texel, or magenta).

For faces 18–19 (`quadBboxUvs`): same outcome as described in REL-K-02 — NaN
propagates through `minOf`/`maxOf` into the division and fills the result with
`Float.NaN`.

Neither path throws an exception or logs a warning. This is the MED finding REL-K-02.

### 5. Behavior at `faceIndex = Int.MIN_VALUE`

`require(faceIndex in knot.paths.indices)` — `knot.paths.indices` is `0 until 20`
(an `IntRange`). Kotlin's `in` operator on `IntRange` calls `IntRange.contains(Int)`,
which computes `Int.MIN_VALUE >= 0` → false. The `require` fires immediately with:
```
faceIndex -2147483648 out of bounds for Knot with 20 faces (valid range: 0 until 20)
```
No overflow, no bypass, no crash before the guard. The `else` branch in the `when`
is not reached. Behavior is correct.

### 6. Depth-sort bug mention in error messages

The depth-sort bug is **not mentioned in any error message** emitted by `forKnotFace`
or `quadBboxUvs`. This is correct: UV generation is orthogonal to depth sorting, and
embedding a depth-sort warning in a UV exception message would be misleading (the
exception fires on invalid faceIndex, unrelated to depth). The appropriate location
for the depth-sort caveat is the class-level KDoc of `Knot` (where it already lives)
and optionally a `@note` in `forKnotFace` KDoc (REL-K-05, LOW). There is no bug here.

---

## Recommendations

### Immediate Actions (HIGH — strongly recommended before shipping)

1. **REL-K-01 — Add try/catch wrapper to `forKnotFace`** (`UvGenerator.kt:370`)
   Wrap the `when` expression in `try { … } catch (e: Exception) { throw IAE("... Knot at ${knot.position}, faceIndex=$faceIndex", e) }`
   to match `forPrismFace`'s error-handling pattern.

### Short-term Improvements (MED — addressable in a follow-up)

2. **REL-K-02 — Add finite-coordinate guard to `quadBboxUvs`**
   A `require(pts.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })`
   guard at entry makes NaN/Infinity fail-fast with a clear message instead of
   silently producing NaN GPU values.

3. **REL-K-03 — Add `init` check for `sourcePrisms.size == 3` in `Knot`**
   Converts the regression guard from test-time-only to construction-time. Does not
   pin dimension values (that remains in the unit test) but prevents empty or
   wrong-count lists from ever reaching UV generation.

### Long-term Hardening (LOW/NIT — cosmetic or future-facing)

4. **REL-K-04 — Remove or comment the unreachable `else` branch** in `forKnotFace`
   to reduce reader confusion.

5. **REL-K-05 — Cross-reference Knot's depth-sort caveat** in `forKnotFace` KDoc
   to aid developers debugging visual artifacts.

6. **REL-K-NIT-01 — No action needed** — `@Throws` annotation absence is
   pre-existing across all generators; this slice does not worsen it.
