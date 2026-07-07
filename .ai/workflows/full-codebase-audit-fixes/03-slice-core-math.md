---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: core-math
status: defined
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: l
depends-on: []
tags: [isometric-core, math, geometry, tests, kdoc]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-gesture-coordination.md, 03-slice-compose-contracts.md, 03-slice-view-module.md, 03-slice-shape-geometry.md, 03-slice-docs-and-changelog.md, 03-slice-snapshot-sweep-gate.md]
  plan: 04-plan-core-math.md
  implement: 05-implement-core-math.md
  implement-index: 05-implement.md
---

# Slice: Core Math & Geometry

## The Slice

Nine of the twenty-eight audit items live in the oldest code in the repo, and this slice
burns down all of them at once: the transposed `rotateX`/`rotateY` matrices, the 2D/3D mix
in segment distance, the `isPointCloseToPoly` KDoc lie, the missing lightness clamp that
paints back-lit faces black, the `normalize()` contract drift, the first-three-vertices
winding shortcut in `cullPath`, the degenerate `TileCoordinate.hashCode`, and the two
DepthSorter diagnostic tests that cannot fail. It goes first because everything else in the
library renders *through* this code — and because the codebase scan showed the scariest item
(the rotation flip, a breaking change) has near-zero internal blast radius: nothing in the
repo depends on the current direction, so the real work is writing the tests that were never
written.

Two structural decisions shape the work. First, `isPointCloseToPoly` gains its promised
inside-test only on the *public* function — the three internal callers
(`hasIntersection`, `hasInteriorIntersection`, `HitTester`) move to a private edge-only
helper first, so the fix cannot ripple into depth-sorting. Second, the F1/F2 test
conversions land before or with the `cullPath` change, because those DepthSorter scenarios
are the natural regression net for exactly that change — fixing the net before walking the
wire.

The standing rule for every behavioral fix here: the new test must **fail against the
pre-fix implementation** before the fix lands. That constructive proof is what separates
this sweep from the vacuous tests it is replacing. The one risk worth naming: the rotation
flip is only half the fix — the documented CCW/right-handed convention in KDoc is the other
half, and shipping the sign change without the contract just relocates the ambiguity.

## Goal

All Zone A findings (A1–A7) and the core-module test-rigor findings (F1, F2) fixed with
regression tests that provably observe each defect; the rotation convention documented in
KDoc on all three rotate functions.

## Why This Slice Exists

Core math is the foundation every module renders through; fixing it first means every later
slice verifies against correct geometry. The F1/F2 conversions belong here because the
shape's sequencing note pins them to the A6 culling change they guard.

## Scope

- **In:** `Point.kt` (rotateX/rotateY flip + KDoc convention on all three rotate functions;
  full-3D `distanceToSegmentSquared`/`distanceToSegment`), `IntersectionUtils.kt` (private
  edge-only helper + public inside-test), `IsoColor.kt` (`lighten` lightness clamp),
  `Vector.kt` (`normalize` KDoc), `IsometricProjection.kt` (`cullPath` full shoelace),
  `TileCoordinate.kt` (hashCode), `DepthSorterTest.kt` (F1 assertions, F2 existence guards),
  new/strengthened cases in PointTest, VectorTest, IsoColorTest, PathTest,
  TileCoordinateTest, IntersectionUtilsTest.
- **Out:** shape geometry fixes (→ `shape-geometry`); anything Compose or View
  (→ `gesture-coordination`, `compose-contracts`, `view-module`); .mdx/CHANGELOG entries
  describing these fixes (→ `docs-and-changelog`); snapshot re-record (→
  `snapshot-sweep-gate`).

## Acceptance Criteria

- Given `Point(0,1,0)` When `rotateX(ORIGIN, π/2)` Then result is `(0,0,1)`; Given
  `Point(0,0,1)` When `rotateY(ORIGIN, π/2)` Then result is `(1,0,0)` (CCW, right-handed);
  the existing rotateZ test passes unchanged. New PointTest cases MUST fail against the
  pre-fix implementation. (AC-A1a)
  <!-- observable: false — exact-value unit assertions fully prove the rotation matrices; no live runtime involved -->
- KDoc on `rotateX`/`rotateY`/`rotateZ` states the CCW/right-handed convention for positive
  angles. (AC-A1b)
  <!-- observable: true — a library consumer reads this contract; prose accuracy is human-judged -->
  verify: { method: human read-through at review stage + dokka builds clean, env: source KDoc (local Gradle), fixture: n/a, rung: manual-review (residual — prose judgment) }
- Given segment `(0,0,0)→(0,0,2)` and query `(0,0,1)` When `distanceToSegmentSquared` Then
  `0.0`; PointTest:97's "includes z" case strengthened so it fails against the old 2D/3D-mix
  implementation; degenerate segment (v == w) still returns distance-to-point. (AC-A2)
  <!-- observable: false — pure-function assertions; no runtime -->
- Given a point strictly interior to a large polygon and far from all edges When
  `isPointCloseToPoly` Then true; AND all existing IntersectionUtilsTest + DepthSorterTest
  cases pass unchanged with internal callers pinned to the private edge-only helper. (AC-A3)
  <!-- observable: false — unit + regression suites fully cover both the new public contract and the pinned internal semantics -->
- Given `IsoColor(10,10,80)` and `lighten(-0.20, WHITE)` Then no channel is zero-clamped
  from negative lightness (darker blue, not black); property over percentage ∈ [-1,1]:
  channels equal hslToRgb of coerceIn(0,1) lightness; boundaries ±1.0, l=0, l=1 covered. (AC-A4)
  <!-- observable: false — property-based unit assertions; no runtime -->
- `normalize()` KDoc states the zero-vector return; VectorTest pins
  `Vector(0,0,0).normalize() == Vector(0,0,0)`. (AC-A5)
  <!-- observable: false — the pin test proves the behavior; the one-line KDoc correction is checked in the same review pass as AC-A1b -->
- Given a concave polygon whose first-triangle winding disagrees with its full shoelace
  winding When `cullPath` Then the shoelace verdict wins; PathTest CW/CCW/concave cases
  updated; a Stairs-derived regression case added; KDoc notes simple polygons are the
  contract (self-intersecting = undefined-but-stable). (AC-A6)
  <!-- observable: false — geometric unit assertions; the mis-culled Stairs case is reproducible as a pure-function test -->
- `TileCoordinate.hashCode` no longer returns 0 for ORIGIN and distributes `(k, k*1_000_003)`
  patterns; existing equals/hashCode contract tests pass. (AC-A7)
  <!-- observable: false — hash-distribution assertions; no runtime -->
- The 6x6 TileGrid diagnostic test asserts expected face presence and fails if any tile
  top/side face is missing. (AC-F1)
  <!-- observable: false — the criterion is about test rigor itself; provable by mutating the sort and watching it fail -->
- Tower tests assert `findFace(...) >= 0` before ordering assertions — no vacuous pass
  path remains. (AC-F2)
  <!-- observable: false — same: assertion-structure criterion, provable in JVM tests -->

## Dependencies on Other Slices

- None. This slice is the recommended starting point.

## Risks

- **A3 ripple:** the inside-test on the public function changes hit-testing semantics for
  any external caller relying on the (broken) edge-only behavior — accepted; the private
  helper protects the three internal call sites, and the full IntersectionUtils/DepthSorter
  suites are the regression gate.
- **A1 convention half-fix:** flipping signs without landing the KDoc convention in the same
  commit just moves the ambiguity — the flip and the contract are one unit of work.
- **F1/F2 conversions may surface real regressions** (that is their purpose); if the
  strengthened assertions fail against current code, that is a finding to fix, not to relax.
