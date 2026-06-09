// Root build file. Plugins declared (apply false) so subprojects can opt in via the version catalog.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    // Android plugins resolved once at the root classpath so the :comms (and later :app) modules can
    // apply them without a version. Required now that the SDK is present and :comms is enabled (T1.1).
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
