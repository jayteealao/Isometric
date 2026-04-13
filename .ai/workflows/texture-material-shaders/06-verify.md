---
schema: sdlc/v1
type: verify-index
slug: texture-material-shaders
status: in-progress
stage-number: 6
created-at: "2026-04-11T23:44:32Z"
updated-at: "2026-04-12T23:35:33Z"
slices-verified: 5
slices-total: 6
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders per-face-materials"
---

# Verify Index

## Slices Verified

### `material-types` — PASS
- Checks: 5/5 passed
- Acceptance: 3/3 met
- Issues: 0
- Record: [06-verify-material-types.md](06-verify-material-types.md)

### `uv-generation` — PASS
- Checks: 4/4 passed
- Acceptance: 4/4 met
- Issues: 0
- Record: [06-verify-uv-generation.md](06-verify-uv-generation.md)

### `canvas-textures` — PASS (after VF-1 fix)
- Checks: 6/6 passed (build, tests, apiCheck — pre and post fix)
- Acceptance: 4/4 met
- Interactive: 2 runs (1 fail pre-fix, 1 pass post-fix) — checkerboard texture visible on device
- Issues: 1 found (VF-1 HIGH: material data dropped in pipeline + hook missing on default path) — **FIXED**
- Evidence: `verify-evidence/verify-textured-fixed.png`
- Record: [06-verify-canvas-textures.md](06-verify-canvas-textures.md)

### `webgpu-textures` — PARTIAL (automated pass, interactive pending)
- Checks: 4/4 passed (build, tests, apiCheck, 7-invariant static shader review)
- Acceptance: 0/4 met (all require on-device visual verification)
- Interactive: 0 runs — requires Android device + textured WebGPU sample scene
- Issues: 0
- Record: [06-verify-webgpu-textures.md](06-verify-webgpu-textures.md)

### `per-face-materials` — PARTIAL (automated pass, visual deferred)
- Checks: 3/3 passed (build, tests, apiCheck)
- Acceptance: 1/3 met (AC3 via unit tests), 2/3 deferred (AC1, AC2 need sample scene)
- Interactive: 0 runs — sample app lacks perFace {} scene (sample-demo slice)
- Code path review: 5/5 integration points verified structurally correct
- Issues: 0
- Record: [06-verify-per-face-materials.md](06-verify-per-face-materials.md)

## Recommended Next Stage
- **Option A (default):** `/wf-review texture-material-shaders per-face-materials` — all automated checks pass
- **Option B:** `/wf-implement texture-material-shaders sample-demo` — implement sample-demo first, verify both visually
- **Option C:** `/compact` then Option A
