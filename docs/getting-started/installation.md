---
title: Installation
description: How to add Isometric to your Android project
sidebar:
  order: 1
---

## Current Status

Isometric is **not yet published to Maven Central**. For now, the recommended approach is to use a composite build, which lets you depend on the library directly from source.

## Composite Build Setup (Recommended)

Clone or copy the Isometric repository alongside your project, then add the following to your `settings.gradle.kts`:

```kotlin
// settings.gradle.kts
includeBuild("path/to/Isometric") {
    dependencySubstitution {
        substitute(module("io.github.jayteealao:isometric-core")).using(project(":isometric-core"))
        substitute(module("io.github.jayteealao:isometric-compose")).using(project(":isometric-compose"))
    }
}
```

Then add the dependency in your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.jayteealao:isometric-compose:<version>")
}
```

Gradle's dependency substitution will resolve these coordinates against the included build automatically.

## Future: Maven Central

Once the library is published, you will be able to add it directly without a composite build.

<Tabs>
<TabItem label="build.gradle.kts">
```kotlin
dependencies {
    implementation("io.github.jayteealao:isometric-compose:<version>")
}
```
</TabItem>
<TabItem label="Version Catalog">
```toml
# gradle/libs.versions.toml
[versions]
isometric = "<version>"

[libraries]
isometric-core = { module = "io.github.jayteealao:isometric-core", version.ref = "isometric" }
isometric-compose = { module = "io.github.jayteealao:isometric-compose", version.ref = "isometric" }
```

Then in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation(libs.isometric.compose)
}
```
</TabItem>
</Tabs>

## Requirements

| Requirement       | Minimum Version |
| ----------------- | --------------- |
| Android minSdk    | 24              |
| Kotlin            | 1.9+            |
| Jetpack Compose   | 1.5+            |
| JVM target        | 11              |

## Modules

Isometric is split into focused modules so you can depend on only what you need.

| Module                  | Description                                                                 |
| ----------------------- | --------------------------------------------------------------------------- |
| `isometric-core`        | Pure Kotlin/JVM engine. Contains the math, geometry, and projection logic.  |
| `isometric-compose`     | Jetpack Compose integration. Provides `IsometricScene` and shape composables. |
| `isometric-android-view`| Legacy Android View support for non-Compose projects.                       |

Most projects should depend on **isometric-compose**, which transitively includes isometric-core.
