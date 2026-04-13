---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: sample-demo
status: complete
stage-number: 6
created-at: "2026-04-13T17:57:30Z"
updated-at: "2026-04-13T17:57:30Z"
result: pass
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 3
metric-acceptance-total: 3
metric-interactive-checks-run: 4
metric-interactive-checks-passed: 4
metric-issues-found: 4
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [sample, demo, texture, per-face, verify]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-sample-demo.md
  plan: 04-plan-sample-demo.md
  implement: 05-implement-sample-demo.md
  review: 07-review-sample-demo.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders sample-demo"
---

# Verify: Sample App Demo — Textured Scene

## Verification Summary

All 3 automated checks pass (build, tests, apiCheck). All 3 acceptance criteria met via
on-device interactive verification on SM-F956B. Four bugs discovered and fixed during
verification — all committed as atomic fixes before final pass.

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build (all modules) | `./gradlew build -x test -x apiCheck` | PASS — BUILD SUCCESSFUL |
| Unit tests | `./gradlew test` | PASS — 246 tasks, all pass |
| API compatibility | `./gradlew apiCheck` | PASS — no public API breaks |

## Issues Found During Verification (all fixed)

| # | Severity | Issue | Fix | Commit |
|---|----------|-------|-----|--------|
| VF-1 | HIGH | `faceType` dropped by `projectScene()` — `SceneItem` and engine `RenderCommand` construction missing field. All faces fell back to `PerFace.default` (dirt). | Added `faceType` to `SceneItem`, `SceneProjector.add()`, `IsometricEngine.add()`, both sync `projectScene` sites. | `ea14787` |
| VF-2 | HIGH | `faceType` also dropped by `projectSceneAsync()` — the async path used by Canvas + GPU Sort mode was missed by VF-1 fix. | Added `faceType` to the async `RenderCommand` construction site. | `184c0f1` |
| VF-3 | HIGH | WebGPU texture colors inverted (blue tint). Two root causes: (a) `GpuTextureStore` used `BGRA8Unorm` but `Bitmap.copyPixelsToBuffer()` writes RGBA bytes; (b) textured materials inherited `LocalDefaultColor` (blue) as base color, which the GPU lighting shader multiplied into `litColor`. | (a) Changed texture format to `RGBA8Unorm`. (b) Changed base color to `IsoColor.WHITE` for `Textured`/`PerFace` materials. White clear color for render pass. | `c730679`, `b468c9c` |
| VF-4 | MED | WebGPU texture orientation differed from Canvas. Emit shader used hardcoded quad UVs `(0,0)(1,0)(1,1)(0,1)` instead of geometry-aware UVs from `UvGenerator`. | Added per-vertex UV buffer (binding 6) to emit pipeline. `GpuTextureManager` uploads `cmd.uvCoords` as 3×vec4 per face. Emit shader reads real UVs per vertex. | `f581ca0` |

## Interactive Verification Results

### AC1: Prism grid with grass tops and dirt sides
- **Platform & tool**: SM-F956B, adb screencap + uiautomator
- **Steps**: Launched TexturedDemoActivity, observed default Canvas mode
- **Evidence**: `verify-evidence/v-canvas-final-sm.png`
- **Observation**: 4×4 isometric prism grid visible. Top faces show green grass texture, side faces show brown dirt texture with horizontal stripes.
- **Result**: PASS

### AC2: Canvas mode textures via BitmapShader
- **Platform & tool**: SM-F956B, adb input tap + screencap
- **Steps**: Tapped "Canvas" pill (confirmed via uiautomator bounds [74,351][251,477]), then "Canvas + GPU Sort" pill ([272,351][624,477])
- **Evidence**: `verify-evidence/v-canvas-final-sm.png`, `verify-evidence/v-gpusort-sm.png`
- **Observation**: Both Canvas modes render with correct per-face textures. Toggle pills highlight correctly. No crash on mode switch.
- **Result**: PASS

### AC3: WebGPU mode textures via GPU fragment shader
- **Platform & tool**: SM-F956B, adb input tap + screencap
- **Steps**: Tapped "Full WebGPU" pill ([645,369][894,507]), waited 4s for GPU init
- **Evidence**: `verify-evidence/v-webgpu-uv-sm.png`
- **Observation**: WebGPU renders on white background. Green grass tops, brown dirt sides with matching texture orientation to Canvas mode. No crash on mode switch.
- **Result**: PASS

### Mode cycling (no crash)
- **Steps**: Cycled Canvas → Canvas+GPU Sort → Full WebGPU → Canvas
- **Observation**: All transitions smooth, no crash, scene re-renders correctly each time
- **Result**: PASS

## Acceptance Criteria Status

| AC | Status | Method | Evidence |
|----|--------|--------|----------|
| AC1: Prism grid with grass tops + dirt sides | MET | Interactive (device screenshot) | `v-canvas-final-sm.png` |
| AC2: Canvas mode textures via BitmapShader | MET | Interactive (device screenshot) | `v-canvas-final-sm.png`, `v-gpusort-sm.png` |
| AC3: WebGPU mode textures via fragment shader | MET | Interactive (device screenshot) | `v-webgpu-uv-sm.png` |

## Gaps / Unverified Areas

None — all acceptance criteria verified on device.

## Freshness Research

No external dependency changes.

## Recommendation

All automated checks pass. All acceptance criteria met via on-device verification. Four
bugs discovered and fixed with atomic commits. The sample-demo slice is complete and ready
for review.

## Recommended Next Stage
- **Option A (default):** `/wf-review texture-material-shaders sample-demo` — all checks pass, all ACs met
- **Option B:** `/compact` then Option A — clear verification context first (recommended)
- **Option C:** `/wf-handoff texture-material-shaders sample-demo` — skip review (solo project, already visually verified)
