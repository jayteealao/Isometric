---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: canvas-textures
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-12T10:37:07Z"
metric-files-to-touch: 11
metric-step-count: 18
has-blockers: false
revision-count: 3
tags: [canvas, texture, rendering]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-canvas-textures.md
  siblings: [04-plan-material-types.md, 04-plan-uv-generation.md, 04-plan-webgpu-textures.md, 04-plan-per-face-materials.md, 04-plan-sample-demo.md]
  implement: 05-implement-canvas-textures.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders canvas-textures"
---

# Plan: Canvas Textured Rendering

## Goal

Make the Canvas render path draw textured faces using `BitmapShader` + `Matrix`. Includes
`TextureCache` (LRU), `TextureLoader`, `MaterialResolver`, a `MaterialDrawHook` integration
seam, missing-texture fallback (magenta/black checkerboard), and unit + snapshot tests.

**Hero scenario:**
```kotlin
Shape(Prism(origin), material = textured(R.drawable.brick))
```
Renders a fully textured prism in Canvas mode with each face affine-mapped to the brick
texture. Zero code changes required for existing `Shape(Prism(origin), IsoColor.BLUE)` usage.

## Prerequisites (Depends-On)

Both slices are implemented and committed on `feat/texture`:

- **`material-types`**: `IsometricMaterial` sealed interface (`FlatColor`, `Textured`, `PerFace`)
  in `isometric-shader`. `TextureSource` (`Resource`, `Asset`, `BitmapSource`). `UvTransform`.
  `RenderCommand.material: MaterialData?` and `RenderCommand.uvCoords: FloatArray?` in core.
  `ShapeNode.material: MaterialData?` and `ShapeNode.uvProvider: UvCoordProvider?` in compose.

- **`uv-generation`**: `UvGenerator.forPrismFace(prism, faceIndex): FloatArray` producing
  per-vertex UV coords. `PrismFace` enum in core. Shader module's `Shape(geometry, material)`
  composable wires `UvCoordProvider` for `Textured` + `Prism` combinations.

**Critical architectural constraint:** `isometric-compose` does NOT depend on `isometric-shader`.
The dependency graph is `core → compose → shader`. All material-aware rendering logic must
live in `isometric-shader`. Compose provides a minimal hook interface that shader implements.

---

## Current State

### Actual Canvas rendering path (NOT `CanvasRenderBackend`)

`IsometricScene.kt` (line 580) uses `IsometricRenderer` directly inside a `Canvas { }` lambda.
`CanvasRenderBackend` exists but is **not wired** — dead code for Canvas mode.

Two active draw paths:
1. **Native path** (default, ~2x faster): `IsometricRenderer.renderNative()` →
   `NativeSceneRenderer.renderNative()` — uses `android.graphics.Canvas` via `drawIntoCanvas`
2. **Compose path**: `IsometricRenderer.render()` → `renderPreparedScene()` — uses
   `DrawScope.drawPath()` with Compose Path objects and a path pool

Both iterate `PreparedScene.commands` and draw each `RenderCommand` as flat color.
Neither checks `command.material` or `command.uvCoords`.

### Key existing types

| Type | Location | Notes |
|------|----------|-------|
| `RenderCommand.material` | `MaterialData?` in core | Carries `IsometricMaterial` opaquely |
| `RenderCommand.uvCoords` | `FloatArray?` in core | Per-vertex `[u0,v0, u1,v1, ...]` |
| `RenderCommand.points` | `DoubleArray` in core | Flat `[x0,y0, x1,y1, ...]` screen-space |
| `IsometricMaterial.Textured` | shader module | `.source: TextureSource`, `.tint`, `.uvTransform` |
| `IsometricMaterial.PerFace` | shader module | `.default: IsometricMaterial` (non-nullable) |
| `NativeSceneRenderer` | compose module | `fillPaint`, `strokePaint`, `toNativePath()`, `toAndroidColor()` |
| `IsometricRenderer` | compose module | `nativeRenderer`, `pathPool`, benchmark hooks |

---

## Design Decisions

### D1 — MaterialDrawHook Strategy Injection (compose ↔ shader bridge)

Since compose cannot import shader types, we define a minimal `fun interface` in compose:

```kotlin
// In isometric-compose
fun interface MaterialDrawHook {
    fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean  // true = drawn (textured fill applied), false = fall back to flat color
}
```

This follows the same pattern as `UvCoordProvider` — compose defines the interface,
shader provides the implementation. The hook is passed through a `CompositionLocal`:

```kotlin
val LocalMaterialDrawHook = staticCompositionLocalOf<MaterialDrawHook?> { null }
```

`IsometricScene` reads the local and sets it on `IsometricRenderer`, which passes it
to `NativeSceneRenderer`. When null, rendering is 100% flat-color (current behavior).

The shader module provides `TexturedCanvasDrawHook` implementing this interface and a
`ProvideTextureRendering { }` composable that installs it via CompositionLocalProvider.

### D2 — BitmapShader + 3-Point Affine Matrix

Same mathematical approach as rev 1 — `Matrix.setPolyToPoly(src, 0, dst, 0, 3)` maps
3 UV-scaled texture corners to 3 screen-space vertices. Affine is sufficient for
parallelogram isometric faces.

UV source coords come from `RenderCommand.uvCoords` (populated by `uv-generation`).
Screen coords come from `RenderCommand.points` (DoubleArray, converted to Float).

### D3 — LRU TextureCache (in isometric-shader)

`LinkedHashMap(accessOrder=true)` with max 20 entries. Keyed by `TextureSource` identity.
Each entry caches both `Bitmap` and pre-built `BitmapShader(CLAMP, CLAMP)`.

Lives in `isometric-shader` — compose has no knowledge of it.

### D4 — Checkerboard Fallback

On `TextureLoader` failure → 16×16 magenta/black checkerboard bitmap. Generated once,
cached as a field on `TexturedCanvasDrawHook`. Not in the LRU cache.

### D5 — Zero Per-Frame Allocation in Draw Loop

`android.graphics.Paint` held as a field on the hook. `BitmapShader` cached per texture.
`android.graphics.Matrix` reused via `outMatrix` pattern. Only `toNativePath()` allocates
(existing behavior — same allocation as flat-color path).

### D6 — Native Path Only for Textured Rendering

The Compose `DrawScope` path (`IsometricRenderer.renderPreparedScene`) does not support
textured rendering in this slice. When the compose path encounters a textured command, it
degrades to flat color (using `command.color`). This is acceptable because:
- `config.useNativeCanvas` defaults to the native path which is 2x faster
- Users who explicitly use the compose path accept its limitations
- Future: add compose-path texture support via `drawIntoCanvas { }` bridge

---

## File Map

### New files in `isometric-compose`

| File | Purpose |
|------|---------|
| `.../compose/runtime/MaterialDrawHook.kt` | Fun interface + CompositionLocal definition |

### Modified files in `isometric-compose`

| File | Change |
|------|--------|
| `.../compose/runtime/NativeSceneRenderer.kt` | Accept `MaterialDrawHook?`, check per-command |
| `.../compose/runtime/IsometricRenderer.kt` | Accept + propagate `MaterialDrawHook?` |
| `.../compose/runtime/IsometricScene.kt` | Read `LocalMaterialDrawHook`, set on renderer |

### New files in `isometric-shader`

| File | Purpose |
|------|---------|
| `.../shader/render/TextureCache.kt` | LRU bitmap+shader cache |
| `.../shader/render/TextureLoader.kt` | Load TextureSource → Bitmap |
| `.../shader/render/TexturedCanvasDrawHook.kt` | MaterialDrawHook impl: resolve + BitmapShader draw |
| `.../shader/render/ProvideTextureRendering.kt` | Composable installing the hook via CompositionLocal |

### New test files

| File | Module | Purpose |
|------|--------|---------|
| `.../shader/render/TextureCacheTest.kt` | isometric-shader | LRU cache unit tests |
| `.../shader/render/TexturedCanvasDrawHookTest.kt` | isometric-shader | Material resolution + affine matrix tests |
| Paparazzi snapshot tests | isometric-shader or compose | Textured Prism, fallback, mixed rendering |

Total files touched: **11** (5 new source, 3 modified, 3 new test files).

---

## Implementation Steps

### Step 1 — Define `MaterialDrawHook` + CompositionLocal in compose

Create `isometric-compose/.../runtime/MaterialDrawHook.kt`:

```kotlin
package io.github.jayteealao.isometric.compose.runtime

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.jayteealao.isometric.RenderCommand

/**
 * Hook for material-aware rendering in the native Canvas draw path.
 *
 * When a [RenderCommand] carries a non-null [RenderCommand.material], the renderer
 * delegates to this hook before falling back to flat-color drawing.
 *
 * Implementations live in downstream modules (e.g., `isometric-shader`) that can
 * interpret the specific [MaterialData] subtype. This keeps `isometric-compose` free
 * of material-system dependencies.
 */
fun interface MaterialDrawHook {
    /**
     * Draw a material-aware render command.
     *
     * @param nativeCanvas The Android canvas to draw on
     * @param command The render command with [RenderCommand.material] and [RenderCommand.uvCoords]
     * @param nativePath The pre-built native path for the command's polygon
     * @return true if the fill was drawn (hook handled it), false to fall back to flat color
     */
    fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean
}

/**
 * CompositionLocal for providing a [MaterialDrawHook] to the rendering pipeline.
 *
 * When null (default), all commands render as flat color. The `isometric-shader` module's
 * [ProvideTextureRendering] composable sets this to enable textured Canvas rendering.
 *
 * Uses [staticCompositionLocalOf] because the hook is set once per scene and rarely changes.
 */
val LocalMaterialDrawHook = staticCompositionLocalOf<MaterialDrawHook?> { null }
```

---

### Step 2 — Update `NativeSceneRenderer` to use the hook

Modify `isometric-compose/.../runtime/NativeSceneRenderer.kt`:

Add a `materialDrawHook: MaterialDrawHook?` parameter to `renderNative`:

```kotlin
fun DrawScope.renderNative(
    scene: PreparedScene,
    strokeStyle: StrokeStyle = StrokeStyle.FillAndStroke(),
    onRenderError: ((commandId: String, error: Throwable) -> Unit)? = null,
    materialDrawHook: MaterialDrawHook? = null,
) {
    drawIntoCanvas { canvas ->
        // ... existing stroke color/width computation ...

        scene.commands.forEach { command ->
            try {
                val nativePath = command.toNativePath()

                // Try material hook first for textured rendering
                val materialHandled = command.material != null
                    && materialDrawHook != null
                    && materialDrawHook.draw(canvas.nativeCanvas, command, nativePath)

                if (materialHandled) {
                    // Hook drew the fill — still apply stroke if needed
                    when (strokeStyle) {
                        is StrokeStyle.Stroke, is StrokeStyle.FillAndStroke -> {
                            strokePaint.strokeWidth = strokeWidth!!
                            strokePaint.color = strokeAndroidColor!!
                            canvas.nativeCanvas.drawPath(nativePath, strokePaint)
                        }
                        is StrokeStyle.FillOnly -> { /* no stroke */ }
                    }
                } else {
                    // Original flat-color path (unchanged)
                    when (strokeStyle) {
                        is StrokeStyle.FillOnly -> { ... }
                        is StrokeStyle.Stroke -> { ... }
                        is StrokeStyle.FillAndStroke -> { ... }
                    }
                }
            } catch (e: Exception) {
                onRenderError?.invoke(command.commandId, e)
            }
        }
    }
}
```

---

### Step 3 — Update `IsometricRenderer` to propagate the hook

Modify `isometric-compose/.../runtime/IsometricRenderer.kt`:

Add a settable property:

```kotlin
var materialDrawHook: MaterialDrawHook? = null
```

Update `renderNative()` and `renderNativeFromScene()` to pass it through:

```kotlin
fun DrawScope.renderNative(...) {
    // ... existing code ...
    cache.currentPreparedScene?.let { scene ->
        with(nativeRenderer) {
            renderNative(scene, strokeStyle, onRenderError, materialDrawHook)
        }
    }
    // ...
}
```

The Compose `renderPreparedScene` path is unchanged — textured commands degrade to flat
color there (D6).

---

### Step 4 — Update `IsometricScene` to read and install the hook

Modify `isometric-compose/.../runtime/IsometricScene.kt`:

In the composable body, read the hook and set it on the renderer:

```kotlin
// Near existing DisposableEffect for benchmarkHooks (around line 450)
val materialDrawHook = LocalMaterialDrawHook.current
DisposableEffect(materialDrawHook) {
    renderer.materialDrawHook = materialDrawHook
    onDispose { renderer.materialDrawHook = null }
}
```

No other changes needed — the hook flows through `renderNative()` automatically.

---

### Step 5 — Implement `TextureCache` in isometric-shader

Create `isometric-shader/.../shader/render/TextureCache.kt`:

```kotlin
package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader

internal data class CachedTexture(val bitmap: Bitmap, val shader: BitmapShader)

internal class TextureCache(val maxSize: Int = 20) {
    private val cache = object : LinkedHashMap<Any, CachedTexture>(
        maxSize + 1, 0.75f, /* accessOrder= */ true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<Any, CachedTexture>): Boolean =
            size > maxSize
    }

    fun get(key: Any): CachedTexture? = cache[key]

    fun put(key: Any, bitmap: Bitmap): CachedTexture {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val entry = CachedTexture(bitmap, shader)
        cache[key] = entry
        return entry
    }

    fun clear() = cache.clear()
    val size: Int get() = cache.size
}
```

Keyed by `Any` (will be `TextureSource` instances). CLAMP mode — faces map exactly to
texture bounds via UV coords.

---

### Step 6 — Implement `TextureLoader` in isometric-shader

Create `isometric-shader/.../shader/render/TextureLoader.kt`:

```kotlin
package io.github.jayteealao.isometric.shader.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.jayteealao.isometric.shader.TextureSource

internal class TextureLoader(private val context: Context) {
    fun load(source: TextureSource): Bitmap? = when (source) {
        is TextureSource.Resource -> loadResource(source.resId)
        is TextureSource.Asset -> loadAsset(source.path)
        is TextureSource.BitmapSource -> {
            source.ensureNotRecycled()
            source.bitmap
        }
    }

    private fun loadResource(resId: Int): Bitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, resId)
    }.getOrNull()

    private fun loadAsset(path: String): Bitmap? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
```

---

### Step 7 — Implement `TexturedCanvasDrawHook` in isometric-shader

Create `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`:

```kotlin
package io.github.jayteealao.isometric.shader.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.compose.runtime.MaterialDrawHook
import io.github.jayteealao.isometric.shader.IsometricMaterial

internal class TexturedCanvasDrawHook(
    private val cache: TextureCache,
    private val loader: TextureLoader,
) : MaterialDrawHook {

    private val texturedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val affineMatrix = Matrix()
    private val checkerboard: Bitmap by lazy { createCheckerboardBitmap() }

    override fun draw(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
    ): Boolean {
        val material = command.material as? IsometricMaterial ?: return false

        return when (material) {
            is IsometricMaterial.FlatColor -> false  // delegate back to flat-color path
            is IsometricMaterial.Textured -> drawTextured(nativeCanvas, command, nativePath, material)
            is IsometricMaterial.PerFace -> {
                // PerFace resolution deferred to per-face-materials slice.
                // Resolve default sub-material.
                val sub = material.default
                if (sub is IsometricMaterial.Textured) {
                    drawTextured(nativeCanvas, command, nativePath, sub)
                } else {
                    false  // FlatColor default → delegate to flat-color path
                }
            }
        }
    }

    private fun drawTextured(
        nativeCanvas: android.graphics.Canvas,
        command: RenderCommand,
        nativePath: android.graphics.Path,
        material: IsometricMaterial.Textured,
    ): Boolean {
        val uvCoords = command.uvCoords
        if (uvCoords == null || uvCoords.size < 6) return false  // degrade to flat

        val cached = resolveTexture(material.source)
        val texW = cached.bitmap.width
        val texH = cached.bitmap.height

        // Build affine matrix: UV space → screen space
        computeAffineMatrix(uvCoords, command.points, texW, texH, affineMatrix)
        cached.shader.setLocalMatrix(affineMatrix)

        texturedPaint.shader = cached.shader
        texturedPaint.colorFilter = material.tint.toColorFilterOrNull()

        nativeCanvas.drawPath(nativePath, texturedPaint)

        // Reset paint state for next call
        texturedPaint.shader = null
        texturedPaint.colorFilter = null

        return true
    }

    private fun resolveTexture(source: io.github.jayteealao.isometric.shader.TextureSource): CachedTexture {
        return cache.get(source) ?: run {
            val bitmap = loader.load(source) ?: checkerboard
            cache.put(source, bitmap)
        }
    }
}

// --- Internal utility functions ---

internal fun computeAffineMatrix(
    uvCoords: FloatArray,
    screenPoints: DoubleArray,
    texWidth: Int,
    texHeight: Int,
    outMatrix: Matrix,
) {
    val src = floatArrayOf(
        uvCoords[0] * texWidth,  uvCoords[1] * texHeight,
        uvCoords[2] * texWidth,  uvCoords[3] * texHeight,
        uvCoords[4] * texWidth,  uvCoords[5] * texHeight,
    )
    val dst = floatArrayOf(
        screenPoints[0].toFloat(), screenPoints[1].toFloat(),
        screenPoints[2].toFloat(), screenPoints[3].toFloat(),
        screenPoints[4].toFloat(), screenPoints[5].toFloat(),
    )
    outMatrix.setPolyToPoly(src, 0, dst, 0, 3)
}

internal fun createCheckerboardBitmap(): Bitmap {
    val size = 16
    val cellSize = 8
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val isMagenta = ((x / cellSize) + (y / cellSize)) % 2 == 0
            pixels[y * size + x] = if (isMagenta) 0xFFFF00FF.toInt() else 0xFF000000.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

internal fun IsoColor.toColorFilterOrNull(): PorterDuffColorFilter? {
    if (r >= 254.0 && g >= 254.0 && b >= 254.0) return null  // WHITE → no filter
    return PorterDuffColorFilter(
        android.graphics.Color.argb(
            255,
            r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255),
            b.toInt().coerceIn(0, 255)
        ),
        PorterDuff.Mode.MULTIPLY
    )
}
```

**Key design:** `computeAffineMatrix` reads directly from `RenderCommand.points: DoubleArray`
(flat `[x0,y0,...]`), converting `Double → Float` inline. No intermediate `toScreenFloats()`
allocation needed.

---

### Step 8 — Implement `ProvideTextureRendering` composable in isometric-shader

Create `isometric-shader/.../shader/render/ProvideTextureRendering.kt`:

```kotlin
package io.github.jayteealao.isometric.shader.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.jayteealao.isometric.compose.runtime.LocalMaterialDrawHook

/**
 * Enables textured Canvas rendering for any [IsometricScene] in [content].
 *
 * Wraps the content with the [TexturedCanvasDrawHook] which intercepts material-aware
 * [RenderCommand]s and draws them with BitmapShader + affine matrix mapping.
 *
 * Usage:
 * ```kotlin
 * ProvideTextureRendering {
 *     IsometricScene {
 *         Shape(Prism(origin), material = textured(R.drawable.brick))
 *     }
 * }
 * ```
 *
 * @param maxCacheSize Maximum number of textures to keep in the LRU cache. Default: 20.
 * @param content The composable tree containing IsometricScene(s).
 */
@Composable
fun ProvideTextureRendering(
    maxCacheSize: Int = 20,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val hook = remember(context, maxCacheSize) {
        val cache = TextureCache(maxCacheSize)
        val loader = TextureLoader(context.applicationContext)
        TexturedCanvasDrawHook(cache, loader)
    }
    CompositionLocalProvider(LocalMaterialDrawHook provides hook) {
        content()
    }
}
```

---

### Step 9 — Add `isometric-shader` dependency on Android graphics

The shader module's `build.gradle.kts` already has `api(project(":isometric-compose"))`
which transitively provides Android SDK. Verify that `android.graphics.BitmapShader`,
`android.graphics.Matrix`, `android.graphics.BitmapFactory`, `android.content.Context`
are all available. If `BitmapFactory` requires an explicit `implementation` dep, add
`implementation(libs.core.ktx)` or equivalent.

---

### Step 10 — Unit Test: `TextureCacheTest`

Create `isometric-shader/src/test/.../shader/render/TextureCacheTest.kt`:

Tests:
- `put and get returns same entry`
- `LRU eviction removes eldest on overflow`
- `access-order promotion on get`
- `size does not exceed maxSize`
- `clear empties cache`

These tests create `Bitmap` objects directly. Requires Android classes on the test classpath.
Two options:
- **Option A**: Run as `androidTest` (instrumented)
- **Option B**: Add Paparazzi or Robolectric to `isometric-shader` for JVM Android classpath

Recommend **Option B** — add Paparazzi plugin to `isometric-shader/build.gradle.kts` since
we need it for snapshot tests anyway. Paparazzi provides Android framework classes (via
LayoutLib) on the JVM test classpath without a device.

---

### Step 11 — Unit Test: `TexturedCanvasDrawHookTest`

Create `isometric-shader/src/test/.../shader/render/TexturedCanvasDrawHookTest.kt`:

Tests:
- `FlatColor material returns false (delegates to flat-color path)`
- `Textured with valid uvCoords returns true and draws`
- `Textured with null uvCoords returns false (degrades)`
- `Textured with < 6 uvCoords returns false (degrades)`
- `Missing texture resolves to checkerboard`
- `Cache is populated on first resolve, hit on second`
- `computeAffineMatrix identity for full-texture axis-aligned quad`
- `computeAffineMatrix known isometric face`
- `toColorFilterOrNull returns null for white tint`
- `PerFace resolves default sub-material`

---

### Step 12 — Paparazzi Snapshot Tests

Add Paparazzi plugin to `isometric-shader/build.gradle.kts` if not already present.
Create snapshot tests:

#### Test A — Textured Prism (checkerboard texture on all faces)

```kotlin
@Test fun texturedPrism() {
    val texture = createCheckerboardBitmap()
    paparazzi.snapshot {
        ProvideTextureRendering {
            IsometricScene {
                Shape(
                    geometry = Prism(Point(1.0, 1.0, 1.0)),
                    material = texturedBitmap(texture),
                )
            }
        }
    }
}
```

#### Test B — Missing Texture Fallback

```kotlin
@Test fun missingTextureFallback() {
    paparazzi.snapshot {
        ProvideTextureRendering {
            IsometricScene {
                Shape(
                    geometry = Prism(Point(1.0, 1.0, 1.0)),
                    material = texturedAsset("nonexistent/texture.png"),
                )
            }
        }
    }
}
```

#### Test C — Mixed Flat + Textured

```kotlin
@Test fun mixedFlatAndTextured() {
    val texture = createCheckerboardBitmap()
    paparazzi.snapshot {
        ProvideTextureRendering {
            IsometricScene {
                Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))
                Shape(geometry = Prism(Point(2.0, 0.0, 0.0)), material = texturedBitmap(texture))
                Shape(geometry = Prism(Point(4.0, 0.0, 0.0)), material = texturedBitmap(texture))
            }
        }
    }
}
```

---

### Step 13 — Interactive Visual Verification on Device (Maestro + adb)

This step verifies that textured rendering looks correct **on a real device or emulator**,
which Paparazzi cannot fully guarantee (Paparazzi uses LayoutLib/Skia, not the actual GPU
rendering path the user sees).

**Prerequisites:**
- `adb` is available at `/c/Users/jayte/AppData/Local/Android/Sdk/platform-tools/adb`
- Maestro is available at `/c/Users/jayte/.maestro/bin/maestro`
- Existing Maestro flows in `.maestro/` provide patterns to follow
- Sample app (`app/`) needs `implementation(project(":isometric-shader"))` added to
  `build.gradle.kts` (done in the `sample-demo` slice, but we can add it early for
  verification — or add a temporary test activity)

**Option A: Quick manual verification (recommended for this slice)**

Add a temporary composable screen to the sample app's `RuntimeApiActivity` or
`ComposeActivity` that renders textured prisms. This avoids blocking on the `sample-demo`
slice.

```kotlin
// Temporary addition to ComposeActivity.kt or a new TextureTestActivity
ProvideTextureRendering {
    IsometricScene(modifier = Modifier.fillMaxSize()) {
        // Textured prism with a built-in checkerboard
        Shape(Prism(Point(0.0, 0.0, 0.0)), material = texturedBitmap(createCheckerboardBitmap()))
        // Flat color prism for comparison
        Shape(Prism(Point(2.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))
        // Another textured prism to exercise cache
        Shape(Prism(Point(4.0, 0.0, 0.0)), material = texturedBitmap(createCheckerboardBitmap()))
    }
}
```

**Build and install:**
```bash
./gradlew :app:installDebug
```

**Launch and capture screenshot:**
```bash
adb shell am start -n io.github.jayteealao.isometric.sample/.ComposeActivity
sleep 2
adb shell screencap /sdcard/verify-canvas-textures.png
adb pull /sdcard/verify-canvas-textures.png .ai/workflows/texture-material-shaders/verify-evidence/
```

**Pass criteria (visual inspection of screenshot):**
1. Left prism: checkerboard pattern visible on all 3 visible faces (front, right/left, top)
2. Center prism: flat blue fill (existing behavior, backward compat)
3. Right prism: same checkerboard pattern as left (cache reuse — visually identical)
4. No blank/black faces (would indicate BitmapShader failure)
5. No visual artifacts at face edges (would indicate affine matrix error)
6. Stroke outlines visible on all prisms (stroke pass unaffected by texture fill)

**Option B: Maestro automated flow**

Create `.maestro/texture-test.yaml`:
```yaml
appId: io.github.jayteealao.isometric.sample
name: "Canvas Textures - Visual Verification"
---
- launchApp
- waitForAnimationToEnd
# Navigate to the texture test screen (if using a chooser)
- tapOn: "Texture Test"
- waitForAnimationToEnd
# Capture screenshot evidence
- takeScreenshot: screenshots/canvas_textures_textured_prism
# Navigate to missing texture test (if available)
- tapOn: "Missing Texture"
- waitForAnimationToEnd
- takeScreenshot: screenshots/canvas_textures_fallback
```

Run: `maestro test .maestro/texture-test.yaml`

**Evidence storage:** Screenshots saved to `.ai/workflows/texture-material-shaders/verify-evidence/`

**Note:** The full Maestro flow is better suited for the `sample-demo` slice when proper
navigation exists. For this slice, Option A (manual adb screenshot) is sufficient.

---

### Step 14 — Test / Verification Plan Summary

#### Automated checks
- **Build:** `./gradlew build` — all modules compile
- **Lint/typecheck:** `./gradlew :isometric-shader:lint :isometric-compose:lint`
- **Unit tests:** `./gradlew :isometric-shader:test :isometric-compose:test`
  - TextureCacheTest: LRU eviction, size bounds, access-order promotion
  - TexturedCanvasDrawHookTest: material resolution, affine matrix, fallback, degradation
- **Snapshot tests:** `./gradlew :isometric-shader:verifyPaparazziDebug`
  - Textured Prism golden match
  - Missing texture fallback golden match
  - Mixed flat + textured golden match
- **API check:** `./gradlew apiCheck` — public API surface unchanged for compose; new public
  API (`MaterialDrawHook`, `LocalMaterialDrawHook`) must be added to `api/*.api` files

#### Interactive verification (human-in-the-loop)

| Criterion | Platform & tool | Steps | Evidence | Pass criteria |
|-----------|----------------|-------|----------|---------------|
| Textured prism renders correctly on device | Android, adb + screencap | Build + install sample, launch, screenshot | `verify-evidence/canvas_textures_textured_prism.png` | Checkerboard visible on all 3 visible faces, correct affine mapping (no skew artifacts) |
| Flat-color prisms unaffected | Android, adb + screencap | Same screenshot as above | Same file | Blue prism renders identically to pre-texture baseline |
| Missing texture shows checkerboard fallback | Android, adb + screencap | Render shape with invalid asset path | `verify-evidence/canvas_textures_fallback.png` | Magenta/black checkerboard visible, no crash |
| No visual seams at face edges | Android, adb + screencap | Zoom in on textured prism edges | `verify-evidence/canvas_textures_edges.png` | Clean edges, no bleeding between faces |
| Performance: no visible jank with 5+ textured shapes | Android, adb logcat | Render 5 textured prisms, observe frame timing | Logcat Choreographer output | No "Skipped N frames" warnings in logcat |

---

### Step 15 — Acceptance Criteria Verification

| Criterion | Verification method |
|-----------|-------------------|
| Textured prism renders with texture on each face | Snapshot test A — golden match |
| Same texture used by 5 shapes → 1 bitmap in memory | Unit test: after 5 resolves with same source, `cache.size == 1` |
| Missing texture → checkerboard, no crash | Snapshot test B + unit test |
| Existing flat-color behavior unchanged | All existing snapshot tests still pass |
| No allocations in hot draw path | Code review: no `new BitmapShader()` inside draw loop |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Paparazzi cannot render native `BitmapShader` draw (LayoutLib limitation) | Medium | High | Paparazzi uses Skia via LayoutLib; `BitmapShader` is generally supported. If snapshot shows blank, fall back to software-path test |
| `setPolyToPoly(…, 3)` shear on certain face orientations | Low | Medium | `AffineMatrixTest` exercises known geometry; visual snapshot verification |
| `NativeSceneRenderer.renderNative` signature change breaks callers | Low | Low | Only called from `IsometricRenderer`; new param has default value |
| Synchronous `TextureLoader` I/O on main thread for large textures | Low | Medium | Acceptable for initial slice; async preloading documented as follow-up |
| `LinkedHashMap` LRU not thread-safe | Low | Medium | Canvas draw path runs on main thread; add `@MainThread` annotation |
| Paparazzi in shader module adds build complexity | Low | Low | Paparazzi is already configured in compose; same plugin config |

---

## Sequence of Work

```
 1. MaterialDrawHook.kt + LocalMaterialDrawHook  (new, compose, no deps)
 2. NativeSceneRenderer.kt                       (modify, compose, deps: 1)
 3. IsometricRenderer.kt                         (modify, compose, deps: 1)
 4. IsometricScene.kt                            (modify, compose, deps: 1)
 5. TextureCache.kt                              (new, shader, no deps)
 6. TextureLoader.kt                             (new, shader, no deps)
 7. TexturedCanvasDrawHook.kt                    (new, shader, deps: 5, 6)
 8. ProvideTextureRendering.kt                   (new, shader, deps: 7)
 9. TextureCacheTest.kt                          (new test, shader)
10. TexturedCanvasDrawHookTest.kt                (new test, shader)
11. Paparazzi snapshot tests                     (new test, shader, deps: 8)
12. ./gradlew build test                         (all green)
13. Record golden images if Paparazzi works
14. Add temp texture screen to sample app        (manual verification)
15. ./gradlew :app:installDebug                  (build sample app)
16. adb screencap + visual inspection            (interactive verification)
17. Capture evidence to verify-evidence/         (store screenshots)
18. Remove temp screen (or leave for sample-demo slice)
```

Steps 1–4 (compose changes) are independent from 5–8 (shader additions). Step 7 depends
on both 1 (for the interface) and 5–6 (for cache + loader). Steps 14–18 (interactive
verification) depend on all code being complete and building successfully.

---

## What This Slice Does Not Do

- **WebGPU textured rendering** — see `04-plan-webgpu-textures.md`
- **Per-face material resolution** — `PerFace` degrades to default sub-material.
  Full per-face logic is in `per-face-materials` slice.
- **Compose DrawScope textured path** — only native Canvas path is textured.
  Compose path degrades to flat color for textured commands.
- **Texture atlas packing** — deferred
- **AGSL RuntimeShader effects** — deferred
- **Async/background texture loading** — deferred
- **UV generation for non-Prism shapes** — UVs null → flat tint fill
- **CanvasRenderBackend texture support** — it's unused dead code; not modified

---

## Revision History

### 2026-04-11 — Cohesion Review (rev 1)
- Mode: Review-All (cohesion check after material-types dependency inversion)
- Issues found: 4 (3 HIGH, 1 MED)
  1. Material resolution logic placed in compose with shader imports — illegal
  2. CanvasRenderBackend cannot cast MaterialData without shader dep
  3. Prerequisites assumed compose's Shape() has material parameter
  4. File map puts files in wrong module
- Noted: decorator pattern / hook approach needed

### 2026-04-12 — Auto-Review (rev 2)
- Mode: Auto-Review (after material-types + uv-generation implemented)
- Issues found: 11 (5 HIGH, 3 MED, 3 LOW)
  1. **HIGH:** File locations still in `isometric-compose` — moved all to `isometric-shader`
  2. **HIGH:** Plan targets `CanvasRenderBackend` which is unused dead code — retargeted to
     `NativeSceneRenderer` + `IsometricRenderer` (the actual active rendering path)
  3. **HIGH:** `material.texture` → `material.source` (field name mismatch)
  4. **HIGH:** `PerFace.default` treated as nullable — it's non-nullable `IsometricMaterial`
  5. **HIGH:** No mechanism for cross-module rendering — added `MaterialDrawHook` fun interface
     + `LocalMaterialDrawHook` CompositionLocal (same pattern as `UvCoordProvider`)
  6. **MED:** `RenderCommand.points` is `DoubleArray` not `List<Point2D>` — updated
     `computeAffineMatrix` to read from `DoubleArray` directly
  7. **MED:** Both render paths need handling — decided native-only for textured, compose degrades
  8. **MED:** Tests need Android classpath in shader module — added Paparazzi to shader
  9. **LOW:** `asAndroidPath()` discussion irrelevant — removed (toNativePath() exists)
  10. **LOW:** `toScreenFloats()` helper wrong signature — removed (read DoubleArray directly)
  11. **LOW:** `TextureSource.Bitmap` → `TextureSource.BitmapSource` — corrected in code samples
- What was changed: Complete rewrite of architecture (MaterialDrawHook strategy injection),
  file map, all implementation steps, and test plan. Preserved design decisions D2-D5 from
  rev 1 (math and caching approach unchanged).

### 2026-04-12 — Directed Fix: Add interactive verification plan (rev 3)
- Mode: Directed Fix
- Feedback: "is there a visual testing plan for canvas textures"
- Issues found: 1 (MED) — plan had Paparazzi snapshot tests and unit tests but no
  interactive/visual verification on a real device. Paparazzi uses LayoutLib/Skia which
  may differ from actual GPU rendering.
- What was changed:
  - Added **Step 13**: Interactive visual verification on device (Maestro + adb). Two options:
    Option A (quick manual with adb screencap) and Option B (Maestro automated flow).
  - Added **Step 14**: Test / Verification Plan Summary with both automated and interactive
    tables. Interactive table maps 5 acceptance scenarios to platform/tool/steps/evidence/pass-criteria.
  - Updated sequence of work with steps 14–18 for interactive verification.
  - Updated step count from 16 → 18.
  - Evidence captured to `.ai/workflows/texture-material-shaders/verify-evidence/`
- Existing tooling confirmed: `adb` available, Maestro installed at `~/.maestro/bin/maestro`,
  11 existing Maestro flows in `.maestro/`, sample app at `app/` (needs `:isometric-shader`
  dependency for texture test screen).

---

## Recommended Next Stage
- **Option A (default):** `/wf-implement texture-material-shaders canvas-textures` — plan is updated and execution-ready
- **Option B:** `/compact` then Option A — recommended to clear planning context before implementing
