---
id: 07-review-security
workflow: texture-material-shaders
slice: material-types
date: 2026-04-11
reviewer: security-agent
result: findings
severity_summary:
  critical: 0
  high: 1
  medium: 1
  low: 1
  info: 2
---

# Security Review — material-types slice

Scope: `isometric-shader` Android library module
Files reviewed:
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterial.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/TextureSource.kt`
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`
- `isometric-shader/build.gradle.kts`
- `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/RenderCommand.kt`

---

## Findings

### SEC-1 — Path traversal in `TextureSource.Asset(path)` [HIGH]

**File:** `TextureSource.kt`, line 26–29

**Description:**
The `Asset` data class accepts an arbitrary `String` path with only a blank-check guard:
```kotlin
data class Asset(val path: String) : TextureSource {
    init {
        require(path.isNotBlank()) { "Asset path must not be blank" }
    }
}
```
A caller can supply a path such as `"../../shared_prefs/credentials.xml"` or
`"../databases/app.db"`. When a renderer passes this string to `AssetManager.open(path)`,
the Android `AssetManager` silently strips leading `../` segments and clamps to the assets
root — **but this is an implementation detail of `AssetManager`, not a documented
guarantee**. If the renderer ever uses this path for anything other than `AssetManager.open`
(e.g., caches it to disk, logs it to a remote endpoint, or a future renderer uses
`File(context.filesDir, path)`), the unsanitized value becomes a real traversal vector.

Additionally, paths with encoded separators (`%2F`) or null bytes (`\u0000`) are accepted
without rejection. Null bytes in particular can truncate paths in native layers.

**Recommendation:**
Validate the path at construction time in the `init` block:
1. Reject any path containing `..` path components, null bytes (`\u0000`), or leading `/`.
2. Optionally normalize with a canonical path check (strip redundant `.` segments).
3. Document the guarantee ("path is relative to `assets/`, no traversal permitted") so
   renderer authors can rely on it rather than on `AssetManager`'s silent clamping.

```kotlin
data class Asset(val path: String) : TextureSource {
    init {
        require(path.isNotBlank()) { "Asset path must not be blank" }
        require(!path.startsWith('/')) { "Asset path must be relative, got: $path" }
        require(!path.contains('\u0000')) { "Asset path must not contain null bytes" }
        val normalized = path.replace('\\', '/')
        require(!normalized.split('/').any { it == ".." }) {
            "Asset path must not contain '..' components, got: $path"
        }
    }
}
```

---

### SEC-2 — `@DrawableRes` annotation is a lint hint, not a runtime enforcement [MEDIUM]

**File:** `TextureSource.kt`, line 19; `IsometricMaterial.kt`, lines 84–88

**Description:**
`TextureSource.Resource(@DrawableRes val resId: Int)` and the `textured(@DrawableRes resId: Int, ...)`
DSL function both annotate with `@DrawableRes`. This annotation is checked by Android Lint and
the IDE at compile time when the caller supplies a literal like `R.drawable.foo`, but it is
**not enforced at runtime**. Any `Int` value can be passed without triggering an error:
```kotlin
// Lint warns here but the code compiles and runs:
textured(-1)
textured(0)
textured(callerSuppliedInt)  // no lint warning at all for variables
```
If a renderer calls `context.resources.getDrawable(resId)` with an invalid or
cross-app resource ID, the result is an unguarded `Resources.NotFoundException` that
crashes the process. In a worst case where the ID is attacker-controlled (e.g., passed
through a deep link or serialized state), it could be used to probe for the existence of
private resources in other packages — though on modern Android this is blocked by AAPT2
package ID namespacing.

**Recommendation:**
Add a runtime guard in `TextureSource.Resource.init` that rejects obviously invalid IDs:
```kotlin
data class Resource(@DrawableRes val resId: Int) : TextureSource {
    init {
        require(resId != 0) { "DrawableRes ID must not be 0 (Resources.ID_NULL)" }
        require(resId > 0) { "DrawableRes ID must be positive, got $resId" }
    }
}
```
This does not validate the ID against any package, but it eliminates the trivially-invalid
cases and forces an early `IllegalArgumentException` instead of a deferred
`Resources.NotFoundException` deep inside the renderer.

---

### SEC-3 — `TextureSource.BitmapSource` recycle-check is a point-in-time snapshot [LOW]

**File:** `TextureSource.kt`, lines 40–44

**Description:**
The recycled-bitmap guard is checked only once, at construction time:
```kotlin
data class BitmapSource(val bitmap: Bitmap) : TextureSource {
    init {
        require(!bitmap.isRecycled) { "Bitmap must not be recycled" }
    }
}
```
Because `BitmapSource` is a `data class` holding a direct `Bitmap` reference, there is no
ownership contract preventing the caller from calling `bitmap.recycle()` after construction
but before the renderer consumes it. A recycled bitmap passed to a GPU upload call (e.g.,
`GPUDevice.createTexture`) or a Canvas `drawBitmap` call produces a hard native crash, not
a graceful exception.

The KDoc comment ("Do not recycle the bitmap while any material referencing it is active")
is the only guidance, and it is on `TextureSource.BitmapSource`, not on the consuming
renderer.

**Recommendation:**
This is an inherent limitation of holding a mutable platform object by reference. Options
in increasing order of safety:

1. (Current, minimum) Keep the KDoc warning. Accept the crash risk in exchange for zero-copy
   performance.
2. Add a renderer-side `require(!bitmap.isRecycled)` guard immediately before any GPU/Canvas
   operation, so the crash site is at a meaningful stack frame with a readable message.
3. For long-lived materials, copy the bitmap into an immutable snapshot at construction:
   `bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)`. This sacrifices one
   allocation per material construction but eliminates the race entirely.

Option 2 is low-cost and recommended; option 3 may be worth offering as an opt-in DSL flag
(`texturedBitmap(bitmap, ownCopy = true)`).

---

### SEC-4 — No secrets or credentials in code [INFO]

No hardcoded API keys, tokens, passwords, auth headers, or sensitive configuration values
were found in any of the reviewed files. The build file (`build.gradle.kts`) contains only
version coordinates and Maven POM metadata. Clean.

---

### SEC-5 — Dependency supply chain [INFO]

All dependencies declared in `isometric-shader/build.gradle.kts` are from first-party or
well-known sources:

| Dependency | Source | Trust |
|---|---|---|
| `isometric-core`, `isometric-compose` | Local project modules | Trusted (same repo) |
| `libs.annotation` | `androidx.annotation` (Google) | Trusted |
| `libs.kotlin.stdlib` | JetBrains Kotlin stdlib | Trusted |
| `libs.compose.runtime` | Jetpack Compose (Google) | Trusted |
| `libs.junit`, `libs.kotlin.test*` | JUnit / JetBrains (test-only) | Trusted |

No third-party or unvetted transitive dependencies are introduced by this module. The module
does not pull in any networking, serialization, or reflection libraries. Clean.

---

## Summary

| ID | Area | Severity | Status |
|---|---|---|---|
| SEC-1 | Path traversal in `TextureSource.Asset` | HIGH | Needs fix |
| SEC-2 | `@DrawableRes` is lint-only, no runtime guard | MEDIUM | Needs fix |
| SEC-3 | `BitmapSource` post-construction recycle risk | LOW | Mitigate |
| SEC-4 | Secrets / credentials in code | INFO | Clean |
| SEC-5 | Dependency supply chain | INFO | Clean |

The module has a narrow attack surface as expected for a local rendering library. There are
no network calls, no serialization of user data, and no IPC. The two actionable findings
(SEC-1, SEC-2) are both in `TextureSource` constructor validation and are straightforward
to address with a few lines of `require` guards.
