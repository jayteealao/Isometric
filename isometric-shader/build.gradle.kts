plugins {
    id("isometric.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dokka)
    alias(libs.plugins.paparazzi)
    id("isometric.publishing")
}

group = "io.github.jayteealao"
version = "1.2.0-SNAPSHOT"

android {
    namespace = "io.github.jayteealao.isometric.shader"

    buildFeatures {
        compose = true
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-shader",
        version = version.toString()
    )

    pom {
        name.set("Isometric Shader")
        description.set("Material and texture type system for the Isometric rendering engine")
    }
}

dependencies {
    // isometric-core is available transitively via isometric-compose
    api(project(":isometric-compose"))
    implementation(libs.annotation)
    implementation(libs.kotlin.stdlib)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
