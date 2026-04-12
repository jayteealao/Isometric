---
schema: sdlc/v1
type: review
slug: texture-material-shaders
slice: canvas-textures
review-dimension: correctness
reviewer: claude-sonnet-4-6
date: "2026-04-12"
status: complete
verdict: conditional-pass
blocker-count: 0
high-count: 3
med-count: 4
low-count: 1
nit-count: 2
---

# Canvas-Textures Correctness Review

## Findings Table

| ID | Severity | File | Line(s) | Title |
|----|----------|------|---------|-------|
| CT-CR-1 | HIGH | `TexturedCanvasDrawHook.kt` | 110–128 | `computeAffineMatrix` does not apply `uvTransform` — dead API |
| CT-CR-2 | HIGH | `TexturedCanvasDrawHook.kt` / `IsometricNode.kt` | 48–56 / — | `PerFace.faceMap` overrides are never consulted — always falls back to `default` |
| CT-CR-3 | HIGH | `TexturedCanvasDrawHook.kt` | 78 | Paint state leak on exception — shader and colorFilter not cleared if `drawPath` throws |
| CT-CR-4 | MED | `TexturedCanvasDrawHook.kt` | 86–91 | Failed texture loads are permanently cached — no retry on transient failures |
| CT-CR-5 | MED | `IsometricRenderer.kt` | 477–482 | `drawIntoCanvas` bridge allocates a new `android.graphics.Path` per command even when the hook returns `false` |
| CT-CR-6 | MED | `TexturedCanvasDrawHook.kt` | 121–126 | `computeAffineMatrix` has no guard on `screenPoints.size < 6` — AIOOBE on degenerate paths |
| CT-CR-7 | MED | `TextureCache.kt` | 49 | `BitmapSource` cache key uses reference identity for `Bitmap` equality — duplicate entries for same pixel data |
| CT-CR-8 | LOW | `TexturedCanvasDrawHook.kt` | 157–168 | `toColorFilterOrNull` fuzzy-white threshold at 254 silently drops near-white tints |
| CT-CR-9 | NIT | `IsometricNode.kt` | 286 | `uvProvider` lambda ignores its first parameter (`shape`) — `UvCoordProvider` signature promises per-shape UVs but the closure uses a different captured variable |
| CT-CR-10 | NIT | `ProvideTextureRendering.kt` | 34 | `remember(context, maxCacheSize)` key on `context` throws away the LRU cache on any Activity/Context change |

---

## Detailed Findings

### CT-CR-1 — HIGH: `uvTransform` property is dead code in the Canvas render path

**File:** `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`, function `computeAffineMatrix`
**Also:** `isometric-shader/.../shader/UvCoord.kt` (declares `UvTransform`), `IsometricMaterial.kt` (stores it)

**Problem:**
`IsometricMaterial.Textured` carries a `uvTransform: UvTransform` property with fields `scaleU`, `scaleV`, `offsetU`, `offsetV`, and `rotationDegrees`. The DSL doc comment on `textured()` explicitly advertises this:

```kotlin
// From IsometricMaterial.kt KDoc:
Shape(Prism(origin), material = textured(R.drawable.brick, uvTransform = UvTransform(scaleU = 2f, scaleV = 2f)))
```

However, `computeAffineMatrix` takes only the raw `uvCoords` `FloatArray` and never reads `material.uvTransform`. The UV coordinates from `UvGenerator` are always in the raw [0,1] range regardless of any transform the caller specifies. Setting `uvTransform` on a material has zero effect at runtime.

This means:
- Texture tiling (`scaleU = 2f`) silently does nothing.
- UV offset (`offsetU = 0.5f`) silently does nothing.
- UV rotation (`rotationDegrees = 45f`) silently does nothing.

This is a broken public API guarantee — callers following the documented example will observe no effect.

**Fix:** Apply `uvTransform` after generating raw UVs, either in `UvGenerator.forPrismFace` (modifying the returned array) or inside `drawTextured` before calling `computeAffineMatrix`. The transform application order documented in `UvTransform` is: scale → rotate → offset.

```kotlin
// In drawTextured, before computeAffineMatrix:
val transformedUvCoords = material.uvTransform.applyTo(uvCoords)
computeAffineMatrix(transformedUvCoords, command.points, texW, texH, affineMatrix)
```

Alternatively, `BitmapShader.setLocalMatrix` can compose the affine matrix with an additional UV-space matrix representing the transform — but that requires mapping from UV-space transforms to the final texture-space matrix.

---

### CT-CR-2 — HIGH: `PerFace.faceMap` overrides silently ignored

**File:** `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`, lines 48–56

**Problem:**
The `draw()` dispatch for `IsometricMaterial.PerFace` is:

```kotlin
is IsometricMaterial.PerFace -> {
    val sub = material.default
    if (sub is IsometricMaterial.Textured) {
        drawTextured(nativeCanvas, command, nativePath, sub)
    } else {
        false
    }
}
```

This always uses `material.default` and completely ignores `material.faceMap`. The `PerFace` material is specifically designed so callers can assign different textures to different faces (e.g., top face = grass, side face = dirt). The hook never looks up the face-specific material from the map.

The face index is available on `RenderCommand` implicitly — not as a direct property, but it can be recovered because each face of a `Prism` produces a `RenderCommand` with `commandId = "${nodeId}_${path.hashCode()}"` and the UV coords for that face. However, the face index is not stored directly in `RenderCommand`, making the lookup non-trivial.

**Impact:** Any user who calls `perFace { face(0, textured(...)); face(1, textured(...)) }` will see only the `default` material applied to every face. The per-face override system advertised in the API is completely non-functional for the Canvas-textures render path.

**Fix (minimal):** Store the face index in `RenderCommand` (an `Int` property, `faceIndex: Int = -1`) so the draw hook can consult `faceMap[command.faceIndex]`. Alternatively, resolve the face-specific material at node `renderTo` time and store the resolved `IsometricMaterial` in the command, removing the need for `PerFace` to reach the renderer at all. The latter is architecturally cleaner and avoids exposing face indices to the renderer.

---

### CT-CR-3 — HIGH: Paint state leak when `nativeCanvas.drawPath` throws

**File:** `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`, lines 75–83

**Problem:**
`drawTextured` sets up state on the shared `texturedPaint` and cleans it up at the end:

```kotlin
texturedPaint.shader = cached.shader
texturedPaint.colorFilter = material.tint.toColorFilterOrNull()

nativeCanvas.drawPath(nativePath, texturedPaint)   // can throw

texturedPaint.shader = null         // never reached if drawPath throws
texturedPaint.colorFilter = null
```

If `drawPath` throws (e.g., corrupted path data, OOM from a very large texture, or any other runtime exception), the cleanup lines are skipped. On the next frame, `texturedPaint.shader` still holds a reference to the previous command's `BitmapShader`, and `texturedPaint.colorFilter` may still be set. The next command will be drawn with the wrong shader — a stale texture from the previous failed command.

Because `NativeSceneRenderer.renderNative` catches exceptions per-command (`catch (e: Exception)`), execution continues to the next command. The leaked Paint state will corrupt the next command's rendering, potentially drawing the wrong texture or applying a wrong tint.

**Fix:** Use try/finally:

```kotlin
try {
    texturedPaint.shader = cached.shader
    texturedPaint.colorFilter = material.tint.toColorFilterOrNull()
    nativeCanvas.drawPath(nativePath, texturedPaint)
} finally {
    texturedPaint.shader = null
    texturedPaint.colorFilter = null
}
```

---

### CT-CR-4 — MED: Failed texture loads permanently cached as checkerboard

**File:** `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`, lines 86–91

**Problem:**
When a texture fails to load, `resolveTexture` substitutes the checkerboard and stores it in the cache:

```kotlin
private fun resolveTexture(source: TextureSource): CachedTexture {
    return cache.get(source) ?: run {
        val bitmap = loader.load(source) ?: checkerboard
        cache.put(source, bitmap)   // caches checkerboard under the original source key
    }
}
```

Once a source fails to load and the checkerboard is cached under its key, all future frames will return the checkerboard for that source immediately from cache — `loader.load` is never called again.

This is fine for permanent errors (missing resource ID, absent asset file). But if a failure is transient — for example, an OOM during first decode that resolves after the garbage collector runs — the texture will never appear because the permanent checkerboard entry prevents a retry.

For `TextureSource.Resource` and `TextureSource.Asset` the failure is likely permanent and caching the fallback is acceptable. For `TextureSource.BitmapSource` it's also acceptable (the bitmap is either recycled or not). But the behavior should be documented so callers know there is no automatic retry.

**Fix (low effort):** Do not cache the fallback. Store it only in the local variable and return it without populating the cache. On the next frame, `loader.load` will be attempted again. Alternatively, maintain a separate "failed" set to avoid thundering-herd retries.

```kotlin
val bitmap = loader.load(source)
return if (bitmap != null) {
    cache.put(source, bitmap)
} else {
    // Don't cache the fallback — allow retry on the next frame
    CachedTexture(checkerboard, BitmapShader(checkerboard, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
}
```

---

### CT-CR-5 — MED: `drawIntoCanvas` bridge allocates a native path even when hook returns `false`

**File:** `isometric-compose/.../runtime/IsometricRenderer.kt`, lines 474–483

**Problem:**
In `renderPreparedScene` (the Compose DrawScope path), the material hook is invoked inside a `drawIntoCanvas` block:

```kotlin
val materialHandled = command.material != null
    && hook != null
    && run {
        var handled = false
        drawIntoCanvas { canvas ->
            val nativePath = command.toNativePath()   // always allocated
            handled = hook.draw(canvas.nativeCanvas, command, nativePath)
        }
        handled
    }
```

`command.toNativePath()` allocates a fresh `android.graphics.Path` on every invocation. This happens even when the hook returns `false` (e.g., for `IsometricMaterial.FlatColor` commands), because path allocation occurs inside `drawIntoCanvas` before `handled` is set.

Additionally, the `drawIntoCanvas` overhead itself (frame layer acquisition) is paid even for commands the hook declines. For a scene with mostly flat-color geometry and a handful of textured faces, this adds unnecessary allocations and call overhead for the majority of commands.

**Fix:** Check whether the material type could plausibly be handled before entering `drawIntoCanvas`:

```kotlin
// Fast-path: skip the bridge entirely for known flat-color materials
val isTexturedMaterial = command.material is IsometricMaterial  // or a lighter check
val materialHandled = isTexturedMaterial && hook != null && run {
    var handled = false
    drawIntoCanvas { canvas ->
        val nativePath = command.toNativePath()
        handled = hook.draw(canvas.nativeCanvas, command, nativePath)
    }
    handled
}
```

Or move path allocation after determining whether draw is needed. This is a performance issue but can also mask logic errors when `toNativePath` returns an empty path (degenerate command with `points = DoubleArray(0)`) — the hook receives a path with a single `moveTo` and no segments, which silently draws nothing.

---

### CT-CR-6 — MED: `computeAffineMatrix` has no guard on `screenPoints.size < 6`

**File:** `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`, lines 121–126

**Problem:**
`drawTextured` guards the `uvCoords` size (`if (uvCoords == null || uvCoords.size < 6) return false`) but not the `screenPoints` size before passing them to `computeAffineMatrix`:

```kotlin
val dst = floatArrayOf(
    screenPoints[0].toFloat(), screenPoints[1].toFloat(),   // [0] and [1] — needs size >= 2
    screenPoints[2].toFloat(), screenPoints[3].toFloat(),   // [2] and [3] — needs size >= 4
    screenPoints[4].toFloat(), screenPoints[5].toFloat(),   // [4] and [5] — needs size >= 6
)
```

`RenderCommand.points` is documented as `[x0, y0, x1, y1, ...]`. For a well-formed Prism face it will always have at least 8 values (4 vertices × 2 coords). However:
- A path with fewer than 3 vertices (triangle degeneration) produces fewer than 6 elements.
- A `CustomRenderNode` can emit a `RenderCommand` with any `points` array, including empty ones (the template `DoubleArray(0)` used before projection).
- If back-face culling is bypassed or a shape is extremely degenerate, `projectAndCull` could produce a command with fewer points.

In practice the template `DoubleArray(0)` commands never reach the renderer because `engine.projectScene()` replaces them, but the missing guard is a latent `ArrayIndexOutOfBoundsException` waiting for an edge-case input.

**Fix:** Add an early-out before `computeAffineMatrix`:

```kotlin
if (command.points.size < 6) return false
```

---

### CT-CR-7 — MED: `BitmapSource` cache key uses `Bitmap` reference identity

**File:** `isometric-shader/.../shader/TextureSource.kt`, line 49
**File:** `isometric-shader/.../shader/render/TextureCache.kt`, line 35

**Problem:**
`TextureSource.BitmapSource` is a `data class`:

```kotlin
data class BitmapSource(val bitmap: Bitmap) : TextureSource
```

`data class` equality delegates to `Bitmap.equals()`. Android's `Bitmap` does not override `equals()` — it uses `Object.equals()` (reference identity). Therefore two `BitmapSource` instances wrapping the same `Bitmap` reference are equal and map to the same cache entry (correct). But two `BitmapSource` instances wrapping *different* `Bitmap` objects with identical pixel data are treated as different keys and will each get their own cache entry.

In a composable that is re-executed on recomposition (e.g., creating a `Bitmap` inside the composable without `remember`), each recomposition produces a new `Bitmap` object and a new `BitmapSource`, resulting in:
1. A new cache miss every recomposition.
2. The old entry remaining in the LRU cache (not evicted until capacity is exceeded).
3. The old `Bitmap` being held alive by the `CachedTexture` value inside the LRU map long after it should be GC'd.

**Severity note:** This is primarily a memory and performance issue rather than a rendering-correctness bug; pixels are still drawn correctly. However, in a scene with a `Bitmap` constructed in a frequently recomposing context, the LRU cache fills with stale entries, effectively negating the cache.

**Fix:** Document that callers must `remember` any `Bitmap` used in `texturedBitmap(...)` to ensure reference stability. Consider adding a lint warning or KDoc note on `BitmapSource`.

---

### CT-CR-8 — LOW: Fuzzy-white threshold in `toColorFilterOrNull` drops near-white tints

**File:** `isometric-shader/.../shader/render/TexturedCanvasDrawHook.kt`, lines 157–158

**Problem:**
```kotlin
internal fun IsoColor.toColorFilterOrNull(): PorterDuffColorFilter? {
    if (r >= 254.0 && g >= 254.0 && b >= 254.0) return null
    ...
}
```

A tint of `IsoColor(254, 254, 254)` is treated as white (no tint). While `IsoColor.WHITE` is `(255, 255, 255)`, the threshold of 254 causes a visually non-zero tint to be silently dropped. This is a minor correctness issue for high-precision tints but is likely intentional as an optimization to avoid GPU state changes for nearly-white tints. If intentional, the threshold and the reasoning should be documented in the function's KDoc.

---

### CT-CR-9 — NIT: `UvCoordProvider` lambda ignores its own `shape` parameter

**File:** `isometric-shader/.../shader/IsometricMaterialComposables.kt`, lines 68–70
**File:** `isometric-compose/.../runtime/IsometricNode.kt`, lines 285–287

**Problem:**
The `UvCoordProvider` fun interface is defined as:

```kotlin
fun interface UvCoordProvider {
    fun provide(shape: Shape, faceIndex: Int): FloatArray?
}
```

The `shape` parameter promises that the provider receives the shape at call time. However, the concrete lambda in `IsometricMaterialComposables.kt` closes over `prism` and ignores the `shape` parameter entirely:

```kotlin
UvCoordProvider { _, faceIndex -> UvGenerator.forPrismFace(prism, faceIndex) }
```

This is functionally correct because `ShapeNode.renderTo` passes `shape` (which is `this.shape`, the same `Prism` instance) as the first argument. But the interface signature implies the provider is shape-agnostic and can be reused across different shapes — which the concrete implementation is not.

If a user writes a `UvCoordProvider` relying on the passed-in `shape` parameter, and the implementation ignores it in favor of a closure, unexpected behavior could result. The `shape` parameter is misleading noise in the interface; the real input is `faceIndex`.

**Recommendation:** Simplify the interface to `fun interface UvCoordProvider { fun provide(faceIndex: Int): FloatArray? }`, or ensure the `shape` parameter is actually forwarded and used in the implementation.

---

### CT-CR-10 — NIT: `remember(context, maxCacheSize)` discards LRU cache on any context change

**File:** `isometric-shader/.../shader/render/ProvideTextureRendering.kt`, line 34

**Problem:**
```kotlin
val hook = remember(context, maxCacheSize) {
    val cache = TextureCache(maxCacheSize)
    ...
}
```

The `context` key means any `Context` object change recreates the `TextureCache`, flushing all decoded bitmaps. In practice, `LocalContext.current` returns an `Activity` context which changes on configuration changes (rotation). However, `context.applicationContext` is passed to `TextureLoader`, so the loader is stable — only the key is unstable.

**Fix:** Use `context.applicationContext` as the remember key so the cache survives Activity recreations:

```kotlin
val appContext = context.applicationContext
val hook = remember(appContext, maxCacheSize) {
    val cache = TextureCache(maxCacheSize)
    val loader = TextureLoader(appContext)
    TexturedCanvasDrawHook(cache, loader)
}
```

This is a NIT because the effect is a one-time cache miss on configuration change rather than a correctness bug.

---

## Summary

The most important correctness defect is **CT-CR-1**: the `uvTransform` property is advertised in the public API DSL, tested in the test suite, but never consulted during rendering. Any caller who specifies `uvTransform = UvTransform(scaleU = 2f)` expecting their texture to tile will see a single-tile texture instead, with no error or warning.

The second most impactful is **CT-CR-2**: the `PerFace` material is entirely non-functional for per-face texture overrides in the Canvas render path — only the `default` material is ever used.

**CT-CR-3** (Paint state leak on exception) should be fixed before the slice ships because it can cause silent rendering corruption on the frame following any exception in the draw loop.

The remaining findings are medium-to-low severity and can be addressed in follow-up iterations.
