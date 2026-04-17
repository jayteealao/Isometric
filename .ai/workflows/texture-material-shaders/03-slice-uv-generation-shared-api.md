---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: uv-generation-shared-api
status: implemented
stage-number: 3
created-at: "2026-04-17T00:00:00Z"
updated-at: "2026-04-17T11:16:37Z"
complexity: m
depends-on: [api-design-fixes, uv-generation]
source: plan-discovery
source-ref: "po-answers.md 2026-04-16 batch discovery round 2-3"
extension-round: 3
tags: [uv, api, refactor, shared-infrastructure]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  source: po-answers.md
  plan: 04-plan-uv-generation-shared-api.md
  implement: 05-implement-uv-generation-shared-api.md
  verify: 06-verify-uv-generation-shared-api.md
---

# Slice: uv-generation-shared-api

## Goal

Land the shared infrastructure that the five shape UV generation slices (cylinder,
pyramid, stairs, octahedron, knot) depend on — so each downstream slice becomes
purely additive. This is a pure API-refactor slice with no new user-visible
rendering behavior on its own; value comes from unblocking parallel shape work
with a single merge-conflict surface.

## Why This Slice Exists

Plan-phase discovery for the `uv-generation-*` batch revealed four cross-cutting
changes that all five shape slices need. Without consolidating them into a single
prerequisite slice, every UV slice would modify the same files (`IsometricMaterial.kt`,
`RenderCommand.kt`, `IsometricMaterialComposables.kt`) and create avoidable merge
conflicts. By landing the shared surface first with empty stubs, each shape slice
only adds new files plus tiny additive edits — matching the `api-design-fixes`
pattern that preceded `webgpu-uv-transforms`.

## Scope

### In

- **`RenderCommand.faceVertexCount: Int`** — new field on `RenderCommand` in
  `isometric-core`. `uvCoords` length becomes `2 × faceVertexCount`. Consumers
  (Canvas + WebGPU draw paths) must read this field instead of assuming 4 verts.
- **Abstract `IsometricMaterial.PerFace` base class** — the existing concrete
  `PerFace(faceMap: Map<PrismFace, MaterialData>, default: MaterialData)` becomes
  `PerFace.Prism(...)`. The abstract base declares `val default: MaterialData` and
  any common resolution behavior.
- **Shape-specific face enums in `isometric-core`:**
  - `CylinderFace { TOP, BOTTOM, SIDE }`
  - `PyramidFace { BASE, LATERAL_0, LATERAL_1, LATERAL_2, LATERAL_3 }` (or similar
    — plan phase decides names; must cover 4 laterals + 1 base)
  - `StairsFace { TREAD, RISER, SIDE }`
  - `OctahedronFace { UPPER_0..UPPER_3, LOWER_0..LOWER_3 }` or indexed 0..7 —
    plan phase decides
- **Empty `PerFace.<Shape>` variant stubs in `isometric-shader`:**
  - `PerFace.Cylinder(top, bottom, side, default)` — compiles, resolve() returns
    `default` for every face until the cylinder slice fills it in
  - `PerFace.Pyramid(base, laterals: Map<Int, MaterialData>, default)`
  - `PerFace.Stairs(tread, riser, side, default)`
  - `PerFace.Octahedron(byIndex: Map<Int, MaterialData>, default)`
  - No `PerFace.Knot` variant — Knot intentionally omitted per batch discovery
- **`UvCoordProvider.forShape(shape: Shape): UvCoordProvider?` factory skeleton**
  in `isometric-shader`. Initial implementation returns the existing Prism provider
  for `shape is Prism`, `null` otherwise. Each shape slice adds its `when` branch.
- **Consumer migration (Canvas + WebGPU):** Audit and update every consumer that
  assumes `uvCoords.size == 8` or indexes `[0..3]` for UV pairs. Use
  `faceVertexCount` instead.
- **`apiDump`** updates for `isometric-core` and `isometric-shader`.
- **Unit tests** for the refactored PerFace base class (equality, resolution,
  nesting ban), the new face enums (`fromPathIndex` or equivalent), and the
  `forShape()` factory (returns correct provider per shape type).

### Out

- Actual UV generation for non-Prism shapes — that is each shape slice's job.
- New shape face enum values in `isometric-core` beyond what the prereq defines.
- DSL scopes for per-shape PerFace variants (e.g. `cylinderPerFace {}`) — those
  are per-shape slice concerns; the prereq only ships the data classes.
- Any `KnotFace` enum or `PerFace.Knot` variant.
- Sample-demo changes.

## Acceptance Criteria

- **AC1: RenderCommand carries faceVertexCount** — Given the refactored
  `RenderCommand`, when a Prism is rendered, then each emitted command has
  `faceVertexCount == 4` and `uvCoords.size == 8` (unchanged behavior, new field
  populated).
- **AC2: Abstract PerFace compiles** — `IsometricMaterial.PerFace` is an abstract
  sealed base; the existing `PerFace(faceMap, default)` is now `PerFace.Prism(faceMap, default)`.
  All prior call sites of `PerFace(...)` either compile unchanged (if compatibility
  shims are added) or are migrated in this slice.
- **AC3: Face enums exist in core** — `CylinderFace`, `PyramidFace`, `StairsFace`,
  `OctahedronFace` are public in `io.github.jayteealao.isometric.shapes` with
  stable values + KDoc.
- **AC4: Empty PerFace variants compile and resolve to default** — `PerFace.Cylinder(top=X, bottom=null, side=null, default=Y)`
  constructs; resolving a `CylinderFace.BOTTOM` returns `Y`; resolving `CylinderFace.TOP` returns `X`.
- **AC5: forShape() factory dispatches** — `UvCoordProvider.forShape(prism)`
  returns a non-null provider that produces 8-float UV arrays matching
  `UvGenerator.forPrismFace`. `UvCoordProvider.forShape(cylinder)` returns `null`
  (will be non-null after cylinder slice lands).
- **AC6: No rendering regression** — All existing Canvas and WebGPU texture
  rendering tests pass unchanged. Existing `textured()` + `perFace {}` on Prism
  produces identical output before and after this slice.
- **AC7: apiDump green** — `./gradlew :isometric-core:apiCheck :isometric-shader:apiCheck`
  passes with the new baseline.

## Dependencies on Other Slices

- **`api-design-fixes`** (complete) — provides the final `IsometricMaterial`,
  `PerFace`, and `UvCoord` API surface that this slice refactors.
- **`uv-generation`** (complete) — provides the existing `UvGenerator.forPrismFace`,
  `PrismFace`, and `UvCoordProvider` pattern that this slice generalizes.

## Blocks These Slices

All five shape UV slices depend on this slice completing first:

- `uv-generation-cylinder`
- `uv-generation-pyramid`
- `uv-generation-stairs`
- `uv-generation-octahedron`
- `uv-generation-knot`

## Risks

- **`PerFace` renaming is a breaking source change.** `IsometricMaterial.PerFace(faceMap, default)`
  becomes `IsometricMaterial.PerFace.Prism(faceMap, default)`. Every caller that
  directly constructs `PerFace(...)` in test code, sample code, or API dumps must
  be migrated in this slice. Plan phase must enumerate all call sites.
- **`perFace {}` DSL must still produce `PerFace.Prism`.** The existing
  `PerFaceMaterialScope.build()` currently returns `PerFace`; must switch to
  `PerFace.Prism`. Callers of `perFace { ... }` in demo / test code should compile
  unchanged (the DSL is the same; only the produced type narrows).
- **`RenderCommand` field order matters** for consumers that construct it
  positionally. Add `faceVertexCount` with a default value (4) or at the end of
  the parameter list to minimize call-site churn.
- **`apiCheck` will fail** on the first build after changes — plan must schedule
  `apiDump` as an explicit step, not a post-hoc fix.
- **WebGPU buffer layout** (`SceneDataPacker.FaceData`) assumes 4 UV per face in
  its fixed-stride packing. If `faceVertexCount` varies per face, the buffer
  layout must either pad to a max or become variable-stride. Plan phase must
  decide; it may be acceptable to keep the fixed-4 layout for this prereq and
  defer variable-stride support to a later WebGPU slice (since none of this
  prereq's ACs require variable stride).
- **`IsometricMaterialComposables.Shape()` cleanup** — the existing
  `geometry as? Prism` gate and inline UV provider construction must migrate to
  `UvCoordProvider.forShape(geometry)`. One touch point; low risk.

## Notes for Plan Phase

- `IsometricMaterial.PerFace` is currently a `class` with a `private constructor`
  and `companion object { fun of(...) }` factory. Refactoring to an abstract
  sealed base preserves the `sealed` constraint at the top level
  (`sealed interface IsometricMaterial`) while making `PerFace` a sealed
  abstract class with its own subclasses. The nesting-ban invariant
  (`default !is PerFace`) moves to the abstract init block.
- The full-surface prereq approach chosen during discovery ("Full-surface prereq
  (Recommended)") means all 4 shape enums ship in this slice, and all 4 empty
  PerFace variants compile cleanly. Each shape slice's only edit to
  `IsometricMaterial.kt` is filling in its variant's `resolve()` method (or
  similar), not adding new types.
