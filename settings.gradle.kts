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

// --- Android modules (thin shells; enable after Android SDK install + minSdk decision §6 #3) ---
// Uncomment once local.properties points at a valid Android SDK and target API level is decided:
// SDK now present (local.properties -> sdk.dir); :comms enabled for T1.1 (Foreground Service wiring).
include(":comms")      // Foreground Service wiring comms-core to real sockets/lifecycle
// include(":app")        // Compose HMI + Canvas/GL render surface + input
