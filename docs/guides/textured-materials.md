---
title: Textured Materials
description: Apply bitmap textures and per-face materials to isometric shapes
sidebar:
  order: 9
---

The `isometric-shader` module adds bitmap textures and per-face materials on top
of the existing flat-color rendering. Both the Canvas and WebGPU render paths
honor the same material API, so a scene authored once renders consistently across
both.

## Installation

`isometric-shader` is published alongside `isometric-compose`. Add it to your
build:

```kotlin
dependencies {
    implementation("io.github.jayteealao:isometric-compose:1.1.0")
    implementation("io.github.jayteealao:isometric-shader:1.1.0")
}
```

Wrap the part of your composition that uses textures in
`ProvideTextureRendering` so the renderer knows how to decode and cache bitmaps:

```kotlin
ProvideTextureRendering {
    IsometricScene { /* ... */ }
}
```

`ProvideTextureRendering` installs a `TexturedCanvasDrawHook`, an LRU
`TextureCache`, and an optional `LocalTextureErrorCallback`. Without it, textured
materials fall back to flat-color rendering.

## Hero scenario

A Minecraft-style block with grass on the top face and dirt on the sides:

```kotlin
ProvideTextureRendering {
    IsometricScene {
        Shape(
            geometry = Prism(Point.ORIGIN),
            material = prismPerFace {
                top = texturedResource(R.drawable.grass)
                sides = texturedResource(R.drawable.dirt)
            }
        )
    }
}
```

The `material` parameter is the textured overload of `Shape(...)`. Without it
(or with `material = IsoColor.GREEN`) the shape renders as flat color exactly
as before — texture support is purely additive.

## Material types

`IsometricMaterial` is a sealed interface with two variants:

| Variant | When to use |
|---|---|
| `Textured(source, tint, transform)` | One bitmap covers every face. |
| `PerFace.Prism(top, sides, bottom, default)` (and per-shape equivalents) | Different textures per face group. |

Flat-color rendering does not need an `IsometricMaterial` — `IsoColor` itself
implements `MaterialData`, so `Shape(Prism(origin), IsoColor.BLUE)` keeps
working with no wrapper.

### Texture sources

`TextureSource` describes where the bitmap pixels come from:

| Source | Constructor | Notes |
|---|---|---|
| Drawable resource | `texturedResource(R.drawable.grass)` | Recommended for app-bundled textures. Validates `resId != 0`. |
| Asset file | `texturedAsset("textures/grass.png")` | Path is rejected if it contains `..`, leading `/`, or null bytes. |
| In-memory bitmap | `texturedBitmap(bitmap)` | Useful for procedural or runtime-decoded sources. |

All three factories produce a `Textured` material. They each accept optional
`tint: IsoColor` and `transform: TextureTransform` parameters.

## Per-face DSLs

Every shape that supports per-face materials has its own scope DSL so the
property names match the shape's geometry:

```kotlin
// Prism — top / sides / bottom / default
prismPerFace {
    top = texturedResource(R.drawable.grass)
    sides = texturedResource(R.drawable.dirt)
    // bottom defaults to `default`, which defaults to FlatColor
}

// Cylinder — side wall + two caps
cylinderPerFace {
    side = texturedResource(R.drawable.bark)
    topCap = texturedResource(R.drawable.rings)
    bottomCap = topCap
}

// Pyramid — slanted sides + rectangular base
pyramidPerFace {
    sides = texturedResource(R.drawable.stone)
    base = IsoColor.DARK_GRAY
}

// Stairs — riser, tread, and side walls
stairsPerFace {
    riser = texturedResource(R.drawable.brick)
    tread = texturedResource(R.drawable.tile)
    sides = IsoColor.GRAY
}

// Octahedron — per-triangle slots
octahedronPerFace {
    /* … */
}
```

`Knot` decomposes through its three sub-prisms; pass a single `Textured`
material rather than a per-face one.

> **Caution**
>
Mixing material types between faces of the same shape is supported. Faces with
no entry in the DSL fall through to `default` (and then to flat color if
`default` is not set).

## TextureTransform

`TextureTransform` controls how UVs are sampled into the texture. It applies
identically on Canvas and WebGPU.

```kotlin
val tiledMaterial = texturedResource(
    resId = R.drawable.brick,
    transform = TextureTransform.tiling(scale = 4.0)
)
```

| Factory | Effect |
|---|---|
| `TextureTransform.IDENTITY` | No transform (the default). |
| `TextureTransform.tiling(scale)` | Repeat the texture `scale` times across each face. |
| `TextureTransform.offset(x, y)` | Shift the texture origin. |
| `TextureTransform.rotated(radians)` | Rotate the texture in UV space. |

Custom matrices are accepted through the constructor; the validator rejects
non-finite values and zero scale.

## Handling load failures

Textures decode lazily and may fail (resource missing, asset corrupt, decode
out-of-memory). `LocalTextureErrorCallback` surfaces these:

```kotlin
ProvideTextureRendering(
    onTextureLoadError = { source ->
        Log.w("MyApp", "Failed to load texture: $source")
    }
) {
    IsometricScene { /* ... */ }
}
```

The callback fires on the main thread for both Canvas (decode failure) and
WebGPU (atlas-rebuild failure) paths. Atlas-rebuild failures retry on the next
frame, so transient errors clear themselves.

## Cache configuration

`TextureCacheConfig` tunes the LRU cache that backs decoded bitmaps:

```kotlin
ProvideTextureRendering(
    config = TextureCacheConfig(
        maxSize = 32,                  // entry count
        maxBytes = 16L * 1024 * 1024   // total decoded bytes (optional)
    )
) {
    IsometricScene { /* ... */ }
}
```

Both limits are enforced — eviction triggers when either is exceeded.

## Custom decoding

`TextureLoader` is a `fun interface`, so applications can plug in custom
decoding (e.g., a network loader, a Coil bridge, or a DRM-aware loader):

```kotlin
val customLoader = TextureLoader { source ->
    // Return a Bitmap or null to signal failure
}

ProvideTextureRendering(loader = customLoader) {
    IsometricScene { /* ... */ }
}
```

The default loader (`defaultTextureLoader()`) decodes resources, assets, and
direct bitmaps using `BitmapFactory`.

## Render-mode parity

Both render modes produce visually equivalent output for textured materials:

| Mode | Texture path | Notes |
|---|---|---|
| Canvas (default) | `BitmapShader` + affine matrix per face | Full color, immediate decoding. Resource / asset / bitmap sources all work. |
| Canvas + GPU Sort | Same as Canvas | GPU is used only for depth sorting. |
| Full WebGPU | `RGBA8Unorm` atlas + fragment `textureSample` | Bitmap-only currently; resource/asset paths fall back to the atlas via the texture loader. |

Atlas overflow (textures exceeding 2048 px) currently logs an error and skips
the offending texture; multi-page atlas support is on the roadmap.

## Migration notes

If you used the very early `IsometricMaterial.FlatColor` shim, replace it with
a direct `IsoColor` value:

```kotlin
// Before
Shape(Prism(origin), material = FlatColor(IsoColor.BLUE))

// After
Shape(Prism(origin), material = IsoColor.BLUE)
// or, equivalently, no material parameter at all:
Shape(Prism(origin), color = IsoColor.BLUE)
```

`UvTransform` was renamed to `TextureTransform` — direct rename, no aliases.
