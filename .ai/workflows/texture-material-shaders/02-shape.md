---
schema: sdlc/v1
type: shape
slug: texture-material-shaders
status: complete
stage-number: 2
created-at: "2026-04-11T22:20:00Z"
updated-at: "2026-04-11T22:20:00Z"
docs-needed: true
docs-types: [reference, how-to, explanation]
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  intake: 01-intake.md
  next: 03-slice.md
next-command: wf-slice
next-invocation: "/wf-slice texture-material-shaders"
---

# Shape: Textures, Materials & Shaders

## Problem Statement

The Isometric library renders all faces as flat-colored polygons. Users cannot apply
bitmap textures, per-face materials, or shader effects to isometric shapes. The current
data model (`IsoColor` on `RenderCommand`) has no concept of texture coordinates, material
identity, or texture resources. Both Canvas and WebGPU render paths need texture support.

## Primary Actor / User

Library consumers building isometric scenes — game developers who need textured tiles
(grass, dirt, brick), visualization builders who need patterned surfaces, creative coders
who want visual richness beyond flat colors.

## Desired Behavior

### Hero Scenario

```kotlin
// Simple: flat color (unchanged)
Shape(Prism(origin), IsoColor.BLUE)

// Textured: single material for all faces
Shape(Prism(origin), material = textured(R.drawable.brick))

// Per-face: different materials on different faces
Shape(
    Prism(origin),
    material = perFace {
        top = textured(R.drawable.grass)
        sides = textured(R.drawable.dirt)
    }
)
```

Both Canvas and WebGPU render modes produce visually correct textured output. The
texture is affine-mapped onto each isometric face (mathematically correct for
orthographic projection — no perspective correction needed).

### Material Type Hierarchy

```
sealed interface IsometricMaterial
  ├── FlatColor(color: IsoColor)           ← current behavior, zero overhead
  ├── Textured(source: TextureSource, tint, uvTransform)  ← bitmap textures
  └── PerFace(top, sides, bottom, default) ← per-face-group materials
```

`TextureSource` is a sealed interface for resource references:
```
sealed interface TextureSource
  ├── Resource(@DrawableRes resId: Int)
  ├── Asset(path: String)
  └── Bitmap(bitmap: android.graphics.Bitmap)
```

### Module Structure

```
isometric-shader (NEW)
  ├── IsometricMaterial.kt      ← sealed interface + FlatColor, Textured, PerFace
  ├── TextureSource.kt          ← sealed interface for texture references
  ├── UvCoord.kt                ← data class for per-vertex UV coordinates
  ├── UvGenerator.kt            ← UV generation per shape type
  └── TextureCache.kt           ← LRU cache for loaded bitmaps
```

Depends on: `isometric-core` (for Shape, Path, Point types).
Depended on by: `isometric-compose` (Canvas renderer), `isometric-webgpu` (GPU renderer).

## Acceptance Criteria

### AC1: Material Type System
- Given a new `isometric-shader` Gradle module
- When the module is added to the project
- Then it compiles and exposes `IsometricMaterial`, `TextureSource`, `UvCoord` types
  that `isometric-compose` and `isometric-webgpu` can depend on

### AC2: Backward-Compatible Composable API
- Given the existing `Shape(geometry, color)` composable
- When no material parameter is provided
- Then existing code compiles and renders identically to before (flat color)

### AC3: Textured Shape Composable
- Given a `Shape(geometry, material = textured(R.drawable.brick))` call
- When the scene renders in Canvas mode
- Then each face displays the bitmap texture affine-mapped to the isometric face polygon
  using BitmapShader + Matrix

### AC4: Per-Face Materials
- Given a `Shape(geometry, material = perFace { top = textured(grass); sides = textured(dirt) })`
- When the scene renders
- Then the top face shows the grass texture and side faces show the dirt texture
- And back/bottom faces use the default material (flat color or a specified default)

### AC5: UV Generation for Prism
- Given a Prism shape
- When UV coordinates are generated
- Then each face gets UV coords that map [0,0]→[1,1] across the face rectangle
- And the UVs account for isometric foreshortening (top face is a parallelogram in
  screen space but the UV maps the full texture rectangle onto it)

### AC6: WebGPU Textured Rendering
- Given a textured Prism in WebGPU render mode
- When the scene renders
- Then the WGSL fragment shader samples from the bound texture using per-vertex UVs
- And the output matches the Canvas render (affine-mapped, same texture, correct colors)

### AC7: Texture Loading & Caching
- Given a `TextureSource.Resource(R.drawable.brick)` used by multiple shapes
- When the texture is loaded
- Then the bitmap is loaded once and cached (LRU)
- And subsequent shapes reuse the cached bitmap

### AC8: Missing Texture Fallback
- Given a `TextureSource.Resource(R.drawable.nonexistent)`
- When texture loading fails
- Then the face renders with a visible fallback (magenta/black checkerboard or flat tint)
- And no crash occurs

### AC9: Sample App Demo
- Given the sample app running on SM-F956B
- When the user navigates to a textured scene
- Then a Prism displays with grass top and dirt sides in both Canvas and WebGPU modes

## Non-Functional Requirements

- **Performance:** Textured rendering at N=100 faces must stay under 16ms frame time
  in Canvas mode. WebGPU mode must not regress from current benchmarks.
- **Memory:** Texture cache must respect device memory. LRU eviction with configurable
  max size. Default budget: 32MB for texture cache.
- **Backward compatibility:** No breaking changes to public API. New material parameter
  is additive (default null or FlatColor).
- **API design:** Follow `docs/internal/api-design-guideline.md` — progressive
  disclosure (§2), sensible defaults (§3), make invalid states hard to express (§6).

## Edge Cases / Failure Modes

| Case | Expected Behavior |
|------|-------------------|
| Texture source not found | Fallback to checkerboard or flat tint |
| Shape with 0 faces | No crash, no rendering |
| Single-face shape (triangle) | UV generation still works (3-vertex affine) |
| Very large texture (4096x4096) | Load succeeds, log warning about memory |
| Null material on Shape | Falls back to `color` parameter or default color |
| PerFace with missing face role | Uses `default` material for unspecified faces |
| Canvas mode on API < 33 | BitmapShader works on all API levels (no AGSL needed) |
| WebGPU mode, device without Vulkan | WebGPU unavailable — falls back to Canvas |
| Multiple shapes sharing one texture | Single cached bitmap, shared across all |
| Texture with alpha channel | Alpha preserved in both Canvas and WebGPU |

## Affected Areas

### New Module
- `isometric-shader/` — new Gradle module with material types, UV generation, texture cache

### Modified Modules
- **isometric-core:** `RenderCommand` gets `material` and `uvCoords` fields. `Path` may
  get optional UV data. Shape subclasses (Prism first) get face-type metadata.
- **isometric-compose:** `ShapeNode` gets `material` property. `Shape()` composable gets
  `material` parameter. `CanvasRenderBackend` gets textured draw path (BitmapShader).
  `SceneCache.rebuildForGpu` passes material through.
- **isometric-webgpu:** `SceneDataPacker` writes UV coords and texture index into
  FaceData. `GpuTriangulateEmitPipeline` emits actual UVs (not zeros).
  `IsometricFragmentShader` binds texture/sampler and calls `textureSample`.
  New texture upload pipeline for GPU-side textures.
- **app (sample):** New textured scene demo activity/screen.

### Existing Scaffolding (already in place)
- `SceneDataPacker.FaceData.textureIndex` — stub field, emits `NO_TEXTURE` sentinel
- `IsometricFragmentShader` — already receives `uv: vec2<f32>` at location 1, ignores it
- `IsometricVertexShader` — already has `@location(2) uv` and passes through
- `TriangulateEmitShader.writeVertex` — UV slots 6/7 exist, always write `0u`

## Dependencies / Sequencing Notes

1. **Material types first** — `IsometricMaterial`, `TextureSource`, `UvCoord` in
   `isometric-shader` before anything else. All other work depends on these types.
2. **UV generation** depends on material types and shape face-type metadata.
3. **Canvas rendering** depends on material types, UV generation, and texture loading.
4. **WebGPU rendering** depends on all of the above plus shader/pipeline changes.
5. **Per-face materials** depend on face-type metadata on shapes (Prism face roles).
6. **Sample app** depends on both render paths working.

Natural slice boundaries: types → UV → Canvas → WebGPU → per-face → sample

## Questions Asked This Stage

None — all decisions were captured during intake.

## Answers Captured This Stage

None needed.

## Out of Scope

- AGSL RuntimeShader (API 33+ Canvas custom shaders) — deferred
- Texture compression (ETC2/ASTC) — deferred
- Animated textures — deferred
- Procedural texture generation — deferred
- Normal map lighting — deferred
- Texture atlas packing (Shelf/MaxRects) — deferred to post-MVP; single-texture-per-face
  is sufficient for initial delivery
- UV generation for non-Prism shapes (Cylinder, Pyramid, etc.) — deferred to later slices
- `Material.Shader` type for custom shader effects — deferred

## Definition of Done

- [ ] `isometric-shader` module exists and compiles
- [ ] `IsometricMaterial` sealed interface with `FlatColor`, `Textured`, `PerFace` variants
- [ ] `TextureSource` sealed interface with `Resource`, `Asset`, `Bitmap` variants
- [ ] UV generation for Prism faces (4-vertex quad mapping)
- [ ] `RenderCommand` extended with material/UV data
- [ ] `Shape()` composable accepts `material` parameter (backward compatible)
- [ ] Canvas renderer draws textured faces via BitmapShader + Matrix
- [ ] WebGPU renderer draws textured faces via UV-interpolated textureSample
- [ ] `TextureCache` with LRU eviction
- [ ] Missing texture fallback (no crash, visible indicator)
- [ ] Unit tests: UV generation, material resolution, texture mapping math
- [ ] Snapshot tests: textured Prism in Canvas mode (Paparazzi)
- [ ] Sample app: textured Prism with grass top + dirt sides
- [ ] All existing tests pass (no regressions)
- [ ] `apiCheck` passes

## Documentation Plan

### 1. Reference: Material API
- **Type:** reference
- **Audience:** competent user / maintainer
- **Must cover:** `IsometricMaterial` variants, `TextureSource` types, `UvCoord`,
  `UvTransform`, `TextureCache` configuration, `perFace {}` DSL
- **Must NOT cover:** implementation internals, shader code, Canvas vs WebGPU differences
- **Target location:** `docs/api/materials.md` or KDoc on public types

### 2. How-To: Adding Textures to Shapes
- **Type:** how-to
- **Audience:** beginner / competent user
- **Must cover:** loading a texture, applying to a shape, per-face materials, tinting
- **Must NOT cover:** theory of affine mapping, UV math internals, WebGPU pipeline
- **Target location:** `docs/guides/textures.md`

### 3. Explanation: How Texture Mapping Works in Isometric
- **Type:** explanation
- **Audience:** maintainer / contributor
- **Must cover:** why affine mapping is correct, Canvas vs WebGPU rendering paths,
  material resolution and fallback chain, UV generation strategy
- **Must NOT cover:** step-by-step instructions, API reference tables
- **Target location:** `docs/internal/explanations/texture-mapping.md`

## Freshness Research

- **Source:** `docs/internal/research/TEXTURE_SHADER_RESEARCH.md`
  Why it matters: Primary research document for all texture/material/shader work.
  Takeaway: Comprehensive and current. Affine mapping confirmed correct for isometric.
  BitmapShader + Matrix for Canvas, UV textureSample for WebGPU. Material hierarchy
  with progressive disclosure. Scaffolding already exists in codebase (UV slots in
  shaders, textureIndex stub in SceneDataPacker).

- **Source:** Codebase exploration (see Explore agent report)
  Why it matters: Confirms existing scaffolding and integration points.
  Takeaway: UV slots exist in vertex/fragment shaders (always zero). textureIndex field
  exists in SceneDataPacker with NO_TEXTURE sentinel. RenderCommand has baseColor split.
  The four concrete gaps: RenderCommand fields, ShapeNode material param, SceneDataPacker
  UV population, fragment shader textureSample binding.

## Recommended Next Stage

- **Option A (default):** `/wf-slice texture-material-shaders` — Large scope with clear
  natural boundaries (types → UV → Canvas → WebGPU → per-face → sample). Multiple
  acceptance criteria clusters. Needs incremental delivery.
- **Option B:** `/wf-plan texture-material-shaders` — Skip slicing if the user wants to
  plan the entire effort as one unit. Risk: too many moving parts for a single plan.
