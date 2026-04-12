---
schema: workflow-review/1.0
type: security-review
slug: 07-review-webgpu-textures-security
slice-slug: webgpu-textures
review-command: >
  Review the webgpu-textures slice (HEAD~3...HEAD) for security:
  GPU resource leaks, unsafe buffer operations, WGSL injection, DoS via
  large textures, insecure sampler defaults, and native memory safety.
status: complete
updated-at: 2026-04-12
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
result: findings
tags:
  - security
  - gpu
  - texture
  - webgpu
  - resource-leak
  - buffer-safety
  - denial-of-service
refs:
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureStore.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureBinder.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricFragmentShader.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/WebGpuSceneRenderer.kt
  - app/src/main/kotlin/io/github/jayteealao/isometric/sample/WebGpuSampleActivity.kt
---

# Security Review — webgpu-textures slice

Scope: GPU texture upload, bind group creation, fragment shader, and render
pipeline creation introduced in `feat/texture` (HEAD~3…HEAD), +177/-91 lines.

Files reviewed:
- `GpuRenderPipeline.kt` — render pipeline + shader module lifecycle
- `IsometricFragmentShader.kt` — WGSL fragment shader
- `GpuTextureBinder.kt` — sampler + bind group factory
- `GpuTextureStore.kt` — GPU texture creation and bitmap upload
- `GpuFullPipeline.kt` — texture upload orchestration + tex-index buffer
- `WebGpuSceneRenderer.kt` — frame loop + cleanup
- `WebGpuSampleActivity.kt` — sample usage

---

## Findings Table

| ID | Area | Severity | Status |
|---|---|---|---|
| WT-SEC-1 | Integer overflow in `byteCount = w * h * 4` for large bitmaps | HIGH | Needs fix |
| WT-SEC-2 | No upper bound on bitmap dimensions — unbounded GPU memory allocation | HIGH | Needs fix |
| WT-SEC-3 | `identityHashCode` bitmap cache key — hash collision skips upload | MEDIUM | Needs fix |
| WT-SEC-4 | `_textureBindGroup` leaks old bind group on `clearScene` before pipeline init | MEDIUM | Needs fix |
| WT-SEC-5 | `textureView` not closed on non-success surface texture status paths | LOW | Needs fix |
| WT-SEC-6 | `uploadBitmap` does not enforce `TextureUsage` mipmap constraint | LOW | Document |
| WT-SEC-7 | WGSL shader source uses Kotlin string interpolation — injection surface exists | INFO | Document |
| WT-SEC-8 | Async pipeline creation workaround: Scudo risk on fallback sync path not gated | INFO | Document |

---

## Detailed Findings

### WT-SEC-1 — Integer overflow in `byteCount = w * h * 4` for large bitmaps [HIGH]

**File:** `GpuTextureStore.kt`, line 74

**Description:**

```kotlin
val byteCount = w * h * 4
val pixels = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
```

`w`, `h`, and the literal `4` are all `Int`. The multiplication is performed in
32-bit signed integer arithmetic before any widening. On a device that supports
the maximum WebGPU texture size (32768×32768), the product overflows:

```
32768 × 32768 = 1,073,741,824    (still within Int range)
1,073,741,824 × 4 = 4,294,967,296  (overflows Int, wraps to 0)
```

`ByteBuffer.allocateDirect(0)` succeeds silently, returning a zero-capacity
buffer. The subsequent `bitmap.copyPixelsToBuffer(pixels)` then throws
`BufferOverflowException` — but only at the copy step, not at allocation.
On devices with a 16384-texel texture limit, an ARGB_8888 bitmap at that
size already consumes `16384 × 16384 × 4 = 1,073,741,824` bytes (~1 GB),
causing an `OutOfMemoryError` from `allocateDirect` without overflow. In
both cases the failure mode is not gracefully handled — the exception
propagates out of `uploadBitmap` and is caught by `uploadTextures`' caller,
silently substituting the fallback texture, with no diagnostic log.

There is also a secondary overflow at `GpuFullPipeline.kt:391`:

```kotlin
val requiredBytes = faceCount * 4
ByteBuffer.allocateDirect(requiredBytes)
```

This is a lower-risk path because face counts in the millions are unrealistic
for this library, but the pattern is the same.

**Recommendation:**

Widen to `Long` before multiplication:

```kotlin
// GpuTextureStore.kt
val byteCount = w.toLong() * h.toLong() * 4L
require(byteCount <= Int.MAX_VALUE) {
    "Bitmap too large: ${w}x${h} requires $byteCount bytes (max ${Int.MAX_VALUE})"
}
val pixels = ByteBuffer.allocateDirect(byteCount.toInt()).order(ByteOrder.nativeOrder())
```

Add the same widening at `GpuFullPipeline.kt:384` for the GPU buffer size
(it already casts to `Long` but the multiplication is still `Int`):

```kotlin
// Before: size = (faceCount * 4).toLong()
// After:
size = faceCount.toLong() * 4L
```

---

### WT-SEC-2 — No upper bound on bitmap dimensions — unbounded GPU memory allocation [HIGH]

**File:** `GpuTextureStore.kt`, lines 68–88

**Description:**

`uploadBitmap` validates only that the bitmap is `ARGB_8888`; it places no
constraint on `bitmap.width` or `bitmap.height`:

```kotlin
fun uploadBitmap(bitmap: Bitmap): GPUTexture {
    require(bitmap.config == Bitmap.Config.ARGB_8888) { ... }
    val w = bitmap.width
    val h = bitmap.height
    // No dimension check — w and h can be arbitrarily large
    ...
}
```

A caller who passes a `Bitmap` decoded from a high-resolution source (e.g., a
full-resolution camera photo retrieved from the gallery, a 4K design export, or
a bitmap loaded without `inSampleSize`) will cause `allocateDirect` to attempt
to allocate a direct buffer equal to the full pixel footprint. For a 12 MP
camera image (4032×3024): `4032 × 3024 × 4 ≈ 48.8 MB` of direct native
memory, plus the same allocation for the GPU texture itself via `writeTexture`.
Peak native memory during upload is approximately `2 × bitmap_bytes`.

Because `GpuFullPipeline.uploadTextures` only re-uploads when the bitmap
identity changes, and because `BitmapSource.ensureNotRecycled()` is called
before upload, this path can be triggered repeatedly by repeatedly assigning
new large `BitmapSource` materials to scene objects — each change triggers a
release-old/allocate-new cycle, transiently holding two full copies of the
large bitmap in native memory.

The GPU device also has a `maxTextureDimension2D` limit. Attempting to create
a texture larger than this limit is a GPU API error. Dawn will fire an
uncaptured-error callback and the subsequent `writeTexture` on the invalid
`GPUTexture` handle causes undefined behaviour.

**Recommendation:**

1. Add a configurable `maxTextureDimension` cap to `GpuTextureStore` (default
   `2048` or `4096` is appropriate for isometric tile rendering):

```kotlin
internal class GpuTextureStore(
    private val ctx: GpuContext,
    val maxTextureDimension: Int = 2048,
) : AutoCloseable {
    fun uploadBitmap(bitmap: Bitmap): GPUTexture {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { ... }
        require(bitmap.width <= maxTextureDimension && bitmap.height <= maxTextureDimension) {
            "Bitmap dimensions ${bitmap.width}x${bitmap.height} exceed " +
            "maxTextureDimension=$maxTextureDimension. Scale the bitmap before upload."
        }
        ...
    }
}
```

2. Document the size contract in `TextureSource.BitmapSource` KDoc:
   "The bitmap should be ≤ 2048×2048 for best GPU compatibility. Very large
   bitmaps should be down-sampled before wrapping in `BitmapSource`."

3. Log a `Log.w` when a large bitmap is detected (even if not rejected) so
   callers can see the issue in logcat without a crash.

---

### WT-SEC-3 — `identityHashCode` bitmap cache key — hash collision skips upload [MEDIUM]

**File:** `GpuFullPipeline.kt`, lines 346–347

**Description:**

```kotlin
val identity = System.identityHashCode(bitmap)
if (identity == lastUploadedBitmapIdentity) return
```

`System.identityHashCode` returns a 32-bit hash derived from the object's
memory address. The JVM does not guarantee uniqueness — two distinct `Bitmap`
objects can and do share the same `identityHashCode` value, particularly after
GC moves objects in the heap. On ART (Android's runtime), `identityHashCode`
is computed once on first call and stored in the object header, but only if
the object has not yet been relocated by the GC. This means:

- **Hash collision scenario:** `bitmapA` is uploaded, `lastUploadedBitmapIdentity =
  hashA`. The GC collects `bitmapA`. A new `bitmapB` (different image, different
  memory) is allocated and happens to receive `hashA` as its `identityHashCode`.
  The upload cache check `identity == lastUploadedBitmapIdentity` returns `true`,
  and the new bitmap is **silently skipped**. The GPU texture continues to show
  the previous bitmap's content.

- **False skip after scene transition:** The same hash collision can occur when
  a scene switches from one textured material to another if the old bitmap was
  collected between scenes.

`lastUploadedBitmapIdentity` is typed as `Any?` but assigned an `Int`:

```kotlin
private var lastUploadedBitmapIdentity: Any? = null
...
lastUploadedBitmapIdentity = identity  // autoboxed Int
...
if (identity == lastUploadedBitmapIdentity) return  // Int == Any? — compares via equals
```

The comparison uses `equals` (boxed-int equality) not reference equality, so
this is not a reference identity check — it is genuinely an `Int` equality
check, which makes the collision scenario as described above entirely possible.

**Recommendation:**

Cache the bitmap reference itself rather than its hash code:

```kotlin
private var lastUploadedBitmap: Bitmap? = null

// In uploadTextures, replace the identity-hash check:
if (bitmap === lastUploadedBitmap) return   // reference equality — no collision possible

// On upload:
lastUploadedBitmap = bitmap

// On release:
lastUploadedBitmap = null
```

Reference equality (`===`) is O(1), requires no hashing, and cannot produce
false cache hits. The field type becomes `Bitmap?` instead of `Any?`, which is
also cleaner. The existing bitmap-lifecycle concern (caller recycles the bitmap
while it is still the "last uploaded") is not worsened by this change —
`lastUploadedBitmap` holds only a reference, not ownership.

---

### WT-SEC-4 — `_textureBindGroup` leaks old bind group on `clearScene` before `textureBindGroup` first access [MEDIUM]

**File:** `GpuFullPipeline.kt`, lines 408–422

**Description:**

`clearScene()` calls:

```kotlin
_textureBindGroup?.close()
_textureBindGroup = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
```

`textureBinder.buildBindGroup()` calls `checkNotNull(bindGroupLayout)` — it
will throw `IllegalStateException` if `bindGroupLayout` has not been set yet.
This can occur if `clearScene()` is called before `GpuRenderPipeline.ensurePipeline()`
completes (e.g. if the scene is cleared very early in the initialisation
sequence, or if `clearScene` is called after a re-initialisation where
`bindGroupLayout` was nulled by a previous `close()`).

When `buildBindGroup` throws, `_textureBindGroup` has already been closed on
the preceding line. The field is left in a `null` state (the assignment to
`textureBinder.buildBindGroup(...)` never completes). The lazy getter for
`textureBindGroup` then tries to build the fallback bind group again:

```kotlin
val textureBindGroup: GPUBindGroup
    get() = _textureBindGroup ?: run {
        val bg = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
        _textureBindGroup = bg
        bg
    }
```

This second call also throws `IllegalStateException` for the same reason,
except now the throw originates inside the render pass setup path, propagating
past `pass.setBindGroup(0, gp!!.textureBindGroup)` and potentially leaving the
render pass in an un-ended state (pass.end() and pass.close() are not in a
`finally` block). An un-ended render pass may cause a native Dawn assertion
or memory corruption on some driver versions.

**Recommendation:**

Guard `clearScene`'s bind group rebuild with a null check on `bindGroupLayout`:

```kotlin
fun clearScene() {
    lastFaceCount = 0
    lastPaddedCount = 0
    // ... write zero indirect args ...

    // Only rebuild bind group if the pipeline is initialized;
    // otherwise the lazy getter will build it on first frame access.
    if (textureBinder.bindGroupLayout != null) {
        _textureBindGroup?.close()
        _textureBindGroup = textureBinder.buildBindGroup(textureStore.fallbackTextureView)
    } else {
        _textureBindGroup?.close()
        _textureBindGroup = null
    }
}
```

Separately, `drawFrame` in `WebGpuSceneRenderer` should end the render pass in
a `finally` block to guard against exceptions from `setBindGroup` or
`setVertexBuffer`:

```kotlin
val pass = renderEncoder.beginRenderPass(...)
try {
    pass.setPipeline(pipeline.pipeline!!)
    if (shouldDraw) {
        pass.setBindGroup(0, gp!!.textureBindGroup)
        pass.setVertexBuffer(0, gp.vertexBuffer)
        pass.drawIndirect(gp.indirectArgsBuffer, 0L)
    }
} finally {
    pass.end()
    pass.close()
}
```

---

### WT-SEC-5 — `textureView` not closed on non-success surface texture status paths [LOW]

**File:** `WebGpuSceneRenderer.kt`, lines 404–514

**Description:**

Inside `drawFrame`, the surface texture's `textureView` is created and closed
only inside the `SuccessOptimal`/`SuccessSuboptimal` branch:

```kotlin
val surfaceTexture = surface.getCurrentTexture()
// ...
when (surfaceTexture.status) {
    SuccessOptimal, SuccessSuboptimal -> {
        val textureView = surfaceTexture.texture.createView()
        // ...
        textureView.close()
        surfaceTexture.texture.close()
    }
    Outdated -> reconfigureSurface()          // no textureView created here
    Lost     -> recreateSurface(androidSurface) // no textureView created here
    Timeout  -> Unit
    Error    -> throw IllegalStateException(...)
}
```

This specific path is safe because `textureView` is only created inside the
success branch. However, `surfaceTexture.texture` is also only closed in the
success branch. For the `Outdated`, `Lost`, `Timeout`, and `Error` status
paths, `surfaceTexture.texture` is obtained from `getCurrentTexture()` but
never closed. This leaks one surface texture handle per frame for every
non-success frame.

On `Error`, the code throws before any close can happen. If `reconfigureSurface`
or `recreateSurface` throw (e.g. a second device-lost during reconfiguration),
the close calls in the success branch have already been skipped, but there is
no resource to release in that case either — so the main concern is the
non-exceptional `Outdated`/`Timeout` paths.

**Recommendation:**

Close `surfaceTexture.texture` unconditionally via `try/finally`:

```kotlin
val surfaceTexture = surface.getCurrentTexture()
try {
    when (surfaceTexture.status) {
        SuccessOptimal, SuccessSuboptimal -> {
            val textureView = surfaceTexture.texture.createView()
            try {
                // ... render work ...
            } finally {
                textureView.close()
            }
            if (surfaceTexture.status == SuccessSuboptimal) reconfigureSurface()
        }
        Outdated  -> reconfigureSurface()
        Lost      -> recreateSurface(androidSurface)
        Timeout   -> Unit
        Error     -> throw IllegalStateException("Surface getCurrentTexture returned Error")
    }
} finally {
    surfaceTexture.texture.close()
}
```

---

### WT-SEC-6 — `uploadBitmap` does not enforce `TextureUsage` mipmap constraint [LOW]

**File:** `GpuTextureStore.kt`, lines 97–104

**Description:**

`createTexture` always creates textures with `mipLevelCount = 1` (the implicit
default) and `TextureUsage.TextureBinding or TextureUsage.CopyDst`. The
sampler in `GpuTextureBinder` is configured with:

```kotlin
mipmapFilter = MipmapFilterMode.Nearest,
```

When `mipmapFilter` is `Nearest` and the texture has exactly 1 mip level, this
is correct and benign — Dawn silently uses the single base level. However, the
sampler is shared for all textures (including any future textures created with
mip chains). If a future code path adds a texture with `mipLevelCount > 1` but
uses the same shared sampler, the `Nearest` mipmap filter and the auto-derived
bind group layout must both be compatible with the new texture.

This is not a current vulnerability but it is a latent incompatibility trap:
the shared sampler is created at `GpuTextureBinder` init time, before any
texture is known, and cannot be changed without recreating the bind group
layout.

**Recommendation:**

Document the constraint explicitly in `GpuTextureBinder` and `GpuTextureStore`
KDoc: "All textures bound at `@group(0) binding 0` must have `mipLevelCount = 1`.
The shared sampler uses `MipmapFilterMode.Nearest` which is only correct for
single-level textures." Add a `require` in `uploadBitmap` if mip generation
is ever added:

```kotlin
// future-proofing guard:
require(gpuTex.mipLevelCount == 1u) {
    "Only single-level textures are compatible with the shared sampler"
}
```

---

### WT-SEC-7 — WGSL shader source uses Kotlin string interpolation — injection surface exists [INFO]

**File:** `IsometricFragmentShader.kt`, line 17

**Description:**

The fragment shader WGSL source string uses Kotlin string interpolation to
inline the entry point name constant:

```kotlin
val WGSL: String = """
    ...
    @fragment
    fn ${ENTRY_POINT}(in: FragmentInput) -> @location(0) vec4<f32> {
    ...
""".trimIndent()
```

`ENTRY_POINT` is a `const val` hardcoded to `"fragmentMain"`, so there is no
runtime injection risk with the current code. However, the use of interpolation
in shader source establishes a pattern that could be extended by future
contributors to interpolate caller-controlled values (e.g. a user-supplied
texture count, a bind group index, or a format string).

If such an interpolated value ever contained WGSL metacharacters — for example,
a closing brace followed by malicious WGSL constructs — it could alter shader
semantics. While this is unlikely for an internal rendering library, the pattern
is worth flagging because WGSL compilation errors from bad injected data only
surface as device-loss or silent rendering failures, not as Kotlin exceptions.

**Recommendation:**

Either remove the interpolation and use a plain string literal for the entry
point name, or document the invariant: "Only `const val` values may be
interpolated into WGSL source strings. Never interpolate runtime values,
user-supplied strings, or anything that is not a compile-time constant."

---

### WT-SEC-8 — Async pipeline workaround: sync fallback is not gated [INFO]

**File:** `GpuRenderPipeline.kt`, lines 22–31, 124

**Description:**

The KDoc and inline comments clearly explain that `createRenderPipeline` (sync
version) triggers a Scudo double-free on Adreno 750 with Dawn alpha04 when the
fragment shader declares texture/sampler bindings, and that
`createRenderPipelineAndAwait` (async) is used as the workaround.

The code correctly uses only the async path. However, there is no compile-time
or runtime guard preventing a future refactor from switching back to the sync
call. The bug is driver-specific and silent at the Kotlin level (it manifests
as a SIGABRT from Scudo in native code, not as a catchable exception).

**Recommendation:**

Add a comment directly adjacent to the async call site noting the specific
consequence of switching to sync, making it unlikely to be removed in a
"harmless-looking" cleanup:

```kotlin
// WARNING: Do NOT replace with device.createRenderPipeline (synchronous).
// The synchronous variant triggers a Scudo double-free (SIGABRT) on Adreno 750
// with Dawn alpha04 when the fragment shader declares texture/sampler bindings.
// See: WEBGPU_CRASH_INVESTIGATION.md § Bug 1.
val rp = device.createRenderPipelineAndAwait(descriptor)
```

The comment already present is good; this recommendation is to make it even
more prominent with a conventional `WARNING:` marker that static analysis tools
and future reviewers can search for.

---

## Resource Lifecycle Audit

The following table records the lifecycle correctness of all GPU resources
introduced in this slice. A resource is "safe" if it is closed in all
reachable teardown paths; "conditional" if closure depends on a prior
condition being met; "leaky" if at least one reachable path omits close.

| Resource | Owner | Closed in close() | Closed on error path | Result |
|---|---|---|---|---|
| `GpuTextureStore.fallbackTexture` | `GpuTextureStore` | Yes (via `ownedTextures`) | N/A (init only) | Safe |
| `GpuTextureStore.fallbackTextureView` | `GpuTextureStore` | Yes (explicit) | N/A | Safe |
| User-uploaded `GPUTexture` (from `uploadBitmap`) | `GpuTextureStore.ownedTextures` | Yes (loop) | Skipped on OOM | Conditional |
| `uploadedTextureView` | `GpuFullPipeline` | Yes (`releaseUploadedTexture`) | N/A | Safe |
| `_textureBindGroup` | `GpuFullPipeline` | Yes (explicit) | See WT-SEC-4 | Conditional |
| `GpuTextureBinder.sampler` | `GpuTextureBinder` | Yes (`sampler.close()`) | N/A | Safe |
| `GpuRenderPipeline.textureBindGroupLayout` | `GpuRenderPipeline` | Yes | N/A | Safe |
| `GpuRenderPipeline.vertexModule` | `GpuRenderPipeline` | Yes | N/A | Safe |
| `GpuRenderPipeline.fragmentModule` | `GpuRenderPipeline` | Yes | N/A | Safe |
| `surfaceTexture.texture` (per-frame) | `WebGpuSceneRenderer.drawFrame` | Conditional (success path only) | No (see WT-SEC-5) | Leaky |
| `texIndexGpuBuffer` | `GpuFullPipeline` | Yes | N/A | Safe |

---

## Summary

The `webgpu-textures` slice has a narrow external attack surface — it is a
pure client-side GPU rendering library with no network I/O or IPC. All findings
are within the process boundary. There are no blocker-severity issues.

The two HIGH findings both relate to bitmap size safety in `GpuTextureStore`:

- **WT-SEC-1 (HIGH):** The `byteCount = w * h * 4` computation overflows `Int`
  for bitmaps with dimensions near 32768 px, silently allocating a zero-byte
  buffer. Fix requires a one-line widening to `Long` arithmetic.

- **WT-SEC-2 (HIGH):** There is no dimension cap on bitmaps passed to
  `uploadBitmap`. A caller supplying a multi-megapixel bitmap will allocate
  tens or hundreds of MB of native memory and will likely exceed the device's
  `maxTextureDimension2D` limit, resulting in a GPU API error and undefined
  texture state.

The two MEDIUM findings address correctness gaps that can produce silent visual
or resource errors:

- **WT-SEC-3 (MEDIUM):** The `identityHashCode`-based upload cache can produce
  false cache hits when two distinct `Bitmap` objects share the same hash
  code, silently displaying stale GPU texture content. Fix is to cache the
  bitmap reference directly using `===`.

- **WT-SEC-4 (MEDIUM):** Calling `clearScene()` before `bindGroupLayout` is
  initialised throws `IllegalStateException` inside a bind group rebuild that
  has already closed the previous bind group, leaving the field `null` and
  potentially causing an un-ended render pass on the next frame.

The three LOW/INFO findings (WT-SEC-5 through WT-SEC-8) are lower-priority
hygiene items: a per-frame GPU handle leak on non-success surface status codes,
a mipmap filter documentation gap, a WGSL string interpolation pattern note,
and a comment recommendation for the async-pipeline safety workaround.
