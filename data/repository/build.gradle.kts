plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kareem.secondbrain.data.repository"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:privacy"))
    implementation(project(":core:search"))
    implementation(project(":domain"))
    implementation(project(":ai:api"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
