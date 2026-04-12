---
schema: sdlc/v1
type: routing
slug: texture-material-shaders
updated-at: "2026-04-12T10:44:08Z"
current-stage: implement
stage-number: 5
status: active
selected-slice: "canvas-textures"
branch-strategy: dedicated
branch: "feat/texture"
on-correct-branch: true
is-blocked: false
open-questions: []
remaining-slices: [canvas-textures, webgpu-textures, per-face-materials, sample-demo]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders canvas-textures"
refs:
  index: 00-index.md
---

# Next

## Progress

| Slice | Implement | Verify | Review | Status |
|-------|-----------|--------|--------|--------|
| material-types | done | done | done (ship) | All review fixes applied |
| uv-generation | done | done | done (ship) | UV fixes applied |
| canvas-textures | — | — | — | **Next up** (plan rev 3 ready) |
| webgpu-textures | — | — | — | Pending |
| per-face-materials | — | — | — | Pending |
| sample-demo | — | — | — | Pending |

## All Options
- **Option A (default):** `/wf-implement texture-material-shaders canvas-textures` — implement Canvas textured rendering (MaterialDrawHook + BitmapShader + TextureCache). Plan rev 3 includes interactive visual verification with adb/Maestro.
- **Option B:** `/compact` then Option A — recommended to clear plan-review context before implementing
- **Option C:** `/wf-implement texture-material-shaders webgpu-textures` — implement WebGPU textures in parallel (independent of canvas-textures)
- **Option D:** `/wf-plan texture-material-shaders canvas-textures` — re-review the canvas-textures plan if further changes needed
