plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jpn.app"

    buildFeatures {
        buildConfig = true
    }
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jpn.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.1.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
    implementation(files("libs/libXray.aar"))
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
