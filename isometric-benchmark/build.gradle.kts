plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "io.fabianterhorst.isometric.benchmark"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.fabianterhorst.isometric.benchmark"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
