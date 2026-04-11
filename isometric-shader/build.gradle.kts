plugins {
    id("isometric.android.library")
    alias(libs.plugins.dokka)
    id("isometric.publishing")
}

group = "io.github.jayteealao"
version = "1.2.0-SNAPSHOT"

android {
    namespace = "io.github.jayteealao.isometric.shader"

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
    api(project(":isometric-core"))
    implementation(libs.annotation)
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
