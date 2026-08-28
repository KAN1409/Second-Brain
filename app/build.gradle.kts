plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kareem.secondbrain"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.kareem.secondbrain"
        minSdk = 30
        targetSdk = 37
        versionCode = 12
        versionName = "1.0.0-relay-v1-candidate3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:database"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:privacy"))
    implementation(project(":core:search"))
    implementation(project(":domain"))
    implementation(project(":data:repository"))
    implementation(project(":capture:android"))
    implementation(project(":ai:api"))
    implementation(project(":ai:whisper"))
    implementation(project(":ai:ocr"))
    implementation(project(":ai:embedding"))
    implementation(project(":ai:gemini"))
    implementation(libs.androidx.sqlite.framework)
    implementation(project(":feature:timeline"))
    implementation(project(":feature:search"))
    implementation(project(":feature:ask"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:onboarding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
}
