---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: per-face-materials
status: complete
stage-number: 6
created-at: "2026-04-12T23:35:33Z"
updated-at: "2026-04-12T23:35:33Z"
result: partial
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 1
metric-acceptance-total: 3
metric-interactive-checks-run: 0
metric-interactive-checks-passed: 0
metric-issues-found: 0
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [per-face, material, verify]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-per-face-materials.md
  plan: 04-plan-per-face-materials.md
  implement: 05-implement-per-face-materials.md
  review: 07-review-per-face-materials.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders per-face-materials"
---

# Verify: Per-Face Materials

## Verification Summary

All automated checks pass (build, unit tests, apiCheck). AC3 (default fallback for unset
faces) is fully met via unit tests. AC1 (Canvas per-face rendering) and AC2 (WebGPU
per-face rendering) require on-device visual verification, which is deferred because the
sample app does not yet include a `perFace {}` scene — that's the `sample-demo` slice's
responsibility. Code path integrity for AC1 and AC2 was verified via structural code
review across all 5 integration points.

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build (all modules) | `./gradlew build -x test -x apiCheck` | PASS — BUILD SUCCESSFUL |
| Unit tests | `./gradlew test` | PASS — 246 tasks, all pass |
| API compatibility | `./gradlew apiCheck` | PASS — no public API breaks |

## Interactive Verification Results

No interactive verification was performed. The sample app does not include a `perFace {}`
scene. Interactive verification (V1–V5 from the plan) is deferred to the `sample-demo`
slice, which adds per-face prisms to the sample activity.

## Code Path Structural Verification

In lieu of interactive verification, all 5 integration points were verified via code review:

| Point | File | Status |
|-------|------|--------|
| Canvas PerFace branch | `TexturedCanvasDrawHook.kt:52–59` | Correct — resolves via `cmd.faceType` |
| `faceType` population | `IsometricNode.kt:278–292` | Correct — `PrismFace.fromPathIndex(index)` for Prisms |
| UV provider for PerFace | `IsometricMaterialComposables.kt:65–72` | Correct — triggers for both Textured and PerFace |
| `SceneDataPacker.resolveTextureIndex` | `SceneDataPacker.kt:210–222` | Correct — expands PerFace using faceType |
| `collectTextureSources` + `resolveAtlasRegion` | `GpuFullPipeline.kt:379–500` | Correct — iterates faceMap + default |

## Acceptance Criteria Status

| AC | Status | Method | Evidence |
|----|--------|--------|----------|
| AC1: Canvas per-face rendering | DEFERRED | Code review (no sample scene) | 5 integration points verified structurally |
| AC2: WebGPU per-face rendering | DEFERRED | Code review (no sample scene) | Atlas + UV transform paths verified structurally |
| AC3: Default fallback for unset faces | MET | Unit tests | `PerFaceMaterialTest`: `resolve returns default for faces not in faceMap` |

## Issues Found

None.

## Gaps / Unverified Areas

- **Visual rendering**: AC1 and AC2 require on-device visual verification with a perFace
  scene in the sample app. This will be available after the `sample-demo` slice.
- **Multi-texture atlas packing on device**: TextureAtlasManager has no on-device test.
  The shelf-packing algorithm is correct by construction but untested with real GPU textures.
- **Performance regression**: V5 (Animated Towers) not tested — deferred to sample-demo.

## Freshness Research

No external dependency changes since implementation.

## Recommendation

All automated checks pass. AC3 is met. AC1 and AC2 are structurally verified but lack
visual confirmation (deferred to sample-demo). The code is ready for review — visual
verification will occur when the sample-demo slice wires up a per-face scene.

## Recommended Next Stage
- **Option A (default):** `/wf-review texture-material-shaders per-face-materials` — all automated checks pass, code paths verified structurally
- **Option B:** `/wf-implement texture-material-shaders sample-demo` — skip review, implement sample-demo first, then verify both visually
- **Option C:** `/wf-handoff texture-material-shaders per-face-materials` — skip review (solo project)
