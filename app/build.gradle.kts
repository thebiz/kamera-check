plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.john.kameracheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.john.kameracheck"
        minSdk = 30
        targetSdk = 34
        versionCode = 17
        versionName = "1.5"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Fester Schlüssel im Repo, damit Updates sich drüberinstallieren lassen
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // Texterkennung auf dem Gerät (Schrift als Eichmaß für die Unschärfe)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // QR-Code für den Testmuster-Link
    implementation("com.google.zxing:core:3.5.3")
}
