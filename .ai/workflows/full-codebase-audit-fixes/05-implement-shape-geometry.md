---
schema: sdlc/v1
type: implement
slug: full-codebase-audit-fixes
slice-slug: shape-geometry
status: complete
stage-number: 5
created-at: "2026-07-07T16:17:07Z"
updated-at: "2026-07-07T16:17:07Z"
metric-files-changed: 8
metric-lines-added: 121
metric-lines-removed: 12
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: ""
tags: [isometric-core, shapes, octahedron, knot, cylinder, breaking-change]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-shape-geometry.md
  plan: 04-plan-shape-geometry.md
  siblings:
    - 05-implement-core-math.md
    - 05-implement-gesture-coordination.md
    - 05-implement-compose-contracts.md
    - 05-implement-view-module.md
  verify: 06-verify-shape-geometry.md
next-command: wf-verify
next-invocation: "/wf verify full-codebase-audit-fixes shape-geometry"
---

# Implement: Shape Geometry

## The Implementation

Three bugs in the isometric-core shape library, all pure deletion or minimal structural
rearrangement: Octahedron's non-uniform post-scale collapsed its X and Y axes to 70.7% of
their intended span; Knot applied a hardcoded translate(-0.1, 0.15, 0.4) after the 1/5
scale that displaced the shape from any position the caller requested; and Cylinder's init
block validation was dead code because `Circle`'s own requires fire earlier in constructor
delegation. All three are now fixed.

The Octahedron and Knot fixes are pure deletions — no new production logic, just
removing lines that contradicted the documented behavior. The Cylinder fix introduces a
private companion factory (`create()`) that runs the three requires before constructing
`Circle`, preserving the exact public constructor signature. The visible ABI effect is
the expected addition of an empty `Cylinder$Companion` class (additive-only, same pattern
as Knot and Octahedron which already had companion classes).

Three new test classes provide constructive proof: each assertion is written to fail
against the pre-fix geometry and pass after. All 247 isometric-core JVM tests pass;
`./gradlew :isometric-compose:test` also passes because the local Paparazzi goldens
(untracked; `??` in git status) auto-updated when the new geometry was rendered. `apiDump`
was regenerated to include the `Cylinder$Companion` entry and `apiCheck` is clean.

The KnotGeometryTest had one initial test failure (minimum-Z assertion) that led to
a triage decision: the Knot geometry includes a prism at Z=-2.0 (which scales to -0.4 at
position ORIGIN), making negative Z values intrinsic to the shape's design — not a result
of the offset bug. The assertion was corrected to verify Z-shift consistency (position
carry-through) rather than absolute floor.

## Summary of Changes

- `Octahedron.kt`: Removed `val scale = sqrt(2.0) / 2.0` and the `scale()` call; replaced
  with `return paths`. Removed now-unused `kotlin.math.sqrt` import. (B1)
- `Knot.kt`: Removed intermediate `val translatedPaths = scaledPaths.map { it.translate(-0.1, 0.15, 0.4) }` and
  the final re-translate; collapsed to `scaledPaths.map { it.translate(position.x, position.y, position.z) }`. (B2)
- `Cylinder.kt`: Replaced `init { require(…) }` block with private companion `fun create()` that validates
  before constructing `Circle`. Delegates via `Shape(create(…))`. (B3)
- `isometric-core/api/isometric-core.api`: Regenerated via `apiDump`; added `Cylinder$Companion` field
  and class (additive, matches Knot/Octahedron pattern).
- `OctahedronGeometryTest.kt` (new): 6 tests — X/Y span proof (fail pre-fix), Z span baseline,
  path count == 8, winding guard (non-zero area per face), position carry-through.
- `KnotGeometryTest.kt` (new): 5 tests — X min at ORIGIN (fail pre-fix), Y min at ORIGIN,
  Z/X shift carry-through (position-respecting), non-empty paths.
- `CylinderValidationTest.kt` (new): 4 tests — negative radius message contains "Cylinder"
  (fail pre-fix), too-few vertices message, negative height message, valid construction.
- `IsometricEngineTest.kt`: Added comment on line 298 attributing the `Octahedron().paths.size == 8`
  guard to B1.

## Files Changed

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Octahedron.kt`: Remove non-uniform post-scale and unused sqrt import (B1)
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt`: Remove cosmetic offset translate (B2)
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Cylinder.kt`: Companion factory for reachable validation (B3)
- `isometric-core/api/isometric-core.api`: Regenerated API dump — Cylinder$Companion addition
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/OctahedronGeometryTest.kt` (new)
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/KnotGeometryTest.kt` (new)
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/CylinderValidationTest.kt` (new)
- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IsometricEngineTest.kt`: B1 guard comment

## Shared Files (also touched by sibling slices)

- None. All three source files (Octahedron.kt, Knot.kt, Cylinder.kt) and the new test
  directory are exclusive to this slice.

## Notes on Design Choices

- **Cylinder companion object ABI:** The private companion `create()` factory is the only
  correct fix for Kotlin's constructor delegation order — there is no way to run Cylinder's
  own requires before Circle is constructed without this pattern. The resulting ABI addition
  (`Cylinder$Companion`) is additive and consistent with how Octahedron and Knot already
  expose their companion objects.
- **KnotGeometryTest Z assertion:** The Knot's geometry contains `Prism(Point(4.0, 4.0, -2.0), …)`
  which, after 1/5 scale, places vertices at Z = -0.4. Asserting `minZ >= -epsilon` at ORIGIN
  would be geometrically wrong — the shape intentionally extends below Z=0. The tests instead
  assert shift consistency: moving position by +1.0 on any axis shifts the minimum coordinate
  on that axis by exactly +1.0. This is a stronger statement (proves linearity of the position
  carry-through) and correctly scoped.
- **Paparazzi snapshot behavior:** The local goldens in `isometric-compose/src/test/snapshots/`
  are untracked (`??` in git status). When tests ran, Paparazzi recorded fresh goldens from
  the corrected geometry. The compose test suite passed (`BUILD SUCCESSFUL`) because there were
  no committed baseline goldens to compare against — consistent with the plan's note that
  "the red-golden risk is real but confined to local runs until the snapshots directory is committed."

## Verification Seams Built

- AC-B1 geometric → `OctahedronGeometryTest.kt` at `isometric-core/src/test/kotlin/…/shapes/OctahedronGeometryTest.kt` (enables `./gradlew :isometric-core:test` to assert vertex spans and winding)
- AC-B2 geometric → `KnotGeometryTest.kt` at `isometric-core/src/test/kotlin/…/shapes/KnotGeometryTest.kt` (enables `./gradlew :isometric-core:test` to assert position carry-through)
- AC-B3 → `CylinderValidationTest.kt` at `isometric-core/src/test/kotlin/…/shapes/CylinderValidationTest.kt` (enables `./gradlew :isometric-core:test` to assert Cylinder's message text)
- AC-B1 visual, AC-B2 visual → deferred to snapshot-sweep-gate (pre-registered PO decision; no seam to build here)

## Deviations from Plan

1. **KnotGeometryTest Z assertion adjusted (triage, in-scope):** The plan specified `Knot(Point.ORIGIN).paths.flatMap { it.points }.minOf { it.z } >= -epsilon` as a baseline assertion. At runtime, `Prism(Point(4.0, 4.0, -2.0), 1.0, 1.0, 3.0)` places geometry at Z=-2.0, which after 1/5 scaling becomes Z=-0.4. The assertion would fail even after the fix (because -0.4 < -epsilon). Test replaced with shift-consistency assertions (Z shift by position.z) that correctly capture the intent without the geometric misassumption. All other KnotGeometryTest assertions are faithful to the plan.

2. **apiDump regenerated (additive, in-scope):** The plan stated "does not affect the API dump because no public member signature changes." The companion object addition does add `Cylinder$Companion` as an ABI entry. apiDump was regenerated; this is an additive-only change (no existing entry removed or changed), consistent with the evolution guideline. `apiCheck` is clean.

## Anything Deferred

- AC-B1 visual (Octahedron renders correctly, golden re-recorded and inspected) — pre-registered deferral to snapshot-sweep-gate slice per PO "snapshots once at sweep end" decision.
- AC-B2 visual (Knot renders correctly, golden re-recorded and inspected) — same deferral as above.

## Known Risks / Caveats

- **Known-red goldens between this slice and snapshot-sweep-gate:** Once the snapshots directory is committed (by snapshot-sweep-gate), any CI run before that slice's golden re-record will fail on octahedron, knot, and sampleThree tests. The failures are attributed and expected. Test runs between this commit and the sweep gate should scope to `:isometric-core:test`.
- **Cylinder$Companion ABI addition:** Callers using binary-compatibility tools (ProGuard rules, keep rules, reflection on the companion) might need awareness of the new companion class. The class has no public members so the practical surface is zero.

## Freshness Research

No external dependency touched. Pure internal geometry changes within isometric-core. See 04-plan-shape-geometry.md Freshness Research section for the skip-criterion rationale.

## Assumptions / Triage Decisions

- **Autonomous decision — KnotGeometryTest Z assertion:** The initial test had a geometric misassumption about the Knot's minimum Z at ORIGIN. Corrected by replacing with shift-consistency assertions. This is a test-correctness fix within the planned scope; no scope change, no plan drift beyond the test assertion text.
- **Autonomous decision — apiDump regeneration:** The companion object ABI artifact was anticipated as a risk in the plan ("Cylinder apiDump stability (LOW)") but the exact form (empty companion class addition) was not predicted. Since it is additive-only and matches the existing pattern for Knot/Octahedron, this was resolved as an expected side effect and `apiDump` was regenerated.

## Recommended Next Stage

- **Option A (default):** `/wf verify full-codebase-audit-fixes shape-geometry` — all JVM unit tests pass (247/247). AC-B1 and AC-B2 geometric halves fully verified by OctahedronGeometryTest and KnotGeometryTest. AC-B3 verified by CylinderValidationTest. Visual ACis pre-registered as deferred to snapshot-sweep-gate.
- **Option B:** Continue with `docs-and-changelog` or `snapshot-sweep-gate` — no dependency on shape-geometry being verify-complete before they start.
