---
schema: sdlc/v1
type: verify
slug: texture-material-shaders
slice-slug: canvas-textures
status: complete
stage-number: 6
created-at: "2026-04-12T11:21:59Z"
updated-at: "2026-04-12T14:47:56Z"
result: pass
metric-checks-run: 6
metric-checks-passed: 6
metric-acceptance-met: 4
metric-acceptance-total: 4
metric-interactive-checks-run: 2
metric-interactive-checks-passed: 2
metric-issues-found: 1
metric-issues-fixed: 1
evidence-dir: ".ai/workflows/texture-material-shaders/verify-evidence/"
tags: [canvas, texture, rendering]
refs:
  index: 00-index.md
  verify-index: 06-verify.md
  slice-def: 03-slice-canvas-textures.md
  plan: 04-plan-canvas-textures.md
  implement: 05-implement-canvas-textures.md
  review: 07-review.md
next-command: wf-review
next-invocation: "/wf-review texture-material-shaders canvas-textures"
---

# Verify: Canvas Textured Rendering

## Verification Summary

All automated checks pass. All 4 acceptance criteria are met by code path analysis.
All automated checks pass after VF-1 fix. Interactive device testing confirms textured
rendering works on the default Compose DrawScope path. All 4 acceptance criteria are met.

## Automated Checks Run

| Check | Command | Result |
|-------|---------|--------|
| Build (pre-fix) | `./gradlew :isometric-compose:compileDebugKotlin :isometric-shader:compileDebugKotlin :isometric-core:compileKotlin` | PASS |
| Unit tests (pre-fix) | `./gradlew :isometric-shader:testDebugUnitTest :isometric-compose:testDebugUnitTest :isometric-core:test` | PASS |
| API check (pre-fix) | `./gradlew :isometric-compose:apiCheck :isometric-shader:apiCheck :isometric-core:apiCheck` | PASS |
| Build (post-fix) | all three modules | PASS |
| Unit tests (post-fix) | `./gradlew :isometric-core:test :isometric-compose:testDebugUnitTest :isometric-shader:testDebugUnitTest` | PASS |
| API check (post-fix) | `./gradlew :isometric-core:apiCheck :isometric-compose:apiCheck :isometric-shader:apiCheck` | PASS |

## Interactive Verification Results

### Round 1 — Pre-fix (FAIL)

- **Platform & tool**: Android (Samsung SM-F956B, Galaxy Z Fold), adb screencap + uiautomator
- **Steps**: `./gradlew :app:installDebug` → launched ComposeActivity → navigated to "Textured"
  tab via `adb shell input tap` (coordinates from `uiautomator dump`)
- **Evidence**: `verify-evidence/verify-textured-final.png` (pre-fix, shows all-blue prisms)
- **Observation**: Three prisms visible. All render as flat blue — no checkerboard texture.
  Debug logging confirmed `materials=0` in `renderPreparedScene` — material/uvCoords data
  was being dropped in the projection pipeline.
- **Result**: **FAIL**

**Root cause (two issues):**
1. `material` and `uvCoords` were not threaded through `SceneGraph.SceneItem` →
   `IsometricEngine.projectScene()`. Template `RenderCommand`s set them correctly but
   `SceneCache` only passed 5 of 7 fields to `engine.add()`, and `projectScene()` created
   new `RenderCommand`s without them.
2. `IsometricRenderer.renderPreparedScene()` (Compose DrawScope path, used when
   `useNativeCanvas = false` which is the default) did not invoke `MaterialDrawHook`.

### Round 2 — Post-fix (PASS)

- **Platform & tool**: Same device, same flow
- **Steps**: Rebuilt after fix → reinstalled → navigated to "Textured" tab → screenshot
- **Evidence**: `verify-evidence/verify-textured-fixed.png`
- **Observation**: Three prisms visible:
  - **Left prism** (pos 0,0,0): Magenta/black checkerboard texture on all 3 visible faces,
    correctly affine-mapped to each parallelogram face shape
  - **Center prism** (pos 2,0,0): Flat blue — backward compatibility preserved
  - **Right prism** (pos 4,0,0): Same checkerboard pattern as left — cache reuse confirmed
- Debug logging confirmed `materials=6` (6 textured face commands), `uvCoords=8` per face
- No visual artifacts at face edges, no seams between faces
- No errors in logcat
- **Result**: **PASS**

## Acceptance Criteria Status

| # | Criterion | Status | Method | Evidence |
|---|-----------|--------|--------|----------|
| 1 | Textured prism renders with texture on each face | **MET** | Interactive device test | `verify-evidence/verify-textured-fixed.png` — checkerboard visible on all 3 visible faces of both textured prisms |
| 2 | Same texture used by 5 shapes → 1 bitmap in memory | **MET** | Code path analysis + device observation | TextureCache keyed by TextureSource (data class equals). Right prism uses identical texture to left — visually identical. Debug log shows same `BitmapSource@` reference. |
| 3 | Missing texture → checkerboard fallback, no crash | **MET** | Code path analysis | TextureLoader returns null on failure (runCatching). resolveTexture() substitutes 16×16 checkerboard. No exception path. |
| 4 | Existing flat-color behavior unchanged | **MET** | Automated tests + device test | All existing tests pass. Center prism renders flat blue. LocalMaterialDrawHook defaults to null → short-circuits to flat-color path. |

## Issues Found

### VF-1 (HIGH) — FIXED: Material data dropped in projection pipeline + hook not on default path

**Two sub-issues:**

**VF-1a:** `SceneCache` passed only 5 of 7 `RenderCommand` fields to `engine.add()` — `material`
and `uvCoords` were dropped. `SceneGraph.SceneItem` also lacked these fields. Both
`projectScene()` methods in `IsometricEngine` created output `RenderCommand`s without them.

**VF-1b:** `IsometricRenderer.renderPreparedScene()` (Compose DrawScope path, default when
`useNativeCanvas = false`) did not check for `MaterialDrawHook` or delegate to it.

**Fix applied:**
- Added `material: MaterialData?` and `uvCoords: FloatArray?` to `SceneGraph.SceneItem`,
  `SceneGraph.add()`, `SceneProjector.add()` interface, `IsometricEngine.add()` implementation
- Both `projectScene()` methods now carry `material`/`uvCoords` into output `RenderCommand`s
- `SceneCache` passes all 7 fields to `engine.add()`
- `IsometricRenderer.renderPreparedScene()` checks for material hook and delegates via
  `drawIntoCanvas { }` to get native canvas access
- Updated `isometric-core` API dump and fixed mock in `IsometricEngineProjectionTest`

**Files changed:** `SceneGraph.kt`, `SceneProjector.kt`, `IsometricEngine.kt`,
`isometric-core.api`, `IsometricEngineProjectionTest.kt`, `SceneCache.kt`,
`IsometricRenderer.kt`

## Gaps / Unverified Areas

- **Android-dependent unit tests**: TextureCache LRU eviction and affine matrix tests require
  native JNI classes not available on Paparazzi's JVM classpath. Deferred to instrumented tests.
- **Missing texture fallback visual test**: AC3 verified by code path analysis only — not
  tested interactively with an invalid asset path on device.
- **Performance under load**: Not tested with many textured shapes (>10). Synchronous
  `TextureLoader` could cause jank on first frame with many cache misses.

## Freshness Research

Not needed — all APIs are stable Android platform APIs.

## Recommendation

All 4 acceptance criteria met. VF-1 found and fixed during verification. Build, tests, and
API checks all pass post-fix. Interactive device verification confirms textured rendering
works correctly on the default config. Ready for code review.

## Recommended Next Stage
- **Option A (default):** `/wf-review texture-material-shaders canvas-textures` — all checks pass, all ACs met, ready for review
- **Option B:** `/compact` then Option A — recommended to clear verification context
