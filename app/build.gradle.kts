plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jayteealao.isometric.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.jayteealao.isometric.sample"
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
    // New modularized dependencies
    implementation(project(":isometric-android-view"))  // For View-based samples
    implementation(project(":isometric-compose"))       // For Compose samples
    implementation(project(":isometric-shader"))        // For texture/material samples
    implementation(project(":isometric-webgpu"))        // For WebGPU compute backend samples

    // Android
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)

    // Compose
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)

    debugImplementation(libs.compose.ui.tooling)
}
