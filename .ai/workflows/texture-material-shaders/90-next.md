---
schema: sdlc/v1
type: routing
slug: texture-material-shaders
updated-at: "2026-04-12T19:12:05Z"
current-stage: verify
stage-number: 6
status: active
selected-slice: "webgpu-textures"
branch-strategy: dedicated
branch: "feat/texture"
on-correct-branch: true
is-blocked: false
open-questions: []
remaining-slices: [per-face-materials, sample-demo]
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders webgpu-textures"
refs:
  index: 00-index.md
---

# Next

## Progress

| Slice | Implement | Verify | Review | Status |
|-------|-----------|--------|--------|--------|
| material-types | done | done | done | Complete |
| uv-generation | done | done | done | Complete |
| canvas-textures | done | done | done (9 fixes) | Complete |
| webgpu-textures | done | partial (auto pass, interactive pending) | — | **Verify complete, ready for review** |
| per-face-materials | — | — | — | Pending |
| sample-demo | — | — | — | Pending |

## Post-Verify Fixes Applied (not yet in verify record)
- Dawn Scudo crash: switched to async `createRenderPipelineAndAwait` + auto-derived layout
- Dawn uniformity error: replaced `if/textureSample` with unconditional `textureSample` + `select()`
- Added "Textured" tab to `WebGpuSampleActivity` for on-device verification

## All Options
- **Option A (default):** `/wf-review texture-material-shaders webgpu-textures` — code review; all automated checks pass, two runtime fixes applied
- **Option B:** `/compact` then Option A — recommended to clear implementation/debugging context before review dispatch
- **Option C:** `/wf-implement texture-material-shaders per-face-materials` — skip review, move to next slice
- **Option D:** `/wf-implement texture-material-shaders webgpu-textures` — if more on-device issues are found during manual testing
