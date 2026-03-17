---
title: Development Setup
description: Clone, build, and run the Isometric project
sidebar:
  order: 1
---

## Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| JDK | 17+ | Required for Kotlin compilation |
| Android SDK | API 34 | Install via Android Studio SDK Manager |
| Android Studio or IntelliJ IDEA | Latest stable | Android Studio is recommended for full Android toolchain support |
| Node.js | 18+ | Only needed if working on the docs site |

## Clone and Build

```bash
git clone https://github.com/jayteealao/Isometric.git
cd Isometric
./gradlew build
```

This compiles all modules and runs the unit test suite. On first run, Gradle will download dependencies, which may take a few minutes.

To build a specific module:

```bash
./gradlew :isometric-core:build
./gradlew :isometric-compose:build
```

## Run the Sample App

The `app` module contains a sample Android application that demonstrates various scenes:

```bash
./gradlew :app:installDebug
```

Or open the project in Android Studio and run the `app` configuration on an emulator or connected device.

## Project Structure

| Module | Description |
|--------|-------------|
| `isometric-core` | Pure Kotlin/JVM engine. Contains `IsometricEngine`, `SceneProjector`, shapes, colors, projection math, depth sorting. No Android or Compose dependencies. |
| `isometric-compose` | Jetpack Compose integration. Contains `IsometricScene`, composables (`Shape`, `Group`, `Batch`, etc.), `CompositionLocal` providers, gesture handling, and the custom Compose applier. |
| `isometric-android-view` | Legacy Android `View`-based renderer. Maintained for backward compatibility but new projects should use `isometric-compose`. |
| `isometric-benchmark` | Benchmark harness for measuring rendering performance. Uses `AdvancedSceneConfig` hooks to instrument frame timing. |
| `app` | Sample Android application with demo scenes. |
| `site` | Documentation site built with Astro + Starlight. |

## IDE Setup

### Android Studio / IntelliJ

1. Open the project root (`Isometric/`) as a Gradle project.
2. Wait for the Gradle sync to complete.
3. Set the Project SDK to JDK 17 under **File > Project Structure > Project**.
4. If prompted, install the Kotlin plugin matching version 1.9.10.

### Useful Run Configurations

- **Unit Tests**: Right-click `isometric-core/src/test` and select "Run Tests".
- **Sample App**: Select the `app` module run configuration and choose a device.
- **Specific Test**: Right-click any test class or method and select "Run".

### Gradle Tasks

| Task | Description |
|------|-------------|
| `./gradlew build` | Full build with tests |
| `./gradlew :isometric-core:test` | Run core unit tests |
| `./gradlew :isometric-compose:build` | Build Compose module |
| `./gradlew :app:installDebug` | Install sample app on device |
| `./gradlew clean` | Delete all build outputs |

## Docs Site

The documentation site lives in the `site/` directory and uses [Astro](https://astro.build/) with the [Starlight](https://starlight.astro.build/) theme.

```bash
cd site
npm install
npm run dev
```

This starts a local dev server at `http://localhost:4321`. Pages are MDX files in `site/src/content/docs/`. See the [Documentation Guide](/contributing/docs-guide) for details on writing docs.
