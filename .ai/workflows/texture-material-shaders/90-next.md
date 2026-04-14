---
schema: sdlc/v1
type: routing
slug: texture-material-shaders
updated-at: "2026-04-13T23:17:09Z"
current-stage: plan
stage-number: 4
status: active
selected-slice: "api-design-fixes"
branch-strategy: dedicated
branch: "feat/texture"
on-correct-branch: true
is-blocked: false
open-questions: []
remaining-slices: [api-design-fixes]
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders api-design-fixes"
refs:
  index: 00-index.md
  plan: 04-plan-api-design-fixes.md
  slice: 03-slice-api-design-fixes.md
---

# Next

## Situation

Plan for `api-design-fixes` complete (`04-plan-api-design-fixes.md`). 16 steps across 19 files.
All 25 findings from `07-review-webgpu-textures-api.md` have concrete fix paths.

Key sequencing constraint: Step 10 (TM-API-24: decouple CachedTexture from BitmapShader)
must land before Step 11 (TM-API-2: apply TextureTransform with REPEAT TileMode).

## Progress

| Slice | Plan | Implement | Verify | Review | Status |
|-------|------|-----------|--------|--------|--------|
| material-types | ✅ | ✅ | ✅ | ✅ | Complete |
| uv-generation | ✅ | ✅ | ✅ | ✅ | Complete |
| canvas-textures | ✅ | ✅ | ✅ | ✅ | Complete |
| webgpu-textures | ✅ | ✅ | ✅ | ✅ | Complete |
| per-face-materials | ✅ | ✅ | ✅ | ✅ | Complete |
| sample-demo | ✅ | ✅ | ✅ | ✅ | Complete |
| **api-design-fixes** | ✅ | — | — | — | **Plan complete — ready to implement** |

## All Options

- **Option A (default):** `/wf-implement texture-material-shaders api-design-fixes` —
  implement all 16 steps. **Run `/compact` first** to prevent context overflow during
  the 19-file implementation session.
