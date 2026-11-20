plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.prelina.findmynet"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "dev.moriya.findmymac"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Pinned signing key: CI decodes the keystore from the SIGNING_KEYSTORE_B64
    // repo secret and points SIGNING_KEYSTORE at it, so every build carries the
    // same certificate and can overwrite-install over previous builds. Without
    // the env vars (local builds), the default debug signing is used instead
    // and release stays unsigned.
    signingConfigs {
        create("pinned") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASS")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("pinned")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("SIGNING_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("pinned")
            }
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    
    // OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Batch 2: MapLibre Android SDK with OpenStreetMap raster tiles.
    // Pinned exactly (the removed map dependency used a floating,
    // unpinned version). 11.13.5 is the last patch release
    // of the stable 11.x line on Maven Central; see BATCH2.md for why
    // this line was chosen over 12.x/13.x and the 2026 globe line.
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    // Material Components (for Bottom Sheet)
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
