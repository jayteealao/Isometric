---
id: 07-review-canvas-textures-architecture
slice: canvas-textures
review_type: architecture
reviewer: claude-sonnet-4-6
date: 2026-04-12
status: complete
verdict: conditional-pass
---

# Architecture Review — canvas-textures slice

## Scope

Cross-module design evaluation of the canvas-textures slice: dependency direction, interface evolution, data class correctness, hook granularity, DrawScope bridge, and user-facing ergonomics. Evaluated against `docs/internal/api-design-guideline.md` (12 principles).

## Findings Table

| ID | Severity | Area | Title |
|----|----------|------|-------|
| CT-ARCH-1 | INFO | Dependency direction | compose has zero shader imports — direction is correct |
| CT-ARCH-2 | MEDIUM | SceneProjector interface | material/uvCoords added as optional params pollute the base contract |
| CT-ARCH-3 | HIGH | SceneGraph.SceneItem | `data class` with `FloatArray` uses reference equality — broken equals/hashCode |
| CT-ARCH-4 | LOW | Hook granularity | `MaterialDrawHook` surface is tight but couples caller to native Path construction |
| CT-ARCH-5 | MEDIUM | DrawScope bridge | `drawIntoCanvas` + per-command `toNativePath()` in the Compose path is redundant overhead |
| CT-ARCH-6 | LOW | ProvideTextureRendering | Wrapper pattern is ergonomic but has a composable-nesting pitfall |
| CT-ARCH-7 | MEDIUM | SceneProjector evolution | Adding optional fields to the interface breaks binary compatibility for external implementors |
| CT-ARCH-8 | LOW | API guidelines (§4 naming) | `MaterialData` marker name adds no domain meaning; `RenderMaterial` would be clearer |
| CT-ARCH-9 | MEDIUM | API guidelines (§6 invalid states) | `uvCoords` is required for textured rendering but typed as optional — failure deferred to runtime |
| CT-ARCH-10 | INFO | API guidelines (§9 escape hatches) | `LocalMaterialDrawHook` is a clean escape hatch; `fun interface` enables concise overrides |

---

## Detailed Findings

### CT-ARCH-1 — INFO — Dependency direction is correct

**Files:** `isometric-compose/…/MaterialDrawHook.kt`, `isometric-shader/…/TexturedCanvasDrawHook.kt`

`isometric-compose` imports only `RenderCommand` from `isometric-core`; it has no imports from `isometric-shader`. The `MaterialDrawHook` interface lives in `isometric-compose`, and `TexturedCanvasDrawHook` (the implementation) lives in `isometric-shader` which depends on `isometric-compose` via `api(project(":isometric-compose"))`. The dependency arrow flows `isometric-shader → isometric-compose → isometric-core`, which is the correct direction. This design satisfies api-guideline §8 (composition over god objects) and §2 (progressive disclosure) by keeping the interface in the right layer.

**Verdict:** No action needed.

---

### CT-ARCH-2 — MEDIUM — SceneProjector interface polluted with material/uvCoords

**Files:** `isometric-core/…/SceneProjector.kt` lines 32–40, `isometric-core/…/IsometricEngine.kt` lines 177–185

The `SceneProjector.add(path, …)` signature now carries `material: MaterialData?` and `uvCoords: FloatArray?`. These are material-system concerns and are absent from the simpler `add(shape, color)` overload. Any existing external implementation of `SceneProjector` (e.g., test fakes) must now accept these parameters. While the defaults make this source-compatible, it changes the binary interface.

More fundamentally, the existence of two optional fields on the core interface that are only relevant when a downstream shader module is present violates §8 (composition) and §2 (progressive disclosure): the base projection abstraction should know nothing about materials. A better design would be a parallel `MaterialAwareSceneProjector` interface (or a separate `add(…, meta: FaceMetadata)` overload where `FaceMetadata` is an open/extensible value object), so the core interface stays focused.

**Recommendation (medium-term):** Extract `material` and `uvCoords` into a `FaceMetadata` value object or a separate `addWithMaterial()` method on a sub-interface. For now the optional defaults are tolerable, but note this as technical debt before the API is published.

---

### CT-ARCH-3 — HIGH — SceneGraph.SceneItem data class with FloatArray breaks equals/hashCode

**Files:** `isometric-core/…/SceneGraph.kt` lines 8–16

`SceneItem` is a `data class` with a `uvCoords: FloatArray?` field. Kotlin `data class` generates `equals()` using `==` on each field; for arrays, `==` is reference equality, not content equality. This means two `SceneItem` instances with identical UV coordinates but different array instances compare as not equal. The same applies to `hashCode()` — the generated code calls `uvCoords.hashCode()`, which returns the identity hash.

**Practical impact:** `SceneItem` is `internal` and the class is not currently used in any `Set`, `Map`, or cache key, so the equality bug has no immediate runtime consequence. However, the discrepancy between the visual meaning of equality and the actual behavior is a latent bug: if any future cache or deduplication logic uses `SceneItem` as a key the results will be wrong.

`RenderCommand` (the public class) correctly uses `uvCoords.contentEqualsNullable()` and `contentHashCode()` in hand-written `equals`/`hashCode` — but `SceneItem` does not.

**Recommendation:** Either (a) add a hand-written `equals`/`hashCode` to `SceneItem` using `contentEquals`/`contentHashCode`, or (b) change `uvCoords` to `List<Float>?` (which gets structural equality for free from the data class), or (c) make `SceneItem` a plain `class` with no generated equals. Option (a) is lowest friction and most consistent with `RenderCommand`.

---

### CT-ARCH-4 — LOW — MaterialDrawHook: path construction responsibility is unclear

**Files:** `isometric-compose/…/MaterialDrawHook.kt` lines 21–35, `isometric-compose/…/NativeSceneRenderer.kt` lines 63–69, `isometric-compose/…/IsometricRenderer.kt` lines 476–483

`MaterialDrawHook.draw()` receives an `android.graphics.Path` pre-built by the caller. This is an honest performance optimisation: the path is needed for stroke (applied after the fill) so building it once and passing it in avoids a second construction. The contract is documented in the KDoc.

However, the hook signature now forces the caller to always materialise the native path before calling the hook, even if the hook returns `false` (no material). In `NativeSceneRenderer` every command gets `command.toNativePath()` at line 64 before the `material != null` guard — so path allocation is paid for non-textured commands whenever the hook is set. The guard `command.material != null` should be checked before calling `toNativePath()`, which it is in the native renderer (`&& materialDrawHook != null && materialDrawHook.draw(...)` only runs when `command.material != null`), but the `NativeSceneRenderer` unconditionally creates the native path at line 64 before any guard.

This is a minor issue — paths are small and the fast path (null material) returns quickly — but the design doc claim of "zero per-frame allocation in the draw loop" is undermined for scenes where only some faces are textured.

**Recommendation (low priority):** Hoist the `command.material != null` guard before `toNativePath()` in `NativeSceneRenderer` so the path is only allocated when the material hook will actually be invoked.

---

### CT-ARCH-5 — MEDIUM — DrawScope path: drawIntoCanvas bridge is an architectural workaround

**Files:** `isometric-compose/…/IsometricRenderer.kt` lines 465–483

In `renderPreparedScene` (the non-native Compose path), when a `MaterialDrawHook` is present the code:
1. Builds a Compose `Path` via `fillComposePath(path)` (for stroke).
2. Opens `drawIntoCanvas` to reach the native canvas.
3. Calls `command.toNativePath()` — a second path allocation — to produce a native `android.graphics.Path`.
4. Calls the hook with the native path.

Two full path objects are created per textured command on every frame: one Compose Path and one native Path. This is in the code path that exists precisely to avoid native canvas, and the `drawIntoCanvas` bridge exists only because `MaterialDrawHook` takes a native canvas/path.

The root cause is that `MaterialDrawHook` is typed to `android.graphics.Canvas` and `android.graphics.Path`, so it cannot be used from the Compose DrawScope without a bridge. This design decision was intentional (the shader module uses Android APIs), but it means the Compose-only path now has Android-native allocation. The comment in `IsometricRenderer` accurately describes this as a bridge but does not acknowledge the double-path cost.

This is acceptable for now because textured rendering almost always uses `useNativeCanvas = true`, but it is architecturally unsound: the Compose path should either not support the hook at all (users with textures must use `useNativeCanvas = true`), or `MaterialDrawHook` should be split into a Compose-level variant that takes a `DrawScope`/Compose `Path` and a native-level variant.

**Recommendation:** Document in `MaterialDrawHook.kt` that using the hook from the Compose (non-native) draw path incurs one extra native path allocation per textured command per frame due to the `drawIntoCanvas` bridge, and recommend `useNativeCanvas = true` whenever textures are used. Longer term, consider a `ComposeDrawHook` parallel interface if cross-platform support is required.

---

### CT-ARCH-6 — LOW — ProvideTextureRendering wrapper: ergonomic but has a pitfall

**Files:** `isometric-shader/…/ProvideTextureRendering.kt`

The pattern is clean and idiomatic — a composable wrapper that sets a `CompositionLocal` is the standard Compose approach. The `maxCacheSize` parameter is the right knob. The `remember(context, maxCacheSize)` key is correct.

One subtle pitfall: `ProvideTextureRendering` must be placed **outside** `IsometricScene`, not inside the content lambda. The KDoc example shows the correct order, but nothing in the type system or runtime enforces this. If a user nests `ProvideTextureRendering` inside `IsometricScene`'s `content` lambda, the `LocalMaterialDrawHook` will never be read by `IsometricScene`'s `DisposableEffect` at line 171 (which runs at scene composition level, not inside the sub-composition). This is a latent misuse risk — the scene would silently fall back to flat-color rendering without error.

Per api-guideline §6 (make invalid states hard to express), this is a design smell. A possible mitigation is a runtime assertion inside `IsometricScene` that checks whether `LocalMaterialDrawHook.current` is non-null if any `RenderCommand` in the first frame carries a material, and logs a developer warning.

**Recommendation:** Add a developer-mode assertion or `Log.w` inside `IsometricScene`'s `DisposableEffect` (or `SideEffect`) that warns if materials appear in the scene but `LocalMaterialDrawHook.current` is null. This surfaces the "wrong nesting order" mistake on first run.

---

### CT-ARCH-7 — MEDIUM — SceneProjector interface evolution breaks external implementors

**Files:** `isometric-core/…/SceneProjector.kt` lines 27–40

`SceneProjector` is a public interface. Adding a new required method (or a new overload of an existing method that supersedes the old one) is a breaking change for any external `SceneProjector` implementation. The current change adds `material` and `uvCoords` to the existing `add(path, …)` overload as optional parameters with defaults.

In Kotlin, adding a default parameter to an interface method does NOT add a default to the compiled interface ABI — the interface method binary signature changes, and implementing classes that previously compiled against the 5-parameter version will no longer satisfy the 7-parameter interface without recompilation. This is safe for internal fakes and same-project implementations, but breaks binary compatibility for any downstream library that ships a `SceneProjector` implementation.

Per api-guideline §11 (design for evolution), this is a concern if `SceneProjector` is used as an extension point by downstream projects.

**Recommendation:** If `SceneProjector` is intended as a stable extension point, document it with `@Stable` or note its binary compatibility guarantees. Consider version-gating the new method via a new `MaterialAwareSceneProjector` sub-interface with a default bridge implementation, so existing implementors are not broken.

---

### CT-ARCH-8 — LOW — Naming: `MaterialData` adds no domain meaning

**Files:** `isometric-core/…/MaterialData.kt`

The `MaterialData` interface name follows the "avoid throwaway words like `data`" violation listed in api-guideline §4. It is a marker interface; the name does not communicate intent. `RenderMaterial`, `FaceMaterial`, or simply `Material` (scoped to the package) would read more clearly at call sites: `command.material: FaceMaterial?` conveys intent better than `command.material: MaterialData?`.

The `data` suffix is an anti-pattern per §4 ("Avoid throwaway words like `data`, `payload`, `responseObject`…").

**Recommendation:** Rename to `FaceMaterial` or `RenderMaterial` in a future API bump (non-breaking if done in the same release as other changes).

---

### CT-ARCH-9 — MEDIUM — Invalid state: uvCoords required for textured rendering but typed optional

**Files:** `isometric-core/…/SceneProjector.kt` line 39, `isometric-shader/…/TexturedCanvasDrawHook.kt` lines 65–66

The `TexturedCanvasDrawHook.drawTextured()` method silently returns `false` (falls back to flat color) when `uvCoords == null || uvCoords.size < 6`. This means a command with `IsometricMaterial.Textured` but no `uvCoords` will appear as flat-colored without any error or warning to the developer.

Per api-guideline §6 (make invalid states hard to express) and §7 (design errors as first-class), this is a misuse trap. A developer who creates a textured shape but forgets `uvCoords` gets silent wrong behavior, not a compile error or even a runtime warning.

**Recommendation:** In `drawTextured()`, log a developer warning (e.g., `Log.w`) when `uvCoords` is null for a `Textured` material. Longer term, make `uvCoords` required when constructing a textured `RenderCommand` (possibly via a sealed construction helper on `IsometricMaterial.Textured` that requires UV data at construction time).

---

### CT-ARCH-10 — INFO — Escape hatches are clean

**Files:** `isometric-compose/…/MaterialDrawHook.kt`, `isometric-shader/…/ProvideTextureRendering.kt`

`LocalMaterialDrawHook` allows any downstream module to provide its own draw strategy without modifying `isometric-compose` or `isometric-shader`. The `fun interface` declaration makes inline lambdas straightforward. `ProvideTextureRendering` wraps the canonical implementation with a reasonable default cache size, and `maxCacheSize` is exposed for power users. Per api-guideline §9 (escape hatches), the design is well-structured: users can bypass `ProvideTextureRendering` entirely and provide a custom `MaterialDrawHook` via `CompositionLocalProvider(LocalMaterialDrawHook provides myHook)`.

**Verdict:** No action needed.

---

## Summary

The dependency direction is clean (compose does not import shader). The two most actionable findings before this slice ships:

1. **CT-ARCH-3 (HIGH):** Fix `SceneItem.equals`/`hashCode` for the `uvCoords` array — a silent correctness bug even if not yet triggered.
2. **CT-ARCH-5 (MEDIUM):** Document or constrain the Compose-path material hook to avoid the double-path allocation surprise; recommend `useNativeCanvas = true` for textured scenes.
3. **CT-ARCH-9 (MEDIUM):** Add a developer warning when a `Textured` material command has null `uvCoords` to surface the silent fallback.

The remaining findings (CT-ARCH-2, CT-ARCH-7) are medium-term design debt that can be deferred until the API stabilises.
