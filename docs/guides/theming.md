---
title: Theming & Colors
description: Colors, palettes, lighting, and stroke styles
sidebar:
  order: 5
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

// From hex — the numeric overload takes a Long
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

### withAlpha()

`withAlpha(alpha)` returns a copy with the alpha channel multiplied by a `0f..1f` factor —
handy for deriving translucent variants of a palette color:

```kotlin
val base = IsoColor(33, 150, 243)
val ghost = base.withAlpha(0.5f) // half as opaque as base
```

Values outside `0f..1f` throw `IllegalArgumentException`. For fading a rendered node
rather than deriving a color, prefer the per-node `alpha` prop — see
[Per-Node Interactions](interactions.md#alpha).

### Compose Interop

Convert between `IsoColor` and Compose `Color`:

```kotlin
val compose: Color = isoColor.toComposeColor()
val iso: IsoColor = composeColor.toIsoColor()
```

## ColorPalette

`ColorPalette` groups six named color roles for consistent theming:

| Property | Purpose |
|----------|---------|
| `primary` | Main brand color |
| `secondary` | Supporting color |
| `accent` | Highlight / call-to-action |
| `background` | Scene background |
| `surface` | Surface-level shapes |
| `error` | Error states |

Read it via `LocalColorPalette.current` and reference roles by name:

```kotlin
@Composable
fun IsometricScope.PaletteDemo() {
    val palette = LocalColorPalette.current
    Shape(geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0), color = palette.primary)
    Shape(geometry = Prism(Point(3.0, 0.0, 0.0)), color = palette.accent)
    Shape(geometry = Prism(Point(0.0, 3.0, 0.0)), color = palette.surface)
}
```

Create a custom palette by constructing a new `ColorPalette` or calling `copy()` on an
existing one:

```kotlin
val darkPalette = ColorPalette(
    primary = IsoColor(30, 30, 30),
    secondary = IsoColor(60, 60, 60),
    accent = IsoColor(0, 255, 128),
    background = IsoColor.BLACK,
    surface = IsoColor(50, 50, 50),
    error = IsoColor.RED
)

// Or modify an existing palette
val modified = LocalColorPalette.current.copy(accent = IsoColor.YELLOW)
```

## Overriding Theme Defaults

Isometric's theme defaults flow through `CompositionLocal` values — see the
[CompositionLocals reference](../reference/composition-locals.md) for the full table of
locals, types, and defaults. Wrap your scene (or any subtree within it) in
`CompositionLocalProvider` to change the ambient values:

```kotlin
@Composable
fun ThemedScene() {
    CompositionLocalProvider(
        LocalDefaultColor provides IsoColor(76, 175, 80),
        LocalLightDirection provides Vector(0.0, -1.0, 2.0).normalize(),
        LocalStrokeStyle provides StrokeStyle.FillOnly
    ) {
        IsometricScene {
            // All shapes here default to green, top-down lighting, no stroke
            Shape(geometry = Prism(Point.ORIGIN, 2.0, 2.0, 2.0))
            Shape(geometry = Prism(Point(3.0, 0.0, 0.0), 1.0, 1.0, 3.0))
        }
    }
}
```

### Per-Subtree Theming

Nest providers to apply different themes to different parts of the scene. Each provider
overrides only the locals it specifies; the rest inherit from the parent:

```kotlin
@Composable
fun MultiThemeScene() {
    IsometricScene {
        // Default blue shapes
        Shape(geometry = Prism(Point.ORIGIN))

        // Red subtree
        CompositionLocalProvider(LocalDefaultColor provides IsoColor.RED) {
            Shape(geometry = Prism(Point(2.0, 0.0, 0.0)))
            Shape(geometry = Prism(Point(2.0, 2.0, 0.0)))
        }

        // Green subtree with custom palette
        CompositionLocalProvider(
            LocalDefaultColor provides IsoColor.GREEN,
            LocalColorPalette provides ColorPalette(
                primary = IsoColor.GREEN,
                secondary = IsoColor.CYAN
            )
        ) {
            val palette = LocalColorPalette.current
            Shape(
                geometry = Prism(Point(0.0, 2.0, 0.0)),
                color = palette.secondary
            )
        }
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
