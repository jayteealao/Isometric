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

// Workaround for a Gradle 8.14 + kotlin-dsl + configuration-cache pathology on
// Windows: the precompiled-script-plugin generator emits Accessors*.kt files
// into content-hashed paths that change between runs. Gradle's CC then fails
// to MD5-hash the deleted old paths and aborts with errors like
// "Failed to create MD5 hash for file '.../Accessors<hash>.kt' as it does not
// exist" or "Cannot access output property 'sourceCodeOutputDir' of task
// ':build-logic:generatePrecompiledScriptPluginAccessors'".
//
// Marking the affected tasks as not-CC-compatible degrades them gracefully
// (re-runs from scratch instead of being cached) without touching the rest of
// the build. The big CC win on library/sample modules is preserved.
//
// Long-term fix: refactor build-logic from precompiled script plugins into
// real Plugin<Project> classes so the generator stops running entirely.
tasks.matching {
    it.name in setOf(
        "compilePluginsBlocks",
        "generatePrecompiledScriptPluginAccessors",
        "compileKotlin",
    )
}.configureEach {
    notCompatibleWithConfigurationCache(
        "kotlin-dsl + configuration-cache hash race on Windows; Gradle 8.14",
    )
}
