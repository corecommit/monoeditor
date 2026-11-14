package dev.mono.indexer.index

import dev.mono.indexer.fuzzy.FuzzyMatcher
import dev.mono.indexer.snippet.Snippets
import dev.mono.indexer.symbols.CompletionItem
import dev.mono.indexer.symbols.CppSymbolParser
import dev.mono.indexer.symbols.SourceIndex
import dev.mono.indexer.symbols.SymKind
import dev.mono.indexer.symbols.Symbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One directory searched when resolving #include targets: [path] is the
 * absolute directory on disk, [keyPrefix] is its location relative to the
 * storage root ("modules", "projects/Hello"), which becomes the first
 * segment of the index key for files found there.
 */
data class SearchDir(val path: String, val keyPrefix: String)

/**
 * Holds one [SourceIndex] per source file (user modules, project files) and
 * merges them with the curated [StlIndex] to answer completion queries.
 *
 * Files are keyed by their full path relative to the storage root
 * ("modules/greet.h", "projects/Hello/main.cpp"), so same-named files in
 * different projects never collide. Files are re-indexed in the background
 * when their content changes; the checksum in [SourceIndex] makes re-parsing
 * a no-op for untouched files.
 */
class SymbolDatabase {

    private val mutex = Mutex()
    private val indexes = LinkedHashMap<String, SourceIndex>()

    /** Re-indexes [key] if the checksum of [source] changed. Safe to call often. */
    suspend fun reindex(key: String, source: String) {
        val checksum = CppSymbolParser.checksum(source)
        mutex.withLock {
            val existing = indexes[key]
            if (existing != null && existing.checksum == checksum) return
        }
        val index = withContext(Dispatchers.Default) {
            val symbols = CppSymbolParser.parse(source)
            SourceIndex(key, symbols, checksum, System.currentTimeMillis())
        }
        mutex.withLock { indexes[key] = index }
    }

    suspend fun remove(key: String) {
        mutex.withLock { indexes.remove(key) }
    }

    suspend fun snapshot(): Map<String, SourceIndex> = mutex.withLock { LinkedHashMap(indexes) }

    suspend fun indexFor(key: String): SourceIndex? = mutex.withLock { indexes[key] }

    /** An include target resolved to its index key and (when real) its absolute path. */
    private class Resolved(val key: String, val absPath: String?)

    /**
     * Resolves one include target against the search directories. User files
     * always win over the curated STL list — `#include <string>` picks the
     * user's own modules/string.h when one exists.
     */
    private fun resolveInclude(
        target: String,
        dirs: List<SearchDir>,
        virtualModules: Set<String>
    ): Resolved? {
        if (virtualModules.contains(target)) return Resolved("modules/$target", null)
        for (d in dirs) {
            val f = java.io.File(d.path, target)
            if (f.isFile) return Resolved(d.keyPrefix + "/" + target, f.absolutePath)
        }
        return null
    }

    /**
     * Computes completion candidates for a file whose include directives are
     * [includes] (already parsed). [searchDirs] are the module and project
     * directories; [virtualModules] names modules that live in a SAF tree
     * where no File paths exist.
     */
    suspend fun completeFor(
        currentFileKey: String,
        includes: List<Includes.IncludeDirective>,
        searchDirs: List<SearchDir> = emptyList(),
        virtualModules: Set<String> = emptySet()
    ): List<CompletionItem> {
        val snap = snapshot()
        val items = ArrayList<CompletionItem>(256)

        // Current file.
        snap[currentFileKey]?.let { idx ->
            for (s in idx.symbols) {
                items.add(s.toCompletionItem(module = currentFileKey.substringAfterLast('/')))
            }
        }

        for (inc in includes) {
            val target = inc.target
            val resolved = resolveInclude(target, searchDirs, virtualModules)
            if (resolved != null) {
                val idx = snap[resolved.key]
                if (idx != null) {
                    for (s in idx.symbols) {
                        items.add(s.toCompletionItem(module = target))
                    }
                } else if (resolved.absPath != null) {
                    // Module exists but has not been indexed yet: parse inline.
                    val realPath = java.io.File(resolved.absPath)
                    val src = withContext(Dispatchers.IO) {
                        runCatching { realPath.readText() }.getOrNull()
                    }
                    if (src != null) {
                        reindex(resolved.key, src)
                        val fresh = snapshot()[resolved.key]
                        if (fresh != null) {
                            for (s in fresh.symbols) items.add(s.toCompletionItem(module = target))
                        }
                    }
                }
            } else if (Includes.isStandardHeader(target)) {
                for (s in StlIndex.forHeader(target)) {
                    items.add(s.toCompletionItem(module = "<${s.header}>"))
                }
            }
        }

        // Snippets + a small always-available keyword set.
        items.addAll(Snippets.completionItems())
        for (k in QUICK_KEYWORDS) {
            items.add(CompletionItem(k, SymKind.KEYWORD, "keyword", module = "C++"))
        }
        return items
    }

    /**
     * Ranked completion for [prefix]. When [memberAccess] is true (the query
     * came right after ".", "->" or "::") snippets and keywords are dropped
     * — they never make sense in member position — and the empty prefix is
     * allowed.
     */
    suspend fun complete(
        prefix: String,
        currentFileKey: String,
        includes: List<Includes.IncludeDirective>,
        searchDirs: List<SearchDir> = emptyList(),
        virtualModules: Set<String> = emptySet(),
        memberAccess: Boolean = false
    ): List<CompletionItem> {
        var candidates = completeFor(currentFileKey, includes, searchDirs, virtualModules)
        if (memberAccess) {
            candidates = candidates.filter { it.kind != SymKind.SNIPPET && it.kind != SymKind.KEYWORD }
        }
        // De-duplicate by label+module, keep the first occurrence.
        val seen = HashSet<String>()
        val unique = candidates.filter { seen.add(it.label + "|" + it.module) }
        return FuzzyMatcher.filter(prefix, unique)
    }

    private fun Symbol.toCompletionItem(module: String): CompletionItem {
        val qualifier = if (container.isNotEmpty() && container != "std") "$container::" else ""
        return CompletionItem(
            label = name,
            kind = kind,
            detail = buildString {
                if (signature.isNotEmpty()) append(signature)
                if (container.isNotEmpty()) {
                    if (isNotEmpty()) append("  ")
                    append("in ")
                    append(qualifier.ifEmpty { container })
                }
            },
            module = module,
            insert = name
        )
    }

    private val QUICK_KEYWORDS = listOf(
        "int", "float", "double", "char", "bool", "void", "auto", "const",
        "constexpr", "static", "return", "if", "else", "for", "while", "break",
        "continue", "true", "false", "nullptr", "new", "delete", "class",
        "struct", "enum", "namespace", "template", "typename", "using",
        "public", "private", "protected", "virtual", "override", "sizeof",
        "static_cast", "dynamic_cast", "reinterpret_cast", "noexcept", "inline"
    )
}
