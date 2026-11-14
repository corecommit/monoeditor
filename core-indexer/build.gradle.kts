plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Only dependency: coroutines for Mutex/Dispatchers in SymbolDatabase.
    // The lexer, parser and fuzzy matcher stay dependency-free.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
