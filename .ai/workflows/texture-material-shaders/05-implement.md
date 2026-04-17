---
schema: sdlc/v1
type: implement-index
slug: texture-material-shaders
status: in-progress
stage-number: 5
created-at: "2026-04-11T22:32:12Z"
updated-at: "2026-04-17T23:24:00Z"
slices-implemented: 12
slices-total: 15
metric-total-files-changed: 121
metric-total-lines-added: 4881
metric-total-lines-removed: 1107
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders uv-generation-pyramid"
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

## Cross-Slice Integration Notes
- Dependency graph: `core → compose → shader → webgpu`
- `isometric-compose` has zero shader imports — fully usable standalone
- `ShapeNode.material: MaterialData?` is the cross-module bridge
- `MaterialDrawHook` is the render-time bridge: compose defines interface, shader implements
- `NativeSceneRenderer.renderNative()` now accepts optional `MaterialDrawHook` parameter
- **After `uv-generation-shared-api` (this slice):** five shape UV slices become purely additive — each adds a new `PerFace.<Shape>.resolve()` implementation, a new `when` branch in `uvCoordProviderForShape()`, and per-shape UV generator logic. No further changes to `IsometricMaterial.kt`, `RenderCommand.kt`, or the WebGPU/Canvas consumer dispatches.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders uv-generation-pyramid` — confirm AC1–AC5 for the freshly-landed slice, with special attention to the mixed-vertex-count I-02 stress (3 for laterals, 4 for base) on both Canvas and WebGPU backends.
- **Option B:** `/wf-review texture-material-shaders uv-generation-pyramid` — skip verify; the slice has strong unit-test coverage (15 new cases) and apiCheck is green. Review focus: interactive mixed-vertex-count render evidence, new DSL ergonomics, the `GpuTextureManager` dead-arm question.
- **Option C:** Begin next shape slice (`/wf-implement texture-material-shaders uv-generation-cylinder` or `uv-generation-stairs`). Not recommended until pyramid reaches at least verify — each shape slice touches the same 4 files, so bundling verify-first keeps merge-conflict risk low. Plan sequence: octahedron (done) → pyramid (done) → cylinder → stairs → knot.
