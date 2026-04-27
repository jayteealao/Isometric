---
schema: sdlc/v1
type: review
review-kind: maintainability
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
source-file: isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt
focus-lines: 103-146
finding-count: 5
finding-ids: [MA-1, MA-2, MA-3, MA-4, MA-5]
severity-distribution:
  minor: 3
  moderate: 1
  advisory: 1
tags:
  - maintainability
  - naming
  - kdoc
  - magic-numbers
  - coupling
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  source: isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt
---

# Maintainability Review: depth-sort-shared-edge-overpaint

## Scope

Review is limited to the changes introduced in commit `3e811aa` to
`isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`,
specifically lines 103–146 (`countCloserThan` KDoc and body). The public
signature of `closerThan` is unchanged; `countCloserThan` is `private`.

---

## Findings

### MA-1 — KDoc describes the bug history but under-specifies the contract

**Severity**: moderate  
**Lines**: 103–116

The KDoc for `countCloserThan` is partially contract-oriented (first two
sentences state what the function returns) but pivots quickly into a
retrospective of the previous implementation's failure mode
(integer-division truncating 2/4 to 0, the DepthSorter resolution story,
the visible regression). A future maintainer reading the KDoc in isolation
learns what went wrong before, but not:

- What invariant the return value upholds (specifically: `closerThan`
  produces a *signed* result only if the two calls return different values;
  if both return 1 the subtraction is 0, which the caller treats as
  "no ordering signal" — this is a semantic contract, not mentioned).
- What "any vertex" means for degenerate inputs: a single coplanar vertex
  that just exceeds the epsilon could trigger a 1, giving a false positive.
  The deliberate choice to accept that trade-off is not stated.
- What happens when the observer is itself on the plane (observerPosition ≈ 0):
  all products will be near zero and every vertex will fall below the
  epsilon, returning 0. That is probably correct, but it is not documented.

**Recommendation**: Restructure the KDoc so the first block is a clean
contract paragraph (inputs → output → invariant), and move the bug
history to an `@implNote` or a separate `// History:` comment block. The
workflow reference (`See workflow depth-sort-shared-edge-overpaint`) is
appropriate for a team that uses the AI workflow docs — keep it, but place
it at the end.

---

### MA-2 — Return type `Int` misrepresents a binary output; name does not reflect new semantics

**Severity**: moderate  
**Lines**: 117, 145

The function is named `countCloserThan` and returns `Int`, both of which
imply a cardinality. After the change, the function counts nothing: it
returns 1 if any vertex qualifies and 0 otherwise — a boolean predicate
encoded as `Int` for subtraction convenience in `closerThan` (line 100).

The coupling is tighter than before:

- `closerThan` (line 99–101) relies on the fact that both calls return
  values in `{0, 1}` so the subtraction yields `{-1, 0, +1}` — a
  three-valued comparator. If `countCloserThan` were ever refactored to
  return a real count again, `closerThan`'s caller semantics would silently
  change because there is no annotation or type constraint enforcing the
  binary contract.
- The name `countCloserThan` is now semantically misleading. A reader
  who has not studied the history will expect a count and be confused by
  the `if (result > 0) 1 else 0` collapse at line 145.

**Recommendation (two options, either is acceptable since the function is
private)**:

Option A — rename to reflect the binary intent:
```kotlin
private fun hasAnyVertexCloserThan(pathA: Path, observer: Point): Int
```

Option B — change the return type to `Boolean` and update `closerThan` to
use `if/else` or `.compareTo`:
```kotlin
private fun hasAnyVertexCloserThan(pathA: Path, observer: Point): Boolean

fun closerThan(pathA: Path, observer: Point): Int {
    val aCloserThanThis = pathA.hasAnyVertexCloserThan(this, observer)
    val thisCloserThanA = this.hasAnyVertexCloserThan(pathA, observer)
    return when {
        thisCloserThanA && !aCloserThanThis -> 1
        aCloserThanThis && !thisCloserThanA -> -1
        else -> 0
    }
}
```

Option B eliminates the implicit `{0,1}` contract and makes the
three-valued comparator logic explicit at the call site.

---

### MA-3 — `0.000001` is a magic literal; no named constant or unit context

**Severity**: minor  
**Line**: 140

```kotlin
if (observerPosition * pPosition >= 0.000001) {
```

The value `0.000001` (1e-6) appears once. The inline comment above it
(lines 137–139) explains *why* this value was chosen (coordinate range
0..100, floating-point noise absorption, widened from 1e-9). That is
helpful. However:

- The comment is on the block *above* the literal; a reader who jumps
  directly to line 140 from a stack trace or search result sees only
  `0.000001` with no label.
- If the coordinate range contract changes (e.g., the engine supports
  0..1000), a maintainer needs to know to update this constant. A named
  constant makes the relationship between coordinate range and epsilon
  visible.
- `0.000001` vs `1e-6` — the hex-literal form `1e-6` is idiomatic in
  Kotlin/Java numerical code and reads as "one times ten to the negative
  six", which is easier to compare to the comment's `1e-9` reference.

**Recommendation**:
```kotlin
// Products of plane-side distances; a value below this threshold means
// the vertex is coplanar with (or on the wrong side of) pathA's plane.
// Widened from 1e-9 to 1e-6 for the project's 0..100 coordinate range.
private val PLANE_SIDE_EPSILON = 1e-6

// ... in countCloserThan:
if (observerPosition * pPosition >= PLANE_SIDE_EPSILON) {
```

If extracting to a class-level constant is considered premature for a
single use-site, at minimum replace `0.000001` with `1e-6` for
readability.

---

### MA-4 — `observerPosition` is reused without disambiguation

**Severity**: minor  
**Line**: 129

```kotlin
val observerPosition = Vector.dotProduct(n, OU) - d
```

The variable name `observerPosition` is reasonable in context. However,
it represents a *signed scalar distance from the plane* (the dot-product
of the normal with the observer's position vector, minus the plane
offset), not a position vector. In the loop at lines 133–142 a parallel
local `pPosition` is computed for each vertex point.

The asymmetry (`observerPosition` vs `pPosition`) is not immediately
readable: one is a field name implying geometric location, the other a
local implying a scalar. A reader who skims the loop will need to trace
back to understand that the *product* `observerPosition * pPosition >= ε`
tests sign agreement (same side of plane), not absolute magnitude.

**Recommendation**: rename both to make the "signed plane distance"
semantics explicit:
```kotlin
val observerPlaneDist = Vector.dotProduct(n, OU) - d
// ...
val vertexPlaneDist = Vector.dotProduct(n, OP) - d
if (observerPlaneDist * vertexPlaneDist >= PLANE_SIDE_EPSILON) {
```

This is a minor readability issue; it does not affect correctness.

---

### MA-5 — The KDoc omits the rationale for "any vertex" vs majority threshold

**Severity**: advisory  
**Lines**: 108–115

The KDoc uses the word "Permissive" and gives the integer-division
counter-example (2/4 = 0), but does not explain why `result > 0` (any
vertex) was chosen over alternatives such as:

- `result >= 1` (same as chosen, but named differently)
- `result > points.size / 2` (strict majority)
- `result >= points.size - 1` (all but one vertex)

A future maintainer who revisits this function to tune for a different
failure mode (e.g., a case where a single noisy vertex produces a false
positive and breaks a different pair's ordering) will not know whether the
"any vertex" choice was:

a) the *minimal* fix to the specific 2/4 truncation bug, or  
b) a deliberately conservative bound chosen after evaluating majority and
   all-vertex alternatives.

The commit message partially covers this for `git log` readers, but KDoc
is the primary source of truth for code readers.

**Recommendation**: Add one sentence to the KDoc:

> A strict majority threshold (`result > points.size / 2`) was considered
> but rejected: for the shared-edge case only a single vertex may be
> unambiguously on the observer side; requiring a majority would reintroduce
> a variant of the truncation failure.

---

## Summary Table

| ID   | Severity  | Location   | Short description                                        |
|------|-----------|------------|----------------------------------------------------------|
| MA-1 | moderate  | lines 103–116 | KDoc mixes contract with bug history; contract is incomplete |
| MA-2 | moderate  | lines 117, 145 | `Int` return type and `count` name misrepresent binary semantics |
| MA-3 | minor     | line 140   | Magic literal `0.000001`; should be named constant `1e-6` |
| MA-4 | minor     | line 129   | `observerPosition` name obscures "signed plane distance" semantics |
| MA-5 | advisory  | lines 108–115 | KDoc omits rationale for "any vertex" vs majority thresholds |

---

## Overall Assessment

The implementation is correct and the KDoc is substantially better than
the original one-liner. The two moderate findings (MA-1, MA-2) are the
most likely to create confusion during a future refactor: a maintainer
who changes `countCloserThan` without knowing it must return a value in
`{0,1}` will silently break `closerThan`'s three-valued comparator
semantics. The minor and advisory findings (MA-3, MA-4, MA-5) are
low-risk but worth addressing before the function is touched again.

None of the findings require a code change before merging; all are
addressable in a follow-up polish pass.
