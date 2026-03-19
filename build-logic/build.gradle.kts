plugins {
    `kotlin-dsl`
}

group = "io.github.jayteealao.isometric.buildlogic"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // implementation (not compileOnly) so the plugin IDs are registered in build-logic's
    // plugin registry — required for precompiled script plugins that apply these plugins
    // via id("com.android.library") etc.
    implementation(libs.agp.gradle)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.vanniktech.publish.gradle.plugin)
}
