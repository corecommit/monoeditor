// Root build file for Mono.
// Versions are pinned here and applied per-module; there is no version catalog
// on purpose: the project stays readable as plain Gradle Kotlin DSL.

plugins {
    id("com.android.application") version "8.4.2" apply false
    id("com.android.library") version "8.4.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.0" apply false
}
