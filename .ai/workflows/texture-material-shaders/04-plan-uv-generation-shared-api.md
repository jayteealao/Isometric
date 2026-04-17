---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: uv-generation-shared-api
status: implemented
stage-number: 4
created-at: "2026-04-17T00:00:00Z"
updated-at: "2026-04-17T11:16:37Z"
metric-files-to-touch: 16
metric-step-count: 9
has-blockers: false
revision-count: 0
tags: [uv, api, refactor, shared-infrastructure, sealed-class, face-enum]
depends-on: [api-design-fixes, uv-generation]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-uv-generation-shared-api.md
  siblings:
    - 04-plan-uv-generation.md
    - 04-plan-uv-generation-cylinder.md
    - 04-plan-uv-generation-pyramid.md
    - 04-plan-uv-generation-stairs.md
    - 04-plan-uv-generation-octahedron.md
    - 04-plan-uv-generation-knot.md
  implement: 05-implement-uv-generation-shared-api.md
next-command: wf-verify
next-invocation: "/wf-verify texture-material-shaders uv-generation-shared-api"
---

# Plan: uv-generation-shared-api (prerequisite)

## Slice Goal

Refactor `IsometricMaterial.PerFace` from a concrete `class` to an abstract sealed
base, rename the existing variant to `PerFace.Prism`, add `RenderCommand.faceVertexCount`,
add four shape face types (`CylinderFace`, `PyramidFace`, `StairsFace`, `OctahedronFace`)
to `isometric-core`, add four empty `PerFace.<Shape>` variant stubs to `isometric-shader`,
add a `uvCoordProviderForShape()` factory skeleton in `isometric-shader`, and migrate
every consumer off hard-coded 4-vertex UV assumptions. After this slice merges, each
shape UV slice becomes purely additive (new files + tiny `when`-branch insertions).

## Prerequisite: What Prior Slices Provide

- **`api-design-fixes`** (complete) — stable `IsometricMaterial`, `TextureTransform`,
  `UvCoord`, `TextureSource` API.
- **`uv-generation`** (complete) — `PrismFace`, `UvGenerator.forPrismFace()`,
  `UvCoordProvider` fun interface, `ShapeNode.uvProvider` wiring.

## Current State

**`IsometricMaterial.PerFace`** (`isometric-shader/IsometricMaterial.kt`): concrete
`class` with `private constructor`, companion `fun of(faceMap, default)`, holding
`faceMap: Map<PrismFace, MaterialData>` and `default: MaterialData`. Nesting-ban
invariant in init block. `PerFaceMaterialScope.build()` calls `PerFace.of(...)`.

**`RenderCommand`** (`isometric-core/RenderCommand.kt`): has `uvCoords: FloatArray?`
(8 floats for Prism quads), `faceType: PrismFace?`. No `faceVertexCount` field.
Consumer `GpuUvCoordsBuffer.kt` checks `uv.size >= 8` hard-coded.

**`IsometricMaterialComposables.Shape()`**: `val prism = geometry as? Prism` gates UV
generation to Prism only. No `forShape()` factory exists.

**Shape face enums**: Only `PrismFace` in `isometric-core/shapes/`. No other face enums.

**`TexturedCanvasDrawHook`, `SceneDataPacker.resolveTextureIndex`, `GpuTextureManager.resolveEffectiveMaterial`**:
all access `material.faceMap[face]` directly on `IsometricMaterial.PerFace`.

## Proposed Change Strategy

1. Refactor `PerFace` to abstract sealed class with 5 subclasses (Prism, Cylinder,
   Pyramid, Stairs, Octahedron). Remove `PerFace.of()` entirely (no deprecation;
   user prefers direct breaking changes per memory).
2. Add `RenderCommand.faceVertexCount: Int = 4` at end of parameter list (positional
   call sites unchanged).
3. Add 4 shape face types in `isometric-core`. `PyramidFace` is a **sealed class**
   (not enum) because laterals carry an `index` payload; the others are enums.
4. Add 4 empty `PerFace.<Shape>` variant stubs with typed `resolve(face: <Face>)`
   methods returning `default` for all faces.
5. Add `uvCoordProviderForShape(shape: Shape): UvCoordProvider?` factory in
   `isometric-shader` (internal). Initial: returns Prism provider, null for others.
6. Migrate consumers: `when (m)` sub-dispatch on `PerFace.Prism` vs non-Prism variants.
   `GpuUvCoordsBuffer` uses `faceVertexCount` instead of `>= 8`.
7. Run `apiDump`.

## Likely Files / Areas to Touch

| File | Action | Module |
|------|--------|--------|
| `isometric-shader/src/main/kotlin/.../shader/IsometricMaterial.kt` | MODIFY — abstract sealed PerFace + 5 subclasses | isometric-shader |
| `isometric-core/src/main/kotlin/.../RenderCommand.kt` | MODIFY — add `faceVertexCount` | isometric-core |
| `isometric-core/src/main/kotlin/.../shapes/CylinderFace.kt` | CREATE enum | isometric-core |
| `isometric-core/src/main/kotlin/.../shapes/PyramidFace.kt` | CREATE sealed class | isometric-core |
| `isometric-core/src/main/kotlin/.../shapes/StairsFace.kt` | CREATE enum | isometric-core |
| `isometric-core/src/main/kotlin/.../shapes/OctahedronFace.kt` | CREATE enum | isometric-core |
| `isometric-shader/src/main/kotlin/.../shader/IsometricMaterialComposables.kt` | MODIFY — `uvCoordProviderForShape()` replaces `as? Prism` gate | isometric-shader |
| `isometric-compose/src/main/kotlin/.../runtime/IsometricNode.kt` | MODIFY — set `faceVertexCount = path.points.size` | isometric-compose |
| `isometric-webgpu/src/main/kotlin/.../pipeline/GpuUvCoordsBuffer.kt` | MODIFY — use `faceVertexCount` | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../pipeline/SceneDataPacker.kt` | MODIFY — `when (m)` sub-dispatch | isometric-webgpu |
| `isometric-webgpu/src/main/kotlin/.../texture/GpuTextureManager.kt` | MODIFY — `when (m)` sub-dispatch | isometric-webgpu |
| `isometric-shader/src/test/kotlin/.../shader/IsometricMaterialTest.kt` | MODIFY — `PerFace.of` → `PerFace.Prism.of` | isometric-shader |
| `isometric-shader/src/test/kotlin/.../shader/PerFaceMaterialTest.kt` | MODIFY | isometric-shader |
| `isometric-shader/src/test/kotlin/.../shader/PerFaceSharedApiTest.kt` | CREATE — new tests | isometric-shader |
| `isometric-core/src/test/kotlin/.../shapes/ShapeFaceEnumTest.kt` | CREATE — fromPathIndex tests | isometric-core |
| `isometric-core/api/isometric-core.api` + `isometric-shader/api/isometric-shader.api` | UPDATE (apiDump) | — |

## Step-by-Step Plan

### Step 1 — Refactor `IsometricMaterial.PerFace` to abstract sealed base

Replace current `class PerFace private constructor(...)` with:

```kotlin
sealed interface IsometricMaterial : MaterialData {

    data class Textured(...) : IsometricMaterial { ... }  // unchanged

    sealed class PerFace(open val default: MaterialData) : IsometricMaterial {
        init {
            require(default !is PerFace) {
                "PerFace default cannot itself be a PerFace — nesting is not supported"
            }
        }
        override fun baseColor(): IsoColor = default.baseColor()

        companion object {
            val UNASSIGNED_FACE_DEFAULT: IsoColor = IsoColor(128, 128, 128, 255)
        }

        class Prism(
            val faceMap: Map<PrismFace, MaterialData>,
            override val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {
            init {
                require(faceMap.values.none { it is PerFace }) {
                    "PerFace.Prism: face materials cannot themselves be PerFace"
                }
            }
            override fun equals(other: Any?): Boolean { ... }
            override fun hashCode(): Int { ... }
            override fun toString(): String = "PerFace.Prism(faceMap=$faceMap, default=$default)"

            companion object {
                fun of(faceMap: Map<PrismFace, MaterialData>,
                       default: MaterialData = UNASSIGNED_FACE_DEFAULT) = Prism(faceMap, default)
            }
        }

        class Cylinder(
            val top: MaterialData? = null,
            val bottom: MaterialData? = null,
            val side: MaterialData? = null,
            override val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {
            fun resolve(face: CylinderFace): MaterialData = when (face) {
                CylinderFace.TOP    -> top ?: default
                CylinderFace.BOTTOM -> bottom ?: default
                CylinderFace.SIDE   -> side ?: default
            }
            // equals/hashCode/toString
        }

        class Pyramid(
            val base: MaterialData? = null,
            val laterals: Map<Int, MaterialData> = emptyMap(),
            override val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {
            fun resolve(face: PyramidFace): MaterialData = when (face) {
                PyramidFace.BASE       -> base ?: default
                is PyramidFace.Lateral -> laterals[face.index] ?: default
            }
        }

        class Stairs(
            val tread: MaterialData? = null,
            val riser: MaterialData? = null,
            val side: MaterialData? = null,
            override val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {
            fun resolve(face: StairsFace): MaterialData = when (face) {
                StairsFace.TREAD -> tread ?: default
                StairsFace.RISER -> riser ?: default
                StairsFace.SIDE  -> side  ?: default
            }
        }

        class Octahedron(
            val byIndex: Map<OctahedronFace, MaterialData> = emptyMap(),
            override val default: MaterialData = UNASSIGNED_FACE_DEFAULT,
        ) : PerFace(default) {
            fun resolve(face: OctahedronFace): MaterialData = byIndex[face] ?: default
        }
    }
}
```

Update `PerFaceMaterialScope.build()`:
```kotlin
internal fun build(): IsometricMaterial.PerFace.Prism = IsometricMaterial.PerFace.Prism.of(...)
```

Top-level `perFace { }` return type narrows to `IsometricMaterial.PerFace.Prism`.

### Step 2 — Add `faceVertexCount: Int = 4` to `RenderCommand`

At end of parameter list (preserves positional call sites):

```kotlin
class RenderCommand(
    // ... existing fields ...
    val faceType: PrismFace? = null,
    val faceVertexCount: Int = 4,   // NEW
)
```

Include in `equals`/`hashCode`/`toString`. **Note:** prereq keeps `faceType: PrismFace?`
— does NOT generalize to `ShapeFaceTag?`. Non-Prism shape face dispatch goes via
`originalShape as? <Shape>` + `paths.indexOf` + `<ShapeFace>.fromPathIndex` in consumers.

### Step 3 — Add four shape face types to `isometric-core`

**`CylinderFace.kt` (enum):**
```kotlin
enum class CylinderFace { TOP, BOTTOM, SIDE;
    companion object {
        fun fromPathIndex(index: Int, vertexCount: Int): CylinderFace = when {
            index < 0 -> throw IllegalArgumentException(...)
            index == 0 -> TOP
            index == 1 -> BOTTOM
            else -> SIDE
        }
    }
}
```

**`PyramidFace.kt` (sealed class):**
```kotlin
sealed class PyramidFace {
    object BASE : PyramidFace()
    data class Lateral(val index: Int) : PyramidFace() {
        init { require(index in 0..3) }
    }
    companion object {
        val LATERAL_0 = Lateral(0); val LATERAL_1 = Lateral(1)
        val LATERAL_2 = Lateral(2); val LATERAL_3 = Lateral(3)
        // Straight numbering: path index i -> Lateral(i) for i in 0..3, 4 -> BASE
        // Note: construction-order index 1 is face1.rotateZ(PI) — opposite of Lateral(0),
        // not adjacent. Documented in class KDoc.
        fun fromPathIndex(index: Int): PyramidFace = when (index) {
            0 -> LATERAL_0; 1 -> LATERAL_1
            2 -> LATERAL_2; 3 -> LATERAL_3
            4 -> BASE
            else -> throw IllegalArgumentException(...)
        }
    }
}
```

**`StairsFace.kt` (enum):**
```kotlin
enum class StairsFace { RISER, TREAD, SIDE;
    companion object {
        fun fromPathIndex(index: Int, stepCount: Int): StairsFace {
            val totalPaths = 2 * stepCount + 2
            require(index in 0 until totalPaths) { ... }
            return when {
                index >= 2 * stepCount -> SIDE
                index % 2 == 0 -> RISER
                else -> TREAD
            }
        }
    }
}
```

**`OctahedronFace.kt` (enum, 8 values interleaved):**
```kotlin
enum class OctahedronFace {
    UPPER_0, LOWER_0, UPPER_1, LOWER_1,
    UPPER_2, LOWER_2, UPPER_3, LOWER_3;
    companion object {
        fun fromPathIndex(index: Int): OctahedronFace = when (index) {
            0 -> UPPER_0; 1 -> LOWER_0
            2 -> UPPER_1; 3 -> LOWER_1
            4 -> UPPER_2; 5 -> LOWER_2
            6 -> UPPER_3; 7 -> LOWER_3
            else -> throw IllegalArgumentException(...)
        }
    }
}
```

### Step 4 — Fix consumers in `isometric-webgpu`

`SceneDataPacker.resolveTextureIndex()`:
```kotlin
is IsometricMaterial.PerFace -> {
    val face = cmd.faceType
    when (m) {
        is IsometricMaterial.PerFace.Prism ->
            if (face != null) m.faceMap[face] ?: m.default else m.default
        else -> m.default   // non-Prism variants resolved by their own slices
    }
}
```

Same pattern in `GpuTextureManager.resolveEffectiveMaterial()`.

### Step 5 — Fix `GpuUvCoordsBuffer` hard-coded size guard

```kotlin
val uv = scene.commands[i].uvCoords
val vertCount = scene.commands[i].faceVertexCount
if (uv != null && uv.size >= 2 * vertCount) {
    for (j in 0 until 12) {
        cpu.putFloat(if (j < uv.size) uv[j] else 0f)
    }
} else { /* default UVs */ }
```

Fixed-48-byte buffer layout unchanged. Add TODO comment documenting the 12-float cap
and the need for variable-stride packing (affects Cylinder caps N>6, Stairs sides stepCount>2).

### Step 6 — Add `uvCoordProviderForShape()` factory to `isometric-shader`

```kotlin
internal fun uvCoordProviderForShape(shape: Shape): UvCoordProvider? = when (shape) {
    is Prism -> UvCoordProvider { _, faceIndex -> UvGenerator.forPrismFace(shape, faceIndex) }
    else -> null   // shape UV slices add their branches
}
```

Update `IsometricMaterialComposables.Shape()`:
```kotlin
val uvProvider: UvCoordProvider? = if (needsUvs) uvCoordProviderForShape(geometry) else null
```

Remove the `val prism = geometry as? Prism` and `prism!!` references.

### Step 7 — Propagate `faceVertexCount` in `ShapeNode.renderTo()`

```kotlin
RenderCommand(
    // ... existing fields ...
    faceType = if (isPrism) PrismFace.fromPathIndex(index) else null,
    faceVertexCount = path.points.size,
)
```

For Prism quads this equals 4 (default preserved). For other shapes it's the actual
vertex count.

### Step 8 — Test migrations

Update `IsometricMaterialTest`, `PerFaceMaterialTest`: `PerFace.of(...)` → `PerFace.Prism.of(...)`.
Create `PerFaceSharedApiTest`, `ShapeFaceEnumTest`.

### Step 9 — Run `apiDump`

```bash
./gradlew :isometric-core:apiDump :isometric-shader:apiDump
```

Expected changes:
- `isometric-core`: 4 new face types, `RenderCommand.faceVertexCount` field
- `isometric-shader`: `PerFace` abstract, 5 subclasses, `PerFace.Prism.of()` replaces old
  `PerFace.of()`, `perFace` return type narrows to `PerFace.Prism`

## Test / Verification Plan

### Automated

**`PerFaceSharedApiTest` (new):**
- `PerFace.Prism` constructs and resolves via `faceMap`
- `PerFace.Cylinder.resolve(TOP/BOTTOM/SIDE)` — named field + default fallback
- `PerFace.Pyramid.resolve(BASE/Lateral(i))` — base + laterals + default
- `PerFace.Stairs.resolve(TREAD/RISER/SIDE)` — groups + default
- `PerFace.Octahedron.resolve(face)` — byIndex + default
- Nesting ban applies to all subclasses
- `uvCoordProviderForShape(prism)` returns non-null 8-float UV
- `uvCoordProviderForShape(cylinder/octahedron/...)` returns null
- `RenderCommand.faceVertexCount` defaults to 4, honors explicit value

**`ShapeFaceEnumTest` (new):**
- Each enum's `fromPathIndex` mapping
- Out-of-range throws

**Build verification:**
```bash
./gradlew :isometric-core:compileDebugKotlin :isometric-shader:compileDebugKotlin \
  :isometric-compose:compileDebugKotlin :isometric-webgpu:compileDebugKotlin
./gradlew :isometric-core:test :isometric-shader:test
./gradlew :isometric-core:apiDump :isometric-shader:apiDump
./gradlew :isometric-core:apiCheck :isometric-shader:apiCheck
```

### Interactive

- Run `TexturedDemoActivity` in Canvas and Full WebGPU modes. Verify textured Prism
  with grass top + dirt sides unchanged.
- Add temporary `Shape(Cylinder(...), material = IsometricMaterial.PerFace.Cylinder(default = IsoColor.RED))`
  — compiles, renders flat red (stub returns default), no crash.

## Risks / Watchouts

1. **`PerFace` rename is binary-breaking.** JVM name changes. Library is pre-1.0; commit
   `.api` diff.
2. **`PerFaceMaterialScope.build()` return type narrows** — callers typed as `PerFace`
   (abstract base) still compile.
3. **`PyramidFace` as sealed class** changes `when` pattern from enum listing to
   `is Lateral` + `BASE`. Document.
4. **`GpuUvCoordsBuffer` 48-byte slot unchanged** — shapes with >6 verts per face
   silently truncate. Add TODO; affects cylinder caps (N>6) and stairs sides (stepCount>2).
5. **`when (material)` exhaustive after refactor** — `IsometricMaterial` still has
   `Textured` + `PerFace` direct subtypes only.
6. **`apiCheck` fails on first build.** Run `apiDump` first.

## Dependencies on Other Slices

| Slice | Direction | Notes |
|-------|-----------|-------|
| `api-design-fixes` | upstream | complete |
| `uv-generation` | upstream | complete |
| `uv-generation-*` (cylinder, pyramid, stairs, octahedron, knot) | downstream | blocked by this |

## Assumptions

- Kotlin 2.0.21 supports all sealed class patterns used.
- `binary-compatibility-validator` 0.17.0 produces correct output for sealed hierarchies.
- `IsometricMaterial` gains no third direct subtype in this slice.
- External (app/) code only uses `perFace { }` DSL, not `PerFace.of()` directly.
  Confirmed via grep.

## Blockers

None. All upstream slices are complete.

## Freshness Research

- Kotlin 2.0 sealed class nesting — fully supported
  ([Kotlin docs](https://kotlinlang.org/docs/sealed-classes.html))
- `binary-compatibility-validator` apiDump workflow — breaking change on rename is
  expected; commit `.api` diff.
- Sealed interface exhaustiveness after nested sealed class refactor — `when` on
  `IsometricMaterial` remains exhaustive with 2 branches; `PerFace` branch matches
  all subclasses.

## Definition of Done

- [ ] `IsometricMaterial.PerFace` is abstract sealed class with 5 subclasses
- [ ] `PerFace.Prism.of(...)` replaces old `PerFace.of(...)`; nesting ban moves to
      abstract base `init {}`
- [ ] `perFace { }` DSL return type narrows to `PerFace.Prism`
- [ ] `RenderCommand.faceVertexCount: Int = 4` added at end of params
- [ ] `ShapeNode.renderTo()` sets `faceVertexCount = path.points.size`
- [ ] `GpuUvCoordsBuffer` uses `faceVertexCount` instead of `>= 8`
- [ ] `CylinderFace`, `PyramidFace` (sealed class), `StairsFace`, `OctahedronFace`
      exist in `isometric-core` with `fromPathIndex`
- [ ] `uvCoordProviderForShape(shape)` factory exists; returns non-null for Prism only
- [ ] `IsometricMaterialComposables.Shape()` uses `uvCoordProviderForShape()`
- [ ] `SceneDataPacker.resolveTextureIndex` and `GpuTextureManager.resolveEffectiveMaterial`
      use `when (m)` sub-dispatch
- [ ] All existing tests pass
- [ ] `PerFaceSharedApiTest` (11 cases) and `ShapeFaceEnumTest` (12 cases) pass
- [ ] `apiDump` committed; `apiCheck` passes

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders uv-generation-shared-api`
- **Option B:** After this slice merges, begin shape UV slices. Recommended order:
  octahedron → pyramid → cylinder → stairs → knot.
