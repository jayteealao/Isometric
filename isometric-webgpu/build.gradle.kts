plugins {
    id("isometric.android.library")
    alias(libs.plugins.kotlin.compose)
}

group = "io.github.jayteealao"
version = "1.1.0-SNAPSHOT"

android {
    namespace = "io.github.jayteealao.isometric.webgpu"

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Access to GroupNode, RenderCommand, PreparedScene, SceneProjector, etc.
    api(project(":isometric-compose"))

    // Material types (IsometricMaterial, TextureSource) for GPU texture resolution
    implementation(project(":isometric-shader"))

    // Vendored WebGPU — pinned to alpha04
    implementation("androidx.webgpu:webgpu:1.0.0-alpha04")

    // Coroutines for suspend-based GPU readback
    implementation(libs.coroutines.android)

    // Compose for AndroidExternalSurface (Phase 2), but also needed for @Composable in RenderBackend
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
