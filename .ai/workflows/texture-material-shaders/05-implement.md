---
schema: sdlc/v1
type: implement-index
slug: texture-material-shaders
status: in-progress
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-22T21:47:18Z"
slices-implemented: 17
slices-total: 17
slices-partial: 0
metric-total-files-changed: 234
metric-total-lines-added: 10672
metric-total-lines-removed: 2318
updated-at: "2026-04-25T23:12:32Z"
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders webgpu-pipeline-cleanup"
---

# Implement Index

## Slices Implemented

### `material-types` — complete (reworked)
- Files: 8 modified/created (phase 2 rework of dependency inversion)
- Summary: `isometric-compose` no longer depends on `isometric-shader`. Shader provides
  overloaded `Shape(geometry, material)` composables. Nodes use `MaterialData?` from core.
- Record: [05-implement-material-types.md](05-implement-material-types.md)

### `uv-generation` — complete
- Files: 9 (4 new, 5 modified/generated)
- Summary: PrismFace enum, UvGenerator object, uvProvider lambda on ShapeNode, wired from shader's Shape() overload
- Record: [05-implement-uv-generation.md](05-implement-uv-generation.md)
### `canvas-textures` — complete
- Files: 12 (6 new, 6 modified including API dumps)
- Summary: MaterialDrawHook strategy injection in compose, TexturedCanvasDrawHook with BitmapShader + affine matrix in shader, ProvideTextureRendering composable, TextureCache LRU, TextureLoader
- Record: [05-implement-canvas-textures.md](05-implement-canvas-textures.md)

### `webgpu-textures` — complete
- Files: 10 (2 new, 8 modified)
- Summary: GPU texture upload (BGRA8Unorm), @group(0) bind group, fragment shader textureSample with NO_TEXTURE fast path, emit shader real UVs + textureIndex, vertex stride 32→36, SceneDataPacker material resolution
- Record: [05-implement-webgpu-textures.md](05-implement-webgpu-textures.md)

### `per-face-materials` — complete
- Files: 15 (2 new, 13 modified)
- Summary: PerFace uses PrismFace keys + resolve() method, PerFaceMaterialScope DSL, faceType on RenderCommand, Canvas per-face resolution, TextureAtlasManager for WebGPU multi-texture, FaceData 144→160 bytes, emit shader UV transform
- Record: [05-implement-per-face-materials.md](05-implement-per-face-materials.md)

### `sample-demo` — complete
- Files: 4 (2 new, 2 modified)
- Summary: TextureAssets (procedural grass/dirt bitmaps), TexturedDemoActivity (render mode toggle + 4x4 perFace prism grid), manifest + MainActivity entries
- Record: [05-implement-sample-demo.md](05-implement-sample-demo.md)

### `api-design-fixes` — complete
- Files: 23 modified/created (including API dumps)
- Summary: 25 API design findings resolved: FlatColor removed, IsoColor:MaterialData, UvTransform→TextureTransform, BitmapSource→Bitmap, textured→texturedResource, TextureLoader fun interface, TextureCacheConfig, @DslMarker, PerFace.of() factory, Shape(material:MaterialData), TileMode per-draw shader cache
- Record: [05-implement-api-design-fixes.md](05-implement-api-design-fixes.md)

### `webgpu-uv-transforms` — complete
- Files: 8 (5 new, 3 modified)
- Summary: Migrated `sceneUvRegions` from `vec4<f32>` (16 bytes) to `mat3x2<f32>` (24 bytes); CPU-composed affine matrix folds `TextureTransform` + atlas region; IDENTITY fast path; `AddressMode.Repeat` sampler; `UvRegionPacker` extracted for JVM testability; 5 unit tests + 3 WGSL regression tests + Maestro visual flow
- Record: [05-implement-webgpu-uv-transforms.md](05-implement-webgpu-uv-transforms.md)

### `webgpu-texture-error-callback` — complete
- Files: see slice record
- Summary: Unified `onTextureLoadError` forwarding into `GpuTextureManager` via `LocalTextureErrorCallback` CompositionLocal; dispatches on main thread; T-01..T-05 tests.
- Record: [05-implement-webgpu-texture-error-callback.md](05-implement-webgpu-texture-error-callback.md)

### `uv-generation-shared-api` — complete (verify-fix applied)
- Files: 19 (7 new, 12 modified) + 1 test-helper fix
- Summary: Abstract sealed `IsometricMaterial.PerFace` base with 5 subclasses; 4 shape face types (CylinderFace, PyramidFace sealed class, StairsFace, OctahedronFace); `RenderCommand.faceVertexCount` propagated through all 4 compose construction sites; `uvCoordProviderForShape()` factory; WebGPU + Canvas consumers migrated to `when (m)` sub-dispatch; `GpuUvCoordsBuffer` keyed on `2 * faceVertexCount`. apiDump committed.
- Verify-fix (I-01): `PerFaceSharedApiTest.stubRenderCommand` now shares a `Path` across round-trip comparisons via a private companion-scoped `sharedStubPath`. `PerFaceSharedApiTest` 22/22 green after fix.
- Record: [05-implement-uv-generation-shared-api.md](05-implement-uv-generation-shared-api.md)

### `uv-generation-octahedron` — complete (verify-fix I-02 applied)
- Files: 7 (1 new test, 6 modified) in initial commit; +5 more files in verify-fix.
- Summary: Full Octahedron per-face texturing and material dispatch. `UvGenerator.forOctahedronFace()` returns canonical triangle UV `(0,0),(1,0),(0.5,1)` for all 8 faces. `uvCoordProviderForShape` gains `is Octahedron` branch. `IsometricNode.kt` `faceType` emission expands to a `when` that also emits `OctahedronFace.fromPathIndex(index)`. `resolveForFace` gets a real `is PerFace.Octahedron` arm. `GpuTextureManager.collectTextureSources` adds atlas-collection branch. 4 TODO markers removed. 9 new unit tests.
- Verify-fix (I-02): Propagated `faceVertexCount` through `SceneGraph.SceneItem`, `SceneGraph.add`, `SceneProjector.add` (interface), `IsometricEngine.add`, both `IsometricEngine.projectScene()` sites (sync + async), and both `SceneCache` callers (rebuild + rebuildAsync). Canvas textured render for Octahedron now works end-to-end; Canvas/WebGPU parity confirmed visually on emulator-5554. Unblocks all 4 remaining non-quad shape slices.
- Deviations: no `octahedronTextured()` Paparazzi snapshot — compose→shader dependency inversion blocks it. BatchNode `faceType` emission left for follow-up (pre-existing gap).
- Record: [05-implement-uv-generation-octahedron.md](05-implement-uv-generation-octahedron.md)

### `uv-generation-pyramid` — complete (verify-fix I-03 applied)
- Files: 14 (2 new tests, 12 modified: 7 production code + 3 tests + 1 sample app + 1 API dump) + 2 auto-regenerated docs screenshots.
- Summary: Full Pyramid per-face texturing and material dispatch **+ breaking change** from 4-path to 5-path Pyramids. `Pyramid.createPaths()` adds a 5th rectangular BASE path. `UvGenerator.forPyramidFace()` returns canonical apex-at-top triangle UV `(0,1)-(1,1)-(0.5,0)` for 4 laterals and top-down planar `(0,0)-(1,0)-(1,1)-(0,1)` for the base. `uvCoordProviderForShape`, `IsometricNode.faceType` emission, `resolveForFace`, and `GpuTextureManager.collectTextureSources` each get a new `is Pyramid`/`is PerFace.Pyramid` arm. **New public API:** `pyramidPerFace { }` DSL + `PyramidPerFaceMaterialScope` class. `TexturedDemoActivity` gains a `Pyramid` tab. 15 new UV unit tests + 9 new `ShapeNodeFaceTypeTest` regression cases.
- Verify-fix (I-03): Primary fix — `IsometricNode.kt` `faceType` dispatch switched from `transformedShape` to the pre-transform `shape` field. `Shape.scale` / `Shape.rotateZ` are non-`open` methods returning base `Shape`, erasing the concrete Pyramid/Octahedron type; sample's `scale = 3.0` triggered this. Canvas/WebGPU both showed flat gray until the fix landed. Secondary fix — `TexturedCanvasDrawHook` + `SceneDataPacker` now resolve per-face `IsoColor` through their respective paint/vertex-color paths, so `pyramidPerFace { lateral(0, RED); ... }` renders bright colors instead of the PerFace default gray. Canvas/WebGPU parity restored.
- Deviations: `pyramidPerFace { }` DSL added to `IsometricMaterial.kt` next to `perFace { }` rather than the plan-suggested `IsometricMaterialComposables.kt` (the latter only hosts the `Shape()` composable); no Paparazzi `pyramidTextured()` snapshot (compose→shader dep inversion).
- Record: [05-implement-uv-generation-pyramid.md](05-implement-uv-generation-pyramid.md)

### `uv-generation-stairs` — complete
- Files: 8 (1 new test, 7 modified).
- Summary: Full Stairs per-face texturing and material dispatch filling in the
  six extension points pre-wired by the shared-api, pyramid, and cylinder slices.
  `UvGenerator.forStairsFace` emits planar `(x, z)` for risers, `(x, y)` for treads
  (each normalised against `1/stepCount`), and `(y, z)` for the two zigzag side
  faces (right side u-mirrored). `forAllStairsFaces` returns `2 * stepCount + 2`
  FloatArrays. `uvCoordProviderForShape`, `IsometricNode.faceType`, and
  `resolveForFace` each gain a new `is Stairs`/`is PerFace.Stairs` arm.
  `GpuTextureManager.collectTextureSources` switches the warn-stub Stairs arm to
  a real tread/riser/side collector; now-dead
  `warnIfNonPrismPerFaceHasTexturedSlots` + `nonPrismPerFaceWarningsIssued` Set
  deleted (-37 lines). `SceneDataPacker.packInto` gains a KDoc caveat
  documenting Stairs side-face truncation at `stepCount >= 3` under the
  6-vertex cap. 13 new UV unit tests (riser/tread canonical, tiling, side
  sizes across stepCount ∈ {1,2,5,10}, left/right u-mirror with reversed
  vertex order, classification, out-of-range failure, translation invariance,
  `resolveForFace` dispatch). Zero public-API drift (`UvGenerator` is
  `internal object`).
- Deviations: the plan-described `IsometricNode.kt` snippet used
  `shape.stepCount` directly, but the compiler rejects it (mutable `shape`
  property → no smart-cast). Resolved with an explicit `(shape as Stairs)`
  cast — noted in the per-slice record as a compile-time-only fix for future
  variable-geometry sibling plans.
- No `stairsPerFace { }` DSL this slice (plan scope; direct constructor with
  named args covers all three logical slots). No sample-app Stairs tab or
  Maestro flow (plan §"Interactive" is optional; deferred to verify).
- Record: [05-implement-uv-generation-stairs.md](05-implement-uv-generation-stairs.md)

### `uv-generation-knot` — complete
- Files: 6 (1 new test, 5 modified: 4 production code + 1 api dump).
- Summary: Full Knot per-face texturing via bag-of-primitives delegation.
  `Knot.sourcePrisms` (new `@ExperimentalIsometricApi` public val) exposes the
  three source Prisms composing the shape; `UvGenerator.forKnotFace` delegates
  faces 0..17 to `UvGenerator.forPrismFace(sourcePrisms[i/6], i%6)` and
  handles the two custom closing quads (18, 19) with axis-aligned
  bounding-box planar projection via a new private `quadBboxUvs` helper.
  `uvCoordProviderForShape` gains the final `is Knot` arm (file-level `@OptIn`).
  `perFace {}` remains silently unsupported on Knot — `IsometricNode.renderTo`
  has no `is Knot` branch in its `faceType` dispatch, so all Knot commands see
  `faceType = null` and route through `PerFace.default`. Documentation added
  to `Knot` KDoc; no guard code needed. 11 new unit tests covering sub-prism
  delegation identity, bbox range, `forAllKnotFaces` sizing, invalid indices,
  and a `sourcePrisms`-vs-`createPaths` drift regression guard.
  `PerFaceSharedApiTest` null-for-Knot test replaced with a positive provider
  test plus a `CustomShape : Shape(...)` local class exercising the remaining
  `else -> null` branch. apiDump: `+Knot.getSourcePrisms()` only —
  `UvGenerator` is `internal object`, so the shader api is unchanged.
- Deviations: `knotTextured()` Paparazzi snapshot deferred, matching the
  precedent set by every sibling `<shape>Textured()` (compose→shader
  dependency inversion blocks the call site). Interactive verification
  belongs to the verify stage.
- Record: [05-implement-uv-generation-knot.md](05-implement-uv-generation-knot.md)

### `uv-generation-cylinder` — complete
- Files: 16 modified + 2 new (UvGeneratorCylinderTest, .maestro/verify-cylinder.yaml).
- Summary: Full Cylinder per-face texturing and material dispatch. `Cylinder` now
  emits seam-duplicated `Point` instances so the lateral strip wraps without a u=1→0
  smear; `CylinderFace.fromPathIndex` mapping corrected to `0→BOTTOM, 1→TOP` matching
  `Shape.extrude` actual output; `UvGenerator.forCylinderFace` adds side + cap UV
  generation with a shared identity cache for both caps; `uvCoordProviderForShape`,
  `resolveForFace`, `IsometricNode.faceType` emission, and `GpuTextureManager.collectTextureSources`
  each gain a new `is Cylinder`/`is PerFace.Cylinder` arm. **New public API:**
  `cylinderPerFace { }` DSL + `CylinderPerFaceMaterialScope` class. **Breaking change:**
  `Cylinder(vertices > 24)` now throws at construction to surface `RenderCommand`
  ceiling at the earliest moment (existing snapshot test updated from vertices=30 to 20).
  `TexturedDemoActivity` gains a Cylinder tab with a procedural brick texture and a
  `cylinderPerFace { }` red-top / blue-bottom / brick-side demo. 16 new UV unit tests,
  4 new `ShapeNodeFaceTypeTest` regression cases (I-03 pattern), 3 new
  `IsometricEngineTest` cases (path layout, seam identity-distinct, vertices>24 rejection).
  Removed 4 `TODO(uv-generation-cylinder)` markers; removed the now-dead Cylinder arm
  from `warnIfNonPrismPerFaceHasTexturedSlots` (only Stairs remains).
- Deviations: added a procedural `TextureAssets.brick` that the plan assumed existed;
  updated existing `cylinder()` Paparazzi to `vertices = 20` because the new
  `require(vertices in 3..24)` validator rejected the prior `vertices = 30`.
- Record: [05-implement-uv-generation-cylinder.md](05-implement-uv-generation-cylinder.md)

### `webgpu-ngon-faces` — complete (two commits: A prep + B GPU pipeline rewrite)
- Commit A: `04afdd9` (+530/-33 across 7 files) — omnibus prep (ear-clipping,
  benchmark API drift fix, permanent Stairs + Knot tabs, Maestro updates).
- Commit B: this pass (+~1050/-210 across 12 files) — the atomic GPU pipeline
  rewrite. `SceneDataLayout` constants lifted to 24 verts
  (FACE_DATA_BYTES 144→448, TRANSFORMED_FACE_BYTES 96→240). `FaceData` /
  `TransformedFace` WGSL structs rewritten with `array<vec3<f32>, 24>` and
  `array<vec2<f32>, 24>`. Transform-cull-light `main()` loopified into a
  single-pass project + AABB + depth accumulator. `SceneDataPacker.packInto`
  writes 24 vertex slots. `GpuUvCoordsBuffer` replaced with dual-buffer
  (pool binding 6: `array<vec2<f32>>`; table binding 7: `array<vec2<u32>>`)
  driven by new pure helper `UvFaceTablePacker`. `GpuTriangulateEmitPipeline`
  bind-group grows to 8 (binding 7 added), `ensureBuffers` signature split
  `uvCoordsBuffer` → `uvPoolBuffer + uvTableBuffer`. `GpuContext` now
  requests `requiredLimits = GPULimits(maxStorageBuffersPerShaderStage = 8)`
  and fails loud with a webgpu-ngon-faces-named `IllegalStateException` if
  the adapter reports less. `TriangulateEmitShader` WGSL rewritten: binding 0
  now a typed `array<TransformedFace>` read (not flat vec4 offset math);
  UV fetch uses indirect `uvFaceTable[originalIndex]` → loop over `uvPool`;
  triangle emit is a dynamic `for t in 0..triCount` loop; MAX_TRIANGLES_PER_FACE
  22, MAX_VERTICES_PER_FACE 66. `RenderCommand` KDoc + error message refreshed
  to drop the stale 6-UV-pair cap reference.
- Tests (Commit B): `TriangulateEmitShaderTest` rewritten with 5 new
  structural assertions (typed binding 0, vec2 pool, u32 table, indirect
  lookup, loop emit); `SceneDataPackerTest` byte offsets updated
  (baseColor 96→400, vertexCount 92→384) plus new zero-fill test;
  `UvFaceTablePackerTest` CREATE — 7 AC5 tests (total count, monotonic
  offsets, heterogeneous slot-i invariant, null/malformed fallback,
  empty-scene, 24-vert max case).
- AC status: AC5 (buffer + table contract tests) and AC7 (apiCheck — all new
  types are `internal`) met by automated checks. AC1/AC2/AC3 (Cylinder
  cap / Stairs zigzag / Knot WebGPU parity with Canvas) ready to verify on
  device via the Maestro flows that Commit A committed. AC6 (WGSL compile
  validation) structurally asserted in Kotlin tests; real compile happens
  at GpuContext init time on device.
- Deviations: 3 — (1) two-commit split (Commit A + Commit B) vs plan's single
  atomic commit; (2) `SceneDataLayout` is an `object` inside `SceneDataPacker.kt`,
  not a separate file (plan drift); (3) FACE_DATA_BYTES is 448 not 432 (plan
  math correction — a 16-byte u32-pad trailer is required for 16-aligned
  struct stride).
- Deferred: `minBindingSize = 8` on bindings 6+7 (follow-up perf pass applying
  it uniformly to all storage-buffer bindings); Paparazzi snapshots for
  cylinderTextured24 / stairsTextured-stepCount5 / knotTextured (generated
  during verify per discovery decision #7).
- Record: [05-implement-webgpu-ngon-faces.md](05-implement-webgpu-ngon-faces.md)

### `webgpu-pipeline-cleanup` — complete (14-commit omnibus)
- Files: 64 changed (+3326, -833) across 13 commits.
- Summary: Closed ~122 of 123 deferred HIGH+MED+LOW review findings from
  the entire `texture-material-shaders` epic. No new functionality —
  every change targets existing shipped code. Output is byte-equivalent
  on all baseline test fixtures (Paparazzi zero-pixel-drift held).
  Architectural additions (all `internal`): `WgslDiagnostics` (single
  shader-compile diagnostic helper across 6 pipelines),
  `IdentityCachedUvProvider<K : Any>` (single-slot atomic UV-array cache),
  `ShapeUvDescriptor` + `ShapeRegistry` (polymorphic shape-dispatch
  registry; `UvCoordProviderForShape` collapsed from 6-arm `when` to
  registry lookup), `SceneDataLayout` (extracted to its own file).
  Public API additions (additive only — `apiCheck` PASS):
  `FaceIdentifier.Companion.forShape`, `Stairs.MAX_REASONABLE_STEPS`,
  `stairsPerFace { }` DSL + `StairsPerFaceMaterialScope`,
  `TextureCacheConfig.maxBytes` + `MAX_CACHE_SIZE`. Robolectric 4.13
  added to `isometric-shader` test deps; 3 previously-`@Ignore`'d test
  classes lit up. Test seams added: `GpuContext.checkComputeLimits`,
  `TextureCache.putWithSize`, `TextureLoader.decideSampleSize`,
  `GpuTextureManager.collectTextureSourcesFromMaterial`.
- Commits: `0ae2cdb` G1 → `9aabbfd` G1 tests → `5a01a33` G6 →
  `86e5d80` G6 tests → `67ba5f1` G2 → `adeb2ab` G2 tests → `dd8ed69` G3 →
  `7059b66` G3 tests → `3f85983` G4 → `0767552` G7 → `40ec332` G5 →
  `f1ed25c` G8 → `1df8319` G9.
- Deferred: F-03 (WGSL identity early-out — Paparazzi evidence needed),
  P-1 + U-02 (stairs/knot identity caches — `Pair`-as-key infeasibility
  with `===` semantics), D-08 (thin abstraction), N-02 (multi-importer
  rename), D-28 (Maestro pixel-assertion — explicit defer per po-answers),
  `UvGenerator.clearAllCaches()` from `clearScene` (cross-module
  `internal` violation; natural eviction holds).
- Conservative G9: only `UvCoordProviderForShape` migrated to registry.
  `IsometricMaterial.resolveForFace` and
  `GpuTextureManager.collectTextureSources` retain exhaustive sealed-class
  `when (PerFace)` because compile-time exhaustiveness is stronger than
  `Map<KClass, Descriptor>` lookup. Descriptors have
  `collectTextureSourcesContribution` ready for future migration.
- Surfaced finding: Pyramid base winding KDoc says "CCW from below" but
  shoelace +2.0 means "CCW from above" — test pins actual behavior; doc
  fix is a future cosmetic commit.
- Record: [05-implement-webgpu-pipeline-cleanup.md](05-implement-webgpu-pipeline-cleanup.md)

## Cross-Slice Integration Notes
- Dependency graph: `core → compose → shader → webgpu`
- `isometric-compose` has zero shader imports — fully usable standalone
- `ShapeNode.material: MaterialData?` is the cross-module bridge
- `MaterialDrawHook` is the render-time bridge: compose defines interface, shader implements
- `NativeSceneRenderer.renderNative()` now accepts optional `MaterialDrawHook` parameter
- **After `uv-generation-shared-api` (this slice):** five shape UV slices become purely additive — each adds a new `PerFace.<Shape>.resolve()` implementation, a new `when` branch in `uvCoordProviderForShape()`, and per-shape UV generator logic. No further changes to `IsometricMaterial.kt`, `RenderCommand.kt`, or the WebGPU/Canvas consumer dispatches.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders webgpu-pipeline-cleanup` — multi-pass verify expected (per slice Risk 7). 9 finding-groups likely surface 2–3 verify passes minimum. **Consider `/compact` first** — the per-step debugging context (sub-agent reports, build-fix details) is noise for verification. The H-4 instrumented test (Tint compile validation at `WgslDiagnosticsInstrumentedTest`) is `@Ignore`'d and needs manual lift + emulator run before review can certify shader compilation.
- **Option B:** `/wf-review texture-material-shaders webgpu-pipeline-cleanup` — skip verify only if you trust the all-green CI signal absolutely. Review focus: `WgslDiagnostics` extraction across 6 pipelines, `IdentityCachedUvProvider` cache semantics, `ShapeRegistry` registry pattern, the cross-module-`internal` deferral, the 6 deferred findings (F-03/P-1/U-02/D-08/N-02/D-28).
- **Option C:** All 17 slices implement-complete. Epic ready for `/wf-handoff` after verify + review of `webgpu-pipeline-cleanup`.
