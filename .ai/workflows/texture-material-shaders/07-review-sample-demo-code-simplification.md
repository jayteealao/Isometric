---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: sample-demo
review-dimension: code-simplification
status: complete
stage-number: 7
created-at: "2026-04-13T09:00:00Z"
updated-at: "2026-04-13T09:00:00Z"
diff-base: 970b91e
diff-head: feat/texture
---

# Code-Simplification Review — `sample-demo` slice

**Scope:** `git diff 970b91e...feat/texture`

**Files reviewed:**
- `app/.../sample/TexturedDemoActivity.kt` (new)
- `app/.../sample/TextureAssets.kt` (new)
- `app/.../sample/WebGpuSampleActivity.kt` (pre-existing, for duplication comparison)
- `isometric-webgpu/.../texture/GpuTextureManager.kt` (additive change)
- `isometric-webgpu/.../shader/TriangulateEmitShader.kt` (WGSL change)

---

## 1. TogglePill duplication

### Finding: Acceptable for demo code as-is, but worth a one-line fix

`TogglePill` is defined identically in both `TexturedDemoActivity.kt` and
`WebGpuSampleActivity.kt`:

```kotlin
// Appears verbatim in both files — same signature, same Card/Text body
@Composable
private fun TogglePill(label: String, selected: Boolean, onClick: () -> Unit) { … }
```

Both files are `private`-scoped (file-local), so the duplication is invisible to the
library surface. This is acceptable for isolated demo activities. However, it is a
single-point-of-failure for the pill's visual style: if the selected colour or padding
changes, both copies must be updated in sync.

**Recommendation (LOW):** Extract `TogglePill` to a `SampleUiComponents.kt` in the same
`sample` package, declared `internal`. Both activities can then import it without any
public API surface. This is a two-minute mechanical move.

```kotlin
// app/.../sample/SampleUiComponents.kt
@Composable
internal fun TogglePill(label: String, selected: Boolean, onClick: () -> Unit) { … }
```

There is no need for a third-party composable library or a shared module — the
activities are in the same Gradle module `:app`.

---

## 2. Dead code and unnecessary complexity in new files

### TexturedDemoActivity.kt

**Wildcard imports (NIT):** The file uses star-imports for both `androidx.compose.foundation.layout.*`
and `androidx.compose.material.*`. The other activities in the module use explicit imports.
This is a style inconsistency, not a functional problem.

**`tileMaterial` is a `remember` with no key (CORRECT):** The material is stable across
recompositions because `TextureAssets.grassTop`/`dirtSide` are `lazy` object fields —
this is fine. No action needed.

**`TexturedPrismGridScene` parameter type spells out the full qualifier for `IsometricMaterial` (NIT):**

```kotlin
private fun TexturedPrismGridScene(
    renderMode: RenderMode,
    tileMaterial: io.github.jayteealao.isometric.shader.IsometricMaterial,  // full qualifier inline
)
```

`IsometricMaterial` is already transitively in scope through `import io.github.jayteealao.isometric.shader.Shape as MaterialShape`. Adding a regular `import io.github.jayteealao.isometric.shader.IsometricMaterial` would clean this up.

**No dead code.** All three composables (`TexturedDemoScreen`, `TexturedPrismGridScene`,
`TogglePill`) are wired end-to-end. The `FLAG_KEEP_SCREEN_ON` flag is correct and
consistent with `WebGpuSampleActivity`.

### TextureAssets.kt

**Clean and minimal.** The `object` + `lazy` pattern is the right approach for
one-time procedural bitmap generation. No state leakage — the `Paint` and `Canvas`
are locals to each builder function, so GC pressure from large intermediates is bounded.

**`Paint()` is allocated without anti-alias enabled (CORRECT for pixel art):** The
64×64 noise texture intentionally uses aliased circles for a retro/pixelated look.
This is fine; no `Paint.isAntiAlias = true` needed.

**The two `Random` seeds are different (42 vs 99) (CORRECT):** Grass and dirt have
independent noise seeds. Determinism is preserved.

**No dead code.** Both `grassTop` and `dirtSide` are consumed by `TexturedDemoActivity`.

---

## 3. `uploadUvCoordsBuffer` — could it be simplified?

### Current implementation

```kotlin
private fun uploadUvCoordsBuffer(scene: PreparedScene, faceCount: Int) {
    if (faceCount == 0) return
    val entryBytes = 48 // 3 × vec4<f32> = 12 floats × 4 bytes
    uvCoordsBuf.ensureCapacity(faceCount, entryBytes)
    val requiredBytes = faceCount * entryBytes
    val buf = uvCoordsBuf.cpuBuffer!!
    buf.rewind()
    buf.limit(requiredBytes)
    for (i in 0 until faceCount) {
        val uv = scene.commands[i].uvCoords
        if (uv != null && uv.size >= 8) {
            for (j in 0 until 12) {
                buf.putFloat(if (j < uv.size) uv[j] else 0f)
            }
        } else {
            // Default quad UVs: (0,0)(1,0)(1,1)(0,1)(0,0)(0,0)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(1f); buf.putFloat(0f)
            buf.putFloat(1f); buf.putFloat(1f); buf.putFloat(0f); buf.putFloat(1f)
            buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f)
        }
    }
    buf.rewind()
    ctx.queue.writeBuffer(uvCoordsBuf.gpuBuffer!!, 0L, buf)
}
```

### Structural pattern

The method is structurally identical to `uploadUvRegionBuffer` (same
`ensureCapacity`/`rewind`/`limit`/`rewind`/`writeBuffer` skeleton). This pairing is
correct — there is no obvious way to collapse the two methods without losing the
distinct per-face logic.

### The `uv.size >= 8` guard is sound but the fallback writes are misleading

`UvGenerator.forPrismFace` always returns exactly 8 floats (4 pairs for a quad).
The guard `uv != null && uv.size >= 8` therefore always takes the `true` branch for
every real Prism face produced by the existing UV pipeline. The `else` branch (12
hardcoded floats for a default quad) is unreachable in the current codebase — it
exists as a safety net for hypothetical non-Prism shapes whose UV arrays have fewer
than 8 elements.

This is not wrong, but the inner loop inside the `true` branch:

```kotlin
for (j in 0 until 12) {
    buf.putFloat(if (j < uv.size) uv[j] else 0f)
}
```

…always evaluates `j < uv.size` 12 times and always pads slots 8–11 with `0f`.
Since `uv.size` is always exactly 8, the last 4 floats are always zero. This is
correct (the GPU shader only reads up to `vertexCount` UV pairs, which is ≤ 4 for a
Prism), but could be written more explicitly:

```kotlin
// Simpler: write the 8 known UV floats, then pad 4 zeroes
uv.forEach { buf.putFloat(it) }           // 8 floats from UvGenerator
repeat(4) { buf.putFloat(0f) }            // 4 padding floats (slots 4–5 unused)
```

This removes the conditional branch from the hot path.

**Recommendation (LOW):** Replace the conditional inner loop with two explicit writes.
This makes the intent ("UvGenerator gives exactly 8 floats; pad remaining 4") obvious
and avoids the repeated `j < uv.size` test.

### The `entryBytes = 48` magic number

48 is derived from `3 × vec4<f32> = 3 × 16 = 48`. The comment states this, which is
sufficient. However, it could be expressed as a named constant alongside
`TriangulateEmitShader.MAX_VERTICES_PER_FACE` (which is 12) to make the GPU–CPU
contract explicit. Non-blocking.

---

## 4. WGSL UV reading code — clean and minimal?

### Pre-existing concern resolved

The previous shader hardcoded quad UVs as constants `(0,0)(1,0)(1,1)(0,1)` with a
placeholder `uvMid = (0.5, 0.5)` for pentagon/hexagon triangles 2–3. This was
incorrect for any non-standard UV layout. The new slice replaces all of this with
a buffer read.

### New WGSL UV block

```wgsl
let uvBase = key.originalIndex * 3u;
let uvPack0 = sceneUvCoords[uvBase + 0u]; // (u0,v0,u1,v1)
let uvPack1 = sceneUvCoords[uvBase + 1u]; // (u2,v2,u3,v3)
let uvPack2 = sceneUvCoords[uvBase + 2u]; // (u4,v4,u5,v5)
let uv0 = vec2<f32>(uvPack0.x, uvPack0.y) * uvSc + uvOff;
let uv1 = vec2<f32>(uvPack0.z, uvPack0.w) * uvSc + uvOff;
let uv2 = vec2<f32>(uvPack1.x, uvPack1.y) * uvSc + uvOff;
let uv3 = vec2<f32>(uvPack1.z, uvPack1.w) * uvSc + uvOff;
let uv4 = vec2<f32>(uvPack2.x, uvPack2.y) * uvSc + uvOff;
let uv5 = vec2<f32>(uvPack2.z, uvPack2.w) * uvSc + uvOff;
```

**Assessment: clean and correct.**

- Three sequential buffer reads are coalesced from the same cacheline (3 × vec4 = 48 bytes, naturally 16-byte aligned).
- The atlas transform (`* uvSc + uvOff`) is applied once per UV pair, not inside `writeVertex`, which keeps `writeVertex` generic.
- The naming (`uvPack0/1/2`, then `uv0..uv5`) mirrors the CPU-side packing format and is easy to map back to `uploadUvCoordsBuffer`.
- No dynamic indexing in WGSL — all six UV pairs are resolved as named locals. This is consistent with the project's documented policy (see `## Why fully unrolled` in the KDoc) and avoids driver-sensitive array accesses.

### Stale KDoc section

The KDoc block on `TriangulateEmitShader` still contains the sentence:

```
Standard Prism quad UVs are emitted as constants: `(0,0)(1,0)(1,1)(0,1)` for the
4-vertex fan. This matches the CPU-side uv-generation output for all standard Prism
faces. Custom UV transforms require a GPU-side UV buffer in a future slice.
```

This is now incorrect — UVs are read from `sceneUvCoords`, not emitted as constants,
and the "future slice" has been implemented.

**Recommendation (MUST):** Delete the stale `## UV coordinates` section from the KDoc
(lines 28–32 in the current file). The binding table in `## Bindings` already documents
`@binding(6)`, so no replacement text is needed.

---

## Summary

| ID   | Severity | File                          | Finding                                                       |
|------|----------|-------------------------------|---------------------------------------------------------------|
| S-1  | MUST     | `TriangulateEmitShader.kt`    | Delete stale `## UV coordinates` KDoc section (now incorrect) |
| S-2  | LOW      | Both sample activities        | Extract shared `TogglePill` to `SampleUiComponents.kt`        |
| S-3  | LOW      | `GpuTextureManager.kt`        | Replace conditional inner loop in `uploadUvCoordsBuffer` with explicit 8+4 writes |
| S-4  | NIT      | `TexturedDemoActivity.kt`     | Replace wildcard imports with explicit imports                 |
| S-5  | NIT      | `TexturedDemoActivity.kt`     | Add `import IsometricMaterial`; remove inline full qualifier   |

No dead code was found in any new file. The WGSL UV reading path is minimal and
correct. The `uploadUvCoordsBuffer` structural pattern is consistent with
`uploadUvRegionBuffer` and appropriate for the GPU staging buffer pattern used
throughout the pipeline.
