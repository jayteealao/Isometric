---
schema: sdlc/v1
type: plan
slug: texture-material-shaders
slice-slug: material-types
status: complete
stage-number: 4
created-at: "2026-04-11T22:40:00Z"
updated-at: "2026-04-11T22:44:12Z"
metric-files-to-touch: 11
metric-step-count: 14
has-blockers: false
revision-count: 3
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

**Already implemented (commit 3dbb876) — needs rework.** The first implementation added
`api(project(":isometric-shader"))` to `isometric-compose`, making compose depend on
shader. The user's feedback: **isometric-compose must be usable without isometric-shader
and must not depend on it.** Instead of adding a `material` parameter to the existing
`Shape()` composable, provide **overloaded `Shape()` composables** in `isometric-shader`
that accept `IsometricMaterial` instead of `IsoColor`.

### What exists now (from commit 3dbb876)

- `isometric-shader` module with `IsometricMaterial`, `TextureSource`, `UvCoord`, `UvTransform`, DSL builders — **keep all of this**
- `MaterialData` marker interface in `isometric-core` — **keep**
- `RenderCommand` with `material: MaterialData?` and `uvCoords: FloatArray?` — **keep**
- `isometric-compose/build.gradle.kts` has `api(project(":isometric-shader"))` — **remove**
- `ShapeNode`/`PathNode` have `material: IsometricMaterial?` — **change to `MaterialData?`**
- `Shape()`/`Path()` composables have `material: IsometricMaterial?` parameter — **remove parameter**
- `isometric-shader` depends on `isometric-core` only — **add `isometric-compose` + Compose deps**
- No overloaded composables in `isometric-shader` — **add them**

### Target dependency graph

```
isometric-core  (pure JVM — MaterialData, RenderCommand)
       ↓
isometric-compose  (depends on core ONLY — Shape(geometry, color), no shader reference)
       ↓
isometric-shader  (depends on core + compose — types + Shape(geometry, material) overloads)
       ↓
isometric-webgpu  (depends on compose + shader — reads material from RenderCommand)
```

`isometric-compose` is fully usable without `isometric-shader`. Users who want material
support add `isometric-shader` as a dependency and use the overloaded composables.

## Likely Files to Touch

| # | File | Change Type | Module |
|---|------|-------------|--------|
| 1 | `isometric-compose/build.gradle.kts` | Remove `api(project(":isometric-shader"))` | isometric-compose |
| 2 | `isometric-compose/.../IsometricNode.kt` | Change `IsometricMaterial?` → `MaterialData?`, remove shader import | isometric-compose |
| 3 | `isometric-compose/.../IsometricComposables.kt` | Remove `material` param from `Shape()`/`Path()`, remove shader import | isometric-compose |
| 4 | `isometric-shader/build.gradle.kts` | Add `api(project(":isometric-compose"))` + Compose deps | isometric-shader |
| 5 | `isometric-shader/.../IsometricMaterialComposables.kt` | **New file** — overloaded `Shape()` and `Path()` accepting `IsometricMaterial` | isometric-shader |
| 6 | `isometric-compose/api/isometric-compose.api` | Updated by `apiDump` (material param removed) | isometric-compose |
| 7 | `isometric-shader/api/isometric-shader.api` | Updated by `apiDump` (new composables added) | isometric-shader |

Total: 7 files (1 new, 6 modified). Steps 1-7 from original plan (module creation, core types,
DSL builders, tests) are already done and **unchanged**.

## Proposed Change Strategy

### Overloaded composable design

Instead of `Shape(geometry, color, material)` in compose, provide two separate
composable signatures:

**In `isometric-compose` (no shader dependency):**
```kotlin
// Existing — unchanged
fun IsometricScope.Shape(geometry: Shape, color: IsoColor = ..., ...)
```

**In `isometric-shader` (new overloads):**
```kotlin
// Overload: material instead of color
fun IsometricScope.Shape(geometry: Shape, material: IsometricMaterial, ...)
```

The overloaded `Shape()` in shader creates the same `ShapeNode` and sets `material`
on it. When `material` is `FlatColor`, it extracts the color; otherwise it uses
`LocalDefaultColor.current` as the base color and sets the material.

### Node-level plumbing

`ShapeNode.material` stays but is typed as `MaterialData?` (from core) instead of
`IsometricMaterial?` (from shader). This keeps the pipeline working — `RenderCommand`
carries `MaterialData?`, and downstream renderers (Canvas, WebGPU) that depend on
`isometric-shader` cast to `IsometricMaterial` when needed.

### isometric-shader gains Compose dependency

`isometric-shader` now depends on `isometric-compose` (and transitively on Compose
Runtime). This is acceptable:
- `isometric-shader` is already an Android library module
- The composable overloads are the natural home for material-aware API
- No circular dependency: `compose` does NOT depend on `shader`

## Step-by-Step Plan

**Steps 1–7 from the original plan are already implemented and correct.** The changes
below are a rework of steps 8–12.

### Step 8 (rework) — Change `ShapeNode`/`PathNode` material type to `MaterialData?`

File: `isometric-compose/.../IsometricNode.kt`

**Remove** the import:
```kotlin
- import io.github.jayteealao.isometric.shader.IsometricMaterial
```

**Add** the import:
```kotlin
+ import io.github.jayteealao.isometric.MaterialData
```

**Change** `ShapeNode` constructor:
```kotlin
class ShapeNode(
    var shape: Shape,
    var color: IsoColor,
    var material: MaterialData? = null,    // was IsometricMaterial?
) : IsometricNode() {
```

**Change** `PathNode` constructor identically.

The `renderTo()` method already passes `material = material` to `RenderCommand` which
accepts `MaterialData?` — no change needed there.

### Step 9 (rework) — Remove `material` from `Shape()`/`Path()` composables

File: `isometric-compose/.../IsometricComposables.kt`

**Remove** the import:
```kotlin
- import io.github.jayteealao.isometric.shader.IsometricMaterial
```

**Remove** the `material` parameter from `Shape()`:
```kotlin
fun IsometricScope.Shape(
    geometry: Shape,
    color: IsoColor = LocalDefaultColor.current,
-   material: IsometricMaterial? = null,
    alpha: Float = 1f,
    ...
```

**Revert** the factory back to two-arg:
```kotlin
factory = { ShapeNode(geometry, color) },
```

**Remove** the material update line:
```kotlin
-   set(material) { this.material = it; markDirty() }
```

**Remove** the `@param material` KDoc line.

Apply the same changes to `Path()`.

### Step 10 (rework) — Remove `isometric-shader` dependency from `isometric-compose`

File: `isometric-compose/build.gradle.kts`

**Remove** these lines:
```kotlin
-   // api because IsometricMaterial appears in public composable signatures (Shape, Path)
-   api(project(":isometric-shader"))
```

### Step 11 (rework) — Add Compose dependency to `isometric-shader`

File: `isometric-shader/build.gradle.kts`

**Add** plugins and dependencies:
```kotlin
plugins {
    id("isometric.android.library")
+   alias(libs.plugins.kotlin.compose)       // enables Compose compiler
    alias(libs.plugins.dokka)
    id("isometric.publishing")
}

android {
    namespace = "io.github.jayteealao.isometric.shader"
+   buildFeatures { compose = true }
    ...
}

dependencies {
    api(project(":isometric-core"))
+   api(project(":isometric-compose"))
    implementation(libs.annotation)
    implementation(libs.kotlin.stdlib)
+   implementation(libs.compose.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
```

### Step 12 (new) — Create overloaded `Shape()` and `Path()` composables in `isometric-shader`

New file: `isometric-shader/src/main/kotlin/io/github/jayteealao/isometric/shader/IsometricMaterialComposables.kt`

```kotlin
package io.github.jayteealao.isometric.shader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReusableComposeNode
import io.github.jayteealao.isometric.IsoColor
import io.github.jayteealao.isometric.Point
import io.github.jayteealao.isometric.Shape
import io.github.jayteealao.isometric.Path
import io.github.jayteealao.isometric.compose.runtime.IsometricApplier
import io.github.jayteealao.isometric.compose.runtime.IsometricComposable
import io.github.jayteealao.isometric.compose.runtime.IsometricScope
import io.github.jayteealao.isometric.compose.runtime.LocalDefaultColor
import io.github.jayteealao.isometric.compose.runtime.ShapeNode
import io.github.jayteealao.isometric.compose.runtime.PathNode

/**
 * Add a 3D shape with a material to the isometric scene.
 *
 * This overload accepts an [IsometricMaterial] instead of an [IsoColor].
 * For [IsometricMaterial.FlatColor], the color is extracted from the material.
 * For textured or per-face materials, [LocalDefaultColor] is used as the
 * base color and the material is set on the node for the renderer to interpret.
 *
 * @param geometry The 3D shape to render
 * @param material The material describing how faces should be painted
 * @see io.github.jayteealao.isometric.compose.runtime.Shape for the color-only overload
 */
@IsometricComposable
@Composable
fun IsometricScope.Shape(
    geometry: Shape,
    material: IsometricMaterial,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null,
) {
    val color = when (material) {
        is IsometricMaterial.FlatColor -> material.color
        else -> LocalDefaultColor.current
    }
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(geometry, color).also { it.material = material } },
        update = {
            set(geometry) { this.shape = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(material) { this.material = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it; markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it; markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it }
        }
    )
}

/**
 * Add a raw path with a material to the isometric scene.
 *
 * Overload accepting [IsometricMaterial] instead of [IsoColor].
 *
 * @param path The 2D path to render
 * @param material The material describing how the path should be painted
 * @see io.github.jayteealao.isometric.compose.runtime.Path for the color-only overload
 */
@IsometricComposable
@Composable
fun IsometricScope.Path(
    path: Path,
    material: IsometricMaterial,
    alpha: Float = 1f,
    position: Point = Point(0.0, 0.0, 0.0),
    rotation: Double = 0.0,
    scale: Double = 1.0,
    rotationOrigin: Point? = null,
    scaleOrigin: Point? = null,
    visible: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    testTag: String? = null,
    nodeId: String? = null,
) {
    val color = when (material) {
        is IsometricMaterial.FlatColor -> material.color
        else -> LocalDefaultColor.current
    }
    ReusableComposeNode<PathNode, IsometricApplier>(
        factory = { PathNode(path, color).also { it.material = material } },
        update = {
            set(path) { this.path = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(material) { this.material = it; markDirty() }
            set(alpha) { this.alpha = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            set(rotation) {
                require(it.isFinite()) { "rotation must be finite, got $it" }
                this.rotation = it; markDirty()
            }
            set(scale) {
                require(it.isFinite() && it > 0.0) { "scale must be positive and finite, got $it" }
                this.scale = it; markDirty()
            }
            set(rotationOrigin) { this.rotationOrigin = it; markDirty() }
            set(scaleOrigin) { this.scaleOrigin = it; markDirty() }
            set(visible) { this.isVisible = it; markDirty() }
            set(onClick) { this.onClick = it }
            set(onLongClick) { this.onLongClick = it }
            set(testTag) { this.testTag = it }
            set(nodeId) { this.explicitNodeId = it }
        }
    )
}
```

### Step 13 — Run `apiDump` and verify `apiCheck`

```bash
./gradlew :isometric-shader:apiDump
./gradlew :isometric-compose:apiDump
./gradlew apiCheck
```

### Step 14 — Verify full build and tests

```bash
./gradlew build test apiCheck
```

## Test / Verification Plan

| Check | Command | Expected |
|-------|---------|----------|
| Compose compiles WITHOUT shader dep | `./gradlew :isometric-compose:assembleDebug` | BUILD SUCCESSFUL |
| Shader compiles WITH compose dep | `./gradlew :isometric-shader:assembleDebug` | BUILD SUCCESSFUL |
| No circular dependency | Gradle resolves all projects | No cycle error |
| Existing core tests pass | `./gradlew :isometric-core:test` | all green |
| Shader tests pass | `./gradlew :isometric-shader:test` | all green |
| Compose tests pass | `./gradlew :isometric-compose:testDebugUnitTest` | all green |
| API check passes | `./gradlew apiCheck` | no unexpected changes |
| Full build | `./gradlew build` | BUILD SUCCESSFUL |

**Overload resolution check:** Verify that calling `Shape(Prism(origin), flatColor(IsoColor.BLUE))`
resolves to the shader overload, and `Shape(Prism(origin), IsoColor.BLUE)` resolves to the
compose overload. The Kotlin compiler distinguishes by parameter type (`IsometricMaterial` vs
`IsoColor`).

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| `isometric-core` accidentally gains Android dep via `MaterialData` | Low | High | `MaterialData` is a pure Kotlin marker interface. CI will fail immediately. |
| Overload ambiguity between `Shape(geo, color)` and `Shape(geo, material)` | Low | Medium | `IsoColor` and `IsometricMaterial` are unrelated types — Kotlin overload resolution handles this cleanly. Named arguments also disambiguate. |
| `isometric-shader` Compose dependency makes it heavier | Low | Low | Only `compose.runtime` is needed (not UI, Foundation). It's already an Android library. |
| `TextureSource.BitmapSource` holding a recycled bitmap | Medium | Medium | Init guard rejects recycled bitmaps at construction time. |
| Sealed interface evolution risk | Medium | Medium | Documented in KDoc. |
| `ShapeNode`/`PathNode` using `MaterialData?` loses type safety | Low | Low | Only `isometric-shader` overloads set the material field. Renderers cast to `IsometricMaterial` — safe because only `IsometricMaterial` implements `MaterialData`. |

## Dependencies

**Compile-time (revised):**
- `isometric-core` depends on nothing
- `isometric-compose` depends on `isometric-core` (NO shader dependency)
- `isometric-shader` depends on `isometric-core` + `isometric-compose`
- `isometric-webgpu` depends on `isometric-compose` (and will add `isometric-shader` in the `webgpu-textures` slice)

**Slice dependencies:**
- This slice has no dependencies on sibling slices (it is the dependency root).
- All other slices depend on this slice.

## Assumptions

1. The `isometric.android.library` convention plugin is correct for `isometric-shader`.
2. `alias(libs.plugins.kotlin.compose)` and `libs.compose.runtime` are available in the version catalog (already used by `isometric-compose`).
3. `ShapeNode` and `PathNode` are public (needed for `ReusableComposeNode` from shader module). Verified: they are public classes.
4. `IsometricApplier`, `IsometricScope`, `@IsometricComposable`, and `LocalDefaultColor` are public in `isometric-compose`. Verified: all are public.
5. No changes to `isometric-android-view` or `isometric-webgpu` in this slice.

## Blockers

None.

## Freshness Research

No changes since rev 2. All external dependency assumptions still valid.

## Revision History

### 2026-04-11 — Auto-Review (rev 1)
- Mode: Auto-Review
- Issues found: 0
- Verification: All file paths, plugin names, dependency patterns, and API signatures confirmed
  against current codebase state. Plan is current.

### 2026-04-11 — Directed Fix: web-searched best practices (rev 2)
- Mode: Directed Fix
- Feedback: "review plan against web searched best practices and understanding"
- Issues found: 2 (UvCoord validation, sealed interface evolution risk). Fixed.

### 2026-04-11 — Directed Fix: dependency inversion (rev 3)
- Mode: Directed Fix
- Feedback: "isometric-compose should be usable without isometric-shader and should not
  depend on it. plan has been implemented but this needs changing. instead of adding a new
  material parameter why not overload with a constructor that accepts material instead of color"
- **Architectural change:** Reversed the compose→shader dependency. Now shader→compose.
- Changes:
  1. **Step 8 reworked:** `ShapeNode`/`PathNode` use `MaterialData?` (core) not `IsometricMaterial?` (shader)
  2. **Step 9 reworked:** Removed `material` parameter from compose's `Shape()`/`Path()` composables entirely
  3. **Step 10 reworked:** Removed `api(project(":isometric-shader"))` from compose
  4. **Step 11 reworked:** Added `api(project(":isometric-compose"))` + Compose deps to shader
  5. **Step 12 new:** Created overloaded `Shape(geometry, material)` and `Path(path, material)` composables in `isometric-shader`
  6. **Dependency graph:** `core → compose → shader` (was `core → shader → compose`)
  7. **Risk table updated:** Removed circular dependency risk (impossible now), added overload ambiguity risk (low)
  8. **Files to touch reduced:** 11 → 7 (since original steps 1-7 are already done)
  9. **All sibling plans** (`uv-generation`, `canvas-textures`, etc.) reference `isometric-shader` for material types — this is now correct since shader is the leaf module that combines types + composables

## Recommended Next Stage

- **Option A (default):** `/wf-implement texture-material-shaders material-types` — rework
  the already-implemented code per this revised plan. Consider `/compact` first.
- **Option B:** `/wf-plan texture-material-shaders uv-generation` — review the next slice's
  plan before reworking.
