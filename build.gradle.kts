// Root build file. All plugins declared (apply false) so they sit on the classpath once with a
// known version — subprojects then apply via the version catalog without version conflicts.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}
