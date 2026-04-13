---
id: 07-review-sample-demo-security
workflow: texture-material-shaders
slice: sample-demo
date: 2026-04-13
reviewer: security-agent
result: findings
severity_summary:
  critical: 0
  high: 0
  medium: 1
  low: 3
  info: 3
---

# Security Review — sample-demo slice

Scope: commits `970b91e..feat/texture` HEAD (`git diff 970b91e...feat/texture`).

Files reviewed:
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/io/github/jayteealao/isometric/sample/TextureAssets.kt`
- `app/src/main/kotlin/io/github/jayteealao/isometric/sample/TexturedDemoActivity.kt`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/SceneProjector.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureStore.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureManager.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GrowableGpuStagingBuffer.kt` (supporting)

---

## Findings Table

| ID | Area | Severity | Status |
|---|---|---|---|
| SD-SEC-1 | Exported activity has no permission or intent-filter guard | MEDIUM | Needs decision |
| SD-SEC-2 | `FLAG_KEEP_SCREEN_ON` held unconditionally for the activity lifetime | LOW | Needs fix |
| SD-SEC-3 | `GrowableGpuStagingBuffer.ensureCapacity` — unchecked `entryCount * entryBytes` integer overflow | LOW | Needs fix |
| SD-SEC-4 | `uploadUvCoordsBuffer`: `buf.limit(requiredBytes)` with `requiredBytes` derived from potentially mismatched `faceCount` vs buffer capacity | LOW | Review |
| SD-SEC-5 | `TextureAssets` bitmaps never recycled — held for application lifetime | INFO | Document |
| SD-SEC-6 | `FLAG_KEEP_SCREEN_ON` is a sample-only activity but could be confused for a pattern to adopt | INFO | Document |
| SD-SEC-7 | `SceneProjector.emitFace` `faceType` parameter not validated at the interface boundary | INFO | Document |

---

## Detailed Findings

### SD-SEC-1 — Exported activity has no permission or intent-filter guard [MEDIUM]

**File:** `app/src/main/AndroidManifest.xml`, lines 30–32

**Description:**

`TexturedDemoActivity` is declared with `android:exported="true"` and no `<intent-filter>`,
no `android:permission`, and no `android:protectionLevel` guard:

```xml
<activity android:name=".TexturedDemoActivity"
    android:label="Textured Materials"
    android:exported="true" />
```

This matches the pattern of the other sample activities (`ViewSampleActivity`,
`ComposeActivity`, `RuntimeApiActivity`, `WebGpuSampleActivity`,
`InteractionSamplesActivity`) — all exported without guards. The pattern is consistent
within the sample app, but it means any app on the same device (or an ADB shell) can
start `TexturedDemoActivity` directly via an explicit intent:

```bash
adb shell am start -n io.github.jayteealao.isometric.sample/.TexturedDemoActivity
```

**Risk assessment for this slice:**

`TexturedDemoActivity` does **not** read any intent extras, does not process
`getIntent().getData()`, does not load files, and does not expose any IPC return
values. The activity creates only compile-time-constant procedural bitmaps and a
fixed 4×4 grid scene. The render mode toggle is driven purely by in-process
`remember { mutableStateOf(...) }` — there is no mechanism for an external caller
to influence what is rendered or read back any rendered output.

Consequence of malicious launch: the activity opens with the textured grid scene.
No information is disclosed and no state is mutated outside the process. The risk
is confined to a denial-of-service by flooding the foreground (task stack
pollution), which is a standard Android limitation for all exported activities
without guards.

**This is a sample app, not a library.** The finding is MEDIUM rather than HIGH
because the activity contains no exploitable data path. However, if this sample
ships as part of a distribution that users install on production devices (e.g., a
companion demo APK), the exported surface should be minimized.

**Recommendation:**

Option A — Restrict to same-signature callers (preferred for demo APKs):
```xml
<activity android:name=".TexturedDemoActivity"
    android:label="Textured Materials"
    android:exported="true"
    android:permission="android.permission.NONE" />
```
That will block third-party callers at the platform level.

Option B — Mark `exported="false"` if the activity is only meant to be launched
from `MainActivity` within the same process:
```xml
<activity android:name=".TexturedDemoActivity"
    android:label="Textured Materials"
    android:exported="false" />
```
Since `MainActivity` launches it with an explicit component name (same app),
`exported="false"` is sufficient and is the lowest-privilege correct choice.

Option C — Audit all other sample activities and apply the same policy
consistently. The current inconsistency (some activities appear in the launcher
menu, others are internal detail) makes intent-accessibility harder to reason
about.

---

### SD-SEC-2 — `FLAG_KEEP_SCREEN_ON` held unconditionally for the activity lifetime [LOW]

**File:** `TexturedDemoActivity.kt`, line 34

**Description:**

```kotlin
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

This flag is set in `onCreate` and never cleared. The flag causes the device
display to stay on indefinitely while the activity is in the foreground. There is
no corresponding `clearFlags` in `onPause`, `onStop`, or `onDestroy`.

**Risk scenarios:**

1. **Battery drain.** If the activity is launched (e.g., by an ADB script or a
   third-party app exploiting the exported surface — see SD-SEC-1) and left
   running, the screen will never sleep. On a device with a FOSS or enterprise
   profile, this can trigger battery-protection alerts or policy violations.

2. **Interaction with other activities.** If the user presses Home and later the
   activity is brought back to the foreground without being recreated (e.g., via
   recents), the flag persists across sessions. While Android normally clears
   `FLAG_KEEP_SCREEN_ON` when the activity leaves the foreground, the *intent* of
   keeping it only for active rendering is not expressed in code — a future
   refactor could inadvertently persist the flag across configuration changes.

3. **Pattern propagation.** Using `window.addFlags` in `onCreate` without a
   corresponding `clearFlags` in `onPause` is a pattern that, if copied to
   production screens, becomes a real battery drain bug.

**Recommendation:**

Clear the flag in `onPause` and re-set it in `onResume` to confine it to active
foreground time:

```kotlin
override fun onResume() {
    super.onResume()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

override fun onPause() {
    super.onPause()
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
```

Or, for Compose, use the `keepScreenOn` modifier when it becomes stable, or scope
it to the composable lifecycle with `DisposableEffect`.

---

### SD-SEC-3 — Integer overflow in `GrowableGpuStagingBuffer.ensureCapacity` GPU size calculation [LOW]

**File:** `GrowableGpuStagingBuffer.kt`, lines 41–46

**Description:**

```kotlin
val newCapacity = entryCount * 2
gpuBuffer = ctx.device.createBuffer(
    GPUBufferDescriptor(
        usage = usage,
        size = (newCapacity * entryBytes).toLong(),
    )
)
```

Both multiplications are performed in `Int` arithmetic before the final `.toLong()`
cast. If `entryCount` is large enough, `entryCount * 2` overflows a signed 32-bit
integer and wraps to a negative value. The subsequent `* entryBytes` may then
produce a small positive long (depending on the sign and magnitude), causing
`createBuffer` to allocate a GPU buffer far smaller than needed.

**Triggerable in the sample-demo slice:**

`uploadUvCoordsBuffer` calls `ensureCapacity(faceCount, 48)`. The 4×4 demo grid
has 16 prisms × 3 faces = 48 faces maximum, so `entryCount` is at most 48 in the
demo. The overflow cannot be triggered by this demo's hard-coded scene.

**Risk for callers of the library API:**

A scene with more than `Int.MAX_VALUE / 2 = 1,073,741,823` faces would overflow.
That is not reachable in practice. However, a scene with `entryCount = 89_478_486`
and `entryBytes = 48` would yield `newCapacity = 178_956_972`, and
`178_956_972 * 48 = 8_589_934_656` which overflows `Int` to a negative value, then
`.toLong()` gives the wrong answer. The practical threshold for `entryBytes = 48` is
`entryCount > 44_739_242` faces — unreachable today but worth guarding.

**Recommendation:**

Perform all size arithmetic in `Long` from the outset:

```kotlin
fun ensureCapacity(entryCount: Int, entryBytes: Int) {
    require(entryCount > 0) { "entryCount must be > 0" }
    require(entryBytes > 0) { "entryBytes must be > 0" }
    if (entryCount > capacity) {
        gpuBuffer?.close()
        val newCapacity = entryCount.toLong() * 2L
        val gpuSizeBytes = newCapacity * entryBytes.toLong()
        require(gpuSizeBytes <= Int.MAX_VALUE.toLong()) {
            "Requested GPU buffer size $gpuSizeBytes exceeds Int.MAX_VALUE"
        }
        gpuBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(usage = usage, size = gpuSizeBytes)
        ).also { it.setLabel(label) }
        capacity = newCapacity.toInt() // safe: we checked above
    }
    val requiredBytes = entryCount.toLong() * entryBytes.toLong()
    val cpu = cpuBuffer
    if (cpu == null || cpu.capacity() < requiredBytes) {
        require(requiredBytes * 2L <= Int.MAX_VALUE.toLong()) {
            "CPU staging buffer size ${requiredBytes * 2} exceeds Int.MAX_VALUE"
        }
        cpuBuffer = ByteBuffer.allocateDirect((requiredBytes * 2L).toInt())
            .order(ByteOrder.nativeOrder())
    }
}
```

---

### SD-SEC-4 — `uploadUvCoordsBuffer` `buf.limit(requiredBytes)` may silently truncate if CPU buffer is over-allocated [LOW]

**File:** `GpuTextureManager.kt`, lines 262–280

**Description:**

```kotlin
val requiredBytes = faceCount * entryBytes       // Int × Int, same overflow risk as SD-SEC-3
val buf = uvCoordsBuf.cpuBuffer!!
buf.rewind()
buf.limit(requiredBytes)
for (i in 0 until faceCount) {
    val uv = scene.commands[i].uvCoords
    if (uv != null && uv.size >= 8) {
        for (j in 0 until 12) {
            buf.putFloat(if (j < uv.size) uv[j] else 0f)
        }
    } else {
        // 12 putFloat calls = 48 bytes per face
    }
}
buf.rewind()
ctx.queue.writeBuffer(uvCoordsBuf.gpuBuffer!!, 0L, buf)
```

**Issue A — Same `Int` overflow as SD-SEC-3.** `faceCount * entryBytes` is `Int *
Int`. For `entryBytes = 48` this overflows at `faceCount > 44 million`, which is
unreachable but structurally the same defect.

**Issue B — Conditional UV branch writes 12 floats (48 bytes) but only checks
`uv.size >= 8`.** A `uvCoords` array with exactly 8 elements (indices 0–7) will
write indices 0–7 from the array and then pad indices 8–11 with `0f`. This is
correct and intentional (per the doc comment). However, a `uvCoords` array with
exactly 9, 10, or 11 elements will write those extra elements mixed with zeros.
The shader expects a strict `(u0,v0,u1,v1,u2,v2,u3,v3,u4,v4,u5,v5)` layout. A
9-element `uvCoords` (9 floats, 4.5 UV pairs) is a malformed input that produces
a garbled UV layout without any error or warning. This is not a buffer overrun (the
loop is bounded to `j in 0 until 12`) but it is silent data corruption.

**Issue C — `scene.commands[i]` is accessed without bounds checking against
`scene.commands.size`.** `ensureCapacity` sizes the GPU buffer to `faceCount`, and
the loop iterates `faceCount` times, but `scene.commands` is supplied externally.
If `scene.commands.size < faceCount`, the loop will throw an
`IndexOutOfBoundsException`. The call site in `uploadTextures` passes the same
`faceCount` for all three upload functions, and `faceCount` is presumably derived
from `scene.commands.size` by the caller — but this is not enforced at the callee
boundary.

**Recommendation:**

1. Apply the `Long` arithmetic fix from SD-SEC-3 for `requiredBytes`.
2. Add a precondition at the top of `uploadUvCoordsBuffer`:
   ```kotlin
   require(faceCount <= scene.commands.size) {
       "faceCount $faceCount > scene.commands.size ${scene.commands.size}"
   }
   ```
3. Guard the `uvCoords` size: only use UV data if `uv.size` is a multiple of 2 and
   at most 12. Log a warning otherwise:
   ```kotlin
   if (uv != null && uv.size >= 8 && uv.size <= 12) { ... }
   else if (uv != null) {
       Log.w(TAG, "Face $i uvCoords.size=${uv.size} is out of range [8,12]; using default UVs")
       // fall through to default branch
   }
   ```

---

### SD-SEC-5 — `TextureAssets` bitmaps held for application lifetime via `object` + `lazy` [INFO]

**File:** `TextureAssets.kt`, lines 12–16

**Description:**

```kotlin
internal object TextureAssets {
    val grassTop: Bitmap by lazy { buildGrassTop() }
    val dirtSide: Bitmap by lazy { buildDirtSide() }
    ...
}
```

`object` in Kotlin is a singleton backed by a class-level static instance, loaded
when first accessed and never unloaded for the lifetime of the process. The two
`Bitmap` instances (64×64 ARGB_8888 = 16 KB each, 32 KB total) are held in the
static `INSTANCE` field and are never recycled. This is negligible in absolute
terms for a sample app (32 KB), but the pattern is worth noting:

1. If `TextureAssets` were copied to a production module with larger bitmaps, the
   static reference would prevent GC collection even when the activity is destroyed.
2. The bitmaps are passed to the `TexturedCanvasDrawHook` / `GpuTextureStore` which
   hold a reference to the same `Bitmap` instance (not a copy). If either path
   mutates or recycles the bitmap (e.g., via a future refactor), the `object` would
   hand out a recycled bitmap on next access.

**Recommendation:**

Document the intentional lifetime at the declaration site:

```kotlin
/**
 * Procedurally generated texture bitmaps for the textured-demo sample.
 * Bitmaps are 64×64 ARGB_8888 (16 KB each). They are held for the process
 * lifetime via [lazy]; callers must NOT recycle them.
 */
internal object TextureAssets { ... }
```

For production use, prefer generating bitmaps in a `ViewModel` scoped to the
activity lifecycle, or supply them as `TextureSource.BitmapSource` with an explicit
owner that can recycle them on `onCleared`.

---

### SD-SEC-6 — `FLAG_KEEP_SCREEN_ON` set as a bare `window.addFlags` call — risky pattern for copy-paste [INFO]

**File:** `TexturedDemoActivity.kt`, line 34

**Description:**

This is a documentation note supplementing SD-SEC-2. The call:

```kotlin
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

appears in `onCreate` with no comment explaining *why* the screen is kept on (it is
needed to prevent the display from sleeping during a GPU benchmark or rendering
demo). Without the comment, developers who use this activity as a template for new
screens may include the flag by default, unknowingly shipping apps that prevent
screen sleep.

**Recommendation:**

Add a one-line comment:

```kotlin
// Keep display active during rendering demo — remove in production if not required.
window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
```

---

### SD-SEC-7 — `SceneProjector.emitFace` `faceType` parameter not validated at the interface boundary [INFO]

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/SceneProjector.kt`, line 42

**Description:**

The newly added `faceType: PrismFace?` parameter is a nullable enum. The interface
contract does not state what implementations must do when `faceType` is `null` vs.
a specific `PrismFace` value, beyond the implicit assumption that `null` means "no
face type".

In `GpuTextureManager.resolveAtlasRegion`, the handling is:

```kotlin
is IsometricMaterial.PerFace -> {
    val face = cmd.faceType
    if (face != null) m.resolve(face) else m.default
}
```

This is correct — `null` maps to `m.default`. However, because `faceType` is part
of a public interface (`SceneProjector`), any third-party implementor of the
interface could pass an unexpected `PrismFace` value. `PrismFace` is an enum, so
the set of valid values is closed. There is no validation risk here.

The concern is that the KDoc on `emitFace` does not document the semantics of the
new parameter, so future implementors have no guidance on when to set it vs. leave
it null.

**Recommendation:**

Add KDoc to the `faceType` parameter:

```kotlin
/**
 * @param faceType  The face of the prism this triangle set represents, or `null`
 *                  if face-specific material resolution is not applicable (e.g.,
 *                  non-prism geometries or flat-color materials).
 *                  Used by [IsometricMaterial.PerFace] to select the correct
 *                  sub-material for the face.
 */
```

---

## Summary

The `sample-demo` slice has a very narrow attack surface. All key paths use
compile-time-constant data (procedural bitmaps, a fixed 4×4 grid), and no user
input reaches any rendering or buffer-sizing code path.

**Actionable findings in priority order:**

1. **SD-SEC-1 (MEDIUM):** Decide whether `TexturedDemoActivity` should be
   `exported="false"` (same-process launch only) or remain exported with a
   permission guard. Given that no intent data is processed, `exported="false"` is
   the simplest and safest option.

2. **SD-SEC-3 (LOW):** Fix the `Int` overflow in `GrowableGpuStagingBuffer.ensureCapacity`
   by moving size arithmetic into `Long` before the final cast. This is a one-time
   fix that closes the class-level defect for all current and future buffer users.

3. **SD-SEC-4 (LOW):** Add a bounds precondition in `uploadUvCoordsBuffer` (and
   consistently in `uploadTexIndexBuffer` / `uploadUvRegionBuffer`) to guard against
   `faceCount > scene.commands.size`. Add a size-range guard for `uvCoords` to
   prevent silent data corruption on malformed inputs.

4. **SD-SEC-2 (LOW):** Move `FLAG_KEEP_SCREEN_ON` to `onResume`/`onPause` to
   confine it to active foreground time.

The three `INFO` findings require only documentation or comment changes and carry
no direct security risk.
