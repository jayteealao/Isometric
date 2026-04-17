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

## 2026-04-15 — Plan (webgpu-uv-transforms)

**Buffer strategy:** Compose `TextureTransform` + atlas region into a single `mat3x2<f32>`
on the CPU. Expand `sceneUvRegions` binding 5 from `vec4<f32>` (16 bytes) to `mat3x2<f32>`
(24 bytes) per face. Single matrix-vector multiply in WGSL M5 shader.

**WGSL type:** `mat3x2<f32>` native WGSL type — `uvMatrix * vec3(baseUV, 1.0)` yields `vec2`
directly.

**IDENTITY fast path:** When `transform == TextureTransform.IDENTITY`, skip trig and write
the atlas-only diagonal matrix directly.

**Test strategy:** Unit packing tests (5 cases: IDENTITY, tiling, rotation, offset, PerFace)
+ WGSL string content tests + Maestro flow with screenshots for manual AC visual comparison.

**Maestro scope:** Flow takes screenshots before/after mode switch (Canvas + Full WebGPU).
Manual diff is the AC pass criterion for AC1–AC4.

**Layout constant:** Add `UV_REGION_STRIDE = 24` to `SceneDataLayout`.

**Implementation order:** WGSL-first — define the mat3x2 contract in the shader, then write
the Kotlin packing code to match.

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

## 2026-04-16 — Plan (webgpu-texture-error-callback)

**Bridge strategy:** New `LocalTextureErrorCallback = staticCompositionLocalOf { null }`
CompositionLocal in `isometric-shader` (`ProvideTextureRendering.kt`). `ProvideTextureRendering`
sets it alongside `LocalMaterialDrawHook`. `WebGpuRenderBackend.Surface()` reads it and updates
`@Volatile var onTextureLoadError` on `WebGpuSceneRenderer` via `SideEffect`. Forwarding lambda
passed to `GpuFullPipeline` → `GpuTextureManager`. No new public API beyond the CompositionLocal.

**Thread dispatch:** Dispatch callback to main thread via `Handler(Looper.getMainLooper()).post { ... }`
from inside `GpuTextureManager`. `Log.w` is kept — callback is additive.

**Error scope:** All load failure paths:
- Site 1: `Log.w` else-branch (unsupported `TextureSource` type) — fires per individual source
- Site 2: `atlasManager.rebuild(entries)` failure — fires for all keys in `entries`
- (The "empty entries" case is already covered by Site 1 firing per skipped source)

## 2026-04-16 — Plan (uv-generation-* batch discovery)

**Batch strategy:** All 5 shape UV slices (cylinder, pyramid, stairs, knot, octahedron) planned in parallel
using sub-agents. Full research playbook per slice (affected code + test infra + web research).

**webgpu-texture-error-callback handoff:** Skip entirely. Follow the pattern of original slices where
per-slice handoff was handled via the master `08-handoff.md`. Mark `progress.webgpu-texture-error-callback`
as `complete` (handoff skipped).

**PerFace scope for non-Prism shapes:** Per-shape PerFace variants. Abstract `IsometricMaterial.PerFace`
base class with shape-specific subclasses: existing `PerFace` becomes `PerFace.Prism`, new `PerFace.Cylinder(top, bottom, side)`,
`PerFace.Pyramid(base, laterals: Map<Int, MaterialData>, default)`, `PerFace.Stairs(tread, riser, side, default)`,
`PerFace.Octahedron(byIndex: Map<Int, MaterialData>, default)`. Knot gets no `PerFace.Knot` variant
(documented as unsupported).

**Variable vertex count:** Add `faceVertexCount: Int` field to `RenderCommand`. `uvCoords` length is
`2 × faceVertexCount`. Canvas + WebGPU consumers must stop assuming 4 vertices per face.

**UV dispatch:** `UvCoordProvider.forShape(geometry: Shape): UvCoordProvider?` factory in `isometric-shader`.
Single `when (geometry)` dispatch grows with each slice. Replaces the current `geometry as? Prism` gate
in `IsometricMaterialComposables.Shape()`.

**Knot handling:** Bag-of-prisms reuse. `UvGenerator.forKnotFace()` delegates to `forPrismFace()` for
the 18 sub-prism paths (indices 0–5, 6–11, 12–17) and applies planar UV to the 2 custom quads (18–19).
No `KnotFace` enum; no `PerFace.Knot` variant. Documented as `perFace` unsupported on Knot.

**Cylinder seam:** Duplicate seam vertices in the `Cylinder` shape (core change) so adjacent side quads
have distinct UV u-values (1.0 and 0.0). Requires a `Cylinder.paths` structural change; core plan must
identify all callers that index by `paths.size` or vertex count.

**Pyramid base:** Add a 5th path (rectangular base quad) to `Pyramid.createPaths()`. Breaking change for
anyone iterating `pyramid.paths`; plan must identify affected callers (tests, snapshot tests, existing
renders) and update them.

**Stairs DSL:** `PerFace.Stairs(tread, riser, side, default)` — logical groups only, `stepCount`-independent.
All treads share one material, all risers share one, both sides share one.

**Prerequisite slice:** New `uv-generation-shared-api` slice lands shared infrastructure FIRST (full-surface
prereq): `RenderCommand.faceVertexCount`, abstract `PerFace` base, all 4 shape face enums
(`CylinderFace`, `PyramidFace`, `StairsFace`, `OctahedronFace`) in `isometric-core`, empty `PerFace.<Shape>`
variant stubs in `isometric-shader`, `UvCoordProvider.forShape()` factory skeleton. Each shape slice then
becomes purely additive.

**Delivery strategy:** No PR strategy per slice — PR strategy decided at slug level at ship time.
Per-slice plans focus on code changes only.

**Slice total:** 6 slices to plan (1 prereq + 5 shape slices). Implementation order: prereq → octahedron →
pyramid → cylinder → stairs → knot (simple to complex).

