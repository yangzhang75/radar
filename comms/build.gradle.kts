plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.shipradar.comms.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":comms-core"))           // pure-JVM protocol/parse/sync/alarm logic
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.lifecycle.service)
}
