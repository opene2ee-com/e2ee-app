plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.opene2ee.opene2ee"
    // Sprint 11.0B — flutter_webrtc 0.13.x requires compileSdk >= 36.
    // The flutter.compileSdkVersion default is 35; we override to 36
    // here so the plugin's `peer_connection_factory` native ABI loads
    // without a `:app:compileDebugKotlin` warning cascade. The
    // targetSdk stays at flutter.targetSdkVersion (35) so the
    // Android 14+ foreground service type variety behavior (Sprint
    // 11.0A) is unchanged.
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.opene2ee.opene2ee"
        // Sprint 14 — minSdk = 26 (Android 8+). NotificationChannel
        // + foreground service + startForegroundService için. Önceki
        // Sprint 12.0C/12.0F+ kodu minSdk = flutter.minSdkVersion
        // kullanıyordu; Sprint 14 VPN kodu 26+ gerektirir.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
