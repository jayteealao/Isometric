---
schema: sdlc/v1
type: review
slug: 07-review-webgpu-textures-architecture
slice-slug: webgpu-textures
review-command: "/wf-review texture-material-shaders webgpu-textures"
status: complete
updated-at: "2026-04-12T00:00:00Z"
metric-findings-total: 8
metric-findings-blocker: 0
metric-findings-high: 2
result: conditional-pass
tags: [webgpu, texture, architecture, review]
refs:
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureBinder.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuFullPipeline.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/texture/GpuTextureStore.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/WebGpuSceneRenderer.kt
  - isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricFragmentShader.kt
  - docs/internal/api-design-guideline.md
---

# Architecture Review — webgpu-textures slice

## Scope

Architectural evaluation of the 5-file `webgpu-textures` slice (HEAD~3…HEAD, +177/-91 lines). Focus areas: component ownership, dependency direction, init-order coupling, mutable nullable state as init protocol, auto-derived vs explicit bind group layout, and whether `GpuTextureBinder` remains a useful abstraction.

---

## Findings Table

| ID | Severity | Area | Title |
|----|----------|------|-------|
| WGT-ARCH-1 | HIGH | Ownership model | `textureBindGroupLayout` has a split lifetime across two components |
| WGT-ARCH-2 | HIGH | Init protocol | Mutable nullable `bindGroupLayout` wires components via sequenced mutation |
| WGT-ARCH-3 | MEDIUM | Init order | Renderer init order is implicit and load-bearing — not documented or enforced |
| WGT-ARCH-4 | MEDIUM | Auto-derived layout | Auto-derived layout trades correctness guarantees for fewer parameters |
| WGT-ARCH-5 | MEDIUM | Abstraction boundary | `GpuTextureBinder` is a thin pass-through — its value as a standalone class is low |
| WGT-ARCH-6 | LOW | Null safety / API | `pipeline.pipeline!!` unsafe dereference after moving to nullable field |
| WGT-ARCH-7 | INFO | Dependency direction | `isometric-webgpu` ← `isometric-shader` direction is unchanged and correct |
| WGT-ARCH-8 | INFO | Fragment shader | `select`-based uniform-control-flow fix is correct; no architecture impact |

---

## Detailed Findings

### WGT-ARCH-1 — HIGH — `textureBindGroupLayout` has a split lifetime across two components

**Files:**
- `GpuRenderPipeline.kt` lines 44–46 (creates and closes the layout)
- `GpuTextureBinder.kt` line 43 (holds a reference without closing it)
- `GpuTextureBinder.kt` line 69 (explicit `// bindGroupLayout is owned by GpuRenderPipeline — don't close it here`)

`GpuRenderPipeline` creates `textureBindGroupLayout` via `rp.getBindGroupLayout(0)` and closes it in `GpuRenderPipeline.close()`. `GpuTextureBinder` holds a reference to the same object in a `var` field and explicitly skips closing it in its own `close()` method.

This is a manual ownership convention enforced only by a comment. The problem:

1. **Use-after-free window:** If `GpuRenderPipeline.close()` runs before `GpuFullPipeline` finishes using `textureBindGroup`, any `buildBindGroup(view)` call that fires during `clearScene()` (which rebuilds the bind group from the fallback view) will operate on a closed `GPUBindGroupLayout`. Dawn may or may not detect this; on Adreno it is likely a silent invalid handle.
2. **Nothing in the type system enforces the ownership rule.** A future maintainer who adds cleanup logic to `GpuTextureBinder.close()` and naively adds `bindGroupLayout?.close()` will introduce a double-free. The comment is the only guard.
3. **Close ordering in `WebGpuSceneRenderer.cleanup()`** closes `renderPipeline` before `fullPipeline` — meaning the layout object is closed while `GpuFullPipeline` (which owns `textureBinder`) is still alive. This is safe *only* because `GpuFullPipeline.close()` does not call `buildBindGroup` after the pipeline is torn down, but it is fragile.

**Recommendation (medium-term):** Either (a) retain the layout in `GpuFullPipeline`/`GpuTextureBinder` and close it there (transferring ownership explicitly), or (b) extract layout creation into `GpuFullPipeline.ensurePipelines()` and pass the pipeline to it as a parameter — so the full pipeline controls the layout's lifetime and `GpuRenderPipeline` never owns the extracted layout. Option (b) restores the pre-change ownership model without requiring explicit layout construction.

---

### WGT-ARCH-2 — HIGH — Mutable nullable `bindGroupLayout` is an init protocol smell

**Files:**
- `GpuTextureBinder.kt` line 43: `var bindGroupLayout: GPUBindGroupLayout? = null`
- `GpuTextureBinder.kt` lines 53–55: `checkNotNull(bindGroupLayout) { "bindGroupLayout not set — call after GpuRenderPipeline creation" }`
- `WebGpuSceneRenderer.kt` line 281: `gp.textureBinder.bindGroupLayout = rp.textureBindGroupLayout`

The pattern of a nullable `var` that must be set before the first method call is a classic two-phase initialization smell. Per api-guideline §6 (make invalid states hard to express), the type `GPUBindGroupLayout?` models the uninitialized state as normal; the caller discovers the mistake at runtime via `checkNotNull`, not at compile time.

Specific issues:
- `GpuTextureBinder` can be constructed and passed around in a state where `buildBindGroup` will always throw. There is no compile-time indicator.
- `GpuFullPipeline` exposes `textureBinder` as a `val` with a public mutable `bindGroupLayout` field. Any code with a reference to `GpuFullPipeline` can overwrite the layout at any time — including mid-frame.
- The accessor path `gp.textureBinder.bindGroupLayout = rp.textureBindGroupLayout` reaches two levels into internal collaborators from the renderer, violating the Law of Demeter.

**Recommendation:** Remove the mutable field. The layout should either be passed to `GpuTextureBinder`'s constructor (making it immutable after construction), or `GpuFullPipeline` should accept the layout as a parameter to `ensurePipelines(renderPipeline)` and wire it internally without exposing the setter. The simplest fix that also closes WGT-ARCH-1 is:

```kotlin
// GpuFullPipeline.kt
suspend fun ensurePipelines(renderPipeline: GpuRenderPipeline) {
    renderPipeline.ensurePipeline()
    val layout = checkNotNull(renderPipeline.textureBindGroupLayout)
    // textureBinder is private; layout is passed directly to buildBindGroup
    ...
}
```

This eliminates the public `textureBinder` exposure and the mutable field entirely.

---

### WGT-ARCH-3 — MEDIUM — Renderer init order is implicit and load-bearing

**Files:**
- `WebGpuSceneRenderer.kt` lines 276–284: the `ensureInitialized` block

The `ensureInitialized` method requires:
1. `configureSurface` (to set `surfaceFormat`)
2. `GpuRenderPipeline` created and `ensurePipeline()` called (to populate `textureBindGroupLayout`)
3. `GpuFullPipeline` created and `textureBinder.bindGroupLayout` set from step 2
4. `GpuFullPipeline.ensurePipelines()` called

Step 4 must come after step 3, and step 3 must come after step 2. This order is not documented in `ensureInitialized` with any comment, and nothing enforces it. A refactor that reorders the `withGpu` block — e.g., to lazily initialize the render pipeline — would silently break texture binding.

Additionally, `configureSurface` must precede `GpuRenderPipeline` construction because `surfaceFormat` is used as a constructor parameter. This coupling is invisible at the call site.

Per api-guideline §6, lifecycle order should be enforced, not relied upon by convention.

**Recommendation (low effort):** Add a comment block at the top of the `withGpu { }` lambda in `ensureInitialized` that spells out the required order and explains why. This is not a structural fix but makes the coupling explicit for reviewers and future maintainers. Structural fix: coalesce steps 2–4 behind a single factory function (e.g., `GpuFullPipeline.createWithRenderPipeline(ctx, surfaceFormat)`) that encapsulates the wiring.

---

### WGT-ARCH-4 — MEDIUM — Auto-derived layout: ergonomic win, reduced correctness guarantees

**Files:**
- `GpuRenderPipeline.kt` lines 122–129
- Previously used `createPipelineLayout(GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(layout)))` with an explicitly constructed layout

The shift from explicit layout construction to `getBindGroupLayout(0)` on the async pipeline removes one source of descriptor duplication (shader bindings declared twice: in WGSL and in `GPUBindGroupLayoutEntry`). The explicit approach had a correctness advantage: layout mismatch between the layout descriptor and the WGSL declaration surfaces as a Dawn validation error at layout creation time, before the pipeline is compiled. With auto-derived layout, any mismatch between what `GpuTextureBinder.buildBindGroup` creates and what the shader actually expects defers the error to draw time (when Dawn validates the bind group against the pipeline).

In practice, since `buildBindGroup` uses `getBindGroupLayout(0)` as its `layout` parameter, the auto-derived approach is actually safer: the bind group is always created with the exact layout the pipeline expects. The only risk is forgetting to call `ensurePipeline()` before creating bind groups (covered by WGT-ARCH-2's `checkNotNull`).

The async API switch (`createRenderPipelineAndAwait` instead of `createRenderPipeline`) is well-motivated by the Adreno Scudo double-free bug documented in the class comment. This is the correct fix.

**Verdict:** The auto-derived layout approach is architecturally sound for this use case. The correctness concern is lower than with the previous split ownership. No action needed beyond WGT-ARCH-2 cleanup.

---

### WGT-ARCH-5 — MEDIUM — `GpuTextureBinder` is a thin pass-through; abstraction value is low

**Files:**
- `GpuTextureBinder.kt` (entire file, 72 lines)
- `GpuFullPipeline.kt` lines 101–102, 118–125, 318, 358, 421

`GpuTextureBinder` currently contains:
1. A `GPUSampler` created at construction time.
2. A nullable `var bindGroupLayout: GPUBindGroupLayout?` (see WGT-ARCH-2).
3. `buildBindGroup(view)` — a one-call wrapper over `device.createBindGroup(...)` with two entries.

All actual bind group lifetime management (`_textureBindGroup` creation, closing, fallback rebuilding) lives in `GpuFullPipeline`. `GpuTextureBinder` does not own the bind group it creates, does not track it, and does not know when it is replaced. It is a sampler holder with a factory method.

This thin wrapper violates api-guideline §8's "focused responsibility" intent in the opposite direction — the component is so thin it adds indirection without encapsulation. The sampler could be a private field of `GpuFullPipeline`, and `buildBindGroup` could be a local private function.

Arguments *for* keeping `GpuTextureBinder` as a class:
- It centralizes sampler configuration in one place, useful if the sampler grows parameters (mipmap levels, anisotropy, etc.).
- The `AutoCloseable` boundary makes it easier to track GPU resource lifetimes in a list.

Arguments *against*:
- Its `close()` does only one thing: `sampler.close()`. This could be inlined.
- The mutable layout field (WGT-ARCH-2) only exists because `GpuTextureBinder` is constructed before the pipeline is ready.

**Recommendation:** If WGT-ARCH-2 is fixed (layout passed to constructor), `GpuTextureBinder` becomes a reasonable but minimal utility class. If the fix is instead to inline the sampler into `GpuFullPipeline`, that also works. No blocking issue, but consider whether the abstraction earns its overhead as the codebase grows.

---

### WGT-ARCH-6 — LOW — `pipeline.pipeline!!` unsafe dereference after nullability change

**Files:**
- `WebGpuSceneRenderer.kt` line 457: `pass.setPipeline(pipeline.pipeline!!)`

The previous API had `pipeline: GPURenderPipeline` (non-null, `val`). The new API has `pipeline: GPURenderPipeline? = null` (nullable, `var`) which is only non-null after `ensurePipeline()`. The `!!` dereference at the call site is protected by the fact that `ensurePipeline()` is always called before `drawFrame()` via `ensureInitialized`, but this is an invisible invariant.

Per api-guideline §7 (errors as first-class), a runtime NPE with a Kotlin `KotlinNullPointerException` is less informative than a `checkNotNull` with an actionable message. The existing pattern elsewhere in the codebase uses `checkNotNull(renderPipeline)` in `drawFrame` — the same pattern should be applied to `pipeline.pipeline`.

Additionally, `GpuRenderPipeline.pipeline` being `var` with `private set` is the right visibility choice, but the external API of `GpuRenderPipeline` now requires callers to know that `pipeline` and `textureBindGroupLayout` are only non-null post-`ensurePipeline`. Making `ensurePipeline()` return the pipeline directly (or use a sealed state) would eliminate the external null check entirely.

**Recommendation (low effort):** Replace `pipeline.pipeline!!` with `checkNotNull(pipeline.pipeline) { "GpuRenderPipeline.ensurePipeline() not yet called" }`. Consider returning the layout from `ensurePipeline()` to make the post-call non-null state explicit in the type system.

---

### WGT-ARCH-7 — INFO — Dependency direction is correct and unchanged

The `isometric-webgpu` module imports from `isometric-shader` (for `IsometricMaterial`, `TextureSource`, `TextureSource.BitmapSource`) in `GpuFullPipeline.kt`. This dependency direction (`isometric-webgpu → isometric-shader → isometric-compose → isometric-core`) is the same as before this slice and is architecturally correct. No new reverse dependencies were introduced. The webgpu module remains appropriately downstream of the shader module.

**Verdict:** No action needed.

---

### WGT-ARCH-8 — INFO — Fragment shader `select` fix is correct; no architecture impact

**Files:**
- `IsometricFragmentShader.kt` lines 17–24

Replacing the `if/return` with `select(textured, in.color, in.textureIndex == 0xFFFFFFFFu)` satisfies Dawn's uniform control flow requirement for `textureSample`. The previous `if (in.textureIndex == 0xFFFFFFFFu) { return in.color; }` was a non-uniform early return — divergent on any frame where some fragments are textured and others are not — which correctly triggered Dawn validation on some drivers. The `select` approach samples unconditionally and discards the result for untextured fragments. This is architecturally the right fix.

**Verdict:** No action needed.

---

## Component responsibility summary

| Component | Responsibility | Concern |
|---|---|---|
| `GpuRenderPipeline` | Creates + owns GPU render pipeline; extracts layout via async API | Creates and *closes* the layout — but binder holds a live reference |
| `GpuTextureBinder` | Holds sampler; creates bind groups from external layout | Thin abstraction; mutable nullable layout is an init smell |
| `GpuTextureStore` | Uploads bitmaps; manages fallback texture; owns all `GPUTexture` lifetimes | Clean. No concerns |
| `GpuFullPipeline` | Orchestrates upload, dispatch, texture state, bind group lifecycle | Correctly owns bind group lifecycle; `textureBinder.bindGroupLayout` mutation is the leak point |
| `WebGpuSceneRenderer` | Coordinates surface, render pipeline, full pipeline init | Init order is implicit; cross-component wiring (`gp.textureBinder.bindGroupLayout = ...`) reaches too deep |

---

## Summary

The webgpu-textures slice is functionally sound and the async pipeline + auto-derived layout approach is the right architectural choice for this driver environment. Two findings require attention before the next slice ships:

1. **WGT-ARCH-1 (HIGH):** `textureBindGroupLayout` is created and closed by `GpuRenderPipeline` but referenced live by `GpuTextureBinder` after `GpuRenderPipeline.close()` runs. Ownership is convention-only and fragile under close-ordering changes.
2. **WGT-ARCH-2 (HIGH):** The `var bindGroupLayout: GPUBindGroupLayout?` two-phase init pattern exposes an invalid intermediate state, reaches through two layers of abstraction from the renderer, and violates api-guideline §6. Fix by passing the layout to `GpuTextureBinder`'s constructor or absorbing the wiring into `GpuFullPipeline.ensurePipelines(renderPipeline)`.

WGT-ARCH-3 (implicit init order) and WGT-ARCH-5 (thin abstraction) are medium-priority clean-up items that can be deferred. WGT-ARCH-6 (unsafe `!!`) is a low-effort fix that should be applied in the same pass as WGT-ARCH-2.
