---
title: Theming & Colors
description: Colors, palettes, lighting, and stroke styles
sidebar:
  order: 4
---

## IsoColor

Isometric uses its own `IsoColor` type rather than Compose's `Color`. Values are in the 0--255 range.

### Constructors

```kotlin
// Int constructor
val blue = IsoColor(33, 150, 243)

// Double constructor
val blue = IsoColor(33.0, 150.0, 243.0)

// Alpha (0-255, default 255)
val semiTransparent = IsoColor(33, 150, 243, 128)

// From hex
val blue = IsoColor.fromHex(0x2196F3)
val blue = IsoColor.fromHex("#2196F3")

// From ARGB components (matches Android's ARGB ordering)
val blue = IsoColor.fromArgb(255, 33, 150, 243)
```

### Named Constants

`IsoColor.WHITE`, `IsoColor.BLACK`, `IsoColor.RED`, `IsoColor.GREEN`, `IsoColor.BLUE`, `IsoColor.GRAY`, `IsoColor.DARK_GRAY`, `IsoColor.LIGHT_GRAY`, `IsoColor.CYAN`, `IsoColor.ORANGE`, `IsoColor.PURPLE`, `IsoColor.YELLOW`, `IsoColor.BROWN`.

### HSL Properties

`IsoColor` exposes `h`, `s`, and `l` properties for hue, saturation, and lightness. These are computed lazily on first access.

### lighten()

`lighten(percentage, lightColor)` blends this color with a light color and increases lightness. Used internally for face shading, but also useful for generating shade variations:

```kotlin
val base = IsoColor(33, 150, 243)
val lighter = base.lighten(0.2, IsoColor.WHITE) // 20% lighter
val darker = base.lighten(-0.1, IsoColor.WHITE) // 10% darker
```

### Compose Interop

Convert between `IsoColor` and Compose `Color`:

```kotlin
val compose: Color = isoColor.toComposeColor()
val iso: IsoColor = composeColor.toIsoColor()
```

## ColorPalette

`ColorPalette` groups related colors for consistent theming:

| Property | Purpose |
|----------|---------|
| `primary` | Main brand color |
| `secondary` | Supporting color |
| `accent` | Highlight / call-to-action |
| `background` | Scene background |
| `surface` | Surface-level shapes |
| `error` | Error states |

## CompositionLocals

Isometric provides several `CompositionLocal` values that control default rendering:

| Local | Default | Purpose |
|-------|---------|---------|
| `LocalDefaultColor` | Material Blue | Default shape color when none is specified |
| `LocalLightDirection` | `Vector(2, -1, 3).normalize()` | Directional light for face shading |
| `LocalStrokeStyle` | `FillAndStroke` | How edges are rendered |
| `LocalColorPalette` | Built-in palette | Theme colors |
| `LocalRenderOptions` | `RenderOptions.Default` | Depth sorting, culling, bounds checking |
| `LocalIsometricEngine` | Error if outside scene | Access the engine for `worldToScreen` / `screenToWorld` |

Override them with `CompositionLocalProvider`:

```kotlin
CompositionLocalProvider(
    LocalDefaultColor provides IsoColor(76, 175, 80),
    LocalLightDirection provides Vector(0.0, -1.0, 2.0).normalize()
) {
    IsometricScene {
        // Shapes here use green as the default color
        // and top-down lighting
        Shape(geometry = Prism(Point.ORIGIN))
    }
}
```

## StrokeStyle

`StrokeStyle` is a sealed class with three variants: `FillOnly`, `Stroke(width, color)`, and `FillAndStroke(width, color)` (default). See [Scene Config reference](../reference/scene-config.md) for the full variant table.

```kotlin
CompositionLocalProvider(
    LocalStrokeStyle provides StrokeStyle.FillOnly
) {
    IsometricScene {
        Shape(geometry = Cylinder(Point.ORIGIN))
    }
}
```
