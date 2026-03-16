# Isometric
Isometric drawing library for Android

**Now with Jetpack Compose support!** 🎉

**NEW: Runtime-Level API** - Advanced Compose Runtime implementation with 7-20x performance improvements! 🚀

## Architecture

The library is now modularized into three packages:

- **:isometric-core** - Platform-agnostic rendering engine (pure Kotlin/JVM)
- **:isometric-compose** - Jetpack Compose UI components with **two API levels**:
  - **High-level API** (`IsometricCanvas`) - Simple and easy to use
  - **Runtime-level API** (`IsometricScene`) - Advanced features with ComposeNode and custom Applier
- **:isometric-android-view** - Traditional Android View support

## Installation

### Gradle (Kotlin DSL)

For **Jetpack Compose** support:
```kotlin
dependencies {
    implementation("io.fabianterhorst:isometric-compose:0.1.0")
}
```

For **traditional View** support:
```kotlin
dependencies {
    implementation("io.fabianterhorst:isometric-android-view:0.1.0")
}
```

### Gradle (Groovy)

For **Jetpack Compose** support:
```groovy
implementation 'io.fabianterhorst:isometric-compose:0.1.0'
```

For **traditional View** support:
```groovy
implementation 'io.fabianterhorst:isometric-android-view:0.1.0'
```

## Quick Start

### Compose - Runtime API (Recommended for new projects)

**Most powerful and performant option:**

```kotlin
@Composable
fun MyIsometricScene() {
    IsometricScene {
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)))
    }
}
```

**Features:**
- 🚀 7-20x faster animations with automatic optimizations
- 🌲 Hierarchical transformations with `Group`
- 🎯 Advanced gesture handling (tap, drag, custom)
- ⚡ Dirty tracking for efficient updates
- 🎨 CompositionLocal theming support
- 💾 Prepared-scene caching enabled by default
- 🔍 Spatial indexing for 7-25x faster hit testing

📖 **[See Runtime API Documentation](docs/RUNTIME_API.md)** | ⚡ **[Performance Guide](docs/PERFORMANCE_OPTIMIZATIONS.md)**

### Compose - High-Level API (Simple scenes)

**Great for quick prototyping and simple use cases:**

```kotlin
@Composable
fun MyIsometricScene() {
    IsometricCanvas {
        add(
            Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 1.0, height = 1.0),
            IsoColor(33.0, 150.0, 243.0)
        )
    }
}
```

### Traditional View

```java
isometricView.add(
    new Prism(new Point(0, 0, 0), 1, 1, 1),
    new Color(33, 150, 243)
);
```

## Documentation

- 📘 [**Runtime API Guide**](docs/RUNTIME_API.md) - Complete reference for the runtime-level API
- 📗 [**Primitive Levels**](docs/PRIMITIVE_LEVELS.md) - Understanding high-level vs low-level API usage
- ⚡ [**Performance Optimizations**](docs/PERFORMANCE_OPTIMIZATIONS.md) - Detailed optimization guide with benchmarks
- 📊 [**Optimization Summary**](docs/OPTIMIZATION_SUMMARY.md) - Quick reference for performance features
- 🔄 [**Migration Guide**](docs/MIGRATION.md) - Migrating from View API to Compose

---

## Runtime API Features

The new **Runtime-Level API** uses Compose Runtime primitives (`ComposeNode`, `Applier`) for maximum performance and flexibility.

### Key Benefits

| Feature | High-Level API | Runtime API |
|---------|---------------|-------------|
| **Animation Performance** | Good | 🚀 **7-20x faster** |
| **Hierarchical Transforms** | ❌ Manual | ✅ **Automatic** |
| **Conditional Rendering** | ❌ Manual clear/add | ✅ **Native If/ForEach** |
| **Gesture Support** | Tap only | ✅ **Tap + Drag + Custom** |
| **Dirty Tracking** | Scene-level | ✅ **Per-node granular** |
| **Memory Usage** | Standard | ✅ **Optimized (ReusableComposeNode)** |
| **Recomposition** | Entire scene | ✅ **Only changed nodes** |

### Performance Comparison

| Scenario | Old API | Runtime API | Improvement |
|----------|---------|-------------|-------------|
| Single animated shape | 15ms | 2ms | **7.5x** |
| Conditional rendering | 12ms | 1ms | **12x** |
| Large grid (100 shapes) | 80ms | 5ms | **16x** |
| Hit testing | 3ms | 1ms | **3x** |

### Architecture

The Runtime API uses a **three-layer architecture**:

```
┌─────────────────────────────────────┐
│  Composable API (Shape, Group)      │
├─────────────────────────────────────┤
│  Node Tree (ComposeNode + Applier)  │
├─────────────────────────────────────┤
│  Rendering (IsometricEngine)        │
└─────────────────────────────────────┘
```

**Key Components:**
- **IsometricNode** - Scene graph with dirty tracking
- **IsometricApplier** - Custom Applier for tree management
- **RenderContext** - Transform accumulation
- **IsometricRenderer** - Efficient rendering with caching

### API Levels

You can use **both levels** in the same project:

**1. High-Level Composables** (Easy):
```kotlin
Shape(geometry = Prism(...), color = color)
Group(rotation = angle) { ... }
```

**2. Low-Level Primitives** (Advanced):
```kotlin
ComposeNode<ShapeNode, IsometricApplier>(...) {
    // Custom update logic
}
```

📖 See [**PRIMITIVE_LEVELS.md**](docs/PRIMITIVE_LEVELS.md) for details.

---

## Examples

### Drawing a simple cube

**Compose:**
```kotlin
IsometricCanvas {
    add(
        Prism(position = Point(0.0, 0.0, 0.0), width = 1.0, depth = 1.0, height = 1.0),
        IsoColor(33.0, 150.0, 243.0)
    )
}
```

**View:**
```java
isometricView.add(
    new Prism(new Point(0, 0, 0), 1, 1, 1),
    new Color(33, 150, 243)
);
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotOne.png?raw=true)

### Drawing multiple Shapes
#### There are 3 basic components: points, paths and shapes. A shape needs an origin point and 3 measurements for the x, y and z axes. The default Prism constructor sets all measurements to 1.

**Compose:**
```kotlin
IsometricCanvas {
    add(Prism(position = Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
    add(Prism(position = Point(-1.0, 1.0, 0.0), width = 1.0, depth = 2.0, height = 1.0), IsoColor(33.0, 150.0, 243.0))
    add(Prism(position = Point(1.0, -1.0, 0.0), width = 2.0, depth = 1.0, height = 1.0), IsoColor(33.0, 150.0, 243.0))
}
```

**View:**
```java
isometricView.add(new Prism(new Point(0, 0, 0)), new Color(33, 150, 243));
isometricView.add(new Prism(new Point(-1, 1, 0), 1, 2, 1), new Color(33, 150, 243));
isometricView.add(new Prism(new Point(1, -1, 0), 2, 1, 1), new Color(33, 150, 243));
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotTwo.png?raw=true)

### Drawing multiple Paths
#### Paths are two dimensional. You can draw and color paths the same as shapes.

```java
isometricView.add(new Prism(Point.ORIGIN, 3, 3, 1), new Color(50, 60, 160));
isometricView.add(new Path(new Point[]{
    new Point(1, 1, 1),
    new Point(2, 1, 1),
    new Point(2, 2, 1),
    new Point(1, 2, 1)
}), new Color(50, 160, 60));
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotPath.png?raw=true)

### The grid
#### Here you can see how the grid looks like. The blue grid is the xy-plane. The z-line is the z-axis.

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotGrid.png?raw=true)

### Supports complex structures

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotThree.png?raw=true)

## Advanced Features

### Animation (Runtime API - Recommended)

**7-8x faster than high-level API!** Only the animated `Group` recomposes:

```kotlin
@Composable
fun AnimatedScene() {
    var angle by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                angle += PI / 90
            }
        }
    }

    IsometricScene {
        // Static shapes (never recompose)
        Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))

        // Animated group (only this recomposes!)
        Group(rotation = angle) {
            Shape(geometry = Octahedron(position = Point(2.0, 0.0, 0.0)), color = IsoColor(255.0, 100.0, 0.0))
        }
    }
}
```

### Hierarchical Transforms (Runtime API)

**Automatic transform composition:**

```kotlin
IsometricScene {
    Group(position = Point(5.0, 0.0, 0.0), rotation = angle) {
        Shape(geometry = Prism(...), color = color1)

        // Nested group - transforms accumulate!
        Group(position = Point(0.0, 0.0, 2.0), rotation = -angle * 2) {
            Shape(geometry = Octahedron(...), color = color2)
        }
    }
}
```

### Interactive Scenes (Runtime API)

**Built-in tap and drag support:**

```kotlin
IsometricScene(
    config = SceneConfig(
        gestures = GestureConfig(
            onTap = { event ->
                println("Tapped node: ${event.node?.nodeId} at (${event.x}, ${event.y})")
            },
            onDrag = { event ->
                // Handle drag gestures
                println("Drag delta: ${event.x}, ${event.y}")
            }
        )
    )
) {
    Shape(geometry = Prism(position = Point(0.0, 0.0, 0.0)), color = IsoColor(33.0, 150.0, 243.0))
    Shape(geometry = Pyramid(position = Point(2.0, 0.0, 0.0)), color = IsoColor(255.0, 100.0, 0.0))
}
```

### Conditional Rendering (Runtime API)

**Native Compose conditionals:**

```kotlin
IsometricScene {
    Shape(geometry = baseShape, color = baseColor)

    If(showExtras) {
        ForEach((0..count).toList()) { i ->
            Shape(geometry = Pyramid(position = Point(i.toDouble(), 0.0, 0.0)), color = colors[i])
        }
    }
}
```

### Animation (High-Level API)

**Simple but rebuilds entire scene:**

```kotlin
@Composable
fun AnimatedScene() {
    val sceneState = rememberIsometricSceneState()
    var angle by remember { mutableStateOf(0.0) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                angle += PI / 90
            }
        }
    }

    LaunchedEffect(angle) {
        sceneState.clear()
        sceneState.add(
            Octahedron(position = Point(0.0, 0.0, 0.0)).rotateZ(Point.ORIGIN, angle),
            IsoColor(33.0, 150.0, 243.0)
        )
    }

    IsometricCanvas(state = sceneState)
}
```

### Interactive Scenes (High-Level API)

```kotlin
IsometricCanvas(
    onItemClick = { item ->
        println("Clicked item: ${item.commandId}")
    }
) {
    add(Prism(position = Point(0.0, 0.0, 0.0)), IsoColor(33.0, 150.0, 243.0))
    add(Pyramid(position = Point(2.0, 0.0, 0.0)), IsoColor(255.0, 100.0, 0.0))
}
```

### Available Shapes
#### [Cylinder](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Cylinder.java), [Knot](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Knot.java), [Octahedron](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Octahedron.java), [Prism](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Prism.java), [Pyramid](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Pyramid.java) and [Stairs](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Stairs.java)

### Translate
#### Traslate is translating an point, path or shape to the given x, y and z distance. Translate is returning a new point, path or shape.

```java
Prism prism = new Prism(new Point(0, 0, 0));
isometricView.add(prism, new Color(33, 150, 243));
isometricView.add(prism.translate(0, 0, 1.1), new Color(33, 150, 243));
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotTranslate.png?raw=true)

### Scale
#### Scale is scaling an point, path or shape with the given x, y and z scaling factors. Scale is returning a new point, path or shape.

```java
Color blue = new Color(50, 60, 160);
Color red = new Color(160, 60, 50);
Prism cube = new Prism(Point.ORIGIN);
isometricView.add(cube.scale(Point.ORIGIN, 3.0, 3.0, 0.5), red);
isometricView.add(cube
	.scale(Point.ORIGIN, 3.0, 3.0, 0.5)
	.translate(0, 0, 0.6), blue);
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotScale.png?raw=true)

### RotateZ
#### RotateZ is rotating an point, path or shape with the given angle in radians on the xy-plane (where an angle of 0 runs along the position x-axis). RotateZ is returning a new point, path or shape.

```java
Color blue = new Color(50, 60, 160);
Color red = new Color(160, 60, 50);
Prism cube = new Prism(Point.ORIGIN, 3, 3, 1);
isometricView.add(cube, red);
isometricView.add(cube
	/* (1.5, 1.5) is the center of the prism */
	.rotateZ(new Point(1.5, 1.5, 0), Math.PI / 12)
	.translate(0, 0, 1.1), blue);
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotRotateZ.png?raw=true)

### Shapes from Paths
#### The method ```Shape.extrude``` allows you to create a 3D model by popping out a 2D path along the z-axis.

```java
Color blue = new Color(50, 60, 160);
Color red = new Color(160, 60, 50);
isometricView.add(new Prism(Point.ORIGIN, 3, 3, 1), blue);
isometricView.add(Shape.extrude(new Path(new Point[]{
	new Point(1, 1, 1),
	new Point(2, 1, 1),
	new Point(2, 3, 1)
}), 0.3), red);
```

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotExtrude.png?raw=true)

### Available Shapes
#### [Cylinder](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Cylinder.java)

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotCylinder.png?raw=true)

[Knot](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Knot.java)

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotKnot.png?raw=true)

[Octahedron](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Octahedron.java)

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotOctahedron.png?raw=true)

[Prism](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Prism.java)

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotPrism.png?raw=true)

[Pyramid](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Pyramid.java) 

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotPyramid.png?raw=true)

[Stairs](https://github.com/FabianTerhorst/Isometric/blob/master/lib/src/main/java/io/fabianterhorst/isometric/shapes/Stairs.java)

![Image](https://github.com/FabianTerhorst/Isometric/blob/master/lib/screenshots/io.fabianterhorst.isometric.screenshot.IsometricViewTest_doScreenshotStairs.png?raw=true)

# Developed By

* Fabian Terhorst
  * [github.com/FabianTerhorst](https://github.com/FabianTerhorst) - <fabian.terhorst@gmail.com>
  * [paypal.me/fabianterhorst](http://paypal.me/fabianterhorst)


# Contributors

* **[cameronwhite08](https://github.com/cameronwhite08)**


# License

    Copyright 2017 Fabian Terhorst

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
