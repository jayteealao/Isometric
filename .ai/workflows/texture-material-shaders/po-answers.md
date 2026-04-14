---
schema: sdlc/v1
type: po-answers
slug: texture-material-shaders
---

# Product Owner Answers

## 2026-04-11 — Intake

**Branch strategy:** Dedicated — `feat/texture` from `feat/webgpu` (already created)

**Appetite:** Large — multiple days, needs slicing and incremental delivery

**Module name:** `isometric-shader` — new module for material/texture/shader abstractions

**Canvas texture approach:** BitmapShader + Matrix (research-recommended, hardware-accelerated)

**AGSL scope:** User asked "do we also need AGSL if we already have WGSL with WebGPU?"
- Clarified: AGSL = Canvas shaders (API 33+), WGSL = WebGPU shaders. Different render paths.
- Decision: AGSL deferred implicitly — not explicitly requested. Canvas gets textures via BitmapShader, shader effects via WebGPU WGSL.

**Success criteria:** Textured Prism in sample app with per-face materials (grass top, dirt sides)

**Non-goals:** None specified — user has no exclusions at present

**WebGPU texture priority:** Both Canvas and WebGPU get texture support in this effort (not Canvas-first)

## 2026-04-11 — Plan (directed fix)

**Module dependency policy:** `isometric-compose` must be usable without `isometric-shader` and must not depend on it. The dependency arrow is reversed: `isometric-shader` depends on `isometric-compose`.

**Composable API strategy:** Instead of adding a `material` parameter to the existing `Shape()` composable in `isometric-compose`, provide overloaded `Shape(geometry, material)` composables in `isometric-shader` that accept `IsometricMaterial` instead of `IsoColor`.

## 2026-04-13 — Slice (api-design-fixes)

**Slice granularity:** One slice — all 25 findings from `07-review-webgpu-textures-api.md` in a single `api-design-fixes` slice. No sub-slicing; the findings are interdependent enough that splitting adds overhead without benefit.

**TM-API-2 (UvTransform application):** Implement now — apply `TextureTransform` (renamed from `UvTransform`) in `computeAffineMatrix` on the Canvas path. WebGPU transform deferred to a future slice.

**TM-API-10 (Shape() name collision):** Consolidate — the `isometric-shader` `Shape()` becomes the superset, accepting both `color: IsoColor` and `material: IsometricMaterial` as alternatives. `isometric-compose.Shape(color)` unchanged; no import alias needed for shader users. **Constraint preserved:** `isometric-compose` must not depend on `isometric-shader`.

**TM-API-13 (rename BitmapSource → Bitmap):** Apply now — branch not merged to master, no external consumers. Direct rename, no deprecation cycle.

**TM-API-14 (rename UvTransform → TextureTransform):** Apply now — same rationale. Add companion factory functions: `tiling(horizontal, vertical)`, `rotated(degrees)`, `offset(u, v)`. Rename parameter `uvTransform` → `transform` at all call sites.

## 2026-04-13 — Architecture Decision (post-slice conversation)

**Revised module architecture for `FlatColor` and `Shape()` — supersedes prior
Shape() consolidation approach in po-answers 2026-04-11 plan entry:**

1. `FlatColor(color: IsoColor) : MaterialData` → moves to `isometric-core`. Pure JVM,
   no Android dependency. `data class`, not `value class` (value classes box when used
   as interface type — allocation benefit doesn't materialize in the render pipeline).

2. `Shape(geometry, material: MaterialData)` → `isometric-compose` replaces
   `Shape(geometry, color: IsoColor)`. Breaking change accepted for minor bump.

3. `fun IsoColor.asMaterial(): FlatColor` convenience bridge → `isometric-compose`.
   Compose Color conversion also lives here (Android-only extension).

4. `IsometricMaterial` in `isometric-shader` loses `FlatColor`. Sealed over
   `Textured` + `PerFace` only. All `IsometricMaterial.FlatColor` references in
   `isometric-shader` update to import `FlatColor` from `isometric-core`.

5. Dependency graph unchanged: `isometric-core` ← `isometric-compose` ←
   `isometric-shader` ← `isometric-webgpu`. No new edges, no cycles.

**`FlatColor` as `value class` rejected:** Interfaces box value classes. `FlatColor`
lives its entire life as `MaterialData?` in `RenderCommand` and `Shape()` parameters.
Boxing happens either way — `data class` gives `copy()` and `equals()` for free.

## 2026-04-14 — Extend (webgpu-uv-transforms slice)

**Transform operations in scope:** All three — scale/tiling (`scaleU`/`scaleV`), offset
(`offsetU`/`offsetV`), and rotation (`rotationDegrees`). Full `TextureTransform` parity
with the Canvas path.

**Per-face independent transforms:** In scope. Each face carries its own `TextureTransform`
in the GPU buffer.

**Dependency:** After `api-design-fixes` completes. Picks up the final `TextureTransform`
API (renamed fields, factory companions, `IDENTITY` constant) so no mid-flight reconciliation.

**Acceptance bar:** Visual parity with Canvas — same `TextureTransform` produces matching
output in both render modes, verified by side-by-side screenshot.

**Explicitly out of scope:** `onTextureLoadError` plumbing into `GpuTextureManager`;
UV gen for non-Prism shapes; animated per-frame transforms.

## 2026-04-14 — Extend round 2 (error callback + UV gen shape slices)

**onTextureLoadError WebGPU plumbing:** Unified — forward the existing callback from
`ProvideTextureRendering` into `GpuTextureManager`. No new API surface; same callback
covers both render paths.

**UV gen shapes in scope:** All non-Prism shapes — Cylinder, Pyramid, Stairs, Knot,
Octahedron.

**UV gen slice split:** One slice per shape (incremental delivery; shapes with complex
UV don't block simpler ones).

**Dependencies:** Both `webgpu-texture-error-callback` and all UV gen slices depend on
`api-design-fixes` completing first. UV gen slices also depend on `uv-generation` (the
original Prism UV system they extend).

