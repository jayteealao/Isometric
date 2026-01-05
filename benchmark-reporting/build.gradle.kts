plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.0"
}

group = "io.fabianterhorst"
version = "1.0.0"

dependencies {
    implementation(kotlin("stdlib"))

    // Core module for scene generation
    implementation(project(":isometric-core"))

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // CSV generation
    implementation("com.opencsv:opencsv:5.8")

    // Markdown generation (optional, if needed)
    // implementation("com.vladsch.flexmark:flexmark-all:0.64.8")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}
