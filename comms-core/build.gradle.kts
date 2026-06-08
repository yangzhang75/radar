plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
