---
schema: sdlc/v1
type: intake
slug: texture-material-shaders
status: complete
stage-number: 1
created-at: "2026-04-11T22:00:00Z"
updated-at: "2026-04-11T22:15:00Z"
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  next: 02-shape.md
next-command: wf-shape
next-invocation: "/wf-shape texture-material-shaders"
---

# Intake

## Restated Request

Implement textures, materials, and shaders for the Isometric library. Faces currently
render as flat colors only. The new system should support bitmap textures mapped onto
isometric faces, a material abstraction (flat color → textured → shader), and per-face
material assignment. Both Canvas and WebGPU render modes must get texture support in
this effort. The material/texture/shader types should live in a new `isometric-shader`
module, separate from the existing compose and webgpu modules.

## Intended Outcome

Users can apply bitmap textures to isometric shapes with per-face control (e.g., grass
on top, dirt on sides of a Prism). The material system follows progressive disclosure:
simple flat colors still work unchanged, textured materials add bitmap mapping, and the
architecture supports shader effects in the future. Both Canvas (BitmapShader + Matrix)
and WebGPU (UV-interpolated fragment shader) render paths produce visually correct
textured output.

## Primary User / Actor

Library consumers building isometric scenes who want visual richness beyond flat colors —
game developers, visualization builders, creative coding projects.

## Known Constraints

- **New module:** `isometric-shader` — material types, texture sources, UV generation
  live here. Keeps core lean and rendering backends decoupled.
- **Canvas approach:** BitmapShader + Matrix.setPolyToPoly (research-recommended,
  hardware-accelerated via Skia, all API levels).
- **WebGPU approach:** UV attributes in vertex buffer, texture atlas as GPU texture,
  WGSL fragment shader with textureSample.
- **Affine mapping is correct:** Isometric = orthographic projection, so affine texture
  mapping produces mathematically correct results (no perspective correction needed).
- **Branch:** `feat/texture` from `feat/webgpu` (already exists).
- **API design:** Must follow `docs/internal/api-design-guideline.md` — progressive
  disclosure, sensible defaults, backward compatibility.
- **Backward compatibility:** Existing `Shape(shape, color)` API continues working.
  New `Shape(shape, material)` overload added alongside.

## Assumptions

- The research in `TEXTURE_SHADER_RESEARCH.md` is current and accurate.
- Prism is the most common shape and should be the first to get UV generation.
- Texture loading/caching is needed for both render paths.
- The existing `RenderCommand` can be extended with material and UV data.
- AGSL (API 33+ Canvas shaders) is not needed initially — Canvas gets textures via
  BitmapShader, advanced shader effects are available via WebGPU WGSL.

## Product Owner Questions Asked

1. Appetite? → Large
2. Module name? → isometric-shader
3. Canvas texture approach? → BitmapShader + Matrix
4. AGSL scope? → Deferred (WGSL covers shader effects in WebGPU mode)
5. Success criteria? → Textured Prism with per-face materials in sample app
6. Non-goals? → None specified
7. WebGPU texture priority? → Both Canvas and WebGPU in same effort

## Product Owner Answers

See `po-answers.md` for full details.

## Unknowns / Open Questions

- Texture atlas strategy: Shelf packing vs MaxRects? Decide during shaping.
- WebGPU texture arrays vs atlases? Research favors atlases for memory efficiency.
- UV generation for non-Prism shapes (Cylinder, Octahedron) — defer to later slices?
- Texture compression (ETC2/ASTC) — include or defer?
- How to handle texture loading lifecycle in Compose (remember, LaunchedEffect)?

## Dependencies / External Factors

- `feat/webgpu` branch must be stable (it is — 24/24 benchmarks, review fixes applied).
- WebGPU vertex layout needs UV attribute extension (currently has position + color only).
- WGSL fragment shader needs textureSample support (new shader code).
- AndroidX WebGPU alpha API for texture creation/upload.

## Risks if Misunderstood

- **Scope creep:** The research doc covers 4 phases and 13 sections. Without slicing,
  this could sprawl. Must be incremented: Material types → UV gen → Canvas textures →
  WebGPU textures → atlas → per-face → shader effects.
- **Module boundary:** If `isometric-shader` depends on Android Bitmap types, it won't
  be pure Kotlin/multiplatform. Material types should be platform-agnostic; texture
  loading should be platform-specific.
- **WebGPU vertex layout change:** Adding UV attributes changes the vertex buffer
  stride, which affects all 5 compute pipeline stages. Must be coordinated.

## Success Criteria

- Textured Prism in sample app with per-face materials (grass top, dirt sides)
- Canvas render mode: BitmapShader + Matrix produces correct affine-mapped textures
- WebGPU render mode: UV-interpolated fragment shader renders textured faces
- Existing `Shape(shape, color)` API unchanged (backward compatible)
- New `Material` sealed interface in `isometric-shader` module
- Unit tests for UV generation, material resolution, texture mapping math
- Snapshot tests for textured Prism (Paparazzi)

## Out of Scope for Now

- No explicit exclusions from the user. The following are implicitly deferred based on
  the phased research plan and will be considered during slicing:
  - AGSL RuntimeShader (API 33+ Canvas shaders)
  - Texture compression (ETC2/ASTC)
  - Animated textures
  - Procedural texture generation
  - Normal map lighting

## Freshness Research

- **Source:** `docs/internal/research/TEXTURE_SHADER_RESEARCH.md`
  Why it matters: Comprehensive research covering all techniques, API patterns, and
  implementation strategies for both Canvas and WebGPU.
  Takeaway: Affine mapping is correct for isometric. BitmapShader + Matrix for Canvas,
  UV-interpolated textureSample for WebGPU. Material sealed interface with progressive
  disclosure. Research is thorough and current.

- **Source:** AndroidX WebGPU alpha (`vendor/androidx-webgpu/`)
  Why it matters: Texture creation/upload API needed for WebGPU path.
  Takeaway: Verify texture creation API in vendor source during planning.

## Recommended Next Stage

- **Option A (default):** `/wf-shape texture-material-shaders` — Large appetite with
  multiple render backends and a new module. Needs shaping to define acceptance criteria,
  edge cases, and module boundaries before slicing.
- **Option B:** `/wf-slice texture-material-shaders` — Skip shape if the research doc
  already provides sufficient spec. Risk: module boundaries and API surface not yet
  defined precisely enough.
