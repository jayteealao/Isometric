---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: per-face-materials
status: complete
stage-number: 7
created-at: "2026-04-13T00:24:09Z"
updated-at: "2026-04-13T00:24:09Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, architecture, performance]
metric-commands-run: 5
metric-findings-total: 14
metric-findings-raw: 38
metric-findings-blocker: 0
metric-findings-high: 4
metric-findings-med: 4
metric-findings-low: 5
metric-findings-nit: 1
tags: [per-face, material, atlas, webgpu, canvas]
refs:
  index: 00-index.md
  slice-def: 03-slice-per-face-materials.md
  implement: 05-implement-per-face-materials.md
  verify: 06-verify-per-face-materials.md
  sub-reviews:
    - 07-review-per-face-materials-correctness.md
    - 07-review-per-face-materials-security.md
    - 07-review-per-face-materials-code-simplification.md
    - 07-review-per-face-materials-architecture.md
    - 07-review-per-face-materials-performance.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders per-face-materials"
---

# Review: per-face-materials

## Verdict

**Ship with caveats**

No blockers found. Four HIGH findings require fixing before handoff: (1) dead `uvOffset`/`uvScale`
fields on `RenderCommand` that waste 16 bytes per face and pollute the core layer with WebGPU
semantics, (2) atlas overflow fallback that silently corrupts rendering, (3) buffer loop not capped
to `faceCount` creating a potential `BufferOverflowException`, and (4) per-frame texture source
scan that runs unconditionally. All are fixable with targeted edits. The core per-face material
resolution logic, DSL, UV transform math, and WGSL struct alignment are all correct.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Logic & invariants | `correctness` | Issues (2 HIGH, 3 MED, 2 LOW, 1 NIT) |
| Vulnerabilities | `security` | Issues (2 HIGH, 2 MED, 2 LOW, 1 NIT) |
| Code quality | `code-simplification` | Issues (2 HIGH, 3 MED, 3 LOW) |
| Architecture | `architecture` | Issues (2 HIGH, 2 MED, 2 LOW, 1 NIT) |
| Performance | `performance` | Issues (1 HIGH, 3 MED, 4 LOW) |

## All Findings (Deduplicated)

| ID | Sev | Conf | Source | File:Line | Issue |
|----|-----|------|--------|-----------|-------|
| PF-1 | HIGH | High | COR-3, PF-CS-1, ARCH-01, ARCH-02, ARCH-06, PERF-4 | `RenderCommand.kt:47-49` | Dead `uvOffset`/`uvScale` fields + 16 dead bytes in FaceData |
| PF-2 | HIGH | High | COR-2, SEC-03 | `TextureAtlasManager.kt:191` | Atlas overflow fallback maps all textures to (0,0) |
| PF-3 | HIGH | Med | SEC-01, SEC-02 | `GpuFullPipeline.kt:460-480` | Buffer loop iterates all commands, not capped at faceCount |
| PF-4 | HIGH | Med | PERF-1, PERF-6 | `GpuFullPipeline.kt:340-350` | collectTextureSources + Set allocation runs every frame |
| PF-5 | MED | High | COR-1, ARCH-04 | `TransformCullLightShader.kt:30` | Stale KDoc says FaceData is 144 bytes |
| PF-6 | MED | High | PF-CS-2, ARCH-07, PERF-2 | `GpuFullPipeline.kt:408,441` | `maxOf(faceCount, faceCount*2)` is always faceCount*2 |
| PF-7 | MED | Med | PF-CS-5 | `GpuFullPipeline.kt:402-481` | uploadTexIndexBuffer / uploadUvRegionBuffer duplicate buffer growth |
| PF-8 | MED | Low | ARCH-03 | `GpuFullPipeline.kt` | GpuFullPipeline god-object — extract GpuTextureManager |
| PF-9 | LOW | Med | COR-6, PF-CS-3 | `GpuFullPipeline.kt:379` | Dead `faceType` parameter on collectTextureSources |
| PF-10 | LOW | Med | COR-4 | `SceneDataPacker.kt:154` | FaceData.textureIndex packed but never read by any shader |
| PF-11 | LOW | Low | COR-5 | `TriangulateEmitShader.kt:282-293` | UV degenerate for 5/6-vertex faces (latent, Prism uses 4-vertex only) |
| PF-12 | LOW | Low | ARCH-05 | `IsometricMaterial.kt` | PerFace Prism-only constraint undocumented in API |
| PF-13 | LOW | Low | PF-CS-8 | `TriangulateEmitShader.kt:282,290` | uvMid computed twice in adjacent WGSL blocks |
| PF-14 | NIT | Low | COR-8 | `TextureAtlasManager.kt:180` | nextPowerOfTwo(maxW + paddingPx) semantically off |

**Total:** BLOCKER: 0 | HIGH: 4 | MED: 4 | LOW: 5 | NIT: 1
*(After dedup: 14 findings merged from 38 raw findings across 5 commands)*

## Findings (Detailed)

### PF-1: Dead uvOffset/uvScale on RenderCommand [HIGH]

**Location:** `isometric-core/.../RenderCommand.kt:47-49`, `SceneDataPacker.kt:160-165`, `TransformCullLightShader.kt:105-108`
**Source:** correctness + code-simplification + architecture + performance

**Evidence:**
```kotlin
val uvOffset: FloatArray? = null,  // never set by any code path
val uvScale: FloatArray? = null,   // never set by any code path
```

**Issue:** `uvOffset`/`uvScale` are always null. SceneDataPacker writes identity defaults (0,0,1,1)
into 16 bytes of each FaceData struct that no shader reads. The M5 emit shader reads UV data from
the separate `sceneUvRegions` buffer (binding 5). Additionally, placing WebGPU atlas semantics in
`isometric-core`'s RenderCommand violates the module boundary (core should be backend-agnostic).

**Fix:** Remove `uvOffset`/`uvScale` from RenderCommand. Strip the 4 f32 fields from FaceData
(revert to 144 bytes). Update WGSL structs in TransformCullLightShader and padding in SceneDataPacker.
The sceneUvRegions buffer (binding 5) remains the sole UV data path.

**Severity:** HIGH | **Confidence:** High

---

### PF-2: Atlas overflow fallback silently corrupts rendering [HIGH]

**Location:** `isometric-webgpu/.../texture/TextureAtlasManager.kt:191`
**Source:** correctness + security

**Evidence:**
```kotlin
return tryPack(sizes, maxAtlasSizePx)
    ?: ShelfLayout(maxAtlasSizePx, maxAtlasSizePx, sizes.map { Placement(0, 0) })
```

**Issue:** When textures exceed 2048x2048, the fallback places all at (0,0). All bitmaps overwrite
each other at the origin. `rebuild()` returns `true` (success), binding the corrupt atlas. No error
logged.

**Fix:** Log an error, return `false` from `rebuild()` on overflow so the caller falls back to
the fallback checkerboard texture.

**Severity:** HIGH | **Confidence:** High

---

### PF-3: Buffer loop not capped at faceCount [HIGH]

**Location:** `isometric-webgpu/.../pipeline/GpuFullPipeline.kt:460-480`
**Source:** security

**Evidence:**
```kotlin
// GPU buffer sized for faceCount entries, but loop iterates ALL commands
for (cmd in scene.commands) {
    buf.putFloat(...)  // 4 floats per iteration
}
```

**Issue:** If `scene.commands.size > faceCount` (due to packer overflow or race), the ByteBuffer
write overshoots `requiredBytes`, causing `BufferOverflowException` or stale data in the tail.

**Fix:** Cap loop to `scene.commands.take(faceCount)` or assert `commands.size == faceCount`.

**Severity:** HIGH | **Confidence:** Med

---

### PF-4: collectTextureSources runs every frame unconditionally [HIGH]

**Location:** `isometric-webgpu/.../pipeline/GpuFullPipeline.kt:340-350`
**Source:** performance

**Issue:** Every `upload()` call scans all commands and allocates a `MutableSet<TextureSource>`
before the atlas cache check. For solid-color scenes this is pure waste.

**Fix:** Gate the scan with a `hasTexturedMaterial` flag on PreparedScene, or cache the set
reference and skip reconstruction when the scene hasn't changed.

**Severity:** HIGH | **Confidence:** Med

---

### PF-5: Stale 144-byte comment [MED]

**Location:** `TransformCullLightShader.kt:30`
**Source:** correctness + architecture

**Issue:** KDoc says `FaceData` is "144 bytes, stride 144" but the actual struct is 160 bytes.
Note: if PF-1 is fixed (revert to 144), this becomes correct again.

**Fix:** Update to match actual size (or leave if PF-1 reverts to 144).

---

### PF-6: Redundant maxOf [MED]

**Location:** `GpuFullPipeline.kt:408,441` (4 occurrences)
**Source:** code-simplification + architecture + performance

**Issue:** `maxOf(faceCount, faceCount * 2)` always equals `faceCount * 2`.

**Fix:** Replace with `faceCount * 2`.

---

### PF-7: Duplicate buffer growth boilerplate [MED]

**Location:** `GpuFullPipeline.kt:402-481`
**Source:** code-simplification

**Issue:** uploadTexIndexBuffer and uploadUvRegionBuffer have nearly identical GPU+CPU buffer
growth patterns (~10 lines each, four blocks total).

**Fix:** Extract a `GrowableGpuBuffer` helper or inline function.

---

### PF-8: GpuFullPipeline god-object [MED]

**Location:** `GpuFullPipeline.kt`
**Source:** architecture

**Issue:** Class now owns atlas management, UV region buffering, tex-index buffering, bind-group
lifecycle, and texture-source caching on top of 6 sub-pipelines.

**Fix:** Extract a `GpuTextureManager` to encapsulate atlas + UV region + tex-index concerns.

---

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| PF-1 | HIGH | correctness+arch+cs+perf | fix | Remove dead fields, revert FaceData to 144 |
| PF-2 | HIGH | correctness+security | fix | Log error, return false on atlas overflow |
| PF-3 | HIGH | security | fix | Cap buffer loop to faceCount |
| PF-4 | HIGH | performance | fix | Gate collectTextureSources with dirty flag |
| PF-5 | MED | correctness+arch | dismiss | Will auto-resolve when PF-1 reverts to 144 |
| PF-6 | MED | cs+arch+perf | fix | Replace maxOf with faceCount * 2 |
| PF-7 | MED | cs | fix | Extract GrowableGpuBuffer helper |
| PF-8 | MED | arch | fix | Extract GpuTextureManager |
| PF-9 | LOW | correctness+cs | untriaged | — |
| PF-10 | LOW | correctness | untriaged | — |
| PF-11 | LOW | correctness | untriaged | — |
| PF-12 | LOW | arch | untriaged | — |
| PF-13 | LOW | cs | untriaged | — |
| PF-14 | NIT | correctness | untriaged | — |

## Recommendations

### Must Fix (triaged "fix")
- **PF-1** — Remove uvOffset/uvScale from RenderCommand + revert FaceData to 144 bytes (~medium effort, touches 5 files)
- **PF-2** — Atlas overflow: log error + return false (~low effort, 1 file)
- **PF-3** — Cap buffer loops to faceCount (~low effort, 2 files)
- **PF-4** — Gate texture scan with dirty flag (~low effort, 1-2 files)
- **PF-6** — Replace redundant maxOf (~trivial, 4 sites)
- **PF-7** — Extract GrowableGpuBuffer (~medium effort, 1 file)
- **PF-8** — Extract GpuTextureManager (~medium effort, refactor)

### Dismissed
- **PF-5** — Stale comment auto-resolves with PF-1 fix

### Consider (LOW/NIT — not triaged)
- PF-9: Dead faceType parameter (trivial fix)
- PF-10: Dead FaceData.textureIndex (auto-resolves if FaceData is cleaned up)
- PF-11: UV degenerate for 5/6-vertex faces (latent, outside Prism scope)
- PF-12: PerFace Prism-only constraint undocumented
- PF-13: uvMid computed twice in WGSL
- PF-14: Semantic paddingPx in atlas sizing

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders per-face-materials` — re-verify after fixes
- **Option B:** `/wf-handoff texture-material-shaders per-face-materials` — all fixes applied, skip re-verify
- **Option C:** `/compact` then Option A — clear review fix context before re-verification

## Fix Status

| ID | Severity | Status | Notes |
|----|----------|--------|-------|
| PF-1 | HIGH | Fixed | Removed uvOffset/uvScale from RenderCommand, reverted FaceData to 144 bytes |
| PF-2 | HIGH | Fixed | Atlas overflow now logs error and returns false |
| PF-3 | HIGH | Fixed | Buffer loops capped to faceCount; packTexIndicesInto takes faceCount param |
| PF-4 | HIGH | Fixed | Added hasTextured early-out in uploadTextures; also removed dead faceType param (PF-9) |
| PF-6 | MED | Fixed | Replaced maxOf(x, x*2) with x*2 in 4 occurrences |
| PF-7 | MED | Fixed | Extracted GrowableGpuStagingBuffer helper class |
| PF-8 | MED | Fixed | Extracted GpuTextureManager from GpuFullPipeline |
| PF-9 | LOW | Fixed | Dead faceType parameter removed (done with PF-4) |
