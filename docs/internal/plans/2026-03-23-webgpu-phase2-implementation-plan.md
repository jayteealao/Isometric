# WebGPU Phase 2 Implementation Plan

> Status: implementation plan
> Date: 2026-03-23
> Scope: `RenderBackend.WebGpu` in `isometric-webgpu`
> Library pin: `androidx.webgpu:webgpu:1.0.0-alpha04`
> Ground truth: current repo source, vendored `vendor/androidx-webgpu/`, and current Android official docs

---

## 1. Goal

Ship a real Phase 2 WebGPU render backend for `IsometricScene` that:

- keeps the public Compose API unchanged
- uses the already-shipped `PreparedScene` as the draw payload
- reuses the existing `GpuContext` lifecycle/threading model instead of introducing a second WebGPU setup path
- avoids the per-frame allocation and JNI-wrapper leaks already observed in Phase 1
- remains optional and non-breaking beside `RenderBackend.Canvas`

Phase 2 is deliberately not the full GPU-driven pipeline. CPU projection, culling, shading, and `PreparedScene` generation remain on the CPU. WebGPU only owns the draw surface and rasterization path.

---

## 2. Current Baseline In This Repo

What already exists on trunk:

- `SceneConfig.renderBackend` and `RenderBackend` live in `isometric-compose`
- `PreparedScene` is the backend boundary, not the mutable node tree
- `ComputeBackend.WebGpu` and `GpuContext` already solve adapter/device setup for Phase 1
- async prepare already exists in `IsometricScene` for GPU compute paths
- the Phase 1 memory report identified concrete JNI and allocation hazards we must not repeat

What does not exist yet:

- `RenderBackend.WebGpu`
- `WebGpuSceneRenderer`
- a WebGPU vertex upload path for `PreparedScene`
- a WebGPU render pipeline for filled isometric polygons
- a surface-backed pointer bridge for the WebGPU backend

Implication: Phase 2 should build on the current backend contract, not reopen API-shape questions already solved in trunk.

---

## 3. Source-Of-Truth Guidance

Use these in order:

1. Current repo source
2. Vendored `vendor/androidx-webgpu/`
3. Official Android docs and release notes
4. Community examples only for non-authoritative intuition

Tight vendor findings that materially affect implementation:

- `GPU.createInstance()` is the entry point, not a `GPUInstance()` constructor.
- `GPUInstance.requestAdapter(...)` and `GPUAdapter.requestDevice(...)` are suspend APIs and throw on failure.
- `GPUInstance.processEvents()` must be polled for async callbacks to fire.
- `GPUSurface.getCurrentTexture()` returns `GPUSurfaceTexture`, and its `status` must be checked.
- `GPUSurface.configure(...)`, `present()`, and `unconfigure()` are the surface lifecycle hooks.
- `GPUDevice`, `GPUCommandEncoder`, `GPURenderPassEncoder`, `GPUBuffer`, `GPUTexture`, and peers are `AutoCloseable`.
- alpha04 added Builder classes and removed some earlier constructor overload assumptions.

Repo-specific findings that override naive sample code:

- all Dawn/WebGPU calls must stay on one dedicated GPU thread
- transient JNI wrappers should be explicitly closed in hot paths when possible
- per-frame object churn is dangerous even when allocations look "small"
- reusable `ByteBuffer` staging is preferred over repeated `mapAsync`/fresh callback allocation patterns

---

## 4. Official Research Takeaways

From Android’s current WebGPU docs and release notes:

- WebGPU on Android currently targets `minSdk 24`
- Vulkan 1.1+ devices are the preferred backend
- the official Compose embedding pattern is `AndroidExternalSurface`
- the official sample runs rendering work off the main thread inside `onSurface`
- the docs explicitly call out `device.pushErrorScope()` / `popErrorScope()` for validation
- `1.0.0-alpha04` is the latest published release as of February 11, 2026, and introduced Builder APIs plus constructor churn

Important interpretation:

- the official sample is directionally useful, but too loose for this repo
- it does not address our single-thread Dawn confinement requirement
- it does not address our JNI wrapper accumulation findings
- it hardcodes `TextureFormat.RGBA8Unorm`, which is acceptable for a tutorial but not a robust backend

Second-pass correction from vendored helper source:

- `androidx.webgpu.helper.createWebGpu(...)` is still useful as a reference, but its own `close()` path currently leaves `device.close()` commented out with upstream TODO `b/428866400`

Implication:

- helper-layer lifecycle behavior should not be treated as authoritative proof that `device.close()` is currently the correct shutdown path for this repo
- continue to prefer the repo's explicit `device.destroy()` discipline unless implementation testing proves a better alternative

---

## 4.1 Second-Pass Findings And Research Gaps

### Gap 1: `GpuContext` reuse is not plug-and-play

The plan says both:

- reuse `GpuContext`
- request the adapter with `compatibleSurface = gpuSurface`

The current `GpuContext.create()` does not accept a surface or adapter options, so it cannot satisfy that requirement as written.

Required correction:

- Phase 2 needs a small `GpuContext` refactor before WS12 implementation starts

Recommended direction:

- add `GpuContext.create(requestAdapterOptions: GPURequestAdapterOptions? = null, deviceDescriptor: GPUDeviceDescriptor? = null)`
- or add a dedicated `GpuContext.createForSurface(gpuSurface: GPUSurface)`

### Gap 2: surface capability handling is broader than format choice

`GPUSurfaceCapabilities` exposes:

- `usages`
- `formats`
- `presentModes`
- `alphaModes`

Required correction:

- Phase 2 surface configuration should validate usage, present mode, and alpha mode, not just texture format

### Gap 3: runtime fallback is policy, not mechanism

The plan recommends preserving Canvas on failure, but the current backend contract does not automatically swap `RenderBackend.WebGpu` back to Canvas during the same composition.

Required correction:

- either define an explicit in-backend fallback mechanism
- or state plainly that failures are surfaced diagnostically and callers must opt back into Canvas themselves

### Gap 4: continuous frame pacing still needs validation

The official `AndroidExternalSurface` sample renders once in `onSurface`. It does not define a long-running animation loop strategy.

Research still needed:

- best continuous-render pacing model for `AndroidExternalSurface`
- whether redraw should be event-driven, loop-driven, or hybrid

### Gap 5: transient wrapper closure still needs one empirical answer

The vendored source clearly marks `GPUTexture` and `GPUTextureView` as `AutoCloseable`, but the official sample does not say whether the surface-acquired texture and created view should be explicitly closed every frame.

Research still needed:

- verify correct close timing for `GPUSurfaceTexture.texture` and its `GPUTextureView` on physical devices

---

## 5. Recommended Phase 2 Architecture

### 5.1 Backend boundary

Keep the existing boundary:

- `IsometricScene` owns composition, `PreparedScene`, dirty tracking, and hit testing
- `RenderBackend.WebGpu` receives `State<PreparedScene?>`, `RenderContext`, `Modifier`, `StrokeStyle`
- the WebGPU renderer never touches `GroupNode` or composition internals

### 5.2 Lifecycle model

Use `AndroidExternalSurface` for the actual display surface, but do not use the official `createWebGpu(surface)` helper as the main abstraction.

Recommendation:

- keep `GpuContext` as the only owner of `GPUInstance`, `GPUAdapter`, `GPUDevice`, `GPUQueue`, and event polling
- create/configure the `GPUSurface` separately from the `Surface` received by `AndroidExternalSurface`
- use `androidx.webgpu.helper.Util.windowFromSurface(surface)` and `GPUSurfaceSourceAndroidNativeWindow`
- refactor `GpuContext` so Phase 2 can provide `compatibleSurface` during adapter selection

Rationale:

- Phase 1 already solved real crashes by constraining all Dawn calls to one dispatcher
- splitting Phase 2 onto a second helper-managed path would duplicate lifecycle logic and reintroduce race risk

### 5.3 Rendering model

Phase 2 should render one packed vertex buffer per prepared scene:

- CPU side: triangulate `PreparedScene.commands` into a flat float buffer
- GPU side: upload or rewrite the vertex buffer only when the prepared scene changes
- per frame: update the viewport uniform only when width/height changes, then draw

Do not rebuild GPU objects every frame.

Long-lived:

- `GPUSurface`
- chosen surface format
- shader modules
- bind group layout
- pipeline layout
- render pipeline
- uniform buffer
- uniform bind group
- vertex buffer capacity cache
- reusable CPU staging `ByteBuffer`

Per-scene-change:

- rewrite packed vertex data
- grow vertex buffer only when capacity is insufficient

Per-frame:

- acquire current surface texture
- create command encoder
- begin render pass
- bind pipeline, bind group, vertex buffer
- draw
- end pass
- finish encoder
- submit
- present
- close transient wrappers

---

## 6. Detailed Implementation Plan

## 6.1 Files To Add

Add:

- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/RenderBackendWebGpu.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/WebGpuSceneRenderer.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/RenderBackendExtensions.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/triangulation/RenderCommandTriangulator.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuVertexBuffer.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/pipeline/GpuRenderPipeline.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricVertexShader.kt`
- `isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/shader/IsometricFragmentShader.kt`

Modify:

- `isometric-webgpu/build.gradle.kts`
- `app/src/main/kotlin/.../WebGpuSampleActivity.kt`
- tests in `isometric-webgpu/src/androidTest`

## 6.2 Milestone 1: Backend surface wrapper

Implement `RenderBackendWebGpu.Surface(...)` as a thin adapter that:

- renders `AndroidExternalSurface`
- remembers a `WebGpuSceneRenderer`
- forwards `preparedScene`, `renderContext`, and `modifier`
- ignores `strokeStyle` in Phase 2 with explicit KDoc

Do not place GPU logic directly in the composable.

## 6.3 Milestone 2: Surface lifecycle and thread ownership

`WebGpuSceneRenderer` should:

- lazily create or reuse `GpuContext`
- create a `GPUSurface` from the Android `Surface`
- request an adapter with `compatibleSurface = gpuSurface`
- choose the surface format from `gpuSurface.getCapabilities(adapter).formats`
- configure the surface with width/height/format/present mode
- own a render loop coroutine scoped to `AndroidExternalSurface.onSurface`

Rules:

- all WebGPU API calls run inside `ctx.withGpu { ... }`
- no WebGPU calls from Compose recomposition or pointer-input coroutines
- `finally` blocks must unconfigure and close what was initialized
- teardown must be partial-init safe

Do not start implementation until `GpuContext` creation supports the surface-compatible adapter request this milestone assumes.

## 6.4 Milestone 3: Robust format and status handling

Do not hardcode `RGBA8Unorm` without capability inspection.

Recommended selection logic:

1. read `GPUSurfaceCapabilities`
2. prefer `TextureFormat.BGRA8Unorm` if offered
3. else prefer `TextureFormat.RGBA8Unorm`
4. else use the first supported format and log it

Also validate:

- `TextureUsage.RenderAttachment` is allowed by `capabilities.usages`
- `PresentMode.Fifo` if available, otherwise the first supported present mode
- `CompositeAlphaMode.Auto` if available, otherwise the first supported alpha mode

Handle `GPUSurfaceTexture.status`:

- `SuccessOptimal`: render normally
- `SuccessSuboptimal`: render, then schedule reconfigure
- `Outdated`: skip draw and reconfigure
- `Lost`: recreate surface and reconfigure
- `Timeout`: skip frame without failing backend
- `Error`: record failure and fall back or surface an error

This is mandatory. The vendored API and review notes make it clear that ignoring status is a correctness bug.

## 6.5 Milestone 4: Triangulation and packing

Implement `RenderCommandTriangulator` against current `RenderCommand.points: DoubleArray`.

Output format:

- `position.xy`: 2 floats
- `color.rgba`: 4 floats
- `uv.xy`: 2 floats reserved as zeroes for Phase 3

Stride:

- 8 floats per vertex
- 32 bytes per vertex

Packing rules:

- triangle fan per convex polygon
- skip commands with fewer than 3 points
- write directly into a reusable direct `ByteBuffer`
- expose `vertexCount`

Do not allocate intermediate per-command `FloatArray`s if avoidable.

## 6.6 Milestone 5: Vertex buffer cache

`GpuVertexBuffer` should own:

- current GPU buffer
- current capacity in vertices
- reusable CPU staging buffer

Behavior:

- if packed vertex count <= capacity, reuse the existing GPU buffer
- if not, grow geometrically rather than exactly
- upload via `queue.writeBuffer(...)`
- avoid `mapAsync` for the normal upload path

This mirrors the Phase 1 lesson: stable reusable objects are more important than elegant but chatty resource creation.

## 6.7 Milestone 6: Render pipeline creation

`GpuRenderPipeline` should create once per surface format:

- shader modules
- bind group layout
- uniform buffer and bind group
- pipeline layout
- render pipeline

Vertex shader responsibilities:

- convert pixel-space `PreparedScene` coordinates into NDC
- flip Y because engine space is screen-down and WebGPU clip space is Y-up
- pass through color and reserved UV

Fragment shader responsibilities:

- return interpolated color only

Recommended extras:

- labels on pipeline, buffers, bind groups, and shader modules
- debug markers around frame encode in debug builds
- optional error scopes around pipeline creation and frame submission while bring-up is active

## 6.8 Milestone 7: Frame loop and invalidation

Drive rendering from a loop that reacts to:

- surface creation/destruction
- size changes
- `PreparedScene` version changes

Recommended behavior:

- cache the last packed scene identity or version
- only repack/reupload when the `PreparedScene` instance changes
- continue drawing each frame for animated camera/resize cases only if the payload or viewport changed

Practical recommendation:

- start with "draw on every surface frame while active"
- once correctness is proven, optimize to event-driven redraw if AndroidExternalSurface behavior permits

Correctness first, then frame throttling.

## 6.9 Milestone 8: Pointer and hit-test bridge

Keep hit testing in `IsometricScene`.

Implementation direction:

- place a transparent Compose overlay above the external surface
- route taps/long-press/drag through existing gesture code
- convert pointer coordinates using the same viewport/camera transform assumptions as Canvas

Do not push gesture logic into the WebGPU renderer.

## 6.10 Milestone 9: Sample integration

Update the sample app to expose two independent toggles:

- compute backend: CPU vs WebGPU sort
- render backend: Canvas vs WebGPU draw

This prevents conflating Phase 1 and Phase 2 regressions.

---

## 7. Known Issues, Risks, And Recommended Solutions

## 7.1 Single-thread Dawn confinement

Risk:

- driver instability or SIGSEGV if `processEvents()` and rendering run on different threads

Solution:

- reuse `GpuContext`
- force all `androidx.webgpu` calls through `withGpu`

## 7.2 Transient JNI wrapper accumulation

Risk:

- command encoders, pass encoders, command buffers, and possibly texture views accumulate until GC

Solution:

- explicitly `close()` transient `AutoCloseable` wrappers after use
- validate with repeated frame capture and heap observation

Open verification item:

- confirm whether acquired `surfaceTexture.texture` wrappers and their created views should be explicitly closed each frame in this backend; vendor types are `AutoCloseable`, but the official sample is silent
- confirm whether close timing for command buffers, texture views, and presented textures has any device-specific interaction with `present()`

## 7.3 Surface status churn

Risk:

- `getCurrentTexture()` may return `Outdated`, `Lost`, `Timeout`, or `SuccessSuboptimal`

Solution:

- treat status handling as a first-class state machine, not a TODO

## 7.4 Partial initialization leaks

Risk:

- pipeline creation or surface configure can fail after some objects were created

Solution:

- every owned field must be nullable or guarded by initialization checks
- `cleanup()` must be safe after any failed milestone

## 7.5 Hardcoded format assumptions

Risk:

- unsupported swapchain format on some devices

Solution:

- select from `GPUSurfaceCapabilities.formats`
- select present mode and alpha mode from `GPUSurfaceCapabilities`
- persist the selected format for later reconfigure

## 7.6 Reintroducing per-frame allocations

Risk:

- Phase 2 can regress into the same OOM pattern already observed in the memory report

Solution:

- reusable `ByteBuffer`
- reusable vertex buffer
- no per-frame callbacks or anonymous objects in hot render code
- no fresh shader/pipeline/bind-group creation per frame

## 7.7 Performance goal confusion

Risk:

- treating "<5ms p95 at 1000 faces" as a guaranteed Phase 2 gate

Solution:

- for Phase 2, measure and report
- keep hard budget gating for WS13 if needed

Phase 2 should ship correctness and a material draw-path improvement, not promise full-GPU-pipeline numbers.

---

## 8. Recommendations

### 8.1 Main recommendation

Implement Phase 2 as a thin, disciplined raster backend on top of current `PreparedScene`.

Do not:

- redesign `SceneConfig`
- redesign `PreparedScene`
- couple the renderer to the mutable scene tree
- mix official helper lifecycle code with repo-specific raw lifecycle code

### 8.2 Debug recommendation

During bring-up, enable:

- object labels
- debug markers
- uncaptured error callback logging
- error scopes around pipeline creation and first-frame render

Reduce this only after stable device runs.

### 8.3 Rollout recommendation

Roll out in this order:

1. Single static triangle sanity sample
2. Static `PreparedScene` render
3. Resizing and orientation handling
4. Pointer overlay wiring
5. Animated sample scenes
6. Benchmark and endurance runs

### 8.4 Fallback recommendation

Any fatal WebGPU init or surface failure should:

- log the backend failure
- preserve the ability for callers to use Canvas

Avoid backend crashes that take down the whole scene when Canvas remains viable.

---

## 9. Testing Plan

## 9.1 Unit tests

Add JVM tests for:

- triangulation counts
- packed vertex layout
- color conversion
- buffer growth behavior

## 9.2 Instrumented tests

Add Android tests for:

- surface initialization and cleanup
- format selection from capabilities
- `GPUSurfaceTexture.status` handling branches
- resize/reconfigure path
- render parity against Canvas for a fixed scene

## 9.3 Endurance tests

Must run on physical devices:

- 2+ minute animated scene without OOM
- repeated activity pause/resume
- rotation or window resize loops
- repeated backend toggle stress

## 9.4 Metrics to capture

Capture:

- frame time
- upload time
- vertex count
- number of surface reconfigures
- heap growth over time
- count of backend failures/status transitions

---

## 10. Web Search And Research Best Practices

Use this research workflow for any future `androidx.webgpu` change:

1. Check the AndroidX release page first for the latest exact version and API churn.
2. Check Android official "Getting started with WebGPU" docs for lifecycle guidance.
3. Check vendored source for exact signatures, IntDef values, Builder availability, and `AutoCloseable` ownership.
4. Check this repo for prior incident reports before copying sample code.
5. Only then consult community posts, Dawn issues, or samples.

When evaluating a snippet:

- reject any snippet that constructs APIs not present in the vendored source
- reject any snippet that ignores `processEvents()` if it uses async callbacks
- reject any snippet that recreates pipelines or buffers every frame
- reject any snippet that hardcodes format/present assumptions without `getCapabilities()`
- reject any snippet that mixes threads for device access
- reject any snippet that treats helper-layer cleanup behavior as authoritative without checking vendored helper source

Primary-source queries that should be repeated before implementation:

- latest `androidx.webgpu` release notes
- official Android WebGPU getting-started page
- vendored `GPUDevice`, `GPUInstance`, `GPUSurface`, `GPUQueue`, `GPUBuffer`, `GPURenderPassEncoder`, `GPUSurfaceConfiguration`

Targeted pre-implementation research checklist:

1. Confirm the required `GpuContext` refactor shape for `compatibleSurface`.
2. Validate whether `GPUSurfaceTexture.texture.close()` and `GPUTextureView.close()` should run every frame.
3. Validate the safest continuous-render pacing model for `AndroidExternalSurface`.
4. Validate supported `presentModes` and `alphaModes` on the target device matrix.
5. Re-check whether `device.destroy()` should remain the preferred shutdown path while upstream helper code still avoids `device.close()`.

---

## 11. Acceptance Criteria

Phase 2 is complete when:

- `RenderBackend.WebGpu` is selectable from `SceneConfig`
- a real scene renders through WebGPU on supported devices
- surface resize and recreation are correct
- pointer overlay still works
- no known per-frame resource leak is observed in a 2+ minute animated run
- Canvas remains available and unchanged
- documentation and sample code reflect alpha04, not pre-alpha04 assumptions

---

## 12. Source Notes

Local repo sources used:

- `CLAUDE.md`
- `docs/internal/plans/2026-03-18-webgpu-roadmap.md`
- `docs/internal/plans/ws12-implementation.md`
- `docs/internal/reviews/2026-03-18-webgpu-view-review.md`
- `docs/internal/reports/memory-gc-performance-report.md`
- `isometric-webgpu/src/main/kotlin/.../GpuContext.kt`
- `isometric-webgpu/src/main/kotlin/.../sort/GpuDepthSorter.kt`
- `isometric-compose/src/main/kotlin/.../render/RenderBackend.kt`
- `isometric-compose/src/main/kotlin/.../SceneConfig.kt`
- `isometric-compose/src/main/kotlin/.../IsometricScene.kt`

Vendored AndroidX source used:

- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUInstance.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUDevice.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUQueue.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUBuffer.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUCommandEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPURenderPassEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUSurface.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUSurfaceConfiguration.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUSurfaceTexture.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/SurfaceGetCurrentTextureStatus.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPURequestAdapterOptions.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUDeviceDescriptor.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/helper/Util.kt`

Current external sources used:

- https://developer.android.com/develop/ui/views/graphics/webgpu/getting-started
- https://developer.android.com/jetpack/androidx/releases/webgpu
- https://developer.android.com/reference/kotlin/androidx/webgpu/GPU
- https://developer.android.com/reference/kotlin/androidx/webgpu/GPUSurface
- https://developer.android.com/reference/kotlin/androidx/webgpu/helper/WebGpu
- https://developer.android.com/reference/kotlin/androidx/webgpu/GPUSurfaceCapabilities
