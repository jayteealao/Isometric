---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: webgpu-ngon-faces
status: complete
stage-number: 4
created-at: "2026-04-22T19:01:04Z"
updated-at: "2026-04-22T19:01:04Z"
metric-files-to-touch: 14
metric-step-count: 11
has-blockers: false
revision-count: 0
tags: [webgpu, uv, ngon, variable-stride, offset-length, gpu-buffer, shader, omnibus]
depends-on: [uv-generation-cylinder, uv-generation-stairs, uv-generation-knot]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-webgpu-ngon-faces.md
  siblings:
    - 04-plan-uv-generation-cylinder.md
    - 04-plan-uv-generation-stairs.md
    - 04-plan-uv-generation-knot.md
  implement: 05-implement-webgpu-ngon-faces.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders webgpu-ngon-faces"
---

# Plan: webgpu-ngon-faces

## Slice Goal

Replace the WebGPU pipeline's fixed 6-vertex-per-face cap with a tightly-packed
offset+length indirection layout supporting up to 24 vertices per face. After
this slice ships, Cylinder caps (`vertices > 6`), Stairs zigzag sides
(`stepCount > 2`), and Knot's variable per-face vertex counts all render
pixel-equivalently between Canvas and Full WebGPU. Per the discovery interview
this is an **atomic lift across the entire 6-vert chain** (UV buffer + transform
output struct + WGSL emit shader) plus an **omnibus cleanup** of the deferred
stairs-verify I-2 (CPU triangulator non-convex fan) and the pre-existing
`:isometric-benchmark` compile failure.

## Current State

The WebGPU rendering pipeline has a 6-vertex-per-face cap that is mirrored in
**three independent locations**, each of which must be lifted in lockstep:

1. **`GpuUvCoordsBuffer.kt`** — fixed 48-byte/face stride (3 × `vec4<f32>` = 6 UV
   pairs). The `entryBytes = 48` literal is at line 37; the `for (j in 0 until 12)`
   loop bound is at lines 59-61. Comment block at lines 48-56 carries the
   `TODO(uv-variable-stride)` rationale and lists Cylinder caps, Stairs zigzag
   sides, and Knot per-face as the three current victims. No companion-object
   constants — both magic numbers (48 bytes, 12 floats) are inline.
2. **`SceneDataPacker.kt:124`** — `val n = pts3d.size.coerceAtMost(6)` caps the
   vertex count written into the M3 transform shader's `FaceData` struct (geometry
   path, separate from UV). This must lift in lockstep or the M5 emit shader will
   still see only 6 NDC positions, defeating the entire UV expansion.
3. **`TransformedFace` 96-byte struct (6 × `vec2<f32>` screen coords)** in the M3
   `TransformCullLightShader.WGSL` output. This is the deepest dependency — the
   M3 shader's per-face output struct's vertex slots directly determine how many
   vertices the M5 emit shader can see, regardless of UV pool size.

The M5 `TriangulateEmitShader.kt` declares `MAX_TRIANGLES_PER_FACE = 4` (line
110) and `MAX_VERTICES_PER_FACE = 12` (line 113). These constants and the
hardcoded `triCount >= 4u` write paths must expand to handle 22 triangles
(24-vertex face fan) and 24 vertex slots.

The slice's UV-fetch site in WGSL (lines 291-304 of the raw string) is the
indirect-lookup target:
```wgsl
let uvBase   = key.originalIndex * 3u;
let uvPack0  = sceneUvCoords[uvBase + 0u];  // (u0,v0,u1,v1)
let uvPack1  = sceneUvCoords[uvBase + 1u];
let uvPack2  = sceneUvCoords[uvBase + 2u];
let uv0 = uvRegion.userMatrix * vec3<f32>(uvPack0.x, uvPack0.y, 1.0);
...
let uv5 = uvRegion.userMatrix * vec3<f32>(uvPack2.z, uvPack2.w, 1.0);
```

This becomes:
```wgsl
let entry = uvFaceTable[key.originalIndex];   // vec2<u32>(offsetPairs, vertCount)
for (var i: u32 = 0u; i < entry.y; i = i + 1u) {
    let uvPair = uvPool[entry.x + i];
    let uvHomog = uvRegion.userMatrix * vec3<f32>(uvPair.x, uvPair.y, 1.0);
    // ...write per-vertex
}
```

The bind-group layout grows from 7 (bindings 0..6) to 8 (bindings 0..7).
Binding 6 changes type from `array<vec4<f32>>` (12-float fixed slots) to
`array<vec2<f32>>` (flat UV pool). Binding 7 is new: `array<vec2<u32>>`
(per-face offset+count table). No existing `BindGroupLayoutCache` or
`PipelineCache` class — cache invalidation is via identity comparison of
`GPUBuffer` references in `GpuTriangulateEmitPipeline.ensureBuffers()`, so a
new `lastUvOffsetBuffer` field is added.

`androidx.webgpu` is at **alpha04** (Feb 2026). Field names in the descriptor
builders have churned across alpha releases — verify against
`vendor/androidx-webgpu/` snapshot before referencing.

The CPU triangulator (`RenderCommandTriangulator.kt:75-91`) is the home of
stairs-verify I-2: triangle-fan emits incorrect triangles for non-convex
polygons (Stairs zigzag sides, possibly future Knot faces). This path is used
only by tests and the Canvas fallback (the M5 GPU path uses its own logic). Per
discovery decision #9, this is fixed in-slice via ear-clipping.

The `:isometric-benchmark/BenchmarkScreen.kt:165` calls
`Shape(geometry = ..., color = ..., position = ...)` — the `color` parameter was
replaced by `material: MaterialData` in the `api-design-fixes` slice (commit
`1eb8f19`). Pre-existing I-1 from stairs verify; one-line repair per
discovery decision #8.

## Reuse Opportunities

Per the GPU code deep-dive sub-agent (key candidates listed; all confirmed by
direct file reads):

- **`UvRegionPacker.kt`** → packs 40 bytes/face (`mat3x2<f32>` + `vec2<f32>` ×
  2) into binding 5 with a `ByteBuffer` CPU-side staging area. **Closest
  template** for the new offset+length table writer. Pattern is directly
  transferable: same staging-buffer shape, same `GrowableGpuStagingBuffer`
  ownership, same upload path. **Recommendation: reuse the pattern (not the
  code) — implement `UvFaceTablePacker` mirroring `UvRegionPacker`'s structure.**
- **`GpuTextureManager.kt`** → `sceneTexIndices` packed as 4 bytes/face into
  binding 4 via `SceneDataPacker.packTexIndicesInto()`. Another fixed-stride
  per-face buffer template. **Recommendation: reuse-as-pattern; the
  `array<u32>` shape is similar enough that the new `array<vec2<u32>>` table
  should follow the same upload sequencing.**
- **`GrowableGpuStagingBuffer.kt`** → already used by `GpuUvCoordsBuffer`.
  Supports `ensureCapacity(entryCount, entryBytes)` with x2 growth. **Two
  separate instances** can be allocated: one for the variable-length pool
  (`entryBytes = 8` for one `vec2<f32>` pair, `entryCount = totalPairCount`),
  one for the offset+length table (`entryBytes = 8` for one `vec2<u32>`,
  `entryCount = faceCount`). **Recommendation: reuse as-is, no modification.**
- **`RenderCommandTriangulator.ensureBuffer()`** → x2 growth strategy for a
  staging `ByteBuffer`. Alternative if `GrowableGpuStagingBuffer` is wrong-fit
  for the variable pool. **Recommendation: prefer `GrowableGpuStagingBuffer`
  for consistency with sibling buffers.**

For the **ear-clipping triangulator** (omnibus item from discovery #9): no
existing ear-clipping implementation in the codebase. **Recommendation:
implement fresh** as a private function inside `RenderCommandTriangulator.kt`,
~50 lines. Public-domain reference: standard O(n²) algorithm using
"is-ear" predicate (vertex is an ear if its triangle contains no other vertex
and is convex). Test fixtures: Stairs zigzag at multiple stepCounts +
synthetic non-convex polygons.

## Likely Files / Areas to Touch

| File | Action | Module |
|------|--------|--------|
| `isometric-webgpu/src/main/kotlin/.../pipeline/GpuUvCoordsBuffer.kt` | REWRITE — dual-buffer layout (pool + table); extract MAX_FACE_VERTICES constant | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../pipeline/SceneDataPacker.kt` | MODIFY — lift `coerceAtMost(6)` at line 124 to `coerceAtMost(MAX_FACE_VERTICES)`; expand `FaceData` byte writes | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../pipeline/SceneDataLayout.kt` | MODIFY — bump `FACE_DATA_BYTES` and `TRANSFORMED_FACE_BYTES` constants from 6-vert to 24-vert sizes | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../shader/TransformCullLightShader.kt` | MODIFY — expand `TransformedFace` struct WGSL (6 → 24 vec2 screen coords); update writes | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../shader/TriangulateEmitShader.kt` | REWRITE WGSL — new bindings 6+7 declarations; replace fixed UV unpack with indirect lookup loop; expand `MAX_TRIANGLES_PER_FACE`, `MAX_VERTICES_PER_FACE` constants | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../pipeline/GpuTriangulateEmitPipeline.kt` | MODIFY — new bind-group layout entry (binding 7); add `lastUvOffsetBuffer` field; update `ensureBuffers()`; expand vertex buffer sizing | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../pipeline/GpuFullPipeline.kt` | MODIFY — null-check + parameter pass for new offset table buffer | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../core/GpuContext.kt` (or equivalent device-init site) | MODIFY — `requiredLimits = { maxStorageBuffersPerShaderStage: 8 }`; throw at init with clear error if adapter rejects | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../pipeline/UvFaceTablePacker.kt` | CREATE — new packer for binding 7, mirrors `UvRegionPacker.kt` pattern | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../triangulation/RenderCommandTriangulator.kt` | MODIFY — replace triangle-fan loop at line 75-91 with ear-clipping; add `private fun earClipTriangulate(points): List<IntArray>` (~50 lines); preserve existing UV/color paths | isometric-webgpu |
| `isometric-webgpu/src/test/kotlin/.../pipeline/GpuUvCoordsBufferTest.kt` | CREATE — unit tests for AC5 (offset table correctness, pool layout, default fallback, empty scene) | isometric-webgpu |
| `isometric-webgpu/src/test/kotlin/.../shader/TriangulateEmitShaderTest.kt` | MODIFY — update assertions for new `MAX_VERTICES_PER_FACE = 24`, new bindings, indirect lookup pattern | isometric-webgpu |
| `isometric-webgpu/src/test/kotlin/.../shader/TriangulateEmitShaderUvTest.kt` | MODIFY — assert binding 6 is `array<vec2<f32>>` (was `vec4<f32>`); assert binding 7 declared; assert indirect lookup expression present | isometric-webgpu |
| `isometric-webgpu/src/test/kotlin/.../triangulation/RenderCommandTriangulatorTest.kt` | MODIFY — add ear-clipping test cases (stairs zigzag, synthetic non-convex polygons); ensure existing convex tests still pass | isometric-webgpu |
| `app/src/main/kotlin/io/github/jayteealao/isometric/sample/TexturedDemoActivity.kt` | MODIFY — extend `TexturedCylinderScene` to 3 columns (12-vert textured + 12-vert perFace + 24-vert textured); update tab description | app |
| `.maestro/verify-cylinder.yaml` | MODIFY — add screenshot at vertices=24 cap; add assertion that cap renders as full disk | app/maestro |
| `.maestro/verify-stairs.yaml` | MODIFY — extend with stepCount=5 fixture screenshot (requires stairs tab in TexturedDemoActivity — CONSIDER adding) | app/maestro |
| `.maestro/verify-knot.yaml` | MODIFY — knot is already in TexturedDemoActivity? **NO** — knot tab does not currently exist; it was added temporarily during verify and reverted. Decision: reuse the same temp-fixture pattern this slice OR add knot tab permanently here | app/maestro |
| `isometric-compose/src/test/kotlin/.../IsometricCanvasSnapshotTest.kt` | MODIFY — add `cylinderTextured24`, `stairsTextured-stepCount5`, `knotTextured` test methods (per-changed-shape new-snapshot strategy from discovery #7) | isometric-compose |
| `isometric-benchmark/src/main/kotlin/.../BenchmarkScreen.kt` | MODIFY — line 165: `Shape(color = ...)` → `Shape(material = ...)` (omnibus repair from discovery #8) | isometric-benchmark |
| `isometric-webgpu/api/isometric-webgpu.api` | UPDATE if any internal class becomes public during refactor (apiCheck gate) | isometric-webgpu |

Two files marked CONSIDER pending Step 7 detail.

## Proposed Change Strategy

**Bottom-up sequencing** (per discovery decision #5) — each step compilable on
its own; shader changes last so the full WGSL story is told once with all
CPU-side scaffolding in place.

1. Extract magic-number constants → expand structs → expand packer → create
   new packer + table buffer → rewrite UV buffer → rewrite WGSL bindings →
   rewrite WGSL fetch logic → expand WGSL emit loop.

2. Omnibus items (I-2 ear-clipping + benchmark fix) are sequenced **after**
   the GPU pipeline rewrite to keep diffs reviewable in groups. They live in
   their own commits within the slice's PR.

## Step-by-Step Plan

### Step 1 — Extract constants + expand TransformedFace

`SceneDataLayout.kt`: introduce
```kotlin
internal const val MAX_FACE_VERTICES = 24
internal const val FACE_DATA_BYTES   = /* recompute from 96 → 96 + 18*8 = 240 */
internal const val TRANSFORMED_FACE_BYTES = /* 96 → 96 + 18*8 = 240 */
```
(Exact byte counts verified during implement; assumes 6×vec2 → 24×vec2.)

`TransformCullLightShader.kt`: expand `TransformedFace` struct WGSL to 24
`vec2<f32>` slots; update all per-face write loops; preserve any padding
alignment comments.

**Compile check:** `./gradlew :isometric-webgpu:compileDebugKotlin` should
pass (struct expansion is bytecode-additive only).

### Step 2 — Lift SceneDataPacker cap

`SceneDataPacker.kt:124`: `val n = pts3d.size.coerceAtMost(MAX_FACE_VERTICES)`.
Audit ALL byte-write loops in this file for additional 6-vertex assumptions
(grep for `* 6`, `coerceAtMost(6)`, `until 6`, `i in 0..5`). Update all.

**Compile check:** `:isometric-webgpu:compileDebugKotlin` + existing
`SceneDataPackerTest` should still pass (5 existing tests cover layout
invariants — they may need byte-offset updates).

### Step 3 — Create `UvFaceTablePacker.kt`

Mirror `UvRegionPacker.kt` exactly. Public surface:
```kotlin
internal class UvFaceTablePacker {
    private val staging = GrowableGpuStagingBuffer(entryBytes = 8)
    fun pack(scene: Scene, uvPoolOffsets: IntArray): GpuBuffer { ... }
}
```
The `uvPoolOffsets[i]` is computed during the same walk that fills the pool —
see Step 4. Each entry is `(offsetPairs: u32, vertCount: u32)`.

### Step 4 — Rewrite `GpuUvCoordsBuffer.kt`

Dual-buffer layout. The class now owns:
- `poolBuffer: GrowableGpuStagingBuffer(entryBytes = 8)` — flat
  `array<vec2<f32>>`. Size = sum of `cmd.faceVertexCount` across all commands
  (× 8 bytes).
- `tableBuffer: GrowableGpuStagingBuffer(entryBytes = 8)` — `array<vec2<u32>>`.
  Size = `faceCount × 8 bytes`.

`upload(scene, faceCount)` walks `scene.commands`:
- Compute `totalPairs = scene.commands.sumOf { it.faceVertexCount }`.
- Ensure pool capacity for `totalPairs * 8` bytes; ensure table capacity for
  `faceCount * 8` bytes.
- For each command: write `(currentOffset, vertCount)` into table[i]; write
  `vertCount` UV pairs into pool starting at `currentOffset`; advance
  `currentOffset += vertCount`.
- Default fallback (uvCoords == null): write 4 UV pairs `(0,0)(1,0)(1,1)(0,1)`
  into pool; table[i] = `(currentOffset, 4)`.

**Critical invariant: `slot i ↔ originalIndex = i`.** The table entry at
index `i` MUST correspond to `commands[i]` regardless of pool offsets. New
unit tests in `GpuUvCoordsBufferTest.kt` pin this for heterogeneous
face-vertex-counts (per AC5).

### Step 5 — Update bind-group layout

`GpuTriangulateEmitPipeline.kt`:
- Lines 204-251 (`ensurePipelines`): change binding 6 type from
  `ReadOnlyStorage<vec4<f32>>` (implicit) to flat pool; add binding 7
  `ReadOnlyStorage<vec2<u32>>`.
- Set `minBindingSize = 8` on both new entries (per web-research finding —
  shifts validation cost from per-dispatch to bind-group-creation time).
- Lines 306-314 (`ensureBuffers`): add `lastUvOffsetBuffer: GPUBuffer?` field;
  identity-compare for cache invalidation.
- Line 382 (`GPUBindGroupEntry`): add binding 7 entry alongside binding 6.

`GpuFullPipeline.kt:248-264`: thread the second buffer through.

### Step 6 — Update GpuContext device init

In the device-creation site (find in implement; likely `GpuContext.kt` or
`WebGpuComputeBackend`), add:
```kotlin
requiredLimits = mapOf(
    "maxStorageBuffersPerShaderStage" to 8L,
)
```
On adapter rejection: throw `IllegalStateException("WebGPU adapter does not
support 8 storage buffers per shader stage; required by webgpu-ngon-faces. " +
"Affected device tier: OpenGL ES 3.1 compat mode.")`. Document fail-loud
behavior in `GpuContext` KDoc.

### Step 7 — Rewrite WGSL UV-fetch path

`TriangulateEmitShader.kt` WGSL string:
- New binding declarations:
  ```wgsl
  @group(0) @binding(6) var<storage, read> uvPool:      array<vec2<f32>>;
  @group(0) @binding(7) var<storage, read> uvFaceTable: array<vec2<u32>>;
  ```
- Replace lines 291-304 (UV unpack):
  ```wgsl
  let entry = uvFaceTable[key.originalIndex];
  let baseOffset = entry.x;
  let vertCount  = entry.y;
  // Loop emitting per-vertex UVs into local array uv[24]
  var uv: array<vec2<f32>, 24>;
  for (var i: u32 = 0u; i < vertCount; i = i + 1u) {
      let pair = uvPool[baseOffset + i];
      let h = uvRegion.userMatrix * vec3<f32>(pair.x, pair.y, 1.0);
      uv[i] = vec2<f32>(h.x, h.y);
  }
  ```
- Expand the triangle emit (lines 307-333) to a loop over `vertCount - 2`
  triangles using fan indices `(0, i, i+1)` for `i in 1..vertCount-1`.
- Bump constants: `MAX_TRIANGLES_PER_FACE = 22`, `MAX_VERTICES_PER_FACE = 66`
  (22 triangles × 3 verts).

### Step 8 — Update WGSL structural tests

`TriangulateEmitShaderTest.kt` + `TriangulateEmitShaderUvTest.kt`: regex
assertions for:
- `@group(0) @binding(6) var<storage, read> uvPool: array<vec2<f32>>`
- `@group(0) @binding(7) var<storage, read> uvFaceTable: array<vec2<u32>>`
- `let entry = uvFaceTable[key.originalIndex]` (indirect-lookup invariant)
- `for (var i: u32 = 0u; i < vertCount` (loop pattern)
- Absence of `sceneUvCoords[uvBase + 0u]` (legacy fixed-stride pattern gone)

This is the AC6 surrogate (per discovery decision #4 — extended regex, no
Naga subprocess).

### Step 9 — RenderCommandTriangulator ear-clipping (omnibus from discovery #9)

In `RenderCommandTriangulator.kt`:
- Add `private fun earClipTriangulate(points: List<Point>): List<IntArray>`
  (~50 lines). Standard O(n²) algorithm: while `points.size > 3`, find an
  ear (vertex whose triangle is convex and contains no other vertex), emit
  it, remove the ear vertex, repeat.
- Replace the fan loop at lines 75-91 with: `if (isConvex(points))
  emitFan(points) else emitEarClip(points)`. Convex check = O(n) winding
  consistency. Convex faces stay on the fast path; only non-convex faces
  pay the O(n²) cost.
- Test fixtures in `RenderCommandTriangulatorTest.kt`: stairs zigzag at
  stepCount = 2, 5, 10; synthetic L-shape; existing convex regression tests
  (must still pass).

### Step 10 — Sample app + Maestro fixtures

`TexturedDemoActivity.kt` `TexturedCylinderScene`: third column with
`Shape(geometry = Cylinder(position = Point(0, 0, 0), radius = 0.5, height =
1.5, vertices = 24), material = cylinderTextured, scale = 2.5)`. Update the
Cylinder tab `description` to call out the 24-vertex demo.

Stairs and Knot need permanent tabs to drive the existing Maestro flows
without temp-fixture revert dance. Add:
- `ShapeTab.Stairs` enum entry + `TexturedStairsScene` (stepCount = 5, two
  staircases with grass/brick/dirt + per-face palette).
- `ShapeTab.Knot` enum entry + `TexturedKnotScene` (brick + grass — same
  layout used during verify-knot, but committed permanently here).

Maestro flows:
- `verify-cylinder.yaml`: add a 5th screenshot at the new 24-vert variant;
  bump tab-tap coordinates if scrolling moved.
- `verify-stairs.yaml`: extend with stepCount=5 fixture; coordinate-tap
  Stairs tab now stable since it's permanent.
- `verify-knot.yaml`: simplify — no swipe needed once Knot is permanent in
  tab list (provided it stays in the visible tab range; if 6+ tabs require
  scrolling, keep the swipe).

### Step 11 — Paparazzi + benchmark fix

`IsometricCanvasSnapshotTest.kt`: add three new test methods:
- `cylinderTextured24` — `Shape(Cylinder(vertices = 24), texturedBitmap(brick))`
- `stairsTextured` — `Shape(Stairs(stepCount = 5), perFace { tread = grass; riser = brick; side = dirt })`
- `knotTextured` — `Shape(Knot(), texturedBitmap(brick))`

Per discovery decision #7: do NOT regenerate the 13 uncommitted baselines.
Document this as a pre-existing baseline-coverage gap in the verify plan
below; AC4 evaluation explicitly scoped to changed shapes only.

`BenchmarkScreen.kt:165`: change `color = item.color` to `material =
SolidColor(item.color)` (or whichever `MaterialData` constructor matches the
post-`api-design-fixes` shape). Verify with
`./gradlew :isometric-benchmark:compileDebugKotlin` — should now succeed,
unblocking `./gradlew check` aggregate.

## Test / Verification Plan

### Automated checks

- **Lint + typecheck:** `./gradlew :isometric-webgpu:compileDebugKotlin
  :isometric-webgpu:compileReleaseKotlin :isometric-benchmark:compileDebugKotlin
  :app:compileDebugKotlin`
- **Unit tests:**
  - `:isometric-webgpu:testDebugUnitTest` — must pass updated
    `TriangulateEmitShaderTest`, `TriangulateEmitShaderUvTest`,
    `SceneDataPackerTest`, `RenderCommandTriangulatorTest`, and new
    `GpuUvCoordsBufferTest` (AC5 contract tests).
  - `:isometric-shader:testDebugUnitTest` — regression-only; the existing
    11 + 11 + 15 + 15 UV-generator tests across cylinder/pyramid/stairs/knot
    must remain green.
  - `:isometric-compose:testDebugUnitTest` + `testReleaseUnitTest` —
    Paparazzi will record baselines for the 3 new methods first run.
- **apiCheck:** `:isometric-{core,shader,compose,webgpu}:apiCheck` — must
  show zero diff (AC7). The buffer is `internal class`; the new `UvFaceTablePacker`
  is `internal class`; binding numbers are not API.
- **Aggregate:** `./gradlew check` — should now succeed end-to-end including
  `:isometric-benchmark` thanks to the omnibus repair.

### Interactive verification (human-in-the-loop)

For AC1 (cylinder cap parity) / AC2 (stairs zigzag parity) / AC3 (knot
parity):

- **Platform & tool:** Android emulator-5554 + Maestro + adb screencap.
- **Steps:**
  1. `./gradlew :app:installDebug`
  2. Run extended Maestro flows: `maestro test .maestro/verify-cylinder.yaml`,
     `maestro test .maestro/verify-stairs.yaml`,
     `maestro test .maestro/verify-knot.yaml`.
  3. Each flow captures Canvas / Full WebGPU / Canvas+GPU Sort / cycle-back
     (4 screenshots × 3 flows = 12 total).
- **Evidence capture:** `.ai/workflows/texture-material-shaders/verify-evidence/screenshots/verify-{cylinder,stairs,knot}-*.png` (overwriting the existing knot-pass-1 set with the
  new permanent-tab versions).
- **Pass criteria:**
  - **AC1:** `verify-cylinder-webgpu.png` shows the 24-vert cap as a full
    brick disk (no partial wedge). Pixel-comparable to
    `verify-cylinder-canvas.png` for the same fixture (within < 1% emulator
    jitter).
  - **AC2:** `verify-stairs-webgpu.png` at stepCount = 5 shows zigzag side
    faces with full UV pattern (no truncation, no diagonal triangulation
    slash from the now-fixed I-2). Pixel-comparable to Canvas.
  - **AC3:** `verify-knot-webgpu.png` shows brick wrapping all visible faces
    (including any that emit > 6 UV pairs). Pixel-comparable to Canvas.

### AC6 — WGSL static validation

Achieved via extended regex/structural assertions in `TriangulateEmitShaderTest`
+ `TriangulateEmitShaderUvTest` (per discovery decision #4). Listed in Step 8.
Documented as "structural validation" not "compile validation" — runtime
shader-compile errors still surface only at `GpuContext` init time on device.

### AC7 — apiCheck

`./gradlew :isometric-{core,shader,compose,webgpu}:apiCheck`. Expected: zero
diff. If anything leaks (e.g., a public constructor on the new
`UvFaceTablePacker`), document the delta and either narrow the visibility
or note the new public surface explicitly.

## Risks / Watchouts

### Risk 1 — `maxStorageBuffersPerShaderStage` rejection on baseline mobile
- **Severity:** HIGH for compat-mode devices; LOW on Vulkan path (default 8).
- **Trigger:** Adapter on OpenGL ES 3.1 compat-mode device reports max = 4.
- **Mitigation:** Step 6 fail-loud at init with clear error message naming
  the slice and the limit. Per discovery decision #2, the user explicitly
  rejected dual-path fallback — the tradeoff is accepted.

### Risk 2 — `androidx.webgpu` alpha04 API drift
- **Severity:** MEDIUM. The library is at `1.0.0-alpha04` (Feb 2026) with
  documented breaking changes across alpha releases (builder-only
  constructors, descriptor renames, callback consolidation).
- **Mitigation:** Cross-reference all binding-descriptor field names against
  `vendor/androidx-webgpu/` snapshot before committing. Implement step by
  step using `./gradlew :isometric-webgpu:compileDebugKotlin` after each
  bindings change.

### Risk 3 — `slot i ↔ originalIndex = i` invariant violation
- **Severity:** HIGH if violated; produces silently-wrong UVs across the
  scene with no error.
- **Mitigation:** AC5 unit tests pin this for heterogeneous faceVertexCount
  inputs. Specifically, a test scene with `commands = [(N=4), (N=24), (N=4),
  (N=8)]` validates that `table[i]` correctly indexes `commands[i]`'s pool
  region for every i, and that the pool offsets are monotonically increasing
  by `N[i-1]`.

### Risk 4 — TransformedFace struct alignment / WGSL padding
- **Severity:** MEDIUM. WGSL imposes alignment rules on structs in storage
  buffers. Going from 6×vec2 (96 bytes) to 24×vec2 (192 bytes raw) — but
  WGSL may add padding if the struct includes other fields with stricter
  alignment.
- **Mitigation:** Read the `TransformedFace` struct in full during
  Step 1; verify any non-vec2 fields and their alignment requirements.
  Update `TRANSFORMED_FACE_BYTES` constant to the actual WGSL-computed
  struct size, not the naive `24 × 8` math. Web-research finding: storage
  buffer arrays use `alignOf(T)` per element, not the 16-byte uniform
  minimum, so `array<vec2<f32>>` packs at 8-byte stride without padding.

### Risk 5 — Bind-group cache invalidation on binding count change
- **Severity:** MEDIUM. Going 7→8 bindings invalidates any cached
  `GPUBindGroupLayout` and dependent `GPUPipelineLayout`. Mismatched bind
  groups silently fail validation in non-debug WebGPU builds.
- **Mitigation:** Per web-research: use **explicit** `GPUPipelineLayout` with
  named `GPUBindGroupLayout` objects; never `layout: 'auto'` for shared
  pipelines. Audit all `setBindGroup` call sites for the M5 pipeline; update
  atomically in the same commit as the layout change. Identity-compare both
  `lastUvCoordsBuffer` AND `lastUvOffsetBuffer` in `ensureBuffers()`.

### Risk 6 — Per-frame packing cost
- **Severity:** LOW. Worst-case packing walk is O(totalVertices) vs current
  O(faceCount × 12). For scenes with 1000 faces × 24 verts = 24K floats vs
  current 12K, the doubling is negligible at frame budget scale.
- **Mitigation:** No benchmark required this slice. If frame profiling
  surfaces packing as a bottleneck post-ship, file a follow-up perf slice.

### Risk 7 — Stairs/Knot tabs added permanently to TexturedDemoActivity
- **Severity:** LOW. Tab order changes shift coordinate-tap targets in
  `verify-cylinder.yaml`. Maestro flows currently use percentage coordinates
  derived empirically.
- **Mitigation:** After installing the rebuilt sample, run `uiautomator
  dump` to re-derive tab coordinates for ALL flows; update each
  `.maestro/verify-*.yaml` in lockstep. Estimated impact: 6 lines per flow.

### Risk 8 — Ear-clipping cost on convex faces (regression)
- **Severity:** LOW with mitigation. Ear-clipping is O(n²); fan is O(n).
- **Mitigation:** Step 9 explicitly fast-paths convex polygons via the
  cheap O(n) winding check; only non-convex faces pay the O(n²) cost. All
  current shapes except Stairs zigzag and possible Knot ring-faces are
  convex.

### Risk 9 — Paparazzi baseline gap
- **Severity:** LOW (accepted per discovery decision #7).
- **Mitigation:** Document explicitly in `06-verify-webgpu-ngon-faces.md`
  that AC4 is scoped to **changed shapes** only. Pre-existing baseline
  coverage gap (only 2 of ~15 committed) is out of scope. New snapshots
  for cylinder24/stairs5/knot serve as the regression guard.

## Dependencies on Other Slices

| Slice | Type | Provides |
|-------|------|----------|
| `uv-generation-cylinder` | hard (verify-pass-3) | `UvGenerator.forCylinderFace` emits N-vertex cap UV arrays this slice packs |
| `uv-generation-stairs` | hard (review-ship-with-caveats) | Zigzag side UV emission — proves AC2 |
| `uv-generation-knot` | hard (review-ship-with-caveats) | Per-face UV emission — proves AC3 |
| `api-design-fixes` | transitive (complete) | Shape composable's `material:` parameter — needed for the BenchmarkScreen.kt fix |
| `webgpu-textures` | foundation (complete) | The M5 emit shader this slice modifies |
| `uv-generation-shared-api` | hard (verify-complete) | `RenderCommand.faceVertexCount` field — authoritative count source for the table |

All dependencies satisfied. Slice ready to implement.

## Assumptions

1. `androidx.webgpu` alpha04 is the active version and the `requiredLimits`
   API on `GPUDeviceDescriptor` accepts `maxStorageBuffersPerShaderStage`
   exactly as named.
2. The emulator-5554 (`Medium_Phone_API_36.0`) advertises Vulkan path with
   `maxStorageBuffersPerShaderStage >= 8` — must verify on first boot
   post-install.
3. `RenderCommandTriangulator.kt` is on the CPU code path only and is
   exercised by tests + Canvas fallback, not the M5 GPU pipeline (confirmed
   by sub-agent — fan loop at lines 75-91 writes `u = 0f, v = 0f` defaults,
   not GPU path's UV).
4. `BenchmarkScreen.kt:165`'s `item.color` is an `IsoColor`, and the
   `MaterialData`-style replacement is the equivalent `SolidColor(item.color)`
   or whatever the post-`api-design-fixes` constructor names it. Verify the
   exact constructor at implement time by reading `Material.kt`.
5. `IsometricCanvasSnapshotTest`'s Paparazzi rule generates fresh baselines
   for new test methods on first record-mode run; existing committed
   baselines remain untouched.
6. The slice's PR will land via the existing `feat/texture` branch (PR #8)
   per the workflow's `branch-strategy: dedicated`.

## Blockers

None. All hard dependencies (cylinder/stairs/knot UV generators) are
implemented and verified.

## Freshness Research

- **`androidx.webgpu` 1.0.0-alpha04** (released Feb 11, 2026 — verified via
  AndroidX release notes). Documented breaking changes: builder-pattern-only
  constructors (alpha04), callback interface consolidation (alpha03), all
  `GPU*` descriptor renames (alpha02), minSdk = 24 (alpha02). No
  storage-buffer-specific bugs in the changelog. Library docs noted as
  "generated utilizing Google Gemini and may contain errors" — vendor
  snapshot is authoritative.
- **WGSL `array<vec2<u32>>` alignment:** 8-byte aligned, 8-byte element
  stride in `var<storage, read>`. Strictly preferable to two parallel u32
  arrays (one binding vs two; one 8-byte read vs two). Source:
  webgpufundamentals.org Storage Buffers article.
- **WebGPU storage buffer size:** `maxStorageBufferBindingSize` default = 128
  MiB. Slice usage at 10K faces × 24 verts × 8 bytes = 1.92 MB pool + 80 KB
  table — trivially safe across all target hardware. Source: MDN
  GPUSupportedLimits.
- **`maxStorageBuffersPerShaderStage` compat-mode trap:** Spec default = 8
  on core profile. Compat mode (OpenGL ES 3.1, baseline mobile) defaults to
  4 and is the active discussion in gpuweb/gpuweb #5103. Mitigation: Step 6
  explicit `requiredLimits` request, fail-loud per discovery decision #2.
- **Bind-group invalidation when binding count changes:** Documented at
  toji.dev — "Bind Groups" best-practices article. Always use explicit
  pipeline layouts; never `layout: 'auto'`.
- **`minBindingSize` perf optimization:** Set `minBindingSize` on layout
  entries to shift validation cost from per-dispatch to bind-group-creation
  time. Material at high frame rates.
- **Out-of-bounds storage buffer reads:** WebGPU runtime guarantees clamped
  behavior (not UB). Software guards optional for memory safety; useful for
  debug parity. Source: SafeRace OOPSLA 2025; gpuweb/gpuweb #1202.
- **Stairs verify I-2 root cause:** confirmed at
  `RenderCommandTriangulator.kt:75-91` — triangle-fan from `v0` valid only
  for convex polygons. Ear-clipping is the textbook general-case fix
  (O(n²), public-domain algorithm, ~50 lines).

## Revision History

*(none — initial plan)*

## Recommended Next Stage

- **Option A (default, recommended):** `/wf-implement texture-material-shaders webgpu-ngon-faces`
  — plan is complete, dependencies all satisfied, sequencing is bottom-up
  with each step independently compilable. Estimated effort: 1.5-2 days
  (omnibus scope adds ~3 hours over the core GPU pipeline rewrite).
  Consider `/compact` first — discovery interview, web research, and code
  exploration are noise for implementation. PreCompact hook will preserve
  workflow state.
- **Option B:** `/wf-slice texture-material-shaders` — split the omnibus
  scope back into separate slices (core GPU rewrite + I-2 ear-clipping +
  benchmark fix as 3 sequential slices). Use only if the omnibus blast
  radius feels uncomfortable on second look.
- **Option C:** `/wf-shape texture-material-shaders` — **not applicable.**
  Spec is sound, ACs are testable, no contradictions surfaced during
  planning.
