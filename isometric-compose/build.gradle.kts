plugins {
    id("isometric.android.library")
    alias(libs.plugins.dokka)
    alias(libs.plugins.paparazzi)
    alias(libs.plugins.ktlint)
    id("isometric.publishing")
}

group = "io.github.jayteealao"
version = "1.2.0-alpha.03-SNAPSHOT"

android {
    namespace = "io.github.jayteealao.isometric.compose"

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel2Api30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.jayteealao",
        artifactId = "isometric-compose",
        version = version.toString()
    )

    pom {
        name.set("Isometric Compose")
        description.set("Jetpack Compose integration for the Isometric rendering engine")
    }
}

dependencies {
    // api (not implementation) because Shape, Point, IsoColor, Vector etc. appear in
    // composable signatures — consumers need direct access to core types.
    // Internal collaborators (SceneGraph, IsometricProjection, DepthSorter, HitTester)
    // are marked internal and don't leak through api.
    api(project(":isometric-core"))

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)

    // Testing
    testImplementation(libs.truth)
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
