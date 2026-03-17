# Animated Documentation Assets

> **Created**: 2026-03-17
> **Status**: Planning
> **Scope**: `:app` sample module + `site/` + shell tooling

---

## Goal

Produce transparent-background animated WebP files from the sample app's wave and animation scenes. Use them as the landing page hero on the docs site and as inline illustrations on guide and example pages.

---

## Current State

| Asset type | Location | Count | Animated? | Transparency? |
|------------|----------|-------|-----------|---------------|
| Static PNGs | `site/public/screenshots/`, `docs/assets/screenshots/` | 16 | No | No |
| Maestro screenshots | `.maestro/screenshots/` (local only) | ~12 | No | No |
| GIFs / WebPs | — | 0 | — | — |

The docs site landing page (`site/src/content/docs/index.mdx`) currently has no visual content — the Starlight hero block has text only. Guide and example pages reference static PNGs or nothing at all.

---

## Output Format: Animated WebP

| Format | Alpha | Colours | Browser support | Notes |
|--------|-------|---------|-----------------|-------|
| Animated WebP | ✓ 8-bit | 24-bit | All modern | Best size/quality ratio for this content |
| APNG | ✓ 8-bit | 24-bit | All modern | 20–30% larger than WebP for this content |
| GIF | ✓ 1-bit | 256 | Universal | Colour banding visible on the gradient shading |

Animated WebP is the right choice. Provide a static PNG fallback for the `<picture>` tag in the MDX files for any browser edge case.

---

## Technical Approach

### Why `GraphicsLayer.record` + `toImageBitmap()`

Screen recording (`adb screenrecord`, `scrcpy`) captures the full composited screen — the system UI background is always present and alpha is lost. Bitmap frame export bypasses the compositor entirely: the `GraphicsLayer` renders directly to an `ARGB_8888` bitmap whose background is initialized to `Color.Transparent`. The result is a PNG with true alpha for every pixel the isometric engine did not paint.

The isometric `Canvas` composable does not fill its background by default — it only draws shape faces. This means the scene background is naturally transparent in a `GraphicsLayer` capture without any extra configuration.

### Architecture

Three changes to `:app`, plus a shell script:

```
RecordingController   — drives fixed-timestep state, coordinates capture lifecycle
RecordingOverlay      — UI button to start/stop recording, frame counter readout
PerformanceSample     — extended with a RecordingController parameter
AnimationSample       — same
site/scripts/         — assemble-animations.sh (ffmpeg)
```

`RecordingController` is pure logic — no Compose dependency. `RecordingOverlay` and the sample extensions are Compose-layer concerns.

### Fixed-Timestep Driver

Real-time animation via `withFrameNanos` produces inconsistent inter-frame deltas depending on device load, which assembles into judder in the output. Recording replaces the real-time clock with a counter:

```
currentTime = frameIndex × (1.0 / outputFps)
```

The animation composable reads `currentTime` from the controller. During playback mode, `currentTime` accumulates via `withFrameNanos` as normal. During recording mode, `currentTime` is stepped deterministically by the controller.

### Capture Loop

```
LaunchedEffect(isRecording) {
    if (!isRecording) return

    repeat(totalFrames) { frameIndex ->
        controller.stepTo(frameIndex)       // updates currentTime state
        withFrameNanos { }                  // wait for Compose to lay out + draw the new frame
        val bitmap = graphicsLayer.toImageBitmap()
        savePng(context, bitmap, frameIndex)
    }

    controller.stopRecording()
}
```

`withFrameNanos { }` with an empty body is the correct way to yield until the next vsync frame has been rendered. It ensures the `graphicsLayer` holds the new frame content before `toImageBitmap()` is called.

### GraphicsLayer Wiring

The composable to be captured wraps its content using `Modifier.drawWithContent`:

```
val graphicsLayer = rememberGraphicsLayer()

Box(
    modifier = Modifier.drawWithContent {
        graphicsLayer.record { this@drawWithContent.drawContent() }
        drawLayer(graphicsLayer)
    }
) {
    IsometricScene(modifier = Modifier.size(recordingSize)) {
        // scene content driven by controller.currentTime
    }
}
```

`graphicsLayer.record { drawContent() }` fires synchronously inside `drawWithContent`, capturing the exact pixels drawn for the current frame into the layer before they reach the screen. `toImageBitmap()` can then be called immediately after the frame completes.

### Frame Storage

Frames are saved to `Context.getExternalFilesDir("recording")` as `frame_%04d.png`. This directory is accessible via ADB without root. Total storage for a 3-second capture at 30 fps at 500 × 500 px is approximately 90 frames × ~300 KB = ~27 MB uncompressed. The directory is cleared at the start of each recording.

---

## Scenes to Record

| Scene | Source composable | Duration | fps | Output size | Notes |
|-------|-------------------|----------|-----|-------------|-------|
| Wave grid | `PerformanceSample` (grid size 8) | 3 s | 30 | 500 × 500 | Primary landing page asset |
| Animation tower | `AnimationSample` | 3 s | 30 | 400 × 500 | For animation guide page |
| Hierarchy rotation | `HierarchySample` | 4 s | 30 | 500 × 500 | For scene graph concepts page |

Grid size 8 (8×8 = 64 prisms) is the right default for the wave: visually rich, renders well at 500 px, and distinguishable from the performance benchmark framing in the benchmarks guide.

The wave grid animation period is `2π ≈ 6.28 s` at the current `wave += 0.05` per frame rate. A 3-second clip at 30 fps captures slightly less than a full wave cycle — this is intentional. A perfect cycle loops too mechanically; a slightly offset loop creates natural-feeling motion when the WebP loops.

To get a seamless loop, record `ceil(2π / 0.05 / 30) × 30 = 126` frames (one full period at 30 fps) rather than a flat 90 frames. The ffmpeg step handles the rest.

---

## Shell Script: `scripts/assemble-animations.sh`

Inputs: a directory of PNGs pulled from the device.
Output: animated WebPs in `site/public/animations/`, static PNG fallbacks.

Steps the script performs:
1. Pull frames from device: `adb pull /sdcard/Android/data/<pkg>/files/recording/ ./frames/`
2. Crop to content bounds using ffmpeg's `cropdetect` filter (removes transparent margin)
3. Resize to target width (500 px wide, height auto) with `lanczos` resampling
4. Encode animated WebP: `ffmpeg -framerate 30 -i frame_%04d.png -c:v libwebp -quality 80 -loop 0 output.webp`
5. Extract first frame as static PNG fallback: `ffmpeg -i output.webp -frames:v 1 output-static.png`
6. Copy both to `site/public/animations/<scene-name>/`

One invocation per scene — the script takes the scene name as an argument and pulls from the corresponding subdirectory on the device.

`ffmpeg` and `adb` must be on `PATH`. No other dependencies.

---

## Docs Site Integration

### Asset Location

```
site/public/animations/
  wave-grid/
    wave-grid.webp          # animated, ~500 × 500, ~200 KB target
    wave-grid-static.png    # first frame fallback
  animation-tower/
    animation-tower.webp
    animation-tower-static.png
  hierarchy/
    hierarchy.webp
    hierarchy-static.png
```

Files in `site/public/` are served at `/Isometric/animations/...` with no transformation by the Astro build.

### Landing Page Hero

Starlight's `hero` block in `astro.config.mjs` accepts an `image` field. Add:

```js
starlight({
  ...
  components: {
    Hero: './src/components/AnimatedHero.astro'
  }
})
```

A custom `AnimatedHero.astro` overrides the default Hero to use a `<picture>` tag with the animated WebP as the primary source and the static PNG as the fallback. This is preferable to passing the animated WebP directly as `hero.image.src` because Starlight's default Hero component uses `<img>` without explicit `loading` attributes and doesn't support `<picture>` source sets.

### Guide and Example Pages

In MDX files, reference animations directly:

```mdx
<picture>
  <source srcset="/Isometric/animations/wave-grid/wave-grid.webp" type="image/webp" />
  <img
    src="/Isometric/animations/wave-grid/wave-grid-static.png"
    alt="Animated 8×8 wave grid — shape heights oscillate via sin"
    width="500"
  />
</picture>
```

Pages that benefit from inline animations:
- `guides/animation.mdx` — wave grid + animation tower
- `examples/animation-patterns.mdx` — wave grid
- `getting-started/quickstart.mdx` — simple animated cube (existing rotating octahedron sample)
- `concepts/scene-graph.mdx` — hierarchy rotation
- `guides/performance.mdx` — full 15×15 grid (performance demo, recorded separately at lower fps)

---

## Documentation Example Catalog

Every code example across the docs site, with the planned visual asset for each.

### Asset Type Key

| Code | Meaning | Pipeline |
|------|---------|----------|
| `animated-webp` | Looping animated WebP | `RecordingController` fixed-timestep |
| `static-png` | Single still frame | `RecordingController` frame 0 (no assembly step) |
| `interactive-webp` | Scripted state-change loop | `RecordingController` + timed state machine (no gesture replay) |
| `code-only` | No visual asset | — |

**`interactive-webp` note**: gesture-driven examples (tap, drag) are visualised as timed state loops. The recording composable drives state changes via `RecordingController.currentTime` thresholds (e.g., `showRoof = (frameIndex / 60) % 2 == 0`) rather than replaying pointer events. The result is a looping WebP that illustrates the before/after states.

---

### getting-started/quickstart.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Single prism at origin | Static | `static-png` | 400 × 400 | Hero scenario — the simplest possible scene |
| Three prisms at different positions | Static | `static-png` | 500 × 400 | Demonstrates positional composition |
| Group with two prisms | Static | `static-png` | 400 × 400 | Hierarchy intro |
| Tap to change color | Interactive | `interactive-webp` | 400 × 400 | Loop: blue → random color → blue; 2 s cycle |

---

### getting-started/coordinate-system.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Prism at `Point.ORIGIN` | Static | `static-png` | 400 × 400 | Coordinate axes reference |

Projection formula blocks are prose — no visual asset needed.

---

### guides/shapes.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Shape catalog (6 shapes) | Static | `static-png` × 6 | 300 × 300 each | **Already exist** as `screenshots/shape-*.png` — no new capture |
| `Shape` composable with all params | Static | `static-png` | 400 × 400 | Shows `position`, `rotation`, `scale`, `visible` |
| `translate` | Static | `static-png` | 450 × 350 | Before/after side-by-side or single "after" frame |
| `scale` | Static | `static-png` | 400 × 350 | Stretched X, compressed Z |
| `rotateZ` | Static | `static-png` | 400 × 400 | 45-degree rotation |
| `Shape.extrude` before/after | Static | `static-png` × 2 | 400 × 350 each | **Already exist** as `screenshots/extrude-*.png` — no new capture |

---

### guides/animation.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `SpinningScene` — rotating octahedron | Animated | `animated-webp` | 400 × 400 | Record one full rotation (~`2π / 0.02 / 30 = 10.5` s at 30 fps, or use 6 s / 180 frames for a smooth loop) |
| `WaveScene` — 5×5 sin grid | Animated | `animated-webp` | 500 × 500 | Same seamless-loop logic as the hero wave; use 126 frames for one full `2π` period |

---

### guides/gestures.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Basic `onTap` handler | Interactive | `interactive-webp` | 400 × 400 | Single prism; loop: neutral → "tapped" highlight → neutral |
| `InteractiveScene` — tap color + drag pan | Interactive | `interactive-webp` | 500 × 500 | Loop: color changes on 3 shapes sequentially + subtle camera pan |

---

### guides/camera.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Basic `CameraState` setup | Static | `static-png` | 500 × 400 | Static scene showing a camera-offset view |
| Drag-to-pan | Interactive | `interactive-webp` | 500 × 400 | Loop: slow smooth pan left/right across the grid |
| `AnimatedCameraScene` — Compose animation pan | Animated | `animated-webp` | 500 × 400 | Driven by `animateDoubleAsState`; record the tween as a fixed-timestep loop |

---

### guides/theming.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `IsoColor` constructors | — | `code-only` | — | Color value snippets, no scene |
| `lighten()` | — | `code-only` | — | Color math snippet |
| Compose interop | — | `code-only` | — | `toComposeColor()` snippet |
| `CompositionLocalProvider` — green light | Static | `static-png` | 400 × 400 | One prism with top-down green lighting, no stroke |
| `StrokeStyle.FillOnly` — cylinder | Static | `static-png` | 400 × 400 | Cylinder with flat fill, edges suppressed |

---

### guides/custom-shapes.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `Path` composable — triangle face | Static | `static-png` | 400 × 350 | Red flat triangle in 3D space |
| `Shape.extrude` — triangular prism | Static | `static-png` | 400 × 400 | Same geometry as shapes.mdx extrude; reuse that asset |
| `CustomNode` — custom triangle | Static | `static-png` | 400 × 400 | Red triangle face via raw `RenderCommand` |

---

### guides/performance.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `RenderOptions` tuning | Static | `static-png` | 400 × 400 | Simple prism scene showing config in action |
| `useNativeCanvas = true` | Static | `static-png` | 400 × 400 | Same scene as above — annotate caption, not a different screenshot |
| Path caching — 10 prisms in row | Static | `static-png` | 600 × 350 | Row of 10 prisms |
| Spatial index tuning | — | `code-only` | — | Config snippet, no distinct visual |
| `frameVersion` + force re-render | Interactive | `interactive-webp` | 400 × 400 | Loop: scene → increment version → brief rebuild flash |
| Performance wave (15×15 grid) | Animated | `animated-webp` | 700 × 600 | Full `PerformanceSample` at grid size 15; referenced in performance guide; recorded separately at 24 fps |

---

### guides/advanced-config.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `DebugScene` — `onAfterDraw` grid overlay | Static | `static-png` | 500 × 450 | Two prisms with translucent grid drawn over |
| Error handling — `onRenderError` | — | `code-only` | — | Logging snippet |
| `EngineAccessScene` — `onEngineReady` | Static | `static-png` | 400 × 400 | Prism + text showing projection version |
| Custom engine — `angle = PI/4` | Static | `static-png` | 400 × 400 | Prism with steeper 45° projection vs default |
| `MockProjector` | — | `code-only` | — | Testing code |
| `CacheControlScene` — `frameVersion` button | Interactive | `interactive-webp` | 400 × 400 | Loop: scene renders → version increments visually |

---

### concepts/scene-graph.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Dirty tracking — 3 prisms, one changes color | Interactive | `interactive-webp` | 500 × 400 | Loop: one prism cycles color, others stay constant; illustrates per-node isolation |
| High-level API — `Group` with `Shape` | Static | `static-png` | 400 × 400 | One group, one prism |
| `ComposeNode` direct access | — | `code-only` | — | Low-level internals |
| Standalone engine usage | — | `code-only` | — | No Compose context |
| `RenderContext` in `CustomNode` | — | `code-only` | — | Reference snippet |
| Hierarchy rotation — `HierarchySample` | Animated | `animated-webp` | 500 × 500 | **Primary scene** — already listed in Scenes to Record table above |

---

### concepts/depth-sorting.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `broadPhaseCellSize` tuning — 20×20 grid | Static | `static-png` | 600 × 500 | Dense grid showing correct overlap |
| `RenderOptions.NoDepthSorting` — 2 prisms | Static | `static-png` | 450 × 350 | Pair of overlapping prisms in declaration order |
| `enableBroadPhaseSort = false` | — | `code-only` | — | Debug flag snippet |

---

### concepts/rendering-pipeline.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| Cache validity code | — | `code-only` | — | Boolean expression |
| `onPreparedSceneReady` — prism | Static | `static-png` | 400 × 400 | Prism with command count text overlay |
| Path caching — 50 prisms | Static | `static-png` | 600 × 400 | 5×10 grid |
| `useNativeCanvas` on Android | — | `code-only` | — | Same config note as performance.mdx |
| `ExportableScene` — 2 shapes + scene capture | Static | `static-png` | 450 × 400 | Prism + pyramid with command count text |

---

### reference/composables.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `Batch` — row of 10 buildings | Static | `static-png` | 650 × 350 | 10 prisms via single `Batch` call |
| `If` — conditional pyramid roof | Interactive | `interactive-webp` | 400 × 450 | Loop: pyramid present → absent → present; 2 s cycle |
| `ForEach` — buildings with key | Static | `static-png` | 500 × 350 | Small grid of buildings |

---

### reference/scene-config.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `AdvancedSceneConfig` combined | Static | `static-png` | 400 × 400 | Prism with red overlay from `onAfterDraw` |

---

### reference/composition-locals.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `ThemedScene` — green top-down no-stroke | Static | `static-png` | 500 × 400 | 2 prisms: flat green with no edge lines |
| `MultiThemeScene` — 3 colour subtrees | Static | `static-png` | 550 × 400 | 5 prisms: default / red subtree / green subtree |
| `CoordinateDisplay` — `worldToScreen` | Static | `static-png` | 400 × 400 | Prism + text showing screen coordinate |
| `PaletteDemo` — 3 palette-colored prisms | Static | `static-png` | 500 × 400 | primary / accent / surface colours side by side |
| Custom dark palette | — | `code-only` | — | Color construction snippet |

---

### reference/engine.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `projectionVersion` | — | `code-only` | — | Counter snippet |
| Direct engine usage | — | `code-only` | — | No composable; server-side scenario |

---

### examples/basic-scenes.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `SimpleCube` | Static | `static-png` | 400 × 400 | 2×2×2 blue prism |
| `Building` — platform + tower + roof | Static | `static-png` | 500 × 500 | Classic isometric building |
| `ShapeGrid` — 3×3 colour gradient grid | Static | `static-png` | 550 × 450 | Colour varies by x/y index |
| `StaircaseScene` | Static | `static-png` | 500 × 450 | Stairs shape + landing platform |
| `GroupedScene` — base + column + cap | Static | `static-png` | 450 × 450 | Hierarchical structure |

---

### examples/animation-patterns.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `SpinningOctahedron` | Animated | `animated-webp` | 400 × 400 | Purple octahedron full rotation; 180 frames at 30 fps = one clean loop |
| `WaveGrid` — 5×5 | Animated | `animated-webp` | 500 × 450 | Same recording as guides/animation.mdx WaveScene; reuse asset |
| `ColorCycling` | Animated | `animated-webp` | 400 × 400 | Single prism cycling through hues; one full `2π` period = 314 frames at 30 fps (≈10 s) — or record 6 s / 180 frames for a shorter demonstrative loop |
| `PulsingScale` | Animated | `animated-webp` | 400 × 400 | Prism scaling up/down; one full sin period = 209 frames at 30 fps; record 120 frames (4 s) for visual loop |
| `OrbitingShapes` | Animated | `animated-webp` | 500 × 450 | 3 cubes orbiting; one full orbit = `2π / 0.02 / 30 = 10.5` s — record 6 s / 180 frames |
| `StaggeredEntrance` | Animated | `animated-webp` | 550 × 400 | 4 cubes sliding in; record full entrance sequence then hold for 1 s; 120 frames total |

---

### examples/interactive-scenes.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `TapToHighlight` — 3 prisms | Interactive | `interactive-webp` | 500 × 400 | Loop: each prism highlights in turn then resets; 3 s cycle |
| `DragToPan` — 8×8 grid | Interactive | `interactive-webp` | 600 × 500 | Loop: smooth camera pan left then right; drives `CameraState` via `RecordingController.currentTime` |
| `TapToAdd` — dynamic shapes | Interactive | `interactive-webp` | 500 × 500 | Loop: shapes appear on platform one by one (scripted), then clear and repeat |
| `ShapeInfoDisplay` — 3 shapes + text | Interactive | `interactive-webp` | 500 × 550 | Loop: text updates as each shape is "tapped" in sequence |
| `InteractiveBuilding` — toggleable roof | Interactive | `interactive-webp` | 450 × 500 | Loop: roof present → absent → present; 2 s cycle |

---

### examples/advanced-patterns.mdx

| Example | Type | Asset | Size | Notes |
|---------|------|-------|------|-------|
| `TowerDemo` — 3 towers | Static | `static-png` | 550 × 500 | Three towers of different heights |
| `CustomGroundPlane` + prism | Static | `static-png` | 500 × 450 | Gray ground plane beneath a blue prism |
| `BatchGrid` — 10×10 | Static | `static-png` | 650 × 500 | 100 prisms via single `Batch` node |
| `RenderOptionsDemo` — sorted vs unsorted groups | Static | `static-png` | 650 × 400 | Left: sorted overlapping shapes; right: unsorted row |
| `LifecycleHooksDemo` — gradient + border | Static | `static-png` | 450 × 450 | Prism on sky-blue gradient bg, red debug border |
| Direct engine usage | — | `code-only` | — | No composable |

---

### faq.mdx

All code blocks are one-liner import alias and error-fix snippets. No visual assets needed.

---

### Production Summary

| Asset type | Count | Notes |
|------------|-------|-------|
| `animated-webp` | 15 | Unique animations (some pages share the same source recording) |
| `static-png` (new) | 40 | Scenes not already covered by existing `screenshots/` |
| `static-png` (existing) | 8 | Already in `site/public/screenshots/` — no new work |
| `interactive-webp` | 14 | Scripted state-machine loops |
| `code-only` | 22 | No visual asset |

**Deduplication opportunities:**
- `SpinningScene` (animation.mdx) = `SpinningOctahedron` (animation-patterns.mdx) — same recording, one asset used in both pages
- `WaveScene` (animation.mdx) = `WaveGrid` (animation-patterns.mdx) — same recording
- `Shape.extrude` (shapes.mdx) = `Shape.extrude` (custom-shapes.mdx) — reuse existing screenshot
- `useNativeCanvas` (performance.mdx) = `useNativeCanvas` (rendering-pipeline.mdx) — same static PNG

After deduplication: **approximately 55 unique visual assets** to produce (15 animated WebPs + 34 net-new static PNGs + 14 interactive WebPs, minus ~8 reuses).

---

### Recording Composables Needed

In addition to the three primary samples (`PerformanceSample`, `AnimationSample`, `HierarchySample`), the following dedicated recording composables are needed inside `:app`:

| Composable | Scene | Pages |
|------------|-------|-------|
| `QuickstartSample` | Single prism / three prisms / group / tap | quickstart.mdx |
| `ShapeTransformSample` | Translate, scale, rotateZ | shapes.mdx |
| `ThemingSample` | Green light + FillOnly cylinder | theming.mdx |
| `CustomShapeSample` | Path face + CustomNode triangle | custom-shapes.mdx |
| `PerformanceStaticSample` | 10-prism row, 50-prism grid, 20×20 dense grid | performance.mdx, rendering-pipeline.mdx |
| `AdvancedConfigSample` | Grid overlay, custom engine angle, palette demo | advanced-config.mdx |
| `SceneGraphSample` | Group + hierarchy + dirty tracking loop | concepts/scene-graph.mdx |
| `DepthSortingSample` | Dense 20×20, insertion-order pair | depth-sorting.mdx |
| `BasicScenesSample` | Simple cube, building, grid, staircase, grouped | basic-scenes.mdx |
| `AnimationPatternsSample` | Color cycling, pulsing scale, orbiting, staggered | animation-patterns.mdx |
| `InteractiveScenesSample` | All 5 interactive examples (scripted loops) | interactive-scenes.mdx |
| `AdvancedPatternsSample` | Towers, ground plane, batch grid, render options, lifecycle hooks | advanced-patterns.mdx |

Each composable accepts an optional `RecordingController` parameter following the same pattern as `PerformanceSample` and `AnimationSample`.

---

## File Changes

| File | Change |
|------|--------|
| `app/src/.../RecordingController.kt` | **new** — fixed-timestep driver, frame counter, storage I/O |
| `app/src/.../RecordingOverlay.kt` | **new** — Compose UI: record button, frame counter badge, stop button |
| `app/src/.../PerformanceSample.kt` | **modified** — accept optional `RecordingController`; swap `currentTime` source when recording |
| `app/src/.../AnimationSample.kt` | **modified** — same pattern |
| `app/src/.../HierarchySample.kt` | **modified** — same pattern |
| `scripts/assemble-animations.sh` | **new** — ffmpeg pipeline: pull → crop → resize → encode WebP → extract fallback |
| `site/public/animations/` | **new directory** — animated WebPs + fallback PNGs (committed) |
| `site/src/components/AnimatedHero.astro` | **new** — custom Starlight Hero override with `<picture>` tag |
| `site/astro.config.mjs` | **modified** — register `AnimatedHero` component override, add `hero.tagline` and `hero.actions` |
| `site/src/content/docs/index.mdx` | **modified** — fill in hero content, add wave grid WebP |
| Guide/example `.mdx` files (6) | **modified** — add `<picture>` blocks at appropriate points |

---

## Completion Criteria

- `RecordingController.stepTo(n)` produces pixel-identical output to real-time playback at the same simulation time
- 90-frame wave grid capture completes without dropped frames on a mid-range device
- Resulting WebP is under 300 KB and loops seamlessly
- All static PNG fallbacks render correctly when WebP is blocked in a browser
- Landing page hero shows the animated wave grid
- Starlight `npm run build` completes with no errors after adding the animated assets
- The static PNG files in `docs/assets/screenshots/` are unchanged (the existing README shape table still works)
