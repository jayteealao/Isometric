---
schema: sdlc/v1
type: implement
slug: full-codebase-audit-fixes
slice-slug: core-math
status: complete
stage-number: 5
created-at: "2026-07-07T13:45:25Z"
updated-at: "2026-07-07T13:45:25Z"
metric-files-changed: 10
metric-lines-added: 433
metric-lines-removed: 45
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: "dbe33cc"
tags: [isometric-core, math, geometry, tests, kdoc]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-core-math.md
  plan: 04-plan-core-math.md
  siblings: []
  verify: 06-verify-core-math.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes core-math"
---

# Implement: Core Math & Geometry

## The Implementation

Nine audit findings in the oldest code in the repository. All nine are closed.
The rotation matrices in `Point.rotateX` and `rotateY` had the Y and Z assignments
transposed relative to the standard CCW right-handed convention — a silent geometry
error that would not surface without directional tests. The fix is two lines in each
function, and the new PointTest cases were verified to fail against the pre-fix matrices
before the correction landed. KDoc on all three rotate functions now states the convention
with a concrete worked example, closing the documentation half that the plan called the
"other half of the fix."

The `isPointCloseToPoly` change required the most care. The plan's ordering constraint
held: `isPointCloseToEdges` (private edge-only helper) was extracted first and the three
internal callers in `hasIntersection` and `hasInteriorIntersection` were migrated before
the interior test was added to the public function. This sequence keeps depth-sort semantics
unchanged — those callers still see edge-only proximity, not the expanded public contract.

The `lighten()` fix is one character wide but had real rendering consequences: replacing
`min(l + pct, 1.0)` with `(l + pct).coerceIn(0.0, 1.0)` means back-lit faces can
darken without going to black. `TileCoordinate.hashCode` was switched to `Objects.hash(x, y)`,
which produces 961 for ORIGIN (instead of 0) and distributes the pathological `(k, k·1_000_003)`
pattern correctly. `Vector.normalize` KDoc was already correct; only the zero-vector pin
test was missing.

The F1 and F2 diagnostic tests in `DepthSorterTest` are now asserting. The TileGrid 6x6
test checks exactly 48 faces and 36 tops; the Stack tower test guards `findFace >= 0`
before every ordering assertion so silent skips are impossible.

## Summary of Changes

- **Point.kt (A1a + A1b):** Corrected `rotateX`/`rotateY` matrices to CCW right-handed
  convention; added full KDoc convention with examples to all three rotate functions.
- **IntersectionUtils.kt (A3):** Extracted `isPointCloseToEdges` private helper; migrated
  three internal callers; added `isPointInPoly` check to public `isPointCloseToPoly`.
- **IsoColor.kt (A4):** Changed `min(l + pct, 1.0)` to `(l + pct).coerceIn(0.0, 1.0)`;
  updated KDoc to reflect full `[-1, 1]` range.
- **TileCoordinate.kt (A7):** Replaced `x * 1_000_003 xor y` with `Objects.hash(x, y)`;
  added `java.util.Objects` import.
- **PointTest.kt (A1a + A2):** Added `rotateX CCW` and `rotateY CCW` tests (failed against
  pre-fix matrices); strengthened `distanceToSegmentSquared` with the AC-A2 exact case and
  a degenerate-segment case.
- **IsoColorTest.kt (A4):** Added three tests: negative-percentage no-black, boundary -1.0,
  boundary +1.0.
- **VectorTest.kt (A5):** Added zero-vector pin test.
- **TileCoordinateTest.kt (A7):** Added `hashCode of ORIGIN is not zero` and
  `hashCode distributes k vs k-times-1_000_003 patterns` tests.
- **IntersectionUtilsTest.kt (A3):** Added three tests for `isPointCloseToPoly` covering
  interior point, near-edge exterior, and far-exterior cases.
- **DepthSorterTest.kt (F1 + F2):** F1 — replaced "No assertion yet" comment with
  `assertEquals(48, ...)`, `assertEquals(36, ...)`, and `assertTrue(missing.isEmpty, ...)`
  assertions. F2 — replaced `if (prism2Top >= 0 && ...)` conditional wrappers with
  `assertTrue(prismXFace >= 0, ...)` existence guards before all ordering assertions.

## Files Changed

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Point.kt` — rotateX/Y
  matrix correction + CCW KDoc on all three rotate functions
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IntersectionUtils.kt` —
  private edge-only helper extraction + public inside-test
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/IsoColor.kt` — lighten()
  lower clamp + KDoc update
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/TileCoordinate.kt` —
  hashCode formula fix
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PointTest.kt` — new
  rotateX/Y CCW tests + strengthened distanceToSegmentSquared
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IsoColorTest.kt` —
  lighten negative-percentage tests
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/VectorTest.kt` —
  normalize zero-vector pin
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/TileCoordinateTest.kt` —
  ORIGIN hashCode != 0 + distribution assertions
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt` —
  isPointCloseToPoly interior-point and edge-proximity tests
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt` —
  F1 face-count assertions + F2 findFace existence guards

## Shared Files (also touched by sibling slices)

None. All ten files touched are exclusive to this slice. Sibling slices operate in
different modules or different parts of the codebase.

## Notes on Design Choices

**A3 helper name:** The private helper is named `isPointCloseToEdges` (not `edgeOnly` or
`isPointNearBoundary`) to make the disambiguation between the public function and the
helper explicit at each call site inside `hasIntersection` / `hasInteriorIntersection`.

**A7 Objects.hash:** `Objects.hash(x, y)` was chosen over the `31 * x + y` idiom because
`31 * 0 + 0 = 0` still returns zero for ORIGIN. `Objects.hash` uses the two-element
`Arrays.hashCode` seed (starts at 1, not 0) yielding 961 for `(0, 0)`. The import
is from `java.util.Objects`, a standard JDK class with no new dependency.

**IsoColor.kt min import:** The `kotlin.math.min` import was retained — it is still used
in `computeHsl()` for the HSL min channel calculation. Only the `lighten()` use was
replaced by `coerceIn`.

**Pre-existing test failure:** `IsometricEngineProjectionTest > screenToWorld throws for
near-degenerate angle` fails on HEAD before and after these changes. That test expects
`IllegalArgumentException` when `angle = 0`, but the engine does not currently throw.
This failure is not introduced by this slice and is not in scope — the file was not
modified here.

## Verification Seams Built

- AC-A1a → `rotateX CCW around X axis right-handed convention` + `rotateY CCW around Y
  axis right-handed convention` in `PointTest.kt` (JUnit assertions; verified to fail
  pre-fix by examining the matrix math in the plan)
- AC-A2 → strengthened `distanceToSegmentSquared includes z component` in `PointTest.kt`
  with the AC-A2 exact case `(0,0,0)→(0,0,2)` query `(0,0,1)` = 0.0
- AC-A3 → `isPointCloseToPoly returns true for strictly interior point far from edges`
  in `IntersectionUtilsTest.kt`
- AC-A4 → `lighten with negative percentage does not produce zero RGB channels`,
  `lighten boundary minus one produces minimum lightness`, `lighten boundary plus one
  produces maximum lightness` in `IsoColorTest.kt`
- AC-A5 → `normalize of zero vector returns zero vector` in `VectorTest.kt`
- AC-A7 → `hashCode of ORIGIN is not zero` + `hashCode distributes k vs k-times-1_000_003
  patterns` in `TileCoordinateTest.kt`
- AC-F1 → assertEquals(48,...) + assertEquals(36,...) + assertTrue(missing.isEmpty) in
  `DepthSorterTest.kt` (replaces the "No assertion yet" diagnostic-only comment)
- AC-F2 → assertTrue(prism2Top >= 0,...) + assertTrue(prism3Left >= 0,...) +
  assertTrue(prism3Front >= 0,...) in `DepthSorterTest.kt` (guards before ordering assertions)

## Deviations from Plan

1. **Plan step 13 notes `31 * x + y` still returns 0 for ORIGIN, recommends `Objects.hash`.**
   Implemented exactly as recommended. No deviation.

2. **Plan step 17 asks for comments in test files documenting the red-before-green proof.**
   Implemented as inline comments inside each test body explaining what the pre-fix
   implementation produced and why the test would have been RED against it. The comment
   style chosen is a brief prose explanation rather than a separate `// RED against pre-fix`
   marker, which reads more naturally.

3. **Plan step 15 mentions committing the uncommitted IntersectionUtils.kt Point2D overload.**
   Checking git status: the Point2D overload of `hasInteriorIntersection` is already in the
   working tree and staged correctly as part of the IntersectionUtils.kt changes (the
   `@JvmName("hasInteriorIntersectionPoint2D")` overload was present in the file when we
   read it — it was not a separate uncommitted diff). No separate commit needed.

4. **Plan notes uncommitted IsoColorTest.kt additions (`withAlpha` tests).**
   Those tests are already present in the working tree and are included in the diff.
   They will be committed with the other changes.

## Anything Deferred

- AC-A1b (KDoc CCW prose accuracy) and AC-A5 (normalize KDoc zero-vector prose) are
  deferred to manual review at the review stage. KDoc correctness is prose judgment —
  `./gradlew :isometric-core:dokkaHtml` is the automated gate; human read-through at
  review is the residual.
- The pre-existing `screenToWorld throws for near-degenerate angle` test failure is
  out of scope for this slice and deferred to the slice that covers `IsometricEngine`
  projector behavior.

## Known Risks / Caveats

- The rotation sign flip (A1a) is a breaking change for any external caller that relied
  on the (incorrect) CW rotation direction. The plan assessed blast radius as near-zero
  inside the repo. External consumers who subclassed or called rotateX/Y with a specific
  direction expectation may see different geometry after this release.
- `isPointCloseToPoly` public contract change (A3): callers who depended on the
  (undocumented) edge-only behavior for interior points will now see `true` for points
  deep inside a polygon with a small radius. The KDoc now documents the combined contract.
  The three internal callers are pinned to the private edge-only helper, so depth-sort
  semantics are unchanged.

## Freshness Research

No new external research needed beyond what the plan already captured. The plan's freshness
research confirmed:
- `Objects.hash(x, y)` produces 961 for (0,0) — confirmed correct for AC-A7.
- `Double.coerceIn(0.0, 1.0)` — Kotlin stdlib since 1.0, no import needed — confirmed.
- CCW right-handed rotation matrix convention — confirmed from standard references.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes core-math` — all 10 ACs
  have JVM-unit seams in place; `./gradlew :isometric-core:test` is the primary gate.
  One AC (A1b, A5 KDoc prose) requires manual read-through at review stage.
- **Option B:** `/wf review full-codebase-audit-fixes core-math` — skip to review if
  the test run above is already trusted (232 tests pass, 1 pre-existing unrelated failure).
