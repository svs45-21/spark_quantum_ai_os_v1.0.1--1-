plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.spark.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.spark.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
