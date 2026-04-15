---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: webgpu-uv-transforms
status: complete
stage-number: 4
created-at: "2026-04-15T06:39:08Z"
updated-at: "2026-04-15T11:31:09Z"
metric-files-to-touch: 7
metric-step-count: 8
has-blockers: false
revision-count: 2
tags: [webgpu, texture, uv, transform, wgsl, mat3x2]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-webgpu-uv-transforms.md
  siblings:
    - 04-plan-api-design-fixes.md
    - 04-plan-webgpu-textures.md
    - 04-plan-per-face-materials.md
  implement: 05-implement-webgpu-uv-transforms.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders webgpu-uv-transforms"
---

# Plan: webgpu-uv-transforms

## Current State

`TextureTransform` (scale, offset, rotation) is fully implemented on the Canvas path via
`BitmapShader.setLocalMatrix(T^-1)` in `TexturedCanvasDrawHook`. In the WebGPU path it is
**silently discarded** — zero references to `TextureTransform` exist in `isometric-webgpu`.

The WebGPU UV pipeline runs through the **M5 compute shader** (`TriangulateEmitShader`), not
the fragment shader:

1. `GpuTextureManager.uploadUvRegionBuffer()` packs `(uvOffsetU, uvOffsetV, uvScaleU, uvScaleV)` —
   4 floats / 16 bytes per face — into the `sceneUvRegions` buffer (binding 5).
   This is the **atlas region** only; no user transform.
2. The M5 WGSL reads `sceneUvRegions[faceIdx]` as `vec4<f32>` and applies:
   `atlasUV = baseUV * uvSc + uvOff`
3. The vertex shader and fragment shader receive already-transformed UVs and call
   `textureSample` directly — no UV work in the fragment shader.

**Key invariant discovered:** `minBindingSize = 0` on all buffer bindings means the
`GPUBindGroupLayout` does NOT need to be recreated when the per-face stride grows from
16 to 24 bytes. Only the GPU buffer and bind group need to be recreated.

## Likely Files / Areas to Touch

- `isometric-webgpu/src/main/kotlin/.../webgpu/pipeline/SceneDataPacker.kt`
  — add `UV_REGION_STRIDE = 24` to `SceneDataLayout`
- `isometric-webgpu/src/main/kotlin/.../webgpu/shader/TriangulateEmitShader.kt`
  — WGSL: change binding 5 from `array<vec4<f32>>` to `array<mat3x2<f32>>`;
    replace 2-line atlas math with one matrix-vector multiply
- `isometric-webgpu/src/main/kotlin/.../webgpu/texture/GpuTextureManager.kt`
  — expand `uploadUvRegionBuffer()`: 16→24 bytes/face; add `resolveTextureTransform()`;
    add `packUvRegionMatrix()` with IDENTITY fast path + full composition path
- `isometric-webgpu/src/test/kotlin/.../webgpu/texture/GpuTextureManagerUvTransformTest.kt`
  — new: 5 unit tests covering IDENTITY, tiling, rotation, offset, PerFace
- `isometric-webgpu/src/test/kotlin/.../webgpu/shader/TriangulateEmitShaderUvTest.kt`
  — new: WGSL string content tests (mat3x2 type, matrix-multiply pattern, no old var names)
- `isometric-webgpu/src/main/kotlin/.../webgpu/texture/GpuTextureBinder.kt`
  — change sampler `addressModeU` and `addressModeV` from `AddressMode.ClampToEdge` to
    `AddressMode.Repeat` (one line; no pipeline layout change needed)
- `.maestro/textured-webgpu-uv.yaml`
  — new: Maestro flow for visual AC verification in Full WebGPU mode

No changes to: fragment shader, vertex shader, `GpuRenderPipeline`,
`SceneDataPacker.packInto()`, or any public API surface.

## Proposed Change Strategy

**Compose user transform + atlas region into a single `mat3x2<f32>` on the CPU.**

Instead of carrying atlas (scale+offset) and user transform (scale+offset+rotation) as
separate data, pre-compose them in `GpuTextureManager.uploadUvRegionBuffer()` into one
6-float affine matrix. The M5 shader reads one `mat3x2<f32>` and does one matrix-vector
multiply per vertex. No trig in the shader.

**Matrix composition math (for implementor reference):**

```
User transform (center-based, around UV center (0.5, 0.5)):
  col0 = (scaleU * cosθ,   scaleU * sinθ)     ← x-axis basis
  col1 = (-scaleV * sinθ,  scaleV * cosθ)     ← y-axis basis
  col2 = (0.5*(1 - uc0x - uc1x) + offsetU,   ← tx
          0.5*(1 - uc0y - uc1y) + offsetV)    ← ty

where uc0x = scaleU*cosθ, uc1x = -scaleV*sinθ, uc0y = scaleU*sinθ, uc1y = scaleV*cosθ

Compose with atlas (diagonal scale + offset):
  composed_col0 = (atlasScaleU * uc0x,          atlasScaleV * uc0y)
  composed_col1 = (atlasScaleU * uc1x,          atlasScaleV * uc1y)
  composed_col2 = (atlasScaleU * tx + atlasOffU, atlasScaleV * ty + atlasOffV)

IDENTITY fast path (scaleU=1, scaleV=1, offsetU=0, offsetV=0, rotationDegrees=0):
  col0 = (atlasScaleU, 0f)
  col1 = (0f, atlasScaleV)
  col2 = (atlasOffU, atlasOffV)

WGSL application:
  let uv_k = sceneUvRegions[faceIdx] * vec3<f32>(base_uv_k, 1.0)
```

## Step-by-Step Plan

### Step 1 — Add `UV_REGION_STRIDE` constant to `SceneDataLayout`

**File:** `SceneDataPacker.kt`

Add `const val UV_REGION_STRIDE = 24` to the `SceneDataLayout` object, alongside the
existing `FACE_DATA_BYTES = 144` and `TRANSFORMED_FACE_BYTES = 96` constants. This is
a named anchor for all downstream code that allocates or indexes into the UV region buffer.

```kotlin
object SceneDataLayout {
    const val FACE_DATA_BYTES = 144
    const val TRANSFORMED_FACE_BYTES = 96
    const val UV_REGION_STRIDE = 24   // NEW: mat3x2<f32> = 3 × vec2<f32> = 6 × f32 = 24 bytes
    const val NO_TEXTURE = -1
}
```

---

### Step 2 — Fix sampler `AddressMode` in `GpuTextureBinder.kt`

**File:** `GpuTextureBinder.kt`

**Why first:** UV values produced by `packUvRegionMatrix()` may exceed [0,1] when `scaleU > 1`
(tiling). If the sampler uses `AddressMode.ClampToEdge`, those values are clamped to the texture
edge — tiling renders as a solid border color. This must be fixed before the WGSL and packing
changes are made, so that correctness is verifiable immediately.

**Change:** In the sampler descriptor construction (a `val` created once at `GpuTextureBinder`
construction, never recreated per-frame), change both address modes:

```kotlin
// BEFORE:
addressModeU = AddressMode.ClampToEdge,
addressModeV = AddressMode.ClampToEdge,

// AFTER:
addressModeU = AddressMode.Repeat,
addressModeV = AddressMode.Repeat,
```

**Why `Repeat` for all faces (including IDENTITY):**
- For IDENTITY faces, UV values stay in [0,1] — `ClampToEdge` and `Repeat` produce identical output.
- A single `Repeat` sampler requires no pipeline recompile, no bind group layout change, and no
  per-draw sampler selection logic (`minBindingSize=0` means the `GPUBindGroupLayout` is
  indifferent to sampler changes).
- A two-sampler approach would require pipeline recompile or dynamic bind group rebuilding —
  unnecessary complexity for zero visual gain on IDENTITY faces.

**Auto-derived layout:** `GpuTextureBinder` obtains its bind group layout via
`GPURenderPipeline.getBindGroupLayout(0)`, not hardcoded. Sampler descriptor changes do not
affect this layout derivation.

---

### Step 3 — Update `TriangulateEmitShader.kt` WGSL (WGSL-first)

**File:** `TriangulateEmitShader.kt`

**3a. Change binding 5 type declaration:**

```wgsl
// BEFORE:
@group(0) @binding(5) var<storage, read> sceneUvRegions: array<vec4<f32>>;

// AFTER:
@group(0) @binding(5) var<storage, read> sceneUvRegions: array<mat3x2<f32>>;
```

**3b. Replace the UV computation pattern.**

The current WGSL has 9 lines to replace (3 variable declarations + 6 per-vertex UV computations).
These are confirmed verbatim from `TriangulateEmitShader.kt` (auto-review 2026-04-15):

```wgsl
// BEFORE — remove all 9 of these lines:
let uvRegion = sceneUvRegions[key.originalIndex];
let uvOff = uvRegion.xy;   // (uvOffsetU, uvOffsetV)
let uvSc  = uvRegion.zw;   // (uvScaleU, uvScaleV)
// ... NDC coordinate computations and uvPack0/1/2 loads remain untouched ...
let uv0 = vec2<f32>(uvPack0.x, uvPack0.y) * uvSc + uvOff;
let uv1 = vec2<f32>(uvPack0.z, uvPack0.w) * uvSc + uvOff;
let uv2 = vec2<f32>(uvPack1.x, uvPack1.y) * uvSc + uvOff;
let uv3 = vec2<f32>(uvPack1.z, uvPack1.w) * uvSc + uvOff;
let uv4 = vec2<f32>(uvPack2.x, uvPack2.y) * uvSc + uvOff;
let uv5 = vec2<f32>(uvPack2.z, uvPack2.w) * uvSc + uvOff;

// AFTER — replace with these 7 lines (net: -2 lines):
let uvMatrix = sceneUvRegions[key.originalIndex];  // mat3x2<f32>
let uv0 = uvMatrix * vec3<f32>(uvPack0.x, uvPack0.y, 1.0);
let uv1 = uvMatrix * vec3<f32>(uvPack0.z, uvPack0.w, 1.0);
let uv2 = uvMatrix * vec3<f32>(uvPack1.x, uvPack1.y, 1.0);
let uv3 = uvMatrix * vec3<f32>(uvPack1.z, uvPack1.w, 1.0);
let uv4 = uvMatrix * vec3<f32>(uvPack2.x, uvPack2.y, 1.0);
let uv5 = uvMatrix * vec3<f32>(uvPack2.z, uvPack2.w, 1.0);
```

> **`uvPack0/1/2` remain** — these are `sceneUvCoords` reads that pack base UV coordinates.
> They are NOT removed; only `uvRegion`, `uvOff`, `uvSc`, and the 6 per-vertex lines change.
> `key.originalIndex` confirmed correct index variable (auto-review verified).

**3c.** The 9-line BEFORE block is entirely replaced by the 7-line AFTER block from 3b —
there are no orphaned variables. `uvRegion`, `uvOff`, and `uvSc` are fully removed as part of
that replacement. The `uvPack0/1/2` reads stay. No other lines change.

---

### Step 4 — Update `GpuTextureManager.kt` Kotlin packing

**File:** `GpuTextureManager.kt`

**4a. Add `resolveTextureTransform(cmd: RenderCommand): TextureTransform`:**

```kotlin
private fun resolveTextureTransform(cmd: RenderCommand): TextureTransform {
    val effective = when (val m = cmd.material) {
        is IsometricMaterial.PerFace -> m.faceMap[cmd.faceType] ?: m.default
        else -> m
    }
    return when (effective) {
        is IsometricMaterial.Textured -> effective.transform
        else -> TextureTransform.IDENTITY
    }
}
```

Pattern mirrors the existing `resolveAtlasRegion()` method.

**4b. Add `packUvRegionMatrix(buf: ByteBuffer, region: AtlasRegion, transform: TextureTransform)`:**

```kotlin
private fun packUvRegionMatrix(
    buf: ByteBuffer,
    region: AtlasRegion,
    transform: TextureTransform,
) {
    val aScU = region.uvScale[0]
    val aScV = region.uvScale[1]
    val aOffU = region.uvOffset[0]
    val aOffV = region.uvOffset[1]

    if (transform == TextureTransform.IDENTITY) {
        // Fast path: diagonal scale + atlas offset (no trig)
        buf.putFloat(aScU);  buf.putFloat(0f)     // col0
        buf.putFloat(0f);    buf.putFloat(aScV)    // col1
        buf.putFloat(aOffU); buf.putFloat(aOffV)   // col2
        return
    }

    // Full path: compose user transform (rotation around (0.5,0.5)) with atlas
    val thetaRad = transform.rotationDegrees * PI.toFloat() / 180f
    val cosA = cos(thetaRad)
    val sinA = sin(thetaRad)
    val su = transform.scaleU
    val sv = transform.scaleV
    val du = transform.offsetU
    val dv = transform.offsetV

    // User transform columns (scale-rotation, centered at (0.5, 0.5)):
    val uc0x = su * cosA;   val uc0y = su * sinA    // col0
    val uc1x = -sv * sinA;  val uc1y = sv * cosA    // col1
    val tx = 0.5f * (1f - uc0x - uc1x) + du         // col2.x
    val ty = 0.5f * (1f - uc0y - uc1y) + dv         // col2.y

    // Compose: atlas * userTransform → combined mat3x2
    buf.putFloat(aScU * uc0x); buf.putFloat(aScV * uc0y)         // col0
    buf.putFloat(aScU * uc1x); buf.putFloat(aScV * uc1y)         // col1
    buf.putFloat(aScU * tx + aOffU); buf.putFloat(aScV * ty + aOffV) // col2
}
```

**4c. Update `uploadUvRegionBuffer()`:**

Update the capacity sizing and per-face packing. **Important:** `uploadUvRegionBuffer` uses a
`GrowableGpuStagingBuffer` (auto-review confirmed), NOT `ByteBuffer.allocateDirect`. Follow the
existing buffer resize pattern in the method — find the capacity call that currently sizes for
`4 * Float.SIZE_BYTES * faceCount` (16 bytes/face) and update it to use `UV_REGION_STRIDE`:

```kotlin
// OLD capacity (4 floats/face, 16 bytes):
// <follow existing GrowableGpuStagingBuffer capacity call pattern>
//   currently: 4 * Float.SIZE_BYTES * faceCount  →  16 * faceCount

// NEW capacity (6 floats/face, 24 bytes):
// <same call, updated size>:
//   SceneDataLayout.UV_REGION_STRIDE * faceCount  →  24 * faceCount

// Do NOT add .order(ByteOrder.nativeOrder()) — GrowableGpuStagingBuffer handles this internally
// (confirmed: ByteOrder.nativeOrder() is set in GrowableGpuStagingBuffer.kt line 53)

// OLD per-face packing (4 putFloat calls):
buf.putFloat(region.uvOffset[0]); buf.putFloat(region.uvOffset[1])
buf.putFloat(region.uvScale[0]);  buf.putFloat(region.uvScale[1])

// NEW per-face packing (via helper):
val transform = resolveTextureTransform(cmd)
packUvRegionMatrix(buf, region, transform)
```

For `packUvRegionMatrix(buf: ByteBuffer, ...)`: check whether `GrowableGpuStagingBuffer` exposes
a `ByteBuffer`-typed `data` property to pass here, or adapt the signature to accept the staging
buffer directly. The `putFloat` calls in Step 4b use `ByteBuffer` semantics.

The GPU buffer upload call and bind group entry remain the same (`uvRegionBuf` GPUBuffer).
The GPU driver re-allocates because the buffer capacity changed.

---

### Step 5 — Unit tests for UV packing

**New file:** `isometric-webgpu/src/test/kotlin/.../webgpu/texture/GpuTextureManagerUvTransformTest.kt`

> If `GpuTextureManager` has too many Android dependencies to unit-test directly, extract
> `packUvRegionMatrix()` into a `UvRegionPacker` companion object and test it instead.

**5 required tests:**

| Test | Input | Expected output (6 floats in buffer) |
|------|-------|---------------------------------------|
| `identity_writesAtlasOnlyMatrix` | `IDENTITY`, atlas scale=(0.5f,0.5f), offset=(0.1f,0.2f)` | `[0.5, 0, 0, 0.5, 0.1, 0.2]` |
| `tiling2x3_scalesMatrix` | `tiling(2f,3f)`, atlas scale=(1f,1f), offset=(0,0) | `[2.0, 0, 0, 3.0, -0.5, -1.0]` (tx=0.5*(1-2)=-0.5, ty=0.5*(1-3)=-1.0) |
| `rotated90_producesCorrectMatrix` | `rotated(90f)`, atlas scale=(1f,1f), offset=(0,0) | col0=(0,1), col1=(-1,0), col2=(1,0) [cos90=0, sin90=1; tx=0.5*(1-0-(-1))=1.0, ty=0.5*(1-1-0)=0] |
| `offset_shiftsTranslationColumn` | `offset(0.5f, 0f)`, atlas scale=(1f,1f), offset=(0,0) | col0=(1,0), col1=(0,1), col2=(0.5,0) [IDENTITY + offsetU=0.5 adds 0.5 to tx] |
| `perFace_topFaceGetsOwnTransform` | `PerFace` with top face = `tiling(2f,2f)`, side = `IDENTITY`; pack for top face vs side face | top: `tiling(2f,2f)` matrix; side: atlas-only matrix |

---

### Step 6 — WGSL content tests for `TriangulateEmitShader`

**New file:** `isometric-webgpu/src/test/kotlin/.../webgpu/shader/TriangulateEmitShaderUvTest.kt`

Pattern follows existing `TriangulateEmitShaderTest`:

```kotlin
class TriangulateEmitShaderUvTest {
    @Test fun binding5_usesmat3x2Type() {
        assertTrue(
            TriangulateEmitShader.WGSL.contains("array<mat3x2<f32>>"),
            "binding 5 must use mat3x2<f32>"
        )
    }

    @Test fun uvApplication_usesMatrixMultiply() {
        assertTrue(
            TriangulateEmitShader.WGSL.contains("* vec3<f32>"),
            "UV application must use matrix-vector multiply"
        )
    }

    @Test fun oldAtlasVarNames_areAbsent() {
        assertFalse(TriangulateEmitShader.WGSL.contains("uvSc"),  "uvSc var removed")
        assertFalse(TriangulateEmitShader.WGSL.contains("uvOff"), "uvOff var removed")
    }
}
```

---

### Step 7 — Maestro flow for visual AC verification

**New file:** `.maestro/textured-webgpu-uv.yaml`

```yaml
# Maestro flow: verify WebGPU UV transforms in TexturedDemoActivity
# Manually compare screenshots against Canvas mode for AC1–AC4 parity.
appId: io.github.jayteealao.isometric.sample
---
- launchApp:
    appId: io.github.jayteealao.isometric.sample
    clearState: false

# Navigate to TexturedDemoActivity
# (TexturedDemoActivity is not in the main nav — launch directly via adb before running Maestro,
#  or add a navigation entry in the sample app if not exposed in MainActivity)
- assertVisible: "Texture Demo"

# Tap to cycle render modes to Full WebGPU
- tapOn: "WebGPU"
- waitForAnimationToEnd
- takeScreenshot: verify-evidence/webgpu-uv-webgpu-mode

# Cycle back to Canvas for side-by-side reference
- tapOn: "Canvas"
- waitForAnimationToEnd
- takeScreenshot: verify-evidence/webgpu-uv-canvas-mode
```

> **Note:** `TexturedDemoActivity` uses `android:exported="false"`. To use with Maestro, either
> (a) launch it via `adb shell am start` first then run the Maestro flow, or (b) expose it as an
> option in `MainActivity`'s nav for the duration of this slice's verification.
> 
> If `TexturedDemoActivity` doesn't show `TextureTransform` parameters by default, also modify
> `TexturedDemoActivity.kt` temporarily to pass `TextureTransform.tiling(2f, 2f)` on one face
> to make tiling visually detectable in the screenshot (optional; delete after verification).

---

### Step 8 — Build + API check

```bash
./gradlew :isometric-webgpu:compileDebugKotlin \
          :isometric-webgpu:test \
          :isometric-webgpu:apiCheck
```

No public API surface changes expected — all modified classes are `internal`.
`apiCheck` should pass without `apiDump`.

## Test / Verification Plan

### Automated checks

- **Build:** `./gradlew :isometric-webgpu:compileDebugKotlin` — must pass (catches WGSL string
  syntax errors indirectly via Kotlin compile + any type errors in packUvRegionMatrix)
- **Unit tests:** `./gradlew :isometric-webgpu:test` — 5 packing tests + 3 WGSL content tests
  + all existing tests must continue to pass (SceneDataPackerTest: 3 tests,
  TriangulateEmitShaderTest: 2 tests, total existing: 14 JVM unit tests)
- **API check:** `./gradlew :isometric-webgpu:apiCheck` — no new public API expected

### Interactive verification (human-in-the-loop)

**AC1: Scale/tiling parity**
- **Platform:** Android emulator (`emulator-5554` — confirmed running)
- **Setup:** Modify `TexturedDemoActivity` to use `TextureTransform.tiling(2f, 2f)` on the top face
- **Steps:**
  1. `adb shell am start -n io.github.jayteealao.isometric.sample/.TexturedDemoActivity`
  2. Tap to Canvas mode → `adb shell screencap -p /sdcard/canvas-tiling.png && adb pull /sdcard/canvas-tiling.png verify-evidence/`
  3. Tap to Full WebGPU mode → same screencap → `verify-evidence/webgpu-tiling.png`
- **Evidence:** `verify-evidence/canvas-tiling.png` vs `verify-evidence/webgpu-tiling.png`
- **Pass criteria:** Both screenshots show 2× horizontal and vertical tiling; visual output matches.

**AC2: Offset parity**
- Same flow with `TextureTransform(offsetU = 0.5f, offsetV = 0f)`
- Pass: texture shifted 50% horizontally in both Canvas and WebGPU modes.

**AC3: Rotation parity**
- Same flow with `TextureTransform(rotationDegrees = 45f)`
- Pass: texture rotated 45° in both modes.

**AC4: Per-face independent transforms**
- Setup: `perFace { top = texturedResource(src, transform = tiling(2f,2f)); leftSide = texturedResource(src, transform = tiling(1f,3f)) }`
- Pass: top face tiles 2×2, leftSide tiles 1×3 in WebGPU mode.

**AC5: IDENTITY no regression**
- Use current `TexturedDemoActivity` unmodified (no `TextureTransform` set)
- Pass: WebGPU rendering pixel-equivalent to pre-slice output.

## Risks / Watchouts

1. **WGSL variable names (CONFIRMED — auto-review 2026-04-15):**
   - `key.originalIndex` ✓ confirmed correct index into `sceneUvRegions`
   - Base UV names are NOT generic `base_uv_k` — actual code uses `vec2<f32>(uvPack0.x, uvPack0.y)` etc.
     (Step 3b now shows exact BEFORE/AFTER verbatim from the file)
   - `uvSc` WGSL content test: `assertFalse(WGSL.contains("uvSc"))` is safe only if comments
     containing "uvScaleU/V" (which have "uvSc" as a substring) are removed with the block.
     The BEFORE block (Step 3b) includes those comments — ensure the full 9-line block is replaced.

2. **`mat3x2<f32>` column-major storage** — WGSL stores `mat3x2` in column-major order.
   The Kotlin `ByteBuffer.putFloat()` calls must write columns in order: col0.x, col0.y,
   col1.x, col1.y, col2.x, col2.y. A transposed write produces incorrect UV output with no crash.

3. **`GpuTextureManager` constructability** — If `packUvRegionMatrix()` can't be unit-tested
   directly (Android context dependency), extract it to a package-private `UvRegionPacker` object.
   Prefer testable code over convenience.

4. **`AtlasRegion.NONE` handling** — When a face has no texture (`textureIndex == NO_TEXTURE`),
   `resolveAtlasRegion()` returns `AtlasRegion.NONE` with `uvScale=[1,1], uvOffset=[0,0]`.
   `packUvRegionMatrix()` will still pack a matrix for these faces, but the fragment shader's
   `select(textured, in.color, in.textureIndex == 0xFFFFFFFFu)` ignores the UV sample. This is
   correct — wasteful but not incorrect.

5. **`ByteOrder.nativeOrder()` assumption** — Confirm the buffer allocation includes
   `.order(ByteOrder.nativeOrder())`. The existing code may already do this; if not, add it.
   Mixed endianness causes corrupted float values on big-endian systems.

6. **REPEAT tiling mode** (**RESOLVED — addressed in Step 2**) — `GpuTextureBinder.kt`
   currently uses `AddressMode.ClampToEdge` on both U and V. UV values from `packUvRegionMatrix()`
   exceed [0,1] when `scaleU > 1`, which would clamp instead of wrap, making tiling invisible.
   **Fix (Step 2):** Change both address modes to `AddressMode.Repeat`. Single-sampler approach
   chosen: for IDENTITY faces UVs stay in [0,1], so `ClampToEdge == Repeat` — zero visual
   difference. No pipeline recompile needed (`minBindingSize=0`; layout auto-derived via
   `getBindGroupLayout(0)`). Eliminates per-draw sampler selection complexity.

## Dependencies on Other Slices

- **`api-design-fixes`** (complete): provides final `TextureTransform` API — all field names,
  `IDENTITY` constant, factory methods, and `init` validation are stable.
- **`webgpu-textures`** (complete): provides `SceneDataPacker`, `GpuTextureManager`,
  `GpuTextureBinder`, and `TriangulateEmitShader`. All complete; no in-flight changes.

## Assumptions

- `TextureTransform` field names (`scaleU`, `scaleV`, `offsetU`, `offsetV`, `rotationDegrees`)
  are final and stable (verified in `api-design-fixes`).
- `AtlasRegion` has `uvOffset: FloatArray` and `uvScale: FloatArray` (both size 2), as reported
  by the exploration sub-agent.
- The M5 emit shader uses `key.originalIndex` (or equivalent) to index into `sceneUvRegions`.
  Confirm the exact index variable name before Step 2.
- `GpuTextureManager.uploadUvRegionBuffer()` allocates the GPU buffer based on face count
  (not a fixed size). The buffer grows automatically when face count increases.

## Blockers

None. `api-design-fixes` dependency is complete (verified). `isometric-webgpu` module builds
clean. All prerequisite classes exist.

## Freshness Research

**WGSL `mat3x2<f32>` alignment (WebGPU spec, April 2025):**
- AlignOf = 8, SizeOf = 24. Storage buffer stride for `array<mat3x2<f32>>` = 24 bytes. ✓
- `mat3x2 * vec3 → vec2` is valid WGSL (3-col × 2-row matrix times 3-element vector).

**WebGPU `minBindingSize=0` (MDN / WebGPU spec):**
- Buffer binding layout stride is NOT encoded in the `GPUBindGroupLayout`. With
  `minBindingSize=0`, no layout recreation needed when buffer size grows. ✓

**Rotation around center (0.5, 0.5) — derived from standard 2D affine math:**
- Canvas path uses `T^-1` pre-concat (inverse affine). WGSL path uses the forward
  affine matrix composed with the atlas transform. Both produce identical UV output
  when the math is correct — AC1–AC4 verify parity.

**Source:** WebGPU specification §10.3 (Memory Layout), MDN WebGPU API reference,
sub-agent 3 findings (2026-04-15).

## Revision History

### Rev 2 — 2026-04-15T11:31:09Z — Auto-Review
**Mode:** Auto-Review (re-inspected codebase against plan assumptions)
**Issues found:** 4 (2 HIGH, 1 MED, 1 LOW)

| ID | Sev | What was wrong | What was fixed |
|----|-----|----------------|----------------|
| AR-1 | HIGH | Step 3b pseudocode used `base_uv_0..5` — these don't exist in the shader; actual names are inline `vec2<f32>(uvPack0.x, uvPack0.y)` etc. | Replaced entire BEFORE/AFTER block with verbatim WGSL from file |
| AR-2 | HIGH | Step 4c showed `ByteBuffer.allocateDirect` — actual code uses `GrowableGpuStagingBuffer` | Updated Step 4c to describe the actual capacity resize pattern; noted ByteOrder is set internally |
| AR-3 | MED | Step 3c only mentioned removing `uvRegion`; `uvOff`, `uvSc`, and 6 per-vertex UV lines also removed | Rewrote Step 3c to clarify all 9 lines are replaced |
| AR-4 | LOW | Risk 1 still said "pseudonames — read file before editing" after directed fix; `key.originalIndex` had been confirmed and `base_uv_k` confirmed wrong | Updated Risk 1 with confirmed findings; added `uvSc` substring test caveat |

**Confirmed correct (no change needed):**
- `key.originalIndex` ✓, `TriangulateEmitShader.WGSL` constant name ✓
- `TextureTransform` field names ✓, `AtlasRegion.uvOffset/uvScale: FloatArray` ✓
- `UV_REGION_STRIDE` does not exist yet ✓, `cmd.faceType` available ✓
- JUnit 4 + Kotlin Test (test files use `kotlin.test.Test`, `kotlin.test.assertEquals`) ✓

---

### Rev 1 — 2026-04-15T06:54:54Z — Directed Fix
**Mode:** Directed Fix
**Feedback:** `investigate sampler AddressMode` (Risk 6 flagged in original plan as "investigate before coding Step 3")
**Sub-agent investigation findings:**
- `GpuTextureBinder.kt` uses a single `val` sampler with `addressModeU = AddressMode.ClampToEdge, addressModeV = AddressMode.ClampToEdge`
- `AddressMode.Repeat = 0x00000002` confirmed available in vendor source
- Bind group layout is auto-derived via `GPURenderPipeline.getBindGroupLayout(0)` — no hardcoded layout
- `minBindingSize=0` on all buffer bindings — GPUBindGroupLayout does not change on sampler switch
- For IDENTITY faces (UVs in [0,1]), `ClampToEdge == Repeat` — no visual regression

**Changes applied:**
1. Added **Step 2** — change `GpuTextureBinder.kt` sampler to `AddressMode.Repeat` (new; inserts before old Step 2)
2. Added `GpuTextureBinder.kt` to **Likely Files / Areas to Touch**; removed it from "No changes to" line
3. Renumbered old Steps 2–7 → Steps 3–8
4. Resolved **Risk 6** with concrete resolution (was: "investigate before coding"; now: resolved in Step 2)
5. Updated frontmatter: `metric-files-to-touch: 7`, `metric-step-count: 8`, `revision-count: 1`
6. Removed "investigate Risk 6 first" caveat from Recommended Next Stage

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders webgpu-uv-transforms`
  — Plan is complete. All 8 steps are ready; Risk 6 (sampler AddressMode) is resolved in Step 2.
- **Option B:** `/wf-plan texture-material-shaders webgpu-texture-error-callback`
  — Plan the next extension slice in parallel (no dependency on this slice).
