---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: per-face-materials
review-command: architecture
status: complete
updated-at: "2026-04-13T01:30:00Z"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 2
result: issues-found
tags: [architecture, per-face, material]
refs:
  review-master: 07-review-per-face-materials.md
---

## Findings Table

| ID | Severity | File | Area | Title |
|----|----------|------|------|-------|
| ARCH-01 | HIGH | `isometric-core/RenderCommand.kt` | Module boundaries | `uvOffset`/`uvScale` on `RenderCommand` are dead fields — written but never read |
| ARCH-02 | HIGH | `isometric-core/RenderCommand.kt` | Module boundaries | `uvOffset`/`uvScale` bake WebGPU atlas concerns into the core layer |
| ARCH-03 | MEDIUM | `isometric-webgpu/.../GpuFullPipeline.kt` | God-object growth | `GpuFullPipeline` now owns atlas management, UV region buffering, tex-index buffering, and bind-group lifecycle |
| ARCH-04 | MEDIUM | `isometric-webgpu/.../shader/TransformCullLightShader.kt` | Struct coupling | Stale comment says `FaceData` is 144 bytes; actual size is 160 — comment not updated |
| ARCH-05 | LOW | `isometric-shader/.../IsometricMaterial.kt` | API surface | `PerFace` works only for `Prism` shapes but nothing in the public API communicates this constraint |
| ARCH-06 | LOW | `isometric-webgpu/.../pipeline/GpuFullPipeline.kt` | Binding proliferation | Two parallel per-face data paths exist: `FaceData` uvOffset/uvScale fields (M1 struct) vs `sceneUvRegions` buffer (binding 5, M5); only the latter is used |
| ARCH-07 | NIT | `isometric-webgpu/.../pipeline/GpuFullPipeline.kt` | Buffer management | `uploadTexIndexBuffer` capacity growth formula `maxOf(faceCount, faceCount * 2)` always equals `faceCount * 2`; the `maxOf` is redundant |

---

## Detailed Findings

### ARCH-01 — HIGH: `uvOffset`/`uvScale` on `RenderCommand` are dead fields

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/RenderCommand.kt` (lines 47–49)

`RenderCommand` carries `uvOffset: FloatArray?` and `uvScale: FloatArray?`. `SceneDataPacker.packInto` writes these into the `FaceData` GPU struct at offsets 132–147. However, the `TransformCullLightShader` (M1) declares those fields in the WGSL struct purely for size alignment — they are never read in the shader body. The M5 emit shader resolves atlas UVs from the separate `sceneUvRegions` buffer (binding 5), built independently by `GpuFullPipeline.uploadUvRegionBuffer` via `resolveAtlasRegion`. This means:

1. `RenderCommand.uvOffset`/`uvScale` are always `null` at the call site where they are constructed (`IsometricNode.ShapeNode.renderTo`) — nothing ever sets them.
2. `SceneDataPacker` reads them with `cmd.uvOffset?.get(0) ?: 0f` defaults, so the FaceData struct always carries identity values (0,0,1,1).
3. The 16 bytes they occupy in `FaceData` are wasted GPU upload bandwidth.

**Recommendation:** Either remove `uvOffset`/`uvScale` from `RenderCommand` and strip them from `FaceData` (saving 16 bytes per face, reverting the struct to 144 bytes and updating both shaders), or document explicitly that they are reserved for a future CPU→GPU pre-computed UV path and suppress the dead-code concern until that path is implemented.

---

### ARCH-02 — HIGH: Core layer carries WebGPU atlas semantics

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/RenderCommand.kt`

`uvOffset` and `uvScale` are described in the KDoc as "Atlas sub-region UV offset/scale for WebGPU texture atlas mapping. Set by the WebGPU pipeline during per-face material resolution." This means the core data-transfer object (DTO) is documented to be mutated by a backend implementation — an inversion of the intended dependency flow (`isometric-core` should be backend-agnostic).

The actual implementation does not set these fields anywhere (the WebGPU pipeline resolves the atlas region at upload time via `GpuFullPipeline.resolveAtlasRegion` without touching `RenderCommand`), so the concern is currently aspirational coupling that isn't active. However, the documented intent is a module boundary violation: if a future refactor does place atlas resolution in the pipeline stage that produces `RenderCommand`, the core module would carry GPU-backend knowledge.

**Recommendation:** Remove the `uvOffset`/`uvScale` fields from `RenderCommand` entirely (see ARCH-01). The atlas-region lookup should remain a `webgpu`-internal concern, resolved at upload time from `cmd.faceType` + `atlasManager.getRegion(...)` as it is today.

---

### ARCH-03 — MEDIUM: `GpuFullPipeline` god-object growth

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt`

`GpuFullPipeline` now directly owns:
- `TextureAtlasManager` (atlas rebuild, region lookup)
- Tex-index buffer management (`texIndexGpuBuffer`, `texIndexCpuBuffer`, `texIndexCapacity`)
- UV-region buffer management (`uvRegionGpuBuffer`, `uvRegionCpuBuffer`, `uvRegionCapacity`)
- Bind-group lifecycle (`textureBinder`, `_textureBindGroup`, `rebuildBindGroup`)
- `GpuTextureStore` (exposed `val textureStore`)
- Texture-source collection and cache-invalidation logic (`collectTextureSources`, `lastAtlasSignature`)

Before this slice the class was already orchestrating 6 sub-pipelines. It now also contains the full texture-management subsystem inline (≈120 new lines). The `upload` method spans 70 lines and calls 3 private upload helpers.

This is a real concern but not yet a blocker — the class is still navigable and the texture logic is grouped in clearly labelled sections. The risk is that each subsequent slice (custom UV transforms, multi-atlas, skinned textures) will continue to grow this file.

**Recommendation:** Extract a `GpuTextureManager` (or similar) that owns `TextureAtlasManager`, `GpuTextureBinder`, tex-index buffer, UV-region buffer, and all related logic. `GpuFullPipeline.upload` would delegate to `textureManager.upload(scene)`, receiving back the bind group and GPU buffer references it needs. This extraction can wait until the texture subsystem stabilises but should be tracked.

---

### ARCH-04 — MEDIUM: Stale comment in `TransformCullLightShader`

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/TransformCullLightShader.kt` (line 30)

The KDoc comment reads:
```
* - `FaceData` ← [SceneDataPacker] / [SceneDataLayout] (144 bytes, stride 144)
```

The actual struct size is 160 bytes (`SceneDataLayout.FACE_DATA_BYTES = 160`). The WGSL struct definition in the same file correctly documents 160 bytes. The KDoc line was not updated when the struct grew from 144 to 160.

This is a correctness risk for future maintainers who read the class-level documentation before the inline struct layout — they will see a contradictory byte count.

**Recommendation:** Update line 30 to read `(160 bytes, stride 160)`.

---

### ARCH-05 — LOW: `PerFace` scope/API does not communicate the Prism-only constraint

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterial.kt`

`PerFace` is a public sealed subtype of `IsometricMaterial`. The `perFace { }` DSL builder is available to any caller. However, `faceType` is only populated on `RenderCommand` when the originating shape is a `Prism` (`IsometricNode.ShapeNode.renderTo`, line 279: `faceType = if (isPrism) PrismFace.fromPathIndex(index) else null`).

A user calling `Shape(geometry = Cylinder(...), material = perFace { ... })` will silently receive `faceType = null` for all commands, causing `PerFace.resolve` to return `default` for every face. No warning, no error.

**Recommendation:** Add a KDoc note on `PerFace` (and/or `perFace { }`) stating "only effective when applied to a `Prism` shape; all other shapes receive the `default` material." A runtime warning log when a `PerFace` material is attached to a non-Prism shape would also aid debugging.

---

### ARCH-06 — LOW: Two parallel UV-data paths — only one is live

**Files:** `SceneDataPacker.kt` (FaceData struct), `GpuFullPipeline.kt` (`uploadUvRegionBuffer`), `TriangulateEmitShader.kt` (binding 5)

There are two conceptually overlapping paths for per-face UV atlas data:

1. **FaceData embedded fields** (`uvOffsetU`, `uvOffsetV`, `uvScaleU`, `uvScaleV` at bytes 132–147): packed from `cmd.uvOffset`/`cmd.uvScale` by `SceneDataPacker`. Always zero/identity because those fields are never set. Read by M1 shader? No — declared for struct alignment but unused in the body.

2. **`sceneUvRegions` buffer** (binding 5): built by `GpuFullPipeline.uploadUvRegionBuffer` from `resolveAtlasRegion` which queries `atlasManager.getRegion(...)`. Read by M5 shader via `sceneUvRegions[key.originalIndex]`. This is the active path.

The FaceData fields (path 1) contribute 16 bytes of per-face GPU upload cost with no functional effect. This is related to ARCH-01 but framed differently: from a system design perspective, two parallel mechanisms exist where one should be authoritative.

**Recommendation:** Remove the embedded UV fields from `FaceData` and `RenderCommand` (resolving ARCH-01/ARCH-02 in the process). The `sceneUvRegions` buffer (binding 5) is the cleaner design — a compact side-channel indexed directly by `originalIndex`, decoupled from the transform pipeline.

---

### ARCH-07 — NIT: Redundant `maxOf` in capacity growth

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt` (lines 408, 441)

```kotlin
val newCapacity = maxOf(faceCount, faceCount * 2)
```

`faceCount * 2 >= faceCount` for all non-negative `faceCount`, so `maxOf` always returns `faceCount * 2`. The guard is harmless but misleading — it implies the intent was `maxOf(MINIMUM_CAPACITY, faceCount * 2)` or `maxOf(faceCount + 1, faceCount * 2)`.

**Recommendation:** Replace with `faceCount * 2` directly, or introduce a named minimum capacity constant if a floor is desired.

---

## Summary

The slice delivers a correct, working per-face material system. The `PerFaceMaterialScope` DSL is ergonomic, the `PrismFace` key type is semantically appropriate, and the atlas-UV pipeline (M5 binding 5) is well-structured. Two issues should be resolved before the next slice: the `uvOffset`/`uvScale` fields on `RenderCommand` (ARCH-01, ARCH-02) are dead weight that embed WebGPU atlas semantics in the core layer — they should be removed, which will also shrink `FaceData` back to 144 bytes and remove the duplicate data path (ARCH-06). The stale "144 bytes" comment in `TransformCullLightShader` (ARCH-04) should be fixed immediately. The `GpuFullPipeline` growth (ARCH-03) is a known accumulation risk worth tracking; extraction of a `GpuTextureManager` is the recommended future mitigation.
