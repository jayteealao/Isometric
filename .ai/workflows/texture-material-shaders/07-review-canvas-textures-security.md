---
id: 07-review-canvas-textures-security
workflow: texture-material-shaders
slice: canvas-textures
date: 2026-04-12
reviewer: security-agent
result: findings
severity_summary:
  critical: 0
  high: 1
  medium: 2
  low: 3
  info: 2
---

# Security Review — canvas-textures slice

Scope: Canvas texture rendering implementation in `isometric-shader`.

Files reviewed:
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TextureLoader.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TextureCache.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TexturedCanvasDrawHook.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/ProvideTextureRendering.kt`
- `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/MaterialDrawHook.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/TextureSource.kt` (cross-reference)

---

## Findings Table

| ID | Area | Severity | Status |
|---|---|---|---|
| CT-SEC-1 | `runCatching` swallows `OutOfMemoryError` | HIGH | Needs fix |
| CT-SEC-2 | No bitmap size limit — OOM via large textures | MEDIUM | Needs fix |
| CT-SEC-3 | Cache bounded by count, not memory footprint | MEDIUM | Needs fix |
| CT-SEC-4 | Cached `BitmapSource` bitmap recycled externally after caching | LOW | Mitigate |
| CT-SEC-5 | `texturedPaint` state left dirty if `drawPath` throws | LOW | Mitigate |
| CT-SEC-6 | `maxCacheSize` has no upper bound guard | LOW | Needs fix |
| CT-SEC-7 | Silent checkerboard fallback masks resource exhaustion events | INFO | Document |
| CT-SEC-8 | Asset path validation: `\` not treated as separator on Android | INFO | Document |

---

## Detailed Findings

### CT-SEC-1 — `runCatching` swallows `OutOfMemoryError` [HIGH]

**Files:** `TextureLoader.kt`, lines 30–36

**Description:**

Both `loadResource` and `loadAsset` use `runCatching { ... }.getOrNull()`:

```kotlin
private fun loadResource(resId: Int): Bitmap? = runCatching {
    BitmapFactory.decodeResource(context.resources, resId)
}.getOrNull()

private fun loadAsset(path: String): Bitmap? = runCatching {
    context.assets.open(path).use { BitmapFactory.decodeStream(it) }
}.getOrNull()
```

Kotlin's `runCatching` catches `Throwable`, not just `Exception`. This means it
silently catches `OutOfMemoryError` thrown during bitmap decode. The `OOM` is
discarded, `null` is returned, and the caller substitutes the checkerboard fallback.

The consequence is three-fold:

1. **Heap degradation is invisible.** After an OOM during decode, the JVM heap may
   be critically low. Execution continues, potentially triggering further OOM failures
   in other allocations, leading to crashes with confusing stack traces far from the
   original cause.

2. **Crash attribution is lost.** Android's crash reporting (Firebase Crashlytics, etc.)
   and `StrictMode` will not see this OOM. The developer has no signal that a texture
   was silently dropped for resource reasons vs. a missing file.

3. **Error amplification.** Once an OOM is swallowed, the next call may also OOM,
   which is also swallowed, and so on. This can turn a single bad texture into a
   full render-failure cascade where every tile shows checkerboard with no diagnostic
   output.

Swallowing `OutOfMemoryError` also violates Android and JVM best practice: OOM is a
`VirtualMachineError` indicating the runtime is in an undefined state and should be
allowed to propagate so the OS can cleanly terminate the process.

**Recommendation:**

Separate `OutOfMemoryError` (and `Error` in general) from expected `IOException`/
`NotFoundException` decode failures. Re-throw errors; only swallow `Exception`:

```kotlin
private fun loadResource(resId: Int): Bitmap? = try {
    BitmapFactory.decodeResource(context.resources, resId)
} catch (e: Exception) {
    Log.w(TAG, "Failed to decode resource $resId", e)
    null
}

private fun loadAsset(path: String): Bitmap? = try {
    context.assets.open(path).use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) {
    Log.w(TAG, "Failed to decode asset '$path'", e)
    null
}
```

At minimum, re-throw `Error` subclasses explicitly:

```kotlin
private fun loadResource(resId: Int): Bitmap? = runCatching {
    BitmapFactory.decodeResource(context.resources, resId)
}.onFailure { t ->
    if (t is Error) throw t  // re-throw OOM, StackOverflowError, etc.
    Log.w(TAG, "Failed to decode resource $resId", t)
}.getOrNull()
```

---

### CT-SEC-2 — No bitmap size limit — OOM via large textures [MEDIUM]

**Files:** `TextureLoader.kt`, lines 30–36

**Description:**

`BitmapFactory.decodeResource` and `BitmapFactory.decodeStream` are called without
a `BitmapFactory.Options` object. There is no cap on `inSampleSize`, `inMaxWidth`,
`inMaxHeight`, or `inBitmap` reuse. A caller who supplies a 4K texture asset
(e.g., a 3840×2160 PNG) will cause the library to attempt to allocate a
`3840 × 2160 × 4 = ~31.6 MB` bitmap synchronously on the main thread.

With `TextureCache.maxSize = 20` (default), the worst-case in-memory footprint from
textures alone is `20 × 31.6 MB = ~632 MB` — far beyond typical Android app heap
limits (usually 256–512 MB). Even a single 4K texture on a low-RAM device can trigger
an OOM.

Because `TextureLoader` is public within the module and `TextureSource.Asset` accepts
arbitrary paths to app-bundled assets, a developer who accidentally bundles a
high-resolution image (e.g., from a design export workflow) will OOM without any
warning or guard rail.

**Recommendation:**

1. **Add a configurable max-dimension cap to `TextureLoader`:**

```kotlin
internal class TextureLoader(
    private val context: Context,
    private val maxTextureDimension: Int = 512,
) {
    private fun loadResource(resId: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, opts)
        opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight)
        opts.inJustDecodeBounds = false
        BitmapFactory.decodeResource(context.resources, resId, opts)
    } catch (e: Exception) { null }

    private fun computeSampleSize(w: Int, h: Int): Int {
        var sample = 1
        while (w / sample > maxTextureDimension || h / sample > maxTextureDimension) {
            sample *= 2
        }
        return sample
    }
}
```

2. **Expose `maxTextureDimension` through `ProvideTextureRendering`** so callers can
   tune it for their target devices.

3. **Document the expected texture size contract** in `TextureSource.Asset` and
   `TextureSource.Resource` KDoc: "Textures should be power-of-two tiles ≤ 512×512 px
   for best performance and memory use."

---

### CT-SEC-3 — Cache bounded by count, not memory footprint [MEDIUM]

**Files:** `TextureCache.kt`, lines 29–54; `ProvideTextureRendering.kt`, line 29

**Description:**

`TextureCache` enforces `maxSize` as a count of `CachedTexture` entries:

```kotlin
override fun removeEldestEntry(...): Boolean = size > maxSize
```

This is a count-LRU, not a byte-budget-LRU. The memory consumed by `maxSize` entries
scales with the dimensions of the decoded bitmaps, not with the count. Two scenarios
are problematic:

**Scenario A — Large textures.** The default `maxSize = 20` with 4K bitmaps yields
~632 MB (see CT-SEC-2). Even with reasonably-sized 256×256 textures, 20 entries =
20 × (256×256×4) = ~5 MB, which is fine — but nothing prevents a caller from
supplying mixed-size textures that happen to be large.

**Scenario B — Cache inflation.** `maxCacheSize: Int` in `ProvideTextureRendering`
has no upper bound:
```kotlin
fun ProvideTextureRendering(
    maxCacheSize: Int = 20,
    ...
)
```
A caller can pass `maxCacheSize = 10_000` and the `require(maxSize > 0)` check in
`TextureCache.init` will pass without complaint, creating a cache that can hold
thousands of large bitmaps.

**Recommendation:**

1. **Change the eviction policy to byte-budget LRU.** Use the count-of-bytes approach:

```kotlin
internal class TextureCache(val maxBytes: Long = 50L * 1024 * 1024) { // 50 MB default
    private var currentBytes: Long = 0

    fun put(source: TextureSource, bitmap: Bitmap): CachedTexture {
        val bitmapBytes = bitmap.allocationByteCount.toLong()
        // evict until there is room
        while (currentBytes + bitmapBytes > maxBytes && cache.isNotEmpty()) {
            val eldestKey = cache.keys.first()
            val eldest = cache.remove(eldestKey)!!
            currentBytes -= eldest.bitmap.allocationByteCount.toLong()
        }
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val entry = CachedTexture(bitmap, shader)
        cache[source] = entry
        currentBytes += bitmapBytes
        return entry
    }
}
```

2. **As an interim fix, cap `maxCacheSize` in `ProvideTextureRendering`:**

```kotlin
require(maxCacheSize in 1..200) {
    "maxCacheSize must be between 1 and 200, got $maxCacheSize"
}
```

---

### CT-SEC-4 — Cached `BitmapSource` bitmap recycled externally after caching [LOW]

**Files:** `TextureLoader.kt`, lines 24–27; `TexturedCanvasDrawHook.kt`, lines 69–70

**Description:**

For `TextureSource.BitmapSource`, `TextureLoader.load()` calls `ensureNotRecycled()`
at load time, then returns the same `Bitmap` reference:

```kotlin
is TextureSource.BitmapSource -> {
    source.ensureNotRecycled()
    source.bitmap
}
```

That bitmap is stored by reference inside `CachedTexture`. If the caller recycles the
bitmap after this point (after the cache has stored it), the cached entry holds a
recycled `Bitmap`. On the next cache hit, `TexturedCanvasDrawHook.drawTextured()`
accesses `cached.bitmap.width` and `cached.bitmap.height`:

```kotlin
val texW = cached.bitmap.width
val texH = cached.bitmap.height
```

Accessing `width` or `height` on a recycled bitmap does not throw — it returns the
last known values. However, the `BitmapShader` wrapping the recycled bitmap will
produce a native crash or undefined rendering when `nativeCanvas.drawPath()` is
called with the stale shader.

The original `SEC-3` finding (from `07-review-security.md`) identified the
construction-time risk; this finding identifies the post-cache-insertion risk
that `ensureNotRecycled()` at load time does not address.

**Recommendation:**

Add a guard immediately before the draw call in `drawTextured`:

```kotlin
val bitmap = cached.bitmap
if (bitmap.isRecycled) {
    // Evict the stale entry and fall back to checkerboard
    cache.invalidate(source)
    return resolveTexture(checkerboardSource) // or inline checkerboard
}
```

Or, for `BitmapSource` specifically, store a defensive copy in `TextureCache.put`:

```kotlin
// In TextureLoader.load for BitmapSource:
is TextureSource.BitmapSource -> {
    source.ensureNotRecycled()
    source.bitmap.copy(source.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
}
```

This trades one allocation per `BitmapSource` texture (acceptable for tile textures)
for complete immunity to external recycle. Expose this as an opt-in via a
`TextureLoader` constructor flag if zero-copy is a hard requirement.

---

### CT-SEC-5 — `texturedPaint` state left dirty if `drawPath` throws [LOW]

**Files:** `TexturedCanvasDrawHook.kt`, lines 75–83

**Description:**

`texturedPaint` is a shared, reused `Paint` object. After configuring it with a
shader and color filter, the draw method nulls them out after the draw call:

```kotlin
texturedPaint.shader = cached.shader
texturedPaint.colorFilter = material.tint.toColorFilterOrNull()

nativeCanvas.drawPath(nativePath, texturedPaint)   // <-- if this throws ...

texturedPaint.shader = null       // ... these lines are skipped
texturedPaint.colorFilter = null
```

If `nativeCanvas.drawPath` throws an unchecked exception (e.g., `IllegalArgumentException`
from a degenerate path, or a native crash surface as a Java exception), the cleanup
lines are skipped. On the next invocation of `draw()` from any command, `texturedPaint`
will carry the previous frame's shader and color filter, causing a different face to
be rendered with the wrong texture.

This is a state-pollution bug, not a crash bug, but it can produce visually incorrect
output that is difficult to reproduce (it only manifests after a prior draw exception).

**Recommendation:**

Use `try/finally` to guarantee cleanup:

```kotlin
texturedPaint.shader = cached.shader
texturedPaint.colorFilter = material.tint.toColorFilterOrNull()
try {
    nativeCanvas.drawPath(nativePath, texturedPaint)
} finally {
    texturedPaint.shader = null
    texturedPaint.colorFilter = null
}
return true
```

---

### CT-SEC-6 — `maxCacheSize` has no upper bound guard [LOW]

**Files:** `ProvideTextureRendering.kt`, line 29; `TextureCache.kt`, line 29

**Description:**

`ProvideTextureRendering(maxCacheSize: Int = 20)` passes the caller-supplied value
directly to `TextureCache(maxSize)`. The only validation is `require(maxSize > 0)`.
There is no upper bound.

A caller can write:

```kotlin
ProvideTextureRendering(maxCacheSize = Int.MAX_VALUE) { ... }
```

This creates a cache that never evicts. Combined with large textures (CT-SEC-2), this
is a trivial OOM path. Even a value of `1000` with modest 128×128 textures
(128×128×4 = 65 KB each) allocates 65 MB, which exceeds the default heap on many
budget Android devices.

The concern is not just accidental misconfiguration — it also matters if
`ProvideTextureRendering` is called inside a loop or called with a parameter derived
from an external configuration source.

**Recommendation:**

Add an upper bound in `ProvideTextureRendering`:

```kotlin
init {
    require(maxCacheSize in 1..500) {
        "maxCacheSize must be in 1..500, got $maxCacheSize. " +
        "For byte-budget control, see TextureCache(maxBytes)."
    }
}
```

This is a soft defence until the byte-budget LRU (CT-SEC-3) is implemented.

---

### CT-SEC-7 — Silent checkerboard fallback masks resource exhaustion events [INFO]

**Files:** `TexturedCanvasDrawHook.kt`, line 88

**Description:**

When texture load fails for any reason, the hook substitutes the checkerboard bitmap
silently:

```kotlin
val bitmap = loader.load(source) ?: checkerboard
cache.put(source, bitmap)
```

The checkerboard is also cached under the failed `source` key. This means:

1. Subsequent frames do not retry the failing source — it is permanently
   replaced by the checkerboard in the LRU until eviction.
2. There is no log, no metric, and no callback to notify the caller that their
   texture failed to load.
3. If the failure was an OOM (which `runCatching` swallows per CT-SEC-1), the
   app appears to be functioning correctly from the outside while the heap is
   in a degraded state.

This is primarily an observability concern: a developer deploying this library may
never notice that a texture path is wrong or a resource ID is invalid because the
render output looks "almost correct" with checkerboard substitution.

**Recommendation:**

At minimum, add a `Log.w` in `resolveTexture` when the fallback is used:

```kotlin
private fun resolveTexture(source: TextureSource): CachedTexture {
    return cache.get(source) ?: run {
        val bitmap = loader.load(source)
        if (bitmap == null) {
            Log.w(TAG, "Texture load failed for $source; using checkerboard fallback")
        }
        cache.put(source, bitmap ?: checkerboard)
    }
}
```

Optionally, expose an `onTextureLoadFailed: ((TextureSource) -> Unit)?` callback on
`ProvideTextureRendering` so the host application can surface this in its own
telemetry.

---

### CT-SEC-8 — Asset path `\` treated as a component separator by validation, but not by `AssetManager` [INFO]

**Files:** `TextureSource.kt`, lines 31–37 (cross-reference); `TextureLoader.kt`, line 35

**Description:**

`TextureSource.Asset.init` splits the path on both `"/"` and `"\\"` to detect `..`
components:

```kotlin
require(".." !in path.split("/", "\\")) {
    "Asset path must not contain '..' components, got '$path'"
}
```

On Android (Linux kernel), `\` is a valid filename character, not a path separator.
`AssetManager.open(path)` treats `\` as a literal character in the filename, not as
a directory separator. This means:

- A path of `"textures\\..\\grass.png"` is rejected by the validation (split on `\\`
  yields `["textures", "..", "grass.png"]`, and `..` is found).
- But `AssetManager` would treat the entire `"textures\\..\\grass.png"` as a single
  filename with backslashes — a file that almost certainly does not exist, resulting
  in a `FileNotFoundException`.

The validation is therefore **overly strict in one direction** (rejecting `\` in
paths even though `AssetManager` treats it as a literal char) and **correctly strict**
in the traversal direction (because `AssetManager` also doesn't do traversal on Android
anyway).

There is no exploitable security gap here — `AssetManager` is sandboxed to the
`assets/` directory. The observation is that the backslash split in the validation
creates a mismatch with the actual resolver semantics, which could confuse developers
on mixed-OS development setups who try to supply paths using Windows separators.

**Recommendation:**

Document the mismatch or normalize early:

```kotlin
// In TextureSource.Asset.init:
// Normalize to forward slashes before validation (cross-platform dev hygiene).
// AssetManager always uses '/' on device regardless of OS.
val normalized = path.replace('\\', '/')
require(!normalized.startsWith('/')) { ... }
require(".." !in normalized.split('/')) { ... }
```

This makes the validation model match the resolver model and avoids surprising
developers who accidentally use backslashes.

---

## Summary

The `canvas-textures` slice has a narrow attack surface — it is a pure client-side
rendering library with no network I/O, no IPC, and no data persistence. All findings
are within the process boundary.

The most actionable items are:

- **CT-SEC-1 (HIGH):** Replace `runCatching` with `try/catch(Exception)` so that
  `OutOfMemoryError` is not silently swallowed. This is a one-line change with
  meaningful safety impact.

- **CT-SEC-2 (MEDIUM) + CT-SEC-3 (MEDIUM):** Together these form the OOM resource
  exhaustion path. Neither has a size guard. Implementing even a simple
  `inSampleSize` cap and a byte-budget eviction policy would close the primary
  avenue for accidental heap exhaustion.

- **CT-SEC-5 (LOW):** The `try/finally` fix for paint state cleanup is trivial and
  should be applied unconditionally.

The `INFO`-level findings (CT-SEC-7, CT-SEC-8) require no code changes but benefit
from documentation updates.
