// Root build file. Plugins declared (apply false) so subprojects can opt in via the version catalog.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
