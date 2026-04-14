---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: sample-demo
status: complete
stage-number: 6
created-at: "2026-04-13T17:57:30Z"
updated-at: "2026-04-13T20:46:12Z"
result: pass
metric-checks-run: 3
metric-checks-passed: 3
metric-acceptance-met: 3
metric-acceptance-total: 3
metric-interactive-checks-run: 4
metric-interactive-checks-passed: 4
metric-issues-found: 0
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [sample, demo, texture, per-face, verify, post-review-fix]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-sample-demo.md
  plan: 04-plan-sample-demo.md
  implement: 05-implement-sample-demo.md
  review: 07-review-sample-demo.md
next-command: wf-handoff
next-invocation: "/wf-handoff texture-material-shaders sample-demo"
---

# Verify: Sample App Demo (post-review-fix)

## Verification Summary

Re-verification after 12 review fixes (SD-2 through SD-13). All automated checks pass.
All 3 acceptance criteria met via on-device interactive verification on SM-F956B. No new
issues found. SD-7 fix (exported=false) confirmed working — ADB direct launch correctly
blocked, activity reachable via MainActivity.

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build (all modules) | `./gradlew build -x test -x apiCheck` | PASS |
| Unit tests | `./gradlew test` | PASS — 246 tasks |
| API compatibility | `./gradlew apiCheck` | PASS |

## Interactive Verification Results

### AC1: Prism grid with grass tops and dirt sides
- **Platform & tool**: SM-F956B, adb + uiautomator
- **Steps**: Launched via MainActivity → tapped "Textured Materials" card
- **Evidence**: `verify-evidence/rv-canvas-sm.png`
- **Observation**: 4x4 grid with green grass tops and brown dirt sides clearly visible
- **Result**: PASS

### AC2: Canvas mode textures via BitmapShader
- **Platform & tool**: SM-F956B, adb input tap (bounds from uiautomator)
- **Steps**: Verified Canvas (default) and Canvas + GPU Sort pills
- **Evidence**: `verify-evidence/rv-canvas-sm.png`, `verify-evidence/rv-gpusort-sm.png`
- **Observation**: Both Canvas modes render correctly with per-face textures
- **Result**: PASS

### AC3: WebGPU mode textures via fragment shader
- **Platform & tool**: SM-F956B, adb input tap
- **Steps**: Tapped "Full WebGPU" pill at (770, 438)
- **Evidence**: `verify-evidence/rv-webgpu-sm.png`
- **Observation**: WebGPU renders with green tops and textured sides. Transparent clear color working (dark background from Compose compositing).
- **Result**: PASS

### SD-7 fix verification: exported=false
- **Steps**: Attempted `adb shell am start -n ...TexturedDemoActivity` — received SecurityException (Permission Denial: not exported). Launched successfully via MainActivity card.
- **Result**: PASS — fix working as intended

## Acceptance Criteria Status

| AC | Status | Method | Evidence |
|----|--------|--------|----------|
| AC1: Prism grid with grass tops + dirt sides | MET | Interactive | `rv-canvas-sm.png` |
| AC2: Canvas mode textures via BitmapShader | MET | Interactive | `rv-canvas-sm.png`, `rv-gpusort-sm.png` |
| AC3: WebGPU mode textures via fragment shader | MET | Interactive | `rv-webgpu-sm.png` |

## Issues Found

None.

## Gaps / Unverified Areas

None — all acceptance criteria verified on device.

## Freshness Research

No external dependency changes.

## Recommendation

All automated checks pass after review fixes. All acceptance criteria met. Ready for handoff.

## Recommended Next Stage
- **Option A (default):** `/wf-handoff texture-material-shaders sample-demo` — all checks pass, all ACs met, review complete
- **Option B:** `/compact` then Option A — clear verification context
