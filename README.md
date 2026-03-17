# Isometric

Declarative isometric rendering for Jetpack Compose.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple.svg)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-24%2B-green.svg)](https://developer.android.com/about/versions/nougat)

![Isometric scene with prisms, stairs, pyramids, and octahedron](docs/assets/screenshots/complex-scene.png)

## What is Isometric?

Isometric is a Kotlin library for rendering interactive isometric (2.5D) scenes in Jetpack Compose. Build scenes declaratively with `Shape`, `Group`, and `Path` composables. Transforms accumulate through the hierarchy, animations recompose only changed nodes, and built-in gesture handling supports tap and drag interactions with spatial hit testing.

## Features

- **Declarative scene graph** — `Shape`, `Group`, `Path`, `Batch`, `If`, `ForEach` composables
- **Hierarchical transforms** — position, rotation, and scale accumulate through groups
- **Per-node dirty tracking** — only changed subtrees re-render
- **Built-in animation** — vsync-aligned via `withFrameNanos`
- **Gesture handling** — tap and drag with spatial-indexed hit testing
- **Camera control** — pan and zoom with `CameraState`
- **6 built-in shapes** — Prism, Pyramid, Cylinder, Octahedron, Stairs, Knot
- **Custom shapes** — extrude paths or implement `CustomNode` for full control

## Quick Start

### Installation

> **Note:** This library is not yet published to Maven Central. To use it now, clone the repository and include the modules as local dependencies.

```kotlin
// settings.gradle.kts — composite build
includeBuild("path/to/Isometric") {
    dependencySubstitution {
        substitute(module("io.github.jayteealao:isometric-core")).using(project(":isometric-core"))
        substitute(module("io.github.jayteealao:isometric-compose")).using(project(":isometric-compose"))
    }
}
```

Once published to Maven Central:

```kotlin
dependencies {
    implementation("io.github.jayteealao:isometric-compose:<version>")
}
```

### Your First Scene

```kotlin
@Composable
fun MyIsometricScene() {
    IsometricScene {
        Shape(
            geometry = Prism(position = Point(0.0, 0.0, 0.0)),
            color = IsoColor(33, 150, 243)
        )
    }
}
```

See the [Quick Start guide](https://jayteealao.github.io/Isometric/getting-started/quickstart/) for a complete walkthrough.

## Documentation

**[Full documentation site](https://jayteealao.github.io/Isometric/)**

- [**Quick Start**](https://jayteealao.github.io/Isometric/getting-started/quickstart/) — Build your first scene in 5 minutes
- [**Shapes Guide**](https://jayteealao.github.io/Isometric/guides/shapes/) — Built-in shapes, transforms, and custom geometry
- [**Performance**](https://jayteealao.github.io/Isometric/guides/performance/) — Caching, native canvas, spatial indexing
- [**Scene Graph**](https://jayteealao.github.io/Isometric/concepts/scene-graph/) — Architecture, node types, and dirty tracking
- [**Migration Guide**](https://jayteealao.github.io/Isometric/migration/view-to-compose/) — Migrating from the View API to Compose
- [**API Reference**](https://jayteealao.github.io/Isometric/api/) — Dokka-generated API docs

## Requirements

| Requirement | Version |
|-------------|---------|
| Android min SDK | 24 |
| Kotlin | 1.9+ |
| Jetpack Compose | 1.5+ |
| JVM target | 11 |

## Modules

| Module | Description |
|--------|-------------|
| `isometric-core` | Platform-agnostic rendering engine (pure Kotlin/JVM) |
| `isometric-compose` | Jetpack Compose integration (`IsometricScene`) |
| `isometric-android-view` | Traditional Android View support (`IsometricView`) |

## Available Shapes

| Prism | Pyramid | Cylinder | Octahedron | Stairs | Knot |
|:-----:|:-------:|:--------:|:----------:|:------:|:----:|
| ![Prism](docs/assets/screenshots/shape-prism.png) | ![Pyramid](docs/assets/screenshots/shape-pyramid.png) | ![Cylinder](docs/assets/screenshots/shape-cylinder.png) | ![Octahedron](docs/assets/screenshots/shape-octahedron.png) | ![Stairs](docs/assets/screenshots/shape-stairs.png) | ![Knot](docs/assets/screenshots/shape-knot.png) |

## Credits

Originally created by [Fabian Terhorst](https://github.com/FabianTerhorst). Rewritten in Kotlin with Compose Runtime API by [jayteealao](https://github.com/jayteealao).

## License

[Apache License 2.0](LICENSE)
