plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.shipradar.halofeed.MainKt")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
