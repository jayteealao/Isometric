---
schema: sdlc/v1
type: review
review-command: style-consistency
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-28T00:00:00Z"
updated-at: "2026-04-28T00:00:00Z"
result: pass-with-nits
metric-findings-total: 11
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 0
metric-findings-low: 1
metric-findings-nit: 10
tags:
  - style-consistency
  - kotlin
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Style-Consistency Review — depth-sort-shared-edge-overpaint

**Scope:** cumulative diff `97416ba..HEAD` on `feat/ws10-interaction-props`.
**Lines reviewed:** ~1284 lines of Kotlin across `isometric-core/main`,
`isometric-core/test`, and `isometric-compose/test`.

**Source files reviewed**

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/DepthSorter.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/AlphaSampleScene.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/LongPressGridScene.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/OnClickRowScene.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`

**Comparison baseline:** `Point.kt`, `Vector.kt`, `Prism.kt`, `Circle.kt`,
`IntersectionUtils.kt` (pre-existing portions), `IsometricEngineTest.kt`,
`VectorTest.kt`, `PointTest.kt`, `WS6EscapeHatchesTest.kt`.

---

## ST-1 — NIT: `EPSILON` literal uses decimal form; all other tolerances in the file use scientific notation

**Severity:** NIT
**File:** `Path.kt` line 298

```kotlin
// current
private const val EPSILON: Double = 0.000001
// sibling uses
private const val EDGE_BAND: Double = 1e-6           // IntersectionUtils.kt:300
// IsometricProjection.kt:61
require(kotlin.math.abs(det) > 1e-10)
```

The companion-object KDoc for `EPSILON` (line 295) already describes the value as
`1e-6` in prose. The only file in the codebase that uses decimal-spelled-out literals
for tolerances is `IntersectionUtils.hasIntersection` (pre-existing, line 146:
`< -0.000000001`) — itself a candidate for its own cleanup. Every other comparison
literal in new code, in test code, and in `IsometricProjection.kt` uses scientific
notation. Writing `0.000001` while the comment says `1e-6` creates a comment-code
mismatch that is slightly confusing at a glance.

**Suggestion:** Change to `private const val EPSILON: Double = 1e-6`.

---

## ST-2 — NIT: `private companion object { private const val … }` double-private redundancy

**Severity:** NIT
**File:** `Path.kt` lines 284–299

```kotlin
private companion object {
    private const val ISO_ANGLE: Double = PI / 6.0
    private const val EPSILON: Double = 0.000001
}
```

The outer `private` on the companion object already restricts it to the `Path` class.
The inner `private` on the constants is therefore redundant — the members cannot be
any more private than the enclosing object already is. Every other `companion object`
in the codebase (`Point`, `Vector`, `Prism`, `Circle`, `Prism`, `IsoColor`, etc.) is
declared without a visibility modifier (implicitly `public`) and applies `private` only
on members that should be hidden. The established pattern is:

```kotlin
companion object {
    private fun createPaths(…) { … }   // private member, public object
}
```

Using `private companion object` is syntactically valid but unusual in this codebase
and slightly misleading — a reader may wonder whether the `private` on the object or
the `private` on the constants is doing the work.

**Suggestion:** Remove the `private` modifier from the companion object declaration so
the file matches all other sibling classes. The constants themselves should remain
`private const val` to preserve the intent of keeping them non-public.

---

## ST-3 — NIT: `ISO_ANGLE = PI / 6.0` in a companion object vs top-level constant pattern in `Circle.kt`

**Severity:** NIT
**File:** `Path.kt` line 290; comparison: `Circle.kt` line 34

`Circle.kt` embeds `2 * PI / vertices` inline in the computation rather than naming a
constant, because the expression is single-use and self-explanatory. `IntersectionUtils.kt`
places `EDGE_BAND` as a top-level `private const` directly in the object body (line 300),
not inside a nested companion.

The idiom used in `Path.kt` (a `private companion object` carrying two named constants)
is the only instance of this pattern in the codebase. It is not wrong, but it diverges
from the sibling pattern. `IntersectionUtils.kt` style — a `private const val` at the
object level — would be more consistent here.

Since `Path` is not a Kotlin `object` but a class, the most natural alternative is a
file-level `private const val`:

```kotlin
// top of file, below imports
private const val ISO_ANGLE: Double = PI / 6.0
private const val EPSILON: Double = 1e-6
```

This matches the pattern in `IntersectionUtils.kt` (top-level-in-object) while also
being the conventional Kotlin idiom for file-scoped private constants in non-object
classes.

**Suggestion (optional):** Move `ISO_ANGLE` and `EPSILON` to file-level private
constants. This is purely cosmetic — there is no behavioral difference.

---

## ST-4 — NIT: `signOfPlaneSide` is a `private fun` instance member; naming and placement diverge from sibling helpers

**Severity:** NIT
**File:** `Path.kt` lines 257–282

The helper `signOfPlaneSide` is a `private` instance method on `Path`. In the rest of
the codebase, non-public helpers are placed either:

- as `private fun` top-level functions in `object`s (`IntersectionUtils.kt`: `min`, `max`),
- as `private` companion functions (`Prism.kt`: `createPaths`), or
- as extension functions on data holders (`DepthSorter.kt`: `TransformedItem.getBounds`).

There is no established pattern for `private` instance methods on non-data classes, so
this is not a violation; it is the most natural place for a method that reads `this.points`
and takes another `Path`. The naming follows the project's `verbOfNoun` camelCase
convention (`signOfPlaneSide`, `hasInteriorIntersection`, `fromTwoPoints`, etc.).

No action required, but worth noting: if `signOfPlaneSide` is ever needed outside
`Path`, moving it to a `companion object` static helper would match the `createPaths`
pattern in `Prism.kt`.

---

## ST-5 — LOW: `DepthSorterTest` contains a test with `println` debug output

**Severity:** LOW
**File:** `DepthSorterTest.kt` lines 108–158

The test `` `diagnostic - face count and top face presence with culling` `` contains
multiple `println` calls that produce console output on every test run. Every other test
in the file (`PathTest.kt`, `IntersectionUtilsTest.kt`, `VectorTest.kt`, `PointTest.kt`)
is assertion-only with no side-effectful output.

The test name itself contains `diagnostic -`, which is not used anywhere else in the
test suite. While this may have been intentionally left for debugging the regression, it
deviates from the pure-assertion style established in sibling files. The `println`s
produce output that clutters CI logs without being observable by a failing assertion.

**Suggestion:** Either remove the `println` calls and rely on assertion messages, or
mark the test with a comment and consider removal when the diagnostic is no longer
needed.

---

## ST-6 — NIT: `DepthSorterTest` test names mix `WS<N>` prefix with plain descriptive style

**Severity:** NIT
**File:** `DepthSorterTest.kt` lines 161, 278, 346, 425

Four tests use a `WS10` prefix in their backtick names:

```
`WS10 NodeIdSample four buildings render in correct front-to-back order`
`WS10 LongPress 3x3 grid back-right cube vertical faces are not drawn first`
`WS10 LongPress full scene back-right cube vertical faces draw after ground top`
`WS10 Alpha full scene each CYAN prism vertical faces draw after ground top`
```

The other new tests in the same file (`coplanar adjacent prisms produce deterministic order`,
`cycle fallback includes all items`, `kahn algorithm preserves existing broad phase sparse test`,
`closerThan is antisymmetric for representative non-coplanar pairs`) and all tests in
`PathTest.kt`, `IntersectionUtilsTest.kt`, and sibling test files do not use `WS<N>`
prefixes. `WS6EscapeHatchesTest.kt` (the one existing precedent) uses it only in the
*class name*, not the test-method names.

Workflow identifiers in test names create two problems: they are only meaningful to
readers familiar with the internal milestone system, and they become stale as bugs are
fixed. Descriptive names survive longer. The test-method bodies already contain detailed
comments explaining the regression class.

**Suggestion:** Remove the `WS10` / `WS10 LongPress` / `WS10 Alpha` prefix from the
four backtick test names, leaving just the descriptive part.

---

## ST-7 — NIT: `DepthSorterTest` mixes `observer` as a test-level field in `PathTest` vs inline local in `DepthSorterTest`

**Severity:** NIT
**File:** `PathTest.kt` line 73; `DepthSorterTest.kt` lines 205, 241

`PathTest.kt` declares `private val observer = Point(-10.0, -10.0, 20.0)` as a class-level
field (line 73). `DepthSorterTest.kt` uses `val observer = Point(-10.0, -10.0, 20.0)` as
a local inside `` `closerThan is antisymmetric…` `` (line 205) — an inline local, not
shared across the class. In `DepthSorter.sort` itself, the observer is defined inline as
a local `val observer = Point(-10.0, -10.0, 20.0)` on line 37.

This creates three separate definitions of the same value with no named constant to link
them. If the observer position changes, all three must be updated independently. The
`PathTest.kt` approach (a class-level private field) is the most DRY for a test class;
`DepthSorterTest` could follow the same pattern.

**Suggestion:** Extract `val observer = Point(-10.0, -10.0, 20.0)` to a class-level
`private val` in `DepthSorterTest` to match `PathTest`'s convention and eliminate the
duplicate local declaration.

---

## ST-8 — NIT: `kotlin.math.abs` used without import in `DepthSorterTest`

**Severity:** NIT
**File:** `DepthSorterTest.kt` lines 182, 187, 318–327, 387–389, 397–406, 455–457, 468–477

Every occurrence of `abs` in `DepthSorterTest.kt` is spelled `kotlin.math.abs(…)` with
the fully-qualified path. The sibling files that call `abs` — `PointTest.kt` (line 29)
and `IsometricEngineProjectionTest.kt` (line 4) — import `kotlin.math.abs` at the top
of the file and call it unqualified as `abs(…)`.

The fully-qualified form is not wrong, but it adds visual noise at every callsite and
diverges from the import-at-top style established in sibling test files. The imports
section of `DepthSorterTest.kt` currently only has three lines (shapes, `kotlin.test.Test`,
`kotlin.test.assertEquals`, `kotlin.test.assertTrue`) and has room for an `abs` import.

**Suggestion:** Add `import kotlin.math.abs` to `DepthSorterTest.kt` and replace the
fully-qualified callsites with plain `abs(…)`.

---

## ST-9 — NIT: `IntersectionUtils.hasIntersection` pre-existing literal `-0.000000001` inconsistent with new `1e-6` / `1e-9` notation

**Severity:** NIT
**File:** `IntersectionUtils.kt` lines 146, 267

These two lines were present before this diff and are unchanged:

```kotlin
if (side1a * side1b < -0.000000001 && side2a * side2b < -0.000000001) {
```

The new `hasInteriorIntersection` function added in this diff copies the same decimal
literal at line 267 rather than normalising to `1e-9`. The KDoc for
`hasInteriorIntersection` (lines 186, 190) describes the threshold as `1e-9` and `1e-6`
in prose, creating the same comment-code mismatch as ST-1. The literal `0.000000001`
appears only in these two places across all production code; everything else uses
scientific notation.

**Suggestion:** Change both `-0.000000001` literals to `-1e-9` in both
`hasIntersection` and `hasInteriorIntersection` for consistency. The `hasIntersection`
change is outside the strict diff scope but is immediately adjacent code touched by this
workflow.

---

## ST-10 — NIT: Scene factory `AlphaSampleScene.kt` CYAN prism coordinates differ from `DepthSorterTest` CYAN prism coordinates

**Severity:** NIT
**File:** `AlphaSampleScene.kt` lines 37–39; `DepthSorterTest.kt` lines 441–443

The `AlphaSampleScene` factory places the three CYAN prisms at:

```kotlin
Prism(Point(3.5, 3.0, 0.1), 0.6, 0.6, 0.8)
Prism(Point(4.3, 3.0, 0.1), 0.6, 0.6, 1.2)
Prism(Point(5.1, 3.0, 0.1), 0.6, 0.6, 1.6)
```

The `DepthSorterTest` test `` `WS10 Alpha full scene each CYAN prism vertical faces draw after ground top` `` (lines 441–443) hard-codes different coordinates:

```kotlin
Triple(3.5, 3.0, 0.8),
Triple(4.3, 3.0, 1.2),
Triple(5.1, 3.0, 1.6)
```

These happen to match, but the test builds its own `Prism` instances directly (using
`Point(x, y, 0.1)` with `1.0` width/depth) rather than calling `AlphaSampleScene()`.
The scene factory uses `0.6, 0.6` width/depth while the test uses the default `1.0, 1.0`.
This means the integration test does not actually exercise the same geometry as the scene
factory it is named after.

The inconsistency is currently benign — the wall-vs-floor sort still fires correctly for
both geometries — but it could mask a future regression where the scene factory's narrower
prisms behave differently from the test's wider ones.

**Suggestion:** The test `` `WS10 Alpha full scene …` `` should either call
`AlphaSampleScene()` through the engine (as `WS10 NodeIdSample …` does), or its prism
dimensions should match the factory (width=0.6, depth=0.6). At minimum, add a comment
noting the dimensional mismatch so a future maintainer who changes the scene factory
also updates the test.

---

## ST-11 — NIT: `WS10NodeIdScene.kt` uses `WS10` prefix; sibling scene factories do not

**Severity:** NIT
**File:** `isometric-compose/src/test/kotlin/…/scenes/WS10NodeIdScene.kt`

The three other scene factories in the same package use purely descriptive names:
`AlphaSampleScene.kt`, `LongPressGridScene.kt`, `OnClickRowScene.kt`. `WS10NodeIdScene.kt`
is the only one carrying a milestone prefix. Within the `scenes/` package this creates
an inconsistency: `WS10NodeIdScene` reads like a versioned artifact while the others read
like stable geometric descriptions. The `IsometricCanvasSnapshotTest` test that calls it
is named `nodeIdRowScene()` (no `WS10` prefix), and the function it calls is
`WS10NodeIdScene()`, so the caller and callee have inconsistent naming.

**Suggestion:** Rename to `NodeIdScene.kt` / `fun IsometricScope.NodeIdScene()`, updating
the snapshot test import and call site. This is cosmetic only.

---

## Checks that passed without findings

**Backtick test-name style:** All new tests in `PathTest.kt`, `DepthSorterTest.kt`, and
`IntersectionUtilsTest.kt` use backtick-quoted descriptive sentence names, matching the
project-wide convention established in `IsometricEngineTest.kt`, `VectorTest.kt`,
`PointTest.kt`, etc.

**KDoc shape:** New KDocs in `Path.kt` (class-level doc, `closerThan`, `signOfPlaneSide`),
`IntersectionUtils.hasInteriorIntersection`, and scene factory files all follow the
established pattern: single-sentence summary, blank-line paragraph body, `@param` /
`@return` tags. Block-comment code examples use the `// code` inline style consistent
with the rest of the codebase. No `@see` tag misuse observed.

**Import ordering:** All new/modified files order imports alphabetically within groups
(android/compose, project, kotlin stdlib) and avoid wildcard imports except the
pre-existing `io.github.jayteealao.isometric.shapes.*` in snapshot tests.

**`if` vs `when` idiom:** `Path.signOfPlaneSide` correctly uses `when` for the
three-branch Boolean tuple (lines 277–281), matching the project idiom. The outer cascade
in `closerThan` uses sequential `if` early-returns rather than a `when`, which is the
natural Kotlin form for a multi-step cascade where each branch terminates the function.
No inconsistency.

**Brace placement and line length:** All new code uses K&R brace style consistent with
the rest of the codebase. No lines observed that significantly exceed the ~120-character
soft limit visible in sibling files.

**`@file:OptIn` placement:** All four new scene factories and the updated snapshot test
use `@file:OptIn(io.github.jayteealao.isometric.ExperimentalIsometricApi::class)` as the
first line, consistent with `IsometricCanvasSnapshotTest.kt`. The unrelated compose test
files in `runtime/` omit `@file:OptIn` because they target non-experimental APIs —
consistent.

**Receiver type consistency across scene factories:** All four factories declare their
receiver as `IsometricScope` (`fun IsometricScope.AlphaSampleScene()`, etc.), which is the
correct and only public scope interface in the runtime. The plan mentioned `IsometricSceneScope`
as a target type, but that type does not exist in the production API; `IsometricScope` is
correct and consistent across all four files.

**Parameter naming in `closerThan`:** The parameter `pathA` follows the same naming
convention as the pre-existing `countCloserThan` predecessor. The `observer` parameter
name is consistent with its usage in `DepthSorter.sort`.

**Comment style:** Inline comments use `//` consistently; no `/* … */` block comments
are used for single-line remarks. The long explanatory block in `PathTest.kt` uses
`// ---` section delimiters which appear in at least one other test file
(`IntersectionUtilsTest.kt`). Consistent.

---

## Summary

The diff is well-styled overall; all major conventions (test-name backticks, KDoc shape,
brace placement, `when` vs `if`, import ordering, receiver types) are consistent with the
rest of the codebase. Ten NITs and one LOW were found. The LOW (ST-5, `println` in a
production test) is the most actionable item: it produces noise in CI output on every test
run. The most pervasive NIT cluster is numeric-literal notation (ST-1, ST-9): both
`Path.EPSILON` and the two `IntersectionUtils` edge-crossing thresholds spell out
`0.000001` / `0.000000001` while every newer tolerance in the codebase and the KDocs
themselves use scientific notation. ST-6 (`WS10` prefix in test-method names) and ST-11
(`WS10NodeIdScene.kt` file name) are cosmetic but reduce discoverability for future
maintainers. ST-10 (geometry mismatch between `AlphaSampleScene` and its corresponding
`DepthSorterTest`) is the only finding that carries a latent correctness risk, though it
is benign today.
