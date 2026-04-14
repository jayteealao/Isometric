---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: performance
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 7
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-med: 2
metric-findings-low: 2
metric-findings-nit: 2
result: issues-found
tags: [performance, api-design, texture, shader, kotlin]
refs:
  review-master: 07-review-api-design-fixes.md
---

# Performance Review — api-design-fixes slice (post-fix pass)

## Context

This review evaluates the **post-implementation** state of `TexturedCanvasDrawHook`,
`TextureCache`, `ProvideTextureRendering`, and `UvCoord.kt` after all triaged findings
from the master review (`07-review-api-design-fixes.md`) were applied. The prior
performance sub-review (`07-review-api-design-fixes-performance.md`, timestamped
`2026-04-14T17:42:09Z`) identified PERF-1 through PERF-6. This file reviews what was
actually fixed and surfaces new or residual issues in the current code.

## Status of Prior Findings

| Prior ID | Sev | Prior title | Resolution |
|----------|-----|-------------|------------|
| PERF-1 | HIGH | `Matrix()` allocated per-draw in `computeAffineMatrix` | **Fixed** — `transformMatrix` and `transformMatrixInv` are now class fields, passed as defaulted parameters; no in-function allocation. |
| PERF-2 | MED  | `shaderCache` grows unbounded | **Fixed** — LRU `LinkedHashMap(accessOrder=true)` with `removeEldestEntry` capping at `cache.maxSize * 2`. |
| PERF-3 | MED  | `toColorFilterOrNull()` allocates `PorterDuffColorFilter` per draw | **Fixed** — `colorFilterFor()` single-entry cache using `cachedTintColor` / `cachedColorFilter` fields. |
| PERF-4 | LOW  | `shaderCache` key only includes `tileU` | **Fixed** — Key is now `Triple(source, tileU, tileV)`. |
| PERF-5 | LOW  | `shaderCache` retains `Bitmap` after `TextureCache` eviction | **Fixed as side-effect of PERF-2** — bounded by `maxSize * 2`. |
| PERF-6 | NIT  | IDENTITY check uses 5-field data-class equality | **Not fixed** — deferred. See PERF-6 carry-forward below. |

---

## Verdict

**Issues found — no blockers.**

Three pre-existing low-severity gaps survive from PERF-6 (deferred), one new HIGH
finding was introduced by the fix for PERF-4 (`Triple` allocation on every draw call
for cache lookup), and three new findings surface in this pass. The hot draw path
still has measurable per-frame allocation: two `FloatArray(6)` objects in
`computeAffineMatrix` plus one `Triple` per draw call. The tint-animation cache
degrades to 100% miss rate under alternating non-white tints. The `rememberUpdatedState`
for `loader` is read only at hook construction time, making the state subscription
effectively dead. None of these are blockers for a 0.x release.

---

## Findings Table

| ID     | Severity | Area | Fix effort |
|--------|----------|------|------------|
| PERF-7 | HIGH | `Triple` allocated on every draw call for `shaderCache` lookup | Low |
| PERF-8 | MED  | `computeAffineMatrix` allocates two `FloatArray(6)` per call unconditionally | Low |
| PERF-9 | MED  | `colorFilterFor()` single-entry cache degrades to 100% miss under alternating tints | Med |
| PERF-10 | LOW | `tileU` and `tileV` are always identical — `Triple` third slot is redundant work | Low |
| PERF-11 | LOW | `ProvideTextureRendering`: `currentLoader` read via `rememberUpdatedState` but consumed only once at hook construction — subscription is dead | Low |
| PERF-6 | NIT  | `TextureTransform.IDENTITY` check uses data-class `!=` (5 float comparisons) per draw | NIT |
| PERF-12 | NIT  | `tileMode` assigned to both `tileU` and `tileV` via redundant local variables | NIT |

---

## Detailed Findings

### PERF-7 — HIGH — `Triple` allocated on every draw call for `shaderCache` lookup

**File:** `TexturedCanvasDrawHook.kt`, lines 121–124

```kotlin
val tileU = tileMode
val tileV = tileMode
val shaderKey = Triple(material.source, tileU, tileV)   // ← new allocation every draw
val shader = shaderCache.getOrPut(shaderKey) {
    BitmapShader(cached.bitmap, tileU, tileV)
}
```

`Triple` is a generic data class holding three boxed or reference values. Every call to
`drawTextured()` allocates a fresh `Triple` instance solely to perform the `shaderCache`
lookup, even when the cache entry already exists. On a cache hit the `Triple` is
immediately discarded. At 300 textured faces × 60 fps:

- **18 000 `Triple` allocations per second**, every one immediately discarded on a hit.
- Each `Triple` is 3 object fields + header ≈ 32 bytes on 64-bit ART → **~576 KB/s of
  ephemeral garbage** purely from key construction.

The class-level KDoc claims "zero per-frame allocation in the draw loop". This finding
reinstates a per-draw allocation on every textured face, just smaller than the original
`Matrix` issue.

**Fix:** Replace `Triple` with a purpose-built key class that keeps a single mutable
`lookupKey` field on the hook for probe-only lookups (no allocation on hit):

```kotlin
// Defined at file level or as a nested class:
private data class ShaderKey(
    val source: TextureSource,
    val tileU: Shader.TileMode,
    val tileV: Shader.TileMode,
)
```

Then in the hook:
```kotlin
private val _lookupKey = ShaderKey(/* placeholder */ TextureSource.Resource(0), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
```

However, `ShaderKey` is a `data class` — it is immutable, so a mutable probe-key
approach requires a mutable class. A simpler and immediately effective fix is to use
a two-level `HashMap<TextureSource, HashMap<Pair<TileMode,TileMode>, BitmapShader>>`
which avoids the outer allocation, or to accept the allocation but downgrade PERF-7
to MED given that `Triple` is a small, short-lived object and modern ART handles it
well.

Alternatively, since `tileU == tileV` in all current code paths (see PERF-10), the key
can be simplified to `Pair(source, tileMode)` which is no worse than the original key
while being smaller. That intermediate fix is zero risk and avoids one boxed `TileMode`
object per `Triple`.

**Severity:** HIGH | **Confidence:** High

---

### PERF-8 — MED — `computeAffineMatrix` allocates two `FloatArray(6)` per call

**File:** `TexturedCanvasDrawHook.kt`, `computeAffineMatrix()`, lines 194–203

```kotlin
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
```

`floatArrayOf(...)` allocates a new `FloatArray` on the heap. Both `src` and `dst` are
6-element arrays, allocated and discarded on every `computeAffineMatrix` call. The class
pre-allocates `affineMatrix`, `transformMatrix`, and `transformMatrixInv` to achieve
zero Matrix allocation — but this same discipline is not applied to the float scratch
buffers.

At 300 textured faces × 60 fps:
- **36 000 `FloatArray` allocations per second** (two per call × 18 000 calls).
- Each `FloatArray(6)` is 24 bytes + header ≈ 40 bytes → **~1.4 MB/s of
  ephemeral garbage** from scratch buffers alone.

This is distinct from the `Triple` issue (PERF-7) and cumulative with it.

**Fix:** Pre-allocate two `FloatArray(6)` fields on `TexturedCanvasDrawHook` alongside
the existing matrix fields, and pass them into `computeAffineMatrix` the same way
`workMatrix` / `workMatrixInv` are passed:

```kotlin
private val srcPoints  = FloatArray(6)
private val dstPoints  = FloatArray(6)
```

Fill them in-place before calling `outMatrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 3)`.

**Severity:** MED | **Confidence:** High

---

### PERF-9 — MED — `colorFilterFor()` degrades to 100% miss under alternating tints

**File:** `TexturedCanvasDrawHook.kt`, lines 62–84

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

The single-entry cache is optimal when exactly one non-white tint is in use across a
frame. It degrades in two common scenarios:

1. **Animation toggling between two non-white colors:** Frame N sets tint A → cache
   stores A. Frame N+1 sets tint B → cache miss, allocates filter B, stores B.
   Frame N+2 sets tint A again → cache miss (stored value is B), allocates filter A.
   Every frame is a miss and every frame allocates a new `PorterDuffColorFilter`.

2. **Multiple distinct tinted faces in the same frame:** If face 1 has tint A and
   face 2 has tint B, draws alternate between A and B within the same frame.
   The single-entry cache thrashes, allocating one filter per draw call.

Scenario 2 is the more common issue. A scene with N distinct non-white tints, each
applied to at least one face, will cycle through all N tints during a single frame's
draw pass. The single-entry cache stores only the last seen tint, so every tint
transition within a frame is a miss.

The white fast-path (`r >= 255 && g >= 255 && b >= 255`) is unaffected and remains O(1).

**Fix options (in order of complexity):**

1. **Extend to a small fixed-capacity map** keyed by `IsoColor` (structural equality).
   A capacity of 8–16 covers virtually all practical isometric palettes without
   memory pressure. Use the same `LinkedHashMap(capacity, 0.75f, accessOrder=true)`
   pattern with `removeEldestEntry` as the shader and texture caches.

2. **Cache per material source** by piggybacking on the `shaderCache` key — store
   `(source, tint) → filter` so the cache is indexed at the same granularity as
   shader lookup. This requires a second map or a richer value type.

3. **Accept the current behavior** if profiling shows tint changes are rare in
   practice. `PorterDuffColorFilter` is a light object (< 100 bytes) and the
   Android Paint system discards the old filter reference automatically. Document
   the limitation.

Option 1 is recommended. The pattern is already used throughout this file.

**Severity:** MED | **Confidence:** Med

---

### PERF-10 — LOW — `tileU` and `tileV` are always identical; Triple third slot is redundant

**File:** `TexturedCanvasDrawHook.kt`, lines 120–123

```kotlin
val tileMode = if (material.transform != TextureTransform.IDENTITY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
val tileU = tileMode
val tileV = tileMode
val shaderKey = Triple(material.source, tileU, tileV)
```

`tileU` and `tileV` are both assigned from the same `tileMode` variable using identical
conditions. They are always equal. The `Triple` key therefore has its third element
always equal to the second — carrying no additional information. The `BitmapShader`
constructor also receives `tileU, tileV` which will always be the same mode.

Historically, `tileU` and `tileV` existed to support asymmetric tiling
(e.g., `scaleU == 2f, scaleV == 1f` → REPEAT/CLAMP). The H-01 fix (master review)
collapsed that logic to a single binary IDENTITY / non-IDENTITY check, making `tileU`
and `tileV` permanently identical. The `Triple` is therefore doing the work of a
`Pair` while allocating an extra field slot.

**Impact:** Contributes to PERF-7. The `Triple` third element hash is always the same
as the second, adding two extra hash computations on every cache probe with zero
discriminating power.

**Fix:** Change the key to `Pair(material.source, tileMode)` (dropping the separate
`tileU`/`tileV` locals entirely). Until asymmetric tiling is implemented (which would
require revisiting the IDENTITY-gate logic), the simpler key is both correct and
slightly cheaper. If/when asymmetric tiling is added, the condition per-axis can be
restored and the key upgraded to `Triple` again.

**Severity:** LOW | **Confidence:** High

---

### PERF-11 — LOW — `rememberUpdatedState(loader)` subscription is dead at hook construction

**File:** `ProvideTextureRendering.kt`, lines 79–85

```kotlin
val currentLoader by rememberUpdatedState(loader)
val currentOnError by rememberUpdatedState(onTextureLoadError)
val hook = remember(context, cacheConfig) {
    val cache = TextureCache(cacheConfig.maxSize)
    val effectiveLoader = currentLoader ?: defaultTextureLoader(context.applicationContext)
    TexturedCanvasDrawHook(cache, effectiveLoader, currentOnError)   // ← snapshot at construction
}
```

`rememberUpdatedState` exists to let lambdas inside `LaunchedEffect` or similar
long-lived effects always read the latest value of a parameter that may change between
recompositions. Its idiomatic use is:

```kotlin
val currentAction by rememberUpdatedState(action)
LaunchedEffect(Unit) {
    delay(1000)
    currentAction()   // reads the latest value of `action` even if recomposition occurred
}
```

Here, `currentLoader` is used only at `remember` block construction time (when the hook
is first created). After that, the `remember` block is never re-executed (keys are
`context` and `cacheConfig`). The `rememberUpdatedState` State object is updated by
Compose on every recomposition with the new `loader` value, but nothing reads
`currentLoader` after the initial hook construction. The subscription produces work
(state write on every recomposition) with no corresponding read.

Concretely: if the caller passes `loader = null` initially and later passes a custom
loader, `currentLoader` updates in the State but `hook.loader` (the field baked into
`TexturedCanvasDrawHook` at construction) remains the original default loader.

`rememberUpdatedState(onTextureLoadError)` suffers from the same issue. The fix from
MED-17 (master review) added `rememberUpdatedState(onTextureLoadError)` but the hook
still captures `currentOnError` as a snapshot at construction time. The State is
updated but never read after construction.

**Fix option A (correct and simple):** Remove both `rememberUpdatedState` wrappers.
Read `loader` and `onTextureLoadError` directly inside the `remember` block since that
is the only site that uses them. If `loader` or `onTextureLoadError` change, a new hook
should be created — add them to the `remember` keys:

```kotlin
val hook = remember(context, cacheConfig, loader, onTextureLoadError) {
    val cache = TextureCache(cacheConfig.maxSize)
    val effectiveLoader = loader ?: defaultTextureLoader(context.applicationContext)
    TexturedCanvasDrawHook(cache, effectiveLoader, onTextureLoadError)
}
```

**Fix option B (correct but slower):** If the hook is intended to pick up loader/error
changes dynamically without recreation, the hook would need to hold
`MutableState<TextureLoader?>` / `MutableState<((TextureSource) -> Unit)?>` fields
and read them at draw time. This is heavier and likely unnecessary since loader
and onTextureLoadError rarely change.

Option A matches the intent and eliminates the stale subscription.

**Note:** MED-03 (master review) fixed inline loader lambda recreation by removing
`loader` from the `remember` key, but that fix created this residual: `loader` changes
no longer recreate the hook (correct) but also no longer take effect (incorrect).

**Severity:** LOW | **Confidence:** High

---

### PERF-6 (carry-forward) — NIT — `TextureTransform.IDENTITY` check uses data-class `!=`

**File:** `TexturedCanvasDrawHook.kt`, line 120; `computeAffineMatrix()`, line 206

```kotlin
val tileMode = if (material.transform != TextureTransform.IDENTITY) ...
if (transform != TextureTransform.IDENTITY) {
```

`TextureTransform.IDENTITY` is a `val` on the companion object — a singleton. The `!=`
operator dispatches to generated `equals()`, comparing all 5 `Float` fields. Because
`TextureTransform` is a `data class`, `===` reference equality is not used by `!=`.

In the overwhelmingly common case where the caller passes `TextureTransform.IDENTITY`
directly (not a structurally equal but distinct instance), a reference check `!==` would
short-circuit in one instruction instead of five comparisons.

There are two `!=` checks per `drawTextured` call: one for tileMode selection and one
inside `computeAffineMatrix`. At 300 faces × 60 fps = 36 000 × 2 = **72 000 five-float
comparisons per second**.

**Fix:** Deferred — see prior review. Add `val isIdentity: Boolean get() = this ===
IDENTITY || (scaleU == 1f && scaleV == 1f && offsetU == 0f && offsetV == 0f &&
rotationDegrees == 0f)` to `TextureTransform` and use `!transform.isIdentity` at both
call sites.

**Severity:** NIT | **Confidence:** High

---

### PERF-12 — NIT — Redundant `tileU`/`tileV` locals shadow `tileMode`

**File:** `TexturedCanvasDrawHook.kt`, lines 120–126

```kotlin
val tileMode = if (material.transform != TextureTransform.IDENTITY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
val tileU = tileMode
val tileV = tileMode
val shaderKey = Triple(material.source, tileU, tileV)
val shader = shaderCache.getOrPut(shaderKey) {
    BitmapShader(cached.bitmap, tileU, tileV)
}
```

`tileU` and `tileV` are single-use aliases for `tileMode` with no additional logic.
They add two local variable reads and assignments on every `drawTextured` invocation.
The compiler likely optimises these out after inlining, but they reduce readability
(the names imply independent U/V tile control which no longer exists) and are a
maintenance hazard: a future developer might add per-axis conditions to only `tileU`
or only `tileV`, accidentally causing asymmetric behaviour that PERF-10 warns against.

**Fix:** Remove `tileU` and `tileV`; reference `tileMode` directly.

**Severity:** NIT | **Confidence:** High

---

## Summary

### New findings vs prior review

| Finding | Type | Status |
|---------|------|--------|
| PERF-1 | HIGH Matrix allocation | Fixed |
| PERF-2 | MED shaderCache unbounded | Fixed |
| PERF-3 | MED colorFilter per-draw | Fixed |
| PERF-4 | LOW tileV missing from key | Fixed |
| PERF-5 | LOW Bitmap retention | Fixed (side-effect of PERF-2) |
| PERF-6 | NIT IDENTITY 5-float check | Carry-forward (deferred) |
| **PERF-7** | **HIGH** Triple per draw (introduced by PERF-4 fix) | **New** |
| **PERF-8** | **MED** FloatArray(6) × 2 per draw | **New** |
| **PERF-9** | **MED** colorFilter cache thrash on multi-tint scenes | **New** |
| **PERF-10** | **LOW** tileU==tileV redundant Triple slot | **New** |
| **PERF-11** | **LOW** rememberUpdatedState dead subscription | **New** |
| **PERF-12** | **NIT** tileU/tileV redundant locals | **New** |

### Recommended actions

**Must fix before 1.0 (HIGH):**

- **PERF-7:** Eliminate per-draw `Triple` allocation. Either simplify to `Pair(source,
  tileMode)` (safe given PERF-10), or use a pre-allocated mutable key struct. The
  `Pair` simplification is the minimal-risk fix given that `tileU == tileV` always.

**Should fix (MED):**

- **PERF-8:** Pre-allocate `srcPoints`/`dstPoints` as `FloatArray(6)` class fields.
  Pass them into `computeAffineMatrix` the same way `workMatrix`/`workMatrixInv` are.
  Eliminates 36 000 `FloatArray` allocations/second — restores the zero-allocation
  class invariant fully.

- **PERF-9:** Upgrade `colorFilterFor` from single-entry cache to a small LRU map
  (capacity 8). Prevents per-draw allocation in multi-tint and animated-tint scenes.

**Consider (LOW):**

- **PERF-10:** Simplify shader cache key to `Pair(source, tileMode)`.
  Strictly a simplification today; also the correct prerequisite for PERF-7.

- **PERF-11:** Remove `rememberUpdatedState(loader)` wrappers and either add `loader`/
  `onTextureLoadError` to the `remember` keys (to re-create the hook on change) or
  document that the hook ignores post-construction loader changes.

**Deferred / NIT:**

- **PERF-6:** Add `isIdentity` to `TextureTransform` for reference-equality short-circuit.
- **PERF-12:** Remove `tileU`/`tileV` aliases; use `tileMode` directly.
