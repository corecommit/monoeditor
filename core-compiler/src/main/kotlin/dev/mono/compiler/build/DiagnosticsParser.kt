package dev.mono.compiler.build

import dev.mono.compiler.model.Diagnostic
import dev.mono.compiler.model.Severity

/**
 * Parses clang's plain (non-colored, no caret) diagnostic output:
 *
 *   /path/file.cpp:12:7: error: expected ';'
 *
 * Linker messages without positions become position-less diagnostics.
 */
object DiagnosticsParser {

    private val DIAG = Regex("^([^:\\n]+?):(\\d+):(\\d+):\\s+(fatal error|error|warning|note):\\s*(.*)$")
    private val LINK_ERR = Regex("^(?:ld\\.lld|clang\\+\\+|clang)[^:]*:\\s*(?:error|fatal error):\\s*(.*)$")

    fun parseLine(line: String): Diagnostic? {
        val m = DIAG.find(line.trim()) ?: return linkerDiagnostic(line)
        val (file, ln, col, sev, msg) = m.destructured
        return Diagnostic(
            file = file,
            line = ln.toIntOrNull() ?: 0,
            col = col.toIntOrNull() ?: 0,
            severity = when (sev) {
                "error", "fatal error" -> Severity.ERROR
                "warning" -> Severity.WARNING
                else -> Severity.NOTE
            },
            message = msg.trim()
        )
    }

    fun parse(output: String): List<Diagnostic> =
        output.lineSequence().mapNotNull { parseLine(it) }.toList()

    private fun linkerDiagnostic(line: String): Diagnostic? {
        val m = LINK_ERR.find(line.trim()) ?: return null
        return Diagnostic("", 0, 0, Severity.ERROR, m.groupValues[1].trim())
    }
}
