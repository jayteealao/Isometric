---
schema: sdlc/v1
type: verify-index
slug: texture-material-shaders
status: in-progress
stage-number: 6
created-at: "2026-04-11T23:44:32Z"
updated-at: "2026-04-15T12:53:42Z"
slices-verified: 8
slices-total: 8
tags: [texture, material, shader, canvas, webgpu]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders webgpu-uv-transforms"
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

### `api-design-fixes` — PASS Round 3 (post-review-fixes-round2, all ACs met)
- Checks: 3/3 passed (build, apiCheck, unit tests 75/75 + 7 @Ignored)
- Acceptance: 13/13 met (AC4 accepted per review triage: two Shape() overloads intentional)
- Interactive: 0 runs (AC1 tiling deferred to device)
- Issues: 0 (all Round 2 findings fixed; 4 in-verify corrections applied cleanly)
- Record: [06-verify-api-design-fixes.md](06-verify-api-design-fixes.md)

### `webgpu-uv-transforms` — PARTIAL (4/6 ACs met on device; AC2/AC3 unit-test only)
- Checks: 4/4 passed (build, test 22/22 pre- and post-fix)
- Acceptance: 4/6 fully met (AC1 tiling ✓, AC4 per-face ✓, AC5 IDENTITY ✓, AC6 automated ✓); 2/6 partial (AC2 offset, AC3 rotation — math only)
- Interactive: 7 screenshots — IDENTITY (3 modes) + tiling-fix (Canvas, WebGPU, Canvas+GPU Sort, cycle-back) all PASS
- Issues: 2 found and fixed — atlas tiling bug (HIGH, `fract()` + UvRegion) + no tiling demo (LOW, extended demo)
- Evidence: `verify-evidence/tiling_fix_canvas_baseline.png`, `verify-evidence/tiling_fix_webgpu.png`, `verify-evidence/tiling_fix_canvas_gpu_sort.png`
- Record: [06-verify-webgpu-uv-transforms.md](06-verify-webgpu-uv-transforms.md)

## Recommended Next Stage
- **Option A (default):** `/wf-review texture-material-shaders webgpu-uv-transforms` — math correct, IDENTITY regression confirmed, ready for code review
- **Option B:** `/wf-handoff texture-material-shaders api-design-fixes` — merge PR #8 first, then review this slice
