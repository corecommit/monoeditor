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
    // "<program>: error: <message>" for any program name. The bundled tools
    // are called libclang.so / libld_lld.so, so a fixed "ld.lld:" / "clang:"
    // prefix would silently drop every linker error.
    private val LINK_ERR = Regex("^([^:\\s]+):\\s+(?:error|fatal error):\\s*(.*)$")

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
        val t = line.trim()
        if (t.startsWith("lld is a generic driver")) {
            return Diagnostic("", 0, 0, Severity.ERROR, "lld is a generic driver (flavor not detected)")
        }
        val m = LINK_ERR.find(t) ?: return null
        return Diagnostic("", 0, 0, Severity.ERROR, m.groupValues[2].trim())
    }
}
