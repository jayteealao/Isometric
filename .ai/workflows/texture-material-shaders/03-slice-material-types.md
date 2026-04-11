---
schema: sdlc/v1
type: slice
slug: texture-material-shaders
slice-slug: material-types
status: defined
stage-number: 3
created-at: "2026-04-11T22:30:00Z"
updated-at: "2026-04-11T22:30:00Z"
complexity: m
depends-on: []
tags: [texture, material, module]
refs:
  index: 00-index.md
  slice-index: 03-slice.md
  siblings: [03-slice-uv-generation.md, 03-slice-canvas-textures.md, 03-slice-webgpu-textures.md, 03-slice-per-face-materials.md, 03-slice-sample-demo.md]
  plan: 04-plan-material-types.md
  implement: 05-implement-material-types.md
---

# Slice: Material Type System & Module

## Goal

Create the `isometric-shader` Gradle module with the `IsometricMaterial` sealed
interface, `TextureSource` types, `UvCoord` data class, and wire the material field
through `RenderCommand`, `ShapeNode`, and the `Shape()` composable. No rendering
changes yet — this is the type foundation everything else builds on.

## Why This Slice Exists

Every other slice depends on these types. By delivering them first with no rendering
changes, we get: compile-time validation of the module dependency graph, backward
compatibility verification, and a stable API surface before adding complex renderer code.

## Scope

**In:**
- New `isometric-shader` Gradle module (build.gradle, settings.gradle)
- `IsometricMaterial` sealed interface: `FlatColor`, `Textured`, `PerFace`
- `TextureSource` sealed interface: `Resource`, `Asset`, `Bitmap`
- `UvCoord` data class
- `UvTransform` data class
- DSL builder functions: `textured()`, `flatColor()`, `perFace {}`
- `RenderCommand` extended with `material: IsometricMaterial?` and `uvCoords: FloatArray?`
- `ShapeNode` / `PathNode` get `material` property
- `Shape()` composable gets `material` parameter (default null = use `color`)
- `apiDump` for new module and updated modules

**Out:**
- No texture loading or caching (slice 3)
- No UV generation (slice 2)
- No rendering changes in Canvas or WebGPU (slices 3, 4)
- No sample app changes (slice 6)

## Acceptance Criteria

- Given the `isometric-shader` module added to `settings.gradle`
- When the project builds
- Then all modules compile, `apiCheck` passes, existing tests pass

- Given `Shape(Prism(origin), material = flatColor(IsoColor.BLUE))`
- When the scene renders
- Then output is identical to `Shape(Prism(origin), IsoColor.BLUE)` (backward compatible)

- Given `Shape(Prism(origin), material = textured(R.drawable.brick))`
- When the scene renders in Canvas mode
- Then it renders as flat color (texture rendering not implemented yet) with no crash

## Dependencies on Other Slices

None — this is the foundation slice.

## Risks

- Module dependency cycle: `isometric-shader` depends on `isometric-core` for Shape/Path
  types. `isometric-compose` depends on `isometric-shader` for Material types. Must
  ensure no circular dependency.
- `TextureSource.Bitmap` references `android.graphics.Bitmap` — this makes the module
  Android-specific. May need to split platform-agnostic types from platform-specific ones.
