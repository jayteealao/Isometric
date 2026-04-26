plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    id("isometric.publishing")
}

group = "io.github.jayteealao"
version = "1.2.0-alpha.01"

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnit()
}

mavenPublishing {
    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-core",
        version = version.toString()
    )

    pom {
        name.set("Isometric Core")
        description.set("Platform-agnostic isometric rendering engine for Kotlin")

        // Upstream attribution — this module is the fork of the original Java library
        developers {
            developer {
                id.set("fabianterhorst")
                name.set("Fabian Terhorst")
                url.set("https://github.com/FabianTerhorst")
            }
        }
    }
}

dependencies {
    // Pure Kotlin/JVM - NO Android dependencies
    implementation(libs.kotlin.stdlib)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}
