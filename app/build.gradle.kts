// :app — Android application shell. DEFERRED module: this builds ONLY after an Android SDK is
// installed (local.properties → sdk.dir) AND `:app` is uncommented in settings.gradle.kts AND
// `android.useAndroidX=true` is set in gradle.properties (see delivery report — orchestrator step).
// It is intentionally NOT wired into the pure-JVM build so that `./gradlew :ui-core:test` etc. keep
// working on machines with no SDK.
//
// This module hosts the T2.1r PPI echo render surface (com.shipradar.app.ppi). All geometry comes
// from :ui-core `com.shipradar.uicore.ppi` (JVM-verified) and all echo colour from
// `com.shipradar.uicore.color.ColorMapper` (JVM-verified). This module adds only the Android
// Canvas/GPU plumbing.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0 Compose compiler plugin. Declared inline (version == catalog `kotlin`) to keep this
    // change inside :app; orchestrator may promote it to a catalog alias. See delivery report.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.shipradar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shipradar.app"
        // TODO(待定 §6 #3 minSdk): target marine console device not yet chosen. 28 (Android 9) is a
        // provisional floor (hardware-accelerated Canvas, modern Compose, common on embedded panels).
        // Confirm against the certified hardware before freezing — 待张建 input.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":shared"))   // contract.EchoSpoke, util.Angles, coroutines Flow (api)
    implementation(project(":ui-core"))  // ppi geometry + color.ColorMapper

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Compose (BOM-pinned via the version catalog; library aliases TODO in catalog — see report).
    val composeBom = platform("androidx.compose:compose-bom:${libs.versions.composeBom.get()}")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
