# Isometric for Jetpack Compose

## Installation

Add the Compose module to your `build.gradle`:

```kotlin
dependencies {
    implementation("io.fabianterhorst:isometric-compose:1.0.0")
}
```

## Quick Start

### Drawing a simple cube with Compose

```kotlin
@Composable
fun SimpleCubeScene() {
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        add(
            shape = Prism(Point(0.0, 0.0, 0.0)),
            color = IsoColor(33.0, 150.0, 243.0) // Blue
        )
    }
}
```

### Drawing multiple shapes

```kotlin
@Composable
fun MultipleShapesScene() {
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
    ) {
        add(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(-1.0, 1.0, 0.0), 1.0, 2.0, 1.0), IsoColor(33.0, 150.0, 243.0))
        add(Prism(Point(1.0, -1.0, 0.0), 2.0, 1.0, 1.0), IsoColor(33.0, 150.0, 243.0))
    }
}
```

### Interactive scene with click handling

```kotlin
@Composable
fun InteractiveScene() {
    val sceneState = rememberIsometricSceneState()
    var selectedItem by remember { mutableStateOf<String?>(null) }

    Column {
        IsometricCanvas(
            state = sceneState,
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp),
            onItemClick = { item ->
                selectedItem = "Clicked: ${item.id}"
            }
        ) {
            add(Prism(Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
            add(Pyramid(Point(2.0, 0.0, 0.0)), IsoColor(255.0, 100.0, 0.0))
        }

        selectedItem?.let {
            Text(it, modifier = Modifier.padding(16.dp))
        }
    }
}
```

### Dynamic scene with state

```kotlin
@Composable
fun DynamicScene() {
    val sceneState = rememberIsometricSceneState()
    var cubeCount by remember { mutableIntStateOf(1) }

    // Rebuild scene when count changes
    LaunchedEffect(cubeCount) {
        sceneState.clear()
        repeat(cubeCount) { i ->
            sceneState.add(
                Prism(Point(i.toDouble(), 0.0, 0.0)),
                IsoColor(
                    r = (33 + i * 30).toDouble().coerceIn(0.0, 255.0),
                    g = 150.0,
                    b = 243.0
                )
            )
        }
    }

    Column {
        IsometricCanvas(
            state = sceneState,
            modifier = Modifier
                .fillMaxWidth()
                .height(400.dp)
        )

        Row {
            Button(onClick = { cubeCount++ }) { Text("+") }
            Text("Cubes: $cubeCount", modifier = Modifier.padding(16.dp))
            Button(onClick = { cubeCount = maxOf(1, cubeCount - 1) }) { Text("-") }
        }
    }
}
```

### Using transforms

```kotlin
@Composable
fun TransformScene() {
    var rotation by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16) // ~60fps
            rotation += 0.02
        }
    }

    val sceneState = rememberIsometricSceneState()

    // Rebuild scene with rotation
    LaunchedEffect(rotation) {
        sceneState.clear()
        val cube = Prism(Point.ORIGIN, 3.0, 3.0, 1.0)

        // Bottom cube (static)
        sceneState.add(cube, IsoColor(160.0, 60.0, 50.0))

        // Top cube (rotating)
        sceneState.add(
            cube.rotateZ(Point(1.5, 1.5, 0.0), rotation)
                .translate(0.0, 0.0, 1.1),
            IsoColor(50.0, 60.0, 160.0)
        )
    }

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier
            .fillMaxWidth()
            .height(400.dp)
    )
}
```

### Performance options

```kotlin
@Composable
fun PerformanceScene() {
    val sceneState = rememberIsometricSceneState()

    IsometricCanvas(
        state = sceneState,
        modifier = Modifier.fillMaxSize(),
        renderOptions = RenderOptions.Performance // Disable depth sorting for speed
    ) {
        // Add hundreds of shapes without sorting overhead
        repeat(50) { x ->
            repeat(50) { y ->
                add(
                    Prism(Point(x.toDouble(), y.toDouble(), 0.0), 0.5, 0.5, 0.5),
                    IsoColor(x * 5.0, y * 5.0, 100.0)
                )
            }
        }
    }
}
```

## Available Shapes

All shapes from the original library are available:

- `Prism(origin, width, length, height)` - Rectangular box
- `Pyramid(origin, width, length, height)` - Pyramid
- `Cylinder(origin, radius, vertices, height)` - Cylindrical shape
- `Octahedron(origin)` - 8-faced polyhedron
- `Stairs(origin, stepCount)` - Staircase
- `Knot(origin)` - Complex knot structure

## Configuration Options

### RenderOptions

```kotlin
data class RenderOptions(
    val enableDepthSorting: Boolean = true,       // Complex but correct depth ordering
    val enableBackfaceCulling: Boolean = true,    // Remove invisible faces
    val enableBoundsChecking: Boolean = true      // Skip offscreen shapes
)

// Presets:
RenderOptions.Default     // All features enabled
RenderOptions.Performance // Depth sorting disabled for speed
RenderOptions.Quality     // All features enabled, no culling
```

### IsometricCanvas Parameters

```kotlin
@Composable
fun IsometricCanvas(
    state: IsometricSceneState = rememberIsometricSceneState(),
    modifier: Modifier = Modifier,
    renderOptions: RenderOptions = RenderOptions.Default,
    strokeWidth: Float = 1f,
    drawStroke: Boolean = true,
    onItemClick: (RenderCommand) -> Unit = {},
    content: IsometricScope.() -> Unit
)
```

## Color Utilities

Convert between Compose Color and IsoColor:

```kotlin
import io.fabianterhorst.isometric.compose.ComposeRenderer.toIsoColor
import io.fabianterhorst.isometric.compose.ComposeRenderer.toComposeColor

val composeColor = Color.Blue
val isoColor = composeColor.toIsoColor()

val backToCompose = isoColor.toComposeColor()
```

## Architecture

The library is now modularized:

- **:isometric-core** - Pure Kotlin/JVM, platform-agnostic engine
- **:isometric-compose** - Jetpack Compose UI integration
- **:isometric-android-view** - Legacy Android View support (backward compatible)

This architecture enables:
- ✅ Clean separation of rendering logic
- ✅ Proper Compose state management
- ✅ Future Kotlin Multiplatform support
- ✅ Backward compatibility with existing View-based code

## Migration from View API

See [MIGRATION.md](MIGRATION.md) for detailed migration instructions.

## License

Apache License 2.0 - See LICENSE file for details
