---
schema: sdlc/v1
type: review
review-command: code-simplification
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
metric-findings-med: 0
metric-findings-low: 0
metric-findings-nit: 4
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - numerical-robustness
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Review: code-simplification

Scope: `git diff HEAD~1 HEAD` — `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
(+29/-12 lines, `countCloserThan` rewrite; `result0` removed). Related new files:
`PathTest.kt` (+134 lines, 6 new test functions), `WS10NodeIdScene.kt` (+27 lines, new
test scene factory).

## Findings

| ID   | Severity | Confidence | Location                              | Summary                                                   |
|------|----------|------------|---------------------------------------|-----------------------------------------------------------|
| CS-1 | NIT      | High       | `Path.kt:145`                         | `if (result > 0) 1 else 0` could be one expression       |
| CS-2 | NIT      | Med        | `Path.kt:103-116` KDoc                | KDoc narrates history already told by the workflow-link   |
| CS-3 | NIT      | High       | `PathTest.kt:57-175`                  | 5 near-identical test functions — parameterisable         |
| CS-4 | NIT      | Low        | `WS10NodeIdScene.kt` vs `InteractionSamplesActivity.NodeIdSample` | Geometry almost-duplicated, not shared |

---

## Detailed Findings

### CS-1 — `if (result > 0) 1 else 0` expression form (NIT)

**Location:** `Path.kt` line 145.

**Current code:**
```kotlin
return if (result > 0) 1 else 0
```

**Discussion:**
The expression is unambiguous and idiomatic enough. Two alternatives exist:

- `return result.coerceAtMost(1)` — reads as "cap to 1", which is the mechanical
  outcome but not the intent ("any match → signal"). Slightly misleading if `result`
  were ever > 1 by design; here it is just an artefact of the counting loop.
- `return if (result > 0) 1 else 0` — current. Explicit, obvious, zero cognitive load.
- `return (result > 0).compareTo(false)` — legal but obscure; don't use.

`result.coerceAtMost(1)` is marginally more concise but hides the boolean semantics.
The current form is already simple. No change required; flagged for completeness.

**Recommendation:** No action needed. If future readers find it noisy, `result.coerceAtMost(1)` is the cleaner mechanical form.

---

### CS-2 — KDoc narrates history already covered by the workflow link (NIT)

**Location:** `Path.kt` lines 103–116.

**Current KDoc body (abbreviated):**
```
Returns 1 if any vertex …

Used by [closerThan] …

Permissive ("any vertex" rather than "majority") is required for shared-edge cases:
for adjacent prism faces, only a fraction of the four vertices may sit on the
observer side of the other plane. The previous implementation collapsed mixed cases
via integer division — `(result + result0) / points.size` — which truncated 2/4 to 0
and reported a spurious tie that DepthSorter could not resolve. The back-to-front
pre-sort then became the sole arbiter and let a farther face paint over a closer one.
See workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
```

The first two sentences (what it returns, how it is used) are necessary. The third
paragraph (everything from "Permissive … is required" onwards) narrates the bug history
that motivated the change. That history is in the commit message, the workflow docs, and
implicitly in the workflow-link sentence at the end. Repeating it in KDoc:

- Adds ~6 lines of maintenance debt to a `private` function.
- Partially duplicates the workflow-link bullet (SEC-2 in the security review already
  flagged the workflow slug itself as leaking internal vocabulary).
- Does not help a reader understand what the function does or how to call it — it
  explains *why it was rewritten* which is changelog territory.

**Recommendation (NIT):** Trim the KDoc to the three-line form:
```kotlin
/**
 * Returns 1 if any vertex of this path is on the same side of pathA's plane as the
 * observer (within a 1e-6 epsilon to absorb floating-point noise), 0 otherwise.
 *
 * Permissive ("any vertex") is required for shared-edge cases where only some vertices
 * sit on the observer side of the other plane. Used by [closerThan].
 */
```
Remove the history paragraph and the workflow-slug sentence. The "why permissive" clause
can stay in one sentence without referencing implementation history.

---

### CS-3 — 5 near-identical `@Test fun` blocks in `PathTest` (NIT)

**Location:** `PathTest.kt` lines 57–175 — functions
`closerThan returns nonzero for hq-right vs factory-top shared-edge case`,
`closerThan resolves X-adjacent neighbours with different heights`,
`closerThan resolves Y-adjacent neighbours with different heights`,
`closerThan resolves top vs vertical side at equal heights`,
`closerThan resolves diagonally offset adjacency`.

Each follows the same pattern:
1. Construct two `Path` quads.
2. Call `bTop.closerThan(aRight, observer)`.
3. `assertTrue(result > 0, "…")`.

This could be expressed as a JUnit 4 `@RunWith(Parameterized::class)` suite or, more
idiomatically in this project (which already uses `kotlin.test`), as a data-driven helper:

```kotlin
data class CloserThanCase(
    val label: String,
    val subject: Path,
    val reference: Path,
    val expected: Int
)

private val closerThanCases = listOf(
    CloserThanCase("hq-right vs factory-top", hqRight, factoryTop, 1),
    // …
)

@Test
fun `closerThan positive cases`() {
    for (case in closerThanCases) {
        val result = case.subject.closerThan(case.reference, observer)
        assertEquals(case.expected, result.coerceIn(-1, 1), "case: ${case.label}")
    }
}
```

**Countervailing argument:** JUnit 4 `Parameterized` would require adding the
`junit:junit` test dependency (the module currently uses `kotlin.test`). Individual
`@Test` functions produce clearer failure messages in the IDE ("test X failed") vs. a
single test function with an inline loop (all cases fail as one). Five cases is also
near the threshold where a loop meaningfully reduces boilerplate — below ten functions
the gain is marginal.

**Recommendation (NIT):** The current form is acceptable. If the suite grows beyond
~8 cases, parameterisation becomes worth it. No action required now.

---

### CS-4 — `WS10NodeIdScene` geometry nearly-duplicated from `NodeIdSample` (NIT)

**Location:** `isometric-compose/src/test/kotlin/…/scenes/WS10NodeIdScene.kt`
vs. `app/src/main/kotlin/…/sample/InteractionSamplesActivity.kt` — `NodeIdSample`.

`WS10NodeIdScene` hardcodes:
```kotlin
Shape(geometry = Prism(Point(-1.0, -1.0, 0.0), 10.0, 6.0, 0.1), color = IsoColor.LIGHT_GRAY)
Shape(geometry = Prism(Point(0.0, 1.0, 0.1), 1.5, 1.5, 3.0), color = IsoColor.BLUE)
Shape(geometry = Prism(Point(2.0, 1.0, 0.1), 1.5, 1.5, 2.0), color = IsoColor.ORANGE)
Shape(geometry = Prism(Point(4.0, 1.0, 0.1), 1.5, 1.5, 1.5), color = IsoColor.GREEN)
Shape(geometry = Prism(Point(6.0, 1.0, 0.1), 1.5, 1.5, 4.0), color = IsoColor.PURPLE)
```

`NodeIdSample` builds the same geometry dynamically from a `buildings: List<Building>`
iterated with `forEachIndexed { i, building -> Point(i * 2.0, 1.0, 0.1) }` and heights
`[3.0, 2.0, 1.5, 4.0]`. The numeric values are identical; the only difference is that
`NodeIdSample` adds `nodeId`, `testTag`, and `onClick` callbacks, and supports
selection highlighting.

**Why this is only NIT:** `WS10NodeIdScene` lives in a test source set and
`NodeIdSample` in the `:app` module. Sharing geometry directly would require either
a test-fixture dependency on `:app` (wrong direction) or extracting a shared constants
object into `:isometric-core` or `:isometric-compose` (legitimate but over-engineered
for five hardcoded shapes). The test scene was deliberately kept simple and stateless,
which is correct for snapshot testing.

**Recommendation (NIT):** No structural change needed. A one-line comment in
`WS10NodeIdScene` noting that the geometry mirrors `NodeIdSample` would prevent
future drift going unnoticed:
```kotlin
// Geometry mirrors NodeIdSample in :app InteractionSamplesActivity (minus callbacks).
// If NodeIdSample building layout changes, update this scene to match.
```

---

### Orphan references to removed `result0`

**Checked:** The diff removes the `result0` variable and both `if` branches that read
or wrote it. The updated KDoc on lines 103–116 references the old expression
`(result + result0) / points.size` only as a historical description inside the
narrative paragraph (see CS-2). No call sites or other files reference `result0`
(it was always `private` and local). The removal is complete.

---

## Summary

This is a small, surgical fix. All four findings are NIT. The three substantive
questions raised in the investigation focus are answered:

- **`if (result > 0) 1 else 0`:** Already the clearest form; `result.coerceAtMost(1)`
  is a viable micro-alternative but not an improvement.
- **KDoc length:** The history paragraph (5 lines) adds maintenance debt without reader
  value for a `private` function; trimming it is the only recommended action.
- **5 individual test functions:** Acceptable at this size; no parameterisation needed now.
- **`WS10NodeIdScene` vs `NodeIdSample`:** Structural duplication is intentional and
  appropriate; a drift-warning comment is the only missing piece.
- **`result0` removal:** Complete — no orphan references remain.

**Result: PASS.** No blockers. No action required to ship; CS-2 (KDoc trim) and CS-4
(drift comment) are the only changes worth making before the next review cycle.
