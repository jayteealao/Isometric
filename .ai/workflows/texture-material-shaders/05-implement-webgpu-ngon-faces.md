---
schema: sdlc/v1
type: implement
slug: texture-material-shaders
slice-slug: webgpu-ngon-faces
status: partial
stage-number: 5
created-at: "2026-04-22T21:18:56Z"
updated-at: "2026-04-22T21:18:56Z"
metric-files-changed: 7
metric-lines-added: 530
metric-lines-removed: 33
metric-deviations-from-plan: 1
metric-review-fixes-applied: 0
commit-sha: "04afdd9"
implementation-mode: partial-commit-a-only
tags: [webgpu, uv, ngon, omnibus, ear-clipping, partial]
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
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders webgpu-ngon-faces"
---

# Implement: webgpu-ngon-faces (Commit A — omnibus prep)

## Summary of Changes

This implementation pass landed **Commit A only** — the independent omnibus
items from the slice's plan. The atomic GPU pipeline rewrite (Commit B —
Steps 1-8 of the plan) is **deferred to a follow-up `/wf-implement` pass**
in a fresh context window where each WGSL/struct/binding change can receive
the careful, calibrated attention it requires. This split was selected
mid-implement after reading the actual GPU shader code and recognizing the
risk profile of unverifiable shader changes.

**Commit A (`04afdd9`, +530/-33 lines, 7 files)** delivers:

1. **Ear-clipping triangulation** in `RenderCommandTriangulator` — fixes
   stairs verify I-2 (non-convex fan defect at line 75 of the pre-commit
   file). Convex polygons stay on the existing O(n) triangle-fan fast
   path; non-convex (Stairs zigzag side faces, possible future Knot ring
   faces) use a new O(n²) ear-clipping algorithm. ~120 LOC across
   `isConvex()`, `earClipTriangulate()`, `isEar()`, `pointInTriangle()`
   helpers.
2. **Benchmark API drift repair** — `BenchmarkScreen.kt:165` one-line
   `Shape(color = item.color) → Shape(material = item.color)` change
   (IsoColor implements MaterialData). Clears pre-existing I-1 across
   the workflow; `:isometric-benchmark:compileDebugKotlin` now green;
   `./gradlew check` aggregate unblocked.
3. **Sample app fixtures** — `TexturedDemoActivity` gains permanent
   `Stairs` (5th tab, stepCount=5 with PerFace.Stairs textured + per-face
   palette pair) and `Knot` (6th tab, brick + grass) ShapeTab entries.
   `TexturedCylinderScene` expanded from 2 columns to 3, with the new
   third column running `vertices = 24` to validate AC1 of the slice
   (cylinder cap parity at N>6) once Commit B's GPU pipeline rewrite
   lands.
4. **Maestro flow updates** — `verify-cylinder.yaml` updated for the new
   24-vert assertion and tab layout; `verify-stairs.yaml` and
   `verify-knot.yaml` (both formerly untracked from prior verify passes)
   committed with descriptions reflecting the permanent tab positions and
   stepCount=5 fixture.
5. **3 new RenderCommandTriangulator unit tests** — L-shape (6-vert
   non-convex), Stairs zigzag stepCount=3 (8-vert), convex hexagon
   regression guard. All 4 tests pass; demonstrates the convex fast-path
   is preserved while non-convex shapes are now correctly triangulated.

## Files Changed (Commit A)

| File | +/− | Action |
|------|-----|--------|
| `isometric-webgpu/.../triangulation/RenderCommandTriangulator.kt` | +145 / −7 | MODIFY — convex fast-path + ear-clipping algorithm |
| `isometric-webgpu/.../triangulation/RenderCommandTriangulatorTest.kt` | +120 / −0 | MODIFY — 3 new test cases |
| `isometric-benchmark/.../BenchmarkScreen.kt` | +1 / −1 | MODIFY — Shape API drift fix |
| `app/.../sample/TexturedDemoActivity.kt` | +148 / −20 | MODIFY — Stairs+Knot tabs, cylinder24 column |
| `.maestro/verify-cylinder.yaml` | +9 / −5 | MODIFY — updated descriptions |
| `.maestro/verify-stairs.yaml` | +50 / −0 | CREATE — was untracked from stairs verify |
| `.maestro/verify-knot.yaml` | +57 / −0 | CREATE — was untracked from knot verify |

## Shared Files (also touched by sibling slices)

- `RenderCommandTriangulator.kt` — was previously CPU triangle-fan only;
  now ear-clipping for non-convex. The change is backward-compatible for
  all current shapes whose face polygons are convex (Prism quads, Pyramid
  triangles, Cylinder ring quads, Octahedron triangles, Knot quads).
- `TexturedDemoActivity.kt` — was previously 4 tabs; now 6. The existing
  Prism/Octahedron/Pyramid/Cylinder behavior is unchanged. Stairs and Knot
  tabs are additive.

## Commit B — Deferred Work (Steps 1-8 of plan + AC5 test)

**Why deferred:** The atomic GPU pipeline rewrite spans 5 files in
lockstep — `GpuUvCoordsBuffer`, `SceneDataPacker`, `TransformCullLightShader`
WGSL struct, `TriangulateEmitShader` WGSL UV-fetch + emit logic, and
`GpuTriangulateEmitPipeline` bind-group. Each change has silent-runtime-failure
modes (struct alignment errors, bind-group cache mismatches, indirect-lookup
boundary bugs) that I cannot verify without on-device GPU testing. Fresh
context window will give each change the precision it needs.

**Concrete deferred work:**

1. **Constant expansion** (`SceneDataPacker.SceneDataLayout` companion):
   - `MAX_FACE_VERTICES = 24`
   - `FACE_DATA_BYTES`: 144 → 432 (24 verts × 16 bytes + 48 bytes
     baseColor/normal/textureIndex/faceIndex/padding)
   - `TRANSFORMED_FACE_BYTES`: 96 → 240 (24 × vec2<f32> + 16 vertexCount/pad
     + 16 litColor + 16 depthKey/faceIndex/visible/pad)

2. **WGSL struct refactor — `FaceData` and `TransformedFace`:**
   - Rename `v0..v5` → `v: array<vec3<f32>, 24>` with `vertexCount: u32`
     packed into v23's pad slot or moved to a dedicated field at end.
   - Rename `s0..s5` → `s: array<vec2<f32>, 24>` similarly.
   - This loop-ifies all per-vertex shader code (project, frustum cull,
     bbox, depth-key sum, result write).

3. **`SceneDataPacker.packInto` byte writes** — lift `pts3d.size.coerceAtMost(6)`
   to `coerceAtMost(MAX_FACE_VERTICES)`; expand the `for (i in 0 until 6)`
   loop to write 24 vertex slots (with the `vertexCount` field landing in
   slot 23's pad rather than slot 5's).

4. **`GpuUvCoordsBuffer` rewrite** — dual-buffer:
   - `poolBuffer: GrowableGpuStagingBuffer(entryBytes = 8)` — flat
     `array<vec2<f32>>` UV pool. Sized by `sumOf { faceVertexCount } × 8`.
   - `tableBuffer: GrowableGpuStagingBuffer(entryBytes = 8)` — `array<vec2<u32>>`
     offset+count entries. Sized by `faceCount × 8`.
   - Walk `scene.commands`: write `(currentOffset, vertCount)` into table[i],
     write `vertCount` UV pairs into pool starting at currentOffset, advance.
   - Default fallback: 4 UV pairs `(0,0)(1,0)(1,1)(0,1)` into pool;
     `table[i] = (currentOffset, 4)`.

5. **`UvFaceTablePacker.kt`** — new internal class mirroring `UvRegionPacker.kt`
   pattern (in `texture/` directory). Owns the offset+count table buffer.

6. **Bind-group layout** in `GpuTriangulateEmitPipeline.ensurePipelines()`:
   - Binding 6 changes type: `array<vec4<f32>>` → `array<vec2<f32>>` (flat pool)
   - Binding 7 NEW: `array<vec2<u32>>` (offset+count table)
   - Set `minBindingSize = 8` on both new entries
   - Add `lastUvOffsetBuffer: GPUBuffer?` field for identity-compare cache

7. **`GpuFullPipeline.kt`** — thread the second buffer through `ensureBuffers`
   and the M5 dispatch site.

8. **`GpuContext` device init** — request
   `requiredLimits = mapOf("maxStorageBuffersPerShaderStage" to 8L)`. Throw
   `IllegalStateException` with clear webgpu-ngon-faces error if adapter
   rejects (per discovery decision #2 fail-loud).

9. **`TriangulateEmitShader.kt` WGSL rewrite:**
   - Add binding 7 declaration
   - Replace lines 291-304 (fixed UV unpack) with indirect lookup loop:
     `let entry = uvFaceTable[key.originalIndex]; for (i in 0..entry.y) { uv[i] = uvRegion.userMatrix * vec3(uvPool[entry.x + i], 1.0).xy; }`
   - Expand triangle emit to a loop over `vertCount - 2` triangles using
     fan indices `(0, i, i+1)`
   - Bump `MAX_TRIANGLES_PER_FACE = 22`, `MAX_VERTICES_PER_FACE = 66`

10. **WGSL structural tests** (`TriangulateEmitShaderTest`,
    `TriangulateEmitShaderUvTest`) — regex assertions for new binding 6+7
    signatures, indirect lookup pattern, absence of legacy `sceneUvCoords[uvBase + 0u]`.

11. **`GpuUvCoordsBufferTest.kt`** (new) — AC5 contract tests:
    - Heterogeneous face UV packing (commands with mixed vertCount = 4, 8, 24)
    - `slot i ↔ originalIndex = i` invariant under that heterogeneity
    - Default fallback for `uvCoords == null`
    - Empty-scene / zero-face edge case
    - Pool offsets monotonically increasing by `prev_vertCount`

## Notes on Design Choices

- **Ear-clipping algorithm choice (per discovery #9):** Standard O(n²)
  ear-clipping was selected over shape-aware Stairs decomposition. The
  former generalizes to any future non-convex face polygon; the latter
  would solve only the documented Stairs case. The convex fast-path
  preserves O(n) cost for all current convex shapes — only the actual
  non-convex faces (Stairs zigzag at stepCount ≥ 2) pay the O(n²) cost.
- **Two-commit split (per follow-up clarification):** The plan specified
  atomic-lift across all 11 steps. The implementation pass discovered
  mid-stream that the GPU pipeline rewrite (Steps 1-8) genuinely requires
  fresh-context care due to unverifiable WGSL changes. Splitting Commit A
  (CPU triangulator + sample + benchmark + Maestro) and Commit B (atomic
  GPU pipeline) preserves the slice's intent while reducing blast-radius
  per commit.
- **Sample app permanent-tab decision:** Stairs and Knot tabs were added
  permanently rather than as temp fixtures (the verify-stage pattern used
  by previous slices). Rationale: post-slice these shapes are fully
  texture-supported and worth showcasing; the Maestro flows now have
  stable tab positions to coordinate-tap without per-run UI dump.

## Deviations from Plan

1. **Two-commit split.** Plan specified single atomic commit across all 11
   steps. Implementation pass split into Commit A (omnibus + sample +
   triangulator) and Commit B (deferred GPU rewrite). Reason: WGSL changes
   carry silent-runtime-failure risk that warrants fresh context. Plan's
   atomic-lift contract is preserved within Commit B alone.
2. **Paparazzi snapshot additions deferred.** Plan Step 11 called for
   adding `cylinderTextured24` / `stairsTextured-stepCount5` /
   `knotTextured` snapshots in `IsometricCanvasSnapshotTest`. These need
   record-mode runs that can be done in either commit — deferred to
   Commit B for cohesion with the GPU changes that affect rendering.

## Anything Deferred

All Commit B work — see "Commit B — Deferred Work" section above.

The 11 deferred items will land in a follow-up `/wf-implement
texture-material-shaders webgpu-ngon-faces` pass. The next pass should
start with `/compact` to drop the Commit A planning/discovery context,
then proceed bottom-up: constants → struct WGSL → packer → pool/table →
binding 7 → shader UV-fetch → tests.

## Known Risks / Caveats

- **AC1/AC2/AC3 untestable until Commit B lands.** The sample app fixtures
  (24-vert cylinder, stepCount=5 stairs, knot) are now in place but will
  still render with the pre-slice partial-wedge / truncation behavior on
  Full WebGPU until Commit B lifts the `GpuUvCoordsBuffer` 6-vertex cap.
  Verify pass for Commit A alone would document this expected state.
- **Ear-clipping does NOT affect WebGPU rendering.** The
  `RenderCommandTriangulator` lives on the CPU code path (Canvas
  fallback + tests). The GPU M5 emit shader has its own triangulation
  logic that is unchanged in Commit A. So stairs verify I-2 (the visible
  WebGPU defect) is NOT cleared by Commit A — it requires Commit B's
  shader rewrite. Commit A clears I-2 only on the CPU path.
- **`apiCheck` not affected.** Both `RenderCommandTriangulator` and
  `BenchmarkScreen` are non-public surfaces; `apiCheck` shows zero diff
  this commit.
- **`./gradlew check` aggregate now unblocks** thanks to the benchmark
  fix. This is a workflow-wide quality-of-life improvement carried by
  this commit.

## Freshness Research

None new this pass — relied on the freshness research from
`04-plan-webgpu-ngon-faces.md` (androidx.webgpu alpha04, WGSL alignment,
storage buffer limits, bind-group invalidation, ear-clipping standard
algorithm). The plan's research was current as of 2026-04-22 and remains
authoritative.

## Recommended Next Stage

- **Option A (default, recommended):** `/compact` then
  `/wf-implement texture-material-shaders webgpu-ngon-faces` — continue
  the slice with Commit B (the GPU pipeline rewrite). Fresh context
  enables careful WGSL/struct/binding work. Implementation order:
  bottom-up per plan §Step-by-Step Plan (constants → WGSL structs →
  packer → buffer → binding 7 → shader → tests). Estimated 1-1.5 days
  in a fresh session.
- **Option B:** `/wf-verify texture-material-shaders webgpu-ngon-faces`
  — verify Commit A alone. Will document partial AC coverage: ear-clipping
  + benchmark + sample fixtures verified; AC1/AC2/AC3 (WebGPU parity)
  intentionally NOT MET pending Commit B. Useful if you want a
  checkpoint commit before resuming.
- **Option C:** `/wf-extend texture-material-shaders from-implement` —
  formalize Commit B's deferred work as its own follow-on slice (e.g.,
  `webgpu-ngon-faces-shader-rewrite`). Cleaner separation but adds
  workflow ceremony.
- **Option D:** `/wf-amend texture-material-shaders from-implement` —
  amend the slice scope to permanently exclude the GPU rewrite. **Not
  recommended** — the GPU rewrite IS the headline value of this slice;
  splitting it out would be a slice-redefinition.
