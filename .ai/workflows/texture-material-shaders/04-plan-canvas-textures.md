---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: canvas-textures
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:40:00Z"
metric-files-to-touch: 12
metric-step-count: 22
has-blockers: false
revision-count: 0
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
`TextureCache` (LRU), `TextureLoader`, `MaterialResolver`, `CanvasRenderBackend` changes,
missing-texture fallback (magenta/black checkerboard), and Paparazzi snapshot tests.

**Hero scenario:**
```kotlin
Shape(Prism(origin), material = textured(R.drawable.brick))
```
Renders a fully textured prism in Canvas mode with each face affine-mapped to the brick
texture. Zero code changes required for existing `Shape(Prism(origin), IsoColor.BLUE)` usage.

## Prerequisites (Depends-On)

This plan assumes the following two slices are already merged and on the working branch:

- **`material-types`**: `IsometricMaterial` sealed interface, `TextureSource`, `UvCoord`,
  `UvTransform`, DSL builders, `RenderCommand.material`, `RenderCommand.uvCoords`,
  `ShapeNode.material`, and `Shape(material=…)` composable.

- **`uv-generation`**: `UvGenerator` producing per-vertex `FloatArray` UV coords on
  `RenderCommand.uvCoords` for all 6 Prism faces. Prism face-type identification via
  `PrismFace` enum.

If either is absent, this slice cannot start.

---

## Design Decisions

### D1 — BitmapShader + Matrix (not Compose ShaderBrush)

`CanvasRenderBackend` currently dispatches through `DrawScope.drawPath()`. For textured
faces we must use `BitmapShader.setLocalMatrix()` to align the texture to each face's
screen-space parallelogram. This requires accessing the **native** `android.graphics.Canvas`
via `drawIntoCanvas { }` and a native `android.graphics.Paint`.

`Compose ShaderBrush` cannot carry a per-face local matrix without dropping to the native
layer anyway (research §2.4). Therefore the textured draw branch always goes through:

```kotlin
drawIntoCanvas { canvas ->
    paint.shader = bitmapShader
    canvas.nativeCanvas.drawPath(nativePath, paint)
}
```

The non-textured (flat-color) branch remains on `drawPath(path, color, Fill)` — no change.

### D2 — 3-Point Affine Matrix via `setPolyToPoly`

Isometric faces are parallelograms (4 vertices, but only 3 are independent). A
3-point affine transform is sufficient and more numerically stable than a 4-point
projective transform for this geometry.

Matrix computation per face:
```
src = floatArrayOf(0f, 0f,  texW, 0f,  texW, texH)   // texture: top-left, top-right, bottom-right
dst = floatArrayOf(p0.x, p0.y,  p1.x, p1.y,  p2.x, p2.y)  // face: matching 3 screen-space vertices
matrix.setPolyToPoly(src, 0, dst, 0, 3)
```

The fourth vertex `p3` is not needed — `setPolyToPoly(…, 3)` computes the unique affine
transform that maps exactly those 3 texture corners to those 3 screen corners.

UV source coordinates come from `RenderCommand.uvCoords` (populated by `uv-generation`).
The UV floats are in [0,1] range and must be scaled by `(texW, texH)` before calling
`setPolyToPoly`.

### D3 — LRU TextureCache (size-bounded, not time-bounded)

A `LinkedHashMap(…, accessOrder=true)` gives O(1) LRU eviction without a third-party
library. Cache is keyed by `TextureSource` identity. Max size defaults to **20 entries**
(covers typical isometric tile sets) with a constructor parameter for override.

The cache lives in `isometric-compose` (not `isometric-shader`) because it holds
`android.graphics.Bitmap` objects — a platform type. It is created once inside
`IsometricScene` via `remember {}` and passed to `CanvasRenderBackend`.

### D4 — TextureLoader loads on first access, not eagerly

`TextureLoader.load(source, context)` is called from the draw path on cache miss. For
`TextureSource.Resource` and `TextureSource.Asset` this is a synchronous
`BitmapFactory.decodeResource` / `BitmapFactory.decodeStream` call. The result is cached.

This is acceptable because the first frame with a new texture will be slow — subsequent
frames hit the cache. For production use, callers can pre-warm the cache. Async preloading
is deferred (out of scope for this slice).

### D5 — Checkerboard Fallback

On any `TextureLoader` failure (resource not found, decode error, null bitmap), the
`MaterialResolver` substitutes a procedurally generated **16×16 magenta/black checkerboard
bitmap**. This bitmap is generated once and cached as a member of `MaterialResolver`
(not in the LRU cache — it's never evicted).

Pattern: 8×8 pixel cells alternating magenta `0xFFFF00FF` and black `0xFF000000`.
This is the industry-standard "missing texture" indicator (Minecraft, Source Engine, Unity).

### D6 — No Shader/Paint Object Allocation in the Draw Loop

`android.graphics.Paint` instances are **never** allocated per-frame. `CanvasRenderBackend`
holds a single `texturedPaint: android.graphics.Paint` instance, initialized lazily on
first textured draw.

`BitmapShader` is allocated once per unique `(Bitmap, TileMode)` pair and stored in the
`TextureCache` alongside the bitmap:

```kotlin
data class CachedTexture(val bitmap: Bitmap, val shader: BitmapShader)
```

The `BitmapShader` is reused across frames; only `setLocalMatrix()` is called per face per
frame (matrix values overwritten in place — no allocation).

### D7 — MaterialResolver is a Pure Function (not a class with state)

`MaterialResolver` is an `object` with a single function:

```kotlin
object MaterialResolver {
    fun resolve(
        material: IsometricMaterial,
        cache: TextureCache,
        loader: TextureLoader,
        fallbackCheckerboard: Bitmap,
    ): ResolvedMaterial
}
```

No instance state. The `TextureCache` and `TextureLoader` are passed in, making the
resolver trivially testable.

---

## File Map

### New files

| File | Module | Purpose |
|------|--------|---------|
| `isometric-compose/.../render/TextureCache.kt` | isometric-compose | LRU bitmap+shader cache |
| `isometric-compose/.../render/TextureLoader.kt` | isometric-compose | Load TextureSource → Bitmap |
| `isometric-compose/.../render/MaterialResolver.kt` | isometric-compose | Resolve IsometricMaterial → ResolvedMaterial |
| `isometric-compose/.../render/ResolvedMaterial.kt` | isometric-compose | Sealed result type for renderer |

### Modified files

| File | Module | Change |
|------|--------|--------|
| `isometric-compose/.../render/CanvasRenderBackend.kt` | isometric-compose | Detect textured material, dispatch via MaterialResolver, draw with BitmapShader |
| `isometric-compose/.../runtime/IsometricScene.kt` | isometric-compose | Create TextureCache + TextureLoader via `remember {}`, pass to CanvasRenderBackend |
| `isometric-compose/.../runtime/render/RenderBackend.kt` | isometric-compose | Add `textureCache` and `textureLoader` parameters to `Surface()` if needed (see step 9) |
| `isometric-compose/src/test/.../IsometricCanvasSnapshotTest.kt` | isometric-compose | Add 3 new snapshot tests |
| `isometric-compose/src/test/.../render/TextureCacheTest.kt` (new) | isometric-compose | Unit tests for LRU cache |
| `isometric-compose/src/test/.../render/MaterialResolverTest.kt` (new) | isometric-compose | Unit tests for fallback chain |
| `isometric-compose/src/test/.../render/AffineMatrixTest.kt` (new) | isometric-compose | Unit tests for matrix computation |

Total files touched: **12** (4 new, 3 existing modified, 5 new test files).

---

## Implementation Steps

### Step 1 — Define `ResolvedMaterial`

Create `isometric-compose/.../render/ResolvedMaterial.kt`:

```kotlin
package io.github.jayteealao.isometric.compose.runtime.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import io.github.jayteealao.isometric.IsoColor

/**
 * The renderer-ready form of an [IsometricMaterial], produced by [MaterialResolver].
 * All heavy lifting (bitmap loading, shader creation) is already done.
 */
internal sealed interface ResolvedMaterial {
    /** Flat color fill — the current behavior. */
    data class FlatColor(val color: IsoColor) : ResolvedMaterial

    /**
     * Bitmap texture fill.
     * [shader] is a pre-configured [BitmapShader] with CLAMP tile mode.
     * The caller must call [shader].setLocalMatrix(affineMatrix) before each draw.
     */
    data class Textured(val shader: BitmapShader, val tint: IsoColor) : ResolvedMaterial
}
```

This sealed interface is `internal` — not part of the public API. The draw loop pattern-
matches on it.

---

### Step 2 — Implement `TextureCache`

Create `isometric-compose/.../render/TextureCache.kt`:

```kotlin
package io.github.jayteealao.isometric.compose.runtime.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * LRU cache mapping [TextureSource] → [CachedTexture].
 *
 * Each entry holds both the [Bitmap] and its pre-built [BitmapShader] (CLAMP/CLAMP mode).
 * The shader is created once and reused across frames; only the local matrix changes per draw.
 *
 * @param maxSize Maximum number of textures to keep in memory. Oldest accessed entry is
 *   evicted when the limit is reached. Default: 20 (typical isometric tile set).
 */
internal class TextureCache(val maxSize: Int = 20) {

    internal data class CachedTexture(val bitmap: Bitmap, val shader: BitmapShader)

    private val cache = object : LinkedHashMap<TextureSource, CachedTexture>(
        maxSize + 1, 0.75f, /* accessOrder= */ true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<TextureSource, CachedTexture>): Boolean =
            size > maxSize
    }

    fun get(source: TextureSource): CachedTexture? = cache[source]

    fun put(source: TextureSource, bitmap: Bitmap): CachedTexture {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val entry = CachedTexture(bitmap, shader)
        cache[source] = entry
        return entry
    }

    fun clear() = cache.clear()

    val size: Int get() = cache.size
}
```

**Why CLAMP not REPEAT**: Faces map exactly to the texture bounds (UVs in [0,1]). CLAMP
avoids visible seams at face edges. If tiling is desired, the user supplies `UvTransform`
scaling (handled when UV generation is extended in a later slice).

---

### Step 3 — Implement `TextureLoader`

Create `isometric-compose/.../render/TextureLoader.kt`:

```kotlin
package io.github.jayteealao.isometric.compose.runtime.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Loads a [TextureSource] to an [android.graphics.Bitmap].
 *
 * All load operations are synchronous. A null return value means the texture could
 * not be loaded; callers should substitute the fallback checkerboard.
 */
internal class TextureLoader(private val context: Context) {

    fun load(source: TextureSource): Bitmap? = when (source) {
        is TextureSource.Resource -> loadResource(source.resId)
        is TextureSource.Asset    -> loadAsset(source.path)
        is TextureSource.Bitmap   -> source.bitmap
    }

    private fun loadResource(resId: Int): Bitmap? = runCatching {
        BitmapFactory.decodeResource(context.resources, resId)
    }.getOrNull()

    private fun loadAsset(path: String): Bitmap? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
```

`runCatching` swallows all decode failures (resource not found, corrupt file, OOM)
and returns null. The null → checkerboard substitution happens in `MaterialResolver`.

---

### Step 4 — Generate Checkerboard Bitmap

Add a top-level function in `MaterialResolver.kt`:

```kotlin
/**
 * Generates the canonical 16×16 magenta/black missing-texture indicator.
 * Called once per [MaterialResolver] instantiation; result is reused indefinitely.
 *
 * Cell size: 8×8 pixels. Colors: magenta (#FF00FF) and black (#000000).
 */
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
```

---

### Step 5 — Implement `MaterialResolver`

Create `isometric-compose/.../render/MaterialResolver.kt`:

```kotlin
package io.github.jayteealao.isometric.compose.runtime.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.shader.IsometricMaterial
import io.github.jayteealao.isometric.shader.TextureSource

/**
 * Resolves [IsometricMaterial] into a [ResolvedMaterial] the renderer can act on.
 *
 * Fallback chain (research §8.4):
 *   Textured → [load bitmap] → success: ResolvedMaterial.Textured
 *                            → failure: ResolvedMaterial.Textured(checkerboard)
 *   FlatColor → ResolvedMaterial.FlatColor (zero overhead, no lookup)
 */
internal object MaterialResolver {

    fun resolve(
        material: IsometricMaterial,
        cache: TextureCache,
        loader: TextureLoader,
        fallbackCheckerboard: Bitmap,
    ): ResolvedMaterial = when (material) {
        is IsometricMaterial.FlatColor -> ResolvedMaterial.FlatColor(material.color)
        is IsometricMaterial.Textured  -> resolveTextured(material, cache, loader, fallbackCheckerboard)
        is IsometricMaterial.PerFace   -> {
            // PerFace resolution is handled by the per-face-materials slice.
            // For now, resolve the "default" sub-material (first non-null, or checkerboard).
            val sub = material.default ?: return ResolvedMaterial.Textured(
                BitmapShader(fallbackCheckerboard, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                IsoColor.WHITE
            )
            resolve(sub, cache, loader, fallbackCheckerboard)
        }
    }

    private fun resolveTextured(
        material: IsometricMaterial.Textured,
        cache: TextureCache,
        loader: TextureLoader,
        fallbackCheckerboard: Bitmap,
    ): ResolvedMaterial.Textured {
        val cached = cache.get(material.texture)
            ?: run {
                val bitmap = loader.load(material.texture) ?: fallbackCheckerboard
                cache.put(material.texture, bitmap)
            }
        return ResolvedMaterial.Textured(cached.shader, material.tint)
    }
}
```

**Key invariant:** `MaterialResolver` never allocates a `BitmapShader` directly for the
resolved result — it always goes through `TextureCache.put()`, which owns shader creation.
The only exception is the `PerFace` temporary path, which creates a one-off shader for the
checkerboard (this case is replaced entirely by the per-face-materials slice).

---

### Step 6 — Affine Matrix Computation

Add a top-level internal function in `CanvasRenderBackend.kt`:

```kotlin
/**
 * Computes the affine [android.graphics.Matrix] that maps texture space to screen space
 * for a single isometric face.
 *
 * Uses [Matrix.setPolyToPoly] with 3 control points — sufficient for the affine
 * transform that parallelogram faces require. The 4-point variant would compute a
 * projective transform, which is numerically less stable and unnecessary here.
 *
 * @param uvCoords  Flat FloatArray of UV pairs [u0,v0, u1,v1, u2,v2, …] from
 *                  [RenderCommand.uvCoords]. At least 6 values (3 points) must be present.
 * @param points    Screen-space 2D vertices [x0,y0, x1,y1, x2,y2, …] from
 *                  [RenderCommand.points] (converted to Float).
 * @param texWidth  Width of the source [Bitmap] in pixels.
 * @param texHeight Height of the source [Bitmap] in pixels.
 * @param outMatrix The matrix to write into (reused across calls — no allocation).
 */
internal fun computeAffineMatrix(
    uvCoords: FloatArray,
    points: FloatArray,
    texWidth: Int,
    texHeight: Int,
    outMatrix: android.graphics.Matrix,
) {
    // src: texture coordinates scaled to pixel space
    val src = floatArrayOf(
        uvCoords[0] * texWidth,  uvCoords[1] * texHeight,
        uvCoords[2] * texWidth,  uvCoords[3] * texHeight,
        uvCoords[4] * texWidth,  uvCoords[5] * texHeight,
    )
    // dst: first 3 screen-space vertices
    val dst = floatArrayOf(
        points[0], points[1],
        points[2], points[3],
        points[4], points[5],
    )
    outMatrix.setPolyToPoly(src, 0, dst, 0, 3)
}
```

`outMatrix` is a reusable `android.graphics.Matrix` held as a member of
`CanvasRenderBackend`. It is written-over each call — no allocation inside the draw loop.

---

### Step 7 — Update `CanvasRenderBackend`

Full rewrite of the draw loop to dispatch on `ResolvedMaterial`:

```kotlin
internal class CanvasRenderBackend(
    private val textureCache: TextureCache,
    private val textureLoader: TextureLoader,
) : RenderBackend {

    // Lazily created — only allocated on the first textured draw.
    private val texturedPaint by lazy { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    private val affineMatrix = android.graphics.Matrix()
    private val checkerboard: android.graphics.Bitmap by lazy { createCheckerboardBitmap() }

    @Composable
    override fun Surface(
        preparedScene: State<PreparedScene?>,
        renderContext: RenderContext,
        modifier: Modifier,
        strokeStyle: StrokeStyle,
    ) {
        Canvas(modifier = modifier) {
            val scene = preparedScene.value ?: return@Canvas
            renderPreparedScene(scene, strokeStyle)
        }
    }

    private fun DrawScope.renderPreparedScene(scene: PreparedScene, strokeStyle: StrokeStyle) {
        val strokeComposeColor = strokeStyle.strokeColor()
        val strokeDrawStyle  = strokeStyle.strokeDrawStyle()

        for (command in scene.commands) {
            val resolved: ResolvedMaterial = if (command.material != null) {
                MaterialResolver.resolve(command.material, textureCache, textureLoader, checkerboard)
            } else {
                ResolvedMaterial.FlatColor(command.color)
            }

            val path = command.toComposePath()

            when (resolved) {
                is ResolvedMaterial.FlatColor -> {
                    drawFlatColor(path, resolved, strokeComposeColor, strokeDrawStyle, strokeStyle)
                }
                is ResolvedMaterial.Textured -> {
                    drawTextured(command, resolved, path, strokeComposeColor, strokeDrawStyle, strokeStyle)
                }
            }
        }
    }

    private fun DrawScope.drawFlatColor(
        path: androidx.compose.ui.graphics.Path,
        resolved: ResolvedMaterial.FlatColor,
        strokeColor: androidx.compose.ui.graphics.Color?,
        strokeStyle: Stroke?,
        outerStyle: StrokeStyle,
    ) {
        val color = resolved.color.toComposeColor()
        when (outerStyle) {
            is StrokeStyle.FillOnly     -> drawPath(path, color, style = Fill)
            is StrokeStyle.Stroke       -> drawPath(path, strokeColor!!, style = strokeStyle!!)
            is StrokeStyle.FillAndStroke -> {
                drawPath(path, color, style = Fill)
                drawPath(path, strokeColor!!, style = strokeStyle!!)
            }
        }
    }

    private fun DrawScope.drawTextured(
        command: RenderCommand,
        resolved: ResolvedMaterial.Textured,
        path: androidx.compose.ui.graphics.Path,
        strokeColor: androidx.compose.ui.graphics.Color?,
        strokeStyle: Stroke?,
        outerStyle: StrokeStyle,
    ) {
        val uvCoords = command.uvCoords
        if (uvCoords == null || uvCoords.size < 6) {
            // UV coords not available — degrade to flat color from tint
            drawPath(path, resolved.tint.toComposeColor(), style = Fill)
            return
        }

        val bmp = resolved.shader   // BitmapShader
        val screenPoints = command.points.toScreenFloats()

        computeAffineMatrix(
            uvCoords = uvCoords,
            points = screenPoints,
            texWidth = bmp./* Bitmap width accessed via CachedTexture */,
            texHeight = /* … */,
            outMatrix = affineMatrix,
        )
        resolved.shader.setLocalMatrix(affineMatrix)

        drawIntoCanvas { canvas ->
            texturedPaint.shader = resolved.shader
            // Apply tint as a color filter if non-white
            texturedPaint.colorFilter = resolved.tint.toColorFilter()
            canvas.nativeCanvas.drawPath(path.toNativePath(), texturedPaint)
        }

        // Stroke pass (material-aware: stroke is always flat black/custom regardless of fill)
        if (outerStyle is StrokeStyle.Stroke || outerStyle is StrokeStyle.FillAndStroke) {
            drawPath(path, strokeColor!!, style = strokeStyle!!)
        }
    }

    override fun toString(): String = "RenderBackend.Canvas"
}
```

**Note on `BitmapShader` access to bitmap dimensions**: `ResolvedMaterial.Textured` carries
only the `BitmapShader` and tint. We need bitmap dimensions for `computeAffineMatrix`. Two
options:

- **Option A**: Store `(shader, bitmap)` in `ResolvedMaterial.Textured` — direct, but
  exposes `Bitmap` reference to the renderer.
- **Option B**: Store `(shader, texWidth, texHeight)` — avoids holding the full `Bitmap`
  reference in the resolved type.

**Decision: Option B.** `ResolvedMaterial.Textured` carries `texWidth: Int` and
`texHeight: Int` alongside `shader`. The `Bitmap` reference remains only in `TextureCache`.

Updated `ResolvedMaterial.Textured`:
```kotlin
data class Textured(
    val shader: BitmapShader,
    val texWidth: Int,
    val texHeight: Int,
    val tint: IsoColor,
) : ResolvedMaterial
```

---

### Step 8 — Screen Points Helper

`RenderCommand.points` is `List<Point2D>`. The draw loop needs a `FloatArray` for
`computeAffineMatrix`. Add an extension function (internal to the render package):

```kotlin
internal fun List<io.github.jayteealao.isometric.Point2D>.toScreenFloats(): FloatArray {
    val arr = FloatArray(size * 2)
    for (i in indices) {
        arr[i * 2]     = this[i].x.toFloat()
        arr[i * 2 + 1] = this[i].y.toFloat()
    }
    return arr
}
```

This allocates a `FloatArray` per draw call. If profiling shows this is a hot path,
replace with a reusable `FloatArray` member in `CanvasRenderBackend` (pre-sized to the
maximum expected face vertex count, e.g. 20×2=40 floats for cylinders).

---

### Step 9 — Wire `TextureCache` and `TextureLoader` Through `IsometricScene`

`CanvasRenderBackend` currently takes no constructor arguments. After this change it takes
`TextureCache` and `TextureLoader`. Both are created with `remember {}` in `IsometricScene`
and depend on `LocalContext`:

```kotlin
// In IsometricScene.kt (the AdvancedSceneConfig overload):
val context = LocalContext.current
val textureCache = remember { TextureCache(maxSize = 20) }
val textureLoader = remember(context) { TextureLoader(context) }

// The CanvasRenderBackend is already created inside RenderBackend resolution.
// Update that to pass the new arguments.
```

`RenderBackend` interface currently has no `textureCache` or `textureLoader` parameters
in `Surface()` — and it should not grow them (WebGPU backend doesn't need them). The cache
and loader are passed as **constructor parameters** to `CanvasRenderBackend` at creation time,
not through the interface.

The current code creates a `CanvasRenderBackend()` as a `remember {}`. Change to:
```kotlin
val canvasBackend = remember(textureLoader) { CanvasRenderBackend(textureCache, textureLoader) }
```

The `textureCache` can be shared across recompositions because it's stable. `textureLoader`
is keyed on `context`, which is stable for the lifetime of the composable.

---

### Step 10 — `IsoColor.toColorFilter()` Extension

When `resolved.tint != IsoColor.WHITE`, apply a multiplicative color filter:

```kotlin
internal fun IsoColor.toColorFilter(): android.graphics.ColorFilter? {
    if (r == 255.0 && g == 255.0 && b == 255.0) return null
    return android.graphics.PorterDuffColorFilter(
        android.graphics.Color.argb(255, r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255)),
        android.graphics.PorterDuff.Mode.MULTIPLY
    )
}
```

This is `null` for the common case (WHITE tint = no filter), avoiding GPU state changes.

---

### Step 11 — Native Path Conversion

The existing `toComposePath()` extension produces a Compose `androidx.compose.ui.graphics.Path`.
For the native draw call, we need `android.graphics.Path`. The existing `NativeSceneRenderer`
already does this conversion. Add an extension (or re-use existing):

```kotlin
internal fun androidx.compose.ui.graphics.Path.toNativePath(): android.graphics.Path {
    val nativePath = android.graphics.Path()
    // Use the existing asAndroidPath() if available, or convert via PathIterator
    return this.asAndroidPath()   // available in Compose UI 1.5+ via androidx.compose.ui.graphics
}
```

Check whether `asAndroidPath()` is already available in the compose-ui version in use.
If not, use `android.graphics.Path()` + `drawContext.canvas.nativeCanvas` via `drawIntoCanvas`.

---

### Step 12 — Unit Test: `TextureCacheTest`

```kotlin
class TextureCacheTest {
    @Test fun `put and get returns same entry`() { … }
    @Test fun `LRU eviction removes eldest on overflow`() {
        val cache = TextureCache(maxSize = 2)
        cache.put(source1, bitmap1)
        cache.put(source2, bitmap2)
        cache.get(source1) // access source1 — it becomes most recently used
        cache.put(source3, bitmap3) // source2 should be evicted
        assertNull(cache.get(source2))
        assertNotNull(cache.get(source1))
        assertNotNull(cache.get(source3))
    }
    @Test fun `size does not exceed maxSize`() { … }
    @Test fun `clear empties cache`() { … }
    @Test fun `shader is created once per bitmap`() {
        // Put same source twice — shader reference should be identical (no reallocation)
    }
}
```

---

### Step 13 — Unit Test: `MaterialResolverTest`

```kotlin
class MaterialResolverTest {
    @Test fun `FlatColor resolves to FlatColor with same color`() { … }
    @Test fun `Textured with cache hit reuses cached shader`() { … }
    @Test fun `Textured with loader failure returns checkerboard shader`() {
        val loader = TextureLoader(mockContext) // loader returns null
        val resolved = MaterialResolver.resolve(
            IsometricMaterial.Textured(TextureSource.Resource(999)),
            cache, loader, checkerboard
        )
        assertIs<ResolvedMaterial.Textured>(resolved)
        // The bitmap inside resolved should be the checkerboard dimensions (16×16)
        assertEquals(16, resolved.texWidth)
    }
    @Test fun `cache is populated on first miss`() { … }
    @Test fun `same source only loaded once across two resolve calls`() { … }
}
```

---

### Step 14 — Unit Test: `AffineMatrixTest`

```kotlin
class AffineMatrixTest {
    @Test fun `identity mapping for full-texture quad`() {
        // UV = [0,0, 1,0, 1,1] mapped to a 64×64 texture
        // Screen points = a 64×64 axis-aligned quad
        // Expected: identity matrix (or translation only)
        val matrix = android.graphics.Matrix()
        computeAffineMatrix(
            uvCoords = floatArrayOf(0f, 0f,  1f, 0f,  1f, 1f),
            points   = floatArrayOf(0f, 0f, 64f, 0f, 64f, 64f),
            texWidth = 64, texHeight = 64,
            outMatrix = matrix
        )
        assertTrue(matrix.isIdentity)
    }

    @Test fun `isometric top face maps correctly`() {
        // Known Prism top face screen coords (from a 480×320 canvas)
        // and known UV [0,0, 1,0, 1,1, 0,1]
        // Assert: applying the matrix to src UV points yields dst screen points
    }

    @Test fun `degenerate UV (all same) does not crash`() {
        // A zero-area face — matrix is degenerate but must not throw
        computeAffineMatrix(
            uvCoords = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
            points   = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f),
            texWidth = 64, texHeight = 64,
            outMatrix = android.graphics.Matrix()
        )
        // No exception — that's the test
    }
}
```

---

### Step 15 — Paparazzi Snapshot Tests

Add to `IsometricCanvasSnapshotTest.kt`:

#### Test A — Textured Prism (single texture, all faces)

```kotlin
@Test
fun texturedPrism() {
    // Use a Paparazzi-friendly bitmap injected via TextureSource.Bitmap
    val checkered = createCheckerboardBitmap()  // reuse the generator for a stable golden
    paparazzi.snapshot {
        Box(modifier = Modifier.size(680.dp, 440.dp)) {
            IsometricScene {
                Shape(
                    geometry = Prism(Point(1.0, 1.0, 1.0)),
                    material = IsometricMaterial.Textured(
                        texture = TextureSource.Bitmap(checkered),
                        tint = IsoColor.WHITE
                    )
                )
            }
        }
    }
}
```

#### Test B — Missing Texture Fallback (checkerboard visible)

```kotlin
@Test
fun missingTextureFallback() {
    // TextureSource.Asset with a path that doesn't exist → loader returns null → checkerboard
    paparazzi.snapshot {
        Box(modifier = Modifier.size(680.dp, 440.dp)) {
            IsometricScene {
                Shape(
                    geometry = Prism(Point(1.0, 1.0, 1.0)),
                    material = IsometricMaterial.Textured(
                        texture = TextureSource.Asset("nonexistent/texture.png")
                    )
                )
            }
        }
    }
}
```

#### Test C — Mixed Flat + Textured (cache reuse)

```kotlin
@Test
fun mixedFlatAndTextured() {
    val texture = createCheckerboardBitmap()
    paparazzi.snapshot {
        Box(modifier = Modifier.size(680.dp, 440.dp)) {
            IsometricScene {
                // First shape: flat color (backward compat check)
                Shape(geometry = Prism(Point(0.0, 0.0, 0.0)), color = MATERIAL_BLUE)
                // Second shape: textured (same texture used — exercises cache reuse)
                Shape(
                    geometry = Prism(Point(2.0, 0.0, 0.0)),
                    material = IsometricMaterial.Textured(TextureSource.Bitmap(texture))
                )
                // Third shape: also textured with same source — must be cache hit
                Shape(
                    geometry = Prism(Point(4.0, 0.0, 0.0)),
                    material = IsometricMaterial.Textured(TextureSource.Bitmap(texture))
                )
            }
        }
    }
}
```

**Golden image strategy**: Run `./gradlew :isometric-compose:recordPaparazziDebug` once
to generate the initial golden images. Subsequent CI runs use `verifyPaparazziDebug`.
The checkerboard-based textures make the golden images deterministic (no file I/O needed
in CI).

---

### Step 16 — Acceptance Criteria Verification

| Criterion | Verification method |
|-----------|-------------------|
| Textured prism renders with texture on each face | Snapshot test A — golden match |
| Same texture used by 5 shapes → 1 bitmap in memory | Unit test: after 5 resolves with same source, `cache.size == 1` |
| Missing texture → checkerboard, no crash | Snapshot test B + unit test in MaterialResolverTest |
| Paparazzi tests pass | CI `verifyPaparazziDebug` |
| Existing flat-color behavior unchanged | All existing `IsometricCanvasSnapshotTest` tests still pass |
| No allocations in hot draw path | Code review: no `new`/`BitmapShader(…)` inside `renderPreparedScene` loop |

---

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| `asAndroidPath()` not available in Compose UI version in use | Medium | High | Fall back to drawing via `drawIntoCanvas` with direct path construction, or check Compose UI version in `build.gradle.kts` |
| `setPolyToPoly(…, 3)` produces unexpected shear on certain face orientations | Low | Medium | `AffineMatrixTest` exercises known Prism geometry; visual inspection via snapshot test |
| `CanvasRenderBackend` constructor change breaks existing `remember {}` creation sites | Low | Low | Single creation site in `IsometricScene.kt`; search confirms no other instantiation |
| `TextureLoader` synchronous I/O on main thread causes ANR for large textures | Low | Medium | Acceptable for initial slice; async preloading is a documented follow-up. Document the constraint in `TextureLoader` kdoc |
| Paparazzi cannot render `drawIntoCanvas` + native shader (known limitation) | Medium | High | Paparazzi uses Skia via LayoutLib; native `BitmapShader` draw is generally supported. If snapshot shows blank, fall back to a software-path test with `Bitmap.Config.SOFTWARE` |
| LRU `LinkedHashMap` not thread-safe — UI thread writes, Compose snapshot thread reads | Low | Medium | Canvas draw path runs on main thread; no concurrent access expected. Add `@MainThread` annotation and note in kdoc |

---

## Sequence of Work

```
1.  ResolvedMaterial.kt              (new, no deps)
2.  TextureCache.kt                  (new, no deps)
3.  createCheckerboardBitmap()       (new, in MaterialResolver.kt)
4.  MaterialResolver.kt              (new, deps: 1, 2, 3)
5.  TextureLoader.kt                 (new, no deps)
6.  computeAffineMatrix()            (new, in CanvasRenderBackend.kt)
7.  toScreenFloats() extension       (new, in CanvasRenderBackend.kt)
8.  IsoColor.toColorFilter()         (new, in ColorExtensions.kt)
9.  CanvasRenderBackend.kt           (modify, deps: 1–8)
10. IsometricScene.kt                (modify: add remember{TextureCache}, remember{TextureLoader})
11. TextureCacheTest.kt              (new unit test)
12. MaterialResolverTest.kt          (new unit test)
13. AffineMatrixTest.kt              (new unit test)
14. IsometricCanvasSnapshotTest.kt   (add 3 snapshot tests)
15. ./gradlew recordPaparazziDebug   (generate golden images)
16. ./gradlew :isometric-compose:test (all unit tests green)
17. ./gradlew :isometric-compose:verifyPaparazziDebug (all golden matches)
```

Steps 1–5 can be done in any order (no mutual dependencies). Step 9 requires 1–8. Step 10
requires 2 and 5. Tests require 9 and 10.

---

## What This Slice Does Not Do

- **WebGPU textured rendering** — see `04-plan-webgpu-textures.md`
- **Per-face material resolution** — `CanvasRenderBackend` applies the same material to all
  faces of a shape. `IsometricMaterial.PerFace` degrades to the `default` sub-material.
  Full per-face logic is in the `per-face-materials` slice.
- **Texture atlas packing** — deferred. Shapes share individual bitmaps, not an atlas.
- **AGSL RuntimeShader effects** (API 33+) — deferred.
- **Async/background texture loading** — `TextureLoader` is synchronous. Preloading is
  documented but not implemented.
- **UV generation for non-Prism shapes** — UV coords on other shape types are `null`;
  the renderer degrades gracefully to flat tint fill.
- **Mipmap generation** — not needed for isometric 2D rendering (see research §7.2).
