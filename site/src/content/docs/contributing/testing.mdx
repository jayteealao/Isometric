---
title: Testing
description: Running tests, generating screenshots, and writing new tests
sidebar:
  order: 2
---

## Running Tests

Run the full core test suite:

```bash
./gradlew :isometric-core:test
```

Run a specific test class:

```bash
./gradlew :isometric-core:test --tests "io.fabianterhorst.isometric.IsoColorTest"
```

Run a single test method:

```bash
./gradlew :isometric-core:test --tests "io.fabianterhorst.isometric.IsoColorTest.testFromHexString"
```

## Test Classes

The core module has unit tests covering all fundamental types:

| Test Class | Covers |
|------------|--------|
| `IsoColorTest` | Color construction, HSL conversion, `fromHex`, `lighten`, `toRGBA`, named constants |
| `PointTest` | Point arithmetic, translation, rotation, scaling, depth calculation |
| `Point2DTest` | 2D point operations |
| `VectorTest` | Vector operations, dot product, cross product, normalization |
| `PathTest` | Path construction, transforms, `closerThan` depth comparison, reversal |
| `ShapeTest` | Shape construction, transforms, extrusion, `orderedPaths` |
| `CircleTest` | Circle path generation with configurable vertex count |
| `IsometricEngineTest` | Engine construction, `add`/`clear`, `projectScene`, hit testing |
| `IsometricEngineProjectionTest` | `worldToScreen`, `screenToWorld` round-trip accuracy |
| `TileCoordinateTest` | Construction, `equals`/`hashCode`/`toString`, `plus`/`minus` operators, `isWithin`, `toPoint`, `ORIGIN` |
| `TileGridConfigTest` | Default values, `tileSize` validation, elevation lambda storage, `equals`/`hashCode` (elevation excluded), `toString` |
| `TileCoordinateExtensionsTest` | `Point.toTileCoordinate` with positive/negative/boundary coords, floor-not-truncation, `screenToTile` round-trip |
| `StackAxisTest` | All three enum values, `unitPoint()` unit vectors, one-non-zero-component invariant |

All test files are located under:

```
isometric-core/src/test/kotlin/io/fabianterhorst/isometric/
```

WS9 test files use the updated package:

```
isometric-core/src/test/kotlin/io/github/jayteealao/isometric/
```

The compose module also has instrumented tests that run on a device or emulator:

| Test Class | Covers |
|------------|--------|
| `TileGridTest` | Per-tile content invocation, bounds enforcement, `onTileClick` wiring, elevation function inputs |
| `StackTest` | Item count, axis directions, gap validation, negative gap, nested stacks |

```bash
./gradlew :isometric-compose:connectedAndroidTest
```

## Screenshot Generation

`DocScreenshotGenerator` is a test-based tool that renders scenes to PNG files using a headless AWT `BufferedImage`. This is how the documentation screenshots are produced.

### Running the Generator

```bash
./gradlew :isometric-core:test --tests "io.fabianterhorst.isometric.DocScreenshotGenerator"
```

Generated images are written to `docs/assets/screenshots/`. After generating, copy them to the docs site:

```bash
cp docs/assets/screenshots/*.png site/public/screenshots/
```

### How It Works

`DocScreenshotGenerator` uses `AwtRenderer` (a test utility in the same source set) to draw `RenderCommand` output from `IsometricEngine` onto a `BufferedImage`. The renderer converts each command's `Point2D` polygon into an AWT `Polygon` and fills it with the computed color.

This approach avoids any Android or Compose dependency, keeping screenshot generation runnable on any JVM.

## Writing New Tests

### File Location

Place test files in the same package structure as the source:

```
isometric-core/src/test/kotlin/io/fabianterhorst/isometric/
```

For shape-specific tests:

```
isometric-core/src/test/kotlin/io/fabianterhorst/isometric/shapes/
```

### Naming Conventions

- Test classes: `{ClassName}Test.kt` (e.g., `PrismTest.kt`)
- Test methods: descriptive names using backticks or camelCase (e.g., `` `translate returns new instance with offset position` `` or `testTranslateReturnsNewInstance`)

### Example Test

```kotlin
package io.fabianterhorst.isometric.shapes

import io.fabianterhorst.isometric.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class PrismTest {

    @Test
    fun `default prism has unit dimensions`() {
        val prism = Prism()
        assertEquals(1.0, prism.width, 0.0)
        assertEquals(1.0, prism.depth, 0.0)
        assertEquals(1.0, prism.height, 0.0)
    }

    @Test
    fun `translate returns new prism with offset position`() {
        val prism = Prism(Point.ORIGIN)
        val moved = prism.translate(1.0, 2.0, 3.0)
        assertEquals(1.0, moved.position.x, 1e-10)
        assertEquals(2.0, moved.position.y, 1e-10)
        assertEquals(3.0, moved.position.z, 1e-10)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative width throws`() {
        Prism(width = -1.0)
    }
}
```

### Testing Guidelines

1. **Test behavior, not implementation.** Verify that `translate` returns correct coordinates, not that it calls a specific internal method.
2. **Test edge cases.** Zero dimensions, negative values, very large scales, `NaN` inputs.
3. **Test immutability.** Verify that transform methods return new instances and do not mutate the original.
4. **Use tolerance for floating-point comparisons.** Always use `assertEquals(expected, actual, tolerance)` with a tolerance like `1e-10`.
5. **Validate error messages.** When testing `require` / `IllegalArgumentException`, verify the message string contains the expected description.
