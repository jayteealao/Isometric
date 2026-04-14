---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: api-design-fixes
status: complete
stage-number: 4
created-at: "2026-04-13T23:17:09Z"
updated-at: "2026-04-14T00:03:13Z"
metric-files-to-touch: 16
metric-step-count: 16
has-blockers: false
revision-count: 2
tags: [api-design, texture, material, review]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-api-design-fixes.md
  siblings:
    - 04-plan-material-types.md
    - 04-plan-uv-generation.md
    - 04-plan-canvas-textures.md
    - 04-plan-webgpu-textures.md
    - 04-plan-per-face-materials.md
    - 04-plan-sample-demo.md
  implement: 05-implement-api-design-fixes.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders api-design-fixes"
---

# Plan: api-design-fixes

## Current State

All six original slices are implemented. This slice fixes all 25 findings from
`07-review-webgpu-textures-api.md` before PR #8 merges to `feat/webgpu`.

**Architecture context (supersedes po-answers 2026-04-11):**
`IsoColor : MaterialData` in `isometric-core` — no `FlatColor` wrapper type. `Shape(geometry,
material: MaterialData)` in `isometric-compose` replaces `Shape(geometry, color: IsoColor)`.
`IsometricMaterial` sealed over `Textured` + `PerFace` only.

**Key observations from codebase inspection:**
- `ShapeNode` (`isometric-compose/.../IsometricNode.kt`) already has both `color: IsoColor` and
  `material: MaterialData?` fields — the compose `Shape()` sets only `color`; the shader `Shape()` sets
  both. After the change, compose `Shape()` sets both.
- `SceneDataPacker` and `GpuTextureManager` (webgpu) use only `is IsometricMaterial.Textured`
  and `is IsometricMaterial.PerFace` branches with `else ->` fallbacks — removing `FlatColor` from
  `IsometricMaterial` requires no changes in either webgpu file beyond the `BitmapSource` rename.
- `TexturedCanvasDrawHook.draw()` has an exhaustive `when(material)` over 3 cases including
  `FlatColor` — this must be updated when `FlatColor` is removed.
- Named-arg `color = IsoColor` call sites (must rename to `material =`): 4 test files,
  ~10 call sites total. All in `isometric-compose/src/androidTest` and `src/test`.
- `TextureCache.put()` hardcodes `BitmapShader(..., TileMode.CLAMP, TileMode.CLAMP)`. For
  `TextureTransform` tiling (scaleU > 1) to work, TileMode must be `REPEAT`. TM-API-24
  (move shader creation to hook) **must land before** TM-API-2.

## Refinement of Slice TM-API-10 ("delete shader Shape()")

The slice said to delete `isometric-shader.Shape(IsometricMaterial)`. After codebase inspection
this is revised: **keep the shader `Shape(IsometricMaterial)` overload** because it sets up the
`UvCoordProvider` for Prism+IsometricMaterial combos — logic that `isometric-compose` cannot
replicate (it cannot reference `UvGenerator`, `IsometricMaterial`, or `UvCoordProvider` from its
dependency scope on `isometric-shader`).

The user-facing goal (no import alias, no name collision) is fully achieved without deletion:
- `isometric-compose.Shape(material: MaterialData)` is the general overload — covers `IsoColor`
- `isometric-shader.Shape(material: IsometricMaterial)` is the specific overload — Kotlin overload
  resolution picks it automatically for `IsometricMaterial` values (more specific type wins)
- `Shape(Prism(), IsoColor.BLUE)` → compose overload ✓ (IsoColor : MaterialData, not IsometricMaterial)
- `Shape(Prism(), textured(...))` → shader overload ✓ (IsometricMaterial is more specific)
- No `import ... Shape as MaterialShape` needed ✓

## Likely Files to Touch

| # | File | Changes |
|---|------|---------|
| 1 | `isometric-core/.../IsoColor.kt` | Add `: MaterialData` marker |
| 2 | `isometric-shader/.../UvCoord.kt` | Rename `UvTransform` → `TextureTransform`; add `init` validation; add factory companions; make `UvCoord` `internal` |
| 3 | `isometric-compose/.../runtime/IsometricComposables.kt` | `Shape()`: rename param `color` → `material: MaterialData`; add `set(material)` to update block |
| 4 | `isometric-compose/src/androidTest/.../TileGridTest.kt` | `color =` → `material =` (composable call) |
| 5 | `isometric-compose/src/androidTest/.../IsometricRendererPathCachingTest.kt` | `color =` → `material =` (composable call) |
| 6 | `isometric-compose/src/androidTest/.../IsometricRendererNativeCanvasTest.kt` | `color =` → `material =` (composable call) |
| 8 | `isometric-shader/.../IsometricMaterial.kt` | Remove `FlatColor`; fix `PerFace.default` + scope default; fix KDoc example; add `@DslMarker`; make `PerFace` constructor `internal`; add `PerFace.of()`; make `resolve()` `internal` |
| 9 | `isometric-shader/.../IsometricMaterialComposables.kt` | Extract `MaterialData.toBaseColor()`; remove duplicate `when` blocks; fix `FlatColor` branch; add `Path()` guard for TM-API-19; rename `textured()` → `texturedResource()`; add KDoc |
| 10 | `isometric-shader/.../TextureSource.kt` | Rename `BitmapSource` → `Bitmap`; add sealed-type evolution note |
| 11 | `isometric-shader/.../UvGenerator.kt` | Mark `internal`; add bounds check + `@throws` KDoc |
| 12 | `isometric-shader/render/TextureLoader.kt` | Add `Log.w` in each catch; promote to `fun interface` |
| 13 | `isometric-shader/render/TextureCache.kt` | `CachedTexture` stores only `Bitmap`; remove `BitmapShader` creation |
| 14 | `isometric-shader/render/TexturedCanvasDrawHook.kt` | Create shader with correct `TileMode` in `resolveTexture()`; apply `TextureTransform` in `computeAffineMatrix()`; fix `FlatColor` branch → `is IsoColor` |
| 15 | `isometric-shader/render/ProvideTextureRendering.kt` | Add `TextureCacheConfig`; add `onTextureLoadError`; add `loader: TextureLoader` param |
| 16 | `isometric-webgpu/.../texture/GpuTextureManager.kt` | `BitmapSource` → `Bitmap` rename |
| 17 | `isometric-webgpu/.../pipeline/SceneDataPacker.kt` | `BitmapSource` → `Bitmap` (if referenced); verify no `FlatColor` branches |
| 18 | `app/.../sample/TexturedDemoActivity.kt` | Remove `Shape as MaterialShape` alias; update `textured()` → `texturedResource()` |
| 19 | `isometric-shader/api/<module>.api` | Record public API removals (BitmapSource, FlatColor, UvTransform, flatColor()) |

## Step-by-Step Plan

Steps are ordered to minimise compilation errors: core types first, then compose, then shader, then webgpu.

---

### Step 1 — `IsoColor : MaterialData` (TM-API-10 foundation)

**File:** `isometric-core/.../IsoColor.kt`

Change the class declaration:
```kotlin
// Before
data class IsoColor @JvmOverloads constructor(...)

// After
data class IsoColor @JvmOverloads constructor(...) : MaterialData
```

One-line change. No other edits. The import of `MaterialData` is in the same package
(`io.github.jayteealao.isometric`) — no import statement needed.

**Also update `MaterialData.kt` KDoc:** The existing KDoc reads *"Implemented by `IsometricMaterial` in the `isometric-shader` module."* After this step `IsoColor` is also an implementor — update to: *"Implemented by `IsoColor` (this module) and `IsometricMaterial` (`isometric-shader`)."*

**Verify:** `val m: MaterialData = IsoColor.BLUE` compiles from `isometric-core` tests alone.

---

### Step 2 — `TextureTransform` rename + validation + factory companions + `UvCoord` internal
(TM-API-5, TM-API-9, TM-API-14)

**File:** `isometric-shader/.../UvCoord.kt`

```kotlin
// Make internal — disconnected from all data paths
internal data class UvCoord(val u: Float, val v: Float) { ... }

// Rename UvTransform → TextureTransform
data class TextureTransform(
    val scaleU: Float = 1f,
    val scaleV: Float = 1f,
    val offsetU: Float = 0f,
    val offsetV: Float = 0f,
    val rotationDegrees: Float = 0f,
) {
    init {
        require(scaleU.isFinite() && scaleU != 0f) { "scaleU must be finite and non-zero, got $scaleU" }
        require(scaleV.isFinite() && scaleV != 0f) { "scaleV must be finite and non-zero, got $scaleV" }
        require(offsetU.isFinite()) { "offsetU must be finite, got $offsetU" }
        require(offsetV.isFinite()) { "offsetV must be finite, got $offsetV" }
        require(rotationDegrees.isFinite()) { "rotationDegrees must be finite, got $rotationDegrees" }
    }

    companion object {
        val IDENTITY = TextureTransform()

        /** Tile the texture [horizontal] × [vertical] times across the face. */
        fun tiling(horizontal: Float, vertical: Float) =
            TextureTransform(scaleU = horizontal, scaleV = vertical)

        /** Rotate the texture by [degrees] around its center. */
        fun rotated(degrees: Float) = TextureTransform(rotationDegrees = degrees)

        /** Shift the texture origin by ([u], [v]) in normalized UV space. */
        fun offset(u: Float, v: Float) = TextureTransform(offsetU = u, offsetV = v)
    }
}
```

Update all `IsometricMaterial.kt` usages: `uvTransform: UvTransform` → `transform: TextureTransform`.

---

### Step 3 — `isometric-compose.Shape()` parameter rename (TM-API-10)

**File:** `isometric-compose/.../runtime/IsometricComposables.kt`

Change signature and factory:
```kotlin
fun IsometricScope.Shape(
    geometry: Shape,
    material: MaterialData = LocalDefaultColor.current,   // IsoColor : MaterialData ✓
    alpha: Float = 1f,
    // ... rest unchanged
) {
    val color = (material as? IsoColor) ?: IsoColor.WHITE
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(geometry, color).also { it.material = material } },
        update = {
            set(geometry) { this.shape = it; markDirty() }
            set(color)    { this.color = it; markDirty() }
            set(material) { this.material = it; markDirty() }
            // ... rest unchanged
        }
    )
}
```

**Note:** `Path()` and `Batch()` composables in this file keep `color: IsoColor` — they are
not part of TM-API-10's scope. Add alpha/tint.alpha KDoc note to `Shape()` KDoc (TM-API-18).

The shader `Shape(IsometricMaterial)` overload in `IsometricMaterialComposables.kt` is **kept**
(see Refinement section above). It remains the preferred path for `IsometricMaterial` because it
sets up `uvProvider` for Prism faces.

---

### Step 4 — Named-arg call site migration (TM-API-10 call sites)

**Important distinction:** Unit tests in `src/test` construct `ShapeNode(shape, color: IsoColor)` directly —
they call the **node constructor**, not the `Shape()` composable. `ShapeNode` constructor signature is
unchanged (`color: IsoColor` stays). Only instrumented tests in `src/androidTest` call the `Shape()`
composable and need renaming.

Rename `color =` → `material =` in these **3 androidTest files only**:

| File | Lines |
|------|-------|
| `isometric-compose/src/androidTest/.../TileGridTest.kt` | L70: `color = IsoColor(...)` → `material = IsoColor(...)` |
| `isometric-compose/src/androidTest/.../IsometricRendererPathCachingTest.kt` | L30 |
| `isometric-compose/src/androidTest/.../IsometricRendererNativeCanvasTest.kt` | L43, L94 |

**Do NOT modify:** `IsometricNodeRenderTest.kt`, `IsometricRendererTest.kt`,
`WS6EscapeHatchesTest.kt` — all their `color = IsoColor.BLUE` references are `ShapeNode()`
constructor calls and must remain unchanged.

Run `./gradlew :isometric-compose:testDebugUnitTest` after this step to confirm no regressions.

---

### Step 5 — `IsometricMaterial.kt` cleanup (TM-API-1, 3, 10, 15, 16, 25)

**File:** `isometric-shader/.../IsometricMaterial.kt`

Changes in order:

**a) Fix KDoc example (TM-API-1):**
Remove the `{ uvScale(...) }` fake lambda from the `IsometricMaterial` KDoc example (line ~17).
Replace with a valid `texturedResource()` example.

**b) Remove `FlatColor` subtype (TM-API-10):**
Delete the `data class FlatColor(val color: IsoColor) : IsometricMaterial` class entirely.
Delete the `fun flatColor(color: IsoColor)` builder function.

**c) Fix `PerFace.default` (TM-API-3):**
```kotlin
// Add at file top (internal, shared constant)
internal val UNASSIGNED_FACE_DEFAULT: IsoColor = IsoColor(128, 128, 128, 255)

// In PerFace:
data class PerFace(
    val faceMap: Map<PrismFace, IsometricMaterial>,
    val default: IsometricMaterial = UNASSIGNED_FACE_DEFAULT,
) : IsometricMaterial {
    ...
    // Make resolve() internal (TM-API-15):
    internal fun resolve(face: PrismFace): IsometricMaterial = faceMap[face] ?: default
}
```

Wait — `PerFace.default` was `IsometricMaterial`. After removing `FlatColor`, `IsoColor` is no
longer an `IsometricMaterial`. So `PerFace.default` type must widen from `IsometricMaterial` to
`MaterialData`. Similarly, `faceMap: Map<PrismFace, IsometricMaterial>` may need widening to
`Map<PrismFace, MaterialData>` for face assignments like `bottom = IsoColor.GRAY`.

**Revised PerFace:**
```kotlin
internal val UNASSIGNED_FACE_DEFAULT: IsoColor = IsoColor(128, 128, 128, 255)

data class PerFace private constructor(     // TM-API-25: primary ctor internal
    val faceMap: Map<PrismFace, MaterialData>,
    val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
) : IsometricMaterial {
    init {
        require(faceMap.values.none { it is PerFace }) {
            "PerFace materials cannot be nested — each face must be IsoColor or Textured"
        }
        require(default !is PerFace) {
            "PerFace default cannot itself be PerFace"
        }
    }

    internal fun resolve(face: PrismFace): MaterialData = faceMap[face] ?: default

    companion object {
        /** Factory for callers who need direct map construction. */
        fun of(faceMap: Map<PrismFace, MaterialData>, default: MaterialData = UNASSIGNED_FACE_DEFAULT) =
            PerFace(faceMap, default)
    }
}
```

**d) `PerFaceMaterialScope.default` fix:**
```kotlin
var default: MaterialData = UNASSIGNED_FACE_DEFAULT
```
Individual face vars (`top`, `sides` etc.) change type from `IsometricMaterial?` to
`MaterialData?` so callers can assign `IsoColor` directly:
```kotlin
var top: MaterialData? = null
// ... etc
```
The `sides` write-only setter changes to `MaterialData?` accordingly.

**e) `@DslMarker` (TM-API-16):**
```kotlin
@DslMarker
annotation class IsometricMaterialDsl

@IsometricMaterialDsl
class PerFaceMaterialScope internal constructor() { ... }
```

**Verify:** `apiDump` for `isometric-shader` must not list `FlatColor`, `flatColor()`, or `resolve()`.

---

### Step 6 — `IsometricMaterialComposables.kt` cleanup (TM-API-7, 11, 12, 18, 19)

**File:** `isometric-shader/.../IsometricMaterialComposables.kt`

**a) Extract `MaterialData.toBaseColor()` (TM-API-7):**
```kotlin
internal fun MaterialData.toBaseColor(): IsoColor = when (this) {
    is IsoColor -> this
    is IsometricMaterial.Textured -> tint
    is IsometricMaterial.PerFace -> IsoColor.WHITE
    else -> IsoColor.WHITE
}
```
Replace the two duplicated `when(material)` blocks in `Shape()` and `Path()` with a single call:
```kotlin
val color = material.toBaseColor()
```

**b) Fix `Path()` guard (TM-API-19):**
Add a `require` at the top of the shader `Path()` composable:
```kotlin
require(material !is IsometricMaterial.Textured && material !is IsometricMaterial.PerFace) {
    "Path() does not support textured materials — use Shape() with a Prism for texture rendering"
}
```

**c) Rename `textured()` → `texturedResource()` (TM-API-12):**
In `IsometricMaterial.kt` (builder section), rename the `textured(@DrawableRes resId)` function
to `texturedResource`. Update all call sites (only in `app/`; the asset and bitmap variants keep
their names).

**d) KDoc additions (TM-API-11):**
Add "Requires `ProvideTextureRendering` in the composition or textures will not be rendered" to
KDoc for `texturedResource()`, `texturedAsset()`, `texturedBitmap()`.

**e) Rename `uvTransform` parameter → `transform` (TM-API-14 call sites):**
In the builder functions: `uvTransform: TextureTransform` → `transform: TextureTransform`.

---

### Step 7 — `TextureSource.Bitmap` rename (TM-API-13, 20)

**File:** `isometric-shader/.../TextureSource.kt`

Rename `BitmapSource` → `Bitmap`:
```kotlin
sealed interface TextureSource {
    data class Resource(...)  : TextureSource  // unchanged
    data class Asset(...)     : TextureSource  // unchanged
    data class Bitmap(...)    : TextureSource  // was BitmapSource
}
```

Update `texturedBitmap()` builder: `TextureSource.BitmapSource(bitmap)` → `TextureSource.Bitmap(bitmap)`.

**Note on ordering:** `TextureLoader.kt` also references `TextureSource.BitmapSource`. That reference
is fixed in **Step 9** when `TextureLoader` is rewritten. Do Steps 7 and 9 as an atomic commit to
avoid a broken-build window between them.

Add sealed-type evolution note to the KDoc (TM-API-20):
```
 * **Evolution note:** Adding a new subtype in a future version is a breaking change
 * for consumers using exhaustive `when` expressions. Use an `else` branch if forward
 * compatibility is needed.
```

---

### Step 8 — `UvGenerator` internal + bounds check (TM-API-4, 8)

**File:** `isometric-shader/.../UvGenerator.kt`

```kotlin
/**
 * ...
 * @throws IllegalArgumentException if [faceIndex] is outside `0 until prism.paths.size`
 */
internal object UvGenerator {
    fun forPrismFace(prism: Prism, faceIndex: Int): FloatArray {
        require(faceIndex in prism.paths.indices) {
            "faceIndex $faceIndex out of bounds for Prism with ${prism.paths.size} faces"
        }
        return try {
            val face = PrismFace.fromPathIndex(faceIndex)
            val path = prism.paths[faceIndex]
            computeUvs(prism, face, path)
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "UV generation failed for Prism at ${prism.position}, faceIndex=$faceIndex", e
            )
        }
    }
    // ... rest unchanged
}
```

---

### Step 9 — `TextureLoader` error logging + `fun interface` (TM-API-6, 23)

**File:** `isometric-shader/render/TextureLoader.kt`

**a) Promote to `fun interface` (TM-API-23):**
```kotlin
fun interface TextureLoader {
    fun load(source: TextureSource): Bitmap?
}
```

**Deliberate deviation from slice spec:** The slice spec wrote `suspend fun load(...)`. The interface
is **not** suspend because `TexturedCanvasDrawHook.draw()` is called synchronously from the Canvas
draw loop — it cannot be in a coroutine scope. Making `load` suspend would require threading the draw
call through a coroutine, which is a scope-creep for this slice. Async loading is an explicitly
deferred future enhancement (documented in the existing KDoc).

**b) Default implementation becomes a factory function:**
```kotlin
internal fun defaultTextureLoader(context: Context): TextureLoader = DefaultTextureLoader(context)

private class DefaultTextureLoader(private val context: Context) : TextureLoader {
    override fun load(source: TextureSource): Bitmap? = when (source) {
        is TextureSource.Resource -> loadResource(source.resId)
        is TextureSource.Asset    -> loadAsset(source.path)
        is TextureSource.Bitmap   -> { source.ensureNotRecycled(); source.bitmap }
    }

    private fun loadResource(resId: Int): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, resId)
    } catch (e: Exception) {
        Log.w("IsometricShader", "Failed to load texture resource: $resId", e)
        null
    }

    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Log.w("IsometricShader", "Failed to load texture asset: $path", e)
        null
    }
}
```

---

### Step 10 — `TextureCache` decouple from shader (TM-API-24)

**File:** `isometric-shader/render/TextureCache.kt`

`CachedTexture` stores only `Bitmap` — shader creation moves to the hook:
```kotlin
internal data class CachedTexture(val bitmap: Bitmap)

internal class TextureCache(val maxSize: Int = 20) {
    // ...
    fun put(source: TextureSource, bitmap: Bitmap): CachedTexture {
        val entry = CachedTexture(bitmap)   // no shader here
        cache[source] = entry
        return entry
    }
}
```

---

### Step 11 — `TexturedCanvasDrawHook`: apply `TextureTransform` + fix `FlatColor` branch
(TM-API-2, 24)

**File:** `isometric-shader/render/TexturedCanvasDrawHook.kt`

**a) Shader creation in `resolveTexture()` with per-material caching (TM-API-24 completion):**

A `BitmapShader` must NOT be created on every `draw()` call — that would allocate a new GPU object
per face per frame. The hook maintains its own shader cache keyed on `(TextureSource, TileMode)`:

```kotlin
private val shaderCache = LinkedHashMap<Pair<TextureSource, Shader.TileMode>, BitmapShader>()

private fun resolveTexture(source: TextureSource, transform: TextureTransform): BitmapShader {
    val cached = cache.get(source) ?: cache.put(source, loader.load(source) ?: checkerboard)
    val tileU = if (transform.scaleU != 1f) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
    val tileV = if (transform.scaleV != 1f) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
    // Use tileU as the cache key — tileU==tileV in practice for uniform tiling
    val key = source to tileU
    return shaderCache.getOrPut(key) {
        BitmapShader(cached.bitmap, tileU, tileV)
    }
}
```

`shaderCache` uses `tileU` as the representative TileMode in the key. If a material uses REPEAT
horizontally but CLAMP vertically (unusual), a combined key `tileU to tileV` would be needed —
document as a future improvement. For the typical `tiling(2f, 2f)` case, `tileU == tileV == REPEAT`.
```

**b) Fix `FlatColor` branch in `draw()` (TM-API-10 follow-on):**
```kotlin
// Before:
is IsometricMaterial.FlatColor -> false

// After: IsoColor is now the flat-color material, not an IsometricMaterial
```
The `when(material)` now covers only `Textured` and `PerFace`. The outer cast is already
`val material = command.material as? IsometricMaterial ?: return false` — if the material is
`IsoColor` (not an `IsometricMaterial`), it returns `false` automatically. No explicit branch
needed.

**c) `computeAffineMatrix()` — apply `TextureTransform` (TM-API-2):**

Add `transform: TextureTransform` parameter. After `setPolyToPoly`:

```kotlin
internal fun computeAffineMatrix(
    uvCoords: FloatArray,
    screenPoints: DoubleArray,
    texWidth: Int,
    texHeight: Int,
    transform: TextureTransform,
    outMatrix: Matrix,
) {
    // ... existing src/dst setup and setPolyToPoly ...
    outMatrix.setPolyToPoly(src, 0, dst, 0, 3)

    if (transform != TextureTransform.IDENTITY) {
        // Build T in texture-pixel space: scale (around center) → rotate (around center) → translate
        val cx = texWidth / 2f
        val cy = texHeight / 2f
        val t = Matrix()
        t.setScale(transform.scaleU, transform.scaleV, cx, cy)
        if (transform.rotationDegrees != 0f) {
            t.postRotate(transform.rotationDegrees, cx, cy)
        }
        if (transform.offsetU != 0f || transform.offsetV != 0f) {
            t.postTranslate(transform.offsetU * texWidth, transform.offsetV * texHeight)
        }
        // Pre-concat T^-1: M_final = M_poly * T^-1
        // BitmapShader samples at T^-1 * M_poly^-1 * screen = T(UV) → correct tiling/offset
        val tInv = Matrix()
        if (t.invert(tInv)) {
            outMatrix.preConcat(tInv)
        }
    }
}
```

Update `drawTextured()` to pass `material.transform` to `computeAffineMatrix()`.
Update `drawTextured()` to call `resolveTexture(material.source, material.transform)`.

**Matrix math note:** `BitmapShader.setLocalMatrix(M)` causes the shader to sample
`bitmap[M^{-1} * screenPos]`. After `setPolyToPoly(src, dst)`, M maps UV-pixel-space to
screen-space. Pre-concatenating `T^-1` gives `M * T^-1`, whose inverse is `T * M^-1`.
The shader then samples `T(M^-1 * screenPos) = T(UV)` — the UV transform is applied
in UV space as intended.

---

### Step 12 — `ProvideTextureRendering` config + error callback + loader (TM-API-17, 21, 22, 23)

**File:** `isometric-shader/render/ProvideTextureRendering.kt`

```kotlin
/**
 * Configuration for the texture LRU cache.
 *
 * @param maxSize Maximum number of distinct [TextureSource]s to keep decoded in memory.
 *   When the cache is full, the least-recently-used entry is evicted. On a cache miss
 *   the texture is decoded synchronously on the first draw frame that needs it.
 *   Sizing guidance: count distinct TextureSource keys your scene uses. 20 covers most
 *   isometric tile sets. Increase for large tile sets with many unique textures.
 */
data class TextureCacheConfig(val maxSize: Int = 20) {
    init { require(maxSize > 0) { "maxSize must be positive, got $maxSize" } }
}

/**
 * Enables textured Canvas rendering for any `IsometricScene` in [content].
 *
 * **Scoping rules:** Install one `ProvideTextureRendering` per composable subtree. A provider
 * does not share its cache with sibling or parent providers — nest providers only if subtrees
 * intentionally need independent caches. A single top-level provider covering all scenes is
 * the typical usage.
 *
 * @param cacheConfig LRU cache configuration.
 * @param loader Custom texture loader. Override to intercept or transform bitmaps at load time.
 * @param onTextureLoadError Called when a texture fails to load. Receives the [TextureSource]
 *   that failed. Use for analytics or user-visible error feedback.
 * @param content The composable tree containing `IsometricScene`(s).
 */
@Composable
fun ProvideTextureRendering(
    cacheConfig: TextureCacheConfig = TextureCacheConfig(),
    loader: TextureLoader? = null,
    onTextureLoadError: ((TextureSource) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val hook = remember(context, cacheConfig, loader) {
        val cache = TextureCache(cacheConfig.maxSize)
        val effectiveLoader = loader ?: defaultTextureLoader(context.applicationContext)
        TexturedCanvasDrawHook(cache, effectiveLoader, onTextureLoadError)
    }
    CompositionLocalProvider(LocalMaterialDrawHook provides hook) { content() }
}
```

Update `TexturedCanvasDrawHook` constructor to accept `onTextureLoadError`. When `loader.load()`
returns null, invoke `onTextureLoadError?.invoke(source)` before substituting checkerboard.

---

### Step 13 — KDoc sweep + `texturedResource()` rename (TM-API-1, 11, 12, 17, 18, 20, 22)

KDoc updates bundled here (mostly additive — no logic changes):

| TM-API | Location | Change |
|--------|----------|--------|
| TM-API-11 | `texturedResource()`, `texturedAsset()`, `texturedBitmap()` | Add "Requires `ProvideTextureRendering`" note |
| TM-API-17 | `ProvideTextureRendering` / `TextureCacheConfig.maxSize` | Expand: unit (distinct TextureSources), LRU eviction, sizing guidance (already done in Step 12) |
| TM-API-18 | `IsometricComposables.kt Shape()`, `IsometricMaterialComposables.kt Shape()/Path()` | Add "@param alpha — applied to the shape's color AFTER tint. For textured materials, `alpha` scales the overall opacity; `tint.alpha` is not separately exposed." |
| TM-API-22 | `ProvideTextureRendering` | Scoping rules note (already done in Step 12) |

---

### Step 14 — WebGPU propagation (TM-API-13)

**File:** `isometric-webgpu/.../texture/GpuTextureManager.kt`

Rename `TextureSource.BitmapSource` → `TextureSource.Bitmap` at line 164.
Verify no `FlatColor` branches exist (confirmed: none in scope).

**File:** `isometric-webgpu/.../pipeline/SceneDataPacker.kt`

Verify: `resolveTextureIndex()` uses `else ->` fallback — no change needed for FlatColor removal.
If any `BitmapSource` reference exists, rename it.

---

### Step 15 — Sample app cleanup

**File:** `app/.../sample/TexturedDemoActivity.kt`

- Remove `import io.github.jayteealao.isometric.shader.Shape as MaterialShape`
- Replace `MaterialShape(geometry, material)` calls with plain `Shape(geometry, material)`
- Update `textured(...)` → `texturedResource(...)` call sites

---

### Step 16 — `apiDump` + `apiCheck`

Run `./gradlew :isometric-core:apiDump :isometric-compose:apiDump :isometric-shader:apiDump :isometric-webgpu:apiDump`.

Verify intentional removals are recorded in `.api` files:
- `isometric-shader`: `FlatColor`, `flatColor()`, `UvTransform`, `UvCoord` (public→internal), `UvGenerator` (public→internal), `BitmapSource`, `textured()`, `PerFace.resolve()`, `PerFace.<init>` (public constructor removed)
- `isometric-core`: `IsoColor` gains `: MaterialData` (additive, no removal)
- `isometric-compose`: `Shape.color` param renamed to `material` (breaking — accept for minor bump)

Run `./gradlew :isometric-shader:apiCheck` to confirm no unintended API drift.

## Test / Verification Plan

### Automated checks

- `./gradlew :isometric-core:test` — `IsoColorTest` should pass without changes (new `: MaterialData`
  is additive); add one new test: `val m: MaterialData = IsoColor.BLUE` compiles → pass
- `./gradlew :isometric-compose:testDebugUnitTest :isometric-compose:connectedDebugAndroidTest` —
  named-arg renames (Step 4) must pass; no regressions
- `./gradlew :isometric-shader:testDebugUnitTest` — post-FlatColor-removal, post-rename
- Paparazzi snapshot tests (if present in shader module): run to confirm visual regression baseline

### Interactive verification (human-in-the-loop)

**AC1 (TM-API-2 applied — TextureTransform tiling):**
- What to verify: `texturedResource(R.drawable.brick, transform = TextureTransform.tiling(2f, 2f))`
  renders the brick texture tiled 2× horizontally across each Prism face
- Platform & tool: Android device (SM-F956B) + `adb shell screencap /sdcard/texture_tiling.png`
- Steps: Launch TexturedDemoActivity → Canvas mode → observe 4×4 prism grid
- Evidence: `adb pull /sdcard/texture_tiling.png` — texture must repeat, not stretch to edge
- Pass criteria: Clearly 2 repetitions of the brick pattern visible on each face

**AC2 (TM-API-3 — PerFace.default visible gray):**
- What to verify: `perFace { top = texturedBitmap(grass) }` renders side faces in gray,
  not transparent/invisible
- Steps: Remove `sides = ...` assignment from TexturedDemoActivity → observe
- Pass criteria: Side faces are mid-gray (RGB ~128), not black and not transparent

## Risks / Watchouts

1. **`PerFace.faceMap` type widening (`IsometricMaterial` → `MaterialData`)**: The `when` in
   `TexturedCanvasDrawHook.draw()` that resolves per-face sub-materials currently returns
   `IsometricMaterial`. After widening to `MaterialData`, the when-switch must handle `is IsoColor`
   as a valid sub-material (returns false, delegates to flat-color path).

2. **`computeAffineMatrix` matrix inversion failure**: `Matrix.invert(tInv)` returns false if the
   matrix is singular (e.g., scaleU = 0). The `TextureTransform.init` validates non-zero scale,
   so this should not occur. Add an assert or log if `invert()` returns false as a safety net.

3. **`resolveTexture()` shader caching**: Addressed in Step 11a — the hook maintains a
   `LinkedHashMap<Pair<TextureSource, TileMode>, BitmapShader>` so shaders are created once and
   reused. If REPEAT/CLAMP tile modes differ per-axis (unusual), the cache key uses only `tileU` as
   representative — note as a known simplification for symmetric tiling cases.

4. **`PerFaceMaterialScope` face types widened to `MaterialData?`**: Existing code that assigns
   `IsometricMaterial.Textured` to face vars will still compile (it is a `MaterialData`). Code that
   relies on the type being `IsometricMaterial?` (e.g., exhaustive when) will see a compile error —
   this is correct and must be fixed at each site.

5. **`apiCheck` false negatives**: Running `apiDump` after each rename group (not just at the end)
   prevents large API diffs that are hard to review. Recommended: `apiDump` after Step 7 (renames
   complete), then again after Step 16 (final).

6. **`textured()` → `texturedResource()` rename in tests and sample app**: The sample app uses
   `textured(R.drawable.brick)` at several places. Grep across `app/` and all test files before
   submitting to catch any missed call sites.

## Dependencies on Other Slices

All six prior slices are complete. This slice modifies their output in-place:
- Changes to `IsometricMaterial.kt` affect the `material-types` slice output
- Changes to `TexturedCanvasDrawHook.kt` affect the `canvas-textures` slice output
- Changes to `UvCoord.kt` and `UvGenerator.kt` affect the `uv-generation` slice output
- No new dependencies created

## Assumptions

- `apiDump` and `apiCheck` Gradle tasks are configured for all four modules (confirmed from
  existing slice review files that reference them)
- `PrismFace.fromPathIndex()` does not throw for valid indices (confirmed by reading UvGenerator
  implementation — it only calls this for indices in `PrismFace.entries.indices`)
- `LocalDefaultColor.current` returns `IsoColor` at runtime (confirmed: `CompositionLocals.kt`
  in compose module defines it as `IsoColor`)
- The `annotation class IsometricMaterialDsl` can live in the same file as `PerFaceMaterialScope`
  (`IsometricMaterial.kt`) — no package restriction applies

## Blockers

None. All 25 findings have clear fix paths. The only sequencing constraint is:
Step 10 (decouple CachedTexture) must land before Step 11 (apply TextureTransform with REPEAT TileMode).

## Freshness Research

No external dependency upgrades are required for this slice. All changes are to first-party code.
Android `Matrix.preConcat()`, `Matrix.invert()`, `BitmapShader`, and `Shader.TileMode.REPEAT`
are stable Android API that have been available since API 1 / API 1 / API 1 / API 1 respectively —
no version concerns.

## Revision History

### 2026-04-13 — Auto-Review (rev 1)
- Mode: Auto-Review (re-invoked immediately after initial plan creation)
- Issues found: 5
  1. **HIGH: Step 4 wrong targets** — `IsometricNodeRenderTest.kt`, `IsometricRendererTest.kt`, and
     `WS6EscapeHatchesTest.kt` listed as named-arg rename targets but all their `color = IsoColor`
     usages are `ShapeNode` constructor calls, not `Shape()` composable calls. `ShapeNode` constructor
     signature stays `color: IsoColor` and must not be renamed. Removed 3 files from list. Fixed
     metric-files-to-touch from 19 → 16. Added clarification note to Step 4.
  2. **MEDIUM: Step 5 stale error message** — `PerFace.init` error string said "FlatColor or Textured"
     — updated to "IsoColor or Textured" post-FlatColor removal.
  3. **LOW: Step 7/9 ordering gap** — Rename in Step 7 would break `TextureLoader.kt` until Step 9.
     Added atomic-commit note to Step 7.
  4. **LOW: Step 9 `suspend` discrepancy** — Slice spec said `suspend fun load` but draw loop is
     synchronous. Added explicit deviation note documenting why non-suspend is correct.
  5. **MEDIUM: Step 11 shader caching was only a risk note** — Per-frame `BitmapShader` allocation
     would be a runtime performance regression. Promoted to an explicit implementation sub-step (11a)
     with `LinkedHashMap<Pair<TextureSource, TileMode>, BitmapShader>` in the hook. Updated Risk #3.

### 2026-04-14 — Auto-Review (rev 2)
- Mode: Auto-Review (triggered by `/wf-plan texture-material-shaders api-design-fixes`)
- Issues found: 1
  1. **LOW: Step 1 missing `MaterialData` KDoc update** — `MaterialData.kt` in `isometric-core`
     has KDoc saying *"Implemented by `IsometricMaterial` in the `isometric-shader` module."*
     Once Step 1 adds `IsoColor : MaterialData`, this KDoc becomes incorrect. Added an explicit
     KDoc update note to Step 1.
- Codebase inspection confirmed: No api-design-fixes implementation has started. All 25 findings
  remain in pre-fix state (FlatColor exists, UvTransform not renamed, Shape() still `color: IsoColor`,
  UvTransform not applied in render path, TextureCacheConfig absent). Plan steps correctly reflect
  pending work. Recent commits (3550c1e, 941191d) are unrelated fixes for prior slices.
- No sibling plan conflicts. No ordering changes required.

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders api-design-fixes` — plan
  is complete and execution-ready. **Run `/compact` first** to preserve workflow state before
  the implementation session.

## Implementation Cross-link
- Implemented in: [05-implement-api-design-fixes.md](05-implement-api-design-fixes.md)
