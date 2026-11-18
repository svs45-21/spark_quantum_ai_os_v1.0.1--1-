plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spark.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spark.companion"
        minSdk = 26
        // Google Play requires API 36 for new apps and updates since Aug 31, 2026.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Two builds from one codebase:
    //  - play: passes Google Play policy. No screen control (Play bans the
    //    Accessibility API for autonomous agents) and no SMS permissions
    //    (texts are drafted in the messaging app instead).
    //  - full: every feature, distributed as a direct APK.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "FULL_FEATURES", "false")
        }
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "FULL_FEATURES", "true")
        }
    }

    // Optional stable signing key, supplied by CI secrets (see android/README.md).
    // Without it, the debug key is used, which is fine for installing on your
    // own phone but changes between CI machines.
    val keystorePath = System.getenv("SPARK_KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("spark") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SPARK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SPARK_KEY_ALIAS")
                keyPassword = System.getenv("SPARK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("spark") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Deliberately framework-only: no AndroidX, so the app stays small and
    // the build has very few moving parts.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // On-device face detection (eyes, nose, mouth, tilt). Bundled model, no
    // Google Play Services needed. Adds a few MB to the APK.
    implementation("com.google.mlkit:face-detection:16.1.7")

    // For FileProvider (sharing the camera photo) and quick-access widget bits.
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
