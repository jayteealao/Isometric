---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: sample-demo
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:49:12Z"
metric-files-to-touch: 8
metric-step-count: 10
has-blockers: false
revision-count: 1
tags: [sample, demo]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-sample-demo.md
  siblings: [04-plan-material-types.md, 04-plan-uv-generation.md, 04-plan-canvas-textures.md, 04-plan-webgpu-textures.md, 04-plan-per-face-materials.md]
  implement: 05-implement-sample-demo.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders sample-demo"
---

# Plan: Sample App Demo — Textured Scene

## Context

This is the final slice of the `texture-material-shaders` workflow. All preceding slices
(`material-types`, `uv-generation`, `canvas-textures`, `webgpu-textures`, `per-face-materials`)
must be complete before this slice is implemented.

The sample app (`app/`) is a Compose-based application with a `MainActivity` that shows a
`SampleCard` list. Each card launches a separate `ComponentActivity`. The pattern used by
all existing activities is:

- `Activity` class in `app/src/main/kotlin/io/github/jayteealao/isometric/sample/`
- Compose UI set via `setContent { MaterialTheme { Surface { ... } } }`
- `ScrollableTabRow` for multi-demo navigation within an activity (pattern from
  `ComposeActivity`, `WebGpuSampleActivity`, `RuntimeApiActivity`)
- Tabs navigate a `when (selectedTab)` inside `Box(modifier = Modifier.weight(1f))`

The render-mode toggle used by `AnimatedTowersBackendSample` in `WebGpuSampleActivity`
(three `TogglePill` buttons wired to a `renderMode` state var) is the exact pattern to
reuse for the Canvas / WebGPU toggle in this slice.

## Texture Assets

### Source

Both textures will be procedurally generated as `android.graphics.Bitmap` objects in
a helper object (`TextureAssets`) so that no external PNG files are required. This keeps
the slice self-contained and avoids licensing questions.

If the project later wants real pixel art textures, the assets can be dropped into
`app/src/main/res/drawable-nodpi/` as PNG files and the `TextureSource.Resource(R.drawable.xxx)`
variant can be used. The code structure shown below makes that a one-line swap per call site.

### Procedural Bitmap Recipes

**`grass_top` (64×64 px)**
- Base fill: `Color.rgb(106, 168, 79)` — medium green
- Noise layer: 8×8 randomly placed darker green dots (`Color.rgb(61, 133, 18)`) to
  simulate grass blades
- Lighter highlight stripe across the top third (`Color.rgb(147, 196, 125)`) for
  isometric top-face shading
- Generated once, cached as a lazy val in `TextureAssets`

**`dirt_side` (64×64 px)**
- Base fill: `Color.rgb(162, 113, 61)` — mid-brown
- Horizontal band variation: alternate rows in slightly lighter/darker brown
  (`Color.rgb(180, 130, 75)` / `Color.rgb(140, 96, 50)`) every 8px
- Small random dark flecks (`Color.rgb(101, 67, 33)`) for gravel texture
- Generated once, cached as a lazy val in `TextureAssets`

Both bitmaps use `Bitmap.Config.ARGB_8888`.

### File Location

```
app/src/main/kotlin/io/github/jayteealao/isometric/sample/TextureAssets.kt
```

```kotlin
internal object TextureAssets {
    val grassTop: Bitmap by lazy { buildGrassTop() }
    val dirtSide: Bitmap by lazy { buildDirtSide() }

    private fun buildGrassTop(): Bitmap { ... }
    private fun buildDirtSide(): Bitmap { ... }
}
```

## New Activity

### File

```
app/src/main/kotlin/io/github/jayteealao/isometric/sample/TexturedDemoActivity.kt
```

### Registration in AndroidManifest.xml

```xml
<activity
    android:name=".TexturedDemoActivity"
    android:label="Textured Materials"
    android:exported="true" />
```

### Entry Point in MainActivity

Add a `SampleCard` in `MainActivity.SampleChooser` after the existing "Interaction API"
card:

```kotlin
SampleCard(
    title = "Textured Materials",
    description = "Per-face BitmapShader (grass top, dirt sides) in Canvas and WebGPU modes",
    onClick = { onSelect(Intent(this@MainActivity, TexturedDemoActivity::class.java)) }
)
```

## Screen Structure

`TexturedDemoActivity` hosts a single composable `TexturedDemoScreen` that contains:

1. A `TexturedDemoInfoCard` showing the current render mode status (reuse
   `WebGpuExplainerCard`-style card from `WebGpuSampleActivity`)
2. A render mode selector row: three `TogglePill` buttons — **Canvas**, **Canvas + GPU Sort**,
   **WebGPU** — wired to a `renderMode: RenderMode` state var (same pattern as
   `AnimatedTowersBackendSample`)
3. A `Box(modifier = Modifier.weight(1f))` containing the `IsometricScene`

```
TexturedDemoScreen
├── TexturedDemoInfoCard          ← render mode name + WebGPU backend status pill
├── Row (TogglePill × 3)          ← Canvas / Canvas+GPU Sort / WebGPU
└── Box(weight=1f)
    └── TexturedPrismGridScene(renderMode)
```

## Scene Composition

### `TexturedPrismGridScene`

A 4×4 grid of textured `Prism` shapes (16 total), centred at isometric origin.

```
grid size : 4 columns × 4 rows
spacing   : 1.05 (slight gap between tiles to make individual shapes legible)
prism size: width=1.0, depth=1.0, height=1.0 (unit cubes)
positions : (col - 1.5) * spacing, (row - 1.5) * spacing, 0.0
```

Material applied to every prism:

```kotlin
val grassMat = textured(TextureSource.Bitmap(TextureAssets.grassTop))
val dirtMat  = textured(TextureSource.Bitmap(TextureAssets.dirtSide))

val tileMaterial = perFace {
    top    = grassMat
    sides  = dirtMat
    // bottom unset → uses default (falls back to dirtMat via `default = dirtMat`)
    default = dirtMat
}
```

Each of the 16 prisms uses the same `tileMaterial` instance — this exercises the shared
texture cache path (the bitmaps should be loaded once and reused).

### `IsometricScene` config

```kotlin
IsometricScene(
    modifier = Modifier.fillMaxSize(),
    config = SceneConfig(
        renderOptions = RenderOptions.Default.copy(enableBroadPhaseSort = true),
        renderMode = renderMode,      // passed in from toggle state
        useNativeCanvas = false,
        gestures = GestureConfig.Disabled,
    )
)
```

### `ForEach` loop structure

```kotlin
ForEach((0 until 4).toList()) { col ->
    ForEach((0 until 4).toList()) { row ->
        Shape(
            geometry = Prism(
                position = Point(
                    (col - 1.5) * 1.05,
                    (row - 1.5) * 1.05,
                    0.0
                ),
                width = 1.0,
                depth = 1.0,
                height = 1.0
            ),
            material = tileMaterial
        )
    }
}
```

## Files to Touch

| # | File | Change |
|---|------|--------|
| 1 | `app/src/main/kotlin/.../sample/TextureAssets.kt` | **NEW** — procedural grass + dirt bitmaps |
| 2 | `app/src/main/kotlin/.../sample/TexturedDemoActivity.kt` | **NEW** — activity + screen composables |
| 3 | `app/src/main/AndroidManifest.xml` | Add `TexturedDemoActivity` entry |
| 4 | `app/src/main/kotlin/.../sample/MainActivity.kt` | Add `SampleCard` entry |
| 5 | `app/src/main/res/values/strings.xml` | Add string `textured_demo_label` (optional, manifest uses inline label) |

Optional (if snapshot tests are added):

| 6 | `app/src/test/.../TexturedDemoScreenshotTest.kt` | **NEW** — Paparazzi canvas-mode snapshot |
| 7 | `app/src/test/.../TextureAssetsTest.kt` | **NEW** — unit test: bitmap dimensions, non-null pixels |

## Step-by-Step Implementation

### Step 0 — Add `isometric-shader` dependency to the app module

**File to modify:** `app/build.gradle.kts`

The app module currently depends on `:isometric-compose` and `:isometric-webgpu` but NOT
on `:isometric-shader`. The textured demo uses the shader module's overloaded `Shape()`
composable, `textured()`, `perFace {}` DSL, and `TextureSource`. Add:

```kotlin
implementation(project(":isometric-shader"))
```

**Imports needed in `TexturedDemoActivity.kt`:**
```kotlin
import io.github.jayteealao.isometric.shader.Shape  // overloaded composable
import io.github.jayteealao.isometric.shader.textured
import io.github.jayteealao.isometric.shader.texturedBitmap
import io.github.jayteealao.isometric.shader.perFace
import io.github.jayteealao.isometric.shader.TextureSource
```

### Step 1 — Texture assets helper

Create `TextureAssets.kt`. Implement `buildGrassTop()` and `buildDirtSide()` using the
bitmap recipes above. Both methods allocate a 64×64 ARGB_8888 bitmap, fill with
`Canvas.drawColor`, then paint noise/stripe overlays using `Canvas.drawRect` and
`Canvas.drawCircle` with `Random`. No external resources needed.

Acceptance check: `TextureAssets.grassTop.width == 64 && TextureAssets.grassTop.height == 64`

### Step 2 — `TexturedDemoActivity` skeleton

Create the activity file. Wire `ComponentActivity.onCreate` → `setContent` →
`MaterialTheme` → `Surface` → `TexturedDemoScreen`. Keep screen on with
`window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` (matches
`WebGpuSampleActivity` to prevent Vulkan surface loss during manual testing).

### Step 3 — Render mode toggle state

Inside `TexturedDemoScreen`, declare:

```kotlin
var renderMode by remember { mutableStateOf<RenderMode>(RenderMode.Canvas()) }
```

Render the three `TogglePill` buttons (reuse or copy from `WebGpuSampleActivity`).
The `TogglePill` composable can be lifted to a shared `SampleUiComponents.kt` if both
activities use it, or simply copied — copy is acceptable for demo code.

### Step 4 — Info card

Display a `Card` showing "Render mode: $renderMode" and, when `renderMode is RenderMode.WebGpu`,
the backend status pill (reuse `WebGpuProviderImpl.computeBackend.status.collectAsState()`
pattern from `WebGpuExplainerCard`).

### Step 5 — Material definitions

In the composable (or in a `remember` block to avoid re-allocation):

```kotlin
val tileMaterial = remember {
    perFace {
        top     = textured(TextureSource.Bitmap(TextureAssets.grassTop))
        sides   = textured(TextureSource.Bitmap(TextureAssets.dirtSide))
        default = textured(TextureSource.Bitmap(TextureAssets.dirtSide))
    }
}
```

`TextureAssets.grassTop` / `.dirtSide` are lazy vals so the bitmaps are not re-allocated
across recompositions.

### Step 6 — `TexturedPrismGridScene` composable

Implement the 4×4 grid scene as described in "Scene Composition" above. Pass `renderMode`
from state into `SceneConfig`. Keep the composable stateless: it takes `renderMode` as a
parameter.

### Step 7 — Register in manifest

Add the `<activity>` element to `AndroidManifest.xml`.

### Step 8 — Add entry in MainActivity

Add the `SampleCard` for "Textured Materials" after the "Interaction API" card.

### Step 9 — Smoke test on device

Launch the app on SM-F956B. Navigate to "Textured Materials". Toggle through Canvas,
Canvas + GPU Sort, WebGPU. Verify:

- Grass-coloured top faces visible on all 16 prisms
- Dirt-coloured side faces visible
- No crash on mode switch
- WebGPU backend status shows "Ready" after brief initialization

## Acceptance Criteria Mapping

| AC from slice | Covered by |
|---------------|------------|
| Prism grid with grass top + dirt sides | Steps 5–6 (4×4 grid, `perFace` material) |
| Canvas mode textures via BitmapShader | Step 6 — `RenderMode.Canvas()` toggle |
| WebGPU mode textures via fragment shader | Step 6 — `RenderMode.WebGpu()` toggle |
| No crash on mode switch | Step 9 manual smoke test |

## Risk Notes

- **Texture generation cost:** 64×64 bitmap allocation is cheap (<1ms); lazy init and
  `remember {}` ensure it runs at most once per Activity lifecycle.
- **WebGPU not available on test device:** If the device's Vulkan/WebGPU layer is not
  ready, `WebGpuExplainerCard` will show "Failed" status and the scene will fall back to
  Canvas. This is expected behavior, not a bug in this slice.
- **`TogglePill` duplication:** The composable is copied from `WebGpuSampleActivity`.
  Acceptable for demo code; a future refactor can extract shared sample UI to a
  `:app-sample-ui` module.
- **`ForEach` key stability:** `col` and `row` integers are stable keys, so no
  recomposition churn on mode switch.

## Revision History

### 2026-04-11 — Cohesion Review (rev 1)
- Mode: Review-All (cohesion check after material-types dependency inversion)
- Issues found: 3 (1 HIGH, 2 MED)
  1. **HIGH:** Missing `:isometric-shader` dependency in `app/build.gradle.kts`. The `Shape()`
     overload accepting `material`, `textured()`, `perFace {}`, and `TextureSource` all live in
     `isometric-shader`. Fix: added Step 0.
  2. **MED:** No import statement for the shader-module `Shape` overload — could be confused with
     compose-module `Shape`. Fix: added explicit imports in Step 0.
  3. **MED:** `textured()`, `perFace {}`, `TextureSource` not attributed to shader module.
     Fix: addressed by Step 0 imports.
