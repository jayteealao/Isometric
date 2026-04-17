---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: uv-generation-shared-api
status: complete
stage-number: 5
created-at: "2026-04-17T11:16:37Z"
updated-at: "2026-04-17T11:16:37Z"
metric-files-changed: 19
metric-lines-added: 1430
metric-lines-removed: 140
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: "14edfbd43b92285fa48a8c7065589b09c5952dec"
tags: [uv, api, refactor, shared-infrastructure, sealed-class, face-enum]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-uv-generation-shared-api.md
  plan: 04-plan-uv-generation-shared-api.md
  siblings:
    - 05-implement-api-design-fixes.md
    - 05-implement-webgpu-uv-transforms.md
    - 05-implement-webgpu-texture-error-callback.md
  verify: 06-verify-uv-generation-shared-api.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders uv-generation-shared-api"
---

# Implement: uv-generation-shared-api

## Summary of Changes

Landed the prerequisite API refactor that unblocks the five shape UV slices (cylinder,
pyramid, stairs, octahedron, knot). Every subsequent shape slice becomes purely
additive on top of this surface.

Key shifts:

1. **`IsometricMaterial.PerFace`** is now an abstract sealed class. The existing concrete
   behaviour moved to `PerFace.Prism`; four new empty stubs (`PerFace.Cylinder`,
   `PerFace.Pyramid`, `PerFace.Stairs`, `PerFace.Octahedron`) compile and resolve to
   `default` until their per-shape UV slices fill in the `resolve()` paths.
2. **`RenderCommand.faceVertexCount: Int = 4`** was added at the end of the parameter
   list. All four construction sites in `IsometricNode.kt` were updated to set
   `path.points.size` (or pass-through for the alpha-copy rewrapper).
3. **Four face types** (`CylinderFace`, `PyramidFace`, `StairsFace`, `OctahedronFace`) now
   live in `isometric-core/shapes/`. `PyramidFace` is a sealed class (not enum) because
   its `Lateral(index)` subtype carries a payload.
4. **`uvCoordProviderForShape(shape: Shape): UvCoordProvider?`** factory lives in
   `isometric-shader` as a single `when` dispatch. Each shape slice adds its branch.
   `IsometricMaterialComposables.Shape()` uses this factory in place of its former
   `as? Prism` gate.
5. **WebGPU + Canvas consumer migration:** `SceneDataPacker.resolveTextureIndex`,
   `GpuTextureManager.resolveEffectiveMaterial`, `GpuTextureManager.collectTextureSources`,
   and `TexturedCanvasDrawHook.draw` all now dispatch on `PerFace.Prism` specifically
   and fall back to `m.default` for the other variants.
6. **`GpuUvCoordsBuffer`** now gates on `uv.size >= 2 * faceVertexCount` rather than the
   old hard-coded `>= 8`. A TODO documents the 12-float cap (affects Cylinder caps
   when `vertices > 6`, Stairs sides when `stepCount > 2`, and Knot).

Verified: `:isometric-core:compileKotlin`, `:isometric-shader:compileDebugKotlin`,
`:isometric-compose:compileDebugKotlin`, `:isometric-webgpu:compileDebugKotlin` all
green. `:isometric-core:test` and `:isometric-shader:testDebugUnitTest` pass with the
two new test classes. `:isometric-core:apiCheck`, `:isometric-shader:apiCheck`,
`:isometric-compose:apiCheck`, and `:isometric-webgpu:apiCheck` all pass against the
updated `.api` dumps.

## Files Changed

### Production code (10 files)

| Path | Action | Notes |
|------|--------|-------|
| `isometric-core/src/main/kotlin/.../shapes/CylinderFace.kt` | CREATE | enum TOP/BOTTOM/SIDE + `fromPathIndex(Int)` |
| `isometric-core/src/main/kotlin/.../shapes/PyramidFace.kt` | CREATE | sealed class BASE/Lateral + LATERAL_0..3 + `fromPathIndex(Int)` |
| `isometric-core/src/main/kotlin/.../shapes/StairsFace.kt` | CREATE | enum RISER/TREAD/SIDE + `fromPathIndex(Int, stepCount: Int)` |
| `isometric-core/src/main/kotlin/.../shapes/OctahedronFace.kt` | CREATE | enum UPPER_0..UPPER_3/LOWER_0..LOWER_3 interleaved + `fromPathIndex(Int)` |
| `isometric-core/src/main/kotlin/.../RenderCommand.kt` | MODIFY | add `faceVertexCount: Int = 4` + equals/hashCode/toString |
| `isometric-shader/src/main/kotlin/.../IsometricMaterial.kt` | MODIFY | abstract sealed `PerFace` + 5 subclasses; `perFace {}` now returns `PerFace.Prism` |
| `isometric-shader/src/main/kotlin/.../UvCoordProviderFactory.kt` | CREATE | `uvCoordProviderForShape(Shape): UvCoordProvider?` |
| `isometric-shader/src/main/kotlin/.../IsometricMaterialComposables.kt` | MODIFY | uses `uvCoordProviderForShape()` in place of `as? Prism` gate |
| `isometric-shader/src/main/kotlin/.../render/TexturedCanvasDrawHook.kt` | MODIFY | `resolvePerFaceSubMaterial` helper for `PerFace.Prism` vs stubs |
| `isometric-compose/src/main/kotlin/.../runtime/IsometricNode.kt` | MODIFY | 4 `RenderCommand` sites propagate `faceVertexCount` |
| `isometric-webgpu/src/main/kotlin/.../pipeline/SceneDataPacker.kt` | MODIFY | `resolvePerFaceSubMaterial`; added `MaterialData` import |
| `isometric-webgpu/src/main/kotlin/.../pipeline/GpuUvCoordsBuffer.kt` | MODIFY | gate on `2 * faceVertexCount`; TODO for variable-stride |
| `isometric-webgpu/src/main/kotlin/.../texture/GpuTextureManager.kt` | MODIFY | `when (m)` sub-dispatch in `resolveEffectiveMaterial` + `collectTextureSources` |

### Tests (4 files)

| Path | Action | Notes |
|------|--------|-------|
| `isometric-core/src/test/kotlin/.../shapes/ShapeFaceEnumTest.kt` | CREATE | 16 cases across all 4 face types |
| `isometric-shader/src/test/kotlin/.../shader/PerFaceSharedApiTest.kt` | CREATE | 13 cases covering each PerFace variant + factory + RenderCommand |
| `isometric-shader/src/test/kotlin/.../shader/PerFaceMaterialTest.kt` | MODIFY | `PerFace.of` → `PerFace.Prism.of` |
| `isometric-shader/src/test/kotlin/.../shader/IsometricMaterialTest.kt` | MODIFY | `PerFace.of` → `PerFace.Prism.of` |

### Generated / API dumps (2 files)

| Path | Action | Notes |
|------|--------|-------|
| `isometric-core/api/isometric-core.api` | UPDATE | +CylinderFace, +PyramidFace, +StairsFace, +OctahedronFace; RenderCommand's `faceVertexCount` does not appear because RenderCommand is package-private in the generated API (constructor + properties already internal/hidden) |
| `isometric-shader/api/isometric-shader.api` | UPDATE | PerFace now abstract; +PerFace.Prism/Cylinder/Pyramid/Stairs/Octahedron; `perFace(...)` return type narrowed to `PerFace.Prism` |

## Shared Files (also touched by sibling slices)

- `IsometricMaterial.kt` — this was last touched by `api-design-fixes`. The sealed-abstract
  refactor replaces that slice's concrete `PerFace private constructor(...)` entirely.
- `RenderCommand.kt` — `faceVertexCount` sits at position 11, after `faceType` (added by
  `uv-generation`) and before any future field.
- `SceneDataPacker.kt`, `GpuTextureManager.kt`, `GpuUvCoordsBuffer.kt` — previously
  edited by `webgpu-textures` and `webgpu-uv-transforms`. No conflicts with those
  earlier changes.
- `IsometricNode.kt` — previously edited by `api-design-fixes` and `uv-generation`.
  The four `RenderCommand(...)` construction sites were already present; only a new
  named argument was added to each.

## Notes on Design Choices

### Sealed-class default storage

`sealed class PerFace(val default: MaterialData)` — the `default` is held once on the
base, not redeclared per subclass. Subclasses pass their `default` parameter through the
super call. This keeps the nesting-ban invariant (`require(default !is PerFace)`) in
the base `init` block so every subclass inherits it. The plan sketched an `open val` /
`override val` pattern, which I deliberately simplified — no subclass changes `default`'s
behaviour, so the override ceremony would be dead code.

### `PyramidFace` as sealed class, not enum

The plan made this call because `Lateral(val index: Int)` carries a payload. I kept that
verbatim. The companion exposes `LATERAL_0..3` as `val` constants so callers that just
want to name a specific lateral don't construct a fresh `Lateral(i)` every time. This
matches Kotlin's own stdlib pattern for parameterized sealed types (e.g. `Result.Failure`).

### `PerFace.Prism` constructor is `internal`, not `private`

The subclass is a nested type inside an abstract sealed parent. A `private` constructor
would prevent `PerFace.Prism.of()` from reaching it through the companion. `internal`
keeps direct construction locked to the module while preserving the companion factory
as the documented entry point.

### `PerFace.Pyramid.laterals` key validation

Added `require(laterals.keys.all { it in 0..3 })` in the Pyramid init block. The plan
didn't spell this out, but it matches the `Lateral(val index: Int)` init-block check
already present on the face type itself, and gives a clearer error at construction time
than at lookup time.

### Canvas consumer (`TexturedCanvasDrawHook`)

The plan's Step 4 listed only WebGPU consumers. Pre-implementation verification found
`TexturedCanvasDrawHook.draw` also accesses `material.faceMap`, so it had to migrate
too — otherwise the shader module wouldn't compile. Added a private
`resolvePerFaceSubMaterial` helper mirroring the pattern used in both `SceneDataPacker`
and `GpuTextureManager`. Three sites now share the same dispatch shape.

### `GpuTextureManager.collectTextureSources`

Third WebGPU consumer the pre-implementation check surfaced (not in the plan). Now
uses `m is IsometricMaterial.PerFace.Prism` to iterate `faceMap.values`; other PerFace
variants only contribute their `default` source because their face-map structure is
different per subclass and empty by default. When the shape UV slices land, each will
augment this method with its own sub-material walk.

## Deviations from Plan

1. **Canvas `TexturedCanvasDrawHook` migration** (not listed in plan Step 4). Required
   for compile-cleanliness after `PerFace.faceMap` moved to `PerFace.Prism`. Implemented
   in parallel with the WebGPU site migrations.
2. **`faceVertexCount` propagation to `PathNode`, `BatchNode`, and the alpha-copy
   rewrapper** (plan only spelled out `ShapeNode.renderTo()`). Without these, the
   default `4` would silently misrepresent the vertex count for non-quad paths flowing
   through those nodes. Low-risk additive change; all four sites now set
   `faceVertexCount = path.points.size` (or preserve the source command's value for the
   alpha rewrapper).

## Anything Deferred

- **Variable-stride UV buffer packing.** `GpuUvCoordsBuffer` still uses a 48-byte
  (12-float / 6-vertex) fixed slot. The TODO documents that faces with more than 6
  vertices silently truncate. Will need a follow-up slice once a shape (Cylinder with
  `vertices > 6`, Stairs with `stepCount > 2`, or Knot) actually exercises that path.
- **DSL scopes for the new PerFace variants** (e.g. `cylinderPerFace { ... }`). The
  prerequisite intentionally only ships the data classes; each shape slice adds its own
  DSL scope if warranted.

## Known Risks / Caveats

- **Binary compatibility:** `PerFace` in bytecode is now abstract; `PerFace$Companion`
  lost `of()`; `PerFace$Prism$Companion` has it. Pre-1.0 library so this is acceptable
  (confirmed by user's no-deprecation-cycles preference in memory).
- **`perFace {}` return type** narrowed from `PerFace` to `PerFace.Prism`. Source-compatible
  for callers typed against the base; binary-breaking for anyone loading the bytecode
  directly.
- **Empty `PerFace` variant stubs** are legal to construct and will render flat (the
  `default` material) everywhere. A user who skips reading the KDoc might construct a
  `PerFace.Cylinder(top = grassTexture)` and be surprised that the top cap is grey.
  Each shape slice's KDoc + tests will clear this up as they land.

## Freshness Research

No new external research for this slice — the plan already captured the relevant
Kotlin sealed-class patterns and `binary-compatibility-validator` behaviour. The only
kotlin-language clarification exercised was nested-class-inside-abstract-sealed-class
access to the companion's `UNASSIGNED_FACE_DEFAULT` (works unqualified; verified by
compile).

## Recommended Next Stage

- **Option A (default):** `/wf-verify texture-material-shaders uv-generation-shared-api` —
  verify the 7 ACs (AC1–AC7 in the slice definition). Most of them are already exercised
  by the unit tests and the passing `apiCheck`; AC6 (no rendering regression) needs a
  Canvas/WebGPU interactive pass against the sample app.
- **Option B:** `/wf-review texture-material-shaders uv-generation-shared-api` — skip
  verify only if the user is confident the compile + unit tests cover all 7 ACs. The
  sample-app regression check (AC6) is the main risk of skipping.
- **Option C:** After verify + review merge, fan out to the five shape slices in
  whichever order the plan's `04-plan.md` specifies (recommended sequence: octahedron →
  pyramid → cylinder → stairs → knot, simple to complex).
