---
schema: sdlc/v1
type: review-command
slug: texture-material-shaders
slice-slug: canvas-textures
reviewer-focus: code-simplification
status: complete
stage-number: 7
created-at: "2026-04-12T00:00:00Z"
updated-at: "2026-04-12T00:00:00Z"
result: findings
metric-findings-total: 5
metric-findings-blocker: 0
metric-findings-high: 1
metric-findings-medium: 2
metric-findings-low: 2
finding-ids: [CT-CS-1, CT-CS-2, CT-CS-3, CT-CS-4, CT-CS-5]
tags: [canvas, texture, rendering, simplification, review]
refs:
  review-master: 07-review.md
  slice-def: 03-slice-canvas-textures.md
  plan: 04-plan-canvas-textures.md
  implement: 05-implement-canvas-textures.md
  verify: 06-verify-canvas-textures.md
---

# Code Simplification Review — canvas-textures slice

## Scope

Files reviewed (new and modified in this slice):

- `isometric-shader/src/main/kotlin/.../shader/render/TexturedCanvasDrawHook.kt` (new)
- `isometric-shader/src/main/kotlin/.../shader/render/TextureCache.kt` (new)
- `isometric-shader/src/main/kotlin/.../shader/render/TextureLoader.kt` (new)
- `isometric-shader/src/main/kotlin/.../shader/render/ProvideTextureRendering.kt` (new)
- `isometric-compose/src/main/kotlin/.../compose/runtime/IsometricRenderer.kt` (modified)
- `isometric-compose/src/main/kotlin/.../compose/runtime/NativeSceneRenderer.kt` (modified)

Supporting context read:

- `MaterialDrawHook.kt`, `CompositionLocals.kt`, `IsometricScene.kt`, `StrokeStyle.kt`
- `RenderExtensions.kt`, `IsometricMaterial.kt`, `TextureSource.kt`

---

## Findings Table

| ID | Severity | File | Issue |
|----|----------|------|-------|
| CT-CS-1 | HIGH | `IsometricRenderer.kt:479` | `toNativePath()` allocates a new `android.graphics.Path` per textured command per frame in the Compose `DrawScope` path, even though a pooled Compose `Path` already exists for the same command |
| CT-CS-2 | MED | `IsometricRenderer.kt:474-483` / `NativeSceneRenderer.kt:67-69` | Duplicated `command.material != null && hook != null` guard + hook dispatch pattern in two separate renderers |
| CT-CS-3 | MED | `IsometricRenderer.kt:486-494` | Collapsed `when (strokeStyle)` for the `materialHandled` branch can be simplified: `Stroke` and `FillAndStroke` arms are identical, reducing to two cases instead of three |
| CT-CS-4 | LOW | `TexturedCanvasDrawHook.kt:48-56` | `PerFace` resolution in the hook only consults `material.default`, silently ignoring `faceMap` — this is dead logic for the `faceMap` branch but draws no attention to the gap |
| CT-CS-5 | LOW | `TexturedCanvasDrawHook.kt:157-168` | White-tint threshold uses `>= 254.0` which is inconsistent with 0–255 scale semantics; a value of 254 would be visually white but still generates a redundant GPU color filter |

---

## Detailed Findings

### CT-CS-1 — Per-frame `toNativePath()` allocation in the Compose DrawScope hook path [HIGH]

**Location:** `IsometricRenderer.kt`, `renderPreparedScene()`, lines 477–483

**Evidence:**
```kotlin
val materialHandled = command.material != null
    && hook != null
    && run {
        var handled = false
        drawIntoCanvas { canvas ->
            val nativePath = command.toNativePath()   // <-- NEW allocation every frame
            handled = hook.draw(canvas.nativeCanvas, command, nativePath)
        }
        handled
    }
```

**Issue:** `command.toNativePath()` (defined in `NativeSceneRenderer.kt:114`) constructs a fresh
`android.graphics.Path` on every call — `android.graphics.Path()` then `moveTo` + N×`lineTo` +
`close()`. This fires once per textured command per frame in the Compose `DrawScope` path.

At the same time, `pathPool[i]` holds a fully filled Compose `Path` that wraps the same Skia
`SkPath`. The native counterpart of that path is the `SkPath` itself; however, Compose's
`androidx.compose.ui.graphics.Path` does not expose its underlying `android.graphics.Path`
through public API, so the pool path cannot be reused directly here.

The `NativeSceneRenderer` path (used when `useNativeCanvas = true`) does **not** suffer this
problem in the same way — it allocates `toNativePath()` too, but there it is the only path
object in play and both backends share the issue. However, the `renderPreparedScene` path is
specifically supposed to be the Compose DrawScope path that benefits from path pooling;
introducing a new native allocation per textured command per frame partially undoes the
pool's purpose and creates a GC pressure spike proportional to the number of textured faces.

**Recommendation (two options):**

**Option A — Native path pool (highest impact):** Add a parallel
`nativePathPool: ArrayList<android.graphics.Path>()` alongside `pathPool` in
`IsometricRenderer`. Before the render loop, grow it in sync with `pathPool`. Before calling
`hook.draw()`, fill `nativePathPool[i]` using a `fillNativePath(target)` extension (mirror of
`fillComposePath`):

```kotlin
internal fun RenderCommand.fillNativePath(target: android.graphics.Path) {
    target.reset()
    val pts = points
    if (pts.isEmpty()) return
    target.moveTo(pts[0].toFloat(), pts[1].toFloat())
    var i = 2
    while (i < pts.size) {
        target.lineTo(pts[i].toFloat(), pts[i + 1].toFloat())
        i += 2
    }
    target.close()
}
```

This eliminates all per-frame `android.graphics.Path` allocations in both the Compose and
native renderer paths.

**Option B — Accept the allocation, document the known cost:** If the textured-face count is
expected to remain small (e.g., < 10 faces per frame), the allocation pressure may be
acceptable. Add a `// TODO(perf): replace with pooled native path` comment so the cost is
visible, and track it for a future performance slice.

Option A is strongly preferred for consistency with the existing path-pool design rationale
(documented at `IsometricRenderer.kt:98–107`).

---

### CT-CS-2 — Duplicated `material != null && hook != null` guard and dispatch in two renderers [MED]

**Location:**
- `IsometricRenderer.kt:474–483` (`renderPreparedScene`)
- `NativeSceneRenderer.kt:67–69` (`renderNative`)

**Evidence (NativeSceneRenderer):**
```kotlin
val materialHandled = command.material != null
    && materialDrawHook != null
    && materialDrawHook.draw(nativeCanvas, command, nativePath)
```

**Evidence (IsometricRenderer):**
```kotlin
val materialHandled = command.material != null
    && hook != null
    && run {
        var handled = false
        drawIntoCanvas { canvas ->
            val nativePath = command.toNativePath()
            handled = hook.draw(canvas.nativeCanvas, command, nativePath)
        }
        handled
    }
```

**Issue:** Both renderers independently implement the same guard (`material != null && hook != null`)
and the same "try hook, fall back to flat color, still apply stroke" logic. The `NativeSceneRenderer`
version is the cleaner of the two (three-argument boolean short-circuit), while the
`IsometricRenderer` version must wrap the hook call inside `drawIntoCanvas { }` to obtain the
native canvas — resulting in a more complex lambda structure for what is conceptually the same
operation.

The duplication is not accidental — the two renderers serve different backends (Compose DrawScope
vs. Android Canvas directly) — but the guard condition and the `materialHandled` semantic are
identical and could be unified in a shared extension function:

```kotlin
// In a shared location (e.g. MaterialDrawHook.kt or a new HookDispatch.kt)
internal fun MaterialDrawHook?.tryDraw(
    nativeCanvas: android.graphics.Canvas,
    command: RenderCommand,
    nativePath: android.graphics.Path,
): Boolean = this != null && command.material != null && draw(nativeCanvas, command, nativePath)
```

Both call sites then reduce to:

```kotlin
val materialHandled = hook.tryDraw(nativeCanvas, command, nativePath)
```

This also eliminates the `run { var handled = false; ... }` awkwardness in `IsometricRenderer`.

**Recommendation:** Extract `MaterialDrawHook?.tryDraw(...)` as an internal extension. Both
renderers call the extension; the null + material guards live in exactly one place.

---

### CT-CS-3 — Redundant `when (strokeStyle)` arms in the `materialHandled` branch [MED]

**Location:** `IsometricRenderer.kt:486–494`

**Evidence:**
```kotlin
if (materialHandled) {
    // Hook drew the fill — apply stroke if needed
    when (strokeStyle) {
        is StrokeStyle.Stroke -> {
            drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
        }
        is StrokeStyle.FillAndStroke -> {
            drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
        }
        is StrokeStyle.FillOnly -> { /* no stroke */ }
    }
}
```

**Issue:** The `Stroke` and `FillAndStroke` arms are byte-for-byte identical. `StrokeStyle` is a
sealed class, so a combined arm would be both correct and exhaustive:

```kotlin
if (materialHandled) {
    when (strokeStyle) {
        is StrokeStyle.Stroke, is StrokeStyle.FillAndStroke ->
            drawPath(path, strokeComposeColor!!, style = strokeDrawStyle!!)
        is StrokeStyle.FillOnly -> { /* no stroke */ }
    }
}
```

Note: the corresponding logic in `NativeSceneRenderer.kt:73–79` already uses the combined form:

```kotlin
when (strokeStyle) {
    is StrokeStyle.Stroke, is StrokeStyle.FillAndStroke -> { ... }
    is StrokeStyle.FillOnly -> { /* no stroke */ }
}
```

The Compose path was not kept in sync with the native renderer during the fix in VF-1b
(documented in `06-verify-canvas-textures.md`).

**Recommendation:** Combine the two identical arms in `renderPreparedScene` to match the
`NativeSceneRenderer` pattern. No semantic change.

---

### CT-CS-4 — `PerFace.faceMap` is silently ignored in `TexturedCanvasDrawHook` [LOW]

**Location:** `TexturedCanvasDrawHook.kt:48–56`

**Evidence:**
```kotlin
is IsometricMaterial.PerFace -> {
    val sub = material.default
    if (sub is IsometricMaterial.Textured) {
        drawTextured(nativeCanvas, command, nativePath, sub)
    } else {
        false
    }
}
```

**Issue:** `PerFace` carries a `faceMap: Map<Int, IsometricMaterial>` whose purpose is to assign
*per-face* materials by face index. The hook unconditionally reads `material.default` and ignores
`faceMap` entirely. For shapes with explicit face-specific materials set via `perFace { face(0, ...) }`,
all faces will render with the default material instead of the per-face material.

This is a **correctness gap that masquerades as a simplification issue**. The implementation is not
overcomplicated — it is incomplete in a way that is silent (no error, no fallback warning). The
`PerFace` case draws attention to the gap only through this review.

However, the canvas-textures slice plan explicitly deferred per-face material resolution to the
`per-face-materials` slice. The concern here is that the current code structure gives false
confidence that `PerFace` is handled: the branch exists, compiles, and runs — it just ignores the
most important part of `PerFace`'s data.

**Recommendation:** Add a code comment documenting the known gap:

```kotlin
is IsometricMaterial.PerFace -> {
    // TODO(per-face-materials): faceMap lookup by face index deferred to the
    // per-face-materials slice. Currently falls back to material.default for all faces.
    val sub = material.default
    if (sub is IsometricMaterial.Textured) {
        drawTextured(nativeCanvas, command, nativePath, sub)
    } else {
        false
    }
}
```

This makes the deferral explicit to the next reader without requiring a code change in this slice.

---

### CT-CS-5 — White-tint threshold `>= 254.0` allows a near-white value to skip the null fast path [LOW]

**Location:** `TexturedCanvasDrawHook.kt:157–168`, `IsoColor.toColorFilterOrNull()`

**Evidence:**
```kotlin
internal fun IsoColor.toColorFilterOrNull(): PorterDuffColorFilter? {
    if (r >= 254.0 && g >= 254.0 && b >= 254.0) return null
    return PorterDuffColorFilter(
        android.graphics.Color.argb(
            255,
            r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255),
            b.toInt().coerceIn(0, 255),
        ),
        PorterDuff.Mode.MULTIPLY,
    )
```

**Issue:** `IsoColor.WHITE` is defined as `IsoColor(255.0, 255.0, 255.0)`, so `WHITE` correctly
returns `null`. However, the threshold is `>= 254.0` rather than `>= 255.0`, meaning any `IsoColor`
with all channels at 254 (visually indistinguishable from white at `a=255` MULTIPLY) returns `null`,
while `IsoColor(253.0, 254.0, 255.0)` (one channel below threshold) creates a `PorterDuffColorFilter`.

The intent is clearly "skip the GPU state change for pure white" but the threshold is one step
loose: a component value of 254 still produces 254 via `toInt().coerceIn(0, 255)`, which under
`MULTIPLY` mode is `254/255 ≈ 0.996`, not exactly 1.0. The GPU color filter is therefore not truly
a no-op for 254; the null short-circuit is slightly incorrect (or at least misleading).

The fix is to align the threshold with the actual no-op condition for `MULTIPLY` mode:

```kotlin
// MULTIPLY(255, x) = x, so only RGB=255,255,255 is a true no-op.
if (r.toInt() >= 255 && g.toInt() >= 255 && b.toInt() >= 255) return null
```

`toInt()` on values > 255.0 (which `IsoColor` permits) would give values > 255, and
`coerceIn(0, 255)` handles those in the non-null path. Using `>= 255` on `toInt()` is
therefore the correct semantic check.

**Recommendation:** Change threshold to `r.toInt() >= 255 && g.toInt() >= 255 && b.toInt() >= 255`.
Trivial one-line change, no behavioral impact for the common `WHITE` case.

---

## Summary

| ID | Severity | Actionable | Recommendation |
|----|----------|------------|----------------|
| CT-CS-1 | HIGH | Yes | Add `nativePathPool` + `fillNativePath` extension to eliminate per-frame `android.graphics.Path` alloc |
| CT-CS-2 | MED | Yes | Extract `MaterialDrawHook?.tryDraw(...)` extension; unify the guard in both renderers |
| CT-CS-3 | MED | Yes | Combine identical `Stroke`/`FillAndStroke` arms in `renderPreparedScene` `when` block |
| CT-CS-4 | LOW | Yes (comment) | Add TODO comment to `PerFace` branch documenting `faceMap` deferral |
| CT-CS-5 | LOW | Yes | Fix white-tint threshold to `>= 255` to match true MULTIPLY no-op condition |

**CT-CS-1** is the most impactful: it partially undoes the path-pool's GC benefit for scenes with
textured faces and is inconsistent with the design rationale already documented in the renderer.
**CT-CS-2** and **CT-CS-3** are straightforward cleanup that improves consistency between the
two render backends. **CT-CS-4** and **CT-CS-5** are minor but each catches a subtle gap in the
implementation.

No finding is a blocker. CT-CS-1 is high severity but not correctness-affecting — it is a
performance regression relative to the path-pool design intent.
