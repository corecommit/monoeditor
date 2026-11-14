package dev.mono.compiler.model

/** Supported C++ standards. */
enum class CppStandard(val flag: String, val label: String) {
    CXX11("-std=c++11", "C++11"),
    CXX14("-std=c++14", "C++14"),
    CXX17("-std=c++17", "C++17"),
    CXX20("-std=c++20", "C++20"),
    CXX23("-std=c++23", "C++23");

    companion object {
        fun fromFlag(flag: String): CppStandard =
            entries.firstOrNull { it.flag == flag } ?: CXX17
    }
}

/** Optimization levels. */
enum class OptLevel(val flag: String, val label: String) {
    O0("-O0", "O0 — no optimization"),
    O1("-O1", "O1 — light"),
    O2("-O2", "O2 — fast"),
    O3("-O3", "O3 — aggressive"),
    OS("-Os", "Os — size");

    companion object {
        fun fromFlag(flag: String): OptLevel =
            entries.firstOrNull { it.flag == flag } ?: O0
    }
}

enum class Severity { ERROR, WARNING, NOTE }

/** One clang diagnostic (or a Mono-originated one such as a missing module). */
data class Diagnostic(
    val file: String,
    val line: Int,
    val col: Int,
    val severity: Severity,
    val message: String
) {
    val hasPosition: Boolean get() = line > 0
}

/** Progress and log events streamed out of a build. */
sealed class BuildEvent {
    data class Started(val tuCount: Int) : BuildEvent()
    data class Phase(val progress: Float, val detail: String) : BuildEvent()
    data class Log(val line: String) : BuildEvent()
    data class DiagnosticEvent(val diagnostic: Diagnostic) : BuildEvent()
    data class Finished(
        val success: Boolean,
        val binaryPath: String?,
        val durationMs: Long,
        val message: String
    ) : BuildEvent()
}

/** One line of the run console. */
sealed class ConsoleEvent {
    data class Stdout(val text: String) : ConsoleEvent()
    data class Stderr(val text: String) : ConsoleEvent()
    data class StdinEcho(val text: String) : ConsoleEvent()
    data class Exit(val code: Int) : ConsoleEvent()
    data object TimedOut : ConsoleEvent()
    data class Failed(val message: String) : ConsoleEvent()
}
