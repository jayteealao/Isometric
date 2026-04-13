---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: sample-demo
review-command: architecture
status: complete
updated-at: "2026-04-13T09:00:00Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-medium: 3
metric-findings-low: 2
metric-findings-nit: 1
result: issues-found
tags: [architecture, sample-demo, texture, faceType, RGBA, bindings, uvCoords]
refs:
  review-master: 07-review.md
  scope-commits: "970b91e..feat/texture"
---

# Architecture Review: sample-demo Slice

Scope: `git diff 970b91e...feat/texture` — 20 files, +465 / -48 lines.

## Findings Table

| ID | Severity | File | Area | Title |
|----|----------|------|------|-------|
| ARCH-SD-01 | HIGH | `isometric-core/SceneProjector.kt` | Public API | `faceType` on `SceneProjector.add()` leaks Prism geometry into the projection abstraction |
| ARCH-SD-02 | HIGH | `isometric-shader/IsometricMaterialComposables.kt` | Color semantics | `IsoColor.WHITE` for all textured materials silently breaks tint on `Textured` and forces opaque white for `PerFace` |
| ARCH-SD-03 | MEDIUM | `isometric-webgpu/texture/GpuTextureStore.kt` | Format correctness | BGRA→RGBA change is technically correct but the checkerboard fallback now has an orphaned byte-value comment |
| ARCH-SD-04 | MEDIUM | `isometric-webgpu/pipeline/GpuTriangulateEmitPipeline.kt` | Portability | 6 storage + 1 uniform = 7 entries in one bind group; `maxStorageBuffersPerShaderStage` WebGPU baseline is 8 — headroom is 2 |
| ARCH-SD-05 | MEDIUM | `isometric-webgpu/texture/GpuTextureManager.kt` | Responsibility | Per-vertex UV buffer (`uvCoordsBuf`) lives in `GpuTextureManager`; it is a geometry/projection concern, not a texture-atlas concern |
| ARCH-SD-06 | LOW | `isometric-core/SceneGraph.kt` | Internal model | `SceneGraph.SceneItem.faceType` mirrors `RenderCommand.faceType` — the duplication is justified but warrants a comment |
| ARCH-SD-07 | LOW | `isometric-webgpu/texture/GpuTextureManager.kt` | Correctness risk | `uploadUvCoordsBuffer` accesses `scene.commands[i]` sequentially assuming the same face ordering as `uploadTexIndexBuffer` / `uploadUvRegionBuffer`; ordering dependency is implicit |
| ARCH-SD-08 | NIT | `app/sample/TexturedDemoActivity.kt` | Demo quality | `TogglePill` is a private copy of the one in `WebGpuSampleActivity` — two divergent copies in the same module |

---

## Detailed Findings

### ARCH-SD-01 — HIGH: `faceType` on `SceneProjector.add()` leaks Prism geometry into the projection abstraction

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/SceneProjector.kt` (line 42)  
**Binary API change:** `isometric-core/api/isometric-core.api` — both `SceneProjector` and `IsometricEngine.add()` signatures changed.

`SceneProjector` is a pure projection abstraction: it converts shapes and paths into sorted 2D render commands. Its `add(path, ...)` overload now carries `faceType: PrismFace?`, which is a Prism-specific face-identity enum. This is a correctness mismatch at the wrong layer:

1. `SceneProjector` is a geometry-agnostic interface. Callers that implement it for non-Prism geometries must now decide what to pass for `faceType`; the only correct answer is `null`, but the parameter is not documented to be Prism-exclusive.
2. The actual assignment (`PrismFace.fromPathIndex(index)`) happens **before** `add` is called — in `IsometricNode.ShapeNode.renderTo` (compose layer). The value is already computed and available on `RenderCommand`. It does not need to travel through `SceneProjector.add` separately; it rides with `RenderCommand`.
3. The signature change is a **binary-breaking** public API change (confirmed by `isometric-core.api` diff). Any external implementors of `SceneProjector` must update. Per the api-design-guideline (Section 11, Evolution), additive changes must not silently break semantics — this breaks any stub/fake implementation that has a fixed signature.

**Root cause:** `faceType` was added to `SceneProjector.add` to get the value into `SceneGraph.SceneItem`, but `SceneGraph.SceneItem` is already populated from `RenderCommand` fields upstream. The value should ride on `SceneItem` (and `RenderCommand`) as an internal fact about the path, not be an additional parameter threaded through the public projection interface.

**Recommendation:** Remove `faceType` from `SceneProjector.add()` and `IsometricEngine.add()`. Derive it from the path's position within the shape's ordered path list inside `SceneGraph.add(shape, color)` or inside the emit pipeline, exactly as `IsometricNode.ShapeNode.renderTo` derives it (`PrismFace.fromPathIndex(index)` when `originalShape is Prism`). The `SceneGraph.SceneItem.faceType` field can remain — it is an internal model concern and is not part of any public API.

This restores API stability and removes the abstraction leak. If `faceType` must remain on `add()` for an explicit-path use case (e.g., external callers manually adding Prism faces), it should be on the internal `SceneGraph` and `IsometricEngine` paths only, not on the `SceneProjector` interface.

---

### ARCH-SD-02 — HIGH: `IsoColor.WHITE` for textured materials silently drops tint

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt` (lines 58–62, 145–149)

Before this slice the color branch was:
```kotlin
val color = when (material) {
    is IsometricMaterial.FlatColor -> material.color
    else -> LocalDefaultColor.current
}
```

After:
```kotlin
val color = when (material) {
    is IsometricMaterial.FlatColor -> material.color
    is IsometricMaterial.Textured -> IsoColor.WHITE
    is IsometricMaterial.PerFace -> IsoColor.WHITE
}
```

Two problems:

**Problem A — Tint ignored for `Textured`.** `IsometricMaterial.Textured` carries a `tint: IsoColor` property (default `IsoColor.WHITE`, documented as "Multiplicative color tint applied over the texture"). A caller who writes `texturedBitmap(bmp, tint = IsoColor.RED)` expects the GPU shader to multiply the texture sample by red. But the `baseColor` sent to the GPU via `RenderCommand.baseColor` is now always `IsoColor.WHITE` — the tint is silently discarded. The Canvas path may also consume this `color` field for shader-based tinting.

**Problem B — Loss of `LocalDefaultColor` for partially textured shapes.** Before this change, a `PerFace` shape whose `default` sub-material is `FlatColor` would inherit a meaningful base color from `LocalDefaultColor`. After the change, the mesh always uploads `WHITE` regardless of what the `default` material specifies. The `PerFace.default` color is resolved per face at render time, so this is partially mitigated — but for Canvas rendering the global `color` field still feeds the path's base paint in non-textured fallbacks.

**Correct fix:** Use the material's own tint, not a hardcoded constant:
```kotlin
val color = when (material) {
    is IsometricMaterial.FlatColor -> material.color
    is IsometricMaterial.Textured  -> material.tint   // respect caller's tint
    is IsometricMaterial.PerFace   -> LocalDefaultColor.current  // no single tint; defer to per-face resolution
}
```

For `PerFace`, keeping `LocalDefaultColor.current` as the baseline color (the pre-existing behavior) is appropriate since there is no single tint for the whole shape — each face resolves its own material. The GPU pipeline then applies the correct per-face texture regardless of this base color.

---

### ARCH-SD-03 — MEDIUM: RGBA8Unorm change is correct but the fallback checkerboard comment is stale

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureStore.kt` (lines 45–51)

The BGRA→RGBA change is **correct**. `Bitmap.copyPixelsToBuffer()` on Android writes bytes as R,G,B,A regardless of the in-memory `ARGB_8888` name (which refers to channel order in the packed 32-bit int, not the byte stream order). Binding the GPU texture as `RGBA8Unorm` aligns the GPU format with what `copyPixelsToBuffer` actually produces, fixing what was a channel-swap bug.

However, the fallback checkerboard initialisation now uses `ByteOrder.nativeOrder()` for a buffer whose bytes are meant to be interpreted as RGBA. On little-endian ARM (all Android devices) `nativeOrder()` is little-endian, but the manual byte `put` sequence is writing individual bytes positionally — byte order does not affect single-byte writes. The `ByteOrder.nativeOrder()` call has no effect here and could mislead a future reader into thinking it matters.

Additionally, the comment on line 43 (`// pixel (0,0): magenta — RGBA = (255, 0, 255, 255)`) is now correct for RGBA, but the bytes are `0xFF, 0x00, 0xFF, 0xFF` which is R=255, G=0, B=255, A=255 — this is indeed magenta in RGBA. The comment is accurate.

**Propagation check:** The same `createTexture` helper is used for both the fallback and for all bitmap uploads. Both now use `RGBA8Unorm`. No other texture creation path exists in `GpuTextureStore`. The atlas (built by `TextureAtlasManager`) uses `GpuTextureStore.uploadBitmap` → `createTexture`, so the atlas is also `RGBA8Unorm`. All textures in the system are now consistently `RGBA8Unorm`. No residual `BGRA8Unorm` exists.

**Minor issue:** The `ByteOrder.nativeOrder()` call on the fallback checkerboard `ByteBuffer` is cargo-cult code (no effect on single-byte puts). Recommend removing it or replacing with `ByteOrder.BIG_ENDIAN` to signal intentional byte-positional writing.

---

### ARCH-SD-04 — MEDIUM: 7 entries in one compute bind group is near the portable floor

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuTriangulateEmitPipeline.kt` (bindings 0–6)

The M5 compute shader now uses one bind group with 7 entries:
- Binding 0: `transformed` (storage, read)
- Binding 1: `sortedKeys` (storage, read)
- Binding 2: `vertices` (storage, read_write)
- Binding 3: `params` (uniform)
- Binding 4: `sceneTexIndices` (storage, read)
- Binding 5: `sceneUvRegions` (storage, read)
- Binding 6: `sceneUvCoords` (storage, read)

**Storage buffers per shader stage:** 6 out of the WebGPU baseline `maxStorageBuffersPerShaderStage = 8`. This leaves headroom of 2.

**Bindings per bind group:** 7 out of `maxBindingsPerBindGroup = 1000` (WebGPU baseline). No issue here.

**Portability risk:** The WebGPU Core tier (`minStorageBuffersPerShaderStage`) is 8, and no Android device running WebGPU via Vulkan is known to expose fewer than 8. The current usage of 6 is safe within that limit. However, the steady march of one binding per slice (tex-index → UV region → UV coords) means that two more texture-related slices would exhaust the storage-buffer limit. Any future addition (e.g., a normal-map buffer, a per-face tint buffer) should be evaluated against this ceiling before being added as a new binding.

**Recommendation:** Document the current binding count and the `maxStorageBuffersPerShaderStage = 8` limit in a comment at the top of `GpuTriangulateEmitPipeline`. When the count reaches 7 storage buffers, consider consolidating `sceneTexIndices`, `sceneUvRegions`, and `sceneUvCoords` into a single interleaved `scenePerFaceData` buffer (one entry = 8+16+48 = 72 bytes) to recover bindings. No action required now.

---

### ARCH-SD-05 — MEDIUM: Per-vertex UV buffer is in the wrong owner

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureManager.kt` (lines 63, 71–72, 104–109, 254–281)

`GpuTextureManager` was introduced (as a previous review recommendation from ARCH-03) to encapsulate texture-atlas concerns: `GpuTextureStore`, `TextureAtlasManager`, `GpuTextureBinder`, `texIndexGpuBuffer`, `uvRegionGpuBuffer`. These are all legitimately texture-atlas data — they describe *where* in the atlas a face's texture lives.

The new `uvCoordsBuf` / `uvCoordsGpuBuffer` is different: it holds per-vertex UV coordinates generated by `UvGenerator.forPrismFace` during shape decomposition in the Compose layer. These UVs describe the face's own parameterisation (how the texture wraps around the face geometry), not how the texture is located inside the atlas. The atlas region (binding 5) and the face UVs (binding 6) are combined at shader time by `atlasUV = baseUV * uvScale + uvOffset`.

**Why this matters:** `uvCoordsBuf` is populated from `scene.commands[i].uvCoords` — it reads geometry/projection data, not texture-atlas data. It belongs to the same conceptual layer as the `FaceData` transform buffer (M1 output) rather than the atlas management layer. Placing it in `GpuTextureManager` means:
1. `GpuTextureManager` now has two separate responsibilities (atlas state + geometry UV state).
2. Future texture-unrelated changes to UV parameterisation (e.g., animated UVs) will land inside a class named "TextureManager".
3. The upload ordering dependency between `uploadUvCoordsBuffer` and the atlas upload (which determines `uvRegions`) is implicit — both are called from `uploadTextures` but neither documents that the other must complete first.

**Recommendation:** Extract `uvCoordsBuf` and `uploadUvCoordsBuffer` into a separate `GpuUvCoordsBuffer` wrapper class, or move them alongside the face transform data (near `SceneDataPacker`). `GpuTextureManager` should expose only atlas-related GPU buffers. `GpuFullPipeline.upload` would call `uvCoordsManager.upload(scene, faceCount)` and retrieve `uvCoordsManager.gpuBuffer` alongside the texture manager's buffers.

This separation is not urgent for a demo slice but becomes important as soon as animated UVs or procedural UV mapping is needed.

---

### ARCH-SD-06 — LOW: `SceneGraph.SceneItem.faceType` duplication is justified but uncommented

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/SceneGraph.kt` (line 18)

`SceneGraph.SceneItem` now carries `faceType: PrismFace?`, mirroring the same field on `RenderCommand`. The duplication is intentional: `SceneItem` is the pre-projection input; `RenderCommand` is the post-projection output. The value passes through unchanged (`sceneGraph.add(..., faceType)` → `SceneItem.faceType` → `RenderCommand(faceType = transformedItem.item.faceType)`).

This is architecturally sound — there is no better place to carry this through projection. However, neither `SceneItem.faceType` nor its sister field on `RenderCommand` has a KDoc entry explaining that this value is Prism-exclusive and is `null` for all other shape types. A future maintainer adding a new shape type with its own face concept will not know whether to add a new enum or extend `PrismFace`.

**Recommendation:** Add a single-line KDoc to both `SceneItem.faceType` and `RenderCommand.faceType` noting "null for non-Prism shapes". The existing KDoc on `RenderCommand.faceType` partially covers this but does not state it for `SceneItem`.

---

### ARCH-SD-07 — LOW: `uploadUvCoordsBuffer` has an implicit ordering dependency on scene command index

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureManager.kt` (lines 254–281)

All three per-face buffer upload methods (`uploadTexIndexBuffer`, `uploadUvRegionBuffer`, `uploadUvCoordsBuffer`) iterate `scene.commands[0..faceCount-1]` and assume that `scene.commands[i]` maps to GPU slot `i` for all three buffers. This is a correct and consistent assumption today — the same `PreparedScene` is passed to all three and the same `faceCount` is used.

However, the contract is not expressed. If a future refactor passes a filtered or re-ordered command list to one of the three methods (e.g., for incremental upload), the indices will silently misalign. The `resolveAtlasRegion` call inside `uploadUvRegionBuffer` uses `cmd.faceType` (a per-command field), while `uploadUvCoordsBuffer` uses `cmd.uvCoords` — both rely on the same position-to-index identity, but neither calls the other or checks consistency.

**Recommendation:** Add a `require(scene.commands.size >= faceCount)` guard at the top of `uploadTextures` and document that all three sub-uploads share the same `scene.commands[0..faceCount-1]` index space. A single integration test verifying that all three buffers have consistent entries for the same face would make this contract explicit.

---

### ARCH-SD-08 — NIT: `TogglePill` duplicated in same module

**File:** `app/src/main/kotlin/io/github/jayteealao/isometric/sample/TexturedDemoActivity.kt` (lines 443–463)

`TogglePill` is a private copy of the same composable in `WebGpuSampleActivity`. Both are in the `io.github.jayteealao.isometric.sample` package (same module). The plan acknowledged this as acceptable for demo code, and it is.

However, since these are in the same Gradle module (`:app`) rather than different modules, extraction to a shared internal composable (`SampleUiComponents.kt` or similar) costs nothing architecturally and avoids future divergence. Two copies in the same file tree are harder to spot than two copies across modules.

**Recommendation:** Extract `TogglePill` to a package-private file (`SampleUi.kt`) in the `sample` package. Both activities import from that file. No functional change.

---

## Summary

The sample-demo slice correctly assembles the prior texture infrastructure into a working demo — the `TexturedDemoActivity`, `TextureAssets`, and manifest wiring are clean and self-contained. No blocker-level issues were found.

**Two high-severity findings require attention before merge:**

1. **ARCH-SD-01** (faceType on SceneProjector): The public `SceneProjector.add()` signature carries a Prism-specific geometry parameter, creating a binary-breaking API change and an abstraction leak. `faceType` should be derived internally from the shape-to-path decomposition, not threaded through the public projection interface.

2. **ARCH-SD-02** (IsoColor.WHITE tint loss): The `Textured` material's `tint` property is silently discarded — the base color should be `material.tint` for `Textured`, not `IsoColor.WHITE`. For `PerFace`, `LocalDefaultColor.current` is the correct pre-existing default.

**Three medium findings warrant tracking:**

- **ARCH-SD-03**: RGBA8Unorm change is correct; minor cleanup to the `ByteOrder.nativeOrder()` no-op.
- **ARCH-SD-04**: The compute bind group now uses 6 of 8 allowed storage-buffer slots; two more additions would exhaust the portable limit. Document and plan consolidation now.
- **ARCH-SD-05**: `uvCoordsBuf` belongs to a geometry-UV concern, not a texture-atlas concern; it should be extracted from `GpuTextureManager`.

The `IsoColor.WHITE` tint issue (ARCH-SD-02) is the most likely to cause visible rendering bugs in the wild (a caller who sets `tint = IsoColor.RED` on `texturedBitmap(...)` will see unmodified texture colors). It should be fixed before the branch merges.
