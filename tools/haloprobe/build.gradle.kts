plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":comms-core")) // 复用真解析器/握手
    implementation(project(":tools:halofeed")) // 复用抓包写入器 RecordWriter
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.shipradar.haloprobe.MainKt")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(17) }
