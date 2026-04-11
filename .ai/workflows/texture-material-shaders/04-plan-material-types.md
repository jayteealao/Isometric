---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: material-types
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:40:00Z"
metric-files-to-touch: 9
metric-step-count: 12
has-blockers: false
revision-count: 2
tags: [texture, material, module]
refs:
  index: 00-index.md
  plan-index: 04-plan.md
  slice-def: 03-slice-material-types.md
  siblings: [04-plan-uv-generation.md, 04-plan-canvas-textures.md, 04-plan-webgpu-textures.md, 04-plan-per-face-materials.md, 04-plan-sample-demo.md]
  implement: 05-implement-material-types.md
next-command: wf-implement
next-invocation: "/wf-implement texture-material-shaders material-types"
---

# Plan: Material Type System & Module (slice: `material-types`)

## Current State

The codebase has no material abstraction. Rendering is color-only:

- `RenderCommand` carries `color: IsoColor` and `baseColor: IsoColor` (for GPU lighting) but no material or UV data.
- `ShapeNode` and `PathNode` each carry a `color: IsoColor` property.
- `Shape()` composable accepts `color: IsoColor` as its second parameter.
- No `isometric-shader` module exists; `settings.gradle` lists six modules: `:app`, `:isometric-core`, `:isometric-compose`, `:isometric-android-view`, `:isometric-webgpu`, `:isometric-benchmark`.
- `isometric-core` is a **pure Kotlin/JVM** module (no Android dependencies). This is a hard constraint — anything referencing `android.graphics.Bitmap` or `@DrawableRes` cannot live there.
- `isometric-compose` depends on `isometric-core` via `api(project(":isometric-core"))`.

## Likely Files to Touch

| # | File | Change Type | Module |
|---|------|-------------|--------|
| 1 | `settings.gradle` | Add `include ':isometric-shader'` | root |
| 2 | `isometric-shader/build.gradle.kts` | New file — Android library module | isometric-shader (new) |
| 3 | `isometric-shader/src/main/kotlin/.../IsometricMaterial.kt` | New file — sealed interface + DSL | isometric-shader (new) |
| 4 | `isometric-shader/src/main/kotlin/.../TextureSource.kt` | New file — sealed interface | isometric-shader (new) |
| 5 | `isometric-shader/src/main/kotlin/.../UvCoord.kt` | New file — data class + UvTransform | isometric-shader (new) |
| 6 | `isometric-core/src/main/kotlin/.../RenderCommand.kt` | Add `material` + `uvCoords` fields | isometric-core |
| 7 | `isometric-compose/.../IsometricNode.kt` | Add `material` property to `ShapeNode` + `PathNode` | isometric-compose |
| 8 | `isometric-compose/.../IsometricComposables.kt` | Add `material` parameter to `Shape()` + `Path()` | isometric-compose |
| 9 | `isometric-compose/build.gradle.kts` | Add `api(project(":isometric-shader"))` dependency | isometric-compose |

Total: 9 files (4 new, 5 modified).

## Proposed Change Strategy

### Module placement decision

`IsometricMaterial` references `@DrawableRes` (Android annotation) and `android.graphics.Bitmap` inside `TextureSource`. This makes a pure-JVM home in `isometric-core` impossible without a split. The cleanest approach is a new **Android library** module `isometric-shader` that:

- depends on `isometric-core` for `IsoColor`
- is depended on by `isometric-compose` (and later `isometric-android-view`, `isometric-webgpu`)

This avoids polluting `isometric-core` with Android types and avoids circular dependencies.

**Dependency graph after this slice:**
```
isometric-core  (pure JVM, no change to dependency graph)
       ↓
isometric-shader  (new Android library — depends on isometric-core)
       ↓
isometric-compose  (depends on isometric-shader AND isometric-core)
```

### Backward compatibility

The existing `Shape(geometry, color)` call site must not break. Strategy: keep `color` as a named parameter with its default (`LocalDefaultColor.current`) but add `material: IsometricMaterial? = null` after it. When `material` is null at render time, the node falls back to the existing `color`-based `RenderCommand` path. No rendering changes — the renderer ignores `material` entirely in this slice.

### `RenderCommand` extension

`RenderCommand` is in `isometric-core` (pure JVM). The `material` field type must not introduce Android dependencies into core. `IsometricMaterial` lives in `isometric-shader` (Android), so it cannot be referenced from `isometric-core` directly.

**Resolution:** Keep `RenderCommand` in `isometric-core` clean. Add `material` and `uvCoords` as fields typed to interfaces that live in `isometric-core`:

- `material: Any? = null` — type-erased, carries the `IsometricMaterial` instance opaquely through the core pipeline without a compile dependency
- `uvCoords: FloatArray? = null` — UV coordinates as a flat packed float array `[u0, v0, u1, v1, ...]`, matching the existing `points: DoubleArray` convention

This is the same pattern already used for `ownerNodeId: String?` — a loosely-typed field that higher layers interpret. The compose renderer (which does depend on `isometric-shader`) can safely cast `material as? IsometricMaterial`.

Alternatively, if the team prefers stronger typing at the cost of a core→shader dependency: introduce a `MaterialData` marker interface in `isometric-core` that `IsometricMaterial` implements. This avoids the cast and adds no Android dependency to core. **Recommended**: use the marker interface approach — it makes invalid casts impossible (guideline §6).

### UvCoord placement

`UvCoord` and `UvTransform` contain only `Double` fields — they are pure Kotlin and could live in `isometric-core`. However, placing them in `isometric-shader` keeps all texture/UV types co-located and avoids splitting the conceptual domain. The `uvCoords: FloatArray?` field on `RenderCommand` avoids any cross-module reference.

## Step-by-Step Plan

### Step 1 — Add `isometric-shader` to `settings.gradle`

File: `settings.gradle`

Append after the existing `include` lines:
```groovy
include ':isometric-shader'
```

### Step 2 — Create `isometric-shader/build.gradle.kts`

New file: `isometric-shader/build.gradle.kts`

```kotlin
plugins {
    id("isometric.android.library")
    alias(libs.plugins.dokka)
    id("isometric.publishing")
}

group = "io.github.jayteealao"
version = "1.2.0-SNAPSHOT"

android {
    namespace = "io.github.jayteealao.isometric.shader"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-shader",
        version = version.toString()
    )

    pom {
        name.set("Isometric Shader")
        description.set("Material and texture type system for the Isometric rendering engine")
    }
}

dependencies {
    // api because IsometricMaterial, TextureSource, UvCoord appear in public composable signatures
    api(project(":isometric-core"))
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
```

Also create the directory structure:
- `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/`
- `isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/`
- `isometric-shader/consumer-rules.pro` (empty)
- `isometric-shader/proguard-rules.pro` (empty)

### Step 3 — Create `MaterialData` marker interface in `isometric-core`

New file: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/MaterialData.kt`

```kotlin
package io.github.jayteealao.isometric

/**
 * Marker interface for material data carried through the render pipeline.
 *
 * Implemented by `IsometricMaterial` in the `isometric-shader` module.
 * `RenderCommand` holds a reference typed to this interface so that
 * `isometric-core` remains free of Android dependencies while still
 * providing compile-time safety for the material field.
 */
interface MaterialData
```

### Step 4 — Extend `RenderCommand` with `material` and `uvCoords`

File: `isometric-core/src/main/kotlin/io/github/jayteealao/isometric/RenderCommand.kt`

Add two nullable fields at the end of the constructor parameter list (both default to null so all existing call sites remain valid):

```kotlin
class RenderCommand(
    val commandId: String,
    val points: DoubleArray,
    val color: IsoColor,
    val originalPath: Path,
    val originalShape: Shape?,
    val ownerNodeId: String? = null,
    val baseColor: IsoColor = color,
    val material: MaterialData? = null,          // NEW
    val uvCoords: FloatArray? = null,            // NEW: packed [u0,v0,u1,v1,...]
) {
```

Update `equals`, `hashCode`, and `toString` to include the new fields:

- `equals`: add `material == other.material && (uvCoords contentEquals other.uvCoords || uvCoords == null && other.uvCoords == null)`
- `hashCode`: add `result = 31 * result + (material?.hashCode() ?: 0)` and `result = 31 * result + (uvCoords?.contentHashCode() ?: 0)`
- `toString`: append `, material=$material, uvCoords=${uvCoords?.size?.let { "$it coords" }}`

### Step 5 — Create `UvCoord.kt` and `UvTransform.kt` in `isometric-shader`

New file: `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/UvCoord.kt`

```kotlin
package io.github.jayteealao.isometric.shader

/**
 * A texture coordinate.
 *
 * Values are typically in [0, 1] for a single texture fill, but may exceed this range
 * when tiling is enabled (e.g., `UvTransform.scaleU = 2f` produces UVs in [0, 2]).
 * No range validation is applied — the shader's `TileMode` (REPEAT/CLAMP/MIRROR)
 * determines how out-of-range values are interpreted.
 *
 * @property u Horizontal texture coordinate (0 = left edge, 1 = right edge)
 * @property v Vertical texture coordinate (0 = top edge, 1 = bottom edge)
 */
data class UvCoord(val u: Float, val v: Float) {
    companion object {
        val TOP_LEFT     = UvCoord(0f, 0f)
        val TOP_RIGHT    = UvCoord(1f, 0f)
        val BOTTOM_RIGHT = UvCoord(1f, 1f)
        val BOTTOM_LEFT  = UvCoord(0f, 1f)
    }
}

/**
 * Affine UV transform applied to texture coordinates before sampling.
 *
 * Applied in order: scale → rotate → offset.
 *
 * @property scaleU Horizontal scale factor (1.0 = no repeat, 2.0 = tile twice)
 * @property scaleV Vertical scale factor
 * @property offsetU Horizontal offset (0.0 = no offset)
 * @property offsetV Vertical offset
 * @property rotationDegrees Rotation in degrees around the texture center (0.0 = no rotation)
 */
data class UvTransform(
    val scaleU: Float = 1f,
    val scaleV: Float = 1f,
    val offsetU: Float = 0f,
    val offsetV: Float = 0f,
    val rotationDegrees: Float = 0f,
) {
    companion object {
        val IDENTITY = UvTransform()
    }
}
```

### Step 6 — Create `TextureSource.kt` in `isometric-shader`

New file: `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/TextureSource.kt`

```kotlin
package io.github.jayteealao.isometric.shader

import android.graphics.Bitmap
import androidx.annotation.DrawableRes

/**
 * Describes where a texture's pixel data comes from.
 *
 * Sealed to prevent invalid combinations at compile time (guideline §6).
 * Use the DSL functions [textured], [texturedAsset], [texturedBitmap] from
 * [IsometricMaterial] builders to construct instances idiomatically.
 */
sealed interface TextureSource {
    /**
     * A drawable resource bundled with the app.
     *
     * @property resId Android drawable resource identifier (e.g., `R.drawable.brick`)
     */
    data class Resource(@DrawableRes val resId: Int) : TextureSource

    /**
     * A file in the app's `assets/` directory.
     *
     * @property path Relative path within `assets/` (e.g., `"textures/grass.png"`)
     */
    data class Asset(val path: String) : TextureSource {
        init {
            require(path.isNotBlank()) { "Asset path must not be blank" }
        }
    }

    /**
     * A pre-decoded [Bitmap] provided directly by the caller.
     *
     * The caller retains ownership of the bitmap's lifecycle.
     * Do not recycle the bitmap while any material referencing it is active.
     *
     * @property bitmap The source bitmap. Must not be recycled.
     */
    data class BitmapSource(val bitmap: Bitmap) : TextureSource {
        init {
            require(!bitmap.isRecycled) { "Bitmap must not be recycled" }
        }
    }
}
```

Note: the `Bitmap` variant is named `BitmapSource` to avoid shadowing `android.graphics.Bitmap` in the same file.

### Step 7 — Create `IsometricMaterial.kt` in `isometric-shader`

New file: `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterial.kt`

```kotlin
package io.github.jayteealao.isometric.shader

import androidx.annotation.DrawableRes
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.MaterialData

/**
 * Describes how a face should be painted.
 *
 * Implements [MaterialData] so that [io.github.jayteealao.isometric.RenderCommand]
 * can carry a material reference without depending on Android types.
 *
 * ## Progressive disclosure (guideline §2)
 *
 * - **Simple:** `flatColor(IsoColor.BLUE)` — zero overhead, identical to existing color path
 * - **Configurable:** `textured(R.drawable.brick) { uvScale(2f, 2f) }` — bitmap texture
 * - **Advanced:** `perFace { top = textured(...); sides = flatColor(...) }` — per-face control
 *
 * ## Sealed interface (guideline §6)
 *
 * Subtypes are exhaustive — renderers `when`-match without an else branch.
 */
sealed interface IsometricMaterial : MaterialData {

    /**
     * Renders the face with a solid [IsoColor]. Zero texture overhead.
     * This is the default when no material is specified (backward compatible path).
     */
    data class FlatColor(val color: IsoColor) : IsometricMaterial

    /**
     * Renders the face with a [TextureSource] bitmap, optionally tinted and transformed.
     *
     * @property source Where to load the bitmap from
     * @property tint Multiplicative color tint applied over the texture (WHITE = no tint)
     * @property uvTransform Affine UV transform (scale, offset, rotation)
     */
    data class Textured(
        val source: TextureSource,
        val tint: IsoColor = IsoColor.WHITE,
        val uvTransform: UvTransform = UvTransform.IDENTITY,
    ) : IsometricMaterial

    /**
     * Assigns different materials to different faces of a shape.
     *
     * Faces not covered by this map fall back to [default].
     *
     * @property faceMap Map from face index (0-based, matching [Shape.paths] order) to material
     * @property default Material used for faces not present in [faceMap]
     */
    data class PerFace(
        val faceMap: Map<Int, IsometricMaterial>,
        val default: IsometricMaterial,
    ) : IsometricMaterial
}

// ── DSL builders ─────────────────────────────────────────────────────────────

/**
 * Creates a [IsometricMaterial.FlatColor] material.
 *
 * ```kotlin
 * Shape(Prism(origin), material = flatColor(IsoColor.BLUE))
 * ```
 */
fun flatColor(color: IsoColor): IsometricMaterial.FlatColor = IsometricMaterial.FlatColor(color)

/**
 * Creates a [IsometricMaterial.Textured] material from a drawable resource.
 *
 * ```kotlin
 * Shape(Prism(origin), material = textured(R.drawable.brick) {
 *     tint = IsoColor.WHITE
 *     uvScale(2f, 2f)
 * })
 * ```
 */
fun textured(
    @DrawableRes resId: Int,
    block: TexturedBuilder.() -> Unit = {},
): IsometricMaterial.Textured = TexturedBuilder(TextureSource.Resource(resId)).apply(block).build()

/**
 * Creates a [IsometricMaterial.Textured] material from an asset path.
 */
fun texturedAsset(
    path: String,
    block: TexturedBuilder.() -> Unit = {},
): IsometricMaterial.Textured = TexturedBuilder(TextureSource.Asset(path)).apply(block).build()

/**
 * Creates a [IsometricMaterial.Textured] material from a [android.graphics.Bitmap].
 */
fun texturedBitmap(
    bitmap: android.graphics.Bitmap,
    block: TexturedBuilder.() -> Unit = {},
): IsometricMaterial.Textured = TexturedBuilder(TextureSource.BitmapSource(bitmap)).apply(block).build()

/**
 * Creates a [IsometricMaterial.PerFace] material via a builder.
 *
 * ```kotlin
 * Shape(Prism(origin), material = perFace(default = flatColor(IsoColor.GRAY)) {
 *     face(0, textured(R.drawable.grass))   // top face
 *     face(1, textured(R.drawable.dirt))    // front face
 *     face(2, textured(R.drawable.dirt))    // right face
 * })
 * ```
 */
fun perFace(
    default: IsometricMaterial = IsometricMaterial.FlatColor(IsoColor.GRAY),
    block: PerFaceBuilder.() -> Unit,
): IsometricMaterial.PerFace = PerFaceBuilder(default).apply(block).build()

// ── Builder classes ───────────────────────────────────────────────────────────

@IsometricMaterialDsl
class TexturedBuilder internal constructor(private val source: TextureSource) {
    var tint: IsoColor = IsoColor.WHITE
    private var uvTransform: UvTransform = UvTransform.IDENTITY

    /** Sets the UV scale. Values > 1 tile the texture; values < 1 show a sub-region. */
    fun uvScale(scaleU: Float, scaleV: Float) {
        uvTransform = uvTransform.copy(scaleU = scaleU, scaleV = scaleV)
    }

    /** Sets the UV offset in [0, 1] space. */
    fun uvOffset(offsetU: Float, offsetV: Float) {
        uvTransform = uvTransform.copy(offsetU = offsetU, offsetV = offsetV)
    }

    /** Sets the UV rotation in degrees. */
    fun uvRotate(degrees: Float) {
        uvTransform = uvTransform.copy(rotationDegrees = degrees)
    }

    internal fun build(): IsometricMaterial.Textured =
        IsometricMaterial.Textured(source = source, tint = tint, uvTransform = uvTransform)
}

@IsometricMaterialDsl
class PerFaceBuilder internal constructor(private val default: IsometricMaterial) {
    private val faceMap = mutableMapOf<Int, IsometricMaterial>()

    /** Assigns [material] to the face at [index] (0-based, matching shape.paths order). */
    fun face(index: Int, material: IsometricMaterial) {
        require(index >= 0) { "Face index must be non-negative, got $index" }
        faceMap[index] = material
    }

    internal fun build(): IsometricMaterial.PerFace =
        IsometricMaterial.PerFace(faceMap = faceMap.toMap(), default = default)
}

/** DSL marker preventing scope leakage between nested builders (guideline §10). */
@DslMarker
annotation class IsometricMaterialDsl
```

### Step 8 — Add `material` property to `ShapeNode` and `PathNode`

File: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricNode.kt`

In `ShapeNode`, add a `material` field after `color`:

```kotlin
class ShapeNode(
    var shape: Shape,
    var color: IsoColor,
    var material: IsometricMaterial? = null,    // NEW
) : IsometricNode() {
```

In `ShapeNode.renderTo`, pass `material` into the `RenderCommand`:

```kotlin
output.add(
    RenderCommand(
        commandId = "${nodeId}_${path.hashCode()}",
        points = DoubleArray(0),
        color = effectiveColor,
        originalPath = path,
        originalShape = transformedShape,
        ownerNodeId = nodeId,
        material = material,                   // NEW
    )
)
```

In `PathNode`, add the same `material` field and pass it through identically.

`BatchNode` does **not** get a `material` field in this slice — it uses uniform color semantics and per-face materials don't apply. Leave it for the `per-face-materials` slice.

### Step 9 — Add `material` parameter to `Shape()` and `Path()` composables

File: `isometric-compose/src/main/kotlin/io/github/jayteealao/isometric/compose/runtime/IsometricComposables.kt`

Add import:
```kotlin
import io.github.jayteealao.isometric.shader.IsometricMaterial
```

In `IsometricScope.Shape()`, add `material` parameter after `color`:

```kotlin
fun IsometricScope.Shape(
    geometry: Shape,
    color: IsoColor = LocalDefaultColor.current,
    material: IsometricMaterial? = null,        // NEW — null means use color
    alpha: Float = 1f,
    // ... remaining params unchanged
) {
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(geometry, color, material) },
        update = {
            set(geometry) { this.shape = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(material) { this.material = it; markDirty() }    // NEW
            // ... remaining sets unchanged
        }
    )
}
```

Apply the same change to `IsometricScope.Path()`.

### Step 10 — Wire `isometric-shader` into `isometric-compose`

File: `isometric-compose/build.gradle.kts`

Add dependency:
```kotlin
// api because IsometricMaterial appears in public composable signatures (Shape, Path)
api(project(":isometric-shader"))
```

### Step 11 — Write unit tests for `isometric-shader`

New file: `isometric-shader/src/test/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialTest.kt`

Tests to cover:
1. `flatColor(IsoColor.BLUE)` returns `IsometricMaterial.FlatColor(IsoColor.BLUE)`
2. `textured(R.drawable.brick)` returns `IsometricMaterial.Textured` with `tint = IsoColor.WHITE` and `uvTransform = UvTransform.IDENTITY`
3. `textured(R.drawable.brick) { uvScale(2f, 2f) }` sets `uvTransform.scaleU == 2f`
4. `perFace(default = flatColor(IsoColor.GRAY)) { face(0, flatColor(IsoColor.GREEN)) }` produces `PerFace` with correct map
5. `UvCoord(2f, 3f)` is valid (tiling produces values > 1.0)
6. `TextureSource.Asset("")` rejects blank path
7. `PerFaceBuilder.face(-1, ...)` rejects negative index

Note: `@DrawableRes` is an annotation and does not require a running Android device for tests.

### Step 12 — Run `apiDump` and verify `apiCheck`

```bash
./gradlew :isometric-shader:apiDump
./gradlew :isometric-core:apiDump
./gradlew :isometric-compose:apiDump
./gradlew apiCheck
```

This records the new public surface for binary compatibility tracking.

## Test / Verification Plan

| Check | Command | Expected |
|-------|---------|----------|
| Module compiles | `./gradlew :isometric-shader:assembleDebug` | BUILD SUCCESSFUL |
| Core compiles (no regression) | `./gradlew :isometric-core:compileKotlin` | BUILD SUCCESSFUL |
| Compose compiles | `./gradlew :isometric-compose:assembleDebug` | BUILD SUCCESSFUL |
| Existing core tests pass | `./gradlew :isometric-core:test` | all green |
| New shader tests pass | `./gradlew :isometric-shader:test` | all green |
| Compose tests pass | `./gradlew :isometric-compose:testDebugUnitTest` | all green |
| API surface recorded | `./gradlew apiDump` | new `.api` file for `isometric-shader` |
| API check passes | `./gradlew apiCheck` | no changes reported for existing modules |
| Full build | `./gradlew build` | BUILD SUCCESSFUL |

**Manual backward-compat check:** After wiring into the sample app, verify that all existing `Shape(geometry, color)` calls still compile and render identically.

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| `isometric-core` accidentally gains Android dep via `MaterialData` | Low | High | `MaterialData` is a pure Kotlin marker interface — no imports. CI will fail the moment `android.*` appears in `isometric-core`. |
| Circular module dependency (`isometric-shader` → `isometric-compose` → `isometric-shader`) | Low | High | `isometric-shader` must only depend on `isometric-core`. Never on `isometric-compose`. Enforced by build graph — Gradle will error on cycles. |
| `TextureSource.BitmapSource` holding a recycled bitmap | Medium | Medium | Init guard in `BitmapSource` rejects recycled bitmaps at construction time (guideline §7). |
| `perFace { face(0, ...) }` face index mismatch with actual shape path order | Medium | Low | Document clearly that indices are 0-based matching `Shape.paths` order. Actual UV/face-name mapping deferred to `per-face-materials` slice. |
| Binary compatibility breakage in `RenderCommand` | Low | High | New fields have defaults — existing call sites compile without change. `apiDump` records the change; `apiCheck` enforces it. |
| Sealed interface evolution risk | Medium | Medium | Adding a new `IsometricMaterial` subtype in the future is a **breaking change** for consumers using exhaustive `when` expressions. Document this in KDoc: "This sealed hierarchy may grow in future versions. Use an `else` branch in `when` if forward compatibility is needed." Consider adding `@Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")` guidance in docs. |
| `isometric-compose` failing `apiCheck` due to new `material` param | Low | Medium | Adding a defaulted parameter to a composable is source-compatible but may be detected as a binary change. Run `apiDump` first and commit the updated `.api` file. |

## Dependencies

**Compile-time:**
- `isometric-shader` depends on `isometric-core` (for `IsoColor`, `MaterialData`)
- `isometric-compose` depends on `isometric-shader` (for `IsometricMaterial`)

**Slice dependencies:**
- This slice has no dependencies on sibling slices (it is the dependency root).
- All other slices (`uv-generation`, `canvas-textures`, `webgpu-textures`, `per-face-materials`, `sample-demo`) depend on this slice.

## Assumptions

1. The `isometric.android.library` convention plugin (in `build-logic`) is the correct template for `isometric-shader`. It sets `compileSdk = 35`, `minSdk = 24`, `jvmTarget = 11`.
2. `@DrawableRes` is from `androidx.annotation` which is already available transitively through `isometric-core`'s dependents — but `isometric-shader/build.gradle.kts` may need to add `implementation(libs.androidx.annotation)` if the transitive path doesn't exist. Check after Step 2.
3. The `libs.plugins.dokka` and `id("isometric.publishing")` plugins are safe to apply to the new module (same pattern as `isometric-core`).
4. Face index 0-based ordering in `PerFace.faceMap` is sufficient for this slice. Named face accessors (e.g., `PrismFace.TOP`) are deferred to `per-face-materials`.
5. No changes to `isometric-android-view` or `isometric-webgpu` in this slice — those modules render from `RenderCommand` and currently ignore the new nullable `material` field.

## Freshness Research

The research doc (`TEXTURE_SHADER_RESEARCH.md`) uses `sealed interface Material` as the type name in section 8.1. This plan uses `IsometricMaterial` per the slice definition (`03-slice-material-types.md`) to avoid name collision with Kotlin's `Material` in Compose Material Design. The research doc's `TextureSource.Bitmap` variant is renamed to `TextureSource.BitmapSource` to avoid shadowing `android.graphics.Bitmap` in the same compilation unit.

The `RenderCommand` in the research doc (section 8.5) shows `data class RenderCommand(... val material: Material? = null, val uvCoords: List<UvCoord>? = null)`. The current implementation uses a `class` (not `data class`) with a flat `DoubleArray` for points. This plan follows the current implementation pattern: adds `material: MaterialData?` (typed to the marker interface) and `uvCoords: FloatArray?` (flat-packed to match the `points: DoubleArray` convention).

## Revision History

### 2026-04-11 — Auto-Review (rev 1)
- Mode: Auto-Review
- Issues found: 0
- Verification: All file paths, plugin names, dependency patterns, and API signatures confirmed
  against current codebase state. Plan is current.

### 2026-04-11 — Directed Fix: web-searched best practices (rev 2)
- Mode: Directed Fix
- Feedback: "review plan against web searched best practices and understanding"
- Web research performed on: sealed interface API design, BitmapShader best practices,
  marker interface vs type-erased Any pattern, isometric UV generation per-face architecture
- Issues found: 2
  1. **UvCoord range validation too strict** (HIGH) — `require(u in 0f..1f)` would crash on
     tiled textures where `UvTransform.scaleU > 1` produces UVs beyond [0,1]. Fix: removed
     range validation; TileMode handles out-of-range values. Updated KDoc to explain.
  2. **Sealed interface evolution risk undocumented** (MED) — adding a new `IsometricMaterial`
     subtype in the future is a breaking change for consumers with exhaustive `when`. Fix:
     added to Risks table with mitigation guidance.
- Also updated: test plan item 5 (was "rejects u=1.5f", now "u=2f is valid for tiling")
- Best practices confirmed correct:
  - `sealed interface` over `sealed class` — plan already correct
  - `MaterialData` marker interface over `Any?` — plan already uses this (strongly preferred)
  - BitmapShader + `setLocalMatrix` + `ARGB_8888` — plan already uses this
  - `@DslMarker` annotation — plan already uses this
  - Per-face planar/box UV projection for isometric — plan already uses this

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders material-types` — plan
  verified, ready for execution. Consider `/compact` first.
- **Option B:** `/wf-plan texture-material-shaders uv-generation` — review the next
  slice's plan before implementing.
