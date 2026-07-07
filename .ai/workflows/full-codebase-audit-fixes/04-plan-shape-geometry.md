---
schema: sdlc/v1
type: plan
slug: full-codebase-audit-fixes
slice-slug: shape-geometry
status: complete
stage-number: 4
created-at: "2026-07-07T12:11:48Z"
updated-at: "2026-07-07T12:11:48Z"
metric-files-to-touch: 6
metric-step-count: 12
has-blockers: false
revision-count: 0
tags: [isometric-core, shapes, octahedron, knot, cylinder, breaking-change]
stack-source: confirmed
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-shape-geometry.md
  siblings:
    - 04-plan-core-math.md
    - 04-plan-gesture-coordination.md
    - 04-plan-compose-contracts.md
    - 04-plan-view-module.md
    - 04-plan-docs-and-changelog.md
    - 04-plan-snapshot-sweep-gate.md
  implement: 05-implement-shape-geometry.md
next-command: wf-implement
next-invocation: "/wf implement full-codebase-audit-fixes shape-geometry"
---

# Plan: Shape Geometry

## The Plan

The Octahedron's non-uniform post-scale is the rarest kind of bug to fix: the pre-scale
vertex geometry is already correct. The four equatorial corners at (0,0,0.5), (1,0,0.5),
(1,1,0.5), (0,1,0.5) and the two apices at (0.5,0.5,0) and (0.5,0.5,1.0) form a regular
octahedron inscribed in a unit cube — the shape the KDoc promises. The `scale(center,
sqrt(2)/2, sqrt(2)/2, 1.0)` call on line 44 then compressed X and Y to roughly 70.7% of
their proper span while leaving Z at 100%, producing the elongated bounding box (0.707 x
0.707 x 1.0) that contradicts the documentation. Removing the call is the entire fix.

The Knot offset is the same pattern at smaller scale. A hardcoded translate(-0.1, 0.15,
0.4) at line 50 was cosmetic centering applied after the 1/5 scale — cosmetic in the
original author's eye, but invisible to every caller who expects `Knot(position)` to place
geometry at `position`. Removing the translate() collapses the two-step translation into
one, and the shape lands where it is told to.

Cylinder's B3 fix is structural: the primary constructor delegates to
`Shape(Shape.extrude(Circle(position, radius, vertices), height).paths)`, which means
Circle's `require(radius > 0.0)` fires before Cylinder's `init` block runs. The fix
introduces a private companion factory that validates first, then instantiates Circle.
The public constructor signature — `@JvmOverloads`, default parameters, identical order —
is untouched, so the API dump is stable.

The plan's deliberate oddity: two of the three fixes change rendered scenes for every
existing caller — that is the PO-accepted breaking change, with migration notes delegated
to the `docs-and-changelog` slice. The Paparazzi goldens for `octahedron.png` and
`knot.png` will go red the moment these land and stay red until the sweep-end re-record.
That window is pre-registered, attributed, and expected. The verification posture for this
slice is geometric unit tests only — constructive proofs that each new assertion fails
against the pre-fix geometry — with the visual deferrals carried explicitly through the
Verification Strategy table below.

## Current State

All three findings remain **still-broken** in the current working tree. The recent commits
(eea28bb, 187e8f4, 38c77e1, 4489761) touched IsometricProjection, compose runtime, and the
android view module — none touched the shape files in scope here. `git diff HEAD` shows no
unstaged changes to Octahedron.kt, Knot.kt, or Cylinder.kt.

- **B1 (Octahedron non-uniform scale):** `still-broken`. Line 44 of Octahedron.kt still
  reads `it.scale(center, scale, scale, 1.0)` where `scale = sqrt(2.0)/2.0`. The
  bounding box is 0.707 x 0.707 x 1.0, not a unit cube.

- **B2 (Knot hardcoded offset):** `still-broken`. Line 50 of Knot.kt still reads
  `val translatedPaths = scaledPaths.map { it.translate(-0.1, 0.15, 0.4) }`. Knot(Point.ORIGIN)
  is offset by (-0.1, 0.15, 0.4) from ORIGIN.

- **B3 (Cylinder message reachability):** `still-broken`. Cylinder.kt init block lines
  27-28 are dead code for radius and vertices checks; Circle's requires at Circle.kt:27-28
  fire first with "Circle radius must be positive" / "Circle needs at least 3 vertices".

**Untracked snapshots directory (`isometric-compose/src/test/snapshots/`):** This directory
exists in the working tree (git status: `??`) and contains 29 PNGs (the current golden
set). It does **not** contain an octahedron-specific golden that reflects the broken
geometry — it contains the pre-existing golden from the initial snapshot commit. After B1/B2
land, the tests that reference `octahedron.png` and `knot.png` will fail against these
stored goldens. The directory is not committed, which means the goldens are local-only and
the CI is not yet running these snapshot verifications — the red-golden risk is real but
confined to local runs until the snapshots directory is committed (a task for
snapshot-sweep-gate).

Affected snapshot tests that will go red:
- `IsometricCanvasSnapshotTest.octahedron` — renders `Octahedron(Point(1.0, 1.0, 1.0))`
- `IsometricCanvasSnapshotTest.knot` — renders `Knot(Point(1.0, 1.0, 1.0))`
- `IsometricCanvasSnapshotTest.sampleThree` — line 119 renders
  `Octahedron(Point(3.0, 2.0, 3.2)).rotateZ(...)` as part of a composite scene
- `DocScreenshotGenerator.shapeOctahedron` — renders `Octahedron(Point.ORIGIN)`
- `DocScreenshotGenerator.shapeKnot` — renders `Knot(Point.ORIGIN)`

## Simplicity Ladder

- **B1 — correct Octahedron vertex span** → rung 3 (reuse as-is): the pre-scale vertex
  geometry already satisfies the unit-cube constraint. The fix is deletion of a `scale()`
  call at line 44; no new code required.

- **B2 — remove Knot offset** → rung 3 (reuse as-is): removing the intermediate
  `translate(-0.1, 0.15, 0.4)` at line 50 and collapsing to a single `translate(position)`
  step. No new code required.

- **B3 — Cylinder validation order** → rung 4 (new code, minimal): rungs 1-3 do not apply
  because the issue is Kotlin constructor execution order (super-constructor arguments are
  evaluated before init blocks). No language built-in, platform feature, or existing utility
  can reorder constructor delegation. The minimum-new-code solution is a private companion
  factory that runs the requires before instantiating Circle.

- **New geometry assertions (OctahedronGeometryTest, KnotGeometryTest)** → rung 4 (new
  test code): no existing test class covers vertex-span or bounding-box assertions for these
  shapes. The IsometricEngineTest line 298 path-count guard exists and stays; the new
  assertions are additive in a dedicated test file per the codebase pattern (IsoColorTest,
  IsometricEngineTest separate by concern).

- **New CylinderValidationTest** → rung 4 (new test code): no existing test class asserts
  Cylinder's own error messages. The existing IsometricEngineTest line 288
  `assertFailsWith<IllegalArgumentException>` asserts only exception type, not message
  text.

## Applied Learnings

No applicable learnings found. `.ai/solutions/INDEX.md` does not exist.

No runtime-evidence-deferrals appear in `00-index.md` for this slug. The repeat-deferral
tripwire has nothing to match: there are no prior wallet-blocking environment dependencies
for geometry unit tests.

## Likely Files / Areas to Touch

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Octahedron.kt`:
  Remove scale() call at line 44 (also delete the `val scale = sqrt(2.0) / 2.0` line above
  it, and the `sqrt` import which becomes unused).

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Knot.kt`:
  Remove translate(-0.1, 0.15, 0.4) step at line 50; collapse to a single translate of
  position.

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/Cylinder.kt`:
  Introduce a private companion factory to front-load validation before Circle instantiation.

- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/paths/Circle.kt`:
  Read-only. Circle's own requires are correct as-is; the fix is in Cylinder, not Circle.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/OctahedronGeometryTest.kt`
  (new): vertex-span, path-count co-location, winding/signed-area guards.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/KnotGeometryTest.kt`
  (new): bounding-box lower-bound and position-carry-through assertions.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/CylinderValidationTest.kt`
  (new): Cylinder's own message text assertions.

- `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/IsometricEngineTest.kt`:
  Line 298 stays unchanged. Add a comment attributing the path-count guard to B1.

**Inbound callers of the affected shapes (read-only concern):**
- `IsometricCanvasSnapshotTest.kt` uses Octahedron and Knot — the snapshot goldens will
  go red, expected and attributed.
- `DocScreenshotGenerator.kt` renders all three shapes — screenshot re-generation is
  delegated to docs-and-changelog and snapshot-sweep-gate.
- No production code calls Octahedron, Knot, or Cylinder with hard-coded expectations on
  vertex positions (only visual rendering via IsometricEngine).

## Proposed Change Strategy

**B1 — Octahedron:**
The vertex coordinates before the scale call are already a perfect unit-cube octahedron.
The strategy is deletion only:
1. Remove `val scale = sqrt(2.0) / 2.0` (line 43).
2. Remove `return paths.map { it.scale(center, scale, scale, 1.0) }` (line 44).
3. Replace with `return paths` (the pre-scale list is the correct output).
4. Remove the now-unused `sqrt` import.

Constructive-proof demonstration: the new `OctahedronGeometryTest` must include an
assertion that fails on the current code before the fix. Concretely:
- `assertTrue(Octahedron().paths.flatMap { it.points }.minOf { it.x } < 0.01)` fails on
  current (min x is ~0.146); passes after fix (min x is 0.0).

**B2 — Knot:**
Remove the two-line translation chain and replace with a direct position translate:
1. Remove `val translatedPaths = scaledPaths.map { it.translate(-0.1, 0.15, 0.4) }`.
2. Change the final line to `scaledPaths.map { it.translate(position.x, position.y, position.z) }`.

Constructive-proof demonstration: `KnotGeometryTest` asserts
`Knot(Point.ORIGIN).paths.flatMap { it.points }.minOf { it.x } >= -epsilon`; this fails
on the current code (min x is approximately -0.02 due to the offset).

**B3 — Cylinder:**
Extract a private companion factory that runs validation before constructing Circle:

```kotlin
class Cylinder @JvmOverloads constructor(
    val position: Point = Point.ORIGIN,
    val radius: Double = 1.0,
    val height: Double = 1.0,
    val vertices: Int = 20
) : Shape(create(position, radius, height, vertices)) {

    companion object {
        private fun create(position: Point, radius: Double, height: Double, vertices: Int): List<Path> {
            require(radius > 0.0) { "Cylinder radius must be positive, got $radius" }
            require(vertices >= 3) { "Cylinder needs at least 3 vertices, got $vertices" }
            require(height > 0.0) { "Cylinder height must be positive, got $height" }
            return Shape.extrude(Circle(position, radius, vertices), height).paths
        }
    }
    // init block removed — validation moved to factory
}
```

This pattern: (a) preserves the identical public constructor signature; (b) makes Cylinder's
own messages reachable; (c) does not change Circle.kt; (d) does not affect the API dump
because no public member signature changes.

Constructive-proof demonstration: `CylinderValidationTest` asserts
`assertFailsWith<IllegalArgumentException> { Cylinder(radius=-1.0) }.message!!.contains("Cylinder")`
— this fails on the current code (message says "Circle").

## Step-by-Step Plan

1. **Create the new test directory** for shapes-scoped tests:
   `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/`

2. **Write `CylinderValidationTest.kt`** (new, pre-fix) with assertions that:
   - Cylinder(radius=-1.0) throws with message containing "Cylinder"
   - Cylinder(vertices=2) throws with message containing "Cylinder"
   - Cylinder(height=-1.0) throws with message containing "Cylinder"
   - Cylinder(radius=1.0, height=1.0, vertices=3) does not throw
   These tests FAIL now (radius and vertices show "Circle"). Record that failure.

3. **Write `OctahedronGeometryTest.kt`** (new, pre-fix) with assertions that:
   - Vertex X span: `minOf { it.x } < 0.01` (demonstrates the ~0.146 pre-fix minimum)
     AND after fix: `minOf { it.x } <= epsilon && maxOf { it.x } >= 1.0 - epsilon`
   - Same for Y
   - Z span already covers [0,1] — write a baseline test that passes before and after
   - Path count == 8 (already passes; establish as co-located baseline)
   - All 8 paths have non-zero signed area (winding guard)
   - Upper-set paths (apex z=1.0) all have same sign; lower-set (apex z=0.0) all have same
     opposite sign (depth-sort stability)

4. **Write `KnotGeometryTest.kt`** (new, pre-fix) with assertions that:
   - `Knot(Point.ORIGIN).paths.flatMap { it.points }.minOf { it.x } >= -epsilon`
     — FAILS pre-fix (~-0.02); passes after fix
   - `Knot(Point(1.0, 0.0, 0.0)).paths.flatMap { it.points }.minOf { it.x } >= 1.0 - epsilon`
     — demonstrates position carry-through

5. **Fix Cylinder.kt** (B3): introduce the private companion factory; remove the init block.

6. **Fix Octahedron.kt** (B1): remove the `val scale = sqrt(2.0) / 2.0` line, remove
   the `return paths.map { it.scale(...) }` line, add `return paths`, remove unused `sqrt`
   import.

7. **Fix Knot.kt** (B2): remove the intermediate translate(-0.1, 0.15, 0.4) line; update
   the final line to translate directly by position.

8. **Run `./gradlew :isometric-core:test`** — confirm all three new test classes now pass
   (CylinderValidationTest, OctahedronGeometryTest, KnotGeometryTest) and IsometricEngineTest
   still passes (path count == 8 guard survives).

9. **Observe which snapshot tests fail**: run `./gradlew :isometric-compose:test` and record
   the list of failing golden tests. Confirm the failures are exactly the attributed list
   (octahedron, knot, sampleThree, not others).

10. **Add the `shapes/` test directory to the IsometricEngineTest comment** noting the B1
    path-count guard.

11. **Verify `./gradlew apiCheck` passes** — Cylinder's public API signature is unchanged.

12. **One conventional commit** per fix group:
    - `fix(shapes): correct Octahedron to unit-cube proportions (B1)` — Octahedron.kt + OctahedronGeometryTest.kt
    - `fix(shapes): remove Knot cosmetic offset so position is respected (B2)` — Knot.kt + KnotGeometryTest.kt
    - `fix(shapes): make Cylinder validation messages reachable (B3)` — Cylinder.kt + CylinderValidationTest.kt

## Verification Strategy

| AC | Tool / method + ladder rung | Environment need — satisfiable? | What must be built | Fallback chain |
|----|------------------------------|--------------------------------|-------------------|----------------|
| AC-B1 geometric (vertex span, path count, winding) | JUnit/kotlin.test unit assertions in OctahedronGeometryTest (`./gradlew :isometric-core:test`) — android rung 1 (Robolectric / JVM unit) | JVM on Windows 11 — yes, no device needed | OctahedronGeometryTest.kt (new); constructive proof: test fails pre-fix, passes post-fix | No fallback needed — JVM unit is sufficient for geometric coordinate assertions |
| AC-B1 visual (rendered correctly, golden inspected) | Paparazzi golden re-record (`recordPaparazziDebug`) + human diff inspection — android rung 2 (device-free screenshot goldens) | Local Gradle JVM on Windows 11 — yes for record; CI Linux for verify (pixel-drift caveat noted in shape) | Golden PNGs re-recorded in snapshot-sweep-gate slice | Pre-registered deferral: cleared by the snapshot-sweep-gate slice's `recordPaparazziDebug` + inspection pass |
| AC-B2 geometric (bounding box at ORIGIN) | JUnit/kotlin.test unit assertions in KnotGeometryTest (`./gradlew :isometric-core:test`) — android rung 1 (JVM unit) | JVM on Windows 11 — yes | KnotGeometryTest.kt (new); constructive proof: minX assertion fails pre-fix | No fallback needed |
| AC-B2 visual (rendered correctly, golden inspected) | Paparazzi golden re-record + human diff inspection — android rung 2 | Local Gradle JVM; CI Linux for verify | Golden PNGs re-recorded in snapshot-sweep-gate slice | Pre-registered deferral: cleared by the snapshot-sweep-gate slice's `recordPaparazziDebug` + inspection pass |
| AC-B3 (Cylinder messages) | JUnit assertFailsWith + message text assertion in CylinderValidationTest (`./gradlew :isometric-core:test`) — android rung 1 (JVM unit) | JVM on Windows 11 — yes | CylinderValidationTest.kt (new) | No fallback needed |

**Constraint resolution per AC:**

- `AC-B1-visual`: `constraint-resolution: proxy+deferral: cleared by the snapshot-sweep-gate slice's recordPaparazziDebug + inspection pass`. Proxy: AC-B1 geometric unit assertions (vertex span, path count, winding) are evidence that the geometry is correct; the visual rendering follows deterministically from correct geometry in the isometric engine. Deferral: golden re-record and human diff inspection are deferred to the snapshot-sweep-gate slice by pre-registered PO decision ("snapshots once at sweep end", po-answers.md Round 3).

- `AC-B2-visual`: `constraint-resolution: proxy+deferral: cleared by the snapshot-sweep-gate slice's recordPaparazziDebug + inspection pass`. Proxy: AC-B2 geometric unit assertions (bounding-box lower bound centered within tolerance) prove positioning is correct. Deferral: same PO decision as B1.

**Known-red goldens in the window between this slice and snapshot-sweep-gate (attributed and expected):**
- `IsometricCanvasSnapshotTest.octahedron` — geometry change (B1)
- `IsometricCanvasSnapshotTest.knot` — geometry change (B2)
- `IsometricCanvasSnapshotTest.sampleThree` — composite scene containing Octahedron (B1)
- `DocScreenshotGenerator.shapeOctahedron` — doc screenshot (B1; re-gen in docs-and-changelog)
- `DocScreenshotGenerator.shapeKnot` — doc screenshot (B2; re-gen in docs-and-changelog)

## Test / Verification Plan

### Automated checks

- **lint/typecheck:** `./gradlew :isometric-core:compileReleaseKotlin` (Kotlin compilation
  gates unused import warning for the removed `sqrt`).
- **unit tests:** `./gradlew :isometric-core:test` — runs IsometricEngineTest (path count
  guard) plus all three new test classes. Expected result: all pass. Constructive proof
  demonstrated in Step 2-4 above (tests written pre-fix and confirmed failing).
- **apiCheck:** `./gradlew apiCheck` — confirms Cylinder's public signature unchanged.
- **Snapshot tests (observe-only, do not scope to pass):** `./gradlew :isometric-compose:test`
  — expect specific failures on octahedron.png and knot.png; verify no unexpected failures
  (no other shapes affected).

### Interactive verification (human-in-the-loop)

Automated only for this slice's AC set. All visual ACs are deferred by pre-registered PO
decision.

The only human step: after running snapshot tests, visually confirm the failure list is
exactly the attributed set (octahedron, knot, sampleThree) — no unexpected regressions in
prism, pyramid, stairs, cylinder, or grid scenes.

Companion skill: none needed for JVM unit tests.

## Risks / Watchouts

- **Known-red goldens window (HIGH, pre-registered):** from the moment B1/B2 land until
  `snapshot-sweep-gate` records new goldens, `./gradlew test` will fail on snapshot
  verification. The failure is expected. Any developer touching the branch in this window
  must know to scope their test run to `:isometric-core:test`, not the full suite. Document
  this in the commit message and in the verify artifact.

- **B1 depth-sort regression (MED):** the new equatorial vertex positions change every
  face's 2D screen projection. The IsometricEngine sorts by depth using projected
  coordinates. A face that was previously hidden by the elongated Z dimension may now appear
  in a different depth-sort order. The winding/signed-area guards in OctahedronGeometryTest
  catch degenerate faces; if any face's signed area approaches zero under the corrected
  geometry, that is a sorting hazard. If the 8 faces test in IsometricEngineTest passes but
  a winding test fails, investigate before marking the fix done.

- **Cylinder apiDump stability (LOW):** the companion factory pattern preserves the public
  constructor signature exactly. But Kotlin generates synthetic helpers for `@JvmOverloads`
  that may differ if the annotation is accidentally moved. Verify `apiDump` output is
  unchanged by diff against the committed `.api` file.

- **Knot bounding box tolerance (LOW):** the Knot geometry uses 1/5 scaling of integer-
  coordinate prisms. After removing the offset, the minimum X coordinate of `Knot(Point.ORIGIN)`
  is 0.0 (from the prism at Point.ORIGIN scaled by 0.2). The epsilon in KnotGeometryTest
  should be at most 1e-9 (floating-point accumulation through scale + translate). Confirm
  the computed value at fix time.

## Dependencies on Other Slices

- **snapshot-sweep-gate clears the B1/B2 visual deferrals:** that slice owns the
  `recordPaparazziDebug` pass, the visual inspection of the diff, and the commit that
  updates the golden files. This slice's AC-B1-visual and AC-B2-visual are pre-registered
  as deferred until that gate.

- **docs-and-changelog writes the B1/B2 migration notes:** CHANGELOG migration sections
  ("before: scale(center, sqrt(2)/2, sqrt(2)/2, 1.0) applied; after: removed") and the
  shapes.mdx rotation note are delegated to that slice. This slice ships no CHANGELOG entry.

- **core-math (no hard dependency):** the shape changes are geometrically independent of
  the core-math zone. No ordering requirement.

- **No inbound dependencies:** no other slice waits on shape-geometry to start.

## Assumptions

- The pre-scale Octahedron vertex coordinates (four corners at z=0.5, two apices) are
  geometrically valid as a regular octahedron inscribed in the unit cube. This was confirmed
  by the audit finding's vertex trace and by the internal physics review
  (docs/internal/reviews/physics-plan-review-3.md lines 149-164). The assumption is that
  the pre-scale geometry does NOT require any additional vertex adjustment beyond removing
  the scale call.

- `sqrt` import in Octahedron.kt is only used for the `scale` constant. If PI is imported
  from `kotlin.math` and `sqrt` is a separate import, removing the `scale` constant and the
  scale() call makes `sqrt` unused. Assume the Kotlin compiler will warn (or the linter will
  flag) rather than silently leave the unused import.

- The Knot's post-offset geometry ((-0.1, 0.15, 0.4) at post-scale) when removed will
  produce a shape with minX == 0.0 (from `Prism(Point.ORIGIN, ...)` at scale 1/5 = 0.0
  minimum). This must be verified numerically at fix time.

- `Shape.extrude` does not call `Circle` validation itself (confirmed by reading
  Shape.kt:82-104 — it takes already-constructed paths). The validation order issue is
  fully contained in Cylinder.kt.

## Blockers

None. All three fixes are self-contained within isometric-core.

PO decisions have been pre-captured for the breaking changes: no deprecation shims (po-answers.md Batch B Q2), migration notes delegate to docs-and-changelog, golden re-record delegates to snapshot-sweep-gate.

No `PO-DECISION-PENDING` items. The "correct" Octahedron proportion is unambiguous: the
audit finding, the physics review, and the KDoc all agree that inscribed-in-unit-cube means
equal spans on all three axes — and the pre-scale vertices already deliver that. If a future
audit concluded the KDoc itself should change rather than the code, that would be a PO call;
but the current PO accepted the fix in Batch B ("Honest geometry (B1/B2): Octahedron is
inscribed in the unit cube as documented (uniform proportions)").

## Freshness Research

No external dependency is touched by this slice. All three fixes are pure internal geometry
changes within isometric-core shapes. No library version, API surface, or third-party
dependency is affected. Freshness is inherited from the shape stage's research
(Kotlin/Gradle stack pinned and confirmed; Paparazzi 1.3.0 cross-platform drift documented
in 02-shape.md). No new web research was conducted for this slice — the skip criterion is
satisfied: pure internal geometry with no external API surface changes.

## Revision History

*(appended by review-and-fix mode)*

## Recommended Next Stage

- **Option A (default):** `/wf implement full-codebase-audit-fixes shape-geometry` — the
  plan is complete and execution-ready. Three targeted source changes + three new test
  files. Consider running `/compact` before implementing — workflow state lives in the
  artifact files on disk and the SessionStart hook re-reads it automatically.
