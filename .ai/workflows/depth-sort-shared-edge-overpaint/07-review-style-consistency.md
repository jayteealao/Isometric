---
schema: sdlc/v1
type: review
review-command: style-consistency
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
result: pass-with-nits
metric-findings-total: 2
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 0
metric-findings-low: 0
metric-findings-nit: 2
tags:
  - rendering
  - depth-sort
  - painter-algorithm
  - isometric-core
  - style
refs:
  index: 00-index.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
---

# Style-Consistency Review — depth-sort-shared-edge-overpaint

**Source files reviewed**
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt` (line 140)
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`
- `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt`

---

## ST-1 — NIT: Numeric literal style inconsistency in Path.kt line 140

**Severity:** NIT

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt`, line 140

**Observation:**
The threshold value is written as `0.000001` (decimal literal). The sibling production file
`IsometricProjection.kt` uses scientific notation `1e-10`. The existing test code in
`DepthSorterTest.kt` (lines 182, 187) also uses `1e-9`. The old Path.kt code that was
replaced used `0.000000001` and `-0.000000001` (decimal), and `IntersectionUtils.kt`
line 146 still uses `0.000000001`. So there is a mixed convention in the repo:
production code uses both forms, with `1e-N` present in `IsometricProjection.kt` and the
inline comments of Path.kt itself (`1e-6` appears in the comment on line 105 and 137),
while the actual comparison literal on line 140 is `0.000001`.

**Evidence:**
- `IsometricProjection.kt:61`: `kotlin.math.abs(det) > 1e-10` — scientific notation
- `DepthSorterTest.kt:182,187`: `< 1e-9` — scientific notation
- `IntersectionUtils.kt:146`: `< -0.000000001` — decimal (legacy, unrelated to this change)
- `Path.kt:140`: `>= 0.000001` — decimal, while comment on line 105 says "1e-6"

The comment in the function KDoc (line 105) and the inline comment (line 137) both
describe the value as `1e-6`, but the actual literal uses `0.000001`. This is a minor
internal inconsistency: the comment is clearer than the code.

**Suggestion:**
Change line 140 to use `1e-6` to match the inline comment and the style seen in
`IsometricProjection.kt` and the test files. This is cosmetic only — the values are
numerically identical.

```kotlin
// Before
if (observerPosition * pPosition >= 0.000001) {
// After
if (observerPosition * pPosition >= 1e-6) {
```

---

## ST-2 — NIT: Test factory file name uses workflow prefix in a `scenes/` package

**Severity:** NIT

**File:** `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/scenes/WS10NodeIdScene.kt`

**Observation:**
This is the first file in the `scenes/` test package. No prior naming convention exists
to compare against within that package. However, looking at the sibling runtime test
directory, the closest analogue is `WS6EscapeHatchesTest.kt` — a test-only file that
also carries a `WS<N>` prefix tied to an internal milestone. `WS10NodeIdScene.kt` follows
the same `WS<N>` prefixing pattern seen there, so it is internally consistent with the
one pre-existing precedent.

The mild concern is that `WS10NodeIdScene.kt` is a *test factory/composable* (not a
test class), while the `WS<N>` prefix appears to have originated for test classes
(`WS6EscapeHatchesTest.kt`). A purely descriptive name like `NodeIdScene.kt` or
`SharedEdgeScene.kt` would survive milestone renaming without confusion. This is purely
cosmetic: the file functions correctly as-is.

**Suggestion (optional):**
Consider renaming to `NodeIdScene.kt` when the `scenes/` package grows, to keep scene
factories milestone-agnostic and consistent with any future `scenes/` additions that
won't carry `WS` prefixes.

---

## Checks that passed without findings

**Test naming convention (backtick names):**
All new backtick-named tests in `PathTest.kt`, `DepthSorterTest.kt`, and
`IntersectionUtilsTest.kt` match the established project convention. Existing tests in
`DepthSorterTest.kt`, `PathTest.kt`, `IsometricEngineTest.kt`, and
`WS6EscapeHatchesTest.kt` all use the same backtick style. No inconsistency found.

**Import style:**
All three new/modified test files follow the project's import convention: alphabetical
within groups, standard `kotlin.test.*` / `org.junit.*` / project imports, each import
on its own line, no wildcard imports except the established `io.github.jayteealao.isometric.shapes.*`
pattern in snapshot tests. `IntersectionUtilsTest.kt` uses only four targeted imports,
consistent with its sibling `PathTest.kt` and `CircleTest.kt`. No inconsistency found.

**WS10NodeIdScene.kt import style:**
Five single-line imports, alphabetically ordered within the androidx/isometric/shapes
groups, consistent with the compose test siblings. Passes.
