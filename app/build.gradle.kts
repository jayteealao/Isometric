plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.fabianterhorst.isometric.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.fabianterhorst.isometric.sample"
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
        kotlinCompilerExtensionVersion = "1.5.3"  // Compatible with Kotlin 1.9.0
    }
}

dependencies {
    // New modularized dependencies
    implementation(project(":isometric-android-view"))  // For View-based samples
    implementation(project(":isometric-compose"))       // For Compose samples

    // Android
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.activity:activity-ktx:1.6.1")

    // Compose (aligned with isometric-compose module)
    val composeVersion = "1.5.0"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.material:material:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.activity:activity-compose:1.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")
}
