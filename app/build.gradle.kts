plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.guardian"
    // Target the latest available at build time. Bump as newer SDKs ship.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.guardian"
        // Android 5.0 (API 21): the lowest version on which Device Owner exists.
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // No shrinking for now — Stage 1/2 prioritize stability and simplicity
            // over size. ProGuard rules are kept so later stages can enable it.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // Stage 3: the TFLite model is memory-mapped from the APK, so it must be stored
    // uncompressed.
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle-aware coroutine scopes for the services.
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager drives the periodic, unattended blocklist refresh.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Stage 3: on-device image classification. Plain TensorFlow Lite runtime (no Play
    // Services, no model downloads) — inference is fully local.
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // Local unit tests (pure-JVM logic: blocklist parsing/matching, DNS packet
    // build/parse round-trips, scan scheduling/confirmation). Run with:
    // ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}
