---
schema: sdlc/v1
type: po-answers
slug: texture-material-shaders
---

# Product Owner Answers

## 2026-04-11 — Intake

**Branch strategy:** Dedicated — `feat/texture` from `feat/webgpu` (already created)

**Appetite:** Large — multiple days, needs slicing and incremental delivery

**Module name:** `isometric-shader` — new module for material/texture/shader abstractions

**Canvas texture approach:** BitmapShader + Matrix (research-recommended, hardware-accelerated)

**AGSL scope:** User asked "do we also need AGSL if we already have WGSL with WebGPU?"
- Clarified: AGSL = Canvas shaders (API 33+), WGSL = WebGPU shaders. Different render paths.
- Decision: AGSL deferred implicitly — not explicitly requested. Canvas gets textures via BitmapShader, shader effects via WebGPU WGSL.

**Success criteria:** Textured Prism in sample app with per-face materials (grass top, dirt sides)

**Non-goals:** None specified — user has no exclusions at present

**WebGPU texture priority:** Both Canvas and WebGPU get texture support in this effort (not Canvas-first)

