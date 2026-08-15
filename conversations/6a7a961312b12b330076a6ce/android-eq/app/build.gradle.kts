plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.neon.eq"
    compileSdk = 34

    // CI (GitHub Actions) runs on a fresh VM every time, so without a fixed keystore
    // AGP auto-generates a brand-new random debug key each build. That means every
    // "new" APK has a different signature than the last install, and Android's
    // package installer silently refuses to update — you end up stuck on an old,
    // stale build without any clear error. Pinning a checked-in keystore fixes that
    // for good: every future build is signed identically, so installs always update
    // cleanly (no more "uninstall the old app first").
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore.p12")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    defaultConfig {
        applicationId = "com.neon.eq"
        minSdk = 21
        targetSdk = 34
        // Tie versionCode/versionName to the CI run so it's easy to confirm on-device
        // (Settings > Apps > Neon EQ > version) that you're actually running the
        // build you think you're running.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    kotlinOptions {
        jvmTarget = "17"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
