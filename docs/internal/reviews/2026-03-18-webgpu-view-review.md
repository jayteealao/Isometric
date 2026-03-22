# WebGPU Roadmap Review

Date: 2026-03-19
Target: [2026-03-18-webgpu-roadmap.md](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md)
Scope:
- WS11, WS12, WS13 plan correctness
- alignment with [api-design-guideline.md](C:/Users/jayte/Documents/dev/Isometric/docs/internal/api-design-guideline.md)
- source accuracy against vendored `androidx-webgpu`
- WebGPU lifecycle/performance guidance

## Summary
The roadmap has improved materially. Most of the earlier review findings are now fixed:

- backend selection is layered through `SceneConfig`
- the backend contract uses `StrokeStyle`
- `PreparedScene` is the Phase 2 draw input
- `gpuSurface.present()` is correct
- `GPUSurfaceTexture.status` handling is now designed
- object lifetime ownership is now partly documented
- the upload path now uses a reusable staging `ByteBuffer`
- the overlay is now mandatory for WebGPU backends
- labels/debug markers are now part of the WS12 contract

The remaining issues are much narrower. They are not broad API-shape problems anymore; they are sample-code correctness, lifecycle completeness, and one remaining test/goal inconsistency.

## Findings

### 1. High: `surfaceFormat` is used for reconfiguration but never assigned in `init()`
References:
- [2026-03-18-webgpu-roadmap.md:1137](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1137)
- [2026-03-18-webgpu-roadmap.md:1157](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1157)
- [2026-03-18-webgpu-roadmap.md:1162](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1162)

`init()` selects the surface format and uses it in the initial `gpuSurface.configure(...)`, but the sample never stores that value into `surfaceFormat`. Later, both `resize()` and `reconfigure()` use `surfaceFormat`, which therefore remains `TextureFormat.Undefined`.

This is a real correctness bug in the current plan code, not a style issue.

Required change:
- assign `surfaceFormat = format` during `init()` before any later call to `reconfigure()`

### 2. Medium: transient `AutoCloseable` GPU objects are still excluded from the ownership model without justification
References:
- [2026-03-18-webgpu-roadmap.md:1072](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1072)
- [GPUCommandEncoder.kt](C:/Users/jayte/Documents/dev/Isometric/vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUCommandEncoder.kt)
- [GPURenderPassEncoder.kt](C:/Users/jayte/Documents/dev/Isometric/vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPURenderPassEncoder.kt)
- [GPUComputePassEncoder.kt](C:/Users/jayte/Documents/dev/Isometric/vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUComputePassEncoder.kt)

The vendored API marks command encoders and pass encoders as `AutoCloseable`, and their KDocs say `close()` is the standard refcount/lifetime operation. The roadmap’s ownership table explicitly says not to call `close()` on `GPUCommandEncoder` after submit, but it does not provide a source-backed reason for that exception.

That leaves the plan in an awkward middle state:
- long-lived resources now have explicit lifetime rules
- transient encoder/pass wrappers do not

Required change:
- either add explicit close rules for transient encoders/passes/command buffers
- or justify, with source-backed reasoning, why those wrappers are intentionally exempted

### 3. Medium: `destroy()` still assumes full initialization and does not describe partial-init cleanup
References:
- [2026-03-18-webgpu-roadmap.md:1315](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1315)
- [2026-03-18-webgpu-roadmap.md:998](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L998)
- [2026-03-18-webgpu-roadmap.md:1003](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1003)

`destroy()` assumes these members are initialized:
- `uniformBuffer`
- `uniformBindGroup`
- `pipeline`
- `gpuSurface`

That is fine only if `init()` and all init-time helpers complete successfully every time. The plan does not describe what happens if:
- surface creation succeeds but pipeline creation fails
- uniform buffer creation fails after surface configure
- an exception occurs during `init()` before the frame loop starts

In those cases the current sample `destroy()` would either crash on uninitialized `lateinit` access or leak partially-created resources.

Required change:
- either guard teardown with `::field.isInitialized`
- or specify an init-time cleanup strategy that handles partial construction safely

### 4. Medium: WS12 deliverables now frame sub-5ms as a hypothesis, but the test plan still encodes it as a hard gate
References:
- [2026-03-18-webgpu-roadmap.md:1425](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1425)
- [2026-03-18-webgpu-roadmap.md:1654](C:/Users/jayte/Documents/dev/Isometric/docs/internal/plans/2026-03-18-webgpu-roadmap.md#L1654)

The roadmap correctly softened the WS12 deliverable language:
- sub-5ms is now a target hypothesis, not a guaranteed outcome

But the physical-device test plan still says:
- `WebGpuFrameBudgetTest` — `1000-face scene produces frame time < 5ms (p95) over 300 frames`

That reintroduces the same promise as an acceptance gate through a different door.

Required change:
- make the test a measurement/reporting test, or
- move the hard `< 5ms` gate to WS13 if that is where the plan really expects it

### 5. Low: the review file itself should no longer carry the old fixed findings
References:
- this file

This review is now the corrected version. The earlier review findings about:
- `SceneConfig` layering
- `StrokeStyle`
- `gpuSurface.present()`
- `textureIndex` in WS12
- missing camera-inverse mapping

are no longer live issues and should stay removed.

## What is now correct
The current roadmap is materially better on the points that previously mattered most:

1. Backend selection is layered through `SceneConfig`, not flat `IsometricScene` knobs.
2. The backend boundary uses `StrokeStyle`.
3. `PreparedScene` is the Phase 2 draw payload.
4. `GPUSurfaceTexture.status` is now part of the design contract.
5. Object lifetime ownership is now documented for the major long-lived GPU objects.
6. The upload path now reuses a staging `ByteBuffer`.
7. The overlay is now mandatory for WebGPU backends, which removes the old routing ambiguity.
8. Labels/debug markers are now part of the planned backend contract.

## Source-accuracy checks that still pass
The roadmap remains accurate on these vendored `androidx-webgpu` points:

1. surface creation must go through `GPUSurfaceDescriptor` with Android native window wrapping
2. `requestAdapter(...)` and `requestDevice(...)` are suspend and throw on failure
3. `GPUInstance.processEvents()` polling is required
4. IntDef-style enums are used instead of nested `GPU*` enum objects
5. `GPUQueue.writeBuffer(...)` takes `ByteBuffer`
6. indirect draw/dispatch entry points exist in the vendored API
7. `GPURenderPassDescriptor.colorAttachments` uses arrays, not lists

## Overall assessment
The plan is now much closer to implementation-ready.

The remaining issues are no longer about the overall direction. They are about tightening the last few places where the code examples and lifecycle rules could still mislead implementation:
- store the chosen surface format
- finish the lifetime rules for transient GPU wrappers
- make partial-init cleanup safe
- align the WS12 test gate with the softer WS12 performance claim

## Recommended next changes
1. assign `surfaceFormat = format` in `WebGpuSceneRenderer.init()`
2. finish the ownership policy for transient encoders/passes/command buffers
3. add partial-init-safe teardown rules
4. align the WS12 benchmark test with the updated “hypothesis, not guarantee” wording
