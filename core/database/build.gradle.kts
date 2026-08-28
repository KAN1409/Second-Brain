plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kareem.secondbrain.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.kotlinx.coroutines.core)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
