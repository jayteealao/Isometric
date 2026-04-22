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

## 2026-04-19 — Extend (webgpu-ngon-faces)

Surfaced during `uv-generation-cylinder` verify pass 2 (Maestro re-run on user request):
Full WebGPU screenshot shows the cylinder top cap rendered as a partial wedge — the
documented `vertices > 6` truncation in `GpuUvCoordsBuffer`'s fixed 48-byte stride.
User explicitly chose to queue a follow-up slice rather than accept the caveat or
lower the sample's vertex count.

**Shape scope:** **All N-gon faces** — cylinder caps + stairs zigzag sides + knot.
Fix the general N>6 case once rather than in separate slices. The TODO in
`GpuUvCoordsBuffer.kt:48-56` explicitly lists all three as current victims of the
stride cap; one slice avoids repeated touches of the buffer.

**Approach:** **Offset + length indirection.** Pack UVs tightly into one linear
buffer; maintain a parallel per-face `(offsetBytes, lengthFloats)` table. WGSL
does one table lookup per face, then indexes into the packed buffer. Rejected:
variable-stride (wastes memory when a single 24-gon forces all ~100 quads to use
192-byte stride) and defer-to-plan (user wanted direction locked at slice level).

**Max vertex count:** **24** — matches existing `require(faceVertexCount in 3..24)`
in `RenderCommand.init` and `require(vertices in 3..24)` in `Cylinder.init`. No
new upper bound to introduce; any shape the library can currently construct fits.

**Dependencies:** **Wait for `uv-generation-stairs` and `uv-generation-knot`** to
land before planning this slice. Rationale: the buffer redesign should be
measured against the full UV-emission surface (stairs zigzag sides with real UVs,
knot per-face UVs), not against placeholders. Avoids re-reworking after the UV
generators arrive with their actual UV shapes. Also depends on
`uv-generation-cylinder` (landed, verify-pass-2-complete).

**Slice slug:** `webgpu-ngon-faces` (user-proposed; alternative `webgpu-uv-variable-stride`
considered but rejected — offset+length indirection isn't a stride at all).

**Evidence:** `.ai/workflows/texture-material-shaders/verify-evidence/screenshots/verify-cylinder-webgpu-pass2.png`
— partial-wedge cap visible on both textured (brick) and cylinderPerFace (red) tops.

## 2026-04-22 — Plan (webgpu-ngon-faces)

Discovery interview, 9 questions across 3 AskUserQuestion rounds.

**Round 1 (4 questions):**

1. **Scope of lift:** **Lift everything atomically.** Single coordinated rewrite of GpuUvCoordsBuffer + SceneDataPacker + TransformedFace struct + TransformCullLightShader.WGSL + TriangulateEmitShader.WGSL — all to 24 vertices. Larger surface but matches AC1-AC3 truthfully. Sub-agent confirmed 6-vert cap is mirrored in the M3 transform shader's output struct, so lifting only the UV buffer is necessary but not sufficient.

2. **Compat mode handling:** **Request maxStorageBuffersPerShaderStage=8, fail loud.** GpuContext init requests `requiredLimits = { maxStorageBuffersPerShaderStage: 8 }`. If adapter can't satisfy, throw at init with a clear error. Most users on Vulkan path; explicit failure beats silent degradation. Rejected: header-in-buffer single-binding (more shader arithmetic) and detect+dual-path (two code paths to maintain).

3. **Offset table entry layout:** **`vec2<u32>(offsetPairs, vertCount)`.** Offset in UV-pair units (each = 8 bytes), count in vertex count. Matches `RenderCommand.faceVertexCount` directly; shader does `pool[offset + vIdx]`. 8 bytes per face. Rejected: bytes-only (extra arithmetic per fetch) and packed u32 with bitmask (future-incompatible if N ever > 256).

4. **WGSL test approach:** **Extended regex/structural tests.** Match the existing TriangulateEmitShaderTest pattern — assert binding declarations, type names, structural invariants via regex on the WGSL string. Zero new tooling. AC6 becomes "structural validation" not "compile validation". Rejected: Naga CLI subprocess (binary dependency on dev + CI machines) and snapshot-only (regression-only, no semantic check).

**Round 2 (4 questions):**

5. **Step sequencing:** **Bottom-up: structs → packer → shaders.** Order: (1) Expand TransformedFace + FACE_DATA_BYTES constants. (2) Update SceneDataPacker (lift coerceAtMost). (3) Rewrite GpuUvCoordsBuffer to dual-buffer. (4) Update WGSL bindings + indirect lookup. Each step compilable; shader changes last so the full WGSL story is told once with all CPU-side scaffolding in place. Rejected: top-down (intermediate states won't compile) and TDD (slower for a known-design rewrite).

6. **Sample fixture:** **Add a third cylinder at vertices=24.** Three columns in `TexturedCylinderScene`: textured 12-vert (existing), perFace 12-vert (existing), textured 24-vert (new). Updated caption notes the new cap rendering. Maestro flow already taps the Cylinder tab — extend assertions only. Rejected: replacing the right cylinder (loses 12-vert per-face baseline) and dedicated N-gon tab (broader UI churn).

7. **Paparazzi strategy:** **Add new snapshots only for changed shapes.** Add cylinderTextured24 + stairsTextured-stepCount5 + knotTextured snapshots; skip regenerating uncommitted baselines (only 2 of ~15 are committed today). Smallest scope. AC4 weakens to "changed-shape coverage + Canvas inspection for unchanged"; documented as such in the verify plan. Rejected: regenerate-all (out-of-scope baseline cleanup) and defer-baselines-doc-slice (postpones the only meaningful regression guard for this rewrite).

8. **Adjacent fixes scope:** **Omnibus cleanup — fix BOTH I-2 and the benchmark.** Slice expands to include: (a) RenderCommandTriangulator non-convex fan defect (stairs verify I-2 — CPU triangulator path) and (b) `:isometric-benchmark/BenchmarkScreen.kt:165` Shape API drift (color → material). User explicitly chose the broader scope over keeping the slice tight. Rationale: both have been deferred across multiple slice cycles; bundling avoids another round of context-loading. Rejected: neither (kicks the can again) and benchmark-only (leaves I-2 still pending).

**Round 3 (1 question):**

9. **I-2 fix approach:** **Ear-clipping triangulation in `RenderCommandTriangulator`.** General O(n²) algorithm, handles any simple polygon. ~50 lines. Future-proof for any non-convex face that may appear. Slight CPU cost but only on the CPU triangulator path (used by tests + Canvas fallback paths). Rejected: shape-aware Stairs decomposition (Stairs-only fix; doesn't generalize) and both (over-engineered for current scope — start with the general fix).

**Slice scope after plan interview:** Original scope (UV buffer + WGSL + transform shader struct expansion + sample fixture + Maestro + Paparazzi for 3 changed shapes) PLUS omnibus additions (RenderCommandTriangulator ear-clipping + benchmark BenchmarkScreen.kt:165 Shape API repair). Complexity remains `l` but the surface has grown — plan must reflect the broader set in §Likely Files / Areas to Touch and §Step-by-Step Plan.

