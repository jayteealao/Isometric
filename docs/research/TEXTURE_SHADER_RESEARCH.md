# Textures & Shaders on Isometric Faces: Research Document

## Executive Summary

The Isometric library currently renders **flat-colored polygons only** — no textures, no UV coordinates, no shaders, no GPU effects. Each face gets a single `IsoColor` modified by a dot-product lighting calculation. This document synthesizes extensive research on how to add texture and shader support to polygon faces, covering techniques for both the current Android Canvas renderer and a future WebGPU renderer.

**Key finding:** Affine texture mapping is mathematically correct for isometric (orthographic) projection — no perspective correction is needed. This means the simplest approaches (`BitmapShader` + `Matrix.setPolyToPoly` on Android Canvas, or UV-interpolated `textureSample` on WebGPU) will produce visually correct results.

---

## Table of Contents

1. [Current Rendering Pipeline](#1-current-rendering-pipeline)
2. [Texture Mapping Techniques](#2-texture-mapping-techniques)
   - 2.1 [Affine Texture Mapping (Why It Works for Isometric)](#21-affine-texture-mapping)
   - 2.2 [Android Canvas: BitmapShader + Matrix](#22-android-canvas-bitmapshader--matrix)
   - 2.3 [Android Canvas: drawBitmapMesh](#23-android-canvas-drawbitmapmesh)
   - 2.4 [Compose: ShaderBrush + ImageShader](#24-compose-shaderbrush--imageshader)
   - 2.5 [AGSL RuntimeShader (API 33+)](#25-agsl-runtimeshader-api-33)
   - 2.6 [WebGPU: Fragment Shader + UV Interpolation](#26-webgpu-fragment-shader--uv-interpolation)
3. [Shader Effects on Faces](#3-shader-effects-on-faces)
   - 3.1 [Compose Gradient Brushes](#31-compose-gradient-brushes)
   - 3.2 [AGSL Custom Per-Pixel Effects](#32-agsl-custom-per-pixel-effects)
   - 3.3 [Normal Map Lighting](#33-normal-map-lighting)
   - 3.4 [Common 2D Face Effects](#34-common-2d-face-effects)
   - 3.5 [Android Canvas ColorFilter](#35-android-canvas-colorfilter)
   - 3.6 [Compose PathEffect](#36-compose-patheffect)
   - 3.7 [WebGPU WGSL Shader Effects](#37-webgpu-wgsl-shader-effects)
4. [UV Coordinate Generation](#4-uv-coordinate-generation)
   - 4.1 [Per-Shape UV Strategies](#41-per-shape-uv-strategies)
   - 4.2 [Automatic UV from Projection Type](#42-automatic-uv-from-projection-type)
   - 4.3 [Isometric Foreshortening Correction](#43-isometric-foreshortening-correction)
5. [Texture Atlas Architecture](#5-texture-atlas-architecture)
   - 5.1 [Why Atlases Are Essential](#51-why-atlases-are-essential)
   - 5.2 [Packing Algorithms](#52-packing-algorithms)
   - 5.3 [Texture Bleeding and Mitigations](#53-texture-bleeding-and-mitigations)
   - 5.4 [WebGPU Texture Arrays vs Atlases](#54-webgpu-texture-arrays-vs-atlases)
6. [Per-Face Material Assignment](#6-per-face-material-assignment)
   - 6.1 [Industry Patterns](#61-industry-patterns)
   - 6.2 [Proposed API for Isometric Library](#62-proposed-api-for-isometric-library)
7. [Mobile GPU Considerations](#7-mobile-gpu-considerations)
   - 7.1 [Texture Compression (ETC2/ASTC)](#71-texture-compression-etc2astc)
   - 7.2 [Mipmap Filtering](#72-mipmap-filtering)
   - 7.3 [Memory Budgets](#73-memory-budgets)
   - 7.4 [Texture Cache Architecture](#74-texture-cache-architecture)
8. [Architecture & Developer Experience](#8-architecture--developer-experience)
   - 8.1 [Material Type Hierarchy](#81-material-type-hierarchy)
   - 8.2 [Kotlin DSL API Design](#82-kotlin-dsl-api-design)
   - 8.3 [Progressive Enhancement Strategy](#83-progressive-enhancement-strategy)
   - 8.4 [Error Handling & Fallback Chain](#84-error-handling--fallback-chain)
   - 8.5 [Backward Compatibility](#85-backward-compatibility)
9. [Integration Points in Current Codebase](#9-integration-points-in-current-codebase)
10. [Common Pitfalls & Mitigations](#10-common-pitfalls--mitigations)
11. [Testing Strategies](#11-testing-strategies)
12. [Recommended Implementation Plan](#12-recommended-implementation-plan)
13. [Unified WebGPU + Texture/Shader Architecture](#13-unified-webgpu--textureshader-architecture)
   - 13.1 [Why Both Efforts Converge](#131-why-both-efforts-converge)
   - 13.2 [The Combined GPU Pipeline](#132-the-combined-gpu-pipeline)
   - 13.3 [Texture Management on WebGPU](#133-texture-management-on-webgpu)
   - 13.4 [Material System Across Both Backends](#134-material-system-across-both-backends)
   - 13.5 [Compute Shaders for Texture Work](#135-compute-shaders-for-texture-work)
   - 13.6 [Per-Face Materials with Instanced Rendering](#136-per-face-materials-with-instanced-rendering)
   - 13.7 [Normal Map Lighting in the GPU Pipeline](#137-normal-map-lighting-in-the-gpu-pipeline)
   - 13.8 [Shared Material Definition, Divergent Resolution](#138-shared-material-definition-divergent-resolution)
   - 13.9 [Canvas-to-WebGPU Texture Migration Path](#139-canvas-to-webgpu-texture-migration-path)
   - 13.10 [Combined Frame Budget Analysis](#1310-combined-frame-budget-analysis)
   - 13.11 [Risks of the Combined Approach](#1311-risks-of-the-combined-approach)
   - 13.12 [Unified Implementation Roadmap](#1312-unified-implementation-roadmap)
14. [Sources](#14-sources)

---

## 1. Current Rendering Pipeline

### How a Shape Becomes Pixels

```
Shape (e.g., Prism)
  → shape.orderedPaths()           // Decompose into faces (Path objects with 3D points)
  → IsometricEngine.add(path, color)  // Register each face
  → engine.prepare()
      → translatePoint()           // 3D→2D projection per vertex
      → cullPath()                 // Back-face culling via cross product
      → itemInDrawingBounds()      // Viewport clipping
      → transformColor()           // Lighting: normal·lightDir dot product
      → sortPaths()                // O(N²) depth sort
  → PreparedScene(commands)        // List<RenderCommand>
  → renderer.render()
      → drawPath(path, color, Fill)   // One Canvas draw call per face
```

### Current RenderCommand

```kotlin
// isometric-core: RenderCommand.kt
data class RenderCommand(
    val id: String,
    val points: List<Point2D>,      // 2D screen-space polygon vertices
    val color: IsoColor,            // Color WITH lighting already applied
    val originalPath: Path,         // Original 3D path (for hit testing)
    val originalShape: Shape?       // Parent shape reference
)
```

### What's Missing

- **No UV coordinates** — faces have no concept of texture mapping
- **No material abstraction** — only `IsoColor` (flat color)
- **No shader integration** — `Paint` objects use solid color only
- **No texture loading/caching** — no bitmap management

### Current Drawing Code

**Compose path** (`ComposeRenderer.kt:21-33`):
```kotlin
drawPath(path, color, style = Fill)
if (drawStroke) {
    drawPath(path, Color.Black.copy(alpha = 0.1f), style = Stroke(width = strokeWidth))
}
```

**Native path** (`IsometricRenderer.kt:196-229`):
```kotlin
fillPaint.color = command.color.toAndroidColor()
canvas.nativeCanvas.drawPath(nativePath, fillPaint)
```

The native renderer's `Paint` object is the **primary integration point** for textures — Android's `Paint.setShader()` accepts `BitmapShader` for texture fills.

### Face Counts by Shape

| Shape | Faces | Vertex Count/Face | Notes |
|-------|-------|-------------------|-------|
| Prism | 6 | 4 (quads) | Rectangular box — most common |
| Pyramid | 4 | 3 (triangles) | Square base implicit |
| Cylinder | 2+N | Varies | N=20 default → 22 faces |
| Octahedron | 8 | 3 (triangles) | Diamond shape |
| Stairs | 2+2N | Varies | N=10 → 22 faces |
| Knot | ~20 | Mixed | Composite of prisms |

---

## 2. Texture Mapping Techniques

### 2.1 Affine Texture Mapping

**Why It Works for Isometric**

Affine texture mapping linearly interpolates (u,v) texture coordinates across a polygon in screen space: `(u,v) = A × (x,y)` where A is an affine matrix. This produces **mathematically correct results** under orthographic/parallel projection because all points on a surface are at the same effective depth. There is no foreshortening, so the linear interpolation assumption holds perfectly.

This is unlike perspective projection where affine mapping causes the "PS1 wobble" effect. For this engine's `translatePoint()` (which performs an orthographic projection), affine mapping is both fast and correct.

**Performance**: The fastest form of texture mapping — only linear interpolation, no per-pixel division. On the PS1, this was the only option (no hardware perspective divide).

**Implication**: The simplest implementation approaches will produce visually correct results for this engine.

### 2.2 Android Canvas: BitmapShader + Matrix

The most direct approach for filling arbitrary polygons with textures on Android Canvas.

```kotlin
// Create shader from texture bitmap
val bitmapShader = BitmapShader(
    textureBitmap,
    Shader.TileMode.REPEAT,
    Shader.TileMode.REPEAT
)

// Compute affine transform: texture corners → projected face corners
val shaderMatrix = Matrix()
shaderMatrix.setPolyToPoly(
    // Source: texture rectangle corners
    floatArrayOf(0f, 0f, texW, 0f, texW, texH, 0f, texH),
    0,
    // Destination: isometric face corners (from RenderCommand.points)
    floatArrayOf(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y),
    0,
    4  // 4-point mapping
)
bitmapShader.setLocalMatrix(shaderMatrix)

// Apply to paint and draw
fillPaint.shader = bitmapShader
canvas.drawPath(facePath, fillPaint)
```

**Key details:**
- `Matrix.setPolyToPoly` with 4 points computes a perspective transform; with 3 points it computes affine
- For isometric parallelogram faces, 3-point affine is sufficient and more stable
- Cache `Paint` objects across frames — never allocate in the draw loop
- Hardware-accelerated via Skia

### 2.3 Android Canvas: drawBitmapMesh

Divides a bitmap into a mesh grid with repositionable control points:

```kotlin
val meshWidth = 4   // 4x4 grid for smooth warping
val meshHeight = 4
val verts = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)
// Fill verts with isometric-projected positions...
canvas.drawBitmapMesh(textureBitmap, meshWidth, meshHeight, verts, 0, null, 0, null)
```

**Trade-offs vs BitmapShader:**
- (+) More control over warping for non-planar distortions
- (-) Only rectangular mesh topology — awkward for triangular faces
- (-) More complex setup than BitmapShader + Matrix

**Recommendation**: Use `BitmapShader + Matrix` for quad faces; `drawBitmapMesh` only if non-affine warping is needed.

### 2.4 Compose: ShaderBrush + ImageShader

The Compose-idiomatic approach for textured polygon fills:

```kotlin
val textureBrush = ShaderBrush(
    ImageShader(
        ImageBitmap.imageResource(id = R.drawable.brick),
        tileModeX = TileMode.Repeated,
        tileModeY = TileMode.Repeated
    )
)

Canvas(modifier = Modifier.fillMaxSize()) {
    drawPath(facePath, brush = textureBrush, style = Fill)
}
```

**Limitation**: `ShaderBrush` fills in screen space — to align the texture to a face's local coordinate system, a `Matrix` transformation must be applied to the underlying shader. This requires dropping to `drawIntoCanvas` to access the native Android shader APIs:

```kotlin
drawIntoCanvas { canvas ->
    val nativeShader = BitmapShader(bitmap, TileMode.REPEAT, TileMode.REPEAT)
    nativeShader.setLocalMatrix(affineMatrix)
    fillPaint.shader = nativeShader
    canvas.nativeCanvas.drawPath(nativePath, fillPaint)
}
```

**Best practice**: Use `remember {}` for `ShaderBrush` instances to prevent reallocation on recomposition.

### 2.5 AGSL RuntimeShader (API 33+)

AGSL provides per-pixel programmability via GPU-compiled shaders:

```kotlin
@Language("AGSL")
val ISOMETRIC_FACE_SHADER = """
    uniform shader faceTexture;
    uniform float2 faceOrigin;   // screen-space origin of the face
    uniform float2 faceAxisU;    // screen-space U axis direction
    uniform float2 faceAxisV;    // screen-space V axis direction
    uniform float2 texSize;

    half4 main(float2 fragCoord) {
        // Convert screen coordinates to face-local UV
        float2 local = fragCoord - faceOrigin;
        float det = faceAxisU.x * faceAxisV.y - faceAxisU.y * faceAxisV.x;
        float u = (local.x * faceAxisV.y - local.y * faceAxisV.x) / det;
        float v = (faceAxisU.x * local.y - faceAxisU.y * local.x) / det;

        return faceTexture.eval(float2(u, v) * texSize);
    }
""".trimIndent()
```

**Key AGSL notes:**
- Coordinates are pixel-space (upper-left origin), NOT normalized 0-1 like GLSL
- `eval()` replaces GLSL's `texture()` for sampling child shaders
- Use `setInputShader("faceTexture", bitmapShader)` for color images
- Use `setInputBuffer("name", bitmapShader)` for non-color data (normals, heightmaps)
- Shader compilation cost is one-time; uniform updates are cheap per-frame
- **Requires Android 13+ (API 33)**

**Fallback**: Pre-API 33 devices must use `BitmapShader + Matrix`.

### 2.6 WebGPU: Fragment Shader + UV Interpolation

The standard WebGPU approach — UV coordinates as vertex attributes, GPU-interpolated:

```wgsl
// Vertex shader
struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) uv: vec2<f32>,
};

@vertex
fn vs(@location(0) pos: vec2<f32>, @location(1) uv: vec2<f32>) -> VertexOutput {
    var out: VertexOutput;
    out.position = vec4<f32>(pos, 0.0, 1.0);
    out.uv = uv;
    return out;
}

// Fragment shader
@group(0) @binding(0) var diffuseTexture: texture_2d<f32>;
@group(0) @binding(1) var diffuseSampler: sampler;

@fragment
fn fs(in: VertexOutput) -> @location(0) vec4<f32> {
    return textureSample(diffuseTexture, diffuseSampler, in.uv);
}
```

**Key WebGPU notes:**
- UV coordinates range [0,1] and are resolution-independent
- The rasterizer automatically interpolates UVs between triangle vertices
- `textureSample()` can ONLY be called in fragment shaders, in uniform control flow
- Textures and samplers are separate objects (unlike combined samplers in GLSL)
- For isometric quad faces, UVs of (0,0), (1,0), (1,1), (0,1) map the full texture

---

## 3. Shader Effects on Faces

### 3.1 Compose Gradient Brushes

Built-in brushes that work with existing `drawPath()`:

```kotlin
// Linear gradient across face
val gradient = Brush.linearGradient(
    colors = listOf(Color(0xFF1A237E), Color(0xFF42A5F5)),
    start = Offset(faceTopX, faceTopY),
    end = Offset(faceBottomX, faceBottomY)
)
drawPath(facePath, brush = gradient, style = Fill)

// Radial gradient (light source effect)
val light = Brush.radialGradient(
    colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
    center = Offset(lightX, lightY),
    radius = 200f
)
```

**TileMode options**: `Repeat`, `Mirror`, `Clamp`, `Decal` (transparent outside bounds).

### 3.2 AGSL Custom Per-Pixel Effects

GPU-accelerated effects via RuntimeShader (API 33+):

```agsl
// Brick pattern procedural texture
uniform float2 resolution;
uniform half4 mortarColor;
uniform half4 brickColor;

half4 main(float2 coord) {
    float2 uv = coord / resolution;
    float2 brickUV = fract(uv * float2(8.0, 16.0));
    float row = floor(uv.y * 16.0);
    if (mod(row, 2.0) > 0.5) brickUV.x = fract(brickUV.x + 0.5);

    float mortar = step(0.05, brickUV.x) * step(0.05, brickUV.y);
    return mix(mortarColor, brickColor, mortar);
}
```

**Composable integration:**
```kotlin
Modifier.drawWithCache {
    val shader = RuntimeShader(BRICK_SHADER)
    val brush = ShaderBrush(shader)
    onDrawBehind {
        shader.setFloatUniform("resolution", size.width, size.height)
        drawPath(facePath, brush)
    }
}
```

### 3.3 Normal Map Lighting

Normal maps encode per-pixel surface direction, creating depth illusion on flat faces:

**Production examples**: Graveyard Keeper, Pillars of Eternity, Godot 4 PointLight2D

**Shader approach** (AGSL):
```agsl
uniform shader normalMap;
uniform shader diffuseTexture;
uniform float3 lightPos;
uniform float3 lightColor;

half4 main(float2 coord) {
    half4 normalSample = normalMap.eval(coord);
    vec3 normal = normalSample.rgb * 2.0 - 1.0;  // Unpack [0,1] → [-1,1]

    vec3 lightDir = normalize(lightPos - vec3(coord, 0.0));
    float diffuse = max(dot(normal, lightDir), 0.0);

    half4 texColor = diffuseTexture.eval(coord);
    return half4(texColor.rgb * diffuse * lightColor, texColor.a);
}
```

**For the engine**: Normal maps would enhance the existing `transformColor()` lighting — instead of flat per-face shading, each pixel within a face would have unique lighting based on the normal map. The result is faces that appear to have surface detail (bricks, wood grain, etc.) reacting to light.

### 3.4 Common 2D Face Effects

| Effect | Technique | Use Case |
|--------|-----------|----------|
| Glow/Outline | SDF-based alpha threshold or multi-pass edge detection | Selection highlight |
| Tint/Color Shift | Uniform color multiply or HSL conversion | Team colors, damage flash |
| Dissolve | Noise texture threshold against alpha | Destruction, transitions |
| Distortion | UV offset via noise/sine function | Heat haze, portals |
| Shadow Projection | Vertex skew shader | Ground shadows |
| Parallax Mapping | UV offset based on view angle + heightmap | Depth illusion |

### 3.5 Android Canvas ColorFilter

Lightweight per-face effects without custom shaders:

**LightingColorFilter** — simulates directional lighting:
```kotlin
// Darken face (shadow side)
paint.colorFilter = LightingColorFilter(0xFF808080.toInt(), 0x00000000)

// Warm tint (sunlit side)
paint.colorFilter = LightingColorFilter(0xFFFFFFFF.toInt(), 0x00201000)
```

**ColorMatrixColorFilter** — full color transformation:
```kotlin
// Sepia effect
val sepia = ColorMatrixColorFilter(ColorMatrix().apply {
    setSaturation(0f)
    // Apply sepia tint matrix
})
paint.colorFilter = sepia
```

**ComposeShader** — combine two shaders with blend mode:
```kotlin
val composedShader = ComposeShader(
    bitmapShader,   // Texture
    gradientShader, // Lighting overlay
    PorterDuff.Mode.MULTIPLY
)
paint.shader = composedShader
```

### 3.6 Compose PathEffect

For face outlines and decorative borders:

```kotlin
// Dashed selection outline
val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f), phase = 0f)
drawPath(facePath, Color.Yellow, style = Stroke(width = 2f, pathEffect = dashEffect))

// Stamped pattern along edge
val stamp = Path().apply { addOval(Rect(0f, 0f, 6f, 6f)) }
val stampEffect = PathEffect.stampedPathEffect(
    shape = stamp, advance = 15f, phase = 0f,
    style = StampedPathEffectStyle.Translate
)

// Chain multiple effects
val chainedEffect = PathEffect.chainPathEffect(dashEffect, cornerEffect)
```

### 3.7 WebGPU WGSL Shader Effects

**Tinted texture with lighting:**
```wgsl
@group(0) @binding(0) var diffuse: texture_2d<f32>;
@group(0) @binding(1) var normal: texture_2d<f32>;
@group(0) @binding(2) var samp: sampler;

struct Uniforms {
    lightDir: vec3<f32>,
    tintColor: vec4<f32>,
    ambientStrength: f32,
};
@group(1) @binding(0) var<uniform> u: Uniforms;

@fragment
fn fs(in: VertexOutput) -> @location(0) vec4<f32> {
    let texColor = textureSample(diffuse, samp, in.uv) * u.tintColor;
    let n = textureSample(normal, samp, in.uv).rgb * 2.0 - 1.0;
    let diffuseLight = max(dot(n, u.lightDir), 0.0);
    let light = u.ambientStrength + (1.0 - u.ambientStrength) * diffuseLight;
    return vec4<f32>(texColor.rgb * light, texColor.a);
}
```

**Instanced face rendering with per-face tint:**
```wgsl
struct InstanceData {
    @location(3) tintColor: vec4<f32>,
    @location(4) uvOffset: vec2<f32>,
    @location(5) uvScale: vec2<f32>,
};

@fragment
fn fs(in: FragInput) -> @location(0) vec4<f32> {
    let atlasUV = in.uv * in.uvScale + in.uvOffset;
    return textureSample(atlas, samp, atlasUV) * in.tintColor;
}
```

---

## 4. UV Coordinate Generation

### 4.1 Per-Shape UV Strategies

**Prism (6 quad faces)**:
Each face is a planar quad in 3D. UV generation uses the face's local 2D coordinate system:

```
Top face (XY plane):    u = (x - origin.x) / dx,  v = (y - origin.y) / dy
Front face (XZ plane):  u = (x - origin.x) / dx,  v = (z - origin.z) / dz
Right face (YZ plane):  u = (y - origin.y) / dy,  v = (z - origin.z) / dz
```

The back/left/bottom faces mirror these with reversed winding.

**Pyramid (4 triangular faces)**:
Triangular UV mapping with 3 UV points per face. Each triangle gets UVs of (0,0), (1,0), (0.5,1).

**Cylinder (N side quads + 2 caps)**:
- Side quads: `u = angle / (2π)`, `v = height / totalHeight`
- Caps: polar mapping `u = 0.5 + r*cos(θ)`, `v = 0.5 + r*sin(θ)`

### 4.2 Automatic UV from Projection Type

| Method | Best For | How It Works |
|--------|----------|-------------|
| **Planar** | Flat faces | Project UVs through plane perpendicular to face normal |
| **Box/Cubic** | Prisms | Project from 6 directions, pick best per face based on normal |
| **Cylindrical** | Cylinders | Wrap UVs around circumference |
| **Spherical** | Octahedrons | Latitude/longitude mapping |

**Recommended for this library**: Box/cubic projection for Prism shapes (most common), with automatic selection based on face normal direction. This eliminates manual UV assignment for the common case.

### 4.3 Isometric Foreshortening Correction

In isometric projection, edges parallel to coordinate axes are shortened by factor **0.8165** (= √(2/3)). The y-axis remains vertical; x and z axes make 30° angles with the horizontal.

**For texture mapping**: UV coordinates should be computed in 3D space (before projection), then the isometric transform naturally handles the visual foreshortening. If UVs are computed in screen space after projection, the foreshortening factor must be divided out to avoid texture distortion.

**In practice**: Compute UVs from the 3D `Path.points`, not from the projected 2D `RenderCommand.points`.

---

## 5. Texture Atlas Architecture

### 5.1 Why Atlases Are Essential

- **Android Canvas**: Each shader change (different texture on Paint) is a state change. Batching faces by texture reduces overhead.
- **WebGPU**: Max 16 sampled textures per shader stage. Atlases avoid bind group changes. All sprites sharing an atlas render in a single draw call.
- **Memory**: One large texture has less overhead than many small textures (fewer allocations, better cache coherence).

### 5.2 Packing Algorithms

| Algorithm | Speed | Efficiency | Best For |
|-----------|-------|-----------|----------|
| **MaxRects** | Medium | Best (offline) | Pre-built atlases, final builds |
| **Shelf** | Fast | Good for similar heights | Uniform isometric tiles |
| **Skyline** | Fast | Good | Dynamic/runtime packing |
| **Binary Tree** | Medium | Good | Frequently changing sets |

**Recommendation for this library**: Isometric tile textures are typically uniform in size — **Shelf packing** is simplest and most efficient. For mixed-size face textures, **MaxRects** provides best space utilization.

**Available implementations**:
- [maxrects-packer](https://github.com/soimy/maxrects-packer) — creates minimum bins near power-of-2 sizes
- [smol-atlas](https://github.com/aras-p/smol-atlas) — C++ Shelf packer with item removal support

### 5.3 Texture Bleeding and Mitigations

**The Problem**: When sampling near the edge of a sprite in an atlas, bilinear filtering or imprecise UV coordinates cause adjacent sprite pixels to bleed through.

**Solutions (in order of effectiveness)**:

1. **Half-pixel UV correction** — inset UVs by 0.5 texels from atlas sprite boundaries:
   ```
   correctedU = u + (0.5 / atlasWidth)   // min edge
   correctedU = u - (0.5 / atlasWidth)   // max edge
   ```

2. **Padding sprites** — add 1-2px gutters between sprites, duplicating edge pixels into the gutter

3. **Disable mipmapping for 2D atlases** — "If you're 2D, don't mipmap. If you're 3D, don't atlas." Mipmaps downsample, mixing adjacent atlas entries.

4. **Use texture arrays** (`texture_2d_array` in WebGPU) — eliminates the problem entirely with separate layers per texture

5. **`GL_NEAREST` filtering** — avoid bilinear interpolation for pixel-art styles

**Isometric-specific note**: Faces are parallelograms when projected. The affine UV transform can push sampling to atlas sprite boundaries. Apply half-pixel correction **after** the affine transform.

### 5.4 WebGPU Texture Arrays vs Atlases

**`texture_2d_array`**: An array of 2D textures where all layers share dimensions/format.

```wgsl
@group(0) @binding(0) var texArray: texture_2d_array<f32>;

@fragment
fn fs(in: FragInput) -> @location(0) vec4<f32> {
    return textureSample(texArray, samp, in.uv, i32(in.textureLayer));
}
```

**Constraint**: All layers must have identical dimensions and format.

**Trade-off**: Texture arrays avoid bleeding entirely but waste memory if face textures vary in size. Atlases are more memory-efficient but require bleeding mitigation.

**WebGPU bind group organization (by update frequency)**:
- Group 0: Per-frame (camera, time, lighting)
- Group 1: Per-material (atlas texture, sampler)
- Group 2: Per-draw (transforms, instance data)

---

## 6. Per-Face Material Assignment

### 6.1 Industry Patterns

| Engine | Approach |
|--------|----------|
| **Unity** | Multiple materials via submeshes; `MeshRenderer.materials` array |
| **Three.js** | `geometry.addGroup(start, count, materialIndex)` + materials array |
| **Godot** | `material` property per CanvasItem; shader inheritance from parent |
| **Blender** | Material IDs per polygon; multi-material containers |
| **General** | Sort faces by material → store start/end indices → one draw call per group |

### 6.2 Proposed API for Isometric Library

**Option A: Material map by face type** (most explicit):

```kotlin
Shape(
    shape = Prism(origin, 1.0, 1.0, 1.0),
    materials = mapOf(
        PrismFace.TOP to grassMaterial,
        PrismFace.FRONT to dirtMaterial,
        PrismFace.RIGHT to dirtMaterial
    ),
    defaultMaterial = Material.FlatColor(IsoColor.GRAY)
)
```

**Option B: Per-face-group material** (simpler for common case):

```kotlin
Shape(
    shape = Prism(origin),
    material = PerFaceMaterial(
        top = grass,
        sides = dirt,
        bottom = null  // invisible / culled
    )
)
```

**Option C: Single material** (simplest, covers 80% of cases):

```kotlin
Shape(
    shape = Prism(origin),
    material = texturedMaterial("brick.png", tint = IsoColor.WHITE)
)
```

**How it maps to existing code**: Each face is already a separate `Path` in `shape.orderedPaths()`, and each `Path` gets its own `RenderCommand`. The material would attach at the `Path` level via an extended `SceneItem`.

---

## 7. Mobile GPU Considerations

### 7.1 Texture Compression (ETC2/ASTC)

| Property | ETC2 | ASTC |
|----------|------|------|
| Device support | ~100% (GLES 3.0+) | ~75% active Android |
| Bit rate (RGB) | 4 bpp fixed | 0.89-8 bpp configurable |
| Bit rate (RGBA) | 8 bpp fixed | 0.89-8 bpp configurable |
| Quality | Medium (35-40 dB PSNR) | Medium-High (35-45+ dB) |
| Block size | 4×4 fixed | 4×4 to 12×12 variable |

**ASTC block size guidance**:
- 4×4 (8 bpp): UI, critical textures
- 5×5 (5.12 bpp): Character sprites
- 6×6 (3.56 bpp): Terrain, backgrounds — **best balance for isometric tiles**
- 8×8 (2 bpp): Large decorative textures

**Runtime detection**:
```kotlin
val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS)
val supportsASTC = extensions.contains("GL_KHR_texture_compression_astc_ldr")
```

**Google Play Asset Delivery**: Use `texture { enableSplit true }` in `build.gradle` with `textures#tcf_astc/` and `textures#tcf_etc2/` directories for automatic per-device format delivery.

### 7.2 Mipmap Filtering

**For isometric 2D rendering**: Generally **skip mipmaps**. Faces are rendered at consistent sizes (no distance-based LOD), and mipmaps complicate atlas usage by causing bleeding.

**Exception**: If the engine adds zoom levels where faces appear at significantly different screen sizes, enable mipmaps for standalone textures (not atlas entries).

**Mobile GPU specifics**:
- `LINEAR_MIPMAP_LINEAR` (trilinear) costs **2× on ARM Mali** GPUs — use only where needed
- 8× trilinear anisotropic costs **16×** more than bilinear — avoid on mobile
- Use `mediump` samplers on Mali for **2× speed** vs `highp`
- Target average texture bandwidth ≤1 GB/s, peak ≤3 GB/s

### 7.3 Memory Budgets

| Device Tier | Texture Budget |
|-------------|---------------|
| Low-end | 100-150 MB |
| Mid-range | 200-300 MB |
| High-end | 400-512 MB |

- **67% of active users** are on mid/low-range chipsets
- Mobile memory bandwidth is **10-40× lower** than desktop
- L1 texture cache is typically **1KB** shared among shader core threads
- ASTC compression provides **~75% memory reduction** with minimal quality loss

### 7.4 Texture Cache Architecture

```
TextureCache (LRU, partitioned by type)
  ├── AtlasTextures: LRU[String → AtlasEntry]
  ├── StandaloneTextures: LRU[String → Bitmap]
  └── ProceduralTextures: LRU[String → Bitmap]

BitmapPool (fixed size, GlideBitmapPool-style)
  ├── get(width, height, config) → Bitmap
  ├── getDirty(width, height, config) → Bitmap    // Skip clear for overwrite
  └── recycle(bitmap)                               // Return to pool
```

**Best practices**:
- Use `Bitmap.prepareToDraw()` to pre-upload textures to GPU asynchronously (Android N+)
- Use `BitmapFactory.Options.inBitmap` to decode into existing bitmap memory
- Use Hardware Bitmaps (API 26+) to store pixels only in GPU memory (50% memory savings)
- Reference counting for safe texture reuse (Glide pattern)
- Low-memory listener to downgrade texture quality under pressure

---

## 8. Architecture & Developer Experience

### 8.1 Material Type Hierarchy

```kotlin
sealed interface Material {
    /** Flat color — current behavior, zero overhead */
    data class FlatColor(val color: IsoColor) : Material

    /** Bitmap texture with optional tint */
    data class Textured(
        val texture: TextureSource,
        val tint: IsoColor = IsoColor.WHITE,
        val uvTransform: UvTransform = UvTransform.IDENTITY
    ) : Material

    /** Custom shader effect (API 33+ with fallback) */
    data class Shader(
        val shaderSource: String,
        val uniforms: Map<String, Any> = emptyMap(),
        val fallback: Material = FlatColor(IsoColor.WHITE)
    ) : Material
}

sealed interface TextureSource {
    data class Resource(@DrawableRes val resId: Int) : TextureSource
    data class Asset(val path: String) : TextureSource
    data class Bitmap(val bitmap: android.graphics.Bitmap) : TextureSource
    data class Atlas(val atlasId: String, val region: UvRect) : TextureSource
}

data class UvTransform(
    val scaleU: Double = 1.0,
    val scaleV: Double = 1.0,
    val offsetU: Double = 0.0,
    val offsetV: Double = 0.0,
    val rotation: Double = 0.0
) {
    companion object {
        val IDENTITY = UvTransform()
    }
}
```

### 8.2 Kotlin DSL API Design

The idiomatic Kotlin approach — type-safe builder with named parameters and defaults:

```kotlin
// Simple: flat color (backward compatible)
Shape(Prism(origin), IsoColor(33.0, 150.0, 243.0))

// Textured face
Shape(
    shape = Prism(origin),
    material = textured(R.drawable.brick) {
        tint = IsoColor.WHITE
        uvScale(2.0, 2.0)  // Repeat texture 2x
    }
)

// Per-face materials
Shape(
    shape = Prism(origin),
    material = perFace {
        top = textured(R.drawable.grass)
        sides = textured(R.drawable.dirt)
    }
)

// Shader effect
Shape(
    shape = Prism(origin),
    material = shader(GLOW_SHADER) {
        uniform("glowColor", IsoColor(255.0, 200.0, 0.0))
        uniform("intensity", 0.8f)
        fallback = flatColor(IsoColor.YELLOW)
    }
)
```

**Key DX principles from research**:
- **Progressive complexity**: `FlatColor` → `Textured` → `Shader` (Three.js pattern)
- **Separation of loading and usage**: `TextureSource` decoupled from `Material`
- **Reusability**: One material instance shared across multiple shapes
- **Defaults**: Everything optional with sensible defaults
- **DSL markers**: Use `@DslMarker` to prevent scope leaking

### 8.3 Progressive Enhancement Strategy

```
Level 0: FlatColor (current)
  └── IsoColor solid fill, no texture, no shader
  └── Works everywhere, zero overhead

Level 1: Textured
  └── BitmapShader + Matrix (all Android versions)
  └── Per-face or per-shape textures
  └── Automatic UV generation

Level 2: Shader
  └── AGSL RuntimeShader (API 33+)
  └── Normal maps, procedural textures, custom effects
  └── Auto-fallback to Level 1 or Level 0

Level 3: WebGPU (future)
  └── Full GPU pipeline with compute + render
  └── Instanced rendering with per-face materials
  └── WGSL fragment shaders
```

### 8.4 Error Handling & Fallback Chain

```
Intended shader → Simplified shader → Textured → Flat color → Error checkerboard
```

**Industry standard**: Black-and-magenta checkerboard is the universal "missing texture" indicator (Minecraft, Source engine, Unity). The game continues running; the checkerboard makes errors visible to developers but non-fatal to users.

```kotlin
// Fallback chain implementation
fun resolveMaterial(material: Material, textureCache: TextureCache): ResolvedMaterial {
    return when (material) {
        is Material.Shader -> {
            if (Build.VERSION.SDK_INT >= 33) {
                try { ResolvedMaterial.Shader(RuntimeShader(material.shaderSource)) }
                catch (e: Exception) { resolveMaterial(material.fallback, textureCache) }
            } else {
                resolveMaterial(material.fallback, textureCache)
            }
        }
        is Material.Textured -> {
            val bitmap = textureCache.get(material.texture)
            if (bitmap != null) ResolvedMaterial.Textured(bitmap, material.tint)
            else ResolvedMaterial.FlatColor(material.tint)
        }
        is Material.FlatColor -> ResolvedMaterial.FlatColor(material.color)
    }
}
```

### 8.5 Backward Compatibility

The current API uses `color: IsoColor` everywhere. Compatibility strategy:

```kotlin
// Current API continues working unchanged
@Composable
fun IsometricScope.Shape(shape: Shape, color: IsoColor) {
    Shape(shape = shape, material = Material.FlatColor(color))
}

// New API adds material parameter
@Composable
fun IsometricScope.Shape(shape: Shape, material: Material) {
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(shape, material) },
        update = {
            set(shape) { this.shape = it; markDirty() }
            set(material) { this.material = it; markDirty() }
        }
    )
}
```

`RenderCommand` gains an optional `material` field alongside the existing `color`:
```kotlin
data class RenderCommand(
    // ... existing fields ...
    val material: Material? = null,
    val uvCoords: List<UvCoord>? = null
)
```

---

## 9. Integration Points in Current Codebase

### Core Module (`isometric-core`)

| File | Change | Purpose |
|------|--------|---------|
| `Path.kt` | Add optional `uvCoords: List<UvCoord>?` | Per-vertex UV coordinates |
| `RenderCommand.kt` | Add `material: Material?`, `uvCoords: List<UvCoord>?` | Carry texture data through pipeline |
| `IsometricEngine.kt` | Pass material through `prepare()` pipeline | Material flows from SceneItem → RenderCommand |
| New: `Material.kt` | Sealed interface hierarchy | Platform-agnostic material definition |
| New: `UvCoord.kt` | `data class UvCoord(val u: Double, val v: Double)` | UV coordinate type |
| Shape classes | Add UV generation per face type | Automatic UV mapping |

### Compose Module (`isometric-compose`)

| File | Change | Purpose |
|------|--------|---------|
| `IsometricRenderer.kt` | Add `renderTextured()` method | Handle textured `RenderCommand`s |
| `IsometricComposables.kt` | Add `material` parameter to `Shape()` | Composable API extension |
| `IsometricNode.kt` → `ShapeNode` | Add `material: Material` property | Node carries material data |
| New: `TextureCache.kt` | LRU cache + bitmap pool | Texture resource management |
| New: `MaterialResolver.kt` | Resolve Material → Paint/Brush setup | Platform-specific rendering |

### Rendering Changes

**Native renderer** (`renderNative`):
```kotlin
// Before (current)
fillPaint.color = command.color.toAndroidColor()
canvas.nativeCanvas.drawPath(nativePath, fillPaint)

// After (with texture support)
when (val resolved = materialResolver.resolve(command)) {
    is ResolvedMaterial.FlatColor -> {
        fillPaint.shader = null
        fillPaint.color = resolved.color.toAndroidColor()
    }
    is ResolvedMaterial.Textured -> {
        val shader = BitmapShader(resolved.bitmap, TileMode.CLAMP, TileMode.CLAMP)
        shader.setLocalMatrix(computeAffineMatrix(command))
        fillPaint.shader = shader
    }
    is ResolvedMaterial.Shader -> {
        fillPaint.shader = resolved.runtimeShader
        // Update uniforms per face
    }
}
canvas.nativeCanvas.drawPath(nativePath, fillPaint)
```

---

## 10. Common Pitfalls & Mitigations

| Pitfall | Impact | Mitigation |
|---------|--------|------------|
| **Texture bleeding in atlas** | Visible seams between face textures | Half-pixel UV correction + 2px padding + no mipmaps for 2D |
| **GPU memory exhaustion** | Crash or severe jank | LRU cache with device-tier budgets; `ComponentCallbacks2.onTrimMemory` listener |
| **GC jank from bitmap allocation** | Frame drops during texture load | Bitmap pool with `inBitmap` reuse (GlideBitmapPool pattern) |
| **Shader compilation stalls** | First-frame jank when new shader appears | Async compilation; keep previous shader until new one ready |
| **Missing texture crashes** | App crash | Graceful fallback chain ending at magenta checkerboard |
| **UV distortion on faces** | Texture appears stretched/skewed | Compute UVs in 3D space (before projection), not screen space |
| **Draw call explosion** | Severe performance regression | Sort faces by material, batch same-material faces, use atlas |
| **Thread safety** | Corruption in texture cache | Current `@Volatile` dirty flags work; texture cache needs `ConcurrentHashMap` or mutex |
| **API 33 shader unavailable** | Shader effects only on newer devices | Mandatory fallback field on `Material.Shader` |
| **Texture loaded at wrong size** | Excess memory or blurry textures | `BitmapFactory.Options.inSampleSize` based on target face screen size |
| **Paint object allocation in draw loop** | GC pressure every frame | `lazy { Paint() }` (already done in codebase) — reuse and mutate |

---

## 11. Testing Strategies

### Unit Tests

| Test | What | How |
|------|------|-----|
| UV math | UvCoord generation for each shape type | Assert UV corners for known Prism/Pyramid |
| Affine matrix | `computeAffineMatrix()` correctness | Assert matrix maps texture corners to face corners |
| Material resolution | Fallback chain | Mock texture loading failure, verify fallback |
| Atlas packing | UV regions don't overlap | Pack known set, verify no UV overlap |

### Snapshot Tests

The codebase already has `IsometricCanvasSnapshotTest.kt` using Paparazzi — extend with:

| Test | What |
|------|------|
| Flat color prism | Baseline — existing behavior unchanged |
| Textured prism (all faces same) | Single texture mapped to all 6 faces |
| Per-face textured prism | Different textures on top/sides |
| Shader effect face | AGSL gradient or procedural pattern |
| Missing texture fallback | Verify checkerboard appears |
| Atlas-based rendering | Multiple shapes sharing one atlas |

### Performance Tests

| Test | Metric | Target |
|------|--------|--------|
| Texture cache hit rate | % cache hits | >95% for static scenes |
| Atlas memory usage | Total atlas bytes | Within device-tier budget |
| Draw call count | Calls per frame | ≤ (number of unique materials) |
| Frame time with textures | ms/frame for N textured faces | < 16ms for N=100 |

---

## 12. Recommended Implementation Plan

### Phase 1: Foundation (Core + Android Canvas)

1. Add `Material` sealed interface to `isometric-core`
2. Add `UvCoord` data class and UV generation for Prism (most common shape)
3. Extend `RenderCommand` with optional `material` and `uvCoords`
4. Implement `BitmapShader + Matrix` rendering in native renderer
5. Add `TextureCache` with LRU eviction and bitmap pooling
6. Add backward-compatible `Shape(shape, material)` overload to composables
7. Snapshot tests for textured Prism

### Phase 2: Atlas & Per-Face Materials

1. Implement atlas packing (Shelf algorithm for uniform tiles)
2. Add `PrismFace` enum and per-face material API
3. UV generation for remaining shapes (Pyramid, Cylinder, etc.)
4. Material-based face sorting for draw call batching
5. Half-pixel UV correction for atlas bleeding prevention
6. Snapshot tests for per-face materials

### Phase 3: Shader Effects (API 33+)

1. Add `Material.Shader` with AGSL support
2. Implement `MaterialResolver` with fallback chain
3. Normal map support via AGSL `setInputShader`
4. Procedural textures (brick, checkerboard, noise)
5. Shader hot-reload for development builds
6. Snapshot tests with API level conditional execution

### Phase 4: WebGPU Integration (Future)

1. WebGPU vertex buffers with UV attributes
2. WGSL fragment shaders for textured faces
3. Texture atlas as GPU texture with per-instance UV offsets
4. Instanced rendering — one draw call for all faces sharing a material
5. Compute shader for UV generation and atlas coordinate lookup
6. `texture_2d_array` for per-face-type textures

---

## 13. Unified WebGPU + Texture/Shader Architecture

> **Cross-reference**: This section bridges the findings from [WEBGPU_ANALYSIS.md](./WEBGPU_ANALYSIS.md) (compute parallelism, instanced rendering, `AndroidExternalSurface` embedding) with the texture/shader techniques documented above, presenting a unified architecture where both efforts reinforce each other.

### 13.1 Why Both Efforts Converge

The WebGPU analysis (WEBGPU_ANALYSIS.md) focused on **performance** — moving the O(N^2) depth sort and scene preparation to GPU compute shaders, and replacing N sequential draw calls with a single instanced draw. The texture/shader research above focused on **visual richness** — putting images and effects on faces instead of flat colors.

These are not separate features. They are **two halves of the same GPU pipeline**:

```
                        CURRENT (CPU-only, flat color)
                        ═══════════════════════════════
Compose DSL → Node Tree → [CPU] transform, cull, sort, light → [CPU] N × drawPath(color)

                        TARGET (GPU pipeline, textured)
                        ════════════════════════════════
Compose DSL → Node Tree → [GPU Compute] transform, cull, sort, light
                              ↓ (zero-copy buffer sharing)
                          [GPU Render] 1 × instanced draw with texture atlas + WGSL fragment shader
```

The key insight is that **adding textures without WebGPU creates a performance regression** (texture state changes are more expensive than flat color), while **adding WebGPU without textures wastes the fragment shader stage** (you'd have a full GPU render pipeline outputting flat colors). Together, compute shaders handle the performance-critical scene preparation while fragment shaders handle the visual richness — both running on the same GPU in the same frame, with zero CPU-GPU data copies between them.

### 13.2 The Combined GPU Pipeline

Merging WEBGPU_ANALYSIS.md Section 5 (compute pipeline) with this document's Section 2.6 (WebGPU fragment texturing):

```
[CPU] Upload scene data + texture atlas (once, or on change)
    |
[GPU] Compute Pass 1: Parallel vertex transformation
    |   - Input: 3D points buffer, transform uniforms
    |   - Output: 2D projected points buffer, per-face UV coordinates buffer
    |
[GPU] Compute Pass 2: Parallel culling
    |   - Back-face culling (cross product of projected edges)
    |   - Frustum culling (bounds check against viewport)
    |   - Output: visibility flags buffer
    |
[GPU] Compute Pass 3: Spatial hash + depth sort
    |   - Build spatial hash grid (atomicAdd)
    |   - Parallel broad-phase pair detection
    |   - Write sorted draw order to index buffer
    |
[GPU] Compute Pass 4: Lighting
    |   - Per-face: normal · lightDir dot product → tint multiplier
    |   - Per-pixel (if normal maps): deferred to fragment shader
    |   - Output: per-face tint color buffer
    |
[GPU] Compute Pass 5: Populate indirect draw buffer
    |   - Write visible face count, instance offsets
    |   - Write per-instance data: UV offset, UV scale, tint, transform
    |
[GPU] Render Pass: Single instanced textured draw
    |   - Vertex shader: read projected points from buffer
    |   - Fragment shader: sample texture atlas at per-face UVs, apply tint
    |   - Uses drawIndexedIndirect() — GPU decides what to draw
    |
[GPU → Display] Present via AndroidExternalSurface
```

Every stage reads the previous stage's output buffer directly on the GPU — **zero CPU involvement per frame** after the initial scene upload. This is the "GPU-driven rendering" pattern described in WEBGPU_ANALYSIS.md, now extended to include textured output.

### 13.3 Texture Management on WebGPU

On the Canvas path, textures are managed as `android.graphics.Bitmap` objects with `BitmapShader`. On the WebGPU path, the same textures become GPU-resident resources:

**Texture upload (once per atlas change):**
```kotlin
// Create GPU texture from atlas bitmap
val atlasTexture = device.createTexture(GPUTextureDescriptor(
    size = GPUExtent3D(atlasWidth, atlasHeight, 1),
    format = TextureFormat.RGBA8Unorm,
    usage = GPUTextureUsage.TEXTURE_BINDING or GPUTextureUsage.COPY_DST
))

device.queue.writeTexture(
    GPUImageCopyTexture(atlasTexture),
    atlasPixelData,
    GPUImageDataLayout(bytesPerRow = atlasWidth * 4),
    GPUExtent3D(atlasWidth, atlasHeight, 1)
)
```

**Sampler creation (once):**
```kotlin
val sampler = device.createSampler(GPUSamplerDescriptor(
    magFilter = FilterMode.Linear,
    minFilter = FilterMode.Linear,
    addressModeU = AddressMode.ClampToEdge,  // Prevent atlas bleeding
    addressModeV = AddressMode.ClampToEdge
))
```

**Key difference from Canvas path**: On Canvas, each face potentially changes the `Paint.shader` (a state change). On WebGPU, the atlas texture is bound once in bind group 1, and per-face UV offsets come from the instance buffer — **zero state changes between faces**.

### 13.4 Material System Across Both Backends

The `Material` sealed interface (Section 8.1) must resolve differently per backend. The core module defines the abstract material; each renderer resolves it:

```kotlin
// isometric-core: Platform-agnostic material definition
sealed interface Material {
    data class FlatColor(val color: IsoColor) : Material
    data class Textured(
        val texture: TextureSource,
        val tint: IsoColor = IsoColor.WHITE,
        val uvTransform: UvTransform = UvTransform.IDENTITY
    ) : Material
    data class Shader(
        val shaderSource: String,
        val uniforms: Map<String, Any> = emptyMap(),
        val fallback: Material = FlatColor(IsoColor.WHITE)
    ) : Material
}
```

**Canvas backend resolution** (per-face, every frame):
```kotlin
// For each RenderCommand:
when (material) {
    is FlatColor -> { paint.shader = null; paint.color = material.color }
    is Textured  -> { paint.shader = BitmapShader(...); shader.setLocalMatrix(affineMatrix) }
    is Shader    -> { paint.shader = RuntimeShader(material.shaderSource) }
}
canvas.drawPath(path, paint)  // One call per face
```

**WebGPU backend resolution** (batched, once per scene change):
```kotlin
// On scene change: build instance buffer for ALL faces
val instanceData = preparedScene.commands.map { cmd ->
    when (val mat = cmd.material) {
        is FlatColor -> InstanceData(
            uvOffset = vec2(0f, 0f),     // Solid color region in atlas (1x1 white pixel)
            uvScale = vec2(0f, 0f),
            tint = mat.color.toVec4()
        )
        is Textured -> InstanceData(
            uvOffset = atlas.getRegion(mat.texture).offset,
            uvScale = atlas.getRegion(mat.texture).scale,
            tint = mat.tint.toVec4()
        )
        is Shader -> InstanceData(
            // Custom shader materials use a separate render pass or
            // encode shader ID for branching in the uber-fragment-shader
            uvOffset = ..., uvScale = ..., tint = ..., shaderId = mat.id
        )
    }
}
device.queue.writeBuffer(instanceBuffer, instanceData)
// Single drawIndexedIndirect() renders everything
```

**Critical design principle**: The `Material` definition is shared, but the resolution strategy is fundamentally different. Canvas resolves materials **per-face per-frame** (expensive). WebGPU resolves materials **per-scene-change into a batch buffer** (cheap per frame, amortized over many frames via PreparedScene caching).

### 13.5 Compute Shaders for Texture Work

WebGPU compute shaders (WEBGPU_ANALYSIS.md Section 5) can accelerate several texture-related operations that would be expensive on the CPU:

**1. UV Coordinate Generation on GPU**

Instead of computing UVs per-face on the CPU (Section 4.1), a compute shader generates UVs for all faces in parallel:

```wgsl
struct FaceGeometry {
    p0: vec3<f32>, p1: vec3<f32>, p2: vec3<f32>, p3: vec3<f32>,
    faceType: u32,  // 0=top, 1=front, 2=right, 3=back, 4=left, 5=bottom
};

@group(0) @binding(0) var<storage, read> faces: array<FaceGeometry>;
@group(0) @binding(1) var<storage, read_write> uvs: array<vec2<f32>>;

@compute @workgroup_size(256)
fn generateUVs(@builtin(global_invocation_id) id: vec3<u32>) {
    let faceIdx = id.x;
    if (faceIdx >= arrayLength(&faces)) { return; }

    let face = faces[faceIdx];
    let baseUvIdx = faceIdx * 4u;

    // Box projection: select UV axes based on face normal direction
    switch (face.faceType) {
        case 0u, 5u: {  // Top/bottom: project onto XY plane
            let origin = face.p0;
            let axisU = face.p1 - face.p0;
            let axisV = face.p3 - face.p0;
            let lenU = length(axisU);
            let lenV = length(axisV);
            uvs[baseUvIdx + 0u] = vec2<f32>(0.0, 0.0);
            uvs[baseUvIdx + 1u] = vec2<f32>(1.0, 0.0);
            uvs[baseUvIdx + 2u] = vec2<f32>(1.0, 1.0);
            uvs[baseUvIdx + 3u] = vec2<f32>(0.0, 1.0);
        }
        default: {  // Side faces: project onto respective plane
            uvs[baseUvIdx + 0u] = vec2<f32>(0.0, 0.0);
            uvs[baseUvIdx + 1u] = vec2<f32>(1.0, 0.0);
            uvs[baseUvIdx + 2u] = vec2<f32>(1.0, 1.0);
            uvs[baseUvIdx + 3u] = vec2<f32>(0.0, 1.0);
        }
    }
}
```

For 1000 faces, this generates 4000 UV coordinates in a single GPU dispatch (~0.01ms) vs sequential CPU computation.

**2. Atlas Coordinate Lookup on GPU**

A compute shader can translate per-face material IDs into atlas UV offsets:

```wgsl
struct AtlasRegion {
    offset: vec2<f32>,
    scale: vec2<f32>,
};

@group(0) @binding(0) var<storage, read> materialIds: array<u32>;
@group(0) @binding(1) var<storage, read> atlasRegions: array<AtlasRegion>;
@group(0) @binding(2) var<storage, read> faceUVs: array<vec2<f32>>;
@group(0) @binding(3) var<storage, read_write> atlasUVs: array<vec2<f32>>;

@compute @workgroup_size(256)
fn resolveAtlasUVs(@builtin(global_invocation_id) id: vec3<u32>) {
    let vertIdx = id.x;
    if (vertIdx >= arrayLength(&faceUVs)) { return; }

    let faceIdx = vertIdx / 4u;
    let matId = materialIds[faceIdx];
    let region = atlasRegions[matId];

    // Transform face-local UV [0,1] → atlas UV sub-region
    atlasUVs[vertIdx] = faceUVs[vertIdx] * region.scale + region.offset;
}
```

This eliminates per-face material lookups from the render pass entirely.

**3. Dynamic Texture Effects via Compute**

Compute shaders can generate procedural textures directly into the atlas at runtime:

```wgsl
// Generate a brick pattern into a region of the atlas texture
@compute @workgroup_size(16, 16)
fn generateBrickTexture(@builtin(global_invocation_id) id: vec3<u32>) {
    let texCoord = vec2<f32>(f32(id.x), f32(id.y));
    let brickUV = fract(texCoord / vec2<f32>(32.0, 16.0));
    let row = floor(texCoord.y / 16.0);
    var shifted = brickUV;
    if (u32(row) % 2u == 1u) { shifted.x = fract(shifted.x + 0.5); }
    let isMortar = step(shifted.x, 0.05) + step(shifted.y, 0.05);
    let color = select(vec4<f32>(0.7, 0.3, 0.2, 1.0), vec4<f32>(0.5, 0.5, 0.5, 1.0), isMortar > 0.0);
    textureStore(atlasTexture, vec2<i32>(id.xy) + atlasOffset, color);
}
```

This is the WebGPU equivalent of AGSL procedural textures, but runs as a compute dispatch and writes directly into the GPU-resident atlas — no CPU-GPU transfer.

### 13.6 Per-Face Materials with Instanced Rendering

The WEBGPU_ANALYSIS.md recommended instanced rendering for performance (N draw calls → 1). This is where per-face materials (Section 6) become critical — each instance needs its own texture region and tint.

**Vertex buffer layout with per-face material data:**

```
Per-Vertex Buffer (shared quad geometry, 4 vertices):
  [position: vec2<f32>, baseUV: vec2<f32>]

Per-Instance Buffer (one entry per face):
  [
    screenPos: vec2<f32>,      // Projected position from compute pass
    faceVerts: vec4<vec2<f32>>,// 4 projected corners (or transform matrix)
    uvOffset: vec2<f32>,       // Atlas sub-region offset
    uvScale: vec2<f32>,        // Atlas sub-region scale
    tintColor: vec4<f32>,      // Per-face color (includes lighting)
    faceNormal: vec3<f32>,     // For per-pixel lighting (if normal maps used)
  ]
```

**WGSL vertex + fragment shaders:**

```wgsl
struct VertexInput {
    @location(0) position: vec2<f32>,   // Unit quad [0,1]
    @location(1) baseUV: vec2<f32>,     // Unit UV [0,1]
};

struct InstanceInput {
    @location(2) faceCorner0: vec2<f32>,
    @location(3) faceCorner1: vec2<f32>,
    @location(4) faceCorner2: vec2<f32>,
    @location(5) faceCorner3: vec2<f32>,
    @location(6) uvOffset: vec2<f32>,
    @location(7) uvScale: vec2<f32>,
    @location(8) tintColor: vec4<f32>,
};

struct VertexOutput {
    @builtin(position) clipPos: vec4<f32>,
    @location(0) atlasUV: vec2<f32>,
    @location(1) tint: vec4<f32>,
};

@vertex
fn vs(vert: VertexInput, inst: InstanceInput) -> VertexOutput {
    // Bilinear interpolation of face corners using unit quad position
    let top = mix(inst.faceCorner0, inst.faceCorner1, vert.position.x);
    let bot = mix(inst.faceCorner3, inst.faceCorner2, vert.position.x);
    let screenPos = mix(top, bot, vert.position.y);

    // Convert screen coords to clip space
    let clipX = (screenPos.x / viewportWidth) * 2.0 - 1.0;
    let clipY = 1.0 - (screenPos.y / viewportHeight) * 2.0;

    var out: VertexOutput;
    out.clipPos = vec4<f32>(clipX, clipY, 0.0, 1.0);
    out.atlasUV = vert.baseUV * inst.uvScale + inst.uvOffset;
    out.tint = inst.tintColor;
    return out;
}

@group(0) @binding(0) var atlas: texture_2d<f32>;
@group(0) @binding(1) var atlasSampler: sampler;

@fragment
fn fs(in: VertexOutput) -> @location(0) vec4<f32> {
    let texColor = textureSample(atlas, atlasSampler, in.atlasUV);
    return texColor * in.tint;
}
```

**Result**: All faces — regardless of their individual materials — render in a **single `drawIndexedIndirect()` call**. The per-face material variation comes entirely from the instance buffer (UV offset/scale for atlas region, tint for color/lighting), not from bind group changes or separate draw calls.

This contrasts sharply with the Canvas path where each differently-textured face requires a `Paint.setShader()` state change.

### 13.7 Normal Map Lighting in the GPU Pipeline

Section 3.3 described normal map lighting via AGSL on the Canvas path. On the WebGPU path, normal maps integrate naturally into the GPU-driven pipeline:

**Atlas layout**: Pack diffuse textures and their corresponding normal maps into the same atlas (or use a second atlas / `texture_2d_array` layer for normals).

**Fragment shader with normal-mapped lighting:**

```wgsl
@group(0) @binding(0) var diffuseAtlas: texture_2d<f32>;
@group(0) @binding(1) var normalAtlas: texture_2d<f32>;
@group(0) @binding(2) var atlasSampler: sampler;

struct LightUniforms {
    lightDir: vec3<f32>,
    ambientStrength: f32,
    lightColor: vec3<f32>,
};
@group(1) @binding(0) var<uniform> light: LightUniforms;

@fragment
fn fs(in: VertexOutput) -> @location(0) vec4<f32> {
    let texColor = textureSample(diffuseAtlas, atlasSampler, in.atlasUV);
    let normalSample = textureSample(normalAtlas, atlasSampler, in.atlasUV);

    // Unpack normal from [0,1] to [-1,1] range
    let normal = normalize(normalSample.rgb * 2.0 - 1.0);

    // Compute per-pixel lighting
    let diffuse = max(dot(normal, light.lightDir), 0.0);
    let litColor = texColor.rgb * (light.ambientStrength + (1.0 - light.ambientStrength) * diffuse * light.lightColor);

    return vec4<f32>(litColor * in.tint.rgb, texColor.a * in.tint.a);
}
```

**Comparison with current pipeline**: The engine currently computes a single `transformColor()` brightness value per face (flat shading). With normal maps on WebGPU, each **pixel** within a face gets unique lighting based on the normal map — producing surface detail (bricks, wood grain, stone) that reacts to light, at zero additional CPU cost. The per-face `tint` from the compute lighting pass provides the base brightness, while the normal map adds per-pixel variation on top.

**On the Canvas path**: The same normal map effect requires AGSL `RuntimeShader` (API 33+ only) with per-face uniform updates. On WebGPU, it's a single fragment shader that runs for all faces in one draw call.

### 13.8 Shared Material Definition, Divergent Resolution

The architecture must support both backends from a single `Material` definition. Here's how the full resolution pipeline differs:

```
                    Material.Textured("brick.png", tint=WHITE)
                                    |
                    ┌───────────────┴───────────────┐
                    |                               |
              Canvas Backend                  WebGPU Backend
              ─────────────                   ──────────────
         1. TextureCache.get()           1. AtlasManager.getRegion()
            → android.graphics.Bitmap       → UvRect(offset, scale)
                    |                               |
         2. BitmapShader(bitmap,         2. Write to instance buffer:
            CLAMP, CLAMP)                   { uvOffset, uvScale, tint }
                    |                               |
         3. Matrix.setPolyToPoly(        3. (No per-face work at render time)
            texCorners → faceCorners)
                    |                               |
         4. paint.shader = shader        4. Single drawIndexedIndirect()
            canvas.drawPath(path, paint)    renders ALL faces
                    |                               |
         [Per face, every frame]         [Per scene change, amortized]
```

**The unified `TextureManager` interface:**

```kotlin
interface TextureManager {
    /** Load texture source into backend-specific storage */
    suspend fun load(source: TextureSource): TextureHandle

    /** Get the texture's rendering data for the current backend */
    fun resolve(handle: TextureHandle): ResolvedTexture

    /** Evict unused textures under memory pressure */
    fun trim(level: Int)
}

// Canvas implementation
class CanvasTextureManager : TextureManager {
    private val cache: LruCache<TextureSource, Bitmap> = ...
    override fun resolve(handle: TextureHandle) =
        ResolvedTexture.CanvasBitmap(cache.get(handle.source)!!)
}

// WebGPU implementation
class WebGpuTextureManager(private val device: GPUDevice) : TextureManager {
    private val atlas: DynamicAtlas = ...
    override fun resolve(handle: TextureHandle) =
        ResolvedTexture.AtlasRegion(atlas.getRegion(handle.source))
}
```

### 13.9 Canvas-to-WebGPU Texture Migration Path

The phased implementation plan from Section 12 and WEBGPU_ANALYSIS.md Section 11 should be **interleaved**, not sequential. Here's why and how:

**Anti-pattern**: Build full Canvas texture support first, then rebuild it all for WebGPU.

**Recommended pattern**: Build the abstract `Material` system once, implement Canvas backend first (immediate value), then add WebGPU backend that consumes the same materials.

```
Phase 1: Material Foundation
├── Define Material sealed interface (core)
├── Define TextureSource, UvCoord, UvTransform (core)
├── UV generation for Prism (core)
├── Canvas backend: BitmapShader + Matrix (compose)
├── TextureCache with LRU + bitmap pool (compose)
└── Compose DSL: Shape(shape, material) overload

Phase 2: Atlas Infrastructure (shared between backends)
├── Atlas packing (Shelf algorithm) — produces UvRect regions
├── Half-pixel UV correction
├── Per-face material API (PrismFace enum)
├── Material-based face sorting
└── This atlas infrastructure serves BOTH Canvas and WebGPU

Phase 3A: Canvas Shader Effects (parallel track)
├── AGSL RuntimeShader for Material.Shader (API 33+)
├── Normal map support via setInputShader
├── Procedural texture generation to offscreen Bitmap
└── Fallback chain: Shader → Textured → FlatColor

Phase 3B: WebGPU Rendering Backend (parallel track)
├── AndroidExternalSurface embedding
├── WebGpuIsometricRenderer consuming RenderCommand
├── Vertex buffer from projected face corners
├── Atlas texture upload to GPU
├── Instance buffer with per-face UV offset/scale/tint
├── WGSL fragment shader for textured instanced draw
└── Single drawIndexedIndirect() for all faces

Phase 4: WebGPU Compute Pipeline
├── Compute: parallel vertex transformation
├── Compute: parallel culling
├── Compute: spatial hash + depth sort
├── Compute: lighting (per-face tint)
├── Compute: UV generation + atlas lookup
├── Compute: populate indirect draw buffer
└── Full GPU-driven rendering with textures
```

**Phase 3A and 3B can run in parallel** because they share the same `Material` definitions and atlas infrastructure from Phase 2, but implement different rendering backends.

### 13.10 Combined Frame Budget Analysis

Extending WEBGPU_ANALYSIS.md Section 6's frame budget with texture overhead:

**1000-face textured scene on mid-range Android:**

| Phase | Canvas (CPU) | Canvas + Textures | WebGPU + Textures |
|-------|-------------|-------------------|-------------------|
| Transform | ~2ms | ~2ms | ~0.1ms (compute) |
| Cull | ~1ms | ~1ms | ~0.05ms (compute) |
| Depth sort | ~15-20ms | ~15-20ms | ~0.3ms (compute) |
| Lighting | ~1ms | ~1ms | ~0.05ms (compute) |
| UV generation | N/A | ~0.5ms | ~0.01ms (compute) |
| Material resolve | N/A | ~1ms (shader setup per face) | ~0.02ms (batch buffer write) |
| Render | ~3-5ms (1000 flat draws) | **~8-12ms** (1000 textured draws, shader state changes) | **~0.3ms** (1 instanced draw) |
| **Total** | **~22-29ms** | **~29-38ms** | **~0.8-2ms** |

**Key observation**: Adding textures to the Canvas path makes performance **worse** (shader state changes per face). Adding textures to the WebGPU path adds **negligible cost** (same single instanced draw, just sampling a texture instead of using a solid color). This makes the case for WebGPU even stronger when textures are involved.

### 13.11 Risks of the Combined Approach

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Two rendering backends to maintain** | Double the rendering code | Shared `Material` + `RenderCommand` abstraction; only the final renderer diverges |
| **Atlas format differences** | Canvas uses `android.graphics.Bitmap`; WebGPU uses `GPUTexture` | Single atlas packing logic, platform-specific upload (`writeTexture` vs `BitmapFactory`) |
| **Texture memory doubled** | Same textures in both CPU bitmap cache and GPU texture | Only one backend active at a time; dispose the other's resources |
| **WGSL shader maintenance** | AGSL and WGSL are different languages | Keep shader effects simple; complex effects only on WebGPU path |
| **Alpha API instability** | `androidx.webgpu` is alpha (v1.0.0-alpha04) | Canvas backend is production fallback; WebGPU is opt-in |
| **Normal map atlas bleeding** | Normal map interpolation across atlas boundaries produces incorrect normals | Separate `texture_2d_array` layer for normal maps (avoids atlas entirely) |
| **Fragment shader branching** | `Material.Shader` with custom WGSL may need branching in uber-shader | Separate render pass for custom-shader faces; batch standard faces |
| **Texture upload latency** | Large atlas upload blocks the GPU queue | Stream texture data in chunks; use `device.queue.writeTexture` with staging buffers |

### 13.12 Unified Implementation Roadmap

Combining WEBGPU_ANALYSIS.md Section 11 and this document's Section 12 into a single timeline:

```
Phase 1: Material Foundation + Canvas Textures
══════════════════════════════════════════════
  Core:
    ✦ Material sealed interface (FlatColor, Textured, Shader)
    ✦ UvCoord, UvTransform, TextureSource types
    ✦ UV generation for Prism (box projection)
    ✦ RenderCommand extended with material + uvCoords
  Compose:
    ✦ BitmapShader + Matrix rendering in native renderer
    ✦ TextureCache (LRU + bitmap pool)
    ✦ Shape(shape, material) composable overload
    ✦ Backward-compatible Shape(shape, color) preserved
  Tests:
    ✦ Snapshot tests for textured Prism

Phase 2: Atlas + Per-Face Materials (Shared Infrastructure)
═══════════════════════════════════════════════════════════
  Core:
    ✦ PrismFace enum + per-face material API
    ✦ UV generation for Pyramid, Cylinder, Octahedron
    ✦ Atlas region data structures (UvRect)
  Compose:
    ✦ Atlas packing (Shelf algorithm)
    ✦ Half-pixel UV correction
    ✦ Material-based face sorting for draw call batching
  Tests:
    ✦ Atlas packing correctness tests
    ✦ Snapshot tests for per-face materials

Phase 3A: Canvas Shader Effects               Phase 3B: WebGPU Rendering Backend
═══════════════════════════                   ══════════════════════════════════
  Compose:                                      New module (isometric-webgpu):
    ✦ Material.Shader + AGSL                      ✦ AndroidExternalSurface embedding
    ✦ MaterialResolver fallback chain              ✦ WebGpuIsometricRenderer
    ✦ Normal maps via setInputShader               ✦ Atlas texture upload to GPU
    ✦ Procedural texture generation                ✦ Instance buffer: UV offset/scale/tint
  Tests:                                          ✦ WGSL fragment shader (textured)
    ✦ API 33 conditional snapshot tests            ✦ Single instanced draw call
                                                Tests:
                                                  ✦ Visual comparison with Canvas output

Phase 4: WebGPU Compute Pipeline (Full GPU-Driven)
══════════════════════════════════════════════════
  isometric-webgpu:
    ✦ Compute: parallel vertex transformation
    ✦ Compute: parallel culling (back-face + frustum)
    ✦ Compute: spatial hash + depth sort
    ✦ Compute: lighting (per-face tint → buffer)
    ✦ Compute: UV generation + atlas lookup
    ✦ Compute: populate indirect draw buffer
    ✦ drawIndexedIndirect() with full GPU-driven pipeline
    ✦ Normal map fragment lighting (per-pixel)
  Tests:
    ✦ Benchmark: Canvas vs WebGPU at N=100, 500, 1000, 5000
    ✦ Device compatibility matrix testing

Phase 5: Advanced Effects (Future)
══════════════════════════════════
    ✦ Procedural textures via compute shader (write to atlas)
    ✦ Dynamic lighting with multiple light sources
    ✦ Post-processing effects (bloom, ambient occlusion)
    ✦ Particle systems via compute shader
    ✦ Animated textures (UV offset animation in instance buffer)
```

**Key principle**: Each phase delivers standalone value. Phase 1 gives textured faces on Canvas today. Phase 2 adds atlas efficiency. Phase 3B gives WebGPU rendering with textures. Phase 4 adds compute parallelism. No phase depends on a future phase to be useful.

---

## 14. Sources

### Texture Mapping Techniques
- [Affine Texture Mapping: Wikipedia](https://en.wikipedia.org/wiki/Texture_mapping)
- [PS1 Affine Textures in Shader Code (Daniel Ilett)](https://danielilett.com/2021-11-06-tut5-21-ps1-affine-textures/)
- [Perspective-Correct Texturing on Canvas (Tulrich)](http://tulrich.com/geekstuff/canvas/perspective.html)
- [Perspective Correct Interpolation (Scratchapixel)](https://www.scratchapixel.com/lessons/3d-basic-rendering/rasterization-practical-implementation/perspective-correct-interpolation-vertex-attributes.html)
- [Texture Drawing for HTML Canvas (Observable)](https://observablehq.com/@shaunlebron/texture-drawing-for-html-canvas)

### Android Canvas
- [Android Canvas BitmapShader API](https://developer.android.com/reference/android/graphics/BitmapShader)
- [Rendering a Path with Bitmap Fill (41 Post)](http://www.41post.com/4794/programming/android-rendering-a-path-with-a-bitmap-fill)
- [50 Shaders of Android Drawing (PSPDFKit)](https://pspdfkit.com/blog/2017/50-shaders-of-android-drawing-on-canvas/)
- [drawBitmapMesh Transform (Elye, Medium)](https://medium.com/mobile-app-development-publication/transforming-picture-with-android-canvas-drawbitmapmesh-35f359235774)
- [Android Shaders Codelab](https://developer.android.com/codelabs/advanced-android-kotlin-training-shaders)
- [Canvas ColorFilter (Chiu-Ki Chan)](https://chiuki.github.io/android-shaders-filters/)

### Compose Shaders
- [Brush: Gradients and Shaders (Android Developers)](https://developer.android.com/develop/ui/compose/graphics/draw/brush)
- [Tiled Image Backgrounds in Compose (dladukedev)](https://dladukedev.com/articles/025_tiled_background_jetpack_compose/)
- [TransformableBrush for Efficient Animations (Aghajari)](https://medium.com/@aghajari/transformablebrush-for-efficient-brush-animations-in-jetpack-compose-eb566278ac5d)
- [Dot. Dash. Design. — PathEffect (ProAndroidDev)](https://proandroiddev.com/dot-dash-design-c30928484f79)

### AGSL
- [Using AGSL in Your App (Android Developers)](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)
- [AGSL Overview (Android Developers)](https://developer.android.com/develop/ui/views/graphics/agsl)
- [AGSL: Made in the Shade(r) (Chet Haase)](https://medium.com/androiddevelopers/agsl-made-in-the-shade-r-7d06d14fe02a)
- [AGSL in Compose Part 1 (Timo Drick)](https://medium.com/@timo_86166/using-androids-new-custom-pixel-shader-agsl-in-compose-part-1-6e6784b5e4d4)
- [drinkthestars/shady — AGSL Collection](https://github.com/drinkthestars/shady)

### Skia
- [SkSL & Runtime Effects (Skia)](https://skia.org/docs/user/sksl/)
- [SkPaint Overview (Skia)](https://skia.org/docs/user/api/skpaint_overview/)
- [Skia Shaders Playground](https://shaders.skia.org/)
- [Shader-Based Render Effects in Compose Desktop (Pushing Pixels)](https://www.pushing-pixels.org/2022/04/09/shader-based-render-effects-in-compose-desktop-with-skia.html)

### WebGPU Textures
- [WebGPU Fundamentals: Textures](https://webgpufundamentals.org/webgpu/lessons/webgpu-textures.html)
- [Learn WebGPU: Texture Mapping](https://eliemichel.github.io/LearnWebGPU/basic-3d-rendering/texturing/texture-mapping.html)
- [WGSL Texture Function Reference (webgpu.rocks)](https://www.webgpu.rocks/wgsl/functions/texture/)
- [Learn Wgpu: Textures and Bind Groups](https://sotrh.github.io/learn-wgpu/beginner/tutorial5-textures/)
- [WebGPU Bind Group Best Practices (Toji.dev)](https://toji.dev/webgpu-best-practices/bind-groups.html)

### Normal Maps & Lighting
- [Isometric Environment Lighting (Post Physical)](https://www.postphysical.io/blog/isometric-environment-lighting-series-part-2)
- [Normal Maps for 2D Games (GameMaker)](https://gamemaker.io/en/blog/using-normal-maps-to-light-your-2d-game)
- [Realtime 2D Lighting in Godot 4.4 (Connor Wolf)](https://www.connorwolf.com/post/realtime-2d-lighting-with-shadows-on-isometric-tiles-in-godot-4-4)
- [Sprite Glow/Outline Breakdown (Cyanilux)](https://www.cyanilux.com/tutorials/sprite-outline-shader-breakdown/)

### Texture Atlases
- [Texture Packing (lisyarus)](https://lisyarus.github.io/blog/posts/texture-packing.html)
- [maxrects-packer (GitHub)](https://github.com/soimy/maxrects-packer)
- [smol-atlas (GitHub)](https://github.com/aras-p/smol-atlas)
- [Preventing Texture Bleeding (WebGL Fundamentals)](https://webglfundamentals.org/webgl/lessons/webgl-qna-how-to-prevent-texture-bleeding-with-a-texture-atlas.html)
- [Texture Atlas (Wikipedia)](https://en.wikipedia.org/wiki/Texture_atlas)

### UV Mapping
- [UV Mapping (Wikipedia)](https://en.wikipedia.org/wiki/UV_mapping)
- [Isometric Projection Math (Clint Bellanger)](https://clintbellanger.net/articles/isometric_math/)
- [Affine Transforms for Isometric (Kevin's Blog)](https://kevinhikaruevans.wordpress.com/2014/12/04/using-affine-transformations-to-emulate-isometric-projection/)
- [Isometric Projection in Games (Pikuma)](https://pikuma.com/blog/isometric-projection-in-games)
- [Texture Coordinate Generation (PBR Book)](https://pbr-book.org/3ed-2018/Texture/Texture_Coordinate_Generation)

### Mobile GPU
- [Android Developers: Textures](https://developer.android.com/games/optimize/textures)
- [ARM GPU Best Practices Rev 3.4 (2025)](https://documentation-service.arm.com/static/67a62b17091bfc3e0a947695)
- [Texture Compression Format Targeting (Android)](https://developer.android.com/guide/playcore/asset-delivery/texture-compression)
- [NVIDIA: ASTC for Game Assets](https://developer.nvidia.com/astc-texture-compression-for-game-assets)
- [Texture Compression in 2020 (Aras P.)](https://aras-p.info/blog/2020/12/08/Texture-Compression-in-2020/)
- [Texture Memory Bandwidth (Android)](https://developer.android.com/agi/sys-trace/texture-memory-bw)

### API Design
- [Three.js MeshBasicMaterial](https://threejs.org/docs/pages/MeshBasicMaterial.html)
- [Three.js Textures Manual](https://threejs.org/manual/en/textures.html)
- [Godot CanvasItem Shaders](https://docs.godotengine.org/en/stable/tutorials/shaders/shader_reference/canvas_item_shader.html)
- [Unity SpriteRenderer](https://docs.unity3d.com/530/Documentation/Manual/class-SpriteRenderer.html)
- [Kotlin Type-Safe Builders](https://kotlinlang.org/docs/type-safe-builders.html)
- [Game Engines as Art Form (Ebiten)](https://medium.com/@hajimehoshi/game-engines-as-an-art-form-f66c835c0a92)
- [Designing a Material System (Beyond the Far Plane)](https://beyondthefarplane.com/designing-a-material-system/)

### Error Handling & Testing
- [Missing Textures (Minecraft Wiki)](https://minecraft.wiki/w/Missing_textures_and_models)
- [Graphics Test Framework (Unity)](https://docs.unity3d.com/Packages/com.unity.testframework.graphics@7.2/manual/index.html)
- [How (Not) to Test Graphics (Bart Wronski)](https://bartwronski.com/2019/08/14/how-not-to-test-graphics-algorithms/)
- [Testing Graphics Code (Aras, 2007)](https://aras-p.info/blog/2007/07/31/testing-graphics-code/)

### WebGPU Compute & Rendering (Section 13 cross-references)
- [AndroidX WebGPU Releases](https://developer.android.com/jetpack/androidx/releases/webgpu)
- [Getting Started with WebGPU on Android](https://developer.android.com/develop/ui/views/graphics/webgpu/getting-started)
- [Exploring the AndroidX WebGPU API in Kotlin](https://shubham0204.github.io/blogpost/programming/androidx-webgpu)
- [WebGPU Indirect Draw Best Practices (Toji.dev)](https://toji.dev/webgpu-best-practices/indirect-draws.html)
- [WebGPU Render Bundle Best Practices (Toji.dev)](https://toji.dev/webgpu-best-practices/render-bundles.html)
- [WebGPU Optimization Fundamentals](https://webgpufundamentals.org/webgpu/lessons/webgpu-optimization.html)
- [Dawn — Native WebGPU Implementation (Google)](https://github.com/google/dawn)
- [AndroidExternalSurface (Composables.com)](https://composables.com/foundation/androidexternalsurface)
- [GPU Tilemap Rendering at 3000 FPS](https://blog.paavo.me/gpu-tilemap-rendering/)
- [Particle Life with GPU Spatial Hashing (lisyarus)](https://lisyarus.github.io/blog/posts/particle-life-simulation-in-browser-using-webgpu.html)
- [WebGPU Compute Shader Basics](https://webgpufundamentals.org/webgpu/lessons/webgpu-compute-shaders.html)
- See also: [WEBGPU_ANALYSIS.md](./WEBGPU_ANALYSIS.md) for full WebGPU parallelism research

### Game Engines & Architecture
- [2B2D WebGPU Engine (GitHub)](https://github.com/mrdrbob/2b2d)
- [PixiJS (GitHub)](https://github.com/pixijs/pixijs)
- [FIFE Engine](https://www.fifengine.net/)
- [Vello: GPU Compute 2D Renderer](https://github.com/linebender/vello)
- [Kool Engine (Kotlin)](https://github.com/kool-engine/kool)
- [Bitmap Memory Management (Android)](https://developer.android.com/topic/performance/graphics/manage-memory)
- [Glide Resource Reuse](https://bumptech.github.io/glide/doc/resourcereuse.html)
- [GlideBitmapPool (GitHub)](https://github.com/amitshekhariitbhu/GlideBitmapPool)
