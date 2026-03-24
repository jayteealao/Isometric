# GPU Object Lifetime — Per-Frame Wrapper Close Policy

> **Scope**: `isometric-webgpu` module
> **Applies to**: All Dawn/WebGPU `AutoCloseable` wrapper objects
> **Authored**: 2026-03-23

---

## Rule

**All per-frame transient `AutoCloseable` wrappers MUST be explicitly `close()`d after use.**

A "per-frame transient" is any WebGPU object created inside a render or compute pass on a
per-frame basis — that is, objects created during `drawFrame()` or inside a compute dispatch
loop, not objects that live for the duration of a `GpuContext` or pipeline.

---

## Why

The vendored `androidx.webgpu` library wraps Dawn C++ objects in JNI handle holders. Each
wrapper holds a native reference that is **not** released by GC alone — the JVM finalizer
may never run on Android, and even when it does, the delay accumulates wrapper objects faster
than they are collected. This causes **JNI handle accumulation** that eventually triggers
`OutOfMemoryError` or Dawn internal assertion failures during long-running sessions.

The Phase 1 endurance audit confirmed: failing to close per-frame transients produces a
monotonic heap growth of ~12 KB/frame at 60 fps = ~720 KB/s. In a 5-minute run this is
~216 MB of leaked native memory.

---

## Objects That Must Be Closed Per Frame

| Object | Created by | Close after |
|--------|-----------|-------------|
| `GPUCommandEncoder` | `device.createCommandEncoder()` | After `queue.submit()` |
| `GPURenderPassEncoder` | `encoder.beginRenderPass()` | After `pass.end()` |
| `GPUComputePassEncoder` | `encoder.beginComputePass()` | After `pass.end()` |
| `GPUCommandBuffer` | `encoder.finish()` | After `queue.submit()` |
| `GPUTextureView` | `texture.createView()` | After the pass that uses it ends |
| `GPUSurfaceTexture.texture` | `surface.getCurrentTexture().texture` | After `surface.present()` |

---

## Correct Pattern (render pass example)

```kotlin
val surfaceTexture = surface.getCurrentTexture()
val textureView = surfaceTexture.texture.createView()
val encoder = device.createCommandEncoder()

val pass = encoder.beginRenderPass(...)
// ... set pipeline, draw calls ...
pass.end()
pass.close()                          // close pass encoder

val commandBuffer = encoder.finish()
queue.submit(arrayOf(commandBuffer))
commandBuffer.close()                 // close command buffer
encoder.close()                       // close command encoder
surface.present()

textureView.close()                   // close texture view
surfaceTexture.texture.close()        // close surface texture wrapper
```

## Correct Pattern (compute pass example)

```kotlin
val encoder = device.createCommandEncoder()

val computePass = encoder.beginComputePass()
computePass.setPipeline(pipeline)
computePass.setBindGroup(0, bindGroup)
computePass.dispatchWorkgroups(workgroupCount)
computePass.end()
computePass.close()                   // close compute pass encoder

val commandBuffer = encoder.finish()
queue.submit(arrayOf(commandBuffer))
commandBuffer.close()
encoder.close()
```

---

## Objects NOT Covered by This Rule

The following objects are **persistent** (live for the lifetime of a pipeline or context)
and are closed via their owning component's `close()` method, not per-frame:

- `GPUDevice` — closed in `GpuContext.destroy()`
- `GPUAdapter` — closed in `GpuContext.destroy()`
- `GPUInstance` — closed in `GpuContext.destroy()`
- `GPUSurface` — closed in `WebGpuSceneRenderer.cleanup()`
- `GPURenderPipeline` — closed in `GpuRenderPipeline.close()`
- `GPUComputePipeline` — closed in the owning pipeline class `close()`
- `GPUBuffer` (persistent, e.g. scene data buffer) — closed in the owning buffer class
- `GPUBindGroup` / `GPUBindGroupLayout` — closed in the owning pipeline class
- `GPUShaderModule` — closed after pipeline creation (not kept alive)

---

## Phase 3 Compute Passes

Each Phase 3 compute pass (`TransformCullLightPass`, `BitonicSortPass`,
`TriangulateEmitPass`) creates a `GPUComputePassEncoder` per dispatch. All pass encoders
MUST be closed after `end()` following the pattern above. The single `GPUCommandEncoder`
that wraps all passes for a frame is closed after `queue.submit()`.

---

## Verification

Heap stability is verified by the endurance benchmark (`IsometricEnduranceBenchmark`) which
runs a 5-minute animated scene and asserts that heap growth stays below 1 MB total. A
per-frame close violation will produce a visible monotonic heap slope in the benchmark
output.
