plugins {
    kotlin("jvm")
}

group = "io.fabianterhorst"
version = "1.0.0"

dependencies {
    // Pure Kotlin/JVM - NO Android dependencies
    implementation(kotlin("stdlib"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.0")
}

kotlin {
    jvmToolchain {
        // Use Java 11 to match Android library requirements (Java 8 target)
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

tasks.test {
    useJUnit()
}
