plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.fabianterhorst.isometric.benchmarkapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.fabianterhorst.isometric.benchmarkapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Enable for baseline profile generation
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.0"
    }
}

dependencies {
    implementation(project(":isometric-compose"))
    implementation(project(":isometric-core"))
    implementation(project(":benchmark-reporting"))

    // Compose
    val composeVersion = "1.5.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.activity:activity-compose:1.8.0")

    // For scriptable benchmark scenarios
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // JankStats for frame metrics
    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Serialization for scenario configs
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
