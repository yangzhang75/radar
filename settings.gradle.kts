rootProject.name = "ship-radar"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// --- Verifiable-today core (pure JVM, no Android SDK required) ---
// These hold ALL protocol/parsing/geometry logic and are unit-testable on the Mac with just Java+Gradle.
// This is what lets many workers run in parallel NOW, each fully verifiable.
include(":shared")        // frozen contract + constants + util  (owner: orchestrator)
include(":comms-core")    // HALO/61162/450 parsing + command encoding + sync logic (pure JVM)
include(":ui-core")       // PPI geometry + color mapping + target/CPA-TCPA logic (pure JVM)
include(":tools:halofeed") // HALO fake-data generator (pure JVM, sends multicast UDP)
include(":tools:haloprobe") // HALO 真雷达探针 (纯 JVM, 本机直连/解码/抓包, 走蒲公英 X5)

// --- Android modules (thin shells over the pure-JVM cores) ---
// Device base: 1920x1200 landscape, wired ethernet, minSdk 26 / targetSdk 34 (defaults; confirm OS version).
include(":comms")      // Foreground Service wiring comms-core to real sockets/lifecycle
include(":app")        // Compose HMI + Canvas/GL render surface + input
