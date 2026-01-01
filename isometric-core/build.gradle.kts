plugins {
    kotlin("jvm") version "1.7.10"
}

group = "io.fabianterhorst"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Pure Kotlin/JVM - NO Android dependencies
    implementation(kotlin("stdlib"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.7.10")
}

kotlin {
    jvmToolchain(8)
}

tasks.test {
    useJUnit()
}
