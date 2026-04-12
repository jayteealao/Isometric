---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: canvas-textures
status: complete
stage-number: 5
created-at: "2026-04-12T10:49:14Z"
updated-at: "2026-04-12T10:49:14Z"
metric-files-changed: 12
metric-lines-added: 300
metric-lines-removed: 19
metric-deviations-from-plan: 2
metric-review-fixes-applied: 0
commit-sha: "e123cb6"
tags: [canvas, texture, rendering, bitmapshader]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-canvas-textures.md
  plan: 04-plan-canvas-textures.md
  siblings: [05-implement-material-types.md, 05-implement-uv-generation.md]
  verify: 06-verify-canvas-textures.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders canvas-textures"
---

# Implement: Canvas Textured Rendering

## Summary of Changes

Implemented the `MaterialDrawHook` strategy injection pattern to enable textured Canvas
rendering without breaking the `compose → shader` dependency direction. The hook is defined
in `isometric-compose`, implemented in `isometric-shader`, and wired through the existing
`NativeSceneRenderer` draw loop.

Key additions:
- `MaterialDrawHook` fun interface + `LocalMaterialDrawHook` CompositionLocal in compose
- `TextureCache` (LRU), `TextureLoader`, `TexturedCanvasDrawHook` (BitmapShader + affine matrix) in shader
- `ProvideTextureRendering` composable for ergonomic hook installation
- `NativeSceneRenderer` delegates to hook for material-aware commands; flat-color unchanged

## Files Changed

### New files (6)
- `isometric-compose/.../runtime/MaterialDrawHook.kt`: `fun interface MaterialDrawHook` with
  `draw(nativeCanvas, command, nativePath): Boolean` + `LocalMaterialDrawHook` CompositionLocal
- `isometric-shader/.../shader/render/TextureCache.kt`: LRU cache (`LinkedHashMap`, accessOrder)
  mapping `TextureSource → CachedTexture(Bitmap, BitmapShader)`. Max 20 entries default.
- `isometric-shader/.../shader/render/TextureLoader.kt`: Loads `TextureSource → Bitmap` via
  `BitmapFactory`. Resource, Asset, BitmapSource branches. Returns null on failure.
- `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`: `MaterialDrawHook` impl.
  Resolves `IsometricMaterial.Textured` → `BitmapShader` draw. Includes `computeAffineMatrix`
  (3-point `setPolyToPoly`), `createCheckerboardBitmap` (16×16 magenta/black fallback),
  `IsoColor.toColorFilterOrNull` (multiplicative tint, null for white).
- `isometric-shader/.../shader/render/ProvideTextureRendering.kt`: Composable wrapping content
  with `CompositionLocalProvider(LocalMaterialDrawHook provides hook)`.
- `isometric-shader/src/test/.../render/TextureCacheTest.kt`: Pure-logic tests for
  `toColorFilterOrNull` (3 tests).

### Modified files (6)
- `isometric-compose/.../runtime/NativeSceneRenderer.kt`: Added `materialDrawHook` parameter
  to `renderNative()`. Per-command: if `command.material != null && hook.draw()` returns true,
  fill is handled by hook; stroke applied separately. Flat-color path unchanged.
- `isometric-compose/.../runtime/IsometricRenderer.kt`: Added `materialDrawHook` property.
  Passed to `nativeRenderer.renderNative()` in both `renderNative()` and `renderNativeFromScene()`.
- `isometric-compose/.../runtime/IsometricScene.kt`: Reads `LocalMaterialDrawHook.current`,
  wires to `renderer.materialDrawHook` via `DisposableEffect` (same pattern as `benchmarkHooks`).
- `isometric-compose/api/isometric-compose.api`: Added `MaterialDrawHook` interface,
  `LocalMaterialDrawHook` local, `materialDrawHook` property on `IsometricRenderer`.
- `isometric-shader/api/isometric-shader.api`: Added `ProvideTextureRendering` composable.
- `isometric-shader/build.gradle.kts`: Added `implementation(libs.compose.ui)` (for
  `LocalContext`), `alias(libs.plugins.paparazzi)` (for Android test classpath + future snapshots).

## Shared Files (also touched by sibling slices)
- `IsometricRenderer.kt`: Added `materialDrawHook` property (sibling slices added
  `benchmarkHooks`, `forceRebuild`, `onRenderError` — no conflict)
- `IsometricScene.kt`: Added hook wiring in existing `DisposableEffect` block
- `NativeSceneRenderer.kt`: Extended `renderNative()` signature with new parameter

## Notes on Design Choices

1. **`MaterialDrawHook` as fun interface** — same pattern as `UvCoordProvider`. Compose
   defines the socket, shader provides the plug. Zero coupling from compose to shader.

2. **Hook returns Boolean** — `true` means "I drew the fill", `false` means "I can't handle
   this, use flat color". This graceful degradation means unknown material types, missing UVs,
   or flat-color materials all fall through to the existing draw path with zero overhead.

3. **Stroke is always applied by the renderer** — the hook only handles the fill pass. This
   ensures consistent stroke behavior regardless of whether the fill is flat or textured.

4. **`computeAffineMatrix` reads `DoubleArray` directly** — no intermediate `toScreenFloats()`
   allocation. Converts `Double → Float` inline for the 3 control points.

5. **Checkerboard is lazy** — `by lazy` on `TexturedCanvasDrawHook.checkerboard` so the 16×16
   bitmap is only allocated if a texture load actually fails.

## Deviations from Plan

1. **Android-dependent tests deferred**: The plan called for `TextureCacheTest` (LRU eviction)
   and `TexturedCanvasDrawHookTest` (affine matrix, checkerboard). These require
   `android.graphics.Bitmap` and `android.graphics.Matrix` which are native JNI classes not
   available on Paparazzi's JVM classpath (Paparazzi provides `PorterDuffColorFilter` etc.
   via LayoutLib but not the native `Bitmap.createBitmap` path). Only pure-logic tests
   (`toColorFilterOrNull`) were kept as JVM tests. The bitmap/matrix tests will be added
   as instrumented tests or verified via Paparazzi snapshot tests in the verify stage.

2. **Paparazzi snapshot tests deferred to verify**: Writing Paparazzi snapshot tests that
   exercise `ProvideTextureRendering` + `IsometricScene` requires composable test infrastructure
   that's better handled in the verify stage where we can also do interactive device verification.

## Anything Deferred

- Compose `DrawScope` textured path (plan D6 — native-only for now)
- Paparazzi snapshot golden images
- Android-dependent unit tests (TextureCache LRU, affine matrix mapping)
- Interactive device verification (adb screencap)

## Known Risks / Caveats

- `TextureLoader` does synchronous I/O on first cache miss (acceptable for small tile textures)
- `LinkedHashMap` LRU is not thread-safe (OK — Canvas draw path is main-thread only)
- `PerFace` material degrades to default sub-material (full per-face rendering in later slice)
- Compose `DrawScope` path (`IsometricRenderer.renderPreparedScene`) ignores materials (flat color)

## Freshness Research
None needed — all APIs used (`BitmapShader`, `Matrix.setPolyToPoly`, `BitmapFactory`) are
stable Android platform APIs.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders canvas-textures` — verify build, tests, and interactive rendering
- **Option B:** `/compact` then Option A — recommended to clear implementation context
