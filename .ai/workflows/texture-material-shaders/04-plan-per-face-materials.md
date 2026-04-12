---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: per-face-materials
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-12T22:41:13Z"
metric-files-to-touch: 12
metric-step-count: 14
has-blockers: false
revision-count: 3
tags: [material, per-face]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-per-face-materials.md
  siblings: [04-plan-material-types.md, 04-plan-uv-generation.md, 04-plan-canvas-textures.md, 04-plan-webgpu-textures.md, 04-plan-sample-demo.md]
  implement: 05-implement-per-face-materials.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders per-face-materials"
---

# Implementation Plan: Per-Face Materials

## Context and Dependencies

This slice builds on four completed slices:

- **material-types**: `IsometricMaterial` sealed interface (`FlatColor`, `Textured`, `PerFace`),
  `TextureSource`, `UvCoord`, `RenderCommand.material`, `RenderCommand.uvCoords`, `ShapeNode.material`
- **uv-generation**: `PrismFace` enum (`TOP`, `BOTTOM`, `FRONT`, `BACK`, `LEFT`, `RIGHT`),
  face-type tagged on `RenderCommand`, UV coords generated per face
- **canvas-textures**: `TextureCache`, `MaterialResolver`, Canvas BitmapShader pipeline
- **webgpu-textures**: `GPUTexture` upload, `SceneDataPacker.textureIndex` populated,
  fragment shader `textureSample` path, single-texture bind group at `@group(1)`

The `PerFace` variant already exists as a stub in `IsometricMaterial` from material-types.
This slice gives it a real implementation in both renderers.

---

## Prism Face Index Mapping

Understanding `Prism.createPaths()` (in `isometric-core`) is required before any resolver
code can be written. The six paths are built and appended in this fixed order:

| List index | Construction | Geometric face |
|------------|-------------|----------------|
| 0 | `face1` — xz-plane at `y=position.y` | FRONT (viewer-near side) |
| 1 | `face1.reverse().translate(0, depth, 0)` | BACK |
| 2 | `face2` — yz-plane at `x=position.x` | LEFT |
| 3 | `face2.reverse().translate(width, 0, 0)` | RIGHT |
| 4 | `face3.reverse()` — xy-plane at `z=position.z` | BOTTOM |
| 5 | `face3.translate(0, 0, height)` — xy-plane at top | TOP |

The uv-generation slice tags each `RenderCommand` with the `PrismFace` enum value derived
from this index order. The `PerFace` resolver uses that tag — no geometry re-analysis needed.

**Key invariant**: `Shape.paths` (and therefore `orderedPaths()`) does **not** reorder
Prism faces. Depth sorting reorders `RenderCommand` objects *after* they are created, not
the path list on `Shape`. The face-type tag set during UV generation remains stable through
the render pipeline.

---

## `PerFace` DSL Design

### API §2 (Progressive Disclosure) and §6 (Invalid States) Compliance

Layer 1 — common case (grass top, dirt sides, transparent bottom):
```kotlin
Shape(
    shape = Prism(origin),
    material = perFace {
        top = textured(R.drawable.grass)
        sides = textured(R.drawable.dirt)
    }
)
```

Layer 2 — fine-grained face control:
```kotlin
Shape(
    shape = Prism(origin),
    material = perFace {
        top    = textured(R.drawable.grass)
        front  = textured(R.drawable.dirt_shadow)
        back   = textured(R.drawable.dirt_shadow)
        left   = textured(R.drawable.dirt_light)
        right  = textured(R.drawable.dirt_light)
        bottom = null   // culled / invisible
    }
)
```

Layer 3 — programmatic construction (escape hatch, no DSL):
```kotlin
val mat = IsometricMaterial.PerFace(
    faceMap = mapOf(
        PrismFace.TOP   to grassMaterial,
        PrismFace.FRONT to dirtMaterial,
        PrismFace.BACK  to dirtMaterial,
        PrismFace.LEFT  to dirtMaterial,
        PrismFace.RIGHT to dirtMaterial,
    ),
    default = IsometricMaterial.FlatColor(IsoColor.GRAY)
)
```

### `PerFaceMaterialScope` Builder

```kotlin
@IsometricDslMarker
class PerFaceMaterialScope {
    var top:    IsometricMaterial? = null
    var bottom: IsometricMaterial? = null
    var front:  IsometricMaterial? = null
    var back:   IsometricMaterial? = null
    var left:   IsometricMaterial? = null
    var right:  IsometricMaterial? = null
    var default: IsometricMaterial = IsometricMaterial.FlatColor(IsoColor.TRANSPARENT)

    /**
     * Convenience shorthand: sets front, back, left, right to the same material.
     * Assignment to [sides] is write-only; reading is unsupported.
     */
    var sides: IsometricMaterial?
        get() = error("sides is write-only")
        set(value) { front = value; back = value; left = value; right = value }

    internal fun build(): IsometricMaterial.PerFace {
        val map = buildMap {
            top?.let { put(PrismFace.TOP, it) }
            bottom?.let { put(PrismFace.BOTTOM, it) }
            front?.let { put(PrismFace.FRONT, it) }
            back?.let { put(PrismFace.BACK, it) }
            left?.let { put(PrismFace.LEFT, it) }
            right?.let { put(PrismFace.RIGHT, it) }
        }
        return IsometricMaterial.PerFace(faceMap = map, default = default)
    }
}

fun perFace(block: PerFaceMaterialScope.() -> Unit): IsometricMaterial.PerFace =
    PerFaceMaterialScope().apply(block).build()
```

**Invalid-state rules** (§6):
- `PerFace` with an empty `faceMap` AND `default = FlatColor(TRANSPARENT)` renders all faces
  invisible — this is valid and intentional, not an error.
- The `sides` setter prevents `sides` from coexisting meaningfully with individual face
  assignments made after it; later assignments win. This is natural builder behavior (last
  writer wins), not an invalid state.
- Type system ensures each slot takes an `IsometricMaterial`, not a raw `IsoColor` — wrong
  type is a compile error.

### `IsometricMaterial.PerFace` Data Class

```kotlin
data class PerFace(
    val faceMap: Map<PrismFace, IsometricMaterial>,
    val default: IsometricMaterial = FlatColor(IsoColor.TRANSPARENT)
) : IsometricMaterial {
    fun resolve(face: PrismFace): IsometricMaterial = faceMap[face] ?: default
}
```

`resolve()` is the single resolution function called by both Canvas and WebGPU resolvers.

---

## Canvas Renderer: Per-Face Resolution

### Where Resolution Happens

`TexturedCanvasDrawHook` (introduced in canvas-textures) is a `MaterialDrawHook` that
receives a `RenderCommand` and draws textured faces using `BitmapShader` + affine matrix.
Currently it handles `FlatColor` (returns false — delegates to flat path), `Textured`
(draws with shader), and `PerFace` (only resolves `.default` — falls back if not `Textured`).

This slice updates the `PerFace` branch in `TexturedCanvasDrawHook.draw()` to resolve
per-face using `cmd.faceType`:

```kotlin
is IsometricMaterial.PerFace -> {
    val faceType = cmd.faceType  // PrismFace? tag from uv-generation (added by this slice)
    val sub = if (faceType != null) material.resolve(faceType) else material.default
    when (sub) {
        is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, sub)
        is IsometricMaterial.FlatColor -> false  // delegate to flat-color path
        is IsometricMaterial.PerFace -> false     // should not happen (validated in init)
    }
}
```

`cmd.faceType: PrismFace?` is a field added to `RenderCommand` **by this slice** (T2).
For non-Prism shapes it is `null`, which triggers `mat.default`.

**No Canvas architecture change** — resolution is per-`RenderCommand`, one command per face,
so the existing one-command-per-draw-call model works unchanged. Each face simply gets a
different `BitmapShader` (or `Paint.color`) depending on its role.

**Performance note**: Canvas must change `Paint.shader` between faces with different
textures. This is expected and unavoidable. Batching by material is out of scope.

---

## WebGPU Renderer: Per-Face Multi-Texture Strategy

### Decision: Texture Atlas (chosen approach for this slice)

Three options were evaluated:

| Option | Complexity | Memory | Bleeding risk | Draw calls |
|--------|-----------|--------|--------------|-----------|
| **Texture atlas** (chosen) | Medium | Efficient | Mitigable | 1 |
| Multiple draw calls | Low | n/a | None | N per material group |
| `texture_2d_array` | Low | Wasteful if uneven sizes | None | 1 |

**Texture atlas** is chosen for this slice because:
- Single draw call is preserved — the architectural goal from research §13.6
- The WebGPU atlas bind group is already at `@group(1)` from webgpu-textures
- Isometric face textures are typically square tiles of uniform or near-uniform size,
  making shelf-packing simple and efficient
- Bleeding is mitigated by half-pixel UV inset (research §5.3)

**`texture_2d_array`** is rejected for this slice because uniform dimensions across all
textures cannot be guaranteed. It is noted as a future upgrade path.

**Multiple draw calls** is rejected as a fallback that degrades performance and contradicts
the single-draw-call design that motivated the WebGPU pipeline investment.

### Atlas Manager (New)

A new `TextureAtlasManager` is introduced in `isometric-webgpu`:

```kotlin
internal class TextureAtlasManager(
    private val device: GPUDevice,
    private val maxAtlasSizePx: Int = 2048,
    private val paddingPx: Int = 2,
) {
    data class AtlasRegion(
        val atlasTexture: GPUTexture,
        val uvOffset: FloatArray,  // [u0, v0]
        val uvScale: FloatArray,   // [uScale, vScale]
    )

    /**
     * Returns the atlas region for [key], packing [bitmap] into the atlas if not
     * already present. If the atlas is full, a new atlas page is started.
     * Callers must hold a reference to the returned [GPUTexture] for the atlas lifetime.
     */
    fun getOrPack(key: TextureSource, bitmap: Bitmap): AtlasRegion
    fun destroy()
}
```

Packing algorithm: **Shelf packing** — simple, correct for uniform isometric tiles.
Atlas dimensions: power-of-2, starting at 512×512, doubling up to `maxAtlasSizePx`.
Padding: 2px gutter between entries (duplicates edge pixels) to prevent bleeding.
UV correction: half-pixel inset applied at `getOrPack()` time, baked into `uvOffset`/`uvScale`.

### `SceneDataPacker` Extension: Per-Face Texture Index

The existing `FaceData` struct at offset `124` already has `textureIndex: u32`. The
webgpu-textures slice uses a single global `textureIndex` (0 or `NO_TEXTURE`). This slice
changes the packer to write a **per-face atlas index**:

In `SceneDataPacker.packInto()`, replace the hardcoded `NO_TEXTURE` sentinel:
```kotlin
// Was: buffer.putInt(SceneDataLayout.NO_TEXTURE)
// Now:
val atlasIdx = cmd.atlasTextureIndex ?: SceneDataLayout.NO_TEXTURE
buffer.putInt(atlasIdx)
```

`cmd.atlasTextureIndex: Int?` is a new field on `RenderCommand`. The WebGPU render backend
populates it by resolving `cmd.material` (or the per-face resolved material) through the
`TextureAtlasManager` before packing.

Additionally, the packer must also write **per-face UV offsets** so each face samples from
its own atlas sub-region. Two new fields are added to `FaceData` in the WGSL struct (and
to `SceneDataLayout`):

```
offset 128   8  uvOffset  (vec2<f32>)  — atlas sub-region offset for this face
offset 136   8  uvScale   (vec2<f32>)  — atlas sub-region scale for this face
```

This extends `FaceData` from 144 bytes to 160 bytes. The WGSL struct in
`transform_cull_light.wgsl` and the `FACE_DATA_BYTES` constant in `SceneDataLayout` must
both be updated. The `SceneDataPacker` must be updated to write the new fields.

**WGSL struct update** (`FaceData`):
```wgsl
struct FaceData {
    // ... existing fields up to offset 124 ...
    textureIndex: u32,       // offset 124
    faceIndex: u32,          // offset 128
    uvOffset: vec2<f32>,     // offset 132
    uvScale: vec2<f32>,      // offset 140
    // offset 148 — 4 bytes padding to reach 16-byte alignment
    _pad: u32,               // offset 148
    // total: 152 bytes
}
```

Wait — the existing layout already packs `faceIndex` at 128 and `_padding` (12 bytes) at
132–143 for a 144-byte total. The `uvOffset` and `uvScale` each need 8 bytes, totalling 16
bytes. The existing 12-byte padding block at offsets 132–143 is replaced:

```
offset 128   4  faceIndex  (u32)
offset 132   8  uvOffset   (vec2<f32>)
offset 140   8  uvScale    (vec2<f32>)
offset 148   4  _padding   (u32)
total: 152 bytes → round up to next 16-byte boundary = 160 bytes
```

Update `SceneDataLayout.FACE_DATA_BYTES = 152`.

### Per-Face Material Resolution on WebGPU

Resolution is done CPU-side during scene packing (before upload), not in the shader. This
keeps the WGSL fragment shader simple and avoids GPU-side material lookup tables.

```kotlin
// In WebGpuSceneRenderer, before SceneDataPacker.packInto():
fun resolvePerFaceMaterials(
    commands: List<RenderCommand>,
    atlasManager: TextureAtlasManager,
    textureLoader: TextureLoader,
): List<RenderCommand> {
    return commands.map { cmd ->
        val mat = cmd.material ?: return@map cmd
        val effectiveMat = when (mat) {
            is IsometricMaterial.PerFace -> {
                val face = cmd.faceType
                if (face != null) mat.resolve(face) else mat.default
            }
            else -> mat
        }
        val region = when (effectiveMat) {
            is IsometricMaterial.Textured -> {
                val bitmap = textureLoader.load(effectiveMat.texture)
                atlasManager.getOrPack(effectiveMat.texture, bitmap)
            }
            else -> null
        }
        cmd.copy(
            material = effectiveMat,
            atlasTextureIndex = region?.let { 0 }, // single atlas page for now
            uvOffset = region?.uvOffset ?: floatArrayOf(0f, 0f),
            uvScale  = region?.uvScale  ?: floatArrayOf(1f, 1f),
        )
    }
}
```

The resolved `commands` list is then passed to `SceneDataPacker.packInto()`.

### WGSL Shader Update: Fragment Shader

The webgpu-textures slice adds `textureSample` when `textureIndex != NO_TEXTURE`. This
slice is compatible — the atlas is the same `texture_2d` at `@group(1) @binding(0)`, but
per-face `uvOffset`/`uvScale` are now interpolated from the vertex buffer instead of
coming from a global uniform.

In `GpuTriangulateEmitPipeline` (the emit pass that writes the final vertex buffer), add
the per-face UV transform into the per-vertex output:
```wgsl
// Per-vertex UV is now: baseUV * uvScale + uvOffset
// where baseUV is the face-local [0,0]→[1,1] quad coord
out.texCoord = baseUV * in_face.uvScale + in_face.uvOffset;
```

The fragment shader change is minimal — it already uses `in.texCoord` to sample. No
change needed there once the emit pass writes the correct transformed UVs.

---

## Dependency Graph

```
T1 (PerFaceMaterialScope + PerFace.resolve()) ─────────────────────────────────────────┐
T2 (RenderCommand: add faceType, atlasTextureIndex, uvOffset, uvScale) ─────────────┐  │
T3 (TexturedCanvasDrawHook: PerFace branch, Canvas) ← T1, T2 ──────────────────────┤  │
T4 (TextureAtlasManager) ← (device available from webgpu-textures) ─────────────────┤  │
T5 (SceneDataLayout: FACE_DATA_BYTES 144→160, add uvOffset/uvScale fields) ─────────┤  │
T6 (SceneDataPacker: write faceIndex, uvOffset, uvScale, textureIndex per face) ← T2,T5┤  │
T7 (WGSL FaceData struct update: uvOffset/uvScale fields) ← T5 ─────────────────────┤  │
T8 (GpuTriangulateEmitPipeline: emit per-face UV transform) ← T7 ───────────────────┤  │
T9 (GpuFullPipeline: resolvePerFaceMaterials() + wire TextureAtlasManager) ← T1,T2,T4,T6 │
T10 (ShapeNode.material: accept PerFace; Shape() composable wire-up) ← T1 ──────────┘  │
T11 (Unit tests: PerFace.resolve() for all 6 faces, default fallback) ← T1 ─────────────┤
T12 (Integration tests: Canvas PerFace renders grass top / dirt sides) ← T3, T10 ───────┤
T13 (Integration tests: WebGPU PerFace atlas packing + UV regions) ← T9 ─────────────────┤
T14 (Snapshot tests: Paparazzi golden images for perFace {}) ← T12 ──────────────────────┘
```

---

## Step-by-Step Implementation

### T1 — Finalize `PerFace` data class and `perFace {}` DSL

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/material/PerFaceMaterial.kt` (NEW)

Add `PerFaceMaterialScope`, `perFace {}` top-level builder function, and ensure
`IsometricMaterial.PerFace.resolve(PrismFace): IsometricMaterial` exists.

The `material-types` slice may have `PerFace` as a stub or partially defined. Verify
and complete the class contract. The `sides` write-only convenience setter must be present.

**Dep:** None (foundation task)

---

### T2 — Extend `RenderCommand` with `faceType` + per-face atlas fields

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/RenderCommand.kt`

Add defaulted fields so existing construction sites compile unchanged:
```kotlin
val faceType: PrismFace? = null,          // set during renderTo() for Prism shapes
val atlasTextureIndex: Int? = null,        // set by WebGPU resolver
val uvOffset: FloatArray = floatArrayOf(0f, 0f),  // atlas sub-region offset
val uvScale:  FloatArray = floatArrayOf(1f, 1f),  // atlas sub-region scale
```

Update `equals`, `hashCode`, `toString`, and `copy()` accordingly.
Update `apiDump` for `isometric-core`.

**File:** `isometric-compose/src/main/kotlin/.../runtime/IsometricNode.kt`

In `ShapeNode.renderTo()` (line ~278), populate `faceType` when the shape is a `Prism`:
```kotlin
faceType = if (transformedShape is Prism) PrismFace.fromPathIndex(index) else null,
```

Similarly update `MaterialShapeNode.renderTo()` and `MultiMaterialShapeNode.renderTo()`.

**Note:** The uv-generation slice did NOT add `faceType` — this slice adds it.

**Dep:** None (can run in parallel with T1)

---

### T3 — `TexturedCanvasDrawHook`: per-face resolution

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TexturedCanvasDrawHook.kt`

Update the `is IsometricMaterial.PerFace` branch in `draw()` (currently at line 52–59)
to resolve using `cmd.faceType` instead of always falling back to `material.default`.
When `cmd.faceType == null` (non-Prism shapes), still fall through to `mat.default`.

**Dep:** T1, T2

---

### T4 — Implement `TextureAtlasManager`

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/atlas/TextureAtlasManager.kt` (NEW)

Implement shelf-packing into a `GPUTexture`. Key details:
- Atlas starts as a 512×512 `RGBA8Unorm` texture. Doubles to 1024, 2048 as needed.
- Per-entry padding: 2px gutter. Edge pixels duplicated into gutter to prevent bleeding.
- UV correction: half-pixel inset baked into returned `AtlasRegion.uvOffset`/`uvScale`.
- `getOrPack()` is idempotent for the same `TextureSource` key.
- `destroy()` calls `GPUTexture.destroy()` on all atlas pages.
- Atlas is rebuilt from scratch on `SceneDataUploader.upload()` call (no incremental
  update in this slice — defer LRU eviction to a future optimization).

**Dep:** Requires `GPUDevice` from initialized WebGPU context (runtime dependency only)

---

### T5 — Update `SceneDataLayout` constants

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPacker.kt`

Update comment block and constant:
```kotlin
//   128       4   faceIndex   (u32)
//   132       8   uvOffset    (vec2<f32>)
//   140       8   uvScale     (vec2<f32>)
//   148       4   _padding    (u32)
// 152  →  160  (next 16-byte aligned boundary)

const val FACE_DATA_BYTES = 152
```

**Dep:** None (constant-only change, but must land before T6 and T7)

---

### T6 — Update `SceneDataPacker.packInto()` to write per-face atlas data

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/SceneDataPacker.kt`

Replace the `_padding` block (3 × `putInt(0)`) with:
```kotlin
// textureIndex — from resolved per-face command
buffer.putInt(cmd.atlasTextureIndex ?: SceneDataLayout.NO_TEXTURE)

// faceIndex
buffer.putInt(index)

// uvOffset (vec2<f32>)
buffer.putFloat(cmd.uvOffset[0])
buffer.putFloat(cmd.uvOffset[1])

// uvScale (vec2<f32>)
buffer.putFloat(cmd.uvScale[0])
buffer.putFloat(cmd.uvScale[1])

// _padding
buffer.putInt(0)
```

**Dep:** T2, T5

---

### T7 — Update WGSL `FaceData` struct

**File:** `isometric-webgpu/src/main/kotlin/.../shader/TransformCullLightShader.kt`

The WGSL is embedded as a Kotlin string (`val WGSL: String`), not an asset file.
Update `struct FaceData` to add `uvOffset` and `uvScale` fields at the correct offsets
matching T5. The `faceIndex` field stays at offset 128 — the `_padding` block
(`_f0, _f1, _f2: u32 × 3`) shrinks to `uvOffset: vec2<f32>`, `uvScale: vec2<f32>`,
`_padding: u32`.

**Dep:** T5

---

### T8 — `TriangulateEmitShader`: apply per-face UV transform

**File:** `isometric-webgpu/src/main/kotlin/.../shader/TriangulateEmitShader.kt`
(WGSL is embedded as a Kotlin string in the `WGSL` property)

Modify the emit pass to transform per-vertex UVs using the face's `uvOffset`/`uvScale`:
```wgsl
let atlasUV = baseUV * faceData.uvScale + faceData.uvOffset;
```

Where `baseUV` is the face-local `[0,0]→[1,1]` coordinate (already computed from vertex
position within the quad). The fragment shader receives `atlasUV` and samples the atlas.

**Dep:** T7

---

### T9 — `GpuFullPipeline` + `WebGpuSceneRenderer`: wire per-face resolution and atlas manager

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt`

**Post-review-fix state:** `textureBinder` is `private lateinit var`, created inside
`ensurePipelines(renderPipeline: GpuRenderPipeline)`. The `uploadTextures()` method
currently handles only a single texture (first found). Replace with atlas-based upload:

1. Add `TextureAtlasManager` as a field (created in `ensurePipelines`).
2. Replace `uploadTextures(scene)` with `uploadTextureAtlas(scene)` that:
   - Scans all commands for distinct textures (expanding `PerFace` materials)
   - Packs all distinct bitmaps into the atlas
   - Rebuilds the bind group with the atlas texture view
3. Replace the single-texture `uploadedTexture`/`uploadedTextureView` fields with
   atlas-managed textures.
4. In `close()`, destroy atlas manager.

**File:** `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt`

In `upload()`, before `SceneDataPacker.packInto()`, call `resolvePerFaceMaterials()`
to expand `PerFace` materials into per-command atlas regions and populate
`atlasTextureIndex`, `uvOffset`, `uvScale` on each command.

The `resolvePerFaceMaterials()` helper lives as a `private` function in
`GpuFullPipeline` or a sibling file.

**Dep:** T1, T2, T4, T6

---

### T10 — Ensure overloaded `Shape()` in `isometric-shader` accepts `PerFace`

**File:** `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt`

`ShapeNode.material: MaterialData?` (typed to core marker interface, set by shader module).
Verify `ShapeNode.renderTo()` passes `material` through to `RenderCommand` unchanged. The
`RenderCommand.faceType` is populated by UV generation, not here. No change expected.

**File (shader composable):** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`

Confirm the overloaded `Shape(geometry, material: IsometricMaterial)` accepts
`perFace { ... }` (which returns `IsometricMaterial.PerFace`). This works because
`PerFace` is a subtype of `IsometricMaterial`. No code change expected.

**Note (material-types rev 3):** The `Shape()` composable in `isometric-compose` has NO
material parameter. The DSL examples in this plan (`Shape(shape, material = perFace { ... })`)
use the shader module's overloaded `Shape()`, not the compose module's.

**Dep:** T1

---

### T11 — Unit tests: `PerFace.resolve()`

**File:** `isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/PerFaceMaterialTest.kt` (NEW)

Test cases:
- `resolve(TOP)` returns `top` when set
- `resolve(FRONT)` returns `front` when set; falls back to `default` when not set
- `resolve(BOTTOM)` returns `default` when `bottom` not set in faceMap
- `perFace { sides = textured(…) }` sets FRONT/BACK/LEFT/RIGHT, TOP/BOTTOM use default
- `perFace {}` (all defaults) resolves every face to `FlatColor(TRANSPARENT)`
- `PerFace.resolve()` is pure and idempotent

**Dep:** T1

---

### T12 — Integration tests: Canvas per-face rendering

**File:** `isometric-compose/src/androidTest/…/PerFaceCanvasTest.kt` (NEW)

Test cases:
- `perFace { top = textured(grass); sides = textured(dirt) }` — top face uses grass
  texture BitmapShader, side faces use dirt texture BitmapShader
- `perFace { top = flatColor(GREEN) }` — non-top faces use default (TRANSPARENT or GRAY)
- Non-Prism path (faceType == null): `mat.default` is used without crash

Verify via `MaterialResolver` mock or Paparazzi snapshot.

**Dep:** T3, T10

---

### T13 — Integration tests: WebGPU atlas packing

**File:** `isometric-webgpu/src/androidTest/…/PerFaceWebGpuTest.kt` (NEW)

Test cases:
- Pack 2 distinct textures; verify `AtlasRegion` UV regions do not overlap
- `resolvePerFaceMaterials()` with a `PerFace` material produces 6 `RenderCommand`
  objects with correct `atlasTextureIndex` and distinct `uvOffset`/`uvScale` per
  face type
- `SceneDataPacker.packInto()` writes `uvOffset` and `uvScale` at the correct byte offsets
  in the output buffer
- Bleeding-prevention UV inset: `uvOffset` is at least `0.5/atlasWidth` greater than the
  raw packed region corner

**Dep:** T9

---

### T14 — Snapshot tests (Paparazzi)

**File:** `isometric-compose/src/test/…/PerFaceMaterialSnapshotTest.kt` (NEW)

Golden images for:
- Prism with `perFace { top = textured(grass); sides = textured(dirt) }`
- Prism with `perFace { top = flatColor(GREEN); bottom = flatColor(BROWN) }`
- Prism with `perFace {}` (all transparent — should render as invisible Prism)

**Dep:** T12

---

## Implementation Order (Batching for Parallelism)

```
Batch 1 (parallel, no deps): T1, T2, T5
Batch 2 (parallel, need Batch 1): T3 (T1+T2), T4, T6 (T2+T5), T7 (T5)
Batch 3 (parallel, need Batch 2): T8 (T7), T9 (T1+T2+T4+T6), T10 (T1)
Batch 4 (parallel, need Batch 3): T11 (T1), T12 (T3+T10), T13 (T9)
Batch 5 (needs Batch 4): T14 (T12)
```

---

## File Inventory

| File | Action | Tasks |
|------|--------|-------|
| `isometric-shader/.../material/PerFaceMaterial.kt` | NEW | T1 |
| `isometric-core/.../RenderCommand.kt` | MODIFY | T2 |
| `isometric-compose/.../runtime/IsometricNode.kt` | MODIFY | T2 (populate faceType) |
| `isometric-shader/.../render/TexturedCanvasDrawHook.kt` | MODIFY | T3 |
| `isometric-webgpu/.../atlas/TextureAtlasManager.kt` | NEW | T4 |
| `isometric-webgpu/.../pipeline/SceneDataPacker.kt` | MODIFY | T5, T6 |
| `isometric-webgpu/.../shader/TransformCullLightShader.kt` | MODIFY | T7 |
| `isometric-webgpu/.../shader/TriangulateEmitShader.kt` | MODIFY | T8 |
| `isometric-webgpu/.../pipeline/GpuFullPipeline.kt` | MODIFY | T9 |
| `isometric-shader/.../IsometricMaterialComposables.kt` | VERIFY (likely no change) | T10 |
| `isometric-shader/src/test/.../PerFaceMaterialTest.kt` | NEW | T11 |
| `isometric-compose/src/androidTest/.../PerFaceCanvasTest.kt` | NEW | T12 |
| `isometric-webgpu/src/androidTest/.../PerFaceWebGpuTest.kt` | NEW | T13 |
| `isometric-compose/src/test/.../PerFaceMaterialSnapshotTest.kt` | NEW | T14 |

**Total:** 14 files (5 new, 8 modified, 1 verify)

---

## Risk Register

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| `FACE_DATA_BYTES` change (144→160) breaks existing WebGPU tests | High | Update all buffer-size assertions in existing tests in the same batch as T5 |
| Atlas UV bleeding on parallelogram faces | Medium | Half-pixel UV inset in `TextureAtlasManager.getOrPack()` (research §5.3) |
| `faceType` is `null` for non-Prism paths | Medium | `PerFace.default` is always the fallback; `null` faceType never panics |
| `TextureAtlasManager` atlas page overflow (> 2048×2048) | Low | Start new atlas page; bind group switching deferred — log warning for now |
| Shelf packer wastes space for mixed-size textures | Low | Shelf packing is sufficient for uniform isometric tiles in v1 |

---

## Test / Verification Plan

### Automated checks

- **Build:** `./gradlew build -x test -x apiCheck` — all modules compile
- **Unit tests:** `./gradlew test` — all existing + new `PerFaceMaterialTest` pass
- **API compatibility:** `./gradlew apiCheck` — no unintended public API breaks
- **Buffer layout test (T13):** Byte-level inspection of `SceneDataPacker` output verifying
  `uvOffset`/`uvScale` at correct offsets (132, 140) in the 152-byte `FaceData`

### Interactive verification (human-in-the-loop)

The per-face-materials slice requires on-device visual verification for 3 acceptance
criteria. The sample-demo slice adds a "Per-Face" tab to `WebGpuSampleActivity`. If
implementing before sample-demo, temporarily add a per-face test scene to the existing
"Textured" tab.

#### V1 — Per-face Canvas rendering: distinct textures per face

- **What to verify:** A Prism with `perFace { top = textured(grass); sides = textured(dirt) }`
  shows the grass texture on the top face and dirt texture on the four side faces
- **Platform & tool:** Android device (Samsung SM-F956B), adb
- **Steps:**
  1. `./gradlew :app:installDebug`
  2. `adb shell am start -n io.github.jayteealao.isometric.sample/.WebGpuSampleActivity`
  3. Navigate to the tab containing the per-face sample scene
  4. Select "Canvas" render mode
  5. `adb exec-out screencap -p > verify-evidence/perface-canvas.png`
- **Pass criteria:** Top face visibly different texture from side faces. No magenta
  checkerboard (fallback) on any visible face.

#### V2 — Per-face WebGPU rendering: atlas-backed texture mapping

- **What to verify:** Same `perFace` material renders correctly in Full WebGPU mode
  with distinct textures per face, matching the Canvas layout
- **Platform & tool:** Same device, adb
- **Steps:**
  1. Same app, same tab
  2. Select "Full WebGPU" render mode
  3. `adb exec-out screencap -p > verify-evidence/perface-webgpu.png`
  4. Compare side-by-side with `perface-canvas.png`
- **Pass criteria:** Same face-to-texture mapping as Canvas. Top face has grass, sides
  have dirt. No atlas UV bleeding (seams between textures). No GPU errors in logcat.

#### V3 — Mixed per-face + flat-color scene

- **What to verify:** A scene with one `perFace` prism and one flat-color prism renders
  both correctly — per-face prism has distinct textures, flat-color prism is solid
- **Platform & tool:** Same device, adb
- **Steps:**
  1. Same app; scene should include both material types
  2. Verify in both Canvas and WebGPU modes
  3. `adb exec-out screencap -p > verify-evidence/perface-mixed.png`
- **Pass criteria:** Flat-color prism renders as solid color (no texture). Per-face prism
  shows correct per-face textures. No interference between the two.

#### V4 — Default fallback for unset faces

- **What to verify:** A `perFace { top = textured(grass) }` prism with no other faces set
  uses the `default` material (transparent or flat color) for non-top faces
- **Platform & tool:** Same device, adb
- **Steps:**
  1. Modify sample scene to have a prism with only `top` set
  2. Verify non-top faces render using the default material
- **Pass criteria:** Non-top faces are either transparent (default) or the specified
  default color. Top face shows the grass texture.

#### V5 — No performance regression on non-textured scenes

- **What to verify:** "Animated Towers" tab (all non-textured prisms) still renders
  smoothly at interactive framerate in WebGPU mode
- **Platform & tool:** Same device, visual inspection
- **Steps:**
  1. Navigate to "Animated Towers" tab, select "Full WebGPU"
  2. Observe rendering for 5+ seconds
  3. Check logcat: `adb logcat -d | grep -i "device.lost\|TDR\|error"` filtered to app
- **Pass criteria:** Smooth animation, no jank, no device-lost, no GPU errors.

### Evidence storage

All screenshots saved to `.ai/workflows/texture-material-shaders/verify-evidence/`
with `perface-` prefix.

---

## Acceptance Criteria Verification

- [ ] `perFace { top = textured(grass); sides = textured(dirt) }` — Canvas: top face
  renders grass BitmapShader, side faces render dirt BitmapShader **(V1)**
- [ ] Same material in WebGPU mode: same face-to-texture mapping visible on device **(V2)**
- [ ] `PerFace` with no `bottom` specified — bottom face uses `default` material **(V4)**
- [ ] `perFace {}` (all unset) — all faces render as transparent (no crash) **(unit test T11)**
- [ ] `resolve(face)` for every `PrismFace` value returns correct material or default **(unit test T11)**
- [ ] Atlas UV regions do not overlap for 2 different textures packed together **(unit test T13)**
- [ ] `SceneDataPacker` writes `uvOffset`/`uvScale` at correct byte offsets **(unit test T13)**
- [ ] No performance regression for non-textured scenes **(V5)**
- [ ] Mixed per-face + flat-color scene renders correctly **(V3)**

## Revision History

### 2026-04-11 — Cohesion Review (rev 1)
- Mode: Review-All (cohesion check after material-types dependency inversion)
- Issues found: 2 (1 MED, 1 LOW)
  1. **MED:** T10 verification targeted compose-level `Shape()` and `IsometricScene.kt` —
     but the `Shape()` composable accepting `IsometricMaterial` is in `isometric-shader`, not
     compose. Fix: rewrote T10 to target the shader module's overloaded `Shape()`.
  2. **LOW:** DSL examples show `Shape(shape, material = perFace { ... })` without clarifying
     this is the shader-module overload. Fix: added note in T10.

### 2026-04-12 — Auto-Review (rev 2)
- Mode: Auto-Review (post webgpu-textures implementation + review fixes)
- Issues found: 6

  1. **HIGH:** `MaterialResolver` class does not exist. Canvas-textures slice implemented
     `TexturedCanvasDrawHook` instead. Fix: rewrote T3 and §Canvas Renderer to reference
     `TexturedCanvasDrawHook.draw()` PerFace branch.

  2. **HIGH:** `RenderCommand.faceType` does not exist. The uv-generation slice did NOT
     add this field. Fix: moved `faceType` addition into T2 (this slice adds it), and
     documented the population point in `ShapeNode.renderTo()` / `IsometricNode.kt`.

  3. **MED:** `GpuFullPipeline` refactored by review fixes — `textureBinder` is now
     `lateinit`, `ensurePipelines()` takes `GpuRenderPipeline` param. Fix: updated T9
     to target `GpuFullPipeline` instead of `WebGpuSceneRenderer`.

  4. **MED:** WGSL shaders are Kotlin string objects (e.g., `TransformCullLightShader.kt`),
     not `.wgsl` asset files. Fix: corrected file paths in T7 and T8.

  5. **LOW:** `FACE_DATA_BYTES` inconsistency — plan body computed 152 but index summary
     said 160. Fix: standardized to 152 throughout (next 8-byte aligned boundary after
     148 bytes of content = 152, since vec2 alignment is 8).

  6. **LOW:** File inventory had wrong target for T3 (`MaterialResolver.kt` → 
     `TexturedCanvasDrawHook.kt`) and wrong target for T9 (`WebGpuSceneRenderer` →
     `GpuFullPipeline`). Fix: updated inventory.

### 2026-04-12 — Directed Fix (rev 3)
- Mode: Directed Fix
- Feedback: "visual verification"
- What was changed: Added `## Test / Verification Plan` section with 5 interactive
  verification steps (V1–V5) covering Canvas per-face rendering, WebGPU atlas rendering,
  mixed scenes, default fallback, and performance regression. Each step includes platform,
  adb commands, screenshot capture, and pass criteria. Cross-referenced acceptance criteria
  with verification IDs. Added evidence storage convention.
