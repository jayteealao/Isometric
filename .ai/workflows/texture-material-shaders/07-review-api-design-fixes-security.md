---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: api-design-fixes
review-command: security
status: complete
updated-at: "2026-04-14T22:14:55Z"
metric-findings-total: 6
metric-findings-blocker: 0
metric-findings-high: 0
metric-findings-med: 1
metric-findings-low: 2
metric-findings-nit: 3
result: issues-found
tags: [security, api-design, kotlin, path-traversal, log-redaction]
refs:
  review-master: 07-review-api-design-fixes.md
---

## Findings

| ID | Sev | Conf | File:Line | Issue |
|----|-----|------|-----------|-------|
| SEC-1 | MED | High | `TextureSource.kt:41-43` | Path traversal: split-based `..` check does not reject dot-containing components (`....`, `./..`) — `File.normalize()` covers standard cases but does not close all bypasses |
| SEC-2 | LOW | High | `TextureLoader.kt:63-68` | `catch (e: Exception)` is overly broad — masks programming errors (NPE, ClassCastException) alongside expected I/O failures |
| SEC-3 | LOW | High | `TextureLoader.kt:63-67` | No bitmap size limit before `BitmapFactory.decodeStream` — large bundled assets cause silent OOM on main thread |
| SEC-4 | LOW | Med | `TexturedCanvasDrawHook.kt:143-153` | Custom loader can pin oversized bitmaps in LRU cache — amplification / cache-stuffing risk from trusted but misconfigured callers |
| SEC-5 | NIT | High | `TextureSource.kt:25` | Negative resource IDs not rejected — `resId != 0` implies only zero is invalid, but negative IDs always fail at load time |
| SEC-6 | NIT | High | `ProvideTextureRendering.kt:59-61` | KDoc example passes `source.toString()` to an analytics call — `Asset(path=...)` toString leaks the full path string to external analytics |

---

## Detailed Findings

### SEC-1: Split-based `..` check is bypassable with non-standard dot sequences [MED]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/TextureSource.kt:41-43`

**Evidence:**
```kotlin
require(".." !in path.split("/", "\\")) {
    "Asset path must not contain '..' components, got '$path'"
}
```

**Issue:**
The split-based check rejects components that equal `".."` exactly. It does not block:
- `"textures/..../secret"` — component `"...."` is not `".."`, passes the split check.
- `"textures/./.."`  — the literal `".."` here would be caught, but `"./..` as one component would not.
- URL-percent-encoded traversal: `"textures/%2e%2e/secret"` — not decoded by the check; whether `AssetManager.open` resolves percent-encoding on all OEM implementations is unspecified.

The defense-in-depth `File.normalize()` check added after the split check does close the normal `./..` and redundant-separator cases, since `java.io.File.normalize()` (Kotlin stdlib) resolves `.` and `..` without touching the filesystem. The normalized path is then checked for a `..` prefix and a `/../` interior segment.

However, `File.normalize()` operates on the host JVM's path rules (which use `/` on Android). It does not decode percent-encoding. A path like `"textures/%2e%2e/asset.png"` passes both the split check and the `File.normalize()` check with the normalized form `"textures/%2e%2e/asset.png"` — a traversal attempt that relies on the `AssetManager` decoding the percent-encoded sequence.

In practice, AOSP `AssetManager.open` resolves a pre-built asset index from the APK and does not perform raw filesystem traversal, so exploitation requires a non-standard OEM `AssetManager`. The risk is theoretical in the current Android ecosystem but the validation inconsistency is worth documenting.

**Fix:** Add an explicit `require('%' !in path) { "Asset path must not contain percent-encoded characters" }` guard, or perform `java.net.URLDecoder.decode(path, "UTF-8")` before all checks and re-validate the decoded form. Add a code comment explaining why `AssetManager` is intrinsically safe on AOSP so future maintainers understand why the defense is defense-in-depth rather than a critical gate.

**Severity:** MED | **Confidence:** High

---

### SEC-2: `catch (e: Exception)` swallows programming errors alongside expected I/O failures [LOW]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TextureLoader.kt:56-68`

**Evidence:**
```kotlin
private fun loadResource(resId: Int): Bitmap? = try {
    BitmapFactory.decodeResource(context.resources, resId)
} catch (e: Exception) {
    Log.w(TAG, "Failed to load texture resource: Resource(id=$resId)", e)
    null
}

private fun loadAsset(path: String): Bitmap? = try {
    context.assets.open(path).use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) {
    Log.w(TAG, "Failed to load texture asset: Asset(path=<redacted>)", e)
    null
}
```

**Issue:**
Both catch blocks catch `Exception`, which includes runtime exceptions such as `NullPointerException`, `ClassCastException`, and `IllegalStateException`. Catching these silently turns coding bugs into invisible failures: a programming error in a custom `TextureLoader` implementation or in a future change to the loading code will be swallowed, logged at `WARN` level, and replaced by a checkerboard — with no crash to alert the developer.

Note: the log redaction for the asset path (`Asset(path=<redacted>)`) is correctly implemented in the current code; this finding is about the catch breadth, not the redaction.

The expected failures are `IOException` (file not found, permission denied) and `OutOfMemoryError` (which is a `Throwable`, not `Exception`, so it already escapes). Narrowing the catch to `IOException` for `loadAsset` and adding `Resources.NotFoundException` for `loadResource` would preserve programmer-visible crashes for coding bugs while still treating I/O failures as graceful null returns.

**Fix:** Narrow the catch clauses:
- `loadAsset`: catch `IOException` only.
- `loadResource`: catch `Resources.NotFoundException` or `IOException`.
- If broader catching is intentional (e.g., to guard against OEM `BitmapFactory` bugs), document the rationale with a comment and consider re-throwing `Error` subclasses explicitly.

**Severity:** LOW | **Confidence:** High

---

### SEC-3: No bitmap size guard before `BitmapFactory.decodeStream` [LOW]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TextureLoader.kt:63-67`

**Evidence:**
```kotlin
private fun loadAsset(path: String): Bitmap? = try {
    context.assets.open(path).use { BitmapFactory.decodeStream(it) }
} catch (e: Exception) { ... }
```

**Issue:**
No `BitmapFactory.Options` are set. A large asset (e.g., 4096×4096 ARGB_8888 = 64 MB decoded) is allocated in full on the main thread with no back-pressure or bound-checking. This is a self-inflicted DoS risk (OOM crash) for apps that accidentally bundle large assets and reference them as tile textures.

The same issue exists in `loadResource`, but `BitmapFactory.decodeResource` applies density scaling internally, which reduces the risk somewhat.

With the public `TextureLoader` fun interface, consumers can inject a loader that delegates back to the default behavior but for arbitrarily large sources, making it difficult for the library to enforce a limit from the outside.

**Fix:** Document in `TextureLoader` KDoc and `TextureSource.Asset` KDoc that the default loader performs no size limiting and is intended for small tile textures. Optionally expose a `maxTextureDimension: Int = 2048` parameter on `TextureCacheConfig` that the default loader uses to compute `inSampleSize` via a `BitmapFactory.Options` two-pass decode.

**Severity:** LOW | **Confidence:** High

---

### SEC-4: Custom loader can pin oversized bitmaps in the LRU cache [LOW]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/TexturedCanvasDrawHook.kt:143-153`

**Evidence:**
```kotlin
private fun resolveToCache(source: TextureSource): CachedTexture {
    cache.get(source)?.let { return it }
    val bitmap = loader.load(source)
    return if (bitmap != null) {
        cache.put(source, bitmap)
    } else {
        onTextureLoadError?.invoke(source)
        cache.put(source, checkerboard)
    }
}
```

**Issue:**
`resolveToCache` unconditionally stores whatever `loader.load(source)` returns. A consumer-supplied `TextureLoader` can:

1. Return a very large bitmap regardless of the original `TextureSource` (size amplification: e.g., source is a 1×1 resource ID, loader returns a 4096×4096 bitmap).
2. Return a different bitmap on each call for the same `TextureSource` key. Combined with the LRU eviction-and-reload cycle, this can cause repeated large allocations (cache stuffing).

The threat model here is a misconfigured or malicious third-party Compose wrapper injecting a `ProvideTextureRendering` higher in the composition tree — not a direct external attacker. Still, the library has no defense against loader misbehavior.

**Fix:** Document in `ProvideTextureRendering` KDoc that the `loader` parameter is fully trusted code. Optionally enforce a maximum bitmap dimension cap inside `resolveToCache` based on `TextureCacheConfig` (see also SEC-3).

**Severity:** LOW | **Confidence:** Med

---

### SEC-5: Negative resource IDs not rejected [NIT]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/TextureSource.kt:23-27`

**Evidence:**
```kotlin
data class Resource(@DrawableRes val resId: Int) : TextureSource {
    init {
        require(resId != 0) { "Resource ID must not be zero" }
    }
}
```

**Issue:**
Android resource IDs are effectively unsigned 32-bit integers packed into a signed `Int`. Negative values do not correspond to valid drawable resources. `BitmapFactory.decodeResource` returns `null` for an invalid ID (handled gracefully), but a negative ID passes the `init` check, creates a valid `TextureSource.Resource` object, fails silently at load time, and logs a `WARN` with the negative integer. The check implies only `0` is invalid, which is misleading to library consumers.

**Fix:** Change the guard to `require(resId > 0) { "Resource ID must be positive, got $resId" }` and update the KDoc `@param` to note that valid resource IDs are always positive.

**Severity:** NIT | **Confidence:** High

---

### SEC-6: KDoc example passes `source.toString()` to analytics — leaks full asset path [NIT]

**Location:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/render/ProvideTextureRendering.kt:57-61`

**Evidence:**
```kotlin
ProvideTextureRendering(onTextureLoadError = { source ->
    analytics.logEvent("texture_load_failed", source.toString())
}) { ... }
```

**Issue:**
`TextureSource.Asset.toString()` is the default Kotlin data class `toString()`, which returns `"Asset(path=textures/user_content/...)"`. If a consumer follows the KDoc example verbatim and the asset path contains user-derived data (user IDs, session tokens embedded in bundle paths), the full path string is transmitted to an analytics backend. This is a documentation-driven misuse pattern rather than a library bug.

Note that the `DefaultTextureLoader` itself correctly redacts the path in its own `Log.w` call (`"Asset(path=<redacted>)"`). The KDoc example contradicts that intent by demonstrating unsafe usage at the callback boundary.

**Fix:** Replace the KDoc example with one that extracts only the source type without logging the full `toString()`:

```kotlin
ProvideTextureRendering(onTextureLoadError = { source ->
    val kind = when (source) {
        is TextureSource.Asset -> "asset"
        is TextureSource.Resource -> "resource"
        is TextureSource.Bitmap -> "bitmap"
    }
    analytics.logEvent("texture_load_failed", mapOf("kind" to kind))
}) { ... }
```

**Severity:** NIT | **Confidence:** High

---

## Summary

Six findings were identified across the five files reviewed: one MED, three LOW, two NIT — no BLOCKER or HIGH issues. The overall security posture of the `api-design-fixes` slice is good; the previously reported SEC-2 finding (asset path logged verbatim) has been correctly fixed — `DefaultTextureLoader` now logs `"Asset(path=<redacted>)"` rather than the raw path.

**Remaining actionable items:**

- **SEC-1 (MED):** The split-based `..` check does not block percent-encoded traversal sequences (`%2e%2e`). Since AOSP `AssetManager` is intrinsically safe (index-based, not filesystem-traversal-based), this is defense-in-depth, but adding a `'%' !in path` guard and a clarifying comment closes the gap definitively.

- **SEC-2 (LOW):** Both catch blocks in `DefaultTextureLoader` catch `Exception`, which silently swallows programming errors (NPE, CCE) alongside expected `IOException`. Narrowing to `IOException` (and `Resources.NotFoundException` for resources) is a low-effort correctness and security hardening improvement.

- **SEC-3 (LOW) / SEC-4 (LOW):** No bitmap size limit is enforced before `BitmapFactory.decodeStream`, and a custom `TextureLoader` can pin arbitrarily large bitmaps in the LRU cache. Both are best addressed with documentation and an optional `maxTextureDimension` cap in `TextureCacheConfig`.

- **SEC-5 (NIT):** The `resId != 0` guard should be `resId > 0` to reject negative IDs.

- **SEC-6 (NIT):** The `onTextureLoadError` KDoc example demonstrates passing `source.toString()` to analytics, which can leak asset paths. The example should show extracting the source type only.
