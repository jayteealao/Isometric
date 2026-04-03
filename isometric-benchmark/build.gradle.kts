plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jayteealao.isometric.benchmark"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jayteealao.isometric.benchmark"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Isometric modules under test
    implementation(project(":isometric-core"))
    implementation(project(":isometric-compose"))
    implementation(project(":isometric-webgpu"))

    // Android
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.material)
    implementation(libs.activity.compose)

    // Coroutines (for FramePacer / orchestrator)
    implementation(libs.coroutines.android)

    // JSON serialization for config/results
    implementation("org.json:json:20231013")

    debugImplementation(libs.compose.ui.tooling)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
