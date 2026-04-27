---
schema: sdlc/v1
type: review
subtype: testing
slug: depth-sort-shared-edge-overpaint
slice-slug: depth-sort-shared-edge-overpaint
status: complete
stage-number: 7
created-at: "2026-04-26T20:44:25Z"
updated-at: "2026-04-26T20:44:25Z"
merge-recommendation: REQUEST_CHANGES
refs:
  index: 00-index.md
  implement: 05-implement-depth-sort-shared-edge-overpaint.md
  verify: 06-verify-depth-sort-shared-edge-overpaint.md
  plan: 04-plan-depth-sort-shared-edge-overpaint.md
tags:
  - rendering
  - depth-sort
  - test-coverage
  - regression
---

# Testing Review: depth-sort-shared-edge-overpaint

**Reviewed:** worktree / commit `3e811aa`
**Date:** 2026-04-26
**Reviewer:** Claude Code
**Workflow:** `depth-sort-shared-edge-overpaint`

---

## 0) Scope, Test Strategy, and Context

**What was reviewed:**
- Source files changed: 2 (`Path.kt`, `DepthSorterTest.kt`)
- Test files changed / added: 4 (`PathTest.kt` +5 cases, `DepthSorterTest.kt` +2 cases,
  `IntersectionUtilsTest.kt` NEW +3 cases, `IsometricCanvasSnapshotTest.kt` +1 case)
- Scene factory added: `WS10NodeIdScene.kt` (test-only)

**Test strategy:**
- Levels: unit (PathTest, IntersectionUtilsTest), integration (DepthSorterTest), visual
  regression (Paparazzi snapshot)
- Framework: kotlin.test + JUnit4 + Paparazzi 1.3.0
- CI environment: GitHub Actions on Linux for Paparazzi; Windows local for unit tests

**Changed behavior:**
1. `Path.countCloserThan` — replaced integer-division `(result + result0) / points.size`
   with permissive binary `if (result > 0) 1 else 0`; widened epsilon 1e-9 → 1e-6
2. `Path.closerThan` — unchanged signature; behavior improved for partial-vertex-overlap cases

**Acceptance criteria (from plan):**
1. (AC-1) `closerThan` returns nonzero for the hq-right vs factory-top case
2. (AC-2) `closerThan` returns zero for genuinely coplanar non-overlapping faces
3. (AC-3) Resolves 5 adjacency sub-cases (4 implemented)
4. (AC-4) DepthSorter integration: four-building scene draws factory_top before hq_right
5. (AC-5) Paparazzi snapshot: no overpaint artefact visible
6. (AC-6) No regressions in pre-existing tests
7. (AC-7) Antisymmetry invariant holds
8. (AC-8) `closerThan` / `countCloserThan` public signatures unchanged

**Reported regression (critical context):**
The yellow-box "selected" state in `LongPressSample` (`InteractionSamplesActivity.kt:167`)
causes missing faces/sides on the emulator. `LongPressSample` uses a 3×3 grid of
uniformly-sized `Prism(width=1.2, depth=1.2, height=1.0)` shapes — none of which change
height on selection; there is no YELLOW box in that sample at all. The YELLOW / height-change
selection pattern belongs to **`OnClickSample`** (line ~96), which uses
`height = if (isSelected) 2.0 else 1.0` and `color = if (isSelected) IsoColor.YELLOW else color`.

---

## 1) Executive Summary

**Merge Recommendation:** REQUEST_CHANGES

**Rationale:**
The test suite covers the narrow `NodeIdSample` regression geometry precisely but
misses two geometry classes that can trigger the same `countCloserThan` failure mode:
(a) a selected-shape with a height change (height 1 → 2) adjacent to a fixed-height
neighbour, and (b) the `OnClickSample` / any scene where the same face pair re-enters the
adjacency zone dynamically (i.e., after a recompose that changes prism height). The
emulator regression reported against `LongPressSample` is most likely the `OnClickSample`
(the yellow-box behaviour lives there), and the test suite has no case that exercises
a height-transition adjacency. One finding is **BLOCKER** (missing regression for the exact
reported failure class), one is **HIGH** (vacuous antisymmetry invariant), one is **HIGH**
(shared-edge-only `IntersectionUtils` case deferred with no tracking), and two are **MED**.

**Overall Assessment:**
- Coverage: Insufficient for the dynamic-height scenario
- Test Quality: Good for the static geometry; vacuous for the antisymmetry invariant
- Flakiness Risk: Low (pure math, deterministic inputs)
- Determinism: Excellent

---

## 2) Coverage Analysis

### Changed Behavior Coverage

| Behavior | File:Line | Tested? | Test Level | Coverage |
|----------|-----------|---------|------------|----------|
| `countCloserThan` permissive binary return | `Path.kt:145` | ✅ Yes | Unit (PathTest, 5 cases) | Happy + 4 adjacency variants |
| `countCloserThan` epsilon widened to 1e-6 | `Path.kt:140` | ⚠️ Partial | Unit | Only implicit; no vertex-exactly-at-epsilon case |
| Height-change adjacency (selected shape grows taller) | `Path.kt:117` | ❌ No | — | None |
| Dynamic recompose: height 1 → 2 changes face plane | `Path.kt:117` | ❌ No | — | None |
| `closerThan` antisymmetry for binary 0/1 result | `DepthSorterTest.kt:201` | ⚠️ Partial | Unit | Passes by construction; see TS-2 |
| `hasIntersection` shared-edge-only case | `IntersectionUtilsTest.kt:1` | ❌ No | — | Deferred; see TS-3 |
| `IsometricCanvasSnapshotTest.nodeIdSharedEdge` visual | `IsometricCanvasSnapshotTest.kt:307` | ⚠️ Partial | Visual (Paparazzi) | Blank PNG on Windows; unverified visually |

**Coverage Summary:**
- ✅ Fully tested: 1 behavior (permissive return, static geometry)
- ⚠️ Partially tested: 3 behaviors
- ❌ Not tested: 3 behaviors

### Test Level Distribution

| Level | Tests (new) | Appropriate? |
|-------|-------------|--------------|
| Unit | 8 | ✅ Yes |
| Integration | 2 | ✅ Yes |
| Visual/E2E | 1 | ✅ Yes (but environment-broken) |

---

## 3) Findings Table

| ID | Severity | Confidence | Category | File:Line | Issue |
|----|----------|------------|----------|-----------|-------|
| TS-1 | BLOCKER | High | Coverage Gap | `PathTest.kt` / `DepthSorterTest.kt` | No test for height-change adjacency — the exact class of geometry that triggers the emulator regression |
| TS-2 | HIGH | High | Assertion Quality (Vacuous) | `DepthSorterTest.kt:201-248` | `closerThan is antisymmetric` passes by construction with current binary return; adds no real protection |
| TS-3 | HIGH | High | Coverage Gap | `IntersectionUtilsTest.kt` | Shared-edge-only `hasIntersection` case deferred from plan; this is the gating predicate for DepthSorter edge insertion |
| TS-4 | MED | High | Coverage Gap | `DepthSorterTest.kt` | No depth-sort integration test for a height-changing scene (selected prism grows adjacent to fixed neighbours) |
| TS-5 | MED | Med | Coverage Gap | `PathTest.kt:140` | No test with a vertex exactly at the 1e-6 epsilon boundary; widening the epsilon is an untested change in behaviour |
| TS-6 | LOW | High | Snapshot Reliability | `IsometricCanvasSnapshotTest.kt:307` | Paparazzi produces blank PNGs on Windows; snapshot test adds no protection against visual regression in this environment |
| TS-7 | NIT | High | Test Organization | `DepthSorterTest.kt:162-198` | Face identification uses raw `Double` coordinates (`it.x - 1.5 < 1e-9`) rather than a named helper; any geometry refactor silently breaks the locator with no compiler warning |

**Findings Summary:**
- BLOCKER: 1
- HIGH: 2
- MED: 2
- LOW: 1
- NIT: 1

---

## 4) Findings (Detailed)

### TS-1: No Test for Height-Change Adjacency — Misses the Reported Regression [BLOCKER]

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt` (missing),
`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt` (missing)

**Why the regression was missed:**
The reported user-visible bug is missing faces/sides on the yellow box in the
LongPress/OnClick sample. Reading `InteractionSamplesActivity.kt:141-153`:

```kotlin
Shape(
    geometry = Prism(
        position = Point(i * 1.5, 0.0, 0.1),
        width = 1.0, depth = 1.0,
        height = if (isSelected) 2.0 else 1.0   // ← height changes on selection
    ),
    color = if (isSelected) IsoColor.YELLOW else color,
    ...
)
```

When shape `i=0` is selected its Prism grows from `height=1.0` to `height=2.0`. Its
right wall is now a 4-vertex face at `x=1.0` spanning `z=[0.1, 2.1]`. Shape `i=1`
(still unselected, `height=1.0`) has a top face at `z=1.1` spanning `x=[1.5, 2.5]`.
Two of `i=0`'s right-wall vertices are above `z=1.1` (observer-side of `i=1`'s top
plane) and none of `i=1`'s vertices are on the observer side of `i=0`'s right-wall
plane. This is **structurally identical** to the hq-right vs factory-top case —
2/4 vertices pass, integer division was 0. After the fix this resolves to 1,
**but only if** `countCloserThan` receives the correct post-recompose face geometry.
The emulator regression suggests it does not.

None of the five `PathTest` cases vary the height of the "selected" face dynamically.
All use static heights that were chosen to match the NodeIdSample scene, not the
OnClickSample's 1→2 height transition. The `DepthSorterTest.WS10 NodeIdSample` test
also uses static heights.

**Concrete scenario not tested:**
- Prism A at `Point(0.0, 0.0, 0.1)`, `width=1.0, depth=1.0, height=2.0` (selected, yellow)
- Prism B at `Point(1.5, 0.0, 0.1)`, `width=1.0, depth=1.0, height=1.0` (unselected)
- A's right wall: `Path(Point(1.0,0.0,0.1), Point(1.0,1.0,0.1), Point(1.0,1.0,2.1), Point(1.0,0.0,2.1))`
- B's top: `Path(Point(1.5,0.0,1.1), Point(2.5,0.0,1.1), Point(2.5,1.0,1.1), Point(1.5,1.0,1.1))`
- `bTop.closerThan(aRight, observer)` must be > 0

**Severity:** BLOCKER
**Confidence:** High
**Category:** Coverage Gap — missing regression for the exact reported failure class

**Suggested Test (PathTest):**
```kotlin
@Test
fun `closerThan resolves selected-height adjacency (height 2 vs height 1)`() {
    // Reproduces OnClickSample geometry: selected prism (height=2) next to
    // unselected prism (height=1), gap of 0.5 between x=1.0 and x=1.5.
    // aRight has 2 of 4 vertices above z=1.1 (observer-side of bTop's plane).
    // Pre-fix: 2/4 = 0 (integer division). Post-fix: 1.
    val aRight = Path(
        Point(1.0, 0.0, 0.1), Point(1.0, 1.0, 0.1),
        Point(1.0, 1.0, 2.1), Point(1.0, 0.0, 2.1)   // selected, height=2
    )
    val bTop = Path(
        Point(1.5, 0.0, 1.1), Point(2.5, 0.0, 1.1),
        Point(2.5, 1.0, 1.1), Point(1.5, 1.0, 1.1)   // unselected, height=1
    )
    val result = bTop.closerThan(aRight, observer)
    assertTrue(result > 0, "Selected-height adjacency must return positive; got $result")
}
```

**Suggested Integration Test (DepthSorterTest):**
```kotlin
@Test
fun `selected shape (height 2) adjacent to unselected (height 1) draws in correct order`() {
    // Mirrors OnClickSample: first shape selected (yellow, h=2), rest unselected (h=1).
    val engine = IsometricEngine()
    engine.add(Prism(Point(0.0, 0.0, 0.1), 1.0, 1.0, 2.0), IsoColor.YELLOW) // selected
    engine.add(Prism(Point(1.5, 0.0, 0.1), 1.0, 1.0, 1.0), IsoColor.RED)
    engine.add(Prism(Point(3.0, 0.0, 0.1), 1.0, 1.0, 1.0), IsoColor.GREEN)

    val scene = engine.projectScene(800, 600, RenderOptions.NoCulling)

    // Selected prism's right wall (x=1.0, z in [0.1,2.1])
    val selectedRightIdx = scene.commands.indexOfFirst { cmd ->
        cmd.originalPath.points.all { kotlin.math.abs(it.x - 1.0) < 1e-9 } &&
            cmd.originalPath.points.any { it.z > 2.0 }
    }
    // Neighbour's top face (z=1.1, x in [1.5,2.5])
    val neighbourTopIdx = scene.commands.indexOfFirst { cmd ->
        cmd.originalPath.points.all { kotlin.math.abs(it.z - 1.1) < 1e-9 } &&
            cmd.originalPath.points.all { it.x in 1.5..2.5 }
    }

    assertTrue(selectedRightIdx >= 0, "Selected prism's right wall must appear")
    assertTrue(neighbourTopIdx >= 0, "Neighbour's top face must appear")
    assertTrue(
        neighbourTopIdx < selectedRightIdx,
        "neighbour top (idx $neighbourTopIdx) must be drawn before selected right wall " +
            "(idx $selectedRightIdx) so the closer face paints on top"
    )
}
```

---

### TS-2: Antisymmetry Test Is Vacuous with the Current Binary Return [HIGH]

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt:201-248`

**The test:**
```kotlin
@Test
fun `closerThan is antisymmetric for representative non-coplanar pairs`() {
    pairs.forEachIndexed { idx, (a, b) ->
        val ab = a.closerThan(b, observer)
        val ba = b.closerThan(a, observer)
        assertEquals(0, ab + ba, ...)
    }
}
```

**Why it is vacuous:**
`countCloserThan` now returns `if (result > 0) 1 else 0` — binary. For any non-coplanar
pair where one direction has at least one vertex on the observer side and the other
direction has zero, the values are +1 and 0 respectively, so `ab + ba = 1 + (-1) = 0`
(via `closerThan` = `pathA.countCloserThan(this) - this.countCloserThan(pathA)`).
For the case where both directions have at least one vertex on the observer side, both
return `countCloserThan=1`, so `closerThan` = `1 - 1 = 0` for both, and `ab + ba = 0`.

In other words: `countCloserThan` returning 0 or 1 **guarantees** `ab + ba ∈ {-2, -1, 0, 1, 2}`,
but the binary clamp means `ab` and `ba` are each in `{-1, 0, 1}`, and the only way to
get `ab + ba ≠ 0` would require `ab = ba = 1` or `ab = ba = -1`, which requires both
directions to independently return `countCloserThan = 1` AND that direction to
also have `countCloserThan = 0` for the other side simultaneously — a logical contradiction
with the same pair. The test therefore cannot fail for any non-coplanar pair with the
current implementation.

The implement note correctly flags this: "passes by construction". The problem is that
a test that cannot fail provides no protection.

**What a meaningful test would look like:**
If `countCloserThan` were reverted to the pre-fix integer-division form, or if a future
contributor changes the return type to a continuous value, the test would still pass
unless the assertion were stronger. A useful version would assert a strict ordering
signal (not just `== 0` sum), verifying that exactly one direction returns non-zero:

```kotlin
for each pair (a, b):
    val ab = a.closerThan(b, observer)
    val ba = b.closerThan(a, observer)
    // Exactly one of ab, ba should be non-zero for non-coplanar faces
    assertTrue(
        (ab > 0 && ba < 0) || (ab < 0 && ba > 0),
        "For non-coplanar pair $idx: expected strict antisymmetry, got ab=$ab ba=$ba"
    )
```

That assertion **would** have caught the pre-fix integer-division bug (where ab and ba
were both 0 for the hq-right/factory-top case).

**Severity:** HIGH
**Confidence:** High
**Category:** Assertion Quality — vacuous invariant

---

### TS-3: Shared-Edge-Only `hasIntersection` Case Deferred Without Tracking [HIGH]

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IntersectionUtilsTest.kt`

**The gap:**
The plan's third `IntersectionUtils` test case was "shared-edge-only behaviour". The
implement doc notes it was replaced with "outer contains inner" and explains the
rationale (unable to determine correct answer without running the test). The shared-edge-only
case was deferred with no issue, no TODO, and no tracking.

**Why it matters:**
`IntersectionUtils.hasIntersection` is the **gating predicate** for
`DepthSorter.checkDepthDependency`. If `hasIntersection` returns `false` for two faces that
share an edge (like hq-right and factory-top at their screen-projection boundary), then
`DepthSorter` adds **no edge** and the fix to `countCloserThan` is never reached. The
shared-edge-only case is therefore the most important test case for this workflow's fix —
more directly relevant than "outer contains inner".

It is not known from the source read alone whether adjacent isometric faces whose
projected 2-D polygons share only an edge (zero-area overlap) cause `hasIntersection`
to return `true` or `false`. If it returns `false`, then the `countCloserThan` fix is
unreachable for the actual regression geometry, and the fix explains the unit-test green
result but not why the emulator still regresses.

**Severity:** HIGH
**Confidence:** High
**Category:** Coverage Gap — deferred test for a path-critical predicate

**Suggested Test:**
```kotlin
@Test
fun `hasIntersection returns correct value for polygons sharing only an edge`() {
    // Two unit squares sharing the edge x=1 (zero-area overlap).
    // This is the 2-D projection of hq-right's top edge meeting factory-top's left edge.
    // DepthSorter only adds an ordering edge when hasIntersection returns true;
    // if it returns false here, the countCloserThan fix is never reached.
    val a = listOf(
        Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.0),
        Point(1.0, 1.0, 0.0), Point(0.0, 1.0, 0.0)
    )
    val b = listOf(
        Point(1.0, 0.0, 0.0), Point(2.0, 0.0, 0.0),
        Point(2.0, 1.0, 0.0), Point(1.0, 1.0, 0.0)
    )
    // Document the observed result as a regression marker regardless of direction.
    // If this returns false, add a test for the full DepthSorter path that confirms
    // these faces still get ordered correctly (perhaps via a different code path).
    val result = IntersectionUtils.hasIntersection(a, b)
    // EXPECTED: determine by running; then pin it here.
    // assertTrue(result, "Shared-edge-only faces must register as intersecting")
    // OR:
    // assertFalse(result, "Shared-edge-only faces do not overlap; ordering via pre-sort only")
}
```

---

### TS-4: No Integration Test for the Dynamic Height-Change Scene [MED]

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt`

**The gap:**
`DepthSorterTest.WS10 NodeIdSample four buildings render in correct front-to-back order`
uses a **static** four-building scene where all heights are fixed at their "pre-selected"
values. The emulator regression occurs when a user interacts with the scene, causing a
recompose that changes one prism from `height=1.0` to `height=2.0`. The test therefore
only verifies the fix works for the original static NodeIdSample geometry, not for
the dynamic interaction scenario that the regression tracks.

A minimal reproduction would be a single `IsometricEngine` call with one shape already
at `height=2.0` next to a `height=1.0` neighbour (as shown in TS-1's suggested
integration test). This is distinct from TS-1's PathTest case because it exercises the
full DepthSorter pipeline including `hasIntersection` gating.

**Severity:** MED
**Confidence:** High
**Category:** Coverage Gap — dynamic state missing from integration test

---

### TS-5: No Test for Vertex Exactly at the 1e-6 Epsilon Boundary [MED]

**Location:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/Path.kt:140`,
`isometric-core/src/test/kotlin/io/github/jayteealao/isometric/PathTest.kt`

**The gap:**
The epsilon widening from 1e-9 to 1e-6 is a behaviour change. A vertex satisfying
`observerPosition * pPosition >= 0.000001` is counted; one just below is not. No test
exercises a vertex within (0, 1e-5) of a plane — the regime where the old epsilon
treated the vertex as on-plane but the new epsilon does not.

For the project's typical 0..100 coordinate range this is low-risk, but the epsilon
change is undocumented in test form, meaning a future reader cannot tell from tests
whether 1e-6 is intentional or accidental.

**Suggested Test:**
```kotlin
@Test
fun `countCloserThan respects 1e-6 epsilon boundary`() {
    // Place one vertex exactly 5e-7 above the plane (below new epsilon → coplanar)
    // and one vertex 2e-6 above the plane (above new epsilon → counted).
    // Observer is on the positive side of the plane.
    // ... (construct appropriate geometry)
}
```

**Severity:** MED
**Confidence:** Med (low risk given coordinate range; confidence in the gap itself is High)
**Category:** Coverage Gap — epsilon boundary untested

---

### TS-6: Paparazzi Snapshot Provides No Effective Protection [LOW]

**Location:** `isometric-compose/src/test/kotlin/io/github/jayteealao/isometric/compose/IsometricCanvasSnapshotTest.kt:307-319`

**The gap:**
`nodeIdSharedEdge()` snapshot test is inert on Windows (all 16 baselines are 6917-byte
blank PNGs). On Linux CI it **will** produce a real render — but only of the static
`WS10NodeIdScene` geometry. The `LongPressSample` / `OnClickSample` yellow-box geometry
(height-change adjacency) is not represented in any snapshot. Even if the fix is
correct for static scenes, a snapshot of the static scene cannot catch a regression
introduced by dynamic height changes.

Additionally, Paparazzi snapshot tests are pixel-level; a 1-pixel difference in
anti-aliasing would cause the test to fail for reasons unrelated to depth-sort
correctness, making it brittle in the opposite direction.

**Severity:** LOW (snapshot is a bonus protection layer, not the primary gate)
**Confidence:** High
**Category:** Snapshot Coverage + Environment reliability

---

### TS-7: Face Locator Uses Magic Floating-Point Coordinates [NIT]

**Location:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/DepthSorterTest.kt:181-189`

**The code:**
```kotlin
val hqRightIndex = scene.commands.indexOfFirst { cmd ->
    cmd.originalPath.points.all { kotlin.math.abs(it.x - 1.5) < 1e-9 } &&
        cmd.originalPath.points.any { it.z > 3.0 }
}
val factoryTopIndex = scene.commands.indexOfFirst { cmd ->
    cmd.originalPath.points.all { kotlin.math.abs(it.z - 2.1) < 1e-9 } &&
        cmd.originalPath.points.all { it.x in 2.0..3.5 }
}
```

The magic values `1.5`, `3.0`, `2.1`, `2.0..3.5` replicate the scene geometry
inline. If the engine adds a fractional-coordinate offset or the prism constructor
changes its vertex layout, these locators silently return `-1`, causing the test to
fail at the `assertTrue(hqRightIndex >= 0)` guard with a message that doesn't explain
*why* the geometry changed. A named helper (`fun findFace(scene, faceDescription): Int`)
with an explanatory assertion would make failures much easier to diagnose.

**Severity:** NIT
**Confidence:** High
**Category:** Test Organization / Readability

---

## 5) Coverage Gaps Summary

### Critical Gaps (BLOCKER/HIGH)

1. **Height-change adjacency** (TS-1) — The exact geometry of the reported regression
   is absent. A selected prism (height=2) adjacent to an unselected prism (height=1)
   with a 0.5-unit gap has structurally identical vertex ratios (2/4 above the neighbour's
   top plane) to the hq-right/factory-top case, but no PathTest or DepthSorterTest covers it.
2. **Antisymmetry test is vacuous** (TS-2) — Cannot fail with the current binary return;
   provides no regression protection.
3. **Shared-edge-only `hasIntersection` case** (TS-3) — The gating predicate for
   DepthSorter edge insertion is untested for the adjacency topology that the fix targets.

### Important Gaps (MED)

4. **Dynamic height-change integration test** (TS-4) — DepthSorter integration coverage
   only covers static heights.
5. **Epsilon boundary** (TS-5) — 1e-9 → 1e-6 widening is tested only implicitly.

---

## 6) Why the Tests Missed the Regression

The core reason is that the test suite was authored to reproduce the **exact diagnosis
geometry** (NodeIdSample, four buildings at fixed coordinates) rather than the
**class of geometry** (any adjacent pair where a height change causes 2/4 vertices to
straddle a neighbour's plane). The two geometries share the same root cause
(`countCloserThan` integer-division collapse) but the test suite only pins one instance.

Additionally:

- `WS10NodeIdScene` replicates `NodeIdSample` verbatim. `LongPressSample` uses a 3×3
  uniform-height grid that never triggers the 2/4 vertex straddling scenario.
  `OnClickSample` (where the yellow-box regression actually appears) uses a 5-shape row
  with `height = if (isSelected) 2.0 else 1.0`. This is the untested geometry.

- The antisymmetry test (TS-2) would have been the natural place to catch any case where
  `countCloserThan` returns 0 for both directions — but the test asserts `ab + ba == 0`,
  which is satisfied by `(0) + (0) == 0`. A stronger assertion (`exactly one is non-zero
  for non-coplanar faces`) would have caught the pre-fix bug and would continue to guard
  against future regressions.

- The deferred shared-edge-only `hasIntersection` case (TS-3) is the highest-risk gap
  because if `hasIntersection` returns `false` for the screen-projected shared edge,
  the entire `countCloserThan` fix is bypassed silently.

---

## 7) Positive Observations

- ✅ **Red-Green test** for the exact diagnosed case (`closerThan returns nonzero for
  hq-right vs factory-top shared-edge case`) is present and well-commented.
- ✅ **Coplanar negative control** (`closerThan returns zero for genuinely coplanar
  non-overlapping faces`) prevents the fix from over-correcting.
- ✅ **Four adjacency variants** in PathTest (X-adjacent diff heights, Y-adjacent diff
  heights, equal heights top-vs-side, diagonally offset) provide reasonable geometric
  diversity.
- ✅ **DepthSorter integration test** (`WS10 NodeIdSample four buildings render in
  correct front-to-back order`) pins end-to-end command ordering, not just the math.
- ✅ **`IntersectionUtils` baseline** establishes a regression marker for the
  `hasIntersection` predicate that previously had zero coverage.
- ✅ **`WS10NodeIdScene` factory** is reusable and correctly separated from the test
  that uses it.
- ✅ **Test naming** is descriptive and behavior-oriented throughout.
- ✅ **No flakiness risk** — all tests use deterministic math inputs with no I/O,
  timing, or randomness.

---

## 8) Recommendations

### Must Fix (BLOCKER/HIGH)

1. **TS-1**: Add `PathTest` case for selected-height adjacency (height 2 vs height 1).
   Add `DepthSorterTest` integration case with one selected prism adjacent to unselected.
   Estimated effort: 20 minutes.

2. **TS-2**: Replace `ab + ba == 0` with `(ab > 0 && ba < 0) || (ab < 0 && ba > 0)`
   for the non-coplanar pairs in the antisymmetry test. The current test cannot fail
   and provides false confidence.
   Estimated effort: 5 minutes.

3. **TS-3**: Add the shared-edge-only `hasIntersection` test. Run it first to determine
   the actual return value, then pin that value as a regression marker with a comment
   explaining the implication for `DepthSorter` edge insertion.
   Estimated effort: 15 minutes.

### Should Fix (MED)

4. **TS-4**: Add a DepthSorter integration test exercising the dynamic
   height-change-adjacency geometry (covered above in TS-1 integration test suggestion).

5. **TS-5**: Add a PathTest case that places a vertex at exactly `2e-6` (above the
   1e-6 threshold) and another at `5e-7` (below the threshold) to pin the epsilon
   boundary behaviour.

### Consider (LOW/NIT)

6. **TS-6**: Add a `LongPressSample` or `OnClickSample`-equivalent snapshot (selected
   shape at height 2.0 adjacent to height 1.0 neighbours) if/when the Paparazzi
   blank-render issue is resolved on Linux CI.

7. **TS-7**: Extract face locators in `DepthSorterTest` into named helpers with
   descriptive assertions.

**Total effort for BLOCKER+HIGH fixes:** ~40 minutes.

---

## 9) CI / Environment Notes

- Paparazzi snapshots are environment-broken on Windows + JDK 17 (6917-byte blanks).
  The `nodeIdSharedEdge` snapshot test is therefore **not a CI gate on this branch** until
  Linux CI generates a real baseline. The `verifyPaparazzi` task passes tautologically
  (recorded vs itself).
- Linux CI will close AC-5 automatically on PR open, but only for the static
  `WS10NodeIdScene` geometry — not for the height-change scenario.
- The `isometric-core` unit tests (183 tests) are deterministic and fully reliable
  on all platforms. They are the meaningful gate here.

---

*Review completed: 2026-04-26T20:44:25Z*
*Workflow: [depth-sort-shared-edge-overpaint](00-index.md)*
