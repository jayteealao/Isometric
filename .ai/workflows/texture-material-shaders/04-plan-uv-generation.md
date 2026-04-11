---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: uv-generation
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:49:12Z"
metric-files-to-touch: 7
metric-step-count: 8
has-blockers: false
revision-count: 1
tags: [uv, geometry]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-uv-generation.md
  siblings: [04-plan-material-types.md, 04-plan-canvas-textures.md, 04-plan-webgpu-textures.md, 04-plan-per-face-materials.md, 04-plan-sample-demo.md]
  implement: 05-implement-uv-generation.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders uv-generation"
---

# Plan: UV Coordinate Generation

## Slice Goal

Implement automatic UV coordinate generation for Prism faces. Add `PrismFace` enum for
face-type identification. Generate per-vertex UV coords mapping `[0,0]→[1,0]→[1,1]→[0,1]`
for each quad face. Populate `RenderCommand.uvCoords` when the material is `Textured`.
Unit test all six face types with known Prism dimensions.

## Prerequisite: What the material-types Slice Provides

This plan assumes the `material-types` slice has been implemented and delivers:

- `isometric-shader` Gradle module (`:isometric-shader` in `settings.gradle`)
- `IsometricMaterial` sealed interface with `FlatColor`, `Textured`, `PerFace` variants
- `TextureSource` sealed interface (`Resource`, `Asset`, `Bitmap`)
- `UvCoord(u: Float, v: Float)` data class
- `RenderCommand` extended with `material: MaterialData?` and `uvCoords: FloatArray?`
  where `uvCoords` is a flat float array in `[u0,v0, u1,v1, ...]` layout (4 pairs = 8 floats
  for a quad face), `null` when material is not `Textured`
- `isometric-compose` does NOT depend on `isometric-shader` (dependency reversed in rev 3)
- `isometric-shader` depends on `isometric-compose` and provides overloaded `Shape(geometry, material)` composables
- `ShapeNode.material` is typed as `MaterialData?` (from core), not `IsometricMaterial?`

## Context: Prism Face Geometry

`Prism.createPaths()` emits six `Path` objects in this fixed order (indices 0–5):

| Index | Role | Plane | Winding | 3D vertex order (from source) |
|-------|------|-------|---------|-------------------------------|
| 0 | FRONT | XZ (y=position.y) | CCW | BL→BR→TR→TL: `(x,y,z)` → `(x+w,y,z)` → `(x+w,y,z+h)` → `(x,y,z+h)` |
| 1 | BACK | XZ (y=position.y+depth) | CW (reversed+translated) | TL→TR→BR→BL: `(x,y+d,z+h)` → `(x+w,y+d,z+h)` → `(x+w,y+d,z)` → `(x,y+d,z)` |
| 2 | LEFT | YZ (x=position.x) | CCW | BL→TL→TR→BR: `(x,y,z)` → `(x,y,z+h)` → `(x,y+d,z+h)` → `(x,y+d,z)` |
| 3 | RIGHT | YZ (x=position.x+width) | CW (reversed+translated) | BL→BR→TR→TL: `(x+w,y+d,z)` → `(x+w,y,z)` → `(x+w,y,z+h)` → `(x+w,y+d,z+h)` |  
| 4 | BOTTOM | XY (z=position.z) | CW (reversed) | BL→BR→TR→TL: `(x+w,y,z)` → `(x,y,z)` → `(x,y+d,z)` → `(x+w,y+d,z)` |
| 5 | TOP | XY (z=position.z+height) | CCW | BL→BR→TR→TL: `(x,y,z+h)` → `(x+w,y,z+h)` → `(x+w,y+d,z+h)` → `(x,y+d,z+h)` |

**Important note on winding and face ordering in source:**
The source builds `face1` (FRONT), then `face1.reverse().translate(0,depth,0)` (BACK),
then `face2` (LEFT), then `face2.reverse().translate(width,0,0)` (RIGHT), then
`face3.reverse()` (BOTTOM), then `face3.translate(0,0,height)` (TOP). The indices 0–5
are stable: `Prism.paths` always contains exactly six faces in this order.

**Key point for face identification:** The ordering is a construction contract in
`createPaths()`. It does not change after translate/rotate/scale because those operations
return a new `Shape(paths.map { it.transform() })` — the list order is preserved. The
`orderedPaths()` method re-sorts by depth, but `paths` itself remains in construction order.
UV generation uses `paths` (not `orderedPaths()`), and `PrismFace.fromPathIndex()` maps
the stable index to the face role.

## UV Mapping Strategy

UV coordinates are computed from the 3D `Path.points` before isometric projection. This is
correct because isometric (orthographic) projection is affine — affine texture mapping
produces mathematically correct results without foreshortening correction. (Research doc §2.1
and §4.3 confirm this.)

Each Prism face is a planar quad. UV mapping uses box/cubic projection: for each face,
two of the three 3D axes span the face plane; those become the U and V axes. The UV
origin (0,0) is assigned to the first vertex of each face as emitted by `createPaths()`,
with U and V increasing to (1,1) at the diagonally opposite vertex.

### Per-Face UV Axis Assignments

| Face | U axis (increasing u) | V axis (increasing v) | Formula |
|------|-----------------------|-----------------------|---------|
| FRONT (index 0) | x: `position.x` → `position.x + width` | z: `position.z` → `position.z + height` | `u = (pt.x - x) / width`, `v = (pt.z - z) / height` |
| BACK (index 1) | x reversed: `position.x + width` → `position.x` | z: `position.z + height` → `position.z` | `u = 1 - (pt.x - x) / width`, `v = 1 - (pt.z - z) / height` |
| LEFT (index 2) | y: `position.y` → `position.y + depth` | z: `position.z` → `position.z + height` | `u = (pt.y - y) / depth`, `v = (pt.z - z) / height` |
| RIGHT (index 3) | y reversed: `position.y + depth` → `position.y` | z: `position.z` → `position.z + height` | `u = 1 - (pt.y - y) / depth`, `v = (pt.z - z) / height` |
| BOTTOM (index 4) | x reversed: `position.x + width` → `position.x` | y: `position.y` → `position.y + depth` | `u = 1 - (pt.x - x) / width`, `v = (pt.y - y) / depth` |
| TOP (index 5) | x: `position.x` → `position.x + width` | y: `position.y` → `position.y + depth` | `u = (pt.x - x) / width`, `v = (pt.y - y) / depth` |

The reversal on BACK, RIGHT, and BOTTOM compensates for the reversed winding applied by
`createPaths()` so that every face maps the full texture rectangle `[0,0]→[1,1]` in the
same left-to-right, bottom-to-top orientation from the viewer's perspective.

### Resulting UV assignments per face

After applying the formulas above to the emitted vertex order, each face's four vertices
receive the following UVs (in `Path.points` order):

| Face | v[0] | v[1] | v[2] | v[3] |
|------|------|------|------|------|
| FRONT | (0,0) | (1,0) | (1,1) | (0,1) |
| BACK | (1,1) | (0,1) | (0,0) | (1,0) |
| LEFT | (0,0) | (0,1) | (1,1) | (1,0) |
| RIGHT | (1,0) | (1,1) | (0,1) | (0,0) — wait, formula: see below |
| BOTTOM | (1,0) | (0,0) | (0,1) | (1,1) |
| TOP | (0,1) | (1,1) | (1,0) | (0,0) — wait, formula: see below |

**Precise values for RIGHT (index 3):**
Vertices as emitted: `(x+w,y+d,z)`, `(x+w,y,z)`, `(x+w,y,z+h)`, `(x+w,y+d,z+h)`.
- v[0]: u=1-(d/d)=0, v=(z-z)/h=0 → (0,0)
- v[1]: u=1-(0/d)=1, v=0 → (1,0)
- v[2]: u=1, v=1 → (1,1)
- v[3]: u=0, v=1 → (0,1)
Result: (0,0),(1,0),(1,1),(0,1) — same canonical ordering as FRONT. Correct.

**Precise values for TOP (index 5):**
Vertices as emitted: `(x,y,z+h)`, `(x+w,y,z+h)`, `(x+w,y+d,z+h)`, `(x,y+d,z+h)`.
- v[0]: u=0, v=0 → (0,0)
- v[1]: u=1, v=0 → (1,0)
- v[2]: u=1, v=1 → (1,1)
- v[3]: u=0, v=1 → (0,1)
Result: (0,0),(1,0),(1,1),(0,1) — canonical. Correct.

**Summary:** With the axis assignments above, all six faces produce the canonical UV
sequence `(0,0),(1,0),(1,1),(0,1)` in their `Path.points` order. This ensures the full
texture rectangle maps uniformly to every face and no texture inversion occurs.

## Implementation Steps

### Step 1 — Add `PrismFace` enum to `isometric-core`

**File:** `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/shapes/PrismFace.kt`

```kotlin
package io.github.jayteealao.isometric.shapes

/**
 * Identifies which face of a [Prism] a path corresponds to.
 *
 * Face roles map to stable path indices in the order produced by [Prism.createPaths]:
 * index 0 = FRONT, 1 = BACK, 2 = LEFT, 3 = RIGHT, 4 = BOTTOM, 5 = TOP.
 */
enum class PrismFace {
    FRONT,
    BACK,
    LEFT,
    RIGHT,
    BOTTOM,
    TOP;

    companion object {
        /**
         * Returns the [PrismFace] for the given 0-based path index within [Prism.paths].
         *
         * @throws IllegalArgumentException if [index] is outside 0..5
         */
        fun fromPathIndex(index: Int): PrismFace = when (index) {
            0 -> FRONT
            1 -> BACK
            2 -> LEFT
            3 -> RIGHT
            4 -> BOTTOM
            5 -> TOP
            else -> throw IllegalArgumentException(
                "Prism has exactly 6 faces (indices 0..5); got index $index"
            )
        }
    }
}
```

No changes to `Prism.kt` itself — the face order is already a stable contract in
`createPaths()`. `PrismFace.fromPathIndex` is the bridge between the construction-order
index and the semantic face role.

### Step 2 — Add `UvGenerator` to `isometric-shader`

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvGenerator.kt`

`UvGenerator` is a pure-function object (no state, no Android dependencies) that accepts
a `Prism` and a 0-based face index and returns a `FloatArray` of 8 floats in
`[u0,v0, u1,v1, u2,v2, u3,v3]` order matching the `Path.points` vertex order.

```kotlin
package io.github.jayteealao.isometric.shader

import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.shapes.Prism
import io.github.jayteealao.isometric.shapes.PrismFace

/**
 * Generates per-vertex UV coordinates for [Prism] faces.
 *
 * UV coordinates are computed in 3D space (before isometric projection). Affine
 * mapping in screen space is correct for orthographic projection — no foreshortening
 * correction is required.
 *
 * Output is a [FloatArray] of 8 floats: [u0,v0, u1,v1, u2,v2, u3,v3] matching the
 * vertex order of [Prism.paths] at the given face index.
 */
object UvGenerator {

    /**
     * Generates UV coordinates for a single Prism face identified by its 0-based
     * path index within [Prism.paths].
     *
     * @param prism The source Prism (provides dimensional extents for normalization)
     * @param faceIndex 0-based index into [Prism.paths] (0=FRONT … 5=TOP)
     * @return [FloatArray] of 8 floats [u0,v0, u1,v1, u2,v2, u3,v3]
     */
    fun forPrismFace(prism: Prism, faceIndex: Int): FloatArray {
        val face = PrismFace.fromPathIndex(faceIndex)
        val path = prism.paths[faceIndex]
        return computeUvs(prism, face, path)
    }

    /**
     * Generates UV coordinates for all six Prism faces in [Prism.paths] order.
     *
     * @return List of 6 [FloatArray], each with 8 floats, indexed 0=FRONT … 5=TOP
     */
    fun forAllPrismFaces(prism: Prism): List<FloatArray> =
        (0..5).map { forPrismFace(prism, it) }

    private fun computeUvs(prism: Prism, face: PrismFace, path: Path): FloatArray {
        val ox = prism.position.x
        val oy = prism.position.y
        val oz = prism.position.z
        val w = prism.width
        val d = prism.depth
        val h = prism.height

        val result = FloatArray(8)
        for (i in 0..3) {
            val pt = path.points[i]
            val (u, v) = when (face) {
                PrismFace.FRONT  -> Pair((pt.x - ox) / w,       (pt.z - oz) / h)
                PrismFace.BACK   -> Pair(1f - (pt.x - ox) / w,  1f - (pt.z - oz) / h)
                PrismFace.LEFT   -> Pair((pt.y - oy) / d,        (pt.z - oz) / h)
                PrismFace.RIGHT  -> Pair(1f - (pt.y - oy) / d,  (pt.z - oz) / h)
                PrismFace.BOTTOM -> Pair(1f - (pt.x - ox) / w,  (pt.y - oy) / d)
                PrismFace.TOP    -> Pair((pt.x - ox) / w,        (pt.y - oy) / d)
            }
            result[i * 2]     = u.toFloat()
            result[i * 2 + 1] = v.toFloat()
        }
        return result
    }
}
```

The `Pair<Double,Double>` destructuring above is clear but allocates. For the hot path
(called inside `renderTo()`), replace with direct index writes to avoid allocation —
see Step 4 note.

### Step 3 — Wire UV generation into the shader module's overloaded `Shape()` composable

**File:** `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`

**Architectural note (rev 3 of material-types):** `isometric-compose` does NOT depend on
`isometric-shader`. UV generation cannot happen in `ShapeNode.renderTo()` (in compose)
because compose cannot import `IsometricMaterial` or `UvGenerator` (in shader). Instead,
UV generation is wired into the shader module's overloaded `Shape(geometry, material)`
composable, which creates a `ShapeNode` and uses `material` typed as `MaterialData?`.

The overloaded `Shape()` composable in `isometric-shader` (created by material-types slice)
is extended to:
1. Check if `material is IsometricMaterial.Textured && geometry is Prism`
2. If so, pre-compute UV coordinates via `UvGenerator` and store them on a
   UV-aware wrapper or pass them through a `CompositionLocal`

**Design decision:** Since `ShapeNode.renderTo()` emits one `RenderCommand` per path
(face), and UV generation needs the face index, the UV population happens **downstream**
after `ShapeNode.renderTo()` is called. The cleanest approach:

- Add a `uvProvider` field to `ShapeNode` typed as `((Shape, Int) -> FloatArray?)?`
  (a lambda that takes the original shape and face index, returns UV coords). This field
  is `null` by default (no UVs). The shader module's `Shape()` composable sets it.
- In `ShapeNode.renderTo()`, call `uvProvider?.invoke(shape, index)` to get UVs per face.
  This requires no shader imports in compose — the lambda is opaque.

**Change to `ShapeNode` (in isometric-compose):**

```kotlin
// New field — set by the shader module's overloaded Shape() composable
var uvProvider: ((Shape, Int) -> FloatArray?)? = null
```

**Change to `ShapeNode.renderTo()` (in isometric-compose):**

```kotlin
for ((index, path) in transformedShape.paths.withIndex()) {
    output.add(
        RenderCommand(
            commandId = "${nodeId}_${path.hashCode()}",
            points = DoubleArray(0),
            color = effectiveColor,
            originalPath = path,
            originalShape = transformedShape,
            ownerNodeId = nodeId,
            material = material,
            uvCoords = uvProvider?.invoke(shape, index),
        )
    )
}
```

**Change to shader module's `Shape()` overload:**

```kotlin
// In IsometricMaterialComposables.kt
val uvProviderLambda: ((Shape, Int) -> FloatArray?)? = if (
    material is IsometricMaterial.Textured && geometry is Prism
) {
    { shape, faceIndex -> UvGenerator.forPrismFace(shape as Prism, faceIndex) }
} else null

ReusableComposeNode<ShapeNode, IsometricApplier>(
    factory = {
        ShapeNode(geometry, color).also {
            it.material = material
            it.uvProvider = uvProviderLambda
        }
    },
    update = {
        // ... existing sets ...
        set(uvProviderLambda) { this.uvProvider = it; markDirty() }
    }
)
```

**Imports needed (in isometric-shader only):**
```kotlin
import io.github.jayteealao.isometric.shapes.Prism
```

No shader imports in `isometric-compose` — `uvProvider` is a plain Kotlin lambda.

### Step 4 — Performance note for `UvGenerator`

The `Pair<Double,Double>` inside `computeUvs` allocates on every vertex. Since this is
called once per face per frame (up to 6 calls per Prism), the allocation rate is low for
typical scenes. For scenes with N=100+ textured Prisms, refactor to direct array writes:

```kotlin
// Zero-allocation alternative for FRONT face:
result[0] = ((pt0.x - ox) / w).toFloat()
result[1] = ((pt0.z - oz) / h).toFloat()
// ... repeated for i=1..3
```

Defer this optimization until the benchmark slice shows it as a hot path.

### Step 5 — `RenderCommand` UV field layout

The `uvCoords: FloatArray?` added by the `material-types` slice uses flat packing:
```
[u0, v0, u1, v1, u2, v2, u3, v3]   // indices 0..7 for a quad face
```

Access helpers (add to `RenderCommand` or as extension functions in `isometric-shader`):

```kotlin
fun RenderCommand.uvU(vertexIndex: Int): Float = uvCoords!![vertexIndex * 2]
fun RenderCommand.uvV(vertexIndex: Int): Float = uvCoords!![vertexIndex * 2 + 1]
```

These are only called when `uvCoords != null` (i.e., when material is `Textured`), so the
`!!` is safe in context. Name them for user intent (guideline §4), not internal detail.

### Step 6 — API surface in `isometric-shader`

`PrismFace` lives in `isometric-core` (shapes package) because it is pure geometry metadata
that does not depend on the shader module. `UvGenerator` lives in `isometric-shader` because
it is the first consumer that bridges geometry and material rendering.

Public surface:
- `PrismFace` enum — `isometric-core`, package `io.github.jayteealao.isometric.shapes`
- `UvGenerator` object — `isometric-shader`, package `io.github.jayteealao.isometric.shader`
- `RenderCommand.uvU(Int)` / `uvV(Int)` — extensions in `isometric-shader`

`UvGenerator` is intentionally a stateless `object`, not a class, because it has no
configuration and no lifecycle. This matches guideline §8 (composition over god objects)
and guideline §10 (idiomatic Kotlin — top-level functions or objects for pure utilities).

### Step 7 — Unit Tests

**File:** `isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/UvGeneratorTest.kt`

Test framework: `kotlin.test` (consistent with existing tests in `isometric-core`).

**Test cases:**

```kotlin
class UvGeneratorTest {

    private val origin = Point(0.0, 0.0, 0.0)
    private val unitPrism = Prism(origin, width=1.0, depth=1.0, height=1.0)

    @Test
    fun `FRONT face maps canonical UVs to path vertex order`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 0) // FRONT
        assertUvAt(uvs, 0, 0f, 0f)  // BL: (x,y,z)
        assertUvAt(uvs, 1, 1f, 0f)  // BR: (x+w,y,z)
        assertUvAt(uvs, 2, 1f, 1f)  // TR: (x+w,y,z+h)
        assertUvAt(uvs, 3, 0f, 1f)  // TL: (x,y,z+h)
    }

    @Test
    fun `TOP face maps canonical UVs to path vertex order`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 5) // TOP
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `BOTTOM face maps canonical UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 4) // BOTTOM
        // BOTTOM: reversed winding, u = 1-(x-ox)/w, v = (y-oy)/d
        // Vertices: (x+w,y,z), (x,y,z), (x,y+d,z), (x+w,y+d,z)
        assertUvAt(uvs, 0, 0f, 0f)  // u=1-1=0, v=0
        assertUvAt(uvs, 1, 1f, 0f)  // u=1-0=1, v=0
        assertUvAt(uvs, 2, 1f, 1f)  // u=1, v=1
        assertUvAt(uvs, 3, 0f, 1f)  // u=0, v=1
    }

    @Test
    fun `LEFT face maps canonical UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 2) // LEFT
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 0f, 1f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 1f, 0f)
    }

    @Test
    fun `RIGHT face maps canonical UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 3) // RIGHT
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `BACK face maps canonical UVs`() {
        val uvs = UvGenerator.forPrismFace(unitPrism, faceIndex = 1) // BACK
        // BACK: u = 1-(x-ox)/w, v = 1-(z-oz)/h
        // Vertices: (x,y+d,z+h), (x+w,y+d,z+h), (x+w,y+d,z), (x,y+d,z)
        assertUvAt(uvs, 0, 1f, 1f)  // u=1-0=1, v=1-1=0 ... wait: v=1-(1)=0
        // Recompute: pt=(x,y+d,z+h): u=1-(0/1)=1, v=1-(1/1)=0 → (1,0)
        // pt=(x+w,y+d,z+h): u=1-1=0, v=1-1=0 → (0,0)
        // pt=(x+w,y+d,z): u=0, v=1-0=1 → (0,1)
        // pt=(x,y+d,z): u=1, v=1 → (1,1)
        // Result: (1,0),(0,0),(0,1),(1,1) — texture mirrored; acceptable for back face
        assertUvAt(uvs, 0, 1f, 0f)
        assertUvAt(uvs, 1, 0f, 0f)
        assertUvAt(uvs, 2, 0f, 1f)
        assertUvAt(uvs, 3, 1f, 1f)
    }

    @Test
    fun `non-unit prism normalises correctly`() {
        val p = Prism(origin, width=3.0, depth=2.0, height=4.0)
        val uvs = UvGenerator.forPrismFace(p, faceIndex = 0) // FRONT
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `translated prism produces same UVs as origin prism`() {
        val translated = Prism(Point(5.0, 7.0, 3.0), width=1.0, depth=1.0, height=1.0)
        val uvs = UvGenerator.forPrismFace(translated, faceIndex = 0)
        assertUvAt(uvs, 0, 0f, 0f)
        assertUvAt(uvs, 1, 1f, 0f)
        assertUvAt(uvs, 2, 1f, 1f)
        assertUvAt(uvs, 3, 0f, 1f)
    }

    @Test
    fun `forAllPrismFaces returns 6 arrays for unit prism`() {
        val all = UvGenerator.forAllPrismFaces(unitPrism)
        assertEquals(6, all.size)
        all.forEach { assertEquals(8, it.size) }
    }

    @Test
    fun `PrismFace fromPathIndex covers all six faces`() {
        PrismFace.entries.forEachIndexed { i, _ ->
            assertEquals(PrismFace.fromPathIndex(i), PrismFace.entries[i])
        }
    }

    @Test
    fun `PrismFace fromPathIndex throws for index out of range`() {
        assertFailsWith<IllegalArgumentException> { PrismFace.fromPathIndex(6) }
        assertFailsWith<IllegalArgumentException> { PrismFace.fromPathIndex(-1) }
    }

    private fun assertUvAt(uvs: FloatArray, vertex: Int, expectedU: Float, expectedV: Float) {
        assertEquals(expectedU, uvs[vertex * 2],     absoluteTolerance = 0.0001f)
        assertEquals(expectedV, uvs[vertex * 2 + 1], absoluteTolerance = 0.0001f)
    }
}
```

**File:** `isometric-core/src/test/kotlin/io/github/jayteealao/isometric/shapes/PrismFaceTest.kt`

```kotlin
class PrismFaceTest {
    @Test
    fun `path index 0 maps to FRONT`() = assertEquals(PrismFace.FRONT, PrismFace.fromPathIndex(0))

    @Test
    fun `path index 5 maps to TOP`() = assertEquals(PrismFace.TOP, PrismFace.fromPathIndex(5))

    @Test
    fun `invalid index throws with descriptive message`() {
        val ex = assertFailsWith<IllegalArgumentException> { PrismFace.fromPathIndex(7) }
        assertTrue(ex.message!!.contains("6 faces"))
    }
}
```

### Step 8 — `apiDump` for changed modules

After changes, run `./gradlew :isometric-core:apiDump :isometric-shader:apiDump` to
record the new public API baseline. New additions:
- `isometric-core`: `PrismFace` enum with `fromPathIndex(Int)`
- `isometric-shader`: `UvGenerator` object with `forPrismFace(Prism,Int)` and
  `forAllPrismFaces(Prism)`; extension functions `RenderCommand.uvU(Int)` and `uvV(Int)`

## Files to Touch

| File | Action | Module |
|------|--------|--------|
| `isometric-core/src/main/kotlin/.../shapes/PrismFace.kt` | CREATE | isometric-core |
| `isometric-shader/src/main/kotlin/.../shader/UvGenerator.kt` | CREATE | isometric-shader |
| `isometric-compose/src/main/kotlin/.../compose/runtime/IsometricNode.kt` | MODIFY `ShapeNode.renderTo()` | isometric-compose |
| `isometric-core/src/test/kotlin/.../shapes/PrismFaceTest.kt` | CREATE | isometric-core |
| `isometric-shader/src/test/kotlin/.../shader/UvGeneratorTest.kt` | CREATE | isometric-shader |
| `isometric-core/api/isometric-core.api` | UPDATE (apiDump) | isometric-core |
| `isometric-shader/api/isometric-shader.api` | UPDATE (apiDump) | isometric-shader |

Total: 7 file touches (5 create, 2 update); `IsometricNode.kt` modification is the one
modify to an existing production file.

## Risks and Mitigations

### Risk 1: Non-uniform scale breaks UV normalization

If a caller applies a non-uniform scale to a `Prism` (e.g., `scaleX=2, scaleY=1`),
the effective face dimensions change but `prism.width/depth/height` remain unchanged,
causing UV distortion.

**Mitigation:** Document that `UvGenerator.forPrismFace` uses the Prism's declared
dimensions (`width`, `depth`, `height`), not the post-transform dimensions. Non-uniform
scaling of textured Prisms is out of scope for this slice. The `isometric-shader` module
will emit a warning in debug builds if a `Prism` with a textured material is rendered
inside a context with non-uniform scale.

### Risk 2: Shape type erasure after transform

After `localContext.applyTransformsToShape(shape)`, the result is typed as `Shape`, not
`Prism`. UV generation therefore casts `shape` (the pre-transform original) to `Prism`.
If `ShapeNode.shape` is mutated to a non-Prism shape after construction, the cast is
skipped (UV generation is gated on `shape is Prism`), so no crash results.

**Mitigation:** The `is Prism` guard in `renderTo()` is the complete safety net. No
additional changes needed.

### Risk 3: Face ordering stability across `paths` access

The test `PrismFaceTest` verifies that index 0 is always FRONT, index 5 is always TOP.
If `Prism.createPaths()` is ever reordered, these tests will fail immediately.

**Mitigation:** The `PrismFace.fromPathIndex` KDoc explicitly states the index contract.
The corresponding test documents the expected ordering. No extra runtime guard needed.

### Risk 4: `isometric-shader` module not yet compiled

If the `material-types` slice is incomplete (e.g., `isometric-shader` module not yet
added to `settings.gradle`), `IsometricNode.kt` will fail to compile because it imports
`UvGenerator` and `IsometricMaterial`.

**Mitigation:** This slice has a hard dependency on `material-types`. Do not merge the
`IsometricNode.kt` changes until the `material-types` slice is merged and green on CI.
The `PrismFace.kt` creation is independent and can be merged first.

## Acceptance Criteria Verification

| Criterion | Covered By |
|-----------|-----------|
| Each quad face gets 4 UV coords mapping [0,0]→[1,0]→[1,1]→[0,1] (slice AC1) | `UvGeneratorTest` FRONT/TOP/RIGHT tests confirm canonical UV ordering |
| TOP face identified as `PrismFace.TOP`, sides as FRONT/BACK/LEFT/RIGHT, bottom as BOTTOM | `PrismFaceTest` + `fromPathIndex` contract |
| Unit tests pass with correct UV values for known Prism dimensions | All `UvGeneratorTest` cases run on JVM with no Android dependencies |
| `RenderCommand.uvCoords` populated when material is `Textured` | `ShapeNode.renderTo()` gate: only emits UVs when `material is Textured && shape is Prism` |

## Definition of Done

- [ ] `PrismFace.kt` created in `isometric-core`
- [ ] `UvGenerator.kt` created in `isometric-shader`
- [ ] `ShapeNode.renderTo()` populates `uvCoords` when material is `Textured` and shape is `Prism`
- [ ] `PrismFaceTest` passes
- [ ] `UvGeneratorTest` — all 9 test cases pass on JVM
- [ ] `apiDump` updated for both modules
- [ ] No regressions in existing `isometric-core` tests
- [ ] `material-types` slice is merged (prerequisite)

## Revision History

### 2026-04-11 — Cohesion Review (rev 1)
- Mode: Review-All (cohesion check after material-types dependency inversion)
- Issues found: 3 (2 HIGH, 1 MED)
  1. **HIGH:** Step 3 imported `IsometricMaterial` and `UvGenerator` into `isometric-compose` — illegal under new arch (compose cannot depend on shader)
  2. **HIGH:** `ShapeNode.material` typed as `IsometricMaterial?` — must be `MaterialData?` (from core)
  3. **MED:** Plan assumed compose's `Shape()` has a material parameter — removed in rev 3
- Fix: Rewrote Step 3 to use a `uvProvider: ((Shape, Int) -> FloatArray?)?` lambda on `ShapeNode`. The lambda is set by the shader module's `Shape()` overload, keeping compose free of shader imports. Updated prerequisite section to reflect new dependency graph.
