plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kareem.secondbrain.core.privacy"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    api(project(":core:model"))
    testImplementation(libs.junit)
}
