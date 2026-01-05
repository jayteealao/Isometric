plugins {
    id("com.android.library")
    kotlin("android")
    id("androidx.benchmark")
}

android {
    namespace = "io.fabianterhorst.isometric.microbenchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
    }

    testBuildType = "benchmark"

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Enable profiling for allocation tracking
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Core modules to benchmark
    implementation(project(":isometric-core"))
    implementation(project(":isometric-compose"))
    implementation(project(":benchmark-reporting"))

    // Jetpack Microbenchmark
    androidTestImplementation("androidx.benchmark:benchmark-junit4:1.2.3")

    // Jetpack Compose for UI benchmarks
    val composeVersion = "1.5.0"
    androidTestImplementation("androidx.compose.ui:ui:$composeVersion")
    androidTestImplementation("androidx.compose.runtime:runtime:$composeVersion")

    // Testing infrastructure
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
}
