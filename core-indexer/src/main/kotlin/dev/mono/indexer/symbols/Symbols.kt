package dev.mono.indexer.symbols

import dev.mono.indexer.snippet.Snippet

/** Kind of a C++ symbol. */
enum class SymKind {
    FUNCTION, METHOD, CLASS, STRUCT, ENUM, UNION, NAMESPACE,
    MACRO, MEMBER, VARIABLE, TYPEDEF, SNIPPET, KEYWORD;

    /** Two-letter glyph used by the completion popup. */
    val badge: String get() = when (this) {
        FUNCTION -> "fn"
        METHOD -> "fn"
        CLASS -> "cl"
        STRUCT -> "st"
        ENUM -> "en"
        UNION -> "un"
        NAMESPACE -> "ns"
        MACRO -> "mc"
        MEMBER -> "mb"
        VARIABLE -> "vr"
        TYPEDEF -> "ty"
        SNIPPET -> "sn"
        KEYWORD -> "kw"
    }
}

/**
 * A single indexed symbol. [signature] is a best-effort reconstruction from
 * the source (heuristic parser, not a full C++ front-end); [container] names
 * the enclosing class/struct/enum/namespace when known.
 */
data class Symbol(
    val name: String,
    val kind: SymKind,
    val signature: String = "",
    val container: String = "",
    val line: Int = 0,
    val header: String = ""
)

/** One row of the editor completion popup. */
data class CompletionItem(
    val label: String,
    val kind: SymKind,
    val detail: String,
    val module: String,
    val insert: String = label,
    val snippet: Snippet? = null
)

/**
 * Result of indexing one source file (a user module, a project file, or one
 * STL header from the bundled index). [checksum] lets the database skip
 * re-parsing unchanged files. [key] is the file's full path relative to the
 * storage root (e.g. "modules/mathx.h", "projects/Hello/main.cpp") so files
 * with the same name in different directories never collide.
 */
data class SourceIndex(
    val key: String,          // full relative path, e.g. "modules/mathx.h"
    val symbols: List<Symbol>,
    val checksum: String,
    val indexedAtMs: Long
)
