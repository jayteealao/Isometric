---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: sample-demo
status: complete
stage-number: 5
created-at: "2026-04-13T08:07:24Z"
updated-at: "2026-04-13T08:07:24Z"
metric-files-changed: 4
metric-lines-added: 215
metric-lines-removed: 0
metric-deviations-from-plan: 0
metric-review-fixes-applied: 0
commit-sha: ""
tags: [sample, demo, texture, per-face]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-sample-demo.md
  plan: 04-plan-sample-demo.md
  siblings: [05-implement-material-types.md, 05-implement-uv-generation.md, 05-implement-canvas-textures.md, 05-implement-webgpu-textures.md, 05-implement-per-face-materials.md]
  verify: 06-verify-sample-demo.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders sample-demo"
---

# Implement: Sample App Demo — Textured Scene

## Summary of Changes

Added a textured demo activity to the sample app showing a 4x4 grid of unit-cube Prisms
with grass-coloured tops and dirt-coloured sides, using the `perFace {}` material DSL.
The scene supports Canvas, Canvas + GPU Sort, and Full WebGPU render modes via a
`TogglePill` toggle row.

## Files Changed

### New files (2)
- `app/src/main/kotlin/.../sample/TextureAssets.kt`: Internal object with two `lazy`
  properties (`grassTop`, `dirtSide`) — procedurally generated 64x64 ARGB_8888 bitmaps.
  `grassTop` has green base + highlight stripe + dark-green noise dots. `dirtSide` has
  brown base + alternating horizontal bands + dark gravel flecks. Deterministic RNG seeds
  for reproducibility.

- `app/src/main/kotlin/.../sample/TexturedDemoActivity.kt`: `ComponentActivity` with
  `FLAG_KEEP_SCREEN_ON`. Contains `TexturedDemoScreen` (render mode state + info card +
  toggle row), `TexturedPrismGridScene` (4x4 `ForEach` loop wrapped in
  `ProvideTextureRendering`), and a local `TogglePill` composable (copied from
  `WebGpuSampleActivity`).

### Modified files (2)
- `app/src/main/AndroidManifest.xml`: Added `<activity>` entry for `.TexturedDemoActivity`
  with label "Textured Materials".

- `app/src/main/kotlin/.../sample/MainActivity.kt`: Added `SampleCard` for "Textured
  Materials" after the "Interaction API" card.

## Shared Files (also touched by sibling slices)

- `MainActivity.kt` — not previously touched by sibling slices (no conflict).
- `AndroidManifest.xml` — not previously touched by sibling slices (no conflict).

## Notes on Design Choices

- **Procedural bitmaps over resources:** Keeps the slice self-contained with no external
  PNG files. Deterministic `Random` seeds mean identical output across runs.
- **`TogglePill` copied, not extracted:** Same as plan — acceptable for demo code. Both
  `WebGpuSampleActivity` and `TexturedDemoActivity` have their own private copy.
- **`remember { perFace { ... } }`:** Material is created once and shared across all 16
  prisms. The underlying `TextureAssets.grassTop`/`.dirtSide` are `lazy val` — bitmaps
  allocated at most once per process.
- **`ProvideTextureRendering` wraps the scene:** Required for Canvas mode to install the
  `TexturedCanvasDrawHook` + `TextureCache`.
- **`enableBroadPhaseSort = true`:** Matches sibling scenes for consistent depth sorting.

## Deviations from Plan

None. Implementation follows plan rev 2 exactly.

## Anything Deferred

- Interactive smoke test on device (verify stage)
- WebGPU backend status pill in info card (plan noted it as optional; omitted for simplicity)

## Known Risks / Caveats

- WebGPU mode may show "Failed" status on devices without Vulkan/WebGPU layer — expected
  behavior, falls back to Canvas.
- `TogglePill` duplication with `WebGpuSampleActivity` — acceptable for demo code.

## Freshness Research

No external dependency changes. All APIs verified current in pre-implementation exploration.

## Recommended Next Stage
- **Option A (default):** `/wf-verify texture-material-shaders sample-demo` — verify build, tests, and on-device rendering
- **Option B:** `/compact` then Option A — clear implementation context first (recommended)
