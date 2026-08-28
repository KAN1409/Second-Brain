plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kareem.secondbrain.ai.ocr"
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
    implementation(project(":ai:api"))
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.tesseract.android)
}
