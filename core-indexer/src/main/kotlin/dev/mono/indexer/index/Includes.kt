package dev.mono.indexer.index

/**
 * #include handling for the module system.
 *
 * User modules live in a `modules/` directory which Mono adds to the compiler
 * include path (-I), so `#include <module.cpp>` and `#include "module.h"`
 * both resolve to user files. This object parses include directives and
 * resolves them against the module directory, the project directory and the
 * bundled STL header list.
 */
object Includes {

    data class IncludeDirective(
        val line: Int,
        val target: String,
        val angle: Boolean
    )

    private val INCLUDE_RE = Regex("^\\s*#\\s*include\\s*([<\"])([^>\"\\n]+)[>\"]")

    fun parse(text: String): List<IncludeDirective> {
        val out = ArrayList<IncludeDirective>(8)
        text.split('\n').forEachIndexed { idx, line ->
            val m = INCLUDE_RE.find(line) ?: return@forEachIndexed
            out.add(IncludeDirective(idx, m.groupValues[2].trim(), m.groupValues[1] == "<"))
        }
        return out
    }

    /**
     * Resolves one include target against search directories (module dir,
     * project dir). Returns the resolved absolute path or null when the
     * include cannot be found.
     */
    fun resolve(target: String, searchDirs: List<String>): String? {
        if (!target.contains('/') && target !in StlIndex.headers) {
            for (dir in searchDirs) {
                val f = java.io.File(dir, target)
                if (f.isFile) return f.absolutePath
            }
        }
        for (dir in searchDirs) {
            val f = java.io.File(dir, target)
            if (f.isFile) return f.absolutePath
        }
        return null
    }

    /** True when the target names a known standard library header. */
    fun isStandardHeader(target: String): Boolean = target in StlIndex.headers

    /**
     * A .h module should carry `#pragma once` or a classic include guard;
     * used by the editor to offer the guard reminder.
     */
    fun hasIncludeGuard(source: String): Boolean {
        if (source.contains(Regex("^\\s*#\\s*pragma\\s+once", RegexOption.MULTILINE))) return true
        val define = Regex("^\\s*#\\s*ifndef\\s+([A-Za-z_][A-Za-z0-9_]*)").find(source) ?: return false
        val guard = define.groupValues[1]
        if (guard.isEmpty()) return false
        return source.contains(Regex("^\\s*#\\s*define\\s+${Regex.escape(guard)}", RegexOption.MULTILINE)) &&
                source.contains(Regex("^\\s*#\\s*endif", RegexOption.MULTILINE))
    }
}
