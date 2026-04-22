---
schema: sdlc/v1
type: plan-index
slug: texture-material-shaders
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-22T19:01:04Z"
planning-mode: all
slices-planned: 16
slices-total: 16
implementation-order: [material-types, uv-generation, canvas-textures, webgpu-textures, per-face-materials, sample-demo, api-design-fixes, webgpu-uv-transforms, webgpu-texture-error-callback, uv-generation-shared-api, uv-generation-octahedron, uv-generation-pyramid, uv-generation-cylinder, uv-generation-stairs, uv-generation-knot, webgpu-ngon-faces]
conflicts-found: 3
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders webgpu-ngon-faces"
---

# Plan Index

## Slice Plan Summaries

### `material-types` (rev 3)
- **Files:** 7 (1 new, 6 modified) — rework of already-implemented steps 8-12
- **Steps:** 14 (steps 1-7 already done; steps 8-14 are the rework)
- **Strategy:** New `isometric-shader` Android library module with types + DSL (done).
  **Rework:** Remove compose→shader dependency. `isometric-compose` uses `MaterialData?`
  (core) on nodes, no material param on composables. `isometric-shader` depends on compose
  and provides overloaded `Shape(geometry, material)` composables.
- **Key risk:** Overload resolution between `Shape(geo, color: IsoColor)` and
  `Shape(geo, material: IsometricMaterial)` — types are unrelated, Kotlin handles cleanly.

### `uv-generation` (rev 1)
- **Files:** 7 (5 new, 2 modified)
- **Steps:** 8
- **Strategy:** `PrismFace` enum in `isometric-core`. `UvGenerator` in `isometric-shader`.
  UV wiring via `uvProvider` lambda on `ShapeNode` (set by shader's `Shape()` overload) —
  **not** via direct import of shader types in compose.
- **Key risk:** Prism face ordering stability across transforms — verified stable.

### `canvas-textures` (rev 2)
- **Files:** 11 (5 new source, 3 modified, 3 test)
- **Steps:** 16
- **Strategy:** `MaterialDrawHook` fun interface in compose (strategy injection pattern,
  same as `UvCoordProvider`). `TexturedCanvasDrawHook` in shader implements it. Hook is
  installed via `LocalMaterialDrawHook` CompositionLocal + `ProvideTextureRendering` composable.
  `NativeSceneRenderer` delegates to hook for material commands. `TextureCache`, `TextureLoader`
  in shader. `BitmapShader` + 3-point affine `Matrix.setPolyToPoly`.
- **Key risk:** Paparazzi rendering of native `BitmapShader` draw (LayoutLib limitation).

### `webgpu-textures` (rev 2)
- **Files:** 9 existing modified + 2 new
- **Steps:** 11 (Step 0: add `:isometric-shader` dep to webgpu)
- **Strategy:** Vertex stride grows 32->36 bytes (new `textureIndex` u32 at location 3,
  `@interpolate(flat)`). `GpuTextureStore` uploads bitmaps as `BGRA8Unorm` (matches Android
  native byte order — no CPU swizzle). Fragment shader: `if (textureIndex == NO_TEXTURE)
  return color; else textureSample` at **`@group(0)`** (render pipeline's only bind group).
  `SceneDataPacker` writes real `textureIndex` from `RenderCommand.material` (including
  `PerFace.default` unwrapping). Emit shader writes constant UV for standard Prism quads
  (identical to CPU-side uv-generation output). Compact per-face `texIndexBuffer` at
  emit shader binding 4.
- **Key risk:** Vertex stride change cascades to emit shader, render pipeline layout, and
  CPU-side triangulator. Must coordinate carefully.

### `per-face-materials`
- **Files:** 12 (5 new, 7 modified)
- **Steps:** 14
- **Strategy:** `PerFace.resolve(PrismFace)` maps face roles to materials. `perFace {}`
  DSL with `top`/`sides`/`bottom`/`front`/`back`/`left`/`right` properties. WebGPU
  multi-texture via texture atlas (Shelf packing, 512-2048px, 2px padding + half-pixel
  UV inset). FaceData extended 144→160 bytes with `uvOffset`/`uvScale` fields.
- **Key risk:** Atlas packing correctness (texture bleeding). Mitigated by padding +
  half-pixel correction.

### `sample-demo` (rev 1)
- **Files:** 8 (2 new, 6 modified — added `app/build.gradle.kts` for shader dep)
- **Steps:** 10 (added Step 0: add `:isometric-shader` dep to app)
- **Strategy:** `TexturedDemoActivity` with 4x4 grid of Prisms. Procedurally generated
  grass/dirt textures (64x64 bitmaps, no external assets needed). Three-button render
  mode toggle (Canvas / Canvas+GPU Sort / WebGPU). Added to `MainActivity` chooser.
- **Key risk:** Minimal — integration only.

### `api-design-fixes`
- **Files:** 16 (15 production, 3 androidTest files — unit test ShapeNode constructor calls are unchanged)
- **Steps:** 16
- **Strategy:** 25 findings from `07-review-webgpu-textures-api.md`. `IsoColor : MaterialData`
  (one-line change in core). `Shape(material: MaterialData)` in compose replaces `Shape(color: IsoColor)`.
  Shader `Shape(IsometricMaterial)` overload kept for UV provider setup — Kotlin picks it automatically
  for `IsometricMaterial` values. `IsometricMaterial.FlatColor` removed. `UvTransform` renamed to
  `TextureTransform` with factory companions and `init` validation. `TextureTransform` applied in
  `computeAffineMatrix` using `preConcat(T^-1)`. `TextureCache.CachedTexture` decoupled from
  `BitmapShader` (creation moves to hook, enabling per-material REPEAT/CLAMP tile mode).
  `BitmapSource` renamed `Bitmap`. `UvGenerator` and `UvCoord` made `internal`.
- **Key risk:** `PerFace.faceMap` type widens from `Map<PrismFace, IsometricMaterial>` to
  `Map<PrismFace, MaterialData>` — all per-face renderer when-switches must handle `is IsoColor`
  as a valid face material. TM-API-24 must land before TM-API-2 (REPEAT tile mode needed for
  TextureTransform tiling to render correctly).

### `webgpu-texture-error-callback` (new — 2026-04-16)
- **Files:** 5 (all modified)
- **Steps:** 9
- **Strategy:** Add `LocalTextureErrorCallback = staticCompositionLocalOf { null }` in
  `isometric-shader` (`ProvideTextureRendering.kt`). `ProvideTextureRendering` sets it
  alongside `LocalMaterialDrawHook`. `WebGpuRenderBackend.Surface()` reads it via
  `LocalTextureErrorCallback.current` and assigns to `@Volatile var` on `WebGpuSceneRenderer`
  via `SideEffect`. Forwarding lambda `{ src -> onTextureLoadError?.invoke(src) }` is passed
  to `GpuFullPipeline(ctx, onError)` → `GpuTextureManager(ctx, onError)`. Callback dispatched
  to main thread via `Handler(Looper.getMainLooper()).post { ... }`.
  Fires at 2 sites: (1) `Log.w` else-branch for unsupported source types; (2) atlas rebuild
  failure for all involved sources.
- **Key risk:** `LocalTextureErrorCallback` must be `public` (cross-module CompositionLocal) —
  regenerate `isometric-shader.api` with `apiDump` before `apiCheck`.
- **Plan:** `04-plan-webgpu-texture-error-callback.md`

### `uv-generation-shared-api` (new — 2026-04-17, prerequisite for all UV geometry slices)
- **Files:** 16 (4 create face-type files in core, 4 create test files, 8 modify/update)
- **Steps:** 9
- **Strategy:** Refactor `IsometricMaterial.PerFace` to abstract sealed class with 5
  subclasses (`Prism`, `Cylinder`, `Pyramid`, `Stairs`, `Octahedron`). Add
  `RenderCommand.faceVertexCount: Int = 4` at end of params (preserves positional calls).
  Add 4 shape face types to `isometric-core`: `CylinderFace`/`StairsFace`/`OctahedronFace`
  as enums, `PyramidFace` as **sealed class** (`BASE` object + `Lateral(index)` data class).
  Add `uvCoordProviderForShape(shape: Shape): UvCoordProvider?` factory returning
  Prism provider + null for all others. `when (m)` sub-dispatch in Canvas/WebGPU material
  consumers handles `PerFace.Prism` vs non-Prism variants. `GpuUvCoordsBuffer` guard
  uses `faceVertexCount` instead of `>= 8`.
- **Key decision:** `RenderCommand.faceType` stays `PrismFace?` (NOT generalized to a
  `ShapeFaceTag?` marker). Non-Prism shapes derive face via `command.originalShape as? <Shape>`
  + `paths.indexOf(command.originalPath)` + `<ShapeFace>.fromPathIndex(index)` in consumers.
  Trade-off: O(N) path lookup per command (N ≤ 22 for Stairs) vs zero new fields on RenderCommand.
- **Key risk:** `PerFace` rename is binary-breaking (JVM name changes). Pre-1.0 library;
  commit `.api` diff via `apiDump`.
- **Plan:** `04-plan-uv-generation-shared-api.md`

### `uv-generation-octahedron` (revised 2026-04-17T17:37Z — auto-review)
- **Files:** 7 (1 create test, 6 modify, 1 apiDump)
- **Steps:** 9
- **Strategy:** Purely additive after prereq. `UvGenerator.forOctahedronFace` returns
  canonical triangle UV `(0,0),(1,0),(0.5,1)` for all 8 faces — congruent equilateral
  triangles need no per-face orientation tracking. Touches five integration points:
  (1) adds `is Octahedron` branch to `uvCoordProviderForShape` in `UvCoordProviderForShape.kt`
  (not `IsometricMaterialComposables.kt`, which the prereq no longer owns);
  (2) replaces the `is PerFace.Octahedron -> default` arm in the shared
  `resolveForFace` extension (H-04 leverage pattern) with real
  `byIndex[faceType as? OctahedronFace] ?: default` dispatch;
  (3) expands `IsometricNode.kt:290` to emit `OctahedronFace.fromPathIndex(index)` so
  per-face dispatch actually fires for AC2;
  (4) adds `is PerFace.Octahedron` collection branch to
  `GpuTextureManager.collectTextureSources` so atlas picks up `byIndex.values` textures;
  (5) clears four `TODO(uv-generation-octahedron)` markers left by the shared-api prereq.
  `Octahedron.kt` is NOT modified.
- **Key risk:** `IsometricNode.kt:290` currently emits `faceType = null` for every
  non-Prism shape, so without the Step 4 expansion, AC2 per-face addressing would silently
  fail — every face resolves to `default`. Auto-review revision caught this.
- **Plan:** `04-plan-uv-generation-octahedron.md` (revision 1)

### `uv-generation-pyramid` (revised 2026-04-17T21:27Z — auto-review; BREAKING CHANGE)
- **Files:** 10 (1 core modify Pyramid, 1 core test update, 4 shader modify, 1 compose modify, 1 webgpu modify, 1 create shader test, 1 sample-app modify)
- **Steps:** 12
- **Strategy:** Add 5th path (rectangular base quad) to `Pyramid.createPaths()` — **breaking
  change** to Pyramid path count (4 → 5). Lateral UV uses constant `(0,1),(1,1),(0.5,0)` for
  all 4 laterals (apex at UV top). Base UV uses planar top-down projection. Wire `is Pyramid`
  into `uvCoordProviderForShape` + `IsometricNode.faceType` emission + `GpuTextureManager.collectTextureSources`
  + replace Pyramid arm in `resolveForFace` (4 shared surfaces). `PerFace.Pyramid.resolve()`
  is **already implemented** by shared-api — plan Step 4 is now a no-op. Add `pyramidPerFace {}`
  DSL alongside Prism's. Pyramid tab added to TexturedDemoActivity mirroring octahedron pattern.
  **Mixed-vertex-count is the first real I-02 stress test** (3-vert laterals + 4-vert base)
  — any remaining propagation gap surfaces visibly as split rendering between lateral/base.
- **Key risks:** (1) mixed-vertex-count I-02 stress test — first shape with within-shape
  vertex-count variation; any propagation gap surfaces visibly.
  (2) `IsometricEngineTest.kt:299` hard assertion on `Pyramid().paths.size == 4` must change to 5.
  (3) Paparazzi `pyramid()` + `sampleThree()` snapshots differ; regenerate.
  (4) `DocScreenshotGenerator.shapePyramid()` PNG differs; regenerate.
  (5) Lateral numbering is straight (`0→Lateral(0), 1→Lateral(1), ...`) per prereq —
  `Lateral(1)` is the opposite face of `Lateral(0)`, not adjacent. Already documented in PyramidFace KDoc.
- **Inherits 7 deferred HIGH findings** from `/wf-review uv-generation-octahedron`
  (CR-2/A-02/M-01, CR-3/R-05, CR-5/AC-04/AC-05, TEST-03/RS-05, TEST-04, M-02, M-04/A-01).
  Not fixed by this slice per user triage.
- **Plan:** `04-plan-uv-generation-pyramid.md` (revision 1)

### `uv-generation-cylinder` (revised 2026-04-18T13:34Z — Auto-Review revision 1)
- **Files:** 12 source + 1 new Maestro flow + 2 apiDump (up from 9)
- **Steps:** 13 (up from 9)
- **Strategy:** Seam vertex duplication in `Cylinder.kt` via new `buildCylinderPaths()` —
  generates N+1 base/top `Point` objects where index N is a geometric copy of index 0 but
  identity-distinct. Side quad k=N-1 references `basePoints[N-1..N]`, giving UV u=(N-1)/N
  and u=1.0 at the seam (no smear). Path count unchanged (N+2). Side UV: u=k/N left /
  (k+1)/N right; v=0 at top. Cap UV: planar disk projection `(0.5+0.5·cos(θ), 0.5+0.5·sin(θ))`
  with **H-5 single-slot identity cache** keyed on `Cylinder ===` (pyramid pattern; 40-float
  cap × 60fps × N shapes savings). Wires five integration points: `is Cylinder` in
  `uvCoordProviderForShape`, real dispatch in `resolveForFace`, `is Cylinder -> CylinderFace.fromPathIndex(index)`
  in `IsometricNode.kt` (on pre-transform `shape`, not `transformedShape` — I-03 invariant),
  `is PerFace.Cylinder` collection in `GpuTextureManager.collectTextureSources`, and 4 TODO
  marker removals. Ships new `cylinderPerFace { }` DSL (mandatory post pyramid BL-1). Fixes
  prereq `CylinderFace.fromPathIndex` semantic bug (0→TOP but `Shape.extrude` puts bottom at 0).
  Removes dead `is PerFace.Cylinder` arm from `warnIfNonPrismPerFaceHasTexturedSlots`
  (leaving only Stairs).
- **Key risks:** (1) `GpuUvCoordsBuffer` 12-float stride unchanged — N>6 cap truncation in
  WebGPU (known limitation, updated `TODO(uv-variable-stride)` comment). (2)
  `RenderCommand.faceVertexCount in 3..24` ceiling — `Cylinder(vertices > 24)` caps would
  crash via `IsometricNode` path; plan recommends `require(vertices <= 24)` in `Cylinder.init`.
  (3) v=0-at-top Cylinder sides vs v=0-at-bottom Prism — cross-cutting documentation.
  (4) `CylinderFace.fromPathIndex` prereq bug — Step 2 fixes it (zero `.api` diff).
  (5) Mixed-vertex-count (4 sides + N caps) is the second I-02 stress test after pyramid.
- **Inherits 6 deferred HIGHs from pyramid review** (H-2, H-3, H-6, H-8, H-10, H-11) — not
  fixed by this slice.
- **Plan:** `04-plan-uv-generation-cylinder.md` (revision 1)

### `uv-generation-stairs` (revised 2026-04-20T15:16Z — Auto-Review revision 1)
- **Files:** 8 source + 1 new shader test + apiDump (no-diff expected)
- **Steps:** 8 (up from 7; Step 2 "fill in resolve()" removed as obsolete,
  Steps 3–5 expanded to cover `IsometricNode`, `resolveForFace`, `GpuTextureManager`
  integration points that sibling cylinder/pyramid slices established).
- **Strategy:** Riser (even path indices, 4 verts): u=x, v=z normalized over riser height.
  Tread (odd indices, 4 verts): u=x, v=y normalized over tread depth. Side (indices 2N, 2N+1,
  variable 2N+2 verts): planar bounding-box projection on (y,z) plane; right side u-mirrored.
  Wire `is Stairs ->` in `uvCoordProviderForShape()`. Add `is Stairs ->
  StairsFace.fromPathIndex(index, shape.stepCount)` to `IsometricNode.kt:301–307`.
  Replace `TODO(uv-generation-stairs)` arm in `resolveForFace` (`IsometricMaterial.kt:430`)
  with `faceType as? StairsFace` dispatch. Replace warn-stub `PerFace.Stairs` arm in
  `GpuTextureManager.collectTextureSources` with real texture collection (mirror Cylinder
  arm); delete now-orphaned `warnIfNonPrismPerFaceHasTexturedSlots` helper.
  `PerFace.Stairs.resolve()` is already fully implemented by shared-api — no stub to fill.
- **Key risks:** (1) `SceneDataPacker.packInto():117` `coerceAtMost(6)` — side faces with
  stepCount ≥ 3 (8+ verts) truncated in WebGPU, visible "notch" artifact. Canvas correct
  at all stepCounts. Same root cause as Cylinder cap limitation.
  (2) Right-side zigzag is `Path(zigzagPoints.reversed()).translate(1,0,0)` — vertex
  order is mirrored before translation, so right-side UV tests must assert in reversed-
  point order. Plan adds left-vs-right vertex-order assertion.
- **Plan:** `04-plan-uv-generation-stairs.md` (revision 1)

### `uv-generation-knot` (new — 2026-04-17, EXPERIMENTAL)
- **Files:** 7
- **Steps:** 7
- **Strategy:** Add `sourcePrisms: List<Prism>` public val to `Knot.kt` with
  `@ExperimentalIsometricApi` (required for UV to access pre-transform dimensions).
  `UvGenerator.forKnotFace` delegates indices 0..17 to `forPrismFace(sourcePrisms[i/6], i%6)`,
  uses `quadBboxUvs` planar projection for custom quads 18, 19. **No `KnotFace` enum, no
  `PerFace.Knot` variant** — `perFace {}` on Knot falls through to `PerFace.Prism.default`
  because `faceType` is null for non-Prism shapes (documented zero-code-change behavior).
- **Key risk:** `sourcePrisms` duplicates constants from `createPaths()` — dimension unit
  test is the regression guard against drift.
- **Plan:** `04-plan-uv-generation-knot.md`

### `webgpu-ngon-faces` (new — 2026-04-22, OMNIBUS)
- **Files:** 14 (4 new, 10 modified) across `:isometric-webgpu` (8), `:isometric-compose`
  (1), `:app` (4 incl. 3 Maestro flows), `:isometric-benchmark` (1)
- **Steps:** 11
- **Strategy:** Atomic lift of the entire 6-vertex chain — `GpuUvCoordsBuffer` becomes a
  dual-buffer (flat `array<vec2<f32>>` UV pool at binding 6 + new `array<vec2<u32>>` per-face
  offset+count table at binding 7), with `SceneDataPacker.coerceAtMost(6)` and
  `TransformedFace`'s 96-byte struct (6×vec2 screen coords) lifted in lockstep to 24 verts.
  Bind-group goes 7→8 entries; device init requests `requiredLimits.maxStorageBuffersPerShaderStage = 8`
  with fail-loud rejection on compat-mode adapters. Bottom-up sequencing: structs → packer →
  shaders. **Omnibus additions per discovery #8:** ear-clipping triangulation in
  `RenderCommandTriangulator.kt` (fixes stairs-verify I-2 non-convex fan defect, ~50 LOC,
  convex-fast-path preserves O(n) for in-budget cases) + one-line `BenchmarkScreen.kt:165`
  `color → material` repair (clears pre-existing I-1 unblocking `./gradlew check` aggregate).
  `TexturedDemoActivity` gains permanent Stairs + Knot tabs alongside the existing four,
  plus a 24-vert cylinder column.
- **Key risks:** (1) `maxStorageBuffersPerShaderStage = 4` rejection on OpenGL ES 3.1
  compat-mode (HIGH on baseline mobile; LOW on Vulkan path) — accepted fail-loud per
  discovery #2; (2) `slot i ↔ originalIndex = i` invariant violation produces silently-wrong
  UVs (HIGH if violated — AC5 unit tests pin this); (3) `androidx.webgpu` alpha04 descriptor
  field-name churn (MEDIUM — verify against vendor snapshot); (4) bind-group cache
  invalidation when going 7→8 bindings (MEDIUM — explicit pipeline layouts, never auto).
- **Plan:** `04-plan-webgpu-ngon-faces.md`

### `webgpu-uv-transforms` (new — 2026-04-15)
- **Files:** 7 (4 modified, 3 new)
- **Steps:** 8
- **Strategy:** Compose `TextureTransform` + atlas region into a single `mat3x2<f32>` on the CPU
  in `GpuTextureManager.uploadUvRegionBuffer()`. Expand `sceneUvRegions` buffer (binding 5)
  from `array<vec4<f32>>` (16 bytes/face) to `array<mat3x2<f32>>` (24 bytes/face).
  WGSL: single matrix-vector multiply replaces two-variable atlas math. IDENTITY fast path
  skips trig. `resolveTextureTransform()` mirrors `resolveAtlasRegion()` for PerFace dispatch.
  No fragment shader, vertex shader, or bind group layout changes needed (`minBindingSize=0`).
- **Key risk (RESOLVED):** `GpuTextureBinder.kt` sampler changed to `AddressMode.Repeat`
  (Step 2 of plan). Single-sampler approach; no pipeline recompile; ClampToEdge==Repeat
  for IDENTITY faces. Concrete change: 1 line in `GpuTextureBinder.kt`.
- **Plan:** 04-plan-webgpu-uv-transforms.md

## Cross-Cutting Concerns

- **Dependency graph (rev 3):** `core → compose → shader → webgpu`. Compose does NOT
  depend on shader. All material-aware logic (types, DSL, caching, resolution, composable
  overloads) lives in `isometric-shader`. Compose only uses `MaterialData?` (core marker).
- **Backward compatibility:** `Shape(shape, color)` in compose is unchanged. Material
  overloads (`Shape(shape, material)`) live in shader module — additive only.
- **`apiCheck`:** Slices 1 and 2 add new public API. Must run `apiDump` + `apiCheck`.
- **Test coverage:** Each slice includes its own tests. Total new tests across all slices:
  ~30+ unit tests, 3 snapshot tests.
- **API design guidelines:** All plans reference relevant sections of
  `docs/internal/api-design-guideline.md` (§2 progressive disclosure, §3 defaults,
  §6 invalid states, §10 host language, §11 evolution).

### Cross-Cutting Concerns (2026-04-17, added for UV geometry batch)

- **WebGPU n-gon face infrastructure debt (BLOCKS clean completion):** `SceneDataPacker.packInto()`
  uses `pts3d.size.coerceAtMost(6)` and `TriangulateEmitShader` caps at 6 vertices /
  4 triangles. `GpuUvCoordsBuffer` allocates 48 bytes (12 floats / 6 UV pairs) per face.
  Two shapes in this batch hit this limit in WebGPU mode:
  - **Cylinder caps** with `vertices > 6` (default is 20) — truncated to 6 verts;
    outer cap ring renders incorrectly
  - **Stairs side faces** with `stepCount > 2` — zigzag polygons have `2·stepCount + 2`
    vertices; truncated to 6; visible "notch" artifact
  Canvas mode is correct in both cases (uses 3-point affine `setPolyToPoly`).
  **Not fixed in this batch.** Each affected slice documents the limitation and suggests
  `stepCount ≤ 2` / `vertices ≤ 6` workaround. A follow-up `webgpu-ngon-faces` slice
  should introduce variable-stride UV packing + WGSL changes. This is a genuine
  cross-cutting concern that will affect any future non-Prism shape with variable-vertex
  faces on WebGPU.

- **`RenderCommand.faceType` typing decision:** Prereq (`uv-generation-shared-api`) keeps
  `faceType: PrismFace?` rather than generalizing to a `ShapeFaceTag?` sealed marker.
  **Consequence:** Non-Prism shape face dispatch in `TexturedCanvasDrawHook`,
  `SceneDataPacker.resolveTextureIndex`, and `GpuTextureManager.resolveEffectiveMaterial`
  must derive the shape-specific face via `command.originalShape as? <Shape>` +
  `paths.indexOf(command.originalPath)` + `<ShapeFace>.fromPathIndex(index)`.
  O(N) path lookup per command, N ≤ 22 for Stairs. Acceptable for now; a follow-up slice
  can add `shapeFaceIndex: Int?` to `RenderCommand` if benchmarks show it hot.

- **UV v-axis convention drift:** `uv-generation-cylinder` plan sets v=0 at top of side
  faces (matches wall/brick texture orientation). Prism + Stairs use v=0 at bottom.
  Real papercut when users apply the same texture to a Prism and a Cylinder in the same
  scene. Documented as known limitation; flipping Prism would be a breaking change.

- **Pyramid lateral numbering:** Prereq's `PyramidFace.fromPathIndex` uses **straight**
  numbering (`0→Lateral(0), 1→Lateral(1), 2→Lateral(2), 3→Lateral(3)`). Construction-order
  in `Pyramid.createPaths()` is `face1, face1.rotateZ(PI), face2, face2.rotateZ(PI)` —
  so `Lateral(1)` refers to the face OPPOSITE `Lateral(0)` (not adjacent). Documented
  in `PyramidFace.Lateral` KDoc.

- **Knot experimental scope:** `Knot` gets no `KnotFace` enum and no `PerFace.Knot`
  variant. Adding `sourcePrisms` to `Knot` (experimental property) is the only core
  change for knot UV support. `perFace {}` on Knot silently falls through to
  `PerFace.Prism.default` because `faceType` is null for non-Prism shapes.

- **Snapshot regeneration burden:** `uv-generation-pyramid` changes Pyramid path count
  from 4 → 5, breaking `pyramid()`, `sampleThree()` Paparazzi snapshots and
  `DocScreenshotGenerator.shapePyramid()` PNG. All three must be regenerated and
  committed by the pyramid slice implementer.

- **Slice planning conflicts detected during cohesion check:**
  1. Original pyramid plan proposed interleaved lateral numbering (reflecting construction
     order: `0→L0, 1→L2, 2→L1, 3→L3`). Corrected to straight numbering to align with prereq.
  2. Original stairs plan used `resolve(faceType: Any?)` signature. Prereq's typed
     `resolve(face: StairsFace)` is cleaner — aligned.
  3. Original pyramid plan proposed generalizing `faceType` to a `ShapeFaceTag?` marker.
     Prereq decided to keep `PrismFace?` with `when (m)` sub-dispatch in consumers —
     pyramid and stairs plans aligned to this decision.

## Integration Points Between Slices

| Producer Slice | Consumer Slice | Shared Interface |
|---|---|---|
| material-types | all others | `IsometricMaterial`, `TextureSource`, `RenderCommand.material` |
| uv-generation | canvas-textures, webgpu-textures | `RenderCommand.uvCoords`, `PrismFace` enum |
| canvas-textures | per-face-materials | `MaterialResolver`, `TextureCache` |
| webgpu-textures | per-face-materials | `GpuTextureStore`, `SceneDataPacker.textureIndex` |
| per-face-materials | sample-demo | `perFace {}` DSL, atlas support |

## Recommended Implementation Order

1. `material-types` — dependency root, all slices need these types ✓ complete
2. `uv-generation` — pure math, enables both renderers ✓ complete
3. `canvas-textures` — default render mode ✓ complete
4. `webgpu-textures` — GPU path ✓ complete
5. `per-face-materials` — hero feature ✓ complete
6. `sample-demo` — end-to-end proof ✓ complete
7. `api-design-fixes` — API cleanup ✓ complete
8. `webgpu-uv-transforms` — TextureTransform on WebGPU ✓ complete
9. `webgpu-texture-error-callback` — error callback WebGPU parity ✓ complete
10. **`uv-generation-shared-api`** — prerequisite for all UV geometry slices ← NEXT
11. `uv-generation-octahedron` — simplest UV geometry (canonical triangle UV)
12. `uv-generation-pyramid` — adds 5th path (BREAKING), snapshot regeneration
13. `uv-generation-cylinder` — seam duplication in Cylinder shape (BREAKING to vertex count)
14. `uv-generation-stairs` — variable-vertex side faces (WebGPU truncation for stepCount > 2)
15. `uv-generation-knot` — EXPERIMENTAL, bag-of-prisms reuse, `textured()`-only ✓ complete
16. **`webgpu-ngon-faces`** — atomic 6→24 vertex lift across UV buffer + transform shader
    + emit shader; omnibus cleanup of stairs-verify I-2 (RenderCommandTriangulator
    ear-clipping) + `:isometric-benchmark` Shape API drift. Depends on slices 13/14/15
    (cylinder/stairs/knot UV emission shapes). ← NEXT TO IMPLEMENT

Slices 11–15 can be implemented in parallel after slice 10 merges, since they only share
the additive extension points established by `uv-generation-shared-api` (each slice fills
in its own `PerFace.<Shape>.resolve()` body, adds its `UvGenerator.forXxxFace()` method,
and wires its `is <Shape> ->` branch in `uvCoordProviderForShape()`). Slice 16
(webgpu-ngon-faces) MUST come after 13/14/15 because it measures the new buffer layout
against the actual UV emission shapes those slices produce, not placeholders.

## Conflicts Found

**Post-rev-3 cohesion review (2026-04-11):** 16 issues found across 5 plans (6 HIGH,
5 MED, 5 LOW). All caused by the dependency inversion in `material-types` rev 3. All fixed.

Key coordination points after fix:
- `RenderCommand.material: MaterialData?` (core) — consumed by all slices
- `isometric-compose` has NO shader imports — uses `MaterialData?` and `uvProvider` lambda
- `isometric-shader` provides composable overloads, texture caching, material resolution
- `isometric-webgpu` adds `isometric-shader` dependency in the `webgpu-textures` slice
- `app` adds `isometric-shader` dependency in the `sample-demo` slice

## Freshness Research

- AndroidX WebGPU vendor API verified: `queue.writeTexture(GPUTexelCopyTextureInfo,
  ByteBuffer, GPUExtent3D, GPUTexelCopyBufferLayout)` confirmed in vendor source.
- `BitmapShader` + `Matrix.setPolyToPoly` approach verified against current Android API.
- Compose `DrawScope.drawIntoCanvas` available in current Compose BOM.

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders uv-generation-shared-api`
  — land the shared-infrastructure prereq first. All 5 UV geometry slices depend on it.
  **Consider `/compact` first** — planning/cohesion-check context is noise for implementation.
- **Option B:** After the prereq merges, run each UV geometry slice's `/wf-implement` in
  parallel branches. Recommended order: octahedron (simplest) → pyramid → cylinder →
  stairs → knot.
- **Option C:** `/wf-slice texture-material-shaders` — if cohesion conflicts identified
  above (pyramid lateral numbering, `faceType` typing, v-axis convention) need further
  refinement before implementation.
