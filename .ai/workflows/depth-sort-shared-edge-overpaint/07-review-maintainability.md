---
schema: sdlc/v1
type: review
review-command: maintainability
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T00:00:00Z"
updated-at: "2026-04-28T00:00:00Z"
diff-range: "97416ba..HEAD"
source-files:
  - isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt
  - isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt
  - isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt
  - isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt
  - isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt
finding-count: 10
finding-ids: [MA-1, MA-2, MA-3, MA-4, MA-5, MA-6, MA-7, MA-8, MA-9, MA-10]
severity-distribution:
  moderate: 3
  minor: 4
  advisory: 3
tags:
  - maintainability
  - depth-sort
  - kdoc
  - naming
refs:
  index: 00-index.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
---

# Maintainability Review: depth-sort-shared-edge-overpaint (Newell rewrite)

## Scope

Cumulative diff `97416ba..HEAD` on `feat/ws10-interaction-props`. The primary
change is the full algorithmic rewrite of `Path.closerThan` to a six-step
Newell Z→X→Y minimax cascade plus the deletion of `countCloserThan` and
the addition of `signOfPlaneSide`. Secondary changes: the `hasInteriorIntersection`
gate in `DepthSorter.checkDepthDependency`, the `DEPTH_SORT_DIAG` revert
in `DepthSorter`, and the new integration tests AC-12 / AC-13. This is
internal code with no public API surface change; `closerThan` is on the
hot path of every render frame.

---

## Findings (ranked by severity, then by call-site impact)

### MA-1 — `signOfPlaneSide` return convention is inverted relative to its name and KDoc header

**Severity**: moderate
**Location**: `Path.kt` lines 239–282

The KDoc header reads:

> - **+1** if every vertex of `this` lies on the OPPOSITE side from observer
>   (so `this` is entirely behind [pathA] — farther).
> - **-1** if every vertex lies on the SAME side as observer
>   (so `this` is entirely in front — closer).

The function name `signOfPlaneSide` reads as "the sign of the plane side
that `this` is on", which the natural reading maps to +1 = same side as
observer (in front) and -1 = opposite side (behind). The implementation
inverts this: +1 means OPPOSITE (farther), -1 means SAME (closer).

The inversion is intentional — it was forced by the pre-existing AC-1
test contract (positive = farther) and is documented in the Round 4 design
notes in `05-implement-depth-sort-shared-edge-overpaint.md`. However, that
context lives in a workflow file, not in the code. A future maintainer
reading only `Path.kt` sees a helper named `signOfPlaneSide` that returns
+1 for "opposite side" and will have to read both the KDoc header AND the
`when` block to resolve the contradiction with the name's natural reading.

Two compounding factors raise this to moderate:
1. `signOfPlaneSide` is used twice in `closerThan` — once directly and
   once with sign inversion (`-sRev`). The sign inversion on `sRev` is
   necessary because the reverse call (`pathA.signOfPlaneSide(this, observer)`)
   returns +1 to mean "pathA is farther than self", which must be negated to
   produce "self is closer". This is correct but the comment at line 232
   only says "sign inverted because the result is from pathA's perspective",
   which doesn't fully explain why the inversion achieves the right sign.
2. The KDoc on `signOfPlaneSide` uses the phrase "same side for this purpose"
   at line 247 (about vertices exactly on the plane) but is attached to the
   -1 (closer) branch — an atypical document structure that adds a small
   extra parsing step.

**Recommendation**: Rename to `planeSideSign` or `depthSign` (matching
the return semantics: negative = closer = draws later), and re-anchor the
KDoc's bullet points to the axis the caller uses: "returns -1 when this
draws AFTER pathA (closer); +1 when this draws BEFORE pathA (farther)."
That phrasing matches `closerThan`'s own KDoc (negative = closer, positive
= farther) and removes the cognitive switch between plane-geometry and
painter-algorithm vocabulary. The sign inversion for `sRev` then becomes
self-evident: "pathA drew before self, so self draws before pathA — negate."

---

### MA-2 — Steps 2/3 (screen-x/y minimax) are untested dead code with an unvalidated sign convention

**Severity**: moderate
**Location**: `Path.kt` lines 168–214

The Round 4 design notes explicitly state: "Steps 2/3 never fire in the
current test corpus. Kept them in the cascade to match the plan's Newell
Z→X→Y structure and as defensive coverage against future scenes where the
plane-side test is degenerate. Sign convention for these steps follows the
iso-projection intuition … since they are not exercised by any test, the
sign is conservative rather than empirically validated."

This means 47 lines of hot-path code — `cosAngle`, `sinAngle`, four
`var` bounds-tracking blocks, and four `if` early-exits — execute every
frame for every face pair that passes the step-1 gate, yet produce no
output in any scenario the test suite covers. Concretely:

- The sign convention at step 3 (`selfScreenYMax < aScreenYMin → return 1`;
  `aScreenYMax < selfScreenYMin → return -1`) is stated in a comment as
  "self entirely-below pathA on screen → self farther" but this is
  non-obvious (smaller screen-y = lower on screen = larger `-(sin*(x+y)+z)`
  value = larger `x+y` or larger `z`, and the relationship between
  screen-y position and painter-order depends on the observer direction).
  If the sign is wrong and a future scene exercises step 3, the sort will
  silently produce incorrect paint order.
- Steps 2 and 3 share the same AABB-style structure as step 1 but compute
  it across two loops (lines 183–200) that are merged differently from
  step 1's loops (lines 150–162). Step 1 loops once over each polygon
  computing depth; steps 2/3 loop once over each polygon computing both
  screen-x and screen-y together. This is a sensible micro-optimisation
  (one loop instead of two) but the structural asymmetry between the step 1
  loop shape and the steps 2/3 loop shape makes the cascade less uniform
  than it appears in the KDoc's numbered list.

**Recommendation (two tiers)**:

Tier A (preferred): Extract steps 2 and 3 into a companion function
`extentCheck(selfMin, selfMax, aMin, aMax, positiveSign: Int): Int`
and call it once for screen-x and once for screen-y, making the structure
mirror step 1's two lines. Add at least one focused unit test that exercises
each step in isolation (e.g., two rectangles that share the same depth range
but have strictly disjoint screen-x). Until the sign is validated by a test,
add a `TODO` comment at the step 3 block: "sign unvalidated — add a unit
test before relying on this step in production scenes."

Tier B (acceptable): If keeping the current structure, add a targeted
unit test for steps 2 and 3 in `PathTest.kt` (two pairs: one that step 1
misses but step 2 resolves, one that steps 1–2 miss but step 3 resolves).
The sign validation is the critical missing piece.

---

### MA-3 — `closerThan` step numbering in the KDoc (1–6) does not match the inline comments (which call plane-side "Step 5" and "Step 6")

**Severity**: moderate
**Location**: `Path.kt` lines 117–143 (KDoc) vs lines 222–236 (inline comments)

The KDoc lists six steps numbered 1–6:
1. Iso-depth (Z) extent
2. Screen-x extent
3. Screen-y extent
4. Plane-side forward
5. Plane-side reverse
6. Polygon split (deferred)

The inline comment at line 222 says "// Step 5: plane-side forward" and
line 228 says "// Step 6: plane-side reverse". So in the inline comments
the plane-side tests are steps 5 and 6, but in the KDoc they are steps 4
and 5 (the KDoc omits the "Step 4" slot for `hasInteriorIntersection`
from the KDoc list itself, while the comment at lines 216–220 explains why
step 4 is intentionally absent from the cascade body).

The result: a reader following the step numbers from the KDoc expects
"plane-side forward" at step 4; a reader following the inline comments
finds "// Step 5". This is not a logic error but it adds a small friction
every time someone cross-references KDoc to code. The `PathTest.kt` suite
header also references "steps 5/6" for plane-side tests (line 57), so the
test file and inline comments agree but both contradict the KDoc.

**Recommendation**: Renumber the KDoc to match the code. Since step 4
(`hasInteriorIntersection`) is intentionally omitted but conceptually
present in the Newell algorithm, either:
  - Renumber KDoc steps to 1/2/3/5/6/7 (leaving 4 as a documented gap
    with a note "step 4 (screen-overlap gate) is applied externally by
    DepthSorter; see checkDepthDependency"), or
  - Use a flat sequential numbering 1–6 in both KDoc and inline comments
    and call the skip out explicitly in a parenthetical. Either is fine;
    the current mismatch is the problem.

---

### MA-4 — `EPSILON` written as `0.000001` in `Path.kt`, `1e-6` in `IntersectionUtils.kt`; no shared constant

**Severity**: minor
**Location**: `Path.kt` line 298 vs `IntersectionUtils.kt` line 300

`Path.kt` companion defines `EPSILON: Double = 0.000001` (decimal literal).
`IntersectionUtils.kt` defines `EDGE_BAND: Double = 1e-6` (scientific
notation). Both are 1×10⁻⁶ and both are used for the same semantic
purpose (numerical tolerance in the depth-sort pipeline). The `closerThan`
KDoc cross-references `EDGE_BAND` in its "Why not the older vote-and-subtract"
section ("matches the rest of the depth-sort pipeline (e.g., … the existing
1e-6 plane-side tolerance)"). So the cross-file semantic relationship is
documented, but the values are not shared.

Three distinct issues:
1. The literal form divergence (`0.000001` vs `1e-6`) — a reader who
   searches for `1e-6` to audit all tolerance uses in the depth-sort
   pipeline will miss `Path.kt`'s constant.
2. The constant names diverge (`EPSILON` vs `EDGE_BAND`). Both names
   describe different geometrical roles (plane-side test vs edge proximity),
   so different names are appropriate — but the `EPSILON` name in `Path.kt`
   is generic enough to be confused with any other epsilon in the file.
3. `IntersectionUtils.kt` also contains an inline `0.000000001` (1e-9) at
   lines 146 and 267 for the strict edge-crossing test in `hasIntersection`
   and `hasInteriorIntersection`. This 1e-9 is a different tolerance (edge
   collinearity, not plane-side), but it has no named constant at all.
   A future reader wondering "why different thresholds for edge crossing
   vs plane side?" has no label to anchor on.

**Recommendation**: At minimum, change `Path.kt`'s `0.000001` to `1e-6`
for consistency of notation. Optionally rename `EPSILON` to
`PLANE_SIDE_EPSILON` to distinguish it from a generic float epsilon. The
1e-9 in `IntersectionUtils` should be extracted to a named constant
`EDGE_CROSSING_EPSILON` in that file; that is a separate but related
cleanup.

---

### MA-5 — Test faces identified by raw geometry coordinates are brittle to scene refactors

**Severity**: minor
**Location**: `DepthSorterTest.kt` AC-12 (lines 384–407), AC-13 (lines 451–491)

Both integration tests identify the ground-top face and the prism vertical
faces by matching exact coordinate values against `originalPath.points`:

```kotlin
pts.all { kotlin.math.abs(it.z - 0.1) < 1e-9 } &&
pts.any { kotlin.math.abs(it.x - (-1.0)) < 1e-9 } &&
pts.any { kotlin.math.abs(it.x - 7.0) < 1e-9 }
```

This pattern is also used in the earlier AC-9 test (lines 315–328) and the
WS10 NodeId test (lines 181–189). The implement notes acknowledge the
brittleness ("Brittle to geometry refactors but stable for this regression").

The specific fragility is:
- If the ground platform dimensions change from `8.0 × 6.0` to anything
  else, `it.x - 7.0` fails silently (the `indexOfFirst` returns -1), the
  subsequent `assertTrue(groundTopIndex >= 0)` fires, and the error message
  says "ground platform top face must appear in scene commands" — which
  sounds like a scene build bug, not a geometry mismatch. The test will
  fail with a misleading message rather than telling the maintainer to
  update the expected coordinate.
- The face-identification blocks are 8–12 lines each, duplicated in
  near-identical form across three test functions. Each copy introduces
  an independent maintenance obligation.

**Recommendation**: Extract a helper function per face type:

```kotlin
private fun PreparedScene.findGroundTop(): Int = commands.indexOfFirst { cmd ->
    val pts = cmd.originalPath.points
    pts.size == 4 && pts.all { abs(it.z - GROUND_TOP_Z) < 1e-9 } &&
        pts.any { abs(it.x - GROUND_MIN_X) < 1e-9 }
}
private const val GROUND_TOP_Z = 0.1
private const val GROUND_MIN_X = -1.0
```

This centralises the coordinate constants and makes the test bodies
read as assertions about relative positions rather than inline geometry
queries. It does not eliminate brittleness but isolates where to update
when coordinates change.

---

### MA-6 — `DepthSorter.checkDepthDependency` comment references the workflow slug by name

**Severity**: minor
**Location**: `DepthSorter.kt` line 140

```kotlin
// See workflow `depth-sort-shared-edge-overpaint` for the full diagnosis.
```

Workflow slugs are implementation-process vocabulary, not permanent project
identifiers. If the workflow directory is reorganised, renamed, or archived,
this comment becomes a dead reference. The comment currently provides
genuine value (it points a future reader at the diagnosis of why
`hasInteriorIntersection` is used over `hasIntersection`), but the pointer
form is fragile.

**Recommendation**: Replace the workflow slug with a plain-language
description that is self-contained:

```kotlin
// Full diagnosis: the lenient hasIntersection returned true for shared-edge
// face pairs that could not overpaint each other, producing spurious
// topological edges that pushed back-corner vertical faces to extreme
// draw positions in grid scenes. See git log for commit 9cef055.
```

Or, if the project intends the `.ai/workflows/` directory to be a permanent
internal reference, keep the slug but make it a relative path comment
(`docs/.ai/workflows/…`) so it is at least findable with a file browser.

---

### MA-7 — `intersects` and `cmpPath` locals in `checkDepthDependency` were kept from a revert but are orphaned

**Severity**: minor
**Location**: `DepthSorter.kt` lines 141–148

The Round 3 diagnostic pass introduced `intersects` and `cmpPath` as local
variables to feed the `DEPTH_SORT_DIAG` log emission. Round 4 reverted all
diagnostic logging but kept these two locals "as a small readability
improvement." Reading the code today:

```kotlin
val intersects = IntersectionUtils.hasInteriorIntersection(...)
if (intersects) {
    val cmpPath = itemA.item.path.closerThan(itemB.item.path, observer)
    if (cmpPath < 0) {
        drawBefore[i].add(j)
    } else if (cmpPath > 0) {
        drawBefore[j].add(i)
    }
}
```

The `intersects` local is genuinely useful — it names the gate check and
makes the `if` branch self-descriptive. The `cmpPath` local is also
reasonable; the name pairs with the comment "// When cmpPath == 0
(coplanar or ambiguous)". However, in both cases the variable could be
eliminated (inline the call into the `if`) and the code would not become
meaningfully harder to read — the only benefit the locals provide is the
name, which is descriptive but not a revelation.

More importantly: the comment at line 153 ("// When cmpPath == 0
(coplanar or ambiguous), intentionally add no edge.") is the only place
that documents the deliberate no-edge behaviour for ties. If `cmpPath`
is inlined, that comment would need to change or be lost. So the `cmpPath`
local is actually load-bearing for the comment's coherence.

This is a low-priority finding. The current state is coherent; the finding
is that a future reader might wonder why locals were introduced when the
logging they once fed no longer exists. A single line comment at the
variable declaration — "// kept for readability; fed the removed
DEPTH_SORT_DIAG logging, now documents the gate-then-compare pattern" —
would pre-empt the question.

**Recommendation**: Add a brief inline comment noting the locals are
intentional (not residue to clean up). Alternatively, keep them as-is;
the `cmpPath` comment dependency justifies their existence independently
of the logging history.

---

### MA-8 — Round/amendment references in test comments will rot as history recedes

**Severity**: advisory
**Location**: `PathTest.kt` lines 62–70; `DepthSorterTest.kt` lines 280, 349

Three test comment blocks reference workflow-process vocabulary that is
meaningful now but will age:

- `PathTest.kt` lines 62–70: the suite header comment says "round 1
  (3e811aa): …", "round 2 (9cef055): …", "round 3 (Newell): …"
  with commit SHAs embedded. The SHAs will remain stable (git history),
  but the "round 1 / round 2 / round 3" framing is workflow-internal
  vocabulary. A future reader of the test file, who has not read the
  workflow documents, will see "round 3 (Newell)" and have no context
  for what "round 3" means. The actual historical information (three
  successive approaches, each regressing a different scenario) is
  valuable and should be preserved — just not in round-numbered form.

- `DepthSorterTest.kt` line 280: "…the over-aggressive-edge regression
  after the original 3e811aa fix" — commit SHA as inline reference.
  SHAs are stable and will `git show` correctly, so this is low risk.
  The comment is genuinely useful (explains why `hasInteriorIntersection`
  replaced `hasIntersection` for the gate), but the SHA-anchored
  citation style will look unusual in a few months.

- `DepthSorterTest.kt` line 349: "…amendment-1 screen-overlap gate" —
  "amendment-1" is a workflow-internal term. To a new contributor, this
  reads as a reference to a document they cannot easily find.

**Recommendation**: Replace "round 1 / round 2 / round 3" in the suite
header with a plain description of the three approaches and their failure
modes (the content is already there; only the framing needs to change).
Replace "amendment-1" with "screen-overlap gate (hasInteriorIntersection)"
since the actual mechanism name is more durable than the amendment label.
SHAs can stay as-is.

---

### MA-9 — No runtime trace remains after the DEPTH_SORT_DIAG revert; "why did this pair return 0" debugging path is long

**Severity**: advisory
**Location**: `DepthSorter.kt` (overall); `Path.kt` (overall)

With the full `DEPTH_SORT_DIAG` block reverted in Round 4, there is no
remaining instrumentation path. If a visual regression surfaces in a scene
that is not covered by AC-9 / AC-12 / AC-13, the debugging workflow is:

1. Add `DEPTH_SORT_DIAG` back (re-implement ~47 lines from the Round 3
   commit, or retrieve them from `2e29dc5`).
2. Build and install on a device.
3. Capture logcat.
4. Trace the logged face pairs manually against the cascade.

This is the same workflow that was used for Round 3, and it worked. The
concern is that there is no lightweight alternative — no `println`
wrapper, no structured event emission controlled by a build flag, no
test hook that exposes which cascade step fired for a given pair. The
Round 3 diag code proved its value in exactly one session and was then
reverted entirely.

The recommendation is not to keep the diag logging in production; the
performance and logcat noise impact are real. The recommendation is to
keep a single reusable diagnostic function in a test-scope utility that
replicates the cascade but returns the step number that fired, so that
a focused unit test can assert "this pair resolved at step 5" rather
than just "this pair returned -1". This would also catch a regression
where a future change to the cascade silently bypasses an expected step.

**Recommendation (advisory, not blocking)**: Add a `testImplementation`-
only helper `Path.closerThanWithTrace(pathA, observer): Pair<Int, Int>`
returning `(result, stepFired)`. The PathTest KDoc already states which
step each test resolves at; this helper would validate those claims rather
than just asserting the sign.

---

### MA-10 — `signOfPlaneSide`'s handling of coplanar vertices is asymmetric with the KDoc's stated contract

**Severity**: advisory
**Location**: `Path.kt` lines 244–248 and 273–276

The KDoc states:

> Vertices exactly on the plane (within [EPSILON]) count as "same side"
> for this purpose.

However the implementation counts coplanar vertices as neither same-side
nor opposite-side:

```kotlin
val signed = observerPosition * pPosition
if (signed > EPSILON) anySameSide = true
if (signed < -EPSILON) anyOppositeSide = true
```

A vertex on the plane has `pPosition ≈ 0`, so `signed ≈ 0`, which falls
below `EPSILON` in the positive test and above `-EPSILON` in the negative
test — it sets neither flag. If ALL vertices are coplanar (both `anySameSide`
and `anyOppositeSide` are false), the `when` falls through to the `else`
branch (0 — undecided). The KDoc's claim that "coplanar vertices count as
same side" is incorrect.

The incorrect KDoc statement does not cause a bug (the actual behaviour
— treating coplanar as undecided, which falls through to the next cascade
step — is arguably more correct than counting coplanar as "same side"). But
a maintainer who reads the KDoc and then adds a guard assuming coplanar
vertices push the result to -1 will write incorrect logic.

The `closerThan` test `closerThan returns zero for coplanar overlapping faces`
(PathTest line 199) documents the correct observable: two faces in the same
plane return 0. If the KDoc were taken literally (coplanar = same side =
-1), that test would fail. So the test is the ground truth; the KDoc is
wrong.

**Recommendation**: Replace the KDoc sentence with the accurate description:

> Vertices exactly on the plane (`|pPosition| ≤ EPSILON`) are treated as
> undecided — they set neither the `anySameSide` nor the `anyOppositeSide`
> flag. If all vertices are coplanar, neither flag fires and the function
> returns 0 (mixed / undecided), falling through to the next cascade step.

---

## Summary Table

| ID    | Severity  | Location                              | Short description                                                             |
|-------|-----------|---------------------------------------|-------------------------------------------------------------------------------|
| MA-1  | moderate  | Path.kt: `signOfPlaneSide` KDoc + uses | Name implies opposite sign convention to its actual return; `sRev` negation unexplained |
| MA-2  | moderate  | Path.kt: steps 2/3 (lines 168–214)   | 47 lines of hot-path code with unvalidated sign convention; no exercising tests |
| MA-3  | moderate  | Path.kt: KDoc step 4 vs inline "step 5" | Step numbering mismatch between KDoc (1–6) and inline comments (labels plane-side as 5/6) |
| MA-4  | minor     | Path.kt line 298 / IntersectionUtils.kt line 300 | `0.000001` vs `1e-6` notation divergence; no shared constant; separate 1e-9 also unnamed |
| MA-5  | minor     | DepthSorterTest.kt AC-12, AC-13       | Face identification by raw coordinates is brittle; duplicated 8-line geometry queries |
| MA-6  | minor     | DepthSorter.kt line 140               | Workflow slug reference in comment will rot if workflow directory is reorganised |
| MA-7  | minor     | DepthSorter.kt lines 141–148          | `intersects`/`cmpPath` locals retained from reverted logging; origin unclear without comment |
| MA-8  | advisory  | PathTest.kt lines 62–70; DepthSorterTest.kt lines 280, 349 | "round 1/2/3" and "amendment-1" workflow vocabulary in test comments will age poorly |
| MA-9  | advisory  | DepthSorter.kt, Path.kt (overall)     | No lightweight trace path after DEPTH_SORT_DIAG revert; debugging requires re-implementing 47 lines |
| MA-10 | advisory  | Path.kt lines 244–248, 273–276        | KDoc says "coplanar counts as same side" but code treats coplanar as undecided |

---

## Overall Assessment

The Newell cascade rewrite is a substantial improvement over the vote-and-subtract
predicate it replaces: the six-step structure is legible, the KDoc on `closerThan`
is the best documentation this algorithm has ever had in this codebase, and the
companion-object constants (`ISO_ANGLE`, `EPSILON`) are a clear win over scattered
inline literals. The three moderate findings are the most likely to bite a future
maintainer: MA-1 (`signOfPlaneSide`'s inverted-name sign convention) is the highest
cognitive-load trap because the helper is called twice with different sign handling;
MA-2 (steps 2/3 untested with unvalidated sign) is the highest latent-correctness
risk because the code is on the hot path and silently wrong for any future scene that
exercises those steps; MA-3 (KDoc/inline step numbering mismatch) is the most
frequent friction point because it affects every reader who cross-references the KDoc
to understand cascade behaviour. None of the findings require a change before merging;
all are addressable in a polish pass.
