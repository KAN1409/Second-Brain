plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.kareem.secondbrain.core.search"
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
    implementation(project(":core:model"))
    implementation(libs.androidx.appsearch)
    implementation(libs.androidx.appsearch.local.storage)
    implementation(libs.androidx.appsearch.builtin.types)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
