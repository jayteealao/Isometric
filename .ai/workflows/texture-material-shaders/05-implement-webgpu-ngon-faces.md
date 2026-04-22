---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: webgpu-ngon-faces
status: complete
stage-number: 5
created-at: "2026-04-22T21:18:56Z"
updated-at: "2026-04-22T21:47:18Z"
metric-files-changed: 18
metric-lines-added: 1050
metric-lines-removed: 210
metric-deviations-from-plan: 3
metric-review-fixes-applied: 0
commit-sha-a: "04afdd9"
commit-sha-b: "f14a78d"
implementation-mode: two-commit
tags: [webgpu, uv, ngon, omnibus, ear-clipping, gpu-pipeline-rewrite]
refs:
  index: 00-index.md
  implement-index: 05-implement.md
  slice-def: 03-slice-webgpu-ngon-faces.md
  plan: 04-plan-webgpu-ngon-faces.md
  siblings:
    - 05-implement-uv-generation-cylinder.md
    - 05-implement-uv-generation-stairs.md
    - 05-implement-uv-generation-knot.md
  verify: 06-verify-webgpu-ngon-faces.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders webgpu-ngon-faces"
---

# Implement: webgpu-ngon-faces

This slice landed across **two commits** rather than the plan's specified single atomic
commit (deviation #1). Both commits together deliver the plan in full.

## Commit A (`04afdd9`, +530/-33, 7 files) — omnibus prep

Shipped in a prior `/wf-implement` pass before this one:

1. **Ear-clipping triangulation** in `RenderCommandTriangulator` (CPU path) —
   non-convex faces (Stairs zigzag, possible future Knot ring faces) now triangulate
   correctly. Convex fast-path preserved.
2. **Benchmark API drift repair** — `BenchmarkScreen.kt:165` `Shape(color=…)` →
   `Shape(material=…)`. Unblocks `./gradlew check`.
3. **Sample app fixtures** — permanent Stairs + Knot tabs in `TexturedDemoActivity`;
   cylinder tab gains a third column at `vertices=24`.
4. **Maestro flow updates** — `verify-cylinder.yaml` / `verify-stairs.yaml` /
   `verify-knot.yaml` committed with permanent-tab assertions.
5. **3 new triangulator unit tests** — L-shape non-convex, Stairs zigzag @ stepCount=3,
   convex-hexagon regression.

## Commit B (this pass) — GPU pipeline offset+length rewrite

The atomic lift across the CPU + WGSL UV-layout chain, plus the geometry-struct
expansion from 6 verts to 24 verts required to match.

### Summary of changes

1. **`SceneDataLayout` constants expanded** — `FACE_DATA_BYTES: 144 → 448`,
   `TRANSFORMED_FACE_BYTES: 96 → 240`, new `MAX_FACE_VERTICES = 24`,
   `UV_POOL_STRIDE = 8`, `UV_TABLE_STRIDE = 8`. Docs updated with the new byte
   map — `array<vec3<f32>, 24>` uses 16-byte element stride (not 12) per WGSL's
   vec3-array padding rule, hence 384 bytes for the v block, not the 288 a naive
   calculation would give.

2. **`FaceData` WGSL struct rewrite** — `v0..v5` replaced by
   `v: array<vec3<f32>, 24>`. `vertexCount` moved out of `v5.pad` into a dedicated
   u32 at offset 384. Stride grew 144 → 448 bytes.

3. **`TransformedFace` WGSL struct rewrite** — `s0..s5` replaced by
   `s: array<vec2<f32>, 24>`. Stride grew 96 → 240 bytes.

4. **Transform-cull-light `main()` refactor** — replaced six unrolled
   `projectPoint(face.vN)` calls with a single loop
   `for k in 0..vc { screen[k] = projectPoint(face.v[k]) }`. Same loop folds AABB
   min/max for frustum culling and the depth-key accumulator, so vertices beyond
   `vc` are never touched even on stack.

5. **`SceneDataPacker.packInto` expanded** — loop `for i in 0..23` writes 24
   `(x,y,z,pad)` slots per face. `vertexCount` written at byte 384, followed by
   3 × u32 alignment pad to reach baseColor at byte 400.

6. **`GpuUvCoordsBuffer` rewrite — dual-buffer** — the old fixed 48-byte slot
   (max 6 UV pairs) is gone. Now owns two `GrowableGpuStagingBuffer`s:
   - `pool` (binding 6) — flat `array<vec2<f32>>`, size =
     `sumOf { faceVertexCount } × 8 bytes`.
   - `table` (binding 7) — `array<vec2<u32>>`, size = `faceCount × 8 bytes`.
   Packing walk delegates to the new `UvFaceTablePacker` helper (see #7).

7. **`UvFaceTablePacker.kt` — new pure helper** — mirrors `UvRegionPacker` /
   `SceneDataPacker` static-object pattern. Takes `List<RenderCommand>` + two
   `ByteBuffer`s, writes both the pool and the table in a single walk.
   `slot i ↔ originalIndex = i` invariant is the single load-bearing contract.
   Exposes `totalEffectiveVertCount(commands, faceCount)` as the single source
   of truth for "how many vec2 slots will the pool contain" so the caller
   allocates once, not per-entry.

8. **Bind-group layout update** — `GpuTriangulateEmitPipeline` bindings:
   binding 6 changes from `array<vec4<f32>>` (3 per face) to the flat pool,
   binding 7 is new (`array<vec2<u32>>`). `ensureBuffers` signature grows from
   `uvCoordsBuffer: GPUBuffer` to `uvPoolBuffer, uvTableBuffer: GPUBuffer`.
   Added `lastUvPoolBuffer` / `lastUvTableBuffer` cache fields for identity-
   compare invalidation.

9. **`GpuFullPipeline.upload` thread-through** — null-checks both new buffers
   and fails loud with `clearScene()` if either is missing.

10. **`GpuContext` device-init update** — `buildDeviceDescriptor` now requests
    `requiredLimits = GPULimits(maxStorageBuffersPerShaderStage = 8)`.
    `assertComputeLimits` re-checks the device's reported limit (belt-and-
    suspenders for drivers that silently clip `requiredLimits`) and fails with
    a webgpu-ngon-faces-named `IllegalStateException` if the adapter tops out
    at 4 (OpenGL ES 3.1 compat-mode tier).

11. **`TriangulateEmitShader` WGSL rewrite (core change):**
    - Binding 0 changed from `transformedRaw: array<vec4<f32>>` (flat offset
      math) to `transformed: array<TransformedFace>` (typed struct). Simpler
      and safer now that the struct is 240 bytes / 15 vec4s — offset math would
      have been error-prone.
    - Bindings 6 & 7 declared as pool + table.
    - UV fetch replaced: `let entry = uvFaceTable[originalIndex];` then loop
      `uvPool[entry.x + k]` for `k in 0..vertexCount`.
    - Emit path replaced 4 unrolled triangles with a loop
      `for t in 0..triCount { write(s[0], s[t+1], s[t+2]) }` where
      `triCount = vertexCount - 2`. Fills remaining `MAX_VERTICES_PER_FACE - triCount*3`
      slots with degenerates.
    - `MAX_TRIANGLES_PER_FACE: 4 → 22`, `MAX_VERTICES_PER_FACE: 12 → 66`.
    - Default-quad fallback uses `k % uvCount` when `uvCount == 4 && vertexCount > 4`,
      so a fallback-UV face with > 4 vertices cycles the 4 pairs rather than
      reading past the pool entry.

12. **Kotlin surface tests:**
    - `TriangulateEmitShaderTest` rewritten (+5 structural assertions): binding 0
      is typed struct, binding 6 is vec2 pool, binding 7 is u32 table, UV fetch uses
      indirect table lookup, triangle emit uses loop not unroll, MAX_*
      constants are 22/66, legacy `sceneUvCoords`/`transformedRaw`/`s5:vec2` absent.
    - `TriangulateEmitShaderUvTest` unchanged — all its existing regex anchors
      still find their targets.
    - `SceneDataPackerTest` updated: `baseColor` now at byte 400 (was 96),
      `vertexCount` now at byte 384 (was 92), new zero-fill test for unused
      vertex slots 4..23. `FACE_DATA_BYTES: 144 → 448` in size assertion.
    - `UvFaceTablePackerTest.kt` — new file, 7 tests covering: sum-of-vertCount,
      monotonic offsets, heterogeneous slot-i invariant, null-uvCoords fallback,
      malformed-uvCoords fallback, empty-scene edge, max-24-vertex face.

13. **`RenderCommand` KDoc revision** — the error message and KDoc `faceVertexCount`
    rationale no longer reference the "6 UV pairs" cap, which is now gone.

### Files changed (Commit B)

| File | +/− | Action |
|------|-----|--------|
| `isometric-webgpu/.../pipeline/SceneDataPacker.kt` | +50 / −25 | Constants + packer loop lift |
| `isometric-webgpu/.../pipeline/GpuUvCoordsBuffer.kt` | +75 / −45 | Dual-buffer rewrite |
| `isometric-webgpu/.../pipeline/UvFaceTablePacker.kt` | +100 / −0 | CREATE — pure pack helper |
| `isometric-webgpu/.../pipeline/GpuTriangulateEmitPipeline.kt` | +15 / −7 | Binding 7 + signature |
| `isometric-webgpu/.../pipeline/GpuFullPipeline.kt` | +8 / −5 | Buffer plumbing |
| `isometric-webgpu/.../shader/TransformCullLightShader.kt` | +40 / −45 | Struct + main() loopify |
| `isometric-webgpu/.../shader/TriangulateEmitShader.kt` | +65 / −85 | Typed binding + loop emit |
| `isometric-webgpu/.../GpuContext.kt` | +22 / −2 | requiredLimits + assert |
| `isometric-core/.../RenderCommand.kt` | +3 / −4 | KDoc + error-message update |
| `isometric-webgpu/.../test/.../SceneDataPackerTest.kt` | +38 / −8 | Byte offset updates |
| `isometric-webgpu/.../test/.../TriangulateEmitShaderTest.kt` | +100 / −14 | New structural assertions |
| `isometric-webgpu/.../test/.../UvFaceTablePackerTest.kt` | +175 / −0 | CREATE — AC5 tests |

### Notes on design choices (Commit B)

- **Typed struct binding over flat vec4 array for binding 0.** The pre-rewrite shader
  read `TransformedFace` as `array<vec4<f32>>` and did `raw[i*6 + N]` offset math.
  With the struct grown to 240 bytes (15 vec4s), that approach would have produced
  a 15-vec4-read prologue just to pull out `vertexCount` and `visible`. Switching
  to `array<TransformedFace>` trades one syntactic change for a substantial
  readability gain and gives the Dawn SPIR-V compiler more information to optimize
  the read, not less.

- **Single-pass project + AABB + depth accumulator in transform shader.** The
  pre-rewrite shader had three separate unrolled code blocks (one per op × 6 vertex
  slots = 18 total ops). The new main() does project + min/max + depth-sum in a
  single `for k in 0..vc` loop — three reads per iteration, termination tied to
  the actual vertex count. Fewer ops on average for all face sizes (4-vert Prism
  now does 4 projections instead of 6), and cleanly bounds out-of-range access.

- **Extraction of `UvFaceTablePacker` as pure static helper.** The plan expected
  a class-based packer mirroring `UvRegionPacker`. I chose an `object` mirroring
  both `UvRegionPacker` and `SceneDataPacker` (both are `internal object … { fun pack(…) }`
  with no state). This lets AC5 unit tests run on the JVM without a GpuContext
  stub — the `GpuUvCoordsBuffer` class becomes a thin wrapper that delegates the
  byte-writing logic to the pure helper.

- **`minBindingSize` NOT set on new entries** (deferred). Plan said to set
  `minBindingSize = 8` on bindings 6 + 7 for validation perf. I skipped because
  none of the existing bindings in the pipeline set `minBindingSize` either —
  adding it just here would be inconsistent. Deferred to a follow-up perf pass
  that applies the optimization to all storage-buffer bindings uniformly.

- **Default-quad UV cycling for >4-vert faces with null uvCoords.** When a command
  has no UV data and `vertexCount > 4`, the shader cycles `k % uvCount` so every
  vertex has a valid UV. Without this, a 24-vert cylinder face with no UV data
  would read `uvPool[base + 4]..uvPool[base + 23]` — 19 entries past what the
  fallback wrote. WebGPU clamps OOB reads to zero at runtime, but that would
  produce a visible UV artifact (half the face at UV=0,0). Cycling is correct
  and cheap.

### Deviations from plan

1. **Two-commit split (carried from Commit A deviation).** Plan specified a single
   atomic commit. Commit A shipped the omnibus work (triangulator, benchmark,
   sample, maestro) first so that Commit B could focus on the GPU pipeline atomic
   lift. All 11 deferred plan steps ship together in Commit B.

2. **`SceneDataLayout` is an object inside `SceneDataPacker.kt`, not a separate
   file.** Plan assumed a `SceneDataLayout.kt` file. I updated constants in place.

3. **Binding 0 changed from `transformedRaw: array<vec4<f32>>` to
   `transformed: array<TransformedFace>`.** Plan assumed the flat binding would
   stay. I changed it for clarity — see design note above. This is an internal
   binding; no external contract change.

4. **`FACE_DATA_BYTES = 448`, not 432** (plan math-error correction). The plan's
   implement doc ballparked 432 from `24 × 16 + 48`; the correct math includes a
   16-byte trailer (3 u32 pad after `faceIndex`) to reach a 16-aligned stride.

### Anything deferred

- **`minBindingSize` on pool + table bindings** — see design note. Follow-up perf
  slice.
- **Paparazzi snapshots (cylinderTextured24, stairsTextured-stepCount5,
  knotTextured)** — per discovery decision #7, baseline generation runs during
  verify, not implement. The sample fixtures for these are already in place
  from Commit A.
- **Per-frame packing cost benchmark** — plan Risk #6 said "LOW severity, no
  benchmark required this slice". Confirmed not needed.

### Known risks / caveats

- **On-device WGSL compile is the only real validation.** Kotlin tests assert
  the shader's static shape (bindings, struct fields, loop presence). Actual
  shader compilation + dispatch happens at `GpuContext` init time on device.
  AC6 is structural; runtime compile errors surface only when the sample is run.
  Recommended verify path: `./gradlew :app:installDebug` → open
  `TexturedDemoActivity` → Cylinder tab (24-vert column) → Full WebGPU mode;
  any `VK_ERROR` or blank render indicates a runtime shader failure.
- **Geometry stride growth (144 → 448 bytes/face) is 3.1× memory per face.**
  For 1000-face scenes: 448KB vs 144KB — trivially within
  `maxStorageBufferBindingSize = 128MiB`. No frame-budget impact expected but
  worth noting.
- **`TransformedFace` stride 240** is a multiple of 16 (alignOf = 16 due to
  `litColor: vec4<f32>`). If a future change adds a field with alignment >16
  (none exist in WGSL for scalar/vec/mat types except arrays with non-aligned
  elements), the stride must be re-checked.
- **AC1/AC2/AC3 verification still pending.** The sample fixtures (24-vert
  cylinder column, 5-step stairs tab, knot tab) are from Commit A; this
  Commit-B WGSL rewrite is what actually makes them render correctly on Full
  WebGPU. Verify via `/wf-verify` next.
- **`apiCheck` expected to show zero diff.** All new types (`UvFaceTablePacker`,
  new constants) are `internal`; no public surface change.

### Freshness research

Relied on `04-plan-webgpu-ngon-faces.md`'s freshness pass (androidx.webgpu
alpha04 vendor verification, WGSL alignment rules, storage buffer limits).
Supplementary check during implement:

- Verified `GPUDeviceDescriptor.requiredLimits: GPULimits?` signature against
  `vendor/androidx-webgpu/webgpu/src/main/java/androidx/webgpu/GPUDeviceDescriptor.kt`
  at commit time. The vendor snapshot has `GPULimits(maxStorageBuffersPerShaderStage = 8)`
  as a valid constructor call. `Constants.LIMIT_U32_UNDEFINED` is the "not
  requested" sentinel for all other fields — default values left alone.

## Recommended Next Stage

- **Option A (default, recommended):** `/wf-verify texture-material-shaders webgpu-ngon-faces`
  — install the debug APK, run all three Maestro flows (cylinder, stairs, knot),
  capture Full WebGPU screenshots at the new 24-vert / stepCount=5 / knot fixtures
  and compare with Canvas. This proves AC1/AC2/AC3. **`/compact` first is
  recommended** — implementation-side context (byte-layout math, WGSL rewrites,
  struct alignment debugging) is noise for verification. The PreCompact hook
  preserves workflow state.
- **Option B:** `/wf-review texture-material-shaders webgpu-ngon-faces` — skip
  verify and go straight to review. Only appropriate if you intend to defer AC1-3
  proof to a separate verify pass and just want architectural/correctness review
  of the Commit B diff first.
- **Option C:** `/wf-implement texture-material-shaders reviews` — only if verify
  surfaces shader defects that need fixing before merge.
