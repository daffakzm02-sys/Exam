plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.system.android.uih"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.system.android.uih"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // API key ditanam saat build dari variabel lingkungan AI_API_KEY (atau HF_TOKEN lama) di Codemagic.
        buildConfigField("String", "API_KEY", "\"${System.getenv("AI_API_KEY") ?: System.getenv("HF_TOKEN") ?: ""}\"")

        // Package name dipakai di StealthHelper, XposedInit, dan JNI signature.
        // Ubah di sini kalau mau ganti identitas lagi — jangan hardcode di file Kotlin.
        buildConfigField("String", "STEALTH_PKG_NAME", "\"com.system.android.uih\"")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags += "-fvisibility=hidden"
                cppFlags += "-O2"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.18.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}