# WebGPU Phase 3 Completion Plan

Date: 2026-03-26
Scope:
- `isometric-webgpu` Phase 3 compute + render pipeline
- correctness, completion, and stabilization of the GPU-driven path
- alignment with vendored `androidx.webgpu` source in `vendor/androidx-webgpu/`

## Goal

Complete the WebGPU Phase 3 path so that it is:

1. functionally correct for supported scene geometry
2. explicit about unsupported geometry if any remains
3. source-accurate against the vendored `androidx.webgpu` API
4. stable under normal renderer lifecycle transitions
5. covered by regression tests that target the actual failure modes seen so far

This plan is intentionally sequenced to fix correctness and contract drift before
attempting to optimize or broaden platform support.

## Current State

The implementation is already in a partial Phase 3 shape:

- M3: transform + cull + light
- M4a: pack sort keys
- M4b: GPU bitonic sort
- M5: triangulate + emit
- M6: render pass using emitted vertex buffer + indirect draw

However, the current path is not complete:

- empty scenes can retain stale draw state
- the M5 shader is still a diagnostic shader rather than the real emit stage
- scene packing silently truncates faces to 4 vertices
- the renderer is still operating with debug/driver-workaround submission mode
- test coverage does not guard the actual Phase 3 breakpoints

## Vendor Constraints

The vendored `androidx.webgpu` source changes how the implementation should be planned:

### 1. Per-frame wrapper closure is mandatory

Relevant vendor/source references:
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUCommandEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPURenderPassEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUComputePassEncoder.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUQueue.kt`
- `docs/internal/GPU_OBJECT_LIFETIME.md`

These wrappers are `AutoCloseable`. The implementation must continue to:

- close command encoders after submit
- close command buffers after submit
- close compute/render pass encoders after `end()`
- close per-frame texture views after use
- close `GPUSurfaceTexture.texture` after present

This is not optional cleanup polish. It is part of the correctness envelope for
long-running sessions.

### 2. `GPUInstance.processEvents()` polling remains part of async initialization

Relevant vendor/source reference:
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUInstance.kt`

`requestAdapter()` and async pipeline/device callbacks depend on `processEvents()`.
The plan should not remove or bypass the existing polling model in `GpuContext`
unless the vendored API changes.

### 3. Surface lifecycle must be treated as validation-sensitive

Relevant vendor/source references:
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUSurface.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUSurfaceTexture.kt`
- `vendor/androidx-webgpu/webgpu/src/androidTest/java/androidx/webgpu/SurfaceTest.kt`
- `vendor/androidx-webgpu/webgpu/src/androidTest/java/androidx/webgpu/ErrorTest.kt`

Surface configure/present operations throw validation/runtime exceptions. The
renderer plan must preserve:

- capability query before configure
- explicit handling of `GPUSurfaceTexture.status`
- safe reconfigure/recreate behavior
- partial-init-safe teardown

### 4. Upload and indirect paths should stay aligned with the real API

Relevant vendor/source references:
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUQueue.kt`
- `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPURenderPassEncoder.kt`

The current architecture is directionally correct here:

- CPU uploads use `GPUQueue.writeBuffer(ByteBuffer)`
- render uses `drawIndirect(buffer, offset)`
- command encoding can use `clearBuffer()` where explicit reset is needed

The plan should use those primitives instead of inventing extra staging passes
unless profiling proves they are necessary.

## Main Problems To Fix

### P1. Host-side stale state on empty scene transitions

Observed issue:
- when the scene becomes null or empty, old GPU buffers/indirect args can remain live
- `hasScene` can stay true from the previous upload
- stale geometry may continue rendering

Required outcome:
- empty scene upload path must explicitly clear draw state
- the renderer must become incapable of drawing stale geometry after an empty scene
- this must be test-covered

### P2. Geometry contract drift

Observed issue:
- `SceneDataPacker` currently truncates input to 4 3D vertices
- core engine geometry supports polygons with more than 4 vertices
- docs/shader comments still imply broader support than the actual code delivers

Required outcome:
- either support the actual engine face range in Phase 3
- or explicitly validate and reject unsupported geometry before GPU upload

Given the engine API today, the preferred direction is to support broader face counts
rather than silently narrowing them.

### P3. M5 is not implemented

Observed issue:
- `TriangulateEmitShader` is still a diagnostic shader
- it does not emit real triangle fans into the render vertex buffer

Required outcome:
- replace the diagnostic code with a production WGSL emit path
- guarantee layout compatibility with the Kotlin-side render vertex format
- guarantee deterministic handling of culled/padding entries

### P4. Dispatch/workaround mode is still debug-oriented

Observed issue:
- renderer still uses individual compute submits as an active workaround
- that is acceptable during isolation, but not as the final implementation target

Required outcome:
- first stabilize correctness
- then narrow the workaround to only the minimum unavoidable device-specific path
- document any remaining vendor/driver-specific deviation

### P5. Regression coverage is not protecting the real pipeline

Observed issue:
- current tests do not cover empty-scene transitions, >4-vertex faces, or real M5 output

Required outcome:
- add focused tests for the actual contract and failure modes

## Implementation Sequence

### Step 1. Lock down the Phase 3 data contract

Before changing implementation details, explicitly define:

- supported maximum input vertices per face in GPU Phase 3
- `FaceData` struct layout
- `TransformedFace` struct layout
- `SortKey` layout and sentinel semantics
- emitted render vertex layout
- indirect draw contract

Deliverables:
- one canonical source of truth in code comments/docs
- removal of contradictory comments about 4-vertex vs 6-vertex support

Decision point:
- if the engine can emit arbitrary convex polygons, decide whether Phase 3 supports:
  - a bounded maximum vertex count, or
  - preprocessing into GPU-friendly chunks on the CPU before upload

The contract must be explicit before any M5 rewrite.

### Step 2. Fix host-side correctness before shader work

Changes:

- make empty/null scene uploads clear renderer draw state
- ensure indirect args and any scene-valid flags reset on empty scene
- use explicit zeroing/reset where needed rather than relying on old contents
- audit reconfigure/recreate/cleanup transitions for partial-init safety

Validation:

- scene with geometry -> empty scene -> no draw
- scene A -> empty -> scene B transition
- surface reconfigure while scene state changes

This step is intentionally first because shader debugging is meaningless while stale
draw state exists.

### Step 3. Fix geometry support drift

Changes:

- update scene packing and M3 transform path to support the chosen maximum face size
- ensure vertex-count-dependent code actually reads/writes the full supported range
- if a bounded maximum remains, add explicit validation and failure mode at upload time

Validation:

- triangles
- quads
- polygons with more than 4 vertices
- existing shapes that produce circle/cylinder-like geometry

The current silent truncation is unacceptable; this step removes it.

### Step 4. Implement the real M5 shader

Changes:

- replace the diagnostic shader body with real triangulate-and-emit logic
- preserve deterministic fixed-stride output unless profiling later justifies another approach
- emit degenerate vertices only for unused slots and sentinels
- keep WGSL free of avoidable driver-hostile patterns while correctness is being restored

Validation:

- verify triangle fan output count per face
- verify sorted order is respected
- verify culled/padding entries produce no visible geometry
- verify emitted buffer matches render pipeline vertex layout

If M5 still trips the Adreno failure after the real shader is in place, we isolate the
driver-sensitive pattern only after functional correctness is proven.

### Step 5. Re-evaluate dispatch sequencing

Changes:

- test single-encoder/single-submit path once Steps 2-4 are correct
- if the Adreno workaround is still required, localize it behind a clearly named policy
- avoid leaving debug flags as permanent architecture

Validation:

- correctness under normal submission path
- correctness under individual-submit fallback
- no accidental dependence on debug-only sequencing

### Step 6. Expand regression coverage

Required tests:

- empty scene clears draw state
- stale geometry does not render after null/empty upload
- scene packer preserves supported vertex counts
- transformed-face layout stays in sync with WGSL
- sort sentinels land at the end and are skipped by M5
- M5 emits correct vertex counts for triangle, quad, and larger polygon cases
- render pipeline consumes emitted vertex format without layout mismatch

Where possible, split these into:

- JVM tests for packing/layout/count logic
- targeted Android/device tests for WebGPU execution behavior

### Step 7. Verification and exit criteria

Minimum exit criteria for “Phase 3 complete”:

1. no stale drawing after empty/null scene transitions
2. no silent geometry truncation inside the supported contract
3. M5 emits real triangles, not diagnostic output
4. renderer runs without relying on undocumented debug behavior
5. affected tests pass
6. any remaining device-specific workaround is documented and intentionally scoped

## Risks

### R1. Adreno-specific codegen or barrier sensitivity may still remain after M5 is corrected

Mitigation:
- restore functional correctness first
- then isolate the smallest shader/control-flow pattern that reproduces device loss
- preserve a contained fallback path if needed

### R2. Supporting arbitrary polygon sizes may broaden scope too far

Mitigation:
- define a bounded GPU face contract if necessary
- validate unsupported input explicitly instead of truncating
- if needed, add CPU preprocessing to split large polygons into GPU-legal faces

### R3. Shader/Kotlin layout drift can reappear silently

Mitigation:
- add layout/stride assertions in tests
- keep a single contract document near the implementation

## Immediate Work Order

1. fix empty-scene stale draw state
2. remove silent face truncation by defining and enforcing the real geometry contract
3. implement the production M5 shader
4. add regression tests for those three areas
5. only then revisit submit/workaround policy

## Notes

This plan is intentionally more conservative than the current implementation trajectory.
The code already jumped ahead into a GPU-driven pipeline; the right move now is not to
add more features first, but to close the contract gaps and complete the path that is
already present.
