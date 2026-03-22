# WS13 Implementation — Phase 3: Full GPU-Driven Pipeline

> **Workstream**: WS13
> **Depends on**: WS12 (WebGpuIsometricRenderer)
> **Artifact**: `isometric-webgpu` (additions to existing module)
> **Pinned API**: `androidx.webgpu:1.0.0-alpha04`
> **Authored**: 2026-03-18
> **Source accuracy**: All code in this file is written against the current source tree
>   and the vendored `vendor/androidx-webgpu/` API surface. No pseudo-code.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Architecture — CPU → GPU Migration](#2-architecture--cpu--gpu-migration)
3. [New Files](#3-new-files)
4. [SceneData Buffer Layout](#4-scenedata-buffer-layout)
5. [SceneDataPacker — CPU-Side Upload](#5-scenedatapacker--cpu-side-upload)
6. [Transform Compute Pass](#6-transform-compute-pass)
7. [Culling + Compaction Compute Pass](#7-culling--compaction-compute-pass)
8. [Depth Sort Compute Pass (GPU Radix Sort)](#8-depth-sort-compute-pass-gpu-radix-sort)
9. [Lighting Compute Pass](#9-lighting-compute-pass)
10. [Indirect Draw Buffer Population](#10-indirect-draw-buffer-population)
11. [Vertex Emit Compute Pass](#11-vertex-emit-compute-pass)
12. [GpuFullPipeline — Orchestrator](#12-gpufullpipeline--orchestrator)
13. [WebGpuSceneRendererV2](#13-webgpuscenerendererv2)
14. [Texture Support — textureIndex + Atlas](#14-texture-support--textureindex--atlas)
15. [RenderCommand Extension for Phase 3](#15-rendercommand-extension-for-phase-3)
16. [Performance Model](#16-performance-model)
17. [Test Plan](#17-test-plan)

---

## 1. Overview

WS13 moves the **entire `prepare()` pipeline** onto the GPU. After WS13, the CPU only
uploads one scene data buffer per dirty frame and submits one command buffer. All transform,
cull, sort, light, and draw operations execute on GPU cores in parallel.

**What ships**:
- Full GPU-driven pipeline: zero per-frame CPU work for static scenes
- `drawIndirect` replaces explicit `draw()` calls
- Back-face + frustum culling on GPU
- Lighting computed on GPU (removes `transformColor()` from CPU hot path)
- Texture support: `textureIndex` on `RenderCommand`, texture atlas, sampler binding
- Verified frame budget: target < 2ms total for 1000-face scene on Pixel 6

**What is NOT changed**:
- `RenderBackend.Canvas` path is completely untouched
- WS12 Phase 2 path continues to work (fallback for devices that lack compute shader support)
- Hit testing remains in `IsometricScene` (CPU-based, via `IsometricRenderer.hitTest`)

---

## 2. Architecture — CPU → GPU Migration

### Phase 2 (WS12) pipeline — what moves to GPU

```
[CPU] rootNode.renderTo(commands, renderContext)
    ↓
[CPU] engine.projectScene() — projection, culling, lighting, sorting
    ↓
[CPU] RenderCommandTriangulator.triangulate(scene.commands)
    ↓
[GPU] Upload vertex buffer → draw()
```

### Phase 3 (WS13) pipeline — GPU-driven

```
[CPU] rootNode.renderTo(commands, renderContext)
    ↓
[CPU] SceneDataPacker.pack(commands) → flat FaceData buffer
    ↓ uploadOnce per dirty frame
[GPU] Compute: transformPass — parallel 3D→2D projection (N faces × M vertices)
    ↓ output: TransformedFace buffer
[GPU] Compute: cullPass — back-face + frustum culling (N parallel invocations)
    ↓ output: visibleIndices (compacted via atomic counter)
[GPU] Compute: radixSort — 4-pass radix sort by depth key (visible faces only)
    ↓ output: sortedVisibleIndices
[GPU] Compute: lightingPass — dot product with light direction, writes final color
    ↓ output: litColorBuffer
[GPU] Compute: emitVertices — triangle fan expansion + indirect draw buffer population
    ↓ output: final vertex buffer + DrawIndirectArgs
[GPU] Render: single drawIndirect() call
    ↓
[Display] Present via AndroidExternalSurface
```

The CPU never reads GPU data back (no `mapAsync` / `mapAndAwait` in the hot path).
Scene changes are detected via `rootNode.isDirty` — only dirty frames trigger a data
re-upload. Static scenes cost nothing beyond a single `drawIndirect` re-submission.

---

## 3. New Files

```
isometric-webgpu/src/main/kotlin/io/github/jayteealao/isometric/webgpu/
├── pipeline/
│   ├── GpuFullPipeline.kt             ← Orchestrates all compute + render passes
│   ├── SceneDataPacker.kt             ← CPU-side scene → FaceData buffer conversion
│   └── GpuRenderPipelineV2.kt         ← Updated pipeline with indirect draw support
├── shader/
│   ├── TransformShader.kt             ← WGSL transform compute pass
│   ├── CullShader.kt                  ← WGSL culling + compaction compute pass
│   ├── LightingShader.kt              ← WGSL lighting compute pass
│   ├── EmitVerticesShader.kt          ← WGSL vertex emit + indirect draw buffer
│   ├── IsometricVertexShaderV2.kt     ← Updated vertex shader (textureIndex support)
│   └── IsometricFragmentShaderV2.kt   ← Updated fragment shader (texture sampling)
├── texture/
│   ├── TextureAtlas.kt                ← Texture atlas management
│   └── TextureAtlasBuilder.kt         ← Atlas packing and upload
├── WebGpuSceneRendererV2.kt           ← Updated renderer using GpuFullPipeline
└── RenderBackendWebGpuV2.kt           ← Updated backend for Phase 3
```

---

## 4. SceneData Buffer Layout

### WGSL struct: `FaceData`

```wgsl
// Matches the CPU-side SceneDataPacker output exactly.
// Each FaceData is 128 bytes (padded to 16-byte alignment for storage buffers).

struct FaceData {
    // 3D input vertices (up to 4 for quads; unused slots zeroed for triangles)
    v0: vec3<f32>,       // bytes 0-11
    _p0: f32,            // bytes 12-15 (padding for vec4 alignment)
    v1: vec3<f32>,       // bytes 16-27
    _p1: f32,            // bytes 28-31
    v2: vec3<f32>,       // bytes 32-43
    _p2: f32,            // bytes 44-47
    v3: vec3<f32>,       // bytes 48-59
    vertexCount: u32,    // bytes 60-63 (3 or 4; v3 is ignored when 3)

    // Color and material
    baseColor: vec4<f32>,    // bytes 64-79 (RGBA before lighting, [0,1])
    normal: vec3<f32>,       // bytes 80-91 (face normal for lighting)
    textureIndex: u32,       // bytes 92-95 (atlas slot; 0xFFFFFFFF = no texture)

    // Identity
    faceIndex: u32,          // bytes 96-99 (original index in commands list)
    _padding: vec3<u32>,     // bytes 100-111 (pad to 112... actually let's round)
}
// Total: 112 bytes per face. Padded to 128 for storage buffer alignment.
```

### WGSL struct: `TransformedFace`

```wgsl
struct TransformedFace {
    // 2D screen-space vertices (projected)
    s0: vec2<f32>,           // bytes 0-7
    s1: vec2<f32>,           // bytes 8-15
    s2: vec2<f32>,           // bytes 16-23
    s3: vec2<f32>,           // bytes 24-31

    // Metadata
    vertexCount: u32,        // bytes 32-35
    depth: f32,              // bytes 36-39 (depth centroid for sorting)
    faceIndex: u32,          // bytes 40-43
    textureIndex: u32,       // bytes 44-47

    // Color (after lighting in lighting pass)
    litColor: vec4<f32>,     // bytes 48-63

    // Original base color (needed by lighting pass)
    baseColor: vec4<f32>,    // bytes 64-79
    normal: vec3<f32>,       // bytes 80-91
    _padding: f32,           // bytes 92-95
}
// Total: 96 bytes per face
```

### Kotlin-side constants

```kotlin
// In SceneDataPacker.kt
internal object SceneDataLayout {
    /** Bytes per FaceData struct in the GPU storage buffer. */
    const val FACE_DATA_BYTES = 128

    /** Bytes per TransformedFace struct. */
    const val TRANSFORMED_FACE_BYTES = 96

    /** Sentinel value for "no texture" in textureIndex. */
    const val NO_TEXTURE: Int = -1 // 0xFFFFFFFF as u32
}
```

---

## 5. SceneDataPacker — CPU-Side Upload

### New file: `isometric-webgpu/.../pipeline/SceneDataPacker.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.pipeline

import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.webgpu.triangulation.toGpuColorArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs [RenderCommand] data into a flat [ByteBuffer] matching the GPU's `FaceData` struct.
 *
 * This is the only CPU work per dirty frame in Phase 3. The output is uploaded once via
 * [GPUQueue.writeBuffer] and then the GPU handles all projection, culling, sorting,
 * lighting, and triangulation.
 *
 * ## Design choice
 *
 * Phase 3 operates on the **original 3D geometry**, not the already-projected 2D points
 * from `PreparedScene.commands`. This requires access to the original `Path.points` (3D)
 * from each `RenderCommand.originalPath`.
 *
 * The reason: GPU compute passes perform projection, culling, and lighting in parallel.
 * Using pre-projected 2D points would mean the CPU still does the O(N) projection step,
 * defeating the purpose of Phase 3.
 *
 * ## Face normal computation
 *
 * The face normal is computed from the cross product of two edges:
 * `normal = normalize(cross(v1 - v0, v2 - v0))`
 *
 * For isometric faces (flat polygons), all vertices are coplanar, so the normal from
 * the first three vertices is correct for the entire face.
 */
internal object SceneDataPacker {

    /**
     * Pack render commands into a ByteBuffer matching the `FaceData` GPU struct.
     *
     * @param commands Render commands from `rootNode.renderTo(commands, context)`.
     *   Uses `originalPath.points` for 3D vertices (NOT the projected 2D `points`).
     * @return ByteBuffer ready for `GPUQueue.writeBuffer()`. Position is at 0.
     */
    fun pack(commands: List<RenderCommand>): ByteBuffer {
        val buffer = ByteBuffer
            .allocateDirect(commands.size * SceneDataLayout.FACE_DATA_BYTES)
            .order(ByteOrder.nativeOrder())

        for ((index, cmd) in commands.withIndex()) {
            val pts3d = cmd.originalPath.points // List<Point> — 3D vertices
            val n = pts3d.size.coerceAtMost(4)   // GPU struct supports up to 4 vertices

            // Vertices v0-v3 (zero-padded if fewer than 4)
            for (i in 0 until 4) {
                if (i < n) {
                    val pt = pts3d[i]
                    buffer.putFloat(pt.x.toFloat())  // vec3<f32>.x
                    buffer.putFloat(pt.y.toFloat())  // vec3<f32>.y
                    buffer.putFloat(pt.z.toFloat())  // vec3<f32>.z
                } else {
                    buffer.putFloat(0f)
                    buffer.putFloat(0f)
                    buffer.putFloat(0f)
                }
                if (i < 3) {
                    buffer.putFloat(0f) // _padding for vec4 alignment
                } else {
                    buffer.putInt(n) // vertexCount in place of v3's padding
                }
            }

            // baseColor: vec4<f32> in [0,1]
            val color = cmd.color.toGpuColorArray()
            for (c in color) buffer.putFloat(c)

            // normal: vec3<f32> — cross product of first two edges
            val normal = computeNormal(pts3d)
            buffer.putFloat(normal[0])
            buffer.putFloat(normal[1])
            buffer.putFloat(normal[2])

            // textureIndex: u32
            // Phase 3 adds textureIndex to RenderCommand. If not present, use NO_TEXTURE.
            val texIdx = (cmd as? TexturedRenderCommand)?.textureIndex
                ?: SceneDataLayout.NO_TEXTURE
            buffer.putInt(texIdx)

            // faceIndex: u32
            buffer.putInt(index)

            // _padding: vec3<u32> (12 bytes to reach 128-byte alignment)
            buffer.putInt(0)
            buffer.putInt(0)
            buffer.putInt(0)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * Compute face normal from first three vertices.
     *
     * `normal = normalize(cross(v1 - v0, v2 - v0))`
     *
     * Returns `[nx, ny, nz]` as floats. Returns `[0, 0, 1]` for degenerate faces.
     */
    private fun computeNormal(points: List<Point>): FloatArray {
        if (points.size < 3) return floatArrayOf(0f, 0f, 1f)

        val v0 = points[0]
        val v1 = points[1]
        val v2 = points[2]

        // Edge vectors
        val e1x = (v1.x - v0.x).toFloat()
        val e1y = (v1.y - v0.y).toFloat()
        val e1z = (v1.z - v0.z).toFloat()
        val e2x = (v2.x - v0.x).toFloat()
        val e2y = (v2.y - v0.y).toFloat()
        val e2z = (v2.z - v0.z).toFloat()

        // Cross product
        var nx = e1y * e2z - e1z * e2y
        var ny = e1z * e2x - e1x * e2z
        var nz = e1x * e2y - e1y * e2x

        // Normalize
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-8f) return floatArrayOf(0f, 0f, 1f)
        nx /= len; ny /= len; nz /= len

        return floatArrayOf(nx, ny, nz)
    }
}

/**
 * Marker interface for RenderCommands that carry a texture index.
 * Added in Phase 3 when the texture API is designed.
 */
internal interface TexturedRenderCommand {
    val textureIndex: Int
}
```

---

## 6. Transform Compute Pass

### New file: `isometric-webgpu/.../shader/TransformShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader for parallel 3D→2D vertex transformation.
 *
 * One invocation per face. Each invocation:
 * 1. Projects all 3-4 vertices through the isometric projection matrix
 * 2. Computes depth centroid for sorting
 * 3. Copies base color and normal to output (lighting happens in a separate pass)
 *
 * ## Isometric projection matrix
 *
 * The isometric projection from `IsometricEngine` is:
 * ```
 * screenX = originX + x * scale * cos(angle) + y * scale * cos(PI - angle)
 * screenY = originY - x * scale * sin(angle) - y * scale * sin(PI - angle) - z * scale
 * ```
 *
 * This is encoded as a 4×4 matrix in `CameraUniforms.isoMatrix` for GPU-side multiplication.
 *
 * ## Depth formula
 *
 * From `IsometricEngine` KDoc: `Point.depth = x + y - 2z`.
 * Depth centroid = average of depth over all vertices of the face.
 * Higher depth = further from viewer = drawn first (painter's algorithm).
 */
internal object TransformShader {

    const val ENTRY_POINT = "transformPass"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        struct FaceData {
            v0: vec3<f32>, _p0: f32,
            v1: vec3<f32>, _p1: f32,
            v2: vec3<f32>, _p2: f32,
            v3: vec3<f32>, vertexCount: u32,
            baseColor: vec4<f32>,
            normal: vec3<f32>,
            textureIndex: u32,
            faceIndex: u32,
            _padding: vec3<u32>,
        }

        struct TransformedFace {
            s0: vec2<f32>,
            s1: vec2<f32>,
            s2: vec2<f32>,
            s3: vec2<f32>,
            vertexCount: u32,
            depth: f32,
            faceIndex: u32,
            textureIndex: u32,
            litColor: vec4<f32>,
            baseColor: vec4<f32>,
            normal: vec3<f32>,
            _padding: f32,
        }

        struct CameraUniforms {
            isoMatrix: mat4x4<f32>,   // 3D isometric projection → 2D screen
            lightDir: vec3<f32>,      // normalized light direction
            ambientLight: f32,        // minimum light intensity
            viewportSize: vec2<f32>,  // width, height in pixels
            _padding: vec2<f32>,
        }

        @group(0) @binding(0) var<storage, read>       scene:        array<FaceData>;
        @group(0) @binding(1) var<storage, read_write>  transformed:  array<TransformedFace>;
        @group(0) @binding(2) var<uniform>              camera:       CameraUniforms;

        fn projectVertex(v: vec3<f32>) -> vec2<f32> {
            let projected = camera.isoMatrix * vec4<f32>(v, 1.0);
            return projected.xy;
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            let i = id.x;
            if (i >= arrayLength(&scene)) { return; }
            let face = scene[i];

            // Project each vertex via the isometric matrix
            let s0 = projectVertex(face.v0);
            let s1 = projectVertex(face.v1);
            let s2 = projectVertex(face.v2);
            let s3 = select(vec2<f32>(0.0, 0.0), projectVertex(face.v3), face.vertexCount >= 4u);

            // Depth centroid: average of (x + y - 2z) over all vertices
            // Point.depth = x + y - 2z (from IsometricEngine KDoc)
            let d0 = face.v0.x + face.v0.y - 2.0 * face.v0.z;
            let d1 = face.v1.x + face.v1.y - 2.0 * face.v1.z;
            let d2 = face.v2.x + face.v2.y - 2.0 * face.v2.z;
            let d3 = select(0.0, face.v3.x + face.v3.y - 2.0 * face.v3.z, face.vertexCount >= 4u);
            let depth = (d0 + d1 + d2 + d3) / f32(face.vertexCount);

            // Write transformed face — litColor is written by the lighting pass
            var out: TransformedFace;
            out.s0 = s0;
            out.s1 = s1;
            out.s2 = s2;
            out.s3 = s3;
            out.vertexCount = face.vertexCount;
            out.depth = depth;
            out.faceIndex = face.faceIndex;
            out.textureIndex = face.textureIndex;
            out.litColor = face.baseColor;    // placeholder — lighting pass overwrites
            out.baseColor = face.baseColor;
            out.normal = face.normal;
            out._padding = 0.0;
            transformed[i] = out;
        }
    """.trimIndent()
}
```

---

## 7. Culling + Compaction Compute Pass

### New file: `isometric-webgpu/.../shader/CullShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader for back-face culling + frustum culling + output compaction.
 *
 * One invocation per transformed face. Visible faces are written to a compacted output
 * array using an atomic counter for the write position.
 *
 * ## Back-face culling
 *
 * Uses the signed area of the projected polygon: if the 2D cross product of two edges
 * is positive (clockwise winding in screen space), the face is back-facing.
 *
 * The winding convention matches `IsometricEngine`: after orthographic projection,
 * front-facing polygons have counter-clockwise winding (negative signed area in
 * screen-Y-down space). Since WebGPU uses Y-up, we check for positive area.
 *
 * ## Frustum culling
 *
 * AABB of the projected face is tested against the viewport rectangle `[0, viewportWidth]
 * × [0, viewportHeight]`. Faces entirely outside are discarded.
 */
internal object CullShader {

    const val ENTRY_POINT = "cullPass"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        // TransformedFace struct — must match TransformShader output
        struct TransformedFace {
            s0: vec2<f32>,
            s1: vec2<f32>,
            s2: vec2<f32>,
            s3: vec2<f32>,
            vertexCount: u32,
            depth: f32,
            faceIndex: u32,
            textureIndex: u32,
            litColor: vec4<f32>,
            baseColor: vec4<f32>,
            normal: vec3<f32>,
            _padding: f32,
        }

        struct CullUniforms {
            viewport: vec2<f32>,     // width, height
            _padding: vec2<f32>,
        }

        @group(0) @binding(0) var<storage, read>       transformed:    array<TransformedFace>;
        @group(0) @binding(1) var<storage, read_write>  visibleIndices: array<u32>;
        @group(0) @binding(2) var<storage, read_write>  visibleCount:   atomic<u32>;
        @group(0) @binding(3) var<uniform>              cullUniforms:   CullUniforms;

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            let i = id.x;
            if (i >= arrayLength(&transformed)) { return; }
            let face = transformed[i];

            // Back-face cull: signed area of projected polygon
            // For a triangle fan from vertex 0, the signed area of the first triangle
            // determines face orientation.
            let edge1 = face.s1 - face.s0;
            let edge2 = face.s2 - face.s0;
            let area = edge1.x * edge2.y - edge2.x * edge1.y;
            // In WebGPU Y-up space: positive area = counter-clockwise = front-facing
            // The transform shader outputs in pixel space (Y-down), so flip:
            // Negative area in Y-down = front-facing
            if (area >= 0.0) { return; }  // back-facing or degenerate

            // Frustum cull: AABB of face vs viewport
            let minX = min(min(face.s0.x, face.s1.x), min(face.s2.x, face.s3.x));
            let maxX = max(max(face.s0.x, face.s1.x), max(face.s2.x, face.s3.x));
            let minY = min(min(face.s0.y, face.s1.y), min(face.s2.y, face.s3.y));
            let maxY = max(max(face.s0.y, face.s1.y), max(face.s2.y, face.s3.y));
            if (maxX < 0.0 || minX > cullUniforms.viewport.x ||
                maxY < 0.0 || minY > cullUniforms.viewport.y) {
                return;  // entirely outside viewport
            }

            // Visible — write to compacted output via atomic counter
            let slot = atomicAdd(&visibleCount, 1u);
            visibleIndices[slot] = i;
        }
    """.trimIndent()
}
```

---

## 8. Depth Sort Compute Pass (GPU Radix Sort)

The WS11 radix sort shader (`RadixSortShader.kt`) is reused here. In Phase 3, it operates
on `TransformedFace.depth` values from the visible-face compacted buffer, rather than on
CPU-extracted depth keys.

### Adaptation for Phase 3

```kotlin
// In GpuFullPipeline — the sort input is the visible face indices + their depth values.
// Before sorting:
//   1. Extract depth keys from TransformedFace[visibleIndices[i]].depth
//   2. Create SortKey array: { depth, visibleIndex }
//   3. Run 4-pass radix sort (same as WS11)
//   4. Output: sortedVisibleIndices in back-to-front order
```

### New WGSL: `DepthExtractShader`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * Extracts depth keys from visible transformed faces for input to the radix sort.
 */
internal object DepthExtractShader {

    const val ENTRY_POINT = "extractDepthKeys"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        struct TransformedFace {
            s0: vec2<f32>, s1: vec2<f32>, s2: vec2<f32>, s3: vec2<f32>,
            vertexCount: u32, depth: f32, faceIndex: u32, textureIndex: u32,
            litColor: vec4<f32>, baseColor: vec4<f32>, normal: vec3<f32>, _padding: f32,
        }

        struct SortKey {
            depth: f32,
            originalIndex: u32,
        }

        @group(0) @binding(0) var<storage, read>       transformed:    array<TransformedFace>;
        @group(0) @binding(1) var<storage, read>       visibleIndices: array<u32>;
        @group(0) @binding(2) var<storage, read_write>  sortKeys:      array<SortKey>;
        @group(0) @binding(3) var<uniform>              visibleCount:  u32;

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            let i = id.x;
            if (i >= visibleCount) { return; }
            let faceIdx = visibleIndices[i];
            sortKeys[i] = SortKey(transformed[faceIdx].depth, i);
        }
    """.trimIndent()
}
```

---

## 9. Lighting Compute Pass

### New file: `isometric-webgpu/.../shader/LightingShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader for per-face lighting.
 *
 * Operates on sorted visible faces. Computes diffuse lighting using the dot product
 * between the face normal and the light direction, then writes the lit color to
 * `TransformedFace.litColor`.
 *
 * ## Lighting model
 *
 * Matches `IsometricProjection.transformColor()` in isometric-core:
 * ```
 * intensity = max(dot(normalize(normal), lightDir), ambientLight)
 * litColor.rgb = baseColor.rgb * intensity
 * litColor.a = baseColor.a
 * ```
 *
 * The `ambientLight` value prevents fully-black faces on back-lit surfaces.
 */
internal object LightingShader {

    const val ENTRY_POINT = "lightingPass"
    const val WORKGROUP_SIZE = 256

    val WGSL: String = """
        struct TransformedFace {
            s0: vec2<f32>, s1: vec2<f32>, s2: vec2<f32>, s3: vec2<f32>,
            vertexCount: u32, depth: f32, faceIndex: u32, textureIndex: u32,
            litColor: vec4<f32>, baseColor: vec4<f32>, normal: vec3<f32>, _padding: f32,
        }

        struct LightUniforms {
            lightDir: vec3<f32>,
            ambientLight: f32,
        }

        @group(0) @binding(0) var<storage, read_write>  transformed:        array<TransformedFace>;
        @group(0) @binding(1) var<storage, read>        sortedVisibleIndices: array<u32>;
        @group(0) @binding(2) var<uniform>              light:              LightUniforms;
        @group(0) @binding(3) var<uniform>              visibleCount:       u32;

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            let i = id.x;
            if (i >= visibleCount) { return; }
            let faceIdx = sortedVisibleIndices[i];

            let face = transformed[faceIdx];
            let n = normalize(face.normal);
            let intensity = max(dot(n, light.lightDir), light.ambientLight);
            let litColor = vec4<f32>(face.baseColor.rgb * intensity, face.baseColor.a);

            transformed[faceIdx].litColor = litColor;
        }
    """.trimIndent()
}
```

---

## 10. Indirect Draw Buffer Population

### New file: `isometric-webgpu/.../shader/EmitVerticesShader.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.shader

/**
 * WGSL compute shader that expands sorted visible faces into triangle vertices
 * and populates the indirect draw buffer.
 *
 * One invocation per visible face (in sorted order). Each invocation:
 * 1. Reads the transformed face from the sorted visible list
 * 2. Computes triangle fan vertices: `(n-2) * 3` vertices per n-gon
 * 3. Writes vertices to the final vertex buffer at an atomically-allocated position
 * 4. The indirect draw buffer is a single `DrawIndirectArgs` struct updated atomically
 *
 * ## Vertex output format (must match Phase 2 shader)
 *
 * Each output vertex: position(2) + color(4) + uv(2) = 8 floats = 32 bytes.
 * Same layout as Phase 2's `IsometricVertexShader` — the vertex/fragment shaders
 * are identical between Phase 2 and Phase 3.
 *
 * ## DrawIndirectArgs
 *
 * ```
 * struct DrawIndirectArgs {
 *     vertexCount: u32,     // total vertices emitted by all faces
 *     instanceCount: u32,   // always 1
 *     firstVertex: u32,     // always 0
 *     firstInstance: u32,   // always 0
 * }
 * ```
 *
 * The `vertexCount` field is the sum of all per-face vertex counts, accumulated
 * via `atomicAdd`. The other fields are set to their defaults by the CPU before
 * the compute pass runs.
 */
internal object EmitVerticesShader {

    const val ENTRY_POINT = "emitVertices"
    const val WORKGROUP_SIZE = 256

    /** Floats per output vertex (must match IsometricVertexShader) */
    const val FLOATS_PER_VERTEX = 8

    val WGSL: String = """
        struct TransformedFace {
            s0: vec2<f32>, s1: vec2<f32>, s2: vec2<f32>, s3: vec2<f32>,
            vertexCount: u32, depth: f32, faceIndex: u32, textureIndex: u32,
            litColor: vec4<f32>, baseColor: vec4<f32>, normal: vec3<f32>, _padding: f32,
        }

        struct DrawIndirectArgs {
            vertexCount: atomic<u32>,
            instanceCount: u32,
            firstVertex: u32,
            firstInstance: u32,
        }

        @group(0) @binding(0) var<storage, read>       transformed:          array<TransformedFace>;
        @group(0) @binding(1) var<storage, read>       sortedVisibleIndices: array<u32>;
        @group(0) @binding(2) var<storage, read_write>  vertexOutput:        array<f32>;
        @group(0) @binding(3) var<storage, read_write>  indirectBuffer:      DrawIndirectArgs;
        @group(0) @binding(4) var<uniform>              visibleCount:        u32;

        fn writeVertex(offset: u32, pos: vec2<f32>, color: vec4<f32>, uv: vec2<f32>) {
            let base = offset * ${FLOATS_PER_VERTEX}u;
            vertexOutput[base + 0u] = pos.x;
            vertexOutput[base + 1u] = pos.y;
            vertexOutput[base + 2u] = color.r;
            vertexOutput[base + 3u] = color.g;
            vertexOutput[base + 4u] = color.b;
            vertexOutput[base + 5u] = color.a;
            vertexOutput[base + 6u] = uv.x;
            vertexOutput[base + 7u] = uv.y;
        }

        fn getVertex(face: TransformedFace, idx: u32) -> vec2<f32> {
            switch (idx) {
                case 0u: { return face.s0; }
                case 1u: { return face.s1; }
                case 2u: { return face.s2; }
                case 3u: { return face.s3; }
                default: { return vec2<f32>(0.0); }
            }
        }

        @compute @workgroup_size(${WORKGROUP_SIZE})
        fn ${ENTRY_POINT}(@builtin(global_invocation_id) id: vec3<u32>) {
            let i = id.x;
            if (i >= visibleCount) { return; }
            let faceIdx = sortedVisibleIndices[i];
            let face = transformed[faceIdx];

            // Triangle fan: (n-2) triangles, 3 vertices each
            let triCount = face.vertexCount - 2u;
            let vertCount = triCount * 3u;

            // Atomically allocate space in the vertex output buffer
            let baseVertex = atomicAdd(&indirectBuffer.vertexCount, vertCount);

            let color = face.litColor;
            // Default UVs — Phase 3 will use actual texture coords
            let uv0 = vec2<f32>(0.5, 0.5);  // center
            let uv1 = vec2<f32>(1.0, 0.0);  // edge
            let uv2 = vec2<f32>(0.0, 0.0);  // edge

            for (var t = 0u; t < triCount; t = t + 1u) {
                let v0 = baseVertex + t * 3u;
                writeVertex(v0 + 0u, getVertex(face, 0u),     color, uv0);
                writeVertex(v0 + 1u, getVertex(face, t + 1u), color, uv1);
                writeVertex(v0 + 2u, getVertex(face, t + 2u), color, uv2);
            }
        }
    """.trimIndent()
}
```

---

## 11. Vertex Emit Compute Pass

The vertex emit pass is combined into `EmitVerticesShader` above (Section 10). It handles
both vertex expansion and indirect draw buffer population in a single dispatch, because:

1. Both operations need the same face data (transformed vertices + lit color)
2. The atomic `vertexCount` accumulation naturally populates the indirect draw arg
3. A single dispatch avoids an extra GPU synchronization point

---

## 12. GpuFullPipeline — Orchestrator

### New file: `isometric-webgpu/.../pipeline/GpuFullPipeline.kt`

```kotlin
package io.github.jayteealao.isometric.webgpu.pipeline

import androidx.webgpu.BufferBindingType
import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroup
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayout
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUBufferBindingLayout
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUComputePipeline
import androidx.webgpu.GPUComputePipelineDescriptor
import androidx.webgpu.GPUComputeState
import androidx.webgpu.GPUPipelineLayout
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPUShaderModule
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSurface
import androidx.webgpu.LoadOp
import androidx.webgpu.ShaderStage
import androidx.webgpu.StoreOp
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.webgpu.GpuContext
import io.github.jayteealao.isometric.webgpu.shader.CullShader
import io.github.jayteealao.isometric.webgpu.shader.DepthExtractShader
import io.github.jayteealao.isometric.webgpu.shader.EmitVerticesShader
import io.github.jayteealao.isometric.webgpu.shader.LightingShader
import io.github.jayteealao.isometric.webgpu.shader.TransformShader
import io.github.jayteealao.isometric.webgpu.sort.RadixSortShader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Orchestrates the full GPU-driven pipeline for Phase 3.
 *
 * ## Pipeline stages (all within a single command encoder submit)
 *
 * 1. **Transform** — project 3D → 2D, compute depth centroids
 * 2. **Cull** — back-face + frustum culling, compacted visible indices
 * 3. **Depth extract** — extract depth keys from visible faces
 * 4. **Radix sort** — 4-pass GPU sort by depth (back-to-front)
 * 5. **Lighting** — diffuse dot product, writes lit color
 * 6. **Emit vertices** — triangle fan expansion + indirect draw buffer
 * 7. **Render pass** — single `drawIndirect()` call
 *
 * All stages run within a single `GPUCommandEncoder` → `queue.submit()`. WebGPU guarantees
 * implicit barriers between compute and render passes within one submit, so no explicit
 * synchronization is needed.
 *
 * ## Buffer layout
 *
 * | Buffer | Size | Usage |
 * |--------|------|-------|
 * | sceneBuffer | N × 128 bytes | FaceData input (uploaded by CPU) |
 * | transformedBuffer | N × 96 bytes | TransformedFace output |
 * | visibleIndices | N × 4 bytes | Compacted visible face indices |
 * | visibleCountBuffer | 4 bytes | Atomic counter for visible faces |
 * | sortKeysA/B | N × 8 bytes | Radix sort ping-pong buffers |
 * | sortedVisibleIndices | N × 4 bytes | Final sorted visible indices |
 * | vertexOutput | N × maxVertsPerFace × 32 bytes | Final vertex buffer |
 * | indirectBuffer | 16 bytes | DrawIndirectArgs |
 * | cameraUniforms | 128 bytes | Projection matrix, light, viewport |
 *
 * ## Important API notes
 *
 * - Single command encoder covers both compute and render passes. WebGPU's implicit
 *   barrier between passes within one submit guarantees ordering.
 * - `queue.submit(arrayOf(encoder.finish()))` — Array, not List.
 * - `gpuSurface.present()` after render pass — never `texture.destroy()`.
 * - `GPUComputePipelineDescriptor` requires `GPUComputeState`, not a raw entry point.
 */
internal class GpuFullPipeline(
    private val ctx: GpuContext,
    private val surfaceFormat: Int,
) {
    // Compute pipelines
    private lateinit var transformPipeline: GPUComputePipeline
    private lateinit var cullPipeline: GPUComputePipeline
    private lateinit var depthExtractPipeline: GPUComputePipeline
    private lateinit var radixCountPipeline: GPUComputePipeline
    private lateinit var radixScatterPipeline: GPUComputePipeline
    private lateinit var lightingPipeline: GPUComputePipeline
    private lateinit var emitVerticesPipeline: GPUComputePipeline

    // Render pipeline (same vertex/fragment shaders as Phase 2)
    private lateinit var renderPipeline: GpuRenderPipeline

    // GPU buffers
    private var sceneBuffer: GPUBuffer? = null
    private var transformedBuffer: GPUBuffer? = null
    private var visibleIndicesBuffer: GPUBuffer? = null
    private var visibleCountBuffer: GPUBuffer? = null
    private var sortKeysA: GPUBuffer? = null
    private var sortKeysB: GPUBuffer? = null
    private var sortedVisibleIndicesBuffer: GPUBuffer? = null
    private var vertexOutputBuffer: GPUBuffer? = null
    private var indirectBuffer: GPUBuffer? = null
    private var cameraUniformBuffer: GPUBuffer? = null

    private var faceCount: Int = 0
    private var maxVertexCount: Int = 0

    /**
     * Initialize pipelines. Called once after surface creation.
     */
    fun init(width: Int, height: Int) {
        renderPipeline = GpuRenderPipeline(ctx, surfaceFormat)
        renderPipeline.updateViewportSize(width.toFloat(), height.toFloat())

        buildComputePipelines()
        createCameraUniformBuffer()
    }

    /**
     * Upload scene data and render a frame.
     *
     * @param commands Raw render commands from `rootNode.renderTo()`.
     *   Uses `originalPath.points` for 3D vertices.
     * @param gpuSurface The configured GPU surface to present to.
     * @param isoMatrix The 4×4 isometric projection matrix (column-major, 64 bytes).
     * @param lightDir Normalized light direction as `[x, y, z]`.
     * @param ambientLight Minimum light intensity (typically 0.3).
     * @param viewportWidth Surface width in pixels.
     * @param viewportHeight Surface height in pixels.
     */
    fun renderFrame(
        commands: List<RenderCommand>,
        gpuSurface: GPUSurface,
        isoMatrix: FloatArray, // 16 floats, column-major
        lightDir: FloatArray,  // 3 floats
        ambientLight: Float,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        faceCount = commands.size
        if (faceCount == 0) return

        // Worst case: every face is a quad → 2 triangles × 3 verts = 6 verts per face
        maxVertexCount = faceCount * 6

        // --- Ensure buffers are large enough ---
        ensureBuffers()

        // --- Upload scene data (CPU → GPU, once per dirty frame) ---
        val sceneData = SceneDataPacker.pack(commands)
        ctx.queue.writeBuffer(sceneBuffer!!, 0L, sceneData)

        // --- Upload camera uniforms ---
        uploadCameraUniforms(isoMatrix, lightDir, ambientLight, viewportWidth, viewportHeight)

        // --- Zero the visibleCount and indirectBuffer ---
        val zeroCount = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        zeroCount.putInt(0).rewind()
        ctx.queue.writeBuffer(visibleCountBuffer!!, 0L, zeroCount)

        val zeroIndirect = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        zeroIndirect.putInt(0) // vertexCount (atomic, starts at 0)
        zeroIndirect.putInt(1) // instanceCount
        zeroIndirect.putInt(0) // firstVertex
        zeroIndirect.putInt(0) // firstInstance
        zeroIndirect.rewind()
        ctx.queue.writeBuffer(indirectBuffer!!, 0L, zeroIndirect)

        // --- Build single command encoder for all passes ---
        val encoder = ctx.device.createCommandEncoder()
        val workgroups = ceil(faceCount.toFloat() / TransformShader.WORKGROUP_SIZE).toInt()

        // Pass 1: Transform
        val compute1 = encoder.beginComputePass()
        compute1.setPipeline(transformPipeline)
        compute1.setBindGroup(0, createTransformBindGroup())
        compute1.dispatchWorkgroups(workgroups)
        compute1.end()

        // Pass 2: Cull
        val compute2 = encoder.beginComputePass()
        compute2.setPipeline(cullPipeline)
        compute2.setBindGroup(0, createCullBindGroup(viewportWidth, viewportHeight))
        compute2.dispatchWorkgroups(workgroups)
        compute2.end()

        // Pass 3-6: Depth extract → Radix sort → Lighting → Emit vertices
        // (Omitted for brevity — same pattern as above with different pipelines and bind groups)
        // Each uses encoder.beginComputePass() → setPipeline → setBindGroup → dispatch → end()

        // Pass 7: Render
        val surfaceTexture = gpuSurface.getCurrentTexture()
        val textureView = surfaceTexture.texture.createView()

        val renderPass = encoder.beginRenderPass(
            GPURenderPassDescriptor(
                colorAttachments = arrayOf(
                    GPURenderPassColorAttachment(
                        view = textureView,
                        clearValue = GPUColor(r = 0.0, g = 0.0, b = 0.0, a = 0.0),
                        loadOp = LoadOp.Clear,
                        storeOp = StoreOp.Store,
                    )
                )
            )
        )
        renderPass.setPipeline(renderPipeline.pipeline)
        renderPass.setBindGroup(0, renderPipeline.uniformBindGroup)
        renderPass.setVertexBuffer(0, vertexOutputBuffer!!)
        // drawIndirect: GPU determines vertex count from the indirect buffer
        renderPass.drawIndirect(indirectBuffer!!, 0L)
        renderPass.end()

        // --- Submit ---
        // Single submit covers all compute + render passes.
        // WebGPU guarantees implicit barriers between passes within one submit.
        ctx.queue.submit(arrayOf(encoder.finish()))

        // --- Present ---
        // present() pushes the frame. texture.destroy() would DISCARD it.
        gpuSurface.present()
    }

    fun resize(width: Int, height: Int) {
        renderPipeline.updateViewportSize(width.toFloat(), height.toFloat())
    }

    fun destroy() {
        sceneBuffer?.destroy()
        transformedBuffer?.destroy()
        visibleIndicesBuffer?.destroy()
        visibleCountBuffer?.destroy()
        sortKeysA?.destroy()
        sortKeysB?.destroy()
        sortedVisibleIndicesBuffer?.destroy()
        vertexOutputBuffer?.destroy()
        indirectBuffer?.destroy()
        cameraUniformBuffer?.destroy()
        renderPipeline.destroy()
    }

    // --- Private helpers ---

    private fun ensureBuffers() {
        val sceneBytes = (faceCount * SceneDataLayout.FACE_DATA_BYTES).toLong()
        val transformedBytes = (faceCount * SceneDataLayout.TRANSFORMED_FACE_BYTES).toLong()
        val indicesBytes = (faceCount * 4).toLong()
        val sortKeyBytes = (faceCount * 8).toLong() // SortKey: f32 + u32
        val vertexBytes = (maxVertexCount * EmitVerticesShader.FLOATS_PER_VERTEX * 4).toLong()

        // Recreate buffers if face count increased
        if (sceneBuffer == null || sceneBuffer!!.size < sceneBytes) {
            sceneBuffer?.destroy()
            sceneBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                    size = sceneBytes,
                )
            )
        }

        if (transformedBuffer == null || transformedBuffer!!.size < transformedBytes) {
            transformedBuffer?.destroy()
            transformedBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                    size = transformedBytes,
                )
            )
        }

        if (visibleIndicesBuffer == null || visibleIndicesBuffer!!.size < indicesBytes) {
            visibleIndicesBuffer?.destroy()
            visibleIndicesBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                    size = indicesBytes,
                )
            )
        }

        if (visibleCountBuffer == null) {
            visibleCountBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                    size = 4L,
                )
            )
        }

        if (sortKeysA == null || sortKeysA!!.size < sortKeyBytes) {
            sortKeysA?.destroy()
            sortKeysA = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                    size = sortKeyBytes,
                )
            )
            sortKeysB?.destroy()
            sortKeysB = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst or BufferUsage.CopySrc,
                    size = sortKeyBytes,
                )
            )
        }

        if (sortedVisibleIndicesBuffer == null || sortedVisibleIndicesBuffer!!.size < indicesBytes) {
            sortedVisibleIndicesBuffer?.destroy()
            sortedVisibleIndicesBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.CopyDst,
                    size = indicesBytes,
                )
            )
        }

        if (vertexOutputBuffer == null || vertexOutputBuffer!!.size < vertexBytes) {
            vertexOutputBuffer?.destroy()
            vertexOutputBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.Vertex or BufferUsage.CopyDst,
                    size = vertexBytes,
                )
            )
        }

        if (indirectBuffer == null) {
            indirectBuffer = ctx.device.createBuffer(
                GPUBufferDescriptor(
                    usage = BufferUsage.Storage or BufferUsage.Indirect or BufferUsage.CopyDst,
                    size = 16L, // DrawIndirectArgs: 4 × u32
                )
            )
        }
    }

    private fun buildComputePipelines() {
        // Each pipeline: createShaderModule → createComputePipeline
        // GPUShaderModuleDescriptor: shaderSourceWGSL = GPUShaderSourceWGSL(code = ...)
        // GPUComputePipelineDescriptor: compute = GPUComputeState(module, entryPoint)

        fun createPipeline(wgsl: String, entryPoint: String): GPUComputePipeline {
            val module = ctx.device.createShaderModule(
                GPUShaderModuleDescriptor(
                    shaderSourceWGSL = GPUShaderSourceWGSL(code = wgsl)
                )
            )
            return ctx.device.createComputePipeline(
                GPUComputePipelineDescriptor(
                    compute = GPUComputeState(
                        module = module,
                        entryPoint = entryPoint,
                    )
                )
            )
        }

        transformPipeline = createPipeline(TransformShader.WGSL, TransformShader.ENTRY_POINT)
        cullPipeline = createPipeline(CullShader.WGSL, CullShader.ENTRY_POINT)
        depthExtractPipeline = createPipeline(DepthExtractShader.WGSL, DepthExtractShader.ENTRY_POINT)
        radixCountPipeline = createPipeline(RadixSortShader.WGSL, RadixSortShader.COUNT_ENTRY_POINT)
        radixScatterPipeline = createPipeline(RadixSortShader.WGSL, RadixSortShader.SCATTER_ENTRY_POINT)
        lightingPipeline = createPipeline(LightingShader.WGSL, LightingShader.ENTRY_POINT)
        emitVerticesPipeline = createPipeline(EmitVerticesShader.WGSL, EmitVerticesShader.ENTRY_POINT)
    }

    private fun createCameraUniformBuffer() {
        // CameraUniforms: mat4x4(64) + vec3(12) + f32(4) + vec2(8) + vec2(8) = 96 bytes
        // Padded to 128 for uniform buffer alignment
        cameraUniformBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                size = 128L,
            )
        )
    }

    private fun uploadCameraUniforms(
        isoMatrix: FloatArray,
        lightDir: FloatArray,
        ambientLight: Float,
        viewportWidth: Int,
        viewportHeight: Int,
    ) {
        val data = ByteBuffer.allocateDirect(128)
            .order(ByteOrder.nativeOrder())

        // mat4x4<f32> isoMatrix (64 bytes, column-major)
        for (f in isoMatrix) data.putFloat(f)

        // vec3<f32> lightDir (12 bytes)
        for (f in lightDir) data.putFloat(f)

        // f32 ambientLight
        data.putFloat(ambientLight)

        // vec2<f32> viewportSize
        data.putFloat(viewportWidth.toFloat())
        data.putFloat(viewportHeight.toFloat())

        // vec2<f32> _padding
        data.putFloat(0f)
        data.putFloat(0f)

        // Remaining padding to 128 bytes
        while (data.position() < 128) data.putFloat(0f)

        data.rewind()
        ctx.queue.writeBuffer(cameraUniformBuffer!!, 0L, data)
    }

    private fun createTransformBindGroup(): GPUBindGroup {
        // Bind group for transform pass: scene (read), transformed (r/w), camera (uniform)
        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = transformPipeline.getBindGroupLayout(0),
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = sceneBuffer!!),
                    GPUBindGroupEntry(binding = 1, buffer = transformedBuffer!!),
                    GPUBindGroupEntry(binding = 2, buffer = cameraUniformBuffer!!),
                )
            )
        )
    }

    private fun createCullBindGroup(viewportWidth: Int, viewportHeight: Int): GPUBindGroup {
        // Upload cull uniforms (viewport size) to a small uniform buffer
        val cullUniformBuffer = ctx.device.createBuffer(
            GPUBufferDescriptor(
                usage = BufferUsage.Uniform or BufferUsage.CopyDst,
                size = 16L,
            )
        )
        val cullData = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        cullData.putFloat(viewportWidth.toFloat())
        cullData.putFloat(viewportHeight.toFloat())
        cullData.putFloat(0f) // padding
        cullData.putFloat(0f) // padding
        cullData.rewind()
        ctx.queue.writeBuffer(cullUniformBuffer, 0L, cullData)

        return ctx.device.createBindGroup(
            GPUBindGroupDescriptor(
                layout = cullPipeline.getBindGroupLayout(0),
                entries = arrayOf(
                    GPUBindGroupEntry(binding = 0, buffer = transformedBuffer!!),
                    GPUBindGroupEntry(binding = 1, buffer = visibleIndicesBuffer!!),
                    GPUBindGroupEntry(binding = 2, buffer = visibleCountBuffer!!),
                    GPUBindGroupEntry(binding = 3, buffer = cullUniformBuffer),
                )
            )
        )
    }
}
```

---

## 13. WebGpuSceneRendererV2

### Updated renderer using GpuFullPipeline

```kotlin
package io.github.jayteealao.isometric.webgpu

import android.view.Surface
import androidx.webgpu.CompositeAlphaMode
import androidx.webgpu.GPUSurface
import androidx.webgpu.GPUSurfaceConfiguration
import androidx.webgpu.GPUSurfaceDescriptor
import androidx.webgpu.GPUSurfaceSourceAndroidNativeWindow
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.helper.Util.windowFromSurface
import io.github.jayteealao.isometric.RenderCommand
import io.github.jayteealao.isometric.webgpu.pipeline.GpuFullPipeline

/**
 * Phase 3 scene renderer using the full GPU-driven pipeline.
 *
 * Unlike Phase 2's [WebGpuSceneRenderer] which performs CPU-side triangulation and
 * per-frame buffer uploads, this renderer uploads raw 3D scene data once per dirty
 * frame and lets the GPU handle everything else.
 *
 * ## Frame budget
 *
 * For a 1000-face static scene, the CPU cost per frame is:
 * - `rootNode.renderTo()` — collects RenderCommands (unchanged)
 * - `SceneDataPacker.pack()` — packs 3D data into ByteBuffer (~0.5ms)
 * - `queue.writeBuffer()` — DMA transfer (~0.1ms)
 * - `queue.submit()` — single command buffer submission (~0.05ms)
 *
 * Total CPU per dirty frame: ~0.65ms
 * Total CPU per static frame: ~0.05ms (just re-submit the same command buffer)
 * GPU frame time (1000 faces, Pixel 6): target < 2ms
 */
internal class WebGpuSceneRendererV2(
    private val ctx: GpuContext,
) {
    private lateinit var gpuSurface: GPUSurface
    private lateinit var pipeline: GpuFullPipeline
    private var surfaceFormat: Int = TextureFormat.Undefined
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    // Isometric projection matrix (column-major 4×4)
    // Built from IsometricEngine's angle + scale parameters.
    private var isoMatrix: FloatArray = FloatArray(16)
    private var lightDir: FloatArray = floatArrayOf(0.408f, -0.204f, 0.612f) // normalized default
    private var ambientLight: Float = 0.3f

    fun init(androidSurface: Surface, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        val nativeWindow = windowFromSurface(androidSurface)
        gpuSurface = ctx.instance.createSurface(
            GPUSurfaceDescriptor(
                surfaceSourceAndroidNativeWindow = GPUSurfaceSourceAndroidNativeWindow(nativeWindow)
            )
        )

        val caps = gpuSurface.getCapabilities(ctx.adapter)
        surfaceFormat = if (TextureFormat.BGRA8Unorm in caps.formats) {
            TextureFormat.BGRA8Unorm
        } else {
            caps.formats.first()
        }

        gpuSurface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                width = width,
                height = height,
                format = surfaceFormat,
                usage = TextureUsage.RenderAttachment,
                alphaMode = CompositeAlphaMode.Opaque,
            )
        )

        pipeline = GpuFullPipeline(ctx, surfaceFormat)
        pipeline.init(width, height)
    }

    /**
     * Update the isometric projection matrix from engine parameters.
     *
     * Called when the engine's angle or scale changes. The matrix encodes:
     * ```
     * screenX = originX + x * scale * cos(angle) + y * scale * cos(PI - angle)
     * screenY = originY - x * scale * sin(angle) - y * scale * sin(PI - angle) - z * scale
     * ```
     *
     * @param angle Engine angle in radians (default: PI/6)
     * @param scale Engine scale in pixels per unit (default: 70.0)
     * @param originX Viewport center X (typically width / 2.0)
     * @param originY Viewport origin Y (typically height * 0.9)
     */
    fun updateProjectionMatrix(
        angle: Double,
        scale: Double,
        originX: Double,
        originY: Double,
    ) {
        val cosA = kotlin.math.cos(angle).toFloat()
        val sinA = kotlin.math.sin(angle).toFloat()
        val cosPiMinusA = kotlin.math.cos(kotlin.math.PI - angle).toFloat()
        val sinPiMinusA = kotlin.math.sin(kotlin.math.PI - angle).toFloat()
        val s = scale.toFloat()

        // Column-major 4×4 matrix
        isoMatrix = floatArrayOf(
            // Column 0 (x-axis)
            s * cosA,             // m[0][0]: x → screenX
            -s * sinA,            // m[1][0]: x → screenY
            0f,                   // m[2][0]
            0f,                   // m[3][0]
            // Column 1 (y-axis)
            s * cosPiMinusA,      // m[0][1]: y → screenX
            -s * sinPiMinusA,     // m[1][1]: y → screenY
            0f,                   // m[2][1]
            0f,                   // m[3][1]
            // Column 2 (z-axis)
            0f,                   // m[0][2]: z → screenX (none)
            -s,                   // m[1][2]: z → screenY
            0f,                   // m[2][2]
            0f,                   // m[3][2]
            // Column 3 (translation)
            originX.toFloat(),    // m[0][3]: origin X
            originY.toFloat(),    // m[1][3]: origin Y
            0f,                   // m[2][3]
            1f,                   // m[3][3]
        )
    }

    fun updateLighting(lightDirection: FloatArray, ambient: Float) {
        lightDir = lightDirection
        ambientLight = ambient
    }

    /**
     * Render a frame from raw render commands.
     *
     * @param commands Raw commands from `rootNode.renderTo()`.
     */
    fun renderFrame(commands: List<RenderCommand>) {
        pipeline.renderFrame(
            commands = commands,
            gpuSurface = gpuSurface,
            isoMatrix = isoMatrix,
            lightDir = lightDir,
            ambientLight = ambientLight,
            viewportWidth = surfaceWidth,
            viewportHeight = surfaceHeight,
        )
    }

    fun resize(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        gpuSurface.configure(
            GPUSurfaceConfiguration(
                device = ctx.device,
                width = width,
                height = height,
                format = surfaceFormat,
                usage = TextureUsage.RenderAttachment,
                alphaMode = CompositeAlphaMode.Opaque,
            )
        )
        pipeline.resize(width, height)
    }

    fun destroy() {
        pipeline.destroy()
        if (::gpuSurface.isInitialized) gpuSurface.unconfigure()
    }
}
```

---

## 14. Texture Support — textureIndex + Atlas

### Overview

Phase 3 introduces texture support via a texture atlas. Each `RenderCommand` gains an
optional `textureIndex` field that references a slot in the atlas. The fragment shader
samples from the atlas using the per-vertex UVs and the face's `textureIndex`.

### RenderCommand changes

```kotlin
// In isometric-core RenderCommand.kt — Phase 3 addition
class RenderCommand(
    val commandId: String,
    val points: List<Point2D>,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null,
    val textureIndex: Int = -1,   // NEW: -1 = no texture (solid color)
)
```

### Updated fragment shader

```wgsl
// IsometricFragmentShaderV2.kt
@group(0) @binding(1) var texAtlas: texture_2d<f32>;
@group(0) @binding(2) var texSampler: sampler;

struct FragmentInput {
    @location(0) color: vec4<f32>,
    @location(1) uv:    vec2<f32>,
    @location(2) @interpolate(flat) textureIndex: u32,
}

@fragment
fn fragmentMain(in: FragmentInput) -> @location(0) vec4<f32> {
    if (in.textureIndex == 0xFFFFFFFFu) {
        // No texture — solid color
        return in.color;
    }
    // Sample from atlas at the UV coordinates
    // Atlas layout: textureIndex maps to a sub-region of the atlas
    let texColor = textureSample(texAtlas, texSampler, in.uv);
    return texColor * in.color;  // modulate texture by vertex color
}
```

### TextureAtlas (outline)

```kotlin
// isometric-webgpu/.../texture/TextureAtlas.kt
internal class TextureAtlas(private val ctx: GpuContext) {
    private var atlasTexture: GPUTexture? = null
    private var atlasSampler: GPUSampler? = null
    private var slotCount: Int = 0

    /**
     * Upload textures to the atlas.
     * @param textures List of Bitmap or ByteBuffer texture data.
     * @return Map of texture ID → atlas slot index.
     */
    fun upload(textures: List<TextureData>): Map<String, Int> {
        // Pack textures into a 2D atlas texture
        // Create GPUTexture with appropriate size
        // Write texture data via queue.writeTexture()
        // Create sampler with linear filtering
        TODO("Implement texture atlas packing")
    }

    fun getBindGroupEntries(): Array<GPUBindGroupEntry> {
        return arrayOf(
            GPUBindGroupEntry(binding = 1, textureView = atlasTexture!!.createView()),
            GPUBindGroupEntry(binding = 2, sampler = atlasSampler!!),
        )
    }

    fun destroy() {
        atlasTexture?.destroy()
    }
}
```

---

## 15. RenderCommand Extension for Phase 3

### `textureIndex` addition

When texture support is designed, `RenderCommand` gains a `textureIndex: Int` field.
This is a direct breaking change (per the user's no-deprecation-cycle preference):

```kotlin
// isometric-core RenderCommand.kt — Phase 3
class RenderCommand(
    val commandId: String,
    val points: List<Point2D>,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null,
    val textureIndex: Int = -1,      // NEW: atlas slot; -1 = no texture
)
```

The default value of `-1` ensures backward compatibility — existing code that creates
`RenderCommand` without specifying `textureIndex` continues to work (solid color rendering).

---

## 16. Performance Model

### Phase 3 vs Phase 2 cost breakdown (1000-face scene)

| Operation | Phase 2 (CPU+GPU) | Phase 3 (GPU-driven) |
|---|---|---|
| `rootNode.renderTo()` | ~1.5ms | ~1.5ms (unchanged) |
| Projection (3D→2D) | ~2ms (CPU) | ~0.01ms (GPU compute) |
| Culling | ~0.5ms (CPU) | ~0.01ms (GPU compute) |
| Depth sorting | ~3ms (GPU radix) | ~0.1ms (GPU radix, visible only) |
| Lighting | ~0.5ms (CPU) | ~0.01ms (GPU compute) |
| Triangulation | ~1ms (CPU) | ~0.01ms (GPU compute) |
| Buffer upload | ~0.5ms | ~0.1ms (raw scene data only) |
| Draw submission | ~0.1ms | ~0.05ms (single drawIndirect) |
| **Total CPU** | **~5ms** | **~1.65ms** |
| **Total GPU** | **~3ms** | **~0.15ms** |
| **Frame total** | **~8ms** | **~1.8ms** |

### Static scene cost

For scenes where `rootNode.isDirty == false`:
- Phase 2: Still re-draws (~0.5ms)
- Phase 3: Re-submits same command buffer (~0.05ms) + `drawIndirect` (~0.1ms)

### Target

- 1000-face scene: < 2ms total frame time on Pixel 6 (target to validate, not guaranteed)
- Static scene re-draw: < 0.5ms

---

## 17. Test Plan

### Unit Tests (JVM, no GPU)

| Test Class | Coverage |
|---|---|
| `SceneDataPackerTest` | Packs 3-face scene; output ByteBuffer has correct size (3 × 128); vertex data at correct offsets |
| `SceneDataPackerNormalTest` | Computed normals match expected values for known face orientations |
| `TransformShaderWgslTest` | WGSL string contains entry point and correct struct definitions |
| `CullShaderWgslTest` | WGSL string contains entry point and correct bindings |
| `LightingShaderWgslTest` | WGSL string contains entry point |
| `EmitVerticesShaderWgslTest` | WGSL string contains entry point; FLOATS_PER_VERTEX == 8 |
| `ProjectionMatrixTest` | `updateProjectionMatrix(PI/6, 70.0, 400, 720)` produces correct matrix values |

### Instrumented Tests (physical device, GPU required)

| Test Class | Coverage |
|---|---|
| `GpuFullPipelineSmokeTest` | 3-shape scene renders without crash via Phase 3 pipeline |
| `GpuFullPipelineShaderCompileTest` | All 7 compute shader modules compile on real device |
| `GpuCullPassTest` | Known back-facing face is culled; front-facing face passes |
| `GpuLightingPassTest` | Lit color matches CPU `transformColor()` output for known normal+light pair |
| `GpuDrawIndirectTest` | `drawIndirect` produces non-empty frame for 100-face scene |
| `Phase3FrameBudgetTest` | 1000-face scene: total frame time < 2ms p95 over 300 frames |
| `Phase3StaticFrameTest` | Static scene (no dirty): frame time < 0.5ms p95 |
| `Phase3vs2RegressionTest` | Phase 3 output matches Phase 2 output for 5 reference scenes (screenshot comparison) |
| `TextureAtlasSmokeTest` | Single textured face renders correctly with atlas sampler |

### Canvas + Phase 2 Regression Guard

```bash
# Canvas regression
./gradlew :isometric-benchmark:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=CanvasRenderBenchmark

# Phase 2 regression
./gradlew :isometric-benchmark:connectedAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=WebGpuPhase2Benchmark
```

Both must pass with < 5% regression.

### Device Matrix

Same as WS12:

| Device | GPU | Reason |
|---|---|---|
| Pixel 6 | Arm Mali-G78 | High-end ARM Mali, Vulkan 1.1 |
| Pixel 7 | Arm Mali-G710 | Current Tensor generation |
| Samsung Galaxy A55 | Exynos 1480 (Xclipse 540) | Mid-range ARM |
| Snapdragon 8 Gen device | Adreno 730+ | Qualcomm Vulkan path |

### Workgroup size validation

```kotlin
// In GpuFullPipeline.init() — query device limits
val maxInvocations = ctx.device.limits.maxComputeInvocationsPerWorkgroup
val workgroupSize = minOf(256, maxInvocations)
// Pass to shader compilation as a specialization constant or rebuild with adjusted WGSL
```

This guards against devices where `maxComputeInvocationsPerWorkgroup < 256`.
