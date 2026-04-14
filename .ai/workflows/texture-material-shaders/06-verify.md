---
schema: sdlc/v1
type: verify-index
slug: texture-material-shaders
status: in-progress
stage-number: 6
created-at: "2026-04-11T23:44:32Z"
updated-at: "2026-04-14T07:00:31Z"
slices-verified: 7
slices-total: 7
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders sample-demo"
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

### `per-face-materials` — PARTIAL (automated pass, visual deferred, post-review-fix)
- Checks: 3/3 passed (build, tests, apiCheck) — re-verified after 8 review fixes
- Acceptance: 1/3 met (AC3 via unit tests), 2/3 deferred (AC1, AC2 need sample scene)
- Interactive: 0 runs — sample app lacks perFace {} scene (sample-demo slice)
- Review fixes: 8/8 applied and verified (PF-1 through PF-9)
- Issues: 0
- Record: [06-verify-per-face-materials.md](06-verify-per-face-materials.md)

### `sample-demo` — PASS (after 4 bugfixes)
- Checks: 3/3 passed (build, tests, apiCheck)
- Acceptance: 3/3 met
- Interactive: 4 runs (Canvas, Canvas+GPU Sort, Full WebGPU, mode cycling) — all pass
- Issues: 4 found (VF-1 through VF-4) — all fixed and committed
- Evidence: `verify-evidence/v-canvas-final-sm.png`, `verify-evidence/v-webgpu-uv-sm.png`
- Record: [06-verify-sample-demo.md](06-verify-sample-demo.md)

### `api-design-fixes` — PARTIAL (1 LOW issue found)
- Checks: 3/3 passed (build, apiCheck ×3, unit tests 90/90)
- Acceptance: 12/13 met (AC4 NOT MET: isometric-shader.Shape(IsometricMaterial) not deleted)
- Interactive: 0 runs (AC1 tiling deferred to device)
- Issues: 1 found (ISSUE-1 LOW: two Shape() overloads exist; shader overload not deleted)
- Record: [06-verify-api-design-fixes.md](06-verify-api-design-fixes.md)

## Recommended Next Stage
- **Option A (default):** `/wf-implement texture-material-shaders api-design-fixes` — fix ISSUE-1: delete `isometric-shader.Shape(IsometricMaterial)`, then re-verify
- **Option B:** `/wf-review texture-material-shaders api-design-fixes` — proceed to review with ISSUE-1 as a known LOW finding
- **Option C:** `/wf-handoff texture-material-shaders api-design-fixes` — skip review (solo / trivial)
