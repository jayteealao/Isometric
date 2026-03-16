# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Jetpack Compose Runtime API (`IsometricScene`) with custom `Applier` and node tree
- Hierarchical transforms via `Group` composable
- Per-node dirty tracking and prepared-scene caching
- Spatial-indexed hit testing
- Tap and drag gesture handling (`GestureConfig`)
- Camera pan/zoom (`CameraState`)
- Native Android Canvas rendering path
- `CustomNode` escape hatch for custom geometry
- `Batch` composable for efficient multi-shape rendering
- `If` and `ForEach` conditional rendering composables
- `AdvancedSceneConfig` for power-user configuration
- Benchmark harness with frame-level metrics
- Paparazzi snapshot tests
- Maestro E2E test flows

### Changed
- Migrated all source from Java to Kotlin
- Modularized into `isometric-core`, `isometric-compose`, `isometric-android-view`
- `Color` renamed to `IsoColor` to avoid Compose namespace collision
- Core module is now pure Kotlin/JVM (no Android dependency)
- `Point`, `Vector`, `Path`, `Shape` support Kotlin operator overloads

### Removed
- `IsometricCanvas` high-level API (superseded by `IsometricScene`)
