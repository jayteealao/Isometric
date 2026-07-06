plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.jayteealao.isometric.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.jayteealao.isometric.sample"
        minSdk = 24
        targetSdk = 33
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"  // Compatible with Kotlin 1.9.22
    }
}

dependencies {
    // New modularized dependencies
    implementation(project(":isometric-android-view"))  // For View-based samples
    implementation(project(":isometric-compose"))       // For Compose samples

    // Android
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.activity:activity-ktx:1.6.1")

    // Compose (pulled from version catalog — see gradle/libs.versions.toml)
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)

    debugImplementation(libs.compose.ui.tooling)
}
