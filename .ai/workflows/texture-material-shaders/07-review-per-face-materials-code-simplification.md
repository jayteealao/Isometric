---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: per-face-materials
review-command: code-simplification
status: complete
updated-at: "2026-04-13T01:30:00Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-medium: 3
metric-findings-low: 3
result: issues-found
tags: [simplification, per-face, material]
refs:
  review-master: 07-review-per-face-materials.md
---

# Code Simplification Review — per-face-materials slice

## Scope

Commit `fde7cc7` on branch `feat/texture`. Files reviewed:

- `isometric-shader/src/main/kotlin/.../shader/IsometricMaterial.kt`
- `isometric-shader/src/main/kotlin/.../shader/render/TexturedCanvasDrawHook.kt`
- `isometric-shader/src/main/kotlin/.../shader/IsometricMaterialComposables.kt`
- `isometric-webgpu/src/main/kotlin/.../pipeline/GpuFullPipeline.kt`
- `isometric-webgpu/src/main/kotlin/.../texture/TextureAtlasManager.kt`
- `isometric-webgpu/src/main/kotlin/.../pipeline/SceneDataPacker.kt`
- `isometric-webgpu/src/main/kotlin/.../pipeline/GpuTriangulateEmitPipeline.kt`
- `isometric-webgpu/src/main/kotlin/.../shader/TriangulateEmitShader.kt`
- `isometric-core/src/main/kotlin/.../RenderCommand.kt`

Supporting context read (not changed in this slice):

- `TransformCullLightShader.kt`

---

## Findings Table

| ID | Severity | File | Issue |
|----|----------|------|-------|
| PF-CS-1 | HIGH | `RenderCommand.kt:48-49`, `SceneDataPacker.kt:160-165` | `uvOffset`/`uvScale` fields on `RenderCommand` are never set anywhere — they are always null; the FaceData fields (offsets 132–147) in `SceneDataPacker.packInto` always write identity defaults |
| PF-CS-2 | HIGH | `GpuFullPipeline.kt:408,441` | `maxOf(faceCount, faceCount * 2)` is always equal to `faceCount * 2`; `maxOf` is a no-op here |
| PF-CS-3 | MED | `GpuFullPipeline.kt:379-397` | `collectTextureSources` accepts a `faceType` parameter that is never read inside the function body |
| PF-CS-4 | MED | `GpuFullPipeline.kt:392` | Smart-cast already established by the `if (m.default is IsometricMaterial.Textured)` guard, but the body still has a redundant explicit cast `(m.default as IsometricMaterial.Textured)` |
| PF-CS-5 | MED | `GpuFullPipeline.kt:402-428`, `GpuFullPipeline.kt:434-481` | `uploadTexIndexBuffer` and `uploadUvRegionBuffer` duplicate the same GPU + CPU buffer growth pattern (four nearly identical blocks of ~10 lines each) |
| PF-CS-6 | LOW | `IsometricMaterial.kt:162-165` | `@Deprecated(ERROR)` on the getter of `sides` is unconventional and will still show up as a deprecation warning for the write path in IDEs; `private set` plus a plain `error()` in the getter is the idiomatic pattern for write-only DSL properties |
| PF-CS-7 | LOW | `TextureAtlasManager.kt:183-191` | `while (atlasW <= maxAtlasSizePx)` loop in `computeShelfLayout` is overengineered for the current use-case: with sorted-by-height input and 2px padding the first `tryPack` attempt almost never fails; a single attempt followed by a power-of-two ceil-height is sufficient |
| PF-CS-8 | LOW | `TriangulateEmitShader.kt:282,290` | `uvMid` (computed as `vec2(0.5, 0.5) * uvSc + uvOff`) is declared and computed twice in two separate `if (triCount >= 3u)` / `if (triCount >= 4u)` blocks; it could be hoisted above both or collapsed into a single `let` |

---

## Detailed Findings

### PF-CS-1 — `RenderCommand.uvOffset`/`uvScale` are never set; FaceData fields are always identity [HIGH]

**Location:** `RenderCommand.kt:48-49`, `SceneDataPacker.kt:159-165`, `TransformCullLightShader.kt:105-108`

**Evidence:**

`RenderCommand` declares two nullable atlas-UV fields:

```kotlin
val uvOffset: FloatArray? = null,
val uvScale: FloatArray? = null,
```

A search across all construction sites — `IsometricNode.kt`, `IsometricEngine.kt`, test files —
shows these fields are never passed a non-null value. Every `RenderCommand` instance has
`uvOffset == null` and `uvScale == null`.

`SceneDataPacker.packInto` reads them:

```kotlin
buffer.putFloat(cmd.uvOffset?.get(0) ?: 0f)
buffer.putFloat(cmd.uvOffset?.get(1) ?: 0f)
buffer.putFloat(cmd.uvScale?.get(0) ?: 1f)
buffer.putFloat(cmd.uvScale?.get(1) ?: 1f)
```

Because the fields are always null, these four writes always produce `0, 0, 1, 1` — an identity
transform that is never consumed. The corresponding `FaceData` struct fields (`uvOffsetU`,
`uvOffsetV`, `uvScaleU`, `uvScaleV` at offsets 132–147) are declared in both
`SceneDataPacker` (layout comment) and `TransformCullLightShader.WGSL` (struct body), but
**no WGSL code in either shader ever reads these fields**. The M5 shader reads UV data from the
separate compact `sceneUvRegions` buffer (binding 5), not from `FaceData`.

**Impact:** 16 bytes per face in the scene buffer are wasted on every frame upload. The public
`RenderCommand` API carries two fields that have no effect. The `SceneDataPacker` layout comment
and the `TransformCullLightShader` WGSL struct both declare dead fields that will mislead future
contributors.

There are two valid paths forward:

*Option A — Remove the fields from `RenderCommand` and `FaceData`:* Drop `uvOffset`/`uvScale`
from `RenderCommand`, remove the four `buffer.putFloat` lines in `SceneDataPacker.packInto`,
shrink the WGSL struct to 144 bytes, and update `SceneDataLayout.FACE_DATA_BYTES` to `144`.
This is the clean choice; atlas UV data lives only in the compact UV region buffer.

*Option B — Use the fields:* If a future slice intends `RenderCommand.uvOffset`/`uvScale` to
carry atlas UV data set by the WebGPU pipeline before packing (rather than after), define when
and where the fields are populated and ensure `SceneDataPacker.packInto` reads live values.
This would require the atlas lookup to happen before packing, which contradicts the current
two-pass design. Option A is preferred.

---

### PF-CS-2 — `maxOf(faceCount, faceCount * 2)` is always `faceCount * 2` [HIGH]

**Location:** `GpuFullPipeline.kt:408`, `GpuFullPipeline.kt:441`

**Evidence (both occurrences are identical):**

```kotlin
val newCapacity = maxOf(faceCount, faceCount * 2)
```

`faceCount * 2 ≥ faceCount` for all non-negative `faceCount`, so `maxOf` always returns
`faceCount * 2`. The `maxOf` call provides no safety benefit and obscures the intent.

Same pattern appears in the CPU staging buffer growth:

```kotlin
val newSize = maxOf(requiredBytes, requiredBytes * 2)
```

All four occurrences always resolve to the doubled value.

**Recommended fix:**

```kotlin
val newCapacity = faceCount * 2
// ... and:
val newSize = requiredBytes * 2
```

This is purely a clarity fix. The current code is correct but misleading — a reader might wonder
what the `maxOf` is guarding against (negative values? overflow?), and there is no guard.

---

### PF-CS-3 — `collectTextureSources` accepts `faceType` but never uses it [MED]

**Location:** `GpuFullPipeline.kt:379-397`

**Evidence:**

```kotlin
private fun collectTextureSources(
    material: io.github.jayteealao.isometric.MaterialData?,
    faceType: io.github.jayteealao.isometric.shapes.PrismFace?,  // ← never read
    out: MutableSet<TextureSource>,
) {
    when (val m = material) {
        is IsometricMaterial.Textured -> out.add(m.source)
        is IsometricMaterial.PerFace -> {
            for (sub in m.faceMap.values) {        // iterates ALL faces
                if (sub is IsometricMaterial.Textured) out.add(sub.source)
            }
            if (m.default is IsometricMaterial.Textured) {
                out.add((m.default as IsometricMaterial.Textured).source)
            }
        }
        else -> {}
    }
}
```

The function iterates all `faceMap.values` regardless of `faceType`. This is correct for atlas
building (all face textures need to be present in the atlas) but `faceType` is still passed and
silently ignored. This misleads readers into believing the collection is filtered to a specific
face.

**Recommended fix:** Remove the `faceType` parameter:

```kotlin
private fun collectTextureSources(
    material: io.github.jayteealao.isometric.MaterialData?,
    out: MutableSet<TextureSource>,
) { ... }
```

Update the call site at line 326:

```kotlin
collectTextureSources(cmd.material, textureSources)
```

The `cmd.faceType` argument passed there is itself unused and can be dropped.

---

### PF-CS-4 — Redundant explicit cast after smart-cast guard [MED]

**Location:** `GpuFullPipeline.kt:391-393`

**Evidence:**

```kotlin
if (m.default is IsometricMaterial.Textured) {
    out.add((m.default as IsometricMaterial.Textured).source)  // explicit cast redundant
}
```

The `is` check on line 391 provides a smart cast: inside the `if` block, `m.default` is already
`IsometricMaterial.Textured`. The explicit `as` cast on line 392 is unnecessary.

**Recommended fix:**

```kotlin
val d = m.default
if (d is IsometricMaterial.Textured) out.add(d.source)
```

(`m.default` is a property access, not a local variable, so Kotlin may not smart-cast it
directly if the property is `open` or the receiver is mutable; introducing a local `val d`
ensures the smart cast is stable in all cases and removes the `as`.)

---

### PF-CS-5 — `uploadTexIndexBuffer` and `uploadUvRegionBuffer` duplicate buffer-growth boilerplate [MED]

**Location:** `GpuFullPipeline.kt:402-428`, `GpuFullPipeline.kt:434-481`

**Evidence:** Both functions share the same four-part structure:

1. Early return if `faceCount == 0`
2. GPU buffer: if `faceCount > capacity`, close old, compute `newCapacity = maxOf(faceCount, faceCount * 2)`, create new `GPUBuffer`, update capacity field
3. CPU staging buffer: if `null` or too small, compute `newSize = maxOf(requiredBytes, requiredBytes * 2)`, allocate new `ByteBuffer.allocateDirect`
4. Pack data into CPU buffer and call `queue.writeBuffer`

Steps 2 and 3 are identical in structure across both functions, differing only in field names
(`texIndexGpuBuffer`/`uvRegionGpuBuffer`, `texIndexCpuBuffer`/`uvRegionCpuBuffer`,
`texIndexCapacity`/`uvRegionCapacity`) and entry size (`4` vs `16` bytes).

**Recommended fix:** Extract a private helper or a small value class:

```kotlin
private class GrowableGpuBuffer(
    private val ctx: GpuContext,
    private val usage: Int,
    private val bytesPerEntry: Int,
    private val label: String,
) {
    var gpuBuffer: GPUBuffer? = null; private set
    var cpuBuffer: ByteBuffer? = null; private set
    private var capacity = 0

    fun ensureCapacity(entryCount: Int) {
        if (entryCount > capacity) {
            gpuBuffer?.close()
            val newCapacity = entryCount * 2
            gpuBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = usage,
                    size = (newCapacity * bytesPerEntry).toLong(),
                )
            ).also { it.setLabel(label) }
            capacity = newCapacity
        }
        val required = entryCount * bytesPerEntry
        if (cpuBuffer == null || cpuBuffer!!.capacity() < required) {
            cpuBuffer = ByteBuffer.allocateDirect(required * 2).order(ByteOrder.nativeOrder())
        }
    }

    fun close() { gpuBuffer?.close(); gpuBuffer = null; capacity = 0 }
}
```

Both `uploadTexIndexBuffer` and `uploadUvRegionBuffer` collapse to `ensure` + `pack` + `writeBuffer`,
removing ~30 lines of duplicated boilerplate. If the helper is added, the `maxOf(n, n*2)` bug
(PF-CS-2) is also fixed in one place.

---

### PF-CS-6 — `@Deprecated(ERROR)` on the getter of `sides` is the wrong tool for a write-only property [LOW]

**Location:** `IsometricMaterial.kt:162-165`

**Evidence:**

```kotlin
var sides: IsometricMaterial?
    @Deprecated("sides is write-only", level = DeprecationLevel.ERROR)
    get() = error("sides is write-only")
    set(value) { front = value; back = value; left = value; right = value }
```

**Issue 1 — Deprecation is for API migration, not enforcement:** `@Deprecated(ERROR)` is
designed to communicate "this API is going away — update your call sites." Using it to make a
getter non-callable is a repurposing of the annotation that will confuse IDE diagnostics and
readers; code that never calls `sides` will not see the deprecation, but the IDE will still
flag the getter as deprecated.

**Issue 2 — The annotation does not prevent compilation of the getter body:** The `error()`
throw is still the runtime enforcement; the annotation adds no additional safety.

**Idiomatic approach for write-only DSL properties:**

```kotlin
// Option A — mark getter private and throw from it (Kotlin convention for write-only):
var sides: IsometricMaterial?
    get() = error("sides is write-only; read front/back/left/right individually")
    set(value) { front = value; back = value; left = value; right = value }
```

A `private get()` would prevent external reads at compile time but would also prevent reading
`sides` internally, which is fine here because it is never read. If the goal is a compile-time
error for external readers, marking get `private` is the correct Kotlin tool. If the goal is
only a runtime guard with a meaningful message, a plain `error()` in a non-deprecated getter
is sufficient.

---

### PF-CS-7 — Two-pass while loop in `computeShelfLayout` is overengineered for the current use-case [LOW]

**Location:** `TextureAtlasManager.kt:175-192`

**Evidence:**

```kotlin
while (atlasW <= maxAtlasSizePx) {
    val result = tryPack(sizes, atlasW)
    if (result != null) return result
    atlasW *= 2
}
// Fallback: pack at max size, clamp height
return tryPack(sizes, maxAtlasSizePx)
    ?: ShelfLayout(maxAtlasSizePx, maxAtlasSizePx, sizes.map { Placement(0, 0) })
```

The `while` loop exists to double `atlasW` until the packing fits vertically. However, `atlasW`
starts at `nextPowerOfTwo(maxW + paddingPx)` where `maxW` is the widest texture, so it is
already at least as wide as the tallest texture is wide. With the input sorted by height
descending and a 2px gutter, the first call to `tryPack` only fails if the combined height of
all textures exceeds `maxAtlasSizePx` even at the current width.

For the typical use-case (a handful of Prism face textures each ≤ 512px), `tryPack` succeeds on
the first attempt with near certainty. The while loop adds complexity without a measurable benefit
in this domain. A single attempt with a power-of-two rounded height is all that is needed:

```kotlin
private fun computeShelfLayout(sizes: List<Pair<Int, Int>>): ShelfLayout {
    if (sizes.isEmpty()) return ShelfLayout(0, 0, emptyList())
    val maxW = sizes.maxOf { it.first }
    val atlasW = nextPowerOfTwo(maxOf(maxW + paddingPx, 512).coerceAtMost(maxAtlasSizePx))
    return tryPack(sizes, atlasW)
        ?: ShelfLayout(maxAtlasSizePx, maxAtlasSizePx, sizes.map { Placement(0, 0) })
}
```

If the initial width is insufficient, the caller can be expanded in a future slice when
incremental atlas updates are supported. The current complexity is premature generality.

---

### PF-CS-8 — `uvMid` is computed twice in adjacent `if` blocks in WGSL [LOW]

**Location:** `TriangulateEmitShader.kt:282`, `TriangulateEmitShader.kt:290`

**Evidence:**

```wgsl
// Triangle 2: (s0, s3, s4) — for 5+ vertex faces (pentagon)
if (triCount >= 3u) {
    let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
    writeVertex((base + 6u) * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
    writeVertex((base + 7u) * 9u, nx3, ny3, r, g, b, a, uvMid.x, uvMid.y, texIdx);
    writeVertex((base + 8u) * 9u, nx4, ny4, r, g, b, a, uvMid.x, uvMid.y, texIdx);
}

// Triangle 3: (s0, s4, s5) — for 6-vertex faces (hexagon)
if (triCount >= 4u) {
    let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
    writeVertex((base + 9u)  * 9u, nx0, ny0, r, g, b, a, uv00.x, uv00.y, texIdx);
    writeVertex((base + 10u) * 9u, nx4, ny4, r, g, b, a, uvMid.x, uvMid.y, texIdx);
    writeVertex((base + 11u) * 9u, nx5, ny5, r, g, b, a, uvMid.x, uvMid.y, texIdx);
}
```

`uvMid` is the same expression (`vec2<f32>(0.5, 0.5) * uvSc + uvOff`) in both blocks.
In the `triCount >= 4` branch the same value is recomputed. WGSL allows hoisting a `let`
outside the `if`:

```wgsl
// Hoist: uvMid is only needed for pentagon/hexagon (triCount ≥ 3)
let uvMid = vec2<f32>(0.5, 0.5) * uvSc + uvOff;
if (triCount >= 3u) { ... }  // uses uvMid
if (triCount >= 4u) { ... }  // uses uvMid
```

Modern GPU drivers constant-fold the computation, so there is no performance regression from
the current approach. This is a readability nit — the duplication suggests a copy-paste and
may cause the two `uvMid` expressions to diverge if either is edited in the future.

---

## Summary

| ID | Severity | Actionable | Recommendation |
|----|----------|------------|----------------|
| PF-CS-1 | HIGH | Yes | Remove `RenderCommand.uvOffset`/`uvScale` fields and the four dead `putFloat` calls in `SceneDataPacker.packInto`; shrink `FACE_DATA_BYTES` from 160 to 144 |
| PF-CS-2 | HIGH | Yes | Replace `maxOf(faceCount, faceCount * 2)` with `faceCount * 2` (four sites); `maxOf` is a no-op and misleads readers |
| PF-CS-3 | MED | Yes | Remove `faceType` parameter from `collectTextureSources` — it is never read |
| PF-CS-4 | MED | Yes | Remove redundant `as IsometricMaterial.Textured` cast inside the `is` guard; introduce a local `val d` to ensure stable smart-cast |
| PF-CS-5 | MED | Yes | Extract `GrowableGpuBuffer` helper (or equivalent) to collapse the duplicated GPU+CPU buffer growth boilerplate across `uploadTexIndexBuffer` and `uploadUvRegionBuffer` |
| PF-CS-6 | LOW | Yes | Replace `@Deprecated(ERROR)` getter with `private get()` or a plain `error()` — deprecation annotation is the wrong mechanism for a write-only DSL property |
| PF-CS-7 | LOW | Yes | Replace `while (atlasW <= maxAtlasSizePx)` retry loop with a single `tryPack` call; the loop is premature generality for the current use-case |
| PF-CS-8 | LOW | Yes | Hoist `uvMid` above the two `if (triCount >= 3u)` / `if (triCount >= 4u)` blocks in WGSL to eliminate the copy-paste duplication |

**PF-CS-1** is the most structurally significant: `RenderCommand` carries two dead public fields
that enlarge every data class instance, add 16 bytes per face to the GPU scene buffer on every
frame, and pollute the `FaceData` WGSL struct with fields never read by any shader. The fix
is a straightforward deletion once confirmed no future slice intends these fields to be populated
before `packInto` is called.

**PF-CS-2** is a correctness illusion: the `maxOf` appears to guard against underflow but
actually does nothing, which can cause future contributors to trust the guard is meaningful.

**PF-CS-3** and **PF-CS-4** are parameter and cast cleanup. **PF-CS-5** is the only change
that requires writing new code (the extraction helper), but it pays back in reduced duplication
across two existing upload functions. **PF-CS-6** through **PF-CS-8** are low-priority polish
with no functional impact.

No finding is a blocker for shipping this slice.
