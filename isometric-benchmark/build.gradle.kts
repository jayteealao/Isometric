plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.github.jayteealao.isometric.benchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.jayteealao.isometric.benchmark"
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
    // Isometric modules under test
    implementation(project(":isometric-core"))
    implementation(project(":isometric-compose"))

    // Android
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.activity:activity-ktx:1.6.1")

    // Compose
    val composeVersion = "1.5.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.runtime:runtime:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.activity:activity-compose:1.6.1")

    // Coroutines (for FramePacer / orchestrator)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON serialization for config/results
    implementation("org.json:json:20231013")

    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.1.3")
}
