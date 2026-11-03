plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vox.hud"
    compileSdk = 27

    defaultConfig {
        applicationId = "com.vox.hud"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "1.0.0"
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

    aaptOptions {
        cruncherEnabled = false
    }
}

dependencies {
    // Zero external dependencies — uses only Android built-in APIs.
    // No OkHttp, no AndroidX — keeping the APK as small as possible
    // for the HUD's limited storage and memory.
}
