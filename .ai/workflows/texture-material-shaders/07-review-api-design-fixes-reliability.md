---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: reliability
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 9
metric-findings-blocker: 0
metric-findings-high: 2
metric-findings-med: 4
metric-findings-low: 2
metric-findings-nit: 1
result: issues-found
tags: [reliability, error-handling, thread-safety, lru-eviction, exception-scope, compose-locals]
refs:
  review-master: 07-review-api-design-fixes.md
---

# Reliability Review: api-design-fixes

## Scope

Focused reliability pass over the five files introduced or substantially changed by the
`api-design-fixes` slice:

| File | Focus Area |
|------|------------|
| `TextureLoader.kt` | Exception catch scope; null ambiguity |
| `TexturedCanvasDrawHook.kt` | Null fallback path; colorFilterFor() cache; shaderCache bound |
| `ProvideTextureRendering.kt` | rememberUpdatedState correctness; nesting behaviour |
| `TextureCache.kt` | Bitmap thread-safety; LRU eviction size |
| `IsometricMaterial.kt` | PerFace.of() concurrency; immutability |

---

## Findings

### REL-01 — catch(Exception) swallows OOM in loadResource / loadAsset [HIGH]

**File:** `TextureLoader.kt:56–68`
**Confidence:** High

Both `loadResource` and `loadAsset` catch `Exception`. `OutOfMemoryError` extends `Error`, not
`Exception`, so OOM is **not** silently swallowed here — that specific risk is absent.

However, `Exception` is still broader than needed. It catches:

- `RuntimeException` from `BitmapFactory` (decode failures, malformed data) — correct to catch
- `IllegalArgumentException` from `resources.openRawResource()` when `resId` is valid at the
  `TextureSource.Resource.init` level but references a non-existent resource ID at runtime
  (e.g., dynamic IDs in split APKs) — arguably correct
- Any `RuntimeException` thrown by a **custom** `BitmapFactory.Options.inBitmap` decode path or
  registered `BitmapFactory.Options` subclass — harder to diagnose since it's logged as "texture
  decode failure" with no hint of root cause

The real gap is that `null` return from `BitmapFactory.decodeResource` (source not found,
decode returns null without throwing) is **indistinguishable** from a caught exception.
Both map to the same `null` result logged with the same warning. Callers of the public `TextureLoader`
interface see no difference between "resource ID not found in this APK" and "PNG header corrupt".
This matters for retry logic or error analytics: the `onTextureLoadError` callback receives only
the `TextureSource`, not a reason code.

**Severity:** HIGH — `null` from a not-found resource and `null` from a decode crash are
indistinguishable at the callback site. This is a contract ambiguity that blocks correct
error-recovery logic.

**Fix options:**
- Add a sealed `LoadResult` type (`Success(Bitmap)`, `NotFound`, `DecodeFailed(cause: Throwable?)`)
  and thread the reason through `onTextureLoadError` — cleanest but a public API change.
- Narrower short-term: log distinct messages for "decode returned null" vs "exception thrown",
  and document that the callback is intentionally reason-agnostic (accept the ambiguity as
  a design choice, not a bug).

---

### REL-02 — checkerboard cached under failed key silently suppresses repeated onTextureLoadError [HIGH]

**File:** `TexturedCanvasDrawHook.kt:143–153` (`resolveToCache`)
**Confidence:** High

When `loader.load(source)` returns null, `onTextureLoadError?.invoke(source)` fires once, then
`cache.put(source, checkerboard)` is called. On every subsequent draw the cache hit path returns
the cached checkerboard without re-invoking `onTextureLoadError`.

This is the **intended behavior for performance** (avoid repeated failed loads and repeated error
notifications), but it has an undocumented reliability contract: once a source fails, it is
permanently mapped to checkerboard for the lifetime of the cache. There is no way to trigger
a retry short of clearing the entire cache.

Two concrete failure modes arise:

1. **Transient failure at cold start.** If an asset is missing on first draw because the APK
   install is incomplete (split delivery), the checkerboard is cached. After the split arrives
   the texture is never retried. The user sees checkerboard permanently until the app restarts.

2. **Custom loader returning null for "not yet loaded"** (e.g., async loaders that return null
   while an async fetch is in flight). The first null silently locks that source to checkerboard.
   The KDoc of `TextureLoader.load()` says "synchronous I/O acceptable", but does not
   explicitly forbid "return null temporarily". Callers can hit this trap easily.

**Severity:** HIGH — silent permanent lock-out of a valid texture source under transient-null
semantics is a reliability footgun with no escape hatch.

**Fix options:**
- Only cache the checkerboard if a distinct "permanent failure" is signalled (e.g., a separate
  `LoadResult.NotFound` variant per REL-01).
- Document explicitly that `null` from `load()` is treated as a permanent failure and caches the
  fallback; callers must not return `null` for "not yet ready".
- Add `cache.evict(source)` to the public `TextureCache` API so callers can force a retry.

---

### REL-03 — rememberUpdatedState hook is read once at construction, not on every draw [MED]

**File:** `ProvideTextureRendering.kt:79–85`
**Confidence:** High

```kotlin
val currentLoader by rememberUpdatedState(loader)
val currentOnError by rememberUpdatedState(onTextureLoadError)
val hook = remember(context, cacheConfig) {
    val effectiveLoader = currentLoader ?: defaultTextureLoader(...)
    TexturedCanvasDrawHook(cache, effectiveLoader, currentOnError)
}
```

`rememberUpdatedState` captures a `State<T>` that always reflects the latest value, but the
`remember` lambda captures `currentLoader` and `currentOnError` **by value** at the moment
`remember` first runs. The lambda reads from the state holders once, extracts `.value` at that
instant, and passes the extracted values into the `TexturedCanvasDrawHook` constructor.

After construction, the hook holds a plain (non-reactive) `loader` and `onTextureLoadError`
reference. If the parent recomposes with a new `loader` or `onTextureLoadError`,
`rememberUpdatedState` updates the `State` objects, but the hook is never notified.

`rememberUpdatedState` is the correct pattern when a **callback** (lambda) is captured by
a `LaunchedEffect` or `DisposableEffect` that reads `currentValue` on every call. Here the
callbacks are wrapped into a `TexturedCanvasDrawHook` object that stores them as final fields.
The pattern does not propagate updates.

The fix applied by MED-17 (loader removed from `remember()` key, `rememberUpdatedState` added)
prevents unnecessary hook recreation — but it also silently breaks live-updates to `loader` after
first composition. If `loader` changes (e.g., toggling a debug loader), the running hook uses the
stale loader forever.

**Severity:** MED — for typical usage `loader` and `onTextureLoadError` are stable across
compositions, so this is rarely observable. It becomes a reliability bug when either parameter
is state-driven.

**Fix:** Pass `State<TextureLoader?>` and `State<((TextureSource)->Unit)?>` into the hook and
dereference `.value` on each `resolveToCache()` call, or re-add `loader` and `onTextureLoadError`
to the `remember()` key (accepting the cache-flush cost on loader change, which is correct
behaviour since a loader change implies the cache contents are stale anyway).

---

### REL-04 — Nested ProvideTextureRendering silently overrides parent; independent caches accumulate [MED]

**File:** `ProvideTextureRendering.kt:86`
**Confidence:** High**

`LocalMaterialDrawHook` is a `staticCompositionLocalOf`. Providing it a second time in a nested
subtree silently replaces the parent hook. This is standard Compose behaviour but has two
reliability consequences:

1. Each nested `ProvideTextureRendering` allocates a full `TextureCache`. If N scenes each wrap
   in their own provider, N caches each hold up to `maxSize` decoded bitmaps. On a memory-constrained
   device this multiplies peak memory use by N. There is no lifecycle event that triggers cache
   eviction when the nested provider leaves composition — the cache is GC-eligible only after
   the `TexturedCanvasDrawHook` is GC-collected.

2. The KDoc warns "nest providers only if subtrees intentionally need independent caches", but
   does not explain the memory consequence or suggest a max total budget. Users following the
   documentation literally ("a provider does not share its cache with sibling or parent providers")
   could nest accidentally and not notice until the device low-memory-kills the process.

**Severity:** MED — design is documented and intentional, but the memory impact of nesting is
not quantified and there is no guard (e.g., a debug-mode assertion that flags double-nesting).

**Fix:** Add a debug-mode `check` that detects `LocalMaterialDrawHook.current != null` inside
a new `ProvideTextureRendering`, and logs a warning (not a crash). Document the memory impact
quantitatively in the KDoc.

---

### REL-05 — colorFilterFor() WHITE fast-path uses floating-point >= 255.0; IsoColor channels clamped to 255 [MED]

**File:** `TexturedCanvasDrawHook.kt:76–84`
**Confidence:** Med

```kotlin
if (tint.r >= 255.0 && tint.g >= 255.0 && tint.b >= 255.0) return null
```

`IsoColor` channels are validated to `0.0..255.0` in `IsoColor.init`. `IsoColor.WHITE` is
`IsoColor(255, 255, 255)` — all channels exactly 255.0. The condition `>= 255.0` correctly
matches WHITE.

The subtle risk: `toColorFilterOrNull()` (the same check, duplicated at the extension function
level) and `colorFilterFor()` both implement the same "is white?" guard independently. They are
currently in sync, but if one is updated without the other (e.g., a future "treat 254.9 as white
for tolerance" change), they diverge. The `colorFilterFor()` cache uses `tint == cachedTintColor`
(structural equality on a data class), so a change in the fast-path threshold in one location
would produce inconsistent filter application.

Also: `colorFilterFor()` returns the cached `cachedColorFilter` on tint equality, but the
`cachedColorFilter` may have been generated by the **previous** invocation of `colorFilterFor()`
which used `toColorFilterOrNull()`. If `toColorFilterOrNull()` was later made to return `null` for
a wider set of near-white colours, the cached filter from a prior call could be a non-null
`PorterDuffColorFilter` that `colorFilterFor()` returns from cache — incorrectly applying a tint.
This is a latent consistency issue, not a current bug.

**Severity:** MED — not a current defect, but a reliability trap for future maintainers due to
duplicated "is white" predicate.

**Fix:** Unify the "is white" check into a single `IsoColor.isWhite()` extension function called
by both `colorFilterFor()` and `toColorFilterOrNull()`.

---

### REL-06 — shaderCache eviction at maxSize * 2 allows cache to temporarily hold evicted Bitmap references [LOW]

**File:** `TexturedCanvasDrawHook.kt:52–56`
**Confidence:** High

The `shaderCache` LRU evicts entries when `size > cache.maxSize * 2`. This means the shader cache
can hold up to `(maxSize * 2)` entries before any eviction occurs. Each `BitmapShader` holds a
strong reference to its source `Bitmap`.

When `maxSize = 1` (the pathological case from the review prompt): `TextureCache` holds 1 bitmap.
`shaderCache` holds up to 2 shaders (each referencing a bitmap). If the single cached bitmap in
`TextureCache` is evicted and a different bitmap replaces it, the old bitmap is still referenced
by the `BitmapShader` in `shaderCache`. The `BitmapShader` — and therefore the old bitmap — is
kept alive until `shaderCache` also evicts that entry. At `maxSize = 2`, the old shader occupies
one of the 2 available slots; with asymmetric TileModes, the same source can have 2 shader entries
(CLAMP+CLAMP and REPEAT+REPEAT), meaning both slots are consumed by the old source's shaders
before any eviction fires. The old bitmap is never freed during this window.

**Severity:** LOW — `maxSize = 1` is an extreme config unlikely in practice, and the retention
window is bounded. However, the `maxSize * 2` ratio is undocumented as an intentional choice.

**Fix:** Document why `maxSize * 2` (a source can have at most 2 tile-mode combinations, so 2x
is the worst-case shader-per-texture multiplier). Add a KDoc note explaining the memory model.

---

### REL-07 — Bitmap in CachedTexture is not thread-safe; draw calls must stay on main thread [LOW]

**File:** `TextureCache.kt:13`
**Confidence:** High

`android.graphics.Bitmap` is not thread-safe for concurrent read and write access. The design
correctly restricts all operations to the main thread (documented in `TextureCache` KDoc and
`TextureLoader` KDoc). The checkerboard `Bitmap` in `TexturedCanvasDrawHook` is created lazily
and shared across all draw calls — safe because draw always occurs on the main thread.

The remaining risk: `TextureSource.Bitmap` wraps a caller-provided bitmap. The caller retains
ownership and the KDoc says "do not recycle while any material referencing it is active". But
there is no enforcement. If a caller recycles the bitmap on a background thread while the main
thread is mid-draw (e.g., inside a Glide/Coil lifecycle callback), `BitmapShader` will attempt
to read recycled bitmap memory.

`ensureNotRecycled()` is called only at load time (inside `DefaultTextureLoader.load()`), not
before each `BitmapShader` upload. A bitmap recycled after the initial load-and-cache is
undetected until the GPU driver faults.

**Severity:** LOW — risk is limited to `TextureSource.Bitmap` callers, and requires caller error
(recycling a live bitmap). The contract is documented. Flagged because the consequence is a native
crash, not a graceful degradation.

**Fix:** Call `bitmap.isRecycled` check (or `ensureNotRecycled()`) inside `resolveToCache()` when
the cached `CachedTexture` is returned, not just at load time. If recycled, evict the cache entry
and reload.

---

### REL-08 — PerFace.of() is reliably safe for concurrent read (immutable after construction) [NIT / INFORMATIONAL]

**File:** `IsometricMaterial.kt:102–105`
**Confidence:** High

`PerFace.of()` constructs a `PerFace` instance with:
- `faceMap: Map<PrismFace, MaterialData>` — the passed-in map is stored directly (not defensively
  copied). If the caller passes a mutable map (e.g., `HashMap`) and mutates it after `of()`, the
  internal state of `PerFace` can change.
- `default: MaterialData` — immutable reference.

In practice, `PerFaceMaterialScope.build()` calls `buildMap { ... }` which returns an immutable
`Map` from the Kotlin stdlib, so the DSL path is safe. Direct callers of `PerFace.of()` who pass
a mutable map can corrupt state.

**Severity:** NIT — the public-facing DSL path is safe; `PerFace.of()` is documented as "advanced
use case". The risk is from direct callers constructing a `HashMap` and passing it. A defensive
copy (`faceMap.toMap()`) inside `PerFace` constructor would make the invariant unconditional.

---

### REL-09 — MED-03 fix (rememberUpdatedState for loader) is incomplete at hook construction [MED]

**File:** `ProvideTextureRendering.kt:79–85`
**Confidence:** High

This is related to REL-03 but distinct. After the MED-03 fix, `loader` was removed from the
`remember()` key to avoid cache-flush on every recomposition when an inline lambda is passed.
The current `remember(context, cacheConfig)` keying is correct for stability.

However, the `remember` block resolves `effectiveLoader` eagerly:

```kotlin
val effectiveLoader = currentLoader ?: defaultTextureLoader(context.applicationContext)
TexturedCanvasDrawHook(cache, effectiveLoader, currentOnError)
```

`currentLoader` is read via `by rememberUpdatedState(loader)` which means `currentLoader` is a
delegated property backed by `State<TextureLoader?>`. Reading `currentLoader` **inside** the
`remember` lambda reads the `State.value` at the time `remember` fires — which is correct for
the initial composition. But because `loader` is no longer in the `remember` key, subsequent
changes to `loader` do NOT cause `remember` to re-execute, so `effectiveLoader` inside the hook
stays stale. `rememberUpdatedState` is only useful when the lambda/callback is invoked
**after** the `remember` block finishes (e.g., in an effect), not during the `remember` body
itself.

Net effect: `loader` changes are silently ignored after first composition. If a user passes
`loader = if (debugMode) debugLoader else null` and `debugMode` changes, the hook continues
using the stale loader. This is the same root cause as REL-03 but worth calling out separately
because the MED-03 fix note in the master review ("rememberUpdatedState(loader); loader removed
from remember() key") reads as a completed fix, when in fact the `rememberUpdatedState` wrapping
is not doing useful work at all in this position — it is a no-op that provides a false sense of
correctness.

**Severity:** MED — the false sense of correctness (fix looks done, but isn't) is the reliability
concern. See REL-03 for fix options.

---

## Summary Table

| ID | Sev | File | Issue |
|----|-----|------|-------|
| REL-01 | HIGH | TextureLoader.kt:56–68 | catch(Exception) + BitmapFactory null return indistinguishable — reason for failure not surfaced to callback |
| REL-02 | HIGH | TexturedCanvasDrawHook.kt:143–153 | Failed source permanently locked to checkerboard; no retry escape hatch |
| REL-03 | MED | ProvideTextureRendering.kt:79–85 | rememberUpdatedState does not propagate loader/onError changes into running hook |
| REL-04 | MED | ProvideTextureRendering.kt:86 | Nested providers silently multiply cache memory; no guard or quantified KDoc warning |
| REL-05 | MED | TexturedCanvasDrawHook.kt:76–84 | Duplicated "is white" predicate risks divergence between colorFilterFor() and toColorFilterOrNull() |
| REL-06 | LOW | TexturedCanvasDrawHook.kt:52–56 | shaderCache maxSize * 2 eviction threshold undocumented; can retain Bitmaps past TextureCache eviction |
| REL-07 | LOW | TextureCache.kt + TexturedCanvasDrawHook.kt | Recycled TextureSource.Bitmap not checked on cache hit — only at initial load time |
| REL-08 | NIT | IsometricMaterial.kt:102–105 | PerFace.of() does not defensively copy faceMap; mutable map passed directly can corrupt PerFace state |
| REL-09 | MED | ProvideTextureRendering.kt:79–85 | MED-03 fix (rememberUpdatedState) is a no-op at the remember() construction site — creates false impression of live-update support |

**Total:** BLOCKER: 0 | HIGH: 2 | MED: 4 | LOW: 2 | NIT: 1

---

## Triage Recommendations

| ID | Sev | Recommended Action | Size |
|----|-----|--------------------|------|
| REL-01 | HIGH | Document null ambiguity as a known limitation in TextureLoader KDoc; distinguish via log message ("returned null" vs "threw"). Full sealed-result type is additive and can be deferred. | xs |
| REL-02 | HIGH | Document the permanent-caching semantic explicitly in resolveToCache() KDoc; add cache.evict(source) API as escape hatch for retry scenarios | s |
| REL-03 | MED | Re-add loader to remember() key OR pass State<> into the hook for deref on each call | xs |
| REL-09 | MED | Same fix as REL-03; the two are the same root cause | — |
| REL-04 | MED | Add debug-mode warning log when LocalMaterialDrawHook.current != null; add KDoc note quantifying memory impact | xs |
| REL-05 | MED | Extract IsoColor.isWhite() extension; call from both sites | xs |
| REL-06 | LOW | Add KDoc comment explaining maxSize * 2 ratio | xs |
| REL-07 | LOW | Add bitmap.isRecycled check on cache hit inside resolveToCache() | xs |
| REL-08 | NIT | Defensive copy: faceMap.toMap() in PerFace constructor | xs |
