plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.remigl.keeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.remigl.keeper"
        // 27 = Android 8.1: java.time + NotificationManager.isNotificationListenerAccessGranted().
        minSdk = 27
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    // No dependency metadata blob embedded in the APK / bundle.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// No runtime dependency: only the Android framework and the Kotlin stdlib end up in the APK.
dependencies {
    testImplementation("junit:junit:4.13.2") // local JVM tests of rotation / format only
}
