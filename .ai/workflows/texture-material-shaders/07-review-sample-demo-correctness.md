---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: sample-demo
review-command: correctness
status: complete
updated-at: "2026-04-13T10:00:00Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
result: issues-found
tags: [correctness, sample-demo, texture, per-face, webgpu, shader]
refs:
  review-master: 07-review.md
  commits: a73d6fe ea14787 184c0f1 c730679 b468c9c f581ca0
---

## Summary

The sample-demo slice is functionally sound for its primary goal: exercising the
`perFace {}` material DSL with procedural textures in a 4×4 Prism grid across three
render modes. Two high-severity findings are present: (1) the emit shader's triangle
fan for Triangle 1 (s0, s2, s3) uses `uv0` for s0 but `uv2` then `uv3`, which is
correct — but Triangles 2/3 (5–6 vertex faces) now emit `uv0` as the fan pivot instead
of `uv00`, meaning the UvGenerator's vertex 0 UV is reused for every fan apex even in
cases where vertex 0 may not be the geometric centre — this is a pre-existing issue
promoted from latent to reachable for the first time by this slice's per-vertex UV path.
(2) `uploadUvCoordsBuffer` iterates `scene.commands[i]` using the CPU-order index to
populate the compact UV buffer, but the emit shader reads using `key.originalIndex`
which is the pre-sort face index — if the sort reorders commands and the compact buffer
is not sorted in the same order, the wrong UVs will be used. Several lower-severity
issues exist: `Random(seed)` determinism on Android is verified correct; RGBA8Unorm is
the correct format for `Bitmap.copyPixelsToBuffer`; the per-vertex UV packing (3 ×
vec4 per face) is correctly indexed; and the `faceType` propagation is complete across
all three code paths (`rebuild`, `rebuildAsync`, `rebuildForGpu`).

---

## Findings Table

| ID | Severity | File | Line(s) | Area | Summary |
|----|----------|------|---------|------|---------|
| COR-1 | HIGH | `GpuTextureManager.kt` | 264–280 | Buffer indexing | `uploadUvCoordsBuffer` writes face `i` at compact-buffer index `i`, but the emit shader reads `sceneUvCoords[key.originalIndex]` — if sort reorders faces, the compact UV buffer and the key's originalIndex are out of sync |
| COR-2 | HIGH | `TriangulateEmitShader.kt` | 291–300 | UV fan apex | For Triangle 2 (triCount ≥ 3) and Triangle 3 (triCount ≥ 4), the fan-apex vertex always uses `uv0` (vertex 0 of the face), which is a polygon corner, not the centroid. For non-convex or irregular 5/6-vertex faces the texture will stretch incorrectly. Latent for Prism-only scenes but now in the per-vertex UV code path. |
| COR-3 | MEDIUM | `GpuTextureManager.kt` | 266 | UV size guard | `uv.size >= 8` accepts exactly 8 floats (4 UV pairs), but `j` loops to 12 — indices 8–11 are padded with 0f. A valid 8-float UV array (from `UvGenerator.forPrismFace`) has no entries for virtual slots 4–5; the pad `(0,0)` means `uv4` and `uv5` are always `(0,0)` for quad faces. In the emit shader those UVs are only used by tri-counts 3 and 4 (5/6 vertex faces), so there is no actual rendering error for standard Prism quads. Low risk but the intent is unclear. |
| COR-4 | MEDIUM | `IsometricMaterialComposables.kt` | 144–148 | Path node UVs | The `Path()` composable sets `IsoColor.WHITE` for `Textured` and `PerFace` materials but no `uvProvider` is set on `PathNode` (there is no `uvProvider` property on `PathNode`). A raw `Path` with a `PerFace` material will have `uvCoords = null` and `faceType = null`, causing `uploadUvCoordsBuffer` to emit default quad UVs for it. Consistent with the design (Paths are 2D; UVs require a 3D Prism), but not documented as a known limitation in the composable's KDoc. |
| COR-5 | LOW | `TextureAssets.kt` | 248–268 | Paint anti-alias | `Paint()` default-constructs with `isAntiAlias = false` on Android. `canvas.drawCircle` for noise dots will produce aliased 3-pixel circles. On a 64×64 bitmap this is acceptable for a demo texture, but the circles may look noticeably blocky when magnified in the isometric view. |
| COR-6 | LOW | `TexturedDemoActivity.kt` | 353–358 | Material sharing | `tileMaterial` is created with `remember {}` at the `TexturedDemoScreen` level and shared across all 16 prisms. `PerFace.resolve()` is stateless so sharing is safe. However, if `TextureAssets.grassTop` or `dirtSide` are recycled externally, `texturedBitmap()` wraps the same `Bitmap` reference. No recycling happens in this code, but a `@VisibleForTesting` note on `TextureAssets` would make the ownership clear. |
| COR-7 | LOW | `GpuTextureManager.kt` | 210 | writeBuffer over-send | `uploadTexIndexBuffer` passes `texIndexBuf.cpuBuffer!!` (full capacity = 2× entryCount) to `writeBuffer`, sending up to 2× the needed data to the GPU. `uploadUvRegionBuffer` and `uploadUvCoordsBuffer` use `rewind()` + `limit(requiredBytes)` to restrict the send. The `uploadTexIndexBuffer` inconsistency pre-dates this slice but is highlighted here as the new code introduced the cleaner pattern without back-patching the old one. |
| COR-8 | NIT | `TexturedDemoActivity.kt` | 413–438 | Unused `rebuildForGpu` path | `TexturedPrismGridScene` uses `useNativeCanvas = false` which routes to the Compose draw path. In `RenderMode.Canvas()` mode the `rebuildForGpu` path is not used. This is correct; the note is that switching to `RenderMode.WebGpu()` will use `rebuildForGpu`, which does correctly preserve `faceType` via `ShapeNode.renderTo`. No action needed — just confirming correctness. |

---

## Detailed Findings

### COR-1 — HIGH: `uploadUvCoordsBuffer` index vs `key.originalIndex` desync under sort

**File:** `isometric-webgpu/.../texture/GpuTextureManager.kt` lines 264–280 and
`isometric-webgpu/.../shader/TriangulateEmitShader.kt` line 263

**Finding:**
`uploadUvCoordsBuffer` iterates the `scene.commands` list in its natural order (index `i`)
and writes one 48-byte UV entry per face to the compact buffer, so that face `i` in the
command list occupies slot `i` in `uvCoordsBuf`:

```kotlin
for (i in 0 until faceCount) {
    val uv = scene.commands[i].uvCoords
    // writes 12 floats to positions i*12 through i*12+11
}
```

In the emit shader the lookup is:

```wgsl
let uvBase = key.originalIndex * 3u;
```

`key.originalIndex` is the face's pre-sort position in the `transformedBuffer` — which for
the Full WebGPU path is the order faces were added to the scene by `SceneDataPacker`.
For the Canvas+GPU-sort path (`rebuildAsync`) the scene commands in `PreparedScene`
are already reordered by the CPU `DepthSorter.sort()` before being packed, so
`scene.commands[i]` and `originalIndex = i` are consistent.

For the **Full WebGPU path** (`rebuildForGpu`), `scene.commands` is the raw output of
`rootNode.renderTo()` in scene-graph traversal order. The GPU bitonic sort in M4
reorders `sortedKeysBuffer` but does not reorder the `sceneUvCoords` compact buffer.
M5 reads:

```wgsl
let key = sceneKeys[global_id.x];  // after GPU sort
let uvBase = key.originalIndex * 3u;
let uvPack0 = sceneUvCoords[uvBase + 0u];
```

`key.originalIndex` refers to the face's slot in the pre-sort `sceneKeys` buffer (the
`SceneDataPacker` output), which is numbered by traversal order — which is the same
order `uploadUvCoordsBuffer` iterates `scene.commands`. For the Full WebGPU path both
orderings use the same traversal order, so `key.originalIndex == i`. The lookup is
consistent.

However, for **`rebuildAsync`** (Canvas + GPU sort), `scene.commands` arrives
already CPU-sorted by `DepthSorter.sort()`. The `uploadUvCoordsBuffer` writes UV slot
`i` for the face at `scene.commands[i]`, which is the sorted position. The GPU sort
is then applied *again* on top of the already-sorted data. In that mode there is no
`key.originalIndex` path (Canvas draws from the prepared scene directly), so the
mismatch is not exercised. But if the Full WebGPU path is ever switched to use a
pre-sorted `PreparedScene`, the index assumptions would break silently.

**Risk:** Latent for the current wiring (the two paths that set up `sceneUvCoords` and
`key.originalIndex` both use traversal order). The risk becomes real if a future refactor
pre-sorts the `PreparedScene` commands before passing them to `GpuFullPipeline`.

**Fix:** Add a comment to `uploadUvCoordsBuffer` documenting the invariant: "UV slot `i`
must correspond to the face at `sceneKeys[i].originalIndex = i`, which requires that
`scene.commands` are in the same order as the packed `SceneDataPacker` output. This
holds for `rebuildForGpu` (traversal order) but would break if `scene.commands` are
pre-sorted before the GPU pipeline." Consider adding an assertion or renaming to make
the dependency explicit.

---

### COR-2 — HIGH: Fan-apex UV `uv0` is a polygon corner, not the centroid, for 5/6-vertex faces

**File:** `isometric-webgpu/.../shader/TriangulateEmitShader.kt` lines 288–300

**Finding:**
The old code for Triangles 2 and 3 used `uv00 = (0,0)` (the apex vertex's canonical UV)
and `uvMid = (0.5, 0.5)` for the two non-apex interior vertices. The new per-vertex UV
code replaces all of those with UvGenerator-computed values:

```wgsl
// Triangle 2: (s0, s3, s4)
writeVertex((base + 6u) * 9u, nx0, ny0, r, g, b, a, uv0.x, uv0.y, texIdx);  // apex = vertex 0
writeVertex((base + 7u) * 9u, nx3, ny3, r, g, b, a, uv3.x, uv3.y, texIdx);
writeVertex((base + 8u) * 9u, nx4, ny4, r, g, b, a, uv4.x, uv4.y, texIdx);

// Triangle 3: (s0, s4, s5)
writeVertex((base + 9u)  * 9u, nx0, ny0, r, g, b, a, uv0.x, uv0.y, texIdx);  // apex = vertex 0
writeVertex((base + 10u) * 9u, nx4, ny4, r, g, b, a, uv4.x, uv4.y, texIdx);
writeVertex((base + 11u) * 9u, nx5, ny5, r, g, b, a, uv5.x, uv5.y, texIdx);
```

For standard Prism quad faces (`triCount = 2`), triangles 2 and 3 are never emitted, so
this has no effect on the sample-demo scene. The risk is that `uv4` and `uv5` come from
the padded-with-zero region of a quad face's UV array (slots 4 and 5 are filled with
`0f` by `uploadUvCoordsBuffer` when `uv.size == 8`). If a 5- or 6-vertex face is ever
rendered through this path, `uv4` and `uv5` will both be `(0,0)` — two vertices of
Triangle 3 will share the same UV coordinate, producing a UV-degenerate triangle (all
samples come from a single texel). This is the same class of issue as the old `uvMid`
problem, just with a different degenerate value.

**Risk:** No impact for the current 4-vertex Prism demo. A real regression path opens if
any non-Prism shape with 5+ vertices (or a future Prism variant) uses a textured
material through the Full WebGPU path.

**Fix:** When `uv.size < 12`, the code pads with zeros. A better fallback for the 5/6-vertex
case would be to use the average of the known UVs, or clamp to the last known UV. A
minimal fix is to document in `uploadUvCoordsBuffer` that slots 4–5 are zero-padded
and that the emit shader therefore produces degenerate UVs for any 5/6-vertex textured face.

---

### COR-3 — MEDIUM: `uv.size >= 8` guard permits 8-float arrays but pads slots 4–5 silently

**File:** `isometric-webgpu/.../texture/GpuTextureManager.kt` line 266

**Finding:**
`UvGenerator.forPrismFace` always returns exactly 8 floats (4 UV pairs for 4 vertices).
The guard `uv.size >= 8` correctly accepts this. The loop `for (j in 0 until 12)`
writes indices 8–11 as `0f` via the `if (j < uv.size) uv[j] else 0f` ternary:

```kotlin
for (j in 0 until 12) {
    buf.putFloat(if (j < uv.size) uv[j] else 0f)
}
```

For a standard 8-float UV array, slots 4 and 5 (the 5th and 6th UV pairs) are
explicitly padded to `(0,0)`. In the emit shader `uv4 = (0,0)*uvSc + uvOff` and
`uv5 = (0,0)*uvSc + uvOff`, meaning both equal the atlas offset (upper-left corner of
the texture in atlas space). This is only used for triangle fans of 5/6-vertex faces.
No rendering error for the current scene.

**Risk:** Low. The comment in the KDoc says "packing up to 6 UV pairs" which implies all 6
slots are meant to carry meaningful data. The 0-pad behaviour for slots 4–5 is not
documented. A future caller providing a 12-float UV array (e.g., for a hexagonal face)
would get correct behaviour, but there is no validation that the input is either 8 or 12
floats — any size ≥ 8 is silently accepted.

**Fix:** Add a comment noting that standard Prism faces supply 8 floats and slots 4–5 are
intentionally zero-padded (unused for quad fan triangulation). Consider tightening the
guard to `uv.size == 8 || uv.size == 12`.

---

### COR-4 — MEDIUM: `Path()` composable with `PerFace` material silently gets no UVs and no faceType

**File:** `isometric-shader/.../IsometricMaterialComposables.kt` lines 144–148

**Finding:**
`Path()` sets `IsoColor.WHITE` when `material is IsometricMaterial.PerFace`, matching the
`Shape()` composable's behaviour. However, `PathNode` has no `uvProvider` property — raw
2D paths cannot generate per-vertex UVs because they have no 3D dimensional extents.
The `PathNode.renderTo` emits a `RenderCommand` with `uvCoords = null` and `faceType = null`.

In `uploadUvCoordsBuffer`, a null `uvCoords` causes the default quad UVs
`(0,0)(1,0)(1,1)(0,1)(0,0)(0,0)` to be emitted. The per-face material resolution in
`resolveAtlasRegion` calls `m.resolve(face)` with `face = null`, falling back to
`m.default`. So the path renders with the default texture and quad UVs — which may or
may not be the user's intent.

**Risk:** A user calling `Path(path = myPath, material = perFace { top = ...; sides = ... })`
will always get `m.default` rendered with identity UV mapping, silently ignoring the
per-face assignment. There is no compile-time or runtime warning.

**Fix:** Add a KDoc note to the `Path()` composable stating that `IsometricMaterial.PerFace`
and `IsometricMaterial.Textured` always use `material.default` for raw `Path` nodes
since face identification requires a 3D `Prism` geometry. Alternatively, add a runtime
`check` or `Log.w` when `PerFace` is used with a `Path`.

---

### COR-5 — LOW: Noise dots in `TextureAssets` use a non-anti-aliased `Paint`

**File:** `app/.../sample/TextureAssets.kt` lines 261–266 and 290–295

**Finding:**
`Paint()` constructed with default arguments does not enable anti-aliasing
(`isAntiAlias = false` by default on Android). The `drawCircle` calls for grass blades
and gravel flecks produce aliased 3-pixel circles. On a 64×64 source bitmap displayed
through a BitmapShader with the isometric projection transform (which scales down), the
aliasing will be blurred by bilinear or trilinear filtering in the shader. In practice
this is not visible as a rendering error, but the intent (simulating organic noise) is
better served with anti-aliased dots.

**Risk:** Cosmetic only. No functional correctness issue.

**Fix:** Add `paint.isAntiAlias = true` before the `drawCircle` calls, or share a single
anti-aliased Paint across both functions.

---

### COR-6 — LOW: `TextureAssets` bitmap ownership is undocumented

**File:** `app/.../sample/TextureAssets.kt` lines 242–245

**Finding:**
`TextureAssets.grassTop` and `dirtSide` are `lazy val` properties on an `internal object`.
They are allocated on first access and cached for the process lifetime. If any caller
calls `grassTop.recycle()`, the next call to `texturedBitmap(TextureAssets.grassTop)`
will wrap a recycled `Bitmap` reference. The `GpuTextureStore.uploadBitmap` call will
then crash with `RuntimeException: Canvas: trying to use a recycled bitmap`.

The sample code does not recycle either bitmap, so there is no actual bug. But the
ownership contract (the object owns the bitmaps; callers must not recycle them) is not
stated anywhere.

**Risk:** Low. Risk becomes real if the sample is extended with a configuration-change
restart path that tries to free bitmaps.

**Fix:** Add a KDoc comment to `TextureAssets` stating "Callers must not call
`Bitmap.recycle()` on these instances; the object retains ownership for the process
lifetime."

---

### COR-7 — LOW: `uploadTexIndexBuffer` passes full-capacity ByteBuffer to `writeBuffer` (pre-existing, inconsistency with new code)

**File:** `isometric-webgpu/.../texture/GpuTextureManager.kt` line 210

**Finding:**
`uploadTexIndexBuffer` calls:

```kotlin
ctx.queue.writeBuffer(texIndexBuf.gpuBuffer!!, 0L, texIndexBuf.cpuBuffer!!)
```

`texIndexBuf.cpuBuffer` is allocated at `2 × faceCount × 4` bytes (double capacity).
After `SceneDataPacker.packTexIndicesInto` fills only the first `faceCount × 4` bytes,
the buffer position is not reset and the limit is at full capacity. The native
`writeBuffer` will read the ByteBuffer from `position` to `limit`, uploading up to
`2 × faceCount × 4` bytes — double the needed data. The extra bytes are stale from
the previous frame (or zeroed if newly allocated), and since the GPU only reads
`sceneTexIndices[key.originalIndex]` for indices 0 through `faceCount-1`, the extra
bytes are never accessed. No rendering error.

By contrast, the new `uploadUvCoordsBuffer` (and the existing `uploadUvRegionBuffer`)
correctly call `buf.rewind()` + `buf.limit(requiredBytes)` before `writeBuffer`.

**Risk:** Wastes up to `faceCount × 4` bytes of GPU upload bandwidth per frame on the
texIndex buffer. Pre-existing issue; not introduced by this slice.

**Fix:** Apply the same `buf.rewind()` + `buf.limit()` + `SceneDataPacker.packTexIndicesInto()`
+ `buf.rewind()` pattern used by `uploadUvRegionBuffer` to `uploadTexIndexBuffer`. Or use
a `ByteBuffer.slice()` overload that restricts the view to exactly `faceCount × 4` bytes.

---

### COR-8 — NIT: White clear color with alpha=1 makes transparent fragments opaque white

**File:** `isometric-webgpu/.../WebGpuSceneRenderer.kt` line 649

**Finding:**
The clear color was changed from `GPUColor(0.0, 0.0, 0.0, 0.0)` (transparent black) to
`GPUColor(1.0, 1.0, 1.0, 1.0)` (opaque white). The rationale is that textured faces use
`IsoColor.WHITE` as the base color, and the white clear color avoids a visible black
border around the isometric canvas when it composites over the white Compose background.

The side-effect is that any screen-space pixel not covered by any rendered face will now
be opaque white instead of transparent. For the Full WebGPU path where the GPU texture
is composited over the Android view, an opaque white clear means the view background
cannot show through the uncovered area. If the `Surface` background color ever changes
from white (e.g., dark mode), the clear color will not match and visible corners will
appear.

**Risk:** Cosmetic. The sample uses `MaterialTheme.colors.background` as the Surface color,
which is white in the default theme. In dark mode this mismatch will be visible.

**Fix:** Pass the clear color as a parameter derived from the Compose theme's background
color, or keep alpha=0 and address the base-color tinting by applying the white tint
only where `IsoColor.WHITE` is used (in the shader or in the material resolution path).
For the demo, the current approach is acceptable.

---

## Key Areas Verified Correct

### 1. faceType propagation — complete across all three code paths

`faceType` is set on `RenderCommand` by `ShapeNode.renderTo()` at
`IsometricNode.kt:290`:
```kotlin
faceType = if (isPrism) PrismFace.fromPathIndex(index) else null
```

It flows correctly through all three pipelines:
- **Canvas (`rebuild`):** `SceneCache.rebuild` → `engine.add(faceType = command.faceType)` → `SceneGraph.add` → `SceneItem.faceType` → `projectScene` builds `RenderCommand(faceType = transformedItem.item.faceType)`. Added in commit `ea14787`.
- **Canvas + GPU sort (`rebuildAsync`):** Same `engine.add()` path, same propagation. Added in commit `184c0f1`.
- **Full WebGPU (`rebuildForGpu`):** Bypasses `engine.add()` entirely; `RenderCommand` objects from `renderTo()` go directly into `PreparedScene`, so `faceType` is preserved with no additional propagation needed. The `GpuTextureManager.resolveAtlasRegion` reads `cmd.faceType` directly.

No other code paths create `RenderCommand` objects with a non-null `material` without
also carrying the correct `faceType` (null for non-Prism shapes is correct behaviour).

### 2. RGBA8Unorm — correct format for Bitmap.copyPixelsToBuffer

`Bitmap.copyPixelsToBuffer` on Android always writes pixels as R, G, B, A bytes
regardless of the native ARGB_8888 storage order. This is documented in the Android
SDK: "The pixel layout for ARGB_8888 bitmaps when using `copyPixelsToBuffer` is RGBA."
`TextureFormat.RGBA8Unorm` is therefore the correct GPU format. The previous
`BGRA8Unorm` was incorrect — it would have caused R and B channel swapping, producing
a blue tint on green textures and vice versa. The fix in commit `c730679` is correct.

The fallback checkerboard texture is also fixed: magenta in RGBA is `(0xFF, 0x00, 0xFF,
0xFF)` which the code writes correctly for `RGBA8Unorm`.

### 3. Per-vertex UV packing — correct for quad faces

UvGenerator returns `[u0,v0, u1,v1, u2,v2, u3,v3]` (8 floats, 4 UV pairs).
`uploadUvCoordsBuffer` packs these as:
- vec4[0]: `(u0, v0, u1, v1)` → `uvPack0.xy = uv0`, `uvPack0.zw = uv1`
- vec4[1]: `(u2, v2, u3, v3)` → `uvPack1.xy = uv2`, `uvPack1.zw = uv3`
- vec4[2]: `(0, 0, 0, 0)`     → unused for quad fan

The emit shader reads:
```wgsl
let uv0 = vec2<f32>(uvPack0.x, uvPack0.y) * uvSc + uvOff;  // vertex 0
let uv1 = vec2<f32>(uvPack0.z, uvPack0.w) * uvSc + uvOff;  // vertex 1
let uv2 = vec2<f32>(uvPack1.x, uvPack1.y) * uvSc + uvOff;  // vertex 2
let uv3 = vec2<f32>(uvPack1.z, uvPack1.w) * uvSc + uvOff;  // vertex 3
```

Triangle 0 uses vertices `(s0, s1, s2)` → UVs `(uv0, uv1, uv2)`.
Triangle 1 uses vertices `(s0, s2, s3)` → UVs `(uv0, uv2, uv3)`.

This is a correct fan triangulation of the quad `(s0, s1, s2, s3)`. UV vertex ordering
matches the screen-space vertex ordering from `TransformCullLightShader` (`v01`, `v23`).

### 4. Random(seed) determinism

`kotlin.random.Random(seed)` constructs a seeded Xorshift128+ generator. On Android the
implementation is the Kotlin stdlib's pure-Kotlin `XorWowRandom` (not delegating to
`java.util.Random`). The seed directly initialises the state registers; given the same
seed the sequence is always identical across JVM restarts, devices, and API levels. The
seeds 42 and 99 used in `TextureAssets` will produce the same bitmap on every device
restart. This is verified correct.

### 5. Buffer size arithmetic — no off-by-one

`uploadUvCoordsBuffer`:
- `entryBytes = 48` (3 × vec4 × 4 bytes = 12 × 4 = 48). Correct.
- `ensureCapacity(faceCount, 48)` allocates at least `faceCount × 48` bytes on the GPU
  buffer and `faceCount × 48 × 2` bytes on the CPU staging buffer.
- `requiredBytes = faceCount × 48`.
- Loop writes exactly `12 floats × 4 bytes = 48 bytes` per face, for `faceCount` faces.
  Total: `faceCount × 48 = requiredBytes`. No off-by-one.
- `buf.rewind()` + `buf.limit(requiredBytes)` restricts the ByteBuffer view before fill.
- Final `buf.rewind()` resets position to 0 (limit stays at `requiredBytes`).
- `writeBuffer(buf)` sends exactly `requiredBytes` bytes to the GPU.

### 6. GpuFullPipeline null-safety for uvCoordsGpuBuffer

`textureManager.uploadTextures(scene, faceCount)` is called on line 215, before the
`!!` dereference of `textureManager.uvCoordsGpuBuffer!!` on line 245.
`uploadTextures` internally calls `uploadUvCoordsBuffer`, which calls
`uvCoordsBuf.ensureCapacity(faceCount, 48)`. `ensureCapacity` always creates a non-null
`gpuBuffer` (it calls `device.createBuffer`). Therefore `uvCoordsGpuBuffer` is
guaranteed non-null at line 245 when `faceCount > 0`. The `faceCount == 0` early-return
in `uploadUvCoordsBuffer` means the buffer is only created on first non-empty frame,
which is consistent with `texIndexGpuBuffer` and `uvRegionGpuBuffer` (same pattern).
No NPE risk.
