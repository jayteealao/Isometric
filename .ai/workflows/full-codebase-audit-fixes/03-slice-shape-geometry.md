---
schema: sdlc/v1
type: slice
slug: full-codebase-audit-fixes
slice-slug: shape-geometry
status: defined
stage-number: 3
created-at: "2026-07-07T11:54:55Z"
updated-at: "2026-07-07T11:54:55Z"
revision-count: 1
complexity: s
depends-on: []
tags: [isometric-core, shapes, octahedron, knot, cylinder, breaking-change]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-core-math.md, 03-slice-gesture-coordination.md, 03-slice-compose-contracts.md, 03-slice-view-module.md, 03-slice-docs-and-changelog.md, 03-slice-snapshot-sweep-gate.md]
  plan: 04-plan-shape-geometry.md
  implement: 05-implement-shape-geometry.md
---

# Slice: Shape Geometry

## The Slice

Two of the sweep's three accepted breaking changes live here, and both are the honest-
geometry kind: the Octahedron's non-uniform post-scale (√2/2, √2/2, 1.0) elongates it along
Z in direct contradiction of its "inscribed in a unit cube" KDoc, and `Knot(Point.ORIGIN)`
isn't at ORIGIN because of a hardcoded cosmetic offset. Both fixes change rendered scenes
for every existing caller — accepted, no deprecation shims, migration notes in CHANGELOG.
The third item is a plain bug with no visual consequence: `Cylinder`'s `require` messages
are unreachable because `Circle`'s validation fires first, so a caller who passes a
negative radius gets an error message about the wrong shape.

The deliberate oddity of this slice is its verify posture: the geometry assertions
(vertex spans, bounding boxes, path counts, error messages) prove correctness in unit
tests, but the Paparazzi goldens for octahedron.png, knot.png, and any composite scene
will go **red and stay red** until the sweep-end re-record — that is the PO's
snapshots-once-at-end decision, not an accident. To keep that red window short, this slice
is deliberately sequenced late (fifth), just before docs and the sweep gate. Its verify
scopes to unit tests and must explicitly attribute the known-red goldens.

## Goal

Octahedron inscribed in the unit cube as documented (B1); Knot positioned at its given
position (B2); Cylinder's own validation messages fire (B3) — each with unit-test proof,
golden re-record deferred to the sweep gate.

## Why This Slice Exists

The two breaking visual fixes need to land in one commit neighborhood so the single
sweep-end re-record attributes their golden churn cleanly; B3 shares the same shape files
and test surface.

## Scope

- **In:** `Octahedron.kt` (remove/correct the non-uniform post-scale), `Knot.kt` (remove
  the hardcoded offset), `Cylinder.kt` (validation order so its `require` messages fire),
  vertex/bounding-box unit tests, IsometricEngineTest path-count guard, Cylinder message
  assertions.
- **Out:** the golden re-record + visual inspection (→ `snapshot-sweep-gate`, by PO
  decision); CHANGELOG migration notes for B1/B2 (→ `docs-and-changelog`); shapes.mdx
  rotation note (→ `docs-and-changelog`; it documents core-math's A1, not this slice).

## Acceptance Criteria

- Octahedron vertices span the full unit cube on all axes (equatorial vertices at the cube
  faces); path count stays 8 (IsometricEngineTest:298 guard); depth sorting of its 8 faces
  survives the geometry change (winding preserved). (AC-B1 geometric half)
  <!-- observable: false — vertex coordinates and path counts are exact unit assertions -->
- The corrected Octahedron renders correctly: golden re-recorded and visually inspected. (AC-B1 visual half)
  <!-- observable: true — a rendered surface change every consumer sees -->
  verify: { method: Paparazzi golden re-record + human diff inspection — pre-registered deferral to the snapshot-sweep-gate slice per PO "snapshots once at sweep end", env: local Gradle JVM on Windows record host (cross-platform pixel-drift caveat vs CI Linux), fixture: IsometricCanvasSnapshotTest octahedron + composite scenes, rung: android-2 (device-free screenshot goldens) }
- `Knot(Point.ORIGIN)` geometry is positioned at ORIGIN — bounding-box assertion centered
  within tolerance. (AC-B2 geometric half)
  <!-- observable: false — bounding-box unit assertion -->
- The repositioned Knot renders correctly: golden re-recorded and visually inspected. (AC-B2 visual half)
  <!-- observable: true — rendered surface change -->
  verify: { method: same deferred re-record + inspection as AC-B1, env: same, fixture: IsometricCanvasSnapshotTest knot scenes, rung: android-2 (device-free screenshot goldens) }
- `Cylinder(radius=-1.0, …)` and invalid-vertex-count constructions throw with Cylinder's
  own message text (tests assert the message, not just the exception type). (AC-B3)
  <!-- observable: false — exception-message assertions -->

## Dependencies on Other Slices

- None hard. Sequenced late by design to shorten the window where snapshot tests are red;
  `snapshot-sweep-gate` depends on this slice having landed.

## Risks

- **Known-red goldens between this slice and the sweep gate:** `./gradlew test` will fail
  on snapshot verification until the re-record. The slice's verify must scope to unit
  tests and record the red goldens as attributed-and-expected, or the failure will look
  like a regression to anyone touching the branch in between.
- **B1 may interact with depth sorting:** the corrected proportions change face geometry;
  the path-count and winding guards exist precisely so a sorting regression surfaces as a
  test failure here, not as a visual artifact at re-record time.
