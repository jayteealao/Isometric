---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: uv-generation
status: complete
stage-number: 5
created-at: "2026-04-12T09:28:40Z"
updated-at: "2026-04-12T09:28:40Z"
metric-files-changed: 9
metric-lines-added: 250
metric-lines-removed: 6
metric-deviations-from-plan: 1
metric-review-fixes-applied: 0
commit-sha: "96ae786"
tags: [uv, geometry]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-uv-generation.md
  plan: 04-plan-uv-generation.md
  siblings: [05-implement-material-types.md]
  verify: 06-verify-uv-generation.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders uv-generation"
---

# Implement: UV Coordinate Generation

## Summary of Changes

Created `PrismFace` enum in `isometric-core` for face identification, `UvGenerator`
object in `isometric-shader` for per-face UV computation, and wired UV generation
into the scene graph via a `uvProvider` lambda on `ShapeNode`. The shader module's
`Shape(geometry, material)` composable sets the provider when the material is `Textured`
and the geometry is a `Prism`.

## Files Changed

### New files (4)
- `isometric-core/.../shapes/PrismFace.kt`: Enum with FRONT/BACK/LEFT/RIGHT/BOTTOM/TOP + `fromPathIndex(Int)`
- `isometric-shader/.../shader/UvGenerator.kt`: Pure-function object computing per-face UVs from 3D vertex positions
- `isometric-core/src/test/.../shapes/PrismFaceTest.kt`: 9 tests for face index mapping
- `isometric-shader/src/test/.../shader/UvGeneratorTest.kt`: 10 tests for UV computation

### Modified files (5)
- `isometric-compose/.../IsometricNode.kt`: Added `uvProvider` lambda field to `ShapeNode`, wired into `renderTo()` with `withIndex()`
- `isometric-shader/.../IsometricMaterialComposables.kt`: `Shape()` overload sets `uvProvider` when `Textured + Prism`
- `isometric-core/api/isometric-core.api`: Updated by apiDump (PrismFace enum)
- `isometric-shader/api/isometric-shader.api`: Updated by apiDump (UvGenerator)
- `isometric-compose/api/isometric-compose.api`: Updated by apiDump (uvProvider field)

## Shared Files
- `IsometricNode.kt` — also modified by material-types; the `uvProvider` field is additive

## Notes on Design Choices
- `uvProvider` as a `((Shape, Int) -> FloatArray?)?` lambda keeps compose free of shader imports — the lambda is opaque
- UV computation uses direct `when` branches with local `u`/`v` variables instead of `Pair` allocation
- UVs are computed from the original (pre-transform) Prism using `position`/`width`/`depth`/`height` — translation-invariant

## Deviations from Plan
1. **Plan's UV table was incorrect for RIGHT and BOTTOM faces.** The plan assumed all faces produce canonical `(0,0),(1,0),(1,1),(0,1)` UV ordering. Tracing the actual vertex order from `Prism.createPaths()` revealed that RIGHT produces `(0,0),(0,1),(1,1),(1,0)` and BOTTOM produces `(1,1),(0,1),(0,0),(1,0)`. The UvGenerator formula is correct — the test expectations were fixed to match the actual vertex data.

## Anything Deferred
- UV generation for non-Prism shapes (Cylinder, Pyramid, etc.) — future slice
- Non-uniform scale UV correction — documented as risk, not addressed
- `PerFace` material UV generation — the provider currently only triggers for `Textured`

## Known Risks / Caveats
- Non-uniform scale applied to a textured Prism will distort UVs (documented in plan)
- `uvProvider` is only set for `Textured` materials; `PerFace` UV resolution is deferred to the per-face-materials slice

## Freshness Research
No external dependency changes.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders uv-generation` — verify UV computation
- **Option B:** `/wf-implement texture-material-shaders canvas-textures` — proceed to next slice
