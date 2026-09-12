plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kareem.khojlocal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kareem.khojlocal"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0-local"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
