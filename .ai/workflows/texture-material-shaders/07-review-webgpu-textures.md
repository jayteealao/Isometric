---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice-slug: webgpu-textures
status: complete
stage-number: 7
created-at: "2026-04-12T21:01:11Z"
updated-at: "2026-04-12T21:01:11Z"
verdict: ship-with-caveats
commands-run: [correctness, security, code-simplification, backend-concurrency, performance, architecture]
metric-commands-run: 6
metric-findings-total: 22
metric-findings-raw: 45
metric-findings-blocker: 0
metric-findings-high: 7
metric-findings-med: 8
metric-findings-low: 4
metric-findings-nit: 3
tags: [webgpu, texture, shader, review]
refs:
  index: 00-index.md
  slice-def: 03-slice-webgpu-textures.md
  implement: 05-implement-webgpu-textures.md
  verify: 06-verify-webgpu-textures.md
  sub-reviews:
    - 07-review-webgpu-textures-correctness.md
    - 07-review-webgpu-textures-security.md
    - 07-review-webgpu-textures-code-simplification.md
    - 07-review-webgpu-textures-backend-concurrency.md
    - 07-review-webgpu-textures-performance.md
    - 07-review-webgpu-textures-architecture.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders reviews"
---

# Review: webgpu-textures

## Verdict

**Ship with caveats**

No blockers. Seven HIGH findings span three clusters: (1) init protocol / ownership — the `bindGroupLayout` two-phase init and split `textureBindGroupLayout` lifetime are the most cross-cutting issues, flagged by 5 of 6 reviewers; (2) safety — integer overflow in bitmap upload and missing dimension cap; (3) correctness — `identityHashCode` cache key collisions and `pipeline!!` force-unwrap. All are addressable in a single implement-reviews pass. The code is functionally correct on-device (Dawn crash and uniformity fixes confirmed) but needs hardening before merging to `feat/webgpu`.

## Domain Coverage

| Domain | Command | Status |
|--------|---------|--------|
| Logic & invariants | `correctness` | Issues (3 HIGH, 3 MED, 2 LOW, 1 NIT) |
| Vulnerabilities | `security` | Issues (2 HIGH, 2 MED, 1 LOW, 3 INFO) |
| Complexity & reuse | `code-simplification` | Issues (1 HIGH, 3 MED, 2 LOW) |
| Async & threading | `backend-concurrency` | Issues (2 HIGH, 3 MED, 1 LOW, 1 NIT) |
| GPU efficiency | `performance` | Issues (2 HIGH, 3 MED, 2 LOW) |
| Component design | `architecture` | Issues (2 HIGH, 3 MED, 1 LOW, 2 INFO) |

## All Findings (Deduplicated)

| ID | Sev | Source | File:Line | Issue |
|----|-----|--------|-----------|-------|
| R-1 | HIGH | CR+BC+ARCH+CS | `WebGpuSceneRenderer.kt:457` | `pipeline.pipeline!!` force-unwrap — replace with `checkNotNull` |
| R-2 | HIGH | CR+SEC+CS | `GpuFullPipeline.kt:346` | `identityHashCode` as bitmap cache key — use `===` reference equality |
| R-3 | HIGH | CR+BC+ARCH+SEC | `GpuTextureBinder.kt:43` | `bindGroupLayout` two-phase init via mutable nullable var — pass to constructor |
| R-4 | HIGH | ARCH | `GpuRenderPipeline.kt:129` / `GpuTextureBinder.kt:69` | Split ownership of `textureBindGroupLayout` — use-after-free risk |
| R-5 | HIGH | SEC | `GpuTextureStore.kt:74` | Integer overflow `w * h * 4` — widen to Long + require() |
| R-6 | HIGH | SEC | `GpuTextureStore.kt:68-88` | No bitmap dimension cap — unbounded GPU memory allocation |
| R-7 | HIGH | CR | `GpuTextureStore.kt:107-113` | Fallback texture close ordering — view closed after texture destroyed |
| R-8 | MED | CR | `GpuFullPipeline.kt:337-343` | GPU texture leak in null-bitmap path — missing `releaseUploadedTexture()` |
| R-9 | MED | CR | `GpuFullPipeline.kt:374` | `uploadTexIndexBuffer` uses `scene.commands.size` not `sceneData.faceCount` |
| R-10 | MED | PERF+CR | `IsometricFragmentShader.kt:22` / `GpuTextureStore.kt` | Unconditional `textureSample` hits 2x2 checkerboard — swap to 1x1 white |
| R-11 | MED | PERF | `TriangulateEmitShader.kt` | Emit shader clears all 12 slots then overwrites — 33% excess writes |
| R-12 | MED | BC | `GpuRenderPipeline.kt:55` / `GpuTextureStore.kt:35` | Missing `assertGpuThread()` on init paths |
| R-13 | MED | CS | `GpuFullPipeline.kt:313-421` | Fallback bind-group close+rebuild duplicated 3 times — extract helper |
| R-14 | MED | PERF | `GpuFullPipeline.kt:374-398` | texIndex buffers allocate exactly — no x2 growth factor |
| R-15 | MED | PERF | `GpuFullPipeline.kt:297-310` | Linear scan for first texture every upload; PerFace arm missing break |
| R-16 | LOW | BC | `GpuRenderPipeline.kt:58-129` | `ensurePipeline()` partial failure leaks shader module handles |
| R-17 | LOW | SEC | `WebGpuSceneRenderer.kt:404-514` | `surfaceTexture.texture` not closed on non-success status paths |
| R-18 | LOW | PERF | multiple | 36-byte vertex stride breaks cache-line alignment (deferred to atlas) |
| R-19 | LOW | SEC | `GpuTextureStore.kt` / `GpuTextureBinder.kt` | Mipmap constraint not documented |
| R-20 | NIT | CR | `WebGpuSampleActivity.kt:419` | Checkerboard bitmap never recycled on disposal |
| R-21 | NIT | CS | `WebGpuSampleActivity.kt:419` | Sample duplicates checkerboard concept — add clarifying comment |
| R-22 | NIT | SEC | `IsometricFragmentShader.kt:17` | WGSL string interpolation pattern — document const-only rule |

**Total:** BLOCKER: 0 | HIGH: 7 | MED: 8 | LOW: 4 | NIT: 3
*(After dedup: 22 findings merged from 45 raw findings across 6 commands)*

## Triage Decisions

| ID | Sev | Source | Decision | Notes |
|----|-----|--------|----------|-------|
| R-1 | HIGH | CR+BC+ARCH+CS | fix | — |
| R-2 | HIGH | CR+SEC+CS | fix | — |
| R-3 | HIGH | CR+BC+ARCH+SEC | fix | — |
| R-4 | HIGH | ARCH | fix | — |
| R-5 | HIGH | SEC | fix | — |
| R-6 | HIGH | SEC | fix | — |
| R-7 | HIGH | CR | fix | — |
| R-8 | MED | CR | fix | — |
| R-9 | MED | CR | fix | — |
| R-10 | MED | PERF+CR | defer | User did not select |
| R-11 | MED | PERF | fix | — |
| R-12 | MED | BC | fix | — |
| R-13 | MED | CS | fix | — |
| R-14 | MED | PERF | fix | — |
| R-15 | MED | PERF | fix | — |
| R-16 | LOW | BC | untriaged | — |
| R-17 | LOW | SEC | untriaged | — |
| R-18 | LOW | PERF | untriaged | deferred to atlas slice |
| R-19 | LOW | SEC | untriaged | — |
| R-20 | NIT | CR | untriaged | — |
| R-21 | NIT | CS | untriaged | — |
| R-22 | NIT | SEC | untriaged | — |

## Recommendations

### Must Fix (triaged "fix") — 14 findings

**Init protocol cluster (R-1, R-3, R-4):** ~medium effort
- R-1: Replace `pipeline.pipeline!!` with `checkNotNull` — trivial
- R-3: Pass `bindGroupLayout` to `GpuTextureBinder` constructor — requires init order refactor
- R-4: Transfer layout ownership or route through `ensurePipelines(rp)` — same refactor as R-3

**Cache key fix (R-2):** ~trivial
- Replace `identityHashCode` + `Any?` with `Bitmap?` + `===`

**Bitmap safety (R-5, R-6):** ~low effort
- R-5: Widen `w * h * 4` to Long arithmetic + require
- R-6: Add `maxTextureDimension` cap in `uploadBitmap`

**Resource lifecycle (R-7, R-8):** ~low effort
- R-7: Close `fallbackTextureView` before destroying texture
- R-8: Call `releaseUploadedTexture()` in null-bitmap path

**Correctness (R-9):** ~trivial
- Pass `sceneData.faceCount` to `uploadTexIndexBuffer` instead of `scene.commands.size`

**Performance (R-11, R-14, R-15):** ~medium effort
- R-11: Reorder emit shader: write real vertices first, fill remaining with degenerates
- R-14: Apply x2 growth factor to texIndex CPU/GPU buffers
- R-15: Add break in PerFace arm; cache `hasTexture` flag

**Thread safety (R-12):** ~low effort
- Add `assertGpuThread()` to `ensurePipeline()`, `ensurePipelines()`, `GpuTextureStore.init`

**Code quality (R-13):** ~trivial
- Extract `setBindGroup(view)` helper in `GpuFullPipeline`

### Deferred

- R-10: Swap fallback to 1x1 white pixel — deferred by user

### Consider (LOW/NIT — not triaged)

- R-16: ensurePipeline partial failure handle leak
- R-17: surfaceTexture.texture leak on non-success
- R-18: 36-byte stride alignment (deferred to atlas slice)
- R-19: Mipmap constraint docs
- R-20: Bitmap.recycle on disposal
- R-21: Sample comment clarification
- R-22: WGSL interpolation const-only doc

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders webgpu-textures` — re-verify after fixes
- **Option B:** `/wf-handoff texture-material-shaders webgpu-textures` — skip to handoff
- **Option C:** `/wf-implement texture-material-shaders per-face-materials` — move to next slice

## Fix Status

| ID | Severity | Status | Notes |
|----|----------|--------|-------|
| R-1 | HIGH | Fixed | `pipeline.pipeline!!` → `checkNotNull` with message |
| R-2 | HIGH | Fixed | `identityHashCode` → `===` reference equality with `Bitmap?` |
| R-3 | HIGH | Fixed | `bindGroupLayout` passed as constructor param to `GpuTextureBinder` |
| R-4 | HIGH | Fixed | Ownership transferred via `takeTextureBindGroupLayout()`; binder closes layout |
| R-5 | HIGH | Fixed | `w * h * 4` widened to Long arithmetic + require |
| R-6 | HIGH | Fixed | Added `MAX_TEXTURE_DIMENSION = 4096` cap |
| R-7 | HIGH | Fixed | `fallbackTextureView.close()` moved before texture destroy loop |
| R-8 | MED | Fixed | `releaseUploadedTexture()` called in null-bitmap path |
| R-9 | MED | Fixed | `uploadTexIndexBuffer` now receives `faceCount` from `sceneData` |
| R-10 | MED | Deferred | User deferred: swap 2x2 checkerboard to 1x1 white pixel |
| R-11 | MED | Fixed | Emit shader writes real vertices first, then fills degenerate slots |
| R-12 | MED | Fixed | `assertGpuThread()` added to `ensurePipeline()`, `ensurePipelines()`, `GpuTextureStore.init` |
| R-13 | MED | Fixed | Extracted `rebuildBindGroup(view)` helper |
| R-14 | MED | Fixed | x2 growth factor applied to both CPU and GPU texIndex buffers |
| R-15 | MED | Fixed | PerFace arm now checks `faceMap` values + outer break guard |
