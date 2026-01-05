plugins {
    kotlin("jvm")
}

group = "io.fabianterhorst"
version = "1.0.0"

dependencies {
    // Pure Kotlin/JVM - NO Android dependencies
    implementation(kotlin("stdlib"))

    // Compose runtime for stability annotations only (no Android dependency)
    compileOnly("androidx.compose.runtime:runtime:1.6.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.0")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.test {
    useJUnit()
}
