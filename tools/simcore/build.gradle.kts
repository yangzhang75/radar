plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":shared"))
    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
kotlin { jvmToolchain(17) }
