---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: correctness
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 6
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 2
metric-findings-low: 1
metric-findings-nit: 1
result: issues-found
tags: [correctness, api-design, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

# Review: correctness — api-design-fixes

> Reviewed against the **current** source files (post-implementation).
> Prior review at this path (`updated-at: 2026-04-14T17:42:09Z`) referenced an
> earlier code snapshot; several findings in that version (C-01, C-02, C-03) are
> now stale because the implementation was subsequently updated.  This review
> supersedes it.

## Findings

| ID | Sev | Conf | File:Line | Issue |
|----|-----|------|-----------|-------|
| CR-1 | HIGH | High | `IsometricMaterialComposables.kt:146` | `Path()` takes `material: IsometricMaterial` but the `require` always throws — every concrete `IsometricMaterial` is either `Textured` or `PerFace`, both of which are rejected |
| CR-2 | HIGH | High | `TexturedCanvasDrawHook.kt:120,124` | Shader cache does not include the bitmap instance in its key — a bitmap evicted from `TextureCache` and reloaded produces a new `Bitmap`, but the old `BitmapShader` (backed by the evicted bitmap) is returned from `shaderCache` |
| CR-3 | MED | High | `TexturedCanvasDrawHook.kt:120` | TileMode is REPEAT for **any** non-IDENTITY transform, including pure-offset and pure-rotation; a rotation-only transform (`rotationDegrees != 0`, `scaleU = scaleV = 1`) does not need REPEAT — CLAMP is correct and is the lower-risk mode |
| CR-4 | MED | Med | `TexturedCanvasDrawHook.kt:77-83` | "Last tint" color-filter cache is a single slot; it is correct for the single-material common case but silently alternates (cache miss every frame) when two different tinted materials appear in the same draw pass — e.g. a PerFace with two differently-tinted Textured sub-materials |
| CR-5 | LOW | Med | `IsometricMaterial.kt:65` | `PerFace.init` nesting check calls `faceMap.values.none { it is PerFace }` but the error message says "must be IsoColor or Textured", which over-constrains the allowed type set in the message relative to the actual runtime check; a future `MaterialData` implementor passes the guard while contradicting the message |
| CR-6 | NIT | High | `TexturedCanvasDrawHook.kt:54` | `shaderCache` eviction threshold `maxSize * 2` is never documented — it silently allows up to 2× the bitmap cache size in GPU-resident shaders while the bitmap cache has already evicted the source bitmaps |

---

## Detailed Findings

### CR-1: `Path(material: IsometricMaterial)` always throws [HIGH]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt:146`

**Evidence:**
```kotlin
fun IsometricScope.Path(
    path: Path,
    material: IsometricMaterial,   // sealed — only Textured and PerFace exist
    ...
) {
    require(material !is IsometricMaterial.Textured && material !is IsometricMaterial.PerFace) {
        "Path() does not support textured materials — use Shape() with a Prism for texture rendering"
    }
    ...
}
```

`IsometricMaterial` is a sealed interface with exactly two concrete subtypes: `Textured` and `PerFace`. The `require` condition is therefore always `false` — every possible `IsometricMaterial` value is either `Textured` or `PerFace`, so the require *always* throws `IllegalArgumentException`. The overload is completely unusable.

The intent was clearly to allow an `IsometricMaterial` that is an `IsoColor` (i.e. flat-color path via the material system), but since `IsoColor` does not implement `IsometricMaterial` — it implements `MaterialData` directly — there is no valid value that can be passed to this overload. The function signature should accept `MaterialData` (so callers can pass `IsoColor`) rather than `IsometricMaterial`.

**Fix:** Change the parameter type from `IsometricMaterial` to `MaterialData`, keep the guard, and document that only flat-color (`IsoColor`) materials are supported. Alternatively remove the overload entirely and rely on the `IsometricComposables.kt` `Path(path, color: IsoColor)` overload in `isometric-compose`.

**Severity:** HIGH | **Confidence:** High

---

### CR-2: Shader cache key does not encode the bitmap instance — stale shaders after LRU eviction [HIGH]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TexturedCanvasDrawHook.kt:120,124`

**Evidence:**
```kotlin
val shaderKey = Triple(material.source, tileU, tileV)
val shader = shaderCache.getOrPut(shaderKey) {
    BitmapShader(cached.bitmap, tileU, tileV)
}
```

The `shaderCache` is keyed by `(TextureSource, TileMode, TileMode)`. A `BitmapShader` is created once per key, backed by the `Bitmap` that was current at creation time.

Scenario that silently corrupts rendering:

1. Source S is loaded → `textureCache[S] = Bitmap_A`. `shaderCache[(S, REPEAT, REPEAT)] = BitmapShader(Bitmap_A)`.
2. `textureCache` evicts S (LRU full) and `Bitmap_A` is no longer referenced by the cache.
3. A new material with source S is drawn. `textureCache` misses, reloads → `Bitmap_A2` (new decode).
4. `shaderCache` hits: returns the old `BitmapShader(Bitmap_A)` — the shader now points to an evicted bitmap.
5. Android's `BitmapShader` holds a hard reference to its backing `Bitmap`, so `Bitmap_A` is not GC'd, but it is *stale*: if the resource was updated (e.g. via a test harness or a reloaded asset bundle) the shader renders the old pixels indefinitely.

For `TextureSource.Bitmap` the scenario is more severe: if the caller recycles `Bitmap_A` after the `textureCache` evicts it, the `BitmapShader` in `shaderCache` holds a reference to a recycled bitmap, and drawing with it produces undefined behavior (typically a crash or black rect on some API levels).

**Fix:** Include the bitmap identity (e.g. `System.identityHashCode(cached.bitmap)` or a wrapper with identity equality) in the shader cache key, or invalidate `shaderCache` entries whenever `textureCache` evicts an entry.

**Severity:** HIGH | **Confidence:** High

---

### CR-3: TileMode REPEAT chosen for rotation-only and offset-only transforms where CLAMP is correct [MED]

**Location:** `TexturedCanvasDrawHook.kt:120`

**Evidence:**
```kotlin
val tileMode = if (material.transform != TextureTransform.IDENTITY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
```

The condition `transform != IDENTITY` is true for **any** non-default field, including:

- `TextureTransform.rotated(45f)` — `scaleU = scaleV = 1`, `offsetU = offsetV = 0`, `rotationDegrees = 45`.
- `TextureTransform.offset(0.1f, 0f)` — scale is unchanged.

For a rotation-only transform with `scaleU = scaleV = 1`, the T^-1 pre-concat rotates UV space by 45°. The rotated parallelogram face occupies a region in UV space that is strictly within `[0, texW] × [0, texH]` — no UV coordinate escapes the texture boundary for faces that fit within one texture tile. REPEAT is unnecessary and may cause unexpected tiling at face edges when the UV diamond from `setPolyToPoly` touches the texture boundary at a diagonal.

For an offset-only transform, whether REPEAT is needed depends on whether `offsetU`/`offsetV` push any mapped UV outside `[0,1]`. For `offsetU = 0.1f`, some pixels near the right edge will map to UV > 1; REPEAT is correct there. For `offsetU = 0f, offsetV = 0f` and only `rotationDegrees != 0`, REPEAT is unnecessary.

The over-use of REPEAT is a low-visual-impact but technically incorrect tile mode selection: it creates seams at texture boundaries that would not appear with CLAMP, and it forces an unnecessary BitmapShader allocation (distinct from the CLAMP shader for the same source under the `(source, tileU, tileV)` key).

**Fix:** Apply REPEAT only when scale or offset components differ from identity values (`abs(scaleU) != 1f || abs(scaleV) != 1f || offsetU != 0f || offsetV != 0f`). Leave rotation-only transforms using CLAMP, since a rotation never extends UV coordinates beyond the normalized texture footprint for a well-formed face.

**Severity:** MED | **Confidence:** High

---

### CR-4: Single-slot tint color-filter cache thrashes when two differently-tinted materials are drawn in the same pass [MED]

**Location:** `TexturedCanvasDrawHook.kt:77-83`

**Evidence:**
```kotlin
private var cachedTintColor: IsoColor = IsoColor.WHITE
private var cachedColorFilter: PorterDuffColorFilter? = null

private fun colorFilterFor(tint: IsoColor): PorterDuffColorFilter? {
    if (tint.r >= 255.0 && tint.g >= 255.0 && tint.b >= 255.0) return null
    if (tint == cachedTintColor) return cachedColorFilter
    val filter = tint.toColorFilterOrNull()
    cachedTintColor = tint
    cachedColorFilter = filter
    return filter
}
```

The cache holds exactly one slot. Per-draw-frame, commands are sorted by depth and drawn in order. A `PerFace` material where `front` has `tint = IsoColor.RED` and `left` has `tint = IsoColor.BLUE` will cause `colorFilterFor` to allocate a new `PorterDuffColorFilter` on every face draw, alternating RED → BLUE → RED → BLUE. This negates the purpose of the cache entirely and creates GC pressure proportional to the number of alternating-tint faces per frame.

This is not a correctness *bug* (the correct filter is always returned), but it is an incorrect assumption baked into the caching strategy: that tint changes are rare across an entire draw pass.

**Fix:** Use a small fixed-size map (2–4 entries) or accept that the optimization is limited to scenes with a single tint and document the limitation. Alternatively, hoist the cache invalidation to the beginning of each `draw()` call and keep per-frame state.

**Severity:** MED | **Confidence:** Med

---

### CR-5: `PerFace.init` error message over-constrains allowed types relative to the actual check [LOW]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterial.kt:65`

**Evidence:**
```kotlin
require(faceMap.values.none { it is PerFace }) {
    "PerFace materials cannot be nested — each face must be IsoColor or Textured"
}
```

The runtime check only rejects `PerFace` values. The error message claims "each face must be IsoColor or Textured". If a future `MaterialData` implementor (e.g. a `ProceduralMaterial` added in a later slice) is placed in `faceMap`, the check passes at runtime while the error message incorrectly states it is forbidden.

**Fix:** Change the message to: `"PerFace materials cannot be nested — a PerFace face value must not itself be PerFace"`.

**Severity:** LOW | **Confidence:** High

---

### CR-6: `shaderCache` eviction bound `maxSize * 2` is undocumented and inconsistent with `TextureCache` [NIT]

**Location:** `TexturedCanvasDrawHook.kt:54`

**Evidence:**
```kotlin
private val shaderCache = object : LinkedHashMap<...>(16, 0.75f, true) {
    override fun removeEldestEntry(...): Boolean {
        return size > cache.maxSize * 2
    }
}
```

The shader cache can hold up to `2 * textureCache.maxSize` entries (default: 40 shaders for 20 bitmaps). The factor of 2 is chosen because each source can produce at most two shaders (CLAMP and REPEAT). This is sound reasoning but is not documented. The comment says only "Keyed by `(TextureSource, tileU, tileV)`". Given that `tileU` and `tileV` are now both encoded in the key (they could differ), a single source could theoretically produce up to 9 shader entries (3 TileModes × 3 TileModes). However, in practice the code only ever uses CLAMP or REPEAT, bounding it to 4 per source (CLAMP/CLAMP, CLAMP/REPEAT, REPEAT/CLAMP, REPEAT/REPEAT). For asymmetric tiling the bound of `maxSize * 2` may be too small (4× would be the correct bound), though the 2× bound is fine for the current usage where tileU always equals tileV.

**Fix:** Add an inline comment explaining the 2× factor and note the asymmetric-tiling edge case.

**Severity:** NIT | **Confidence:** High

---

## Summary

- Total findings: 6
- Blockers: 0
- High: 2 (CR-1, CR-2)
- Med: 2 (CR-3, CR-4)
- Low: 1 (CR-5)
- Nit: 1 (CR-6)
- Status: Issues Found

**CR-1** is a complete dead-end API: every call to `Path(path, material: IsometricMaterial)` throws unconditionally because the sealed interface has no flat-color subtype. This overload must either be removed or its parameter type changed to `MaterialData`.

**CR-2** is a stale-shader hazard: when the `TextureCache` evicts a source and reloads it, the `shaderCache` returns a `BitmapShader` backed by the old (evicted) bitmap. For `TextureSource.Bitmap` with a recycled bitmap, this can cause undefined rendering behavior.

**CR-3** and **CR-4** are lower-risk: CR-3 produces harmless but unnecessary REPEAT mode on rotation-only transforms; CR-4 only degrades the color-filter cache hit rate under multi-tint scenes.

The `T^-1` pre-concat matrix math in `computeAffineMatrix()` is structurally correct. The `TextureTransform.init` validation (`absoluteValue > 0f`) correctly rejects `-0f`. The `PerFace.of()` factory with private constructor correctly enforces the no-nesting invariant. `IsoColor : MaterialData` with `override fun baseColor() = this` is correct. `CachedTexture` storing only `Bitmap` (no `BitmapShader`) is architecturally sound — the motivation (choosing TileMode at draw time) is valid.
