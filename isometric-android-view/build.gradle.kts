plugins {
    id("isometric.android.library")
    id("isometric.publishing")
}

group = "io.github.jayteealao"
version = "1.2.0-alpha.01"

android {
    namespace = "io.github.jayteealao.isometric.view"
}

mavenPublishing {
    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-android-view",
        version = version.toString()
    )

    pom {
        name.set("Isometric Android View")
        description.set("Android View-based adapter for the Isometric rendering engine")
    }
}

dependencies {
    api(project(":isometric-core"))
    implementation(libs.annotation)
}
