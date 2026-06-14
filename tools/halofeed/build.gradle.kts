plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    // 端到端互证测试:halofeed 生成的真线缆字节 ↔ comms-core 的 SpokeParser 解析(两独立来源)。
    testImplementation(project(":comms-core"))
}

application {
    mainClass.set("com.shipradar.halofeed.MainKt")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
