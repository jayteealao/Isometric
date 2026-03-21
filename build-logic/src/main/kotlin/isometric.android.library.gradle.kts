import com.android.build.gradle.LibraryExtension

/**
 * Convention plugin for all Isometric Android library modules.
 *
 * Applies: com.android.library + kotlin.android
 * Sets: compileSdk, minSdk, targetSdk, Java 11 compile options, common test deps.
 *
 * Each consuming module still declares its own:
 *   - android.namespace
 *   - buildFeatures / composeOptions (compose module only)
 *   - module-specific dependencies
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

extensions.configure<LibraryExtension> {
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

// Version catalog type-safe accessors are not available in precompiled script plugins;
// read the catalog via the extension API instead.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
    "androidTestImplementation"(libs.findLibrary("androidx.test.ext.junit").get())
    "androidTestImplementation"(libs.findLibrary("espresso.core").get())
}
