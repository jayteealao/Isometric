plugins {
    id("com.android.test")
    kotlin("android")
    id("androidx.baselineprofile")
}

android {
    namespace = "io.fabianterhorst.isometric.macrobenchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 28  // Macrobenchmark requires API 28+
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":benchmarkapp"

    // Enable experimental options for better measurement
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    // Shared reporting
    implementation(project(":benchmark-reporting"))

    // Jetpack Macrobenchmark
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.3")

    // Testing infrastructure
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test:rules:1.5.0")
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("junit:junit:4.13.2")

    // For JankStats integration
    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")
}

baselineProfile {
    // Configuration for baseline profile generation
    useConnectedDevices = false
}
