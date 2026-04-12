---
schema: sdlc/v1
type: routing
slug: texture-material-shaders
updated-at: "2026-04-12T15:17:23Z"
current-stage: implement
stage-number: 5
status: active
selected-slice: "canvas-textures"
branch-strategy: dedicated
branch: "feat/texture"
on-correct-branch: true
is-blocked: false
open-questions: []
remaining-slices: [webgpu-textures, per-face-materials, sample-demo]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders webgpu-textures"
refs:
  index: 00-index.md
---

# Next

## Progress

| Slice | Implement | Verify | Review | Status |
|-------|-----------|--------|--------|--------|
| material-types | done | done | done | Complete |
| uv-generation | done | done | done | Complete |
| canvas-textures | done | done | done (9 fixes applied) | **Complete** |
| webgpu-textures | — | — | — | Next up |
| per-face-materials | — | — | — | Pending |
| sample-demo | — | — | — | Pending |

## All Options
- **Option A (default):** `/wf-implement texture-material-shaders webgpu-textures` — next slice in implementation order
- **Option B:** `/compact` then Option A — recommended to clear canvas-textures lifecycle context
- **Option C:** `/wf-plan texture-material-shaders webgpu-textures` — re-review webgpu-textures plan first (was written before dependency inversion)
- **Option D:** `/wf-implement texture-material-shaders per-face-materials` — skip webgpu, do per-face next (if WebGPU not priority)
