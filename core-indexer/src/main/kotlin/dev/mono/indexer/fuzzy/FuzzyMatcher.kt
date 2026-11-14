package dev.mono.indexer.fuzzy

import dev.mono.indexer.symbols.CompletionItem

/**
 * Prefix + subsequence fuzzy matcher tuned for code completion:
 * - exact prefix matches score highest;
 * - contiguous runs and word-boundary hits (snake_case, camelCase) add score;
 * - every extra character of the target subtracts a little, so short,
 *   precise symbols outrank long ones.
 */
object FuzzyMatcher {

    fun score(pattern: String, target: String): Int {
        if (pattern.isEmpty()) return 1
        if (target.length < pattern.length) return 0

        val p = pattern.lowercase()
        val t = target.lowercase()

        // Exact prefix (case-insensitive).
        if (t.startsWith(p)) return 10_000 - (target.length - pattern.length) * 4

        // Subsequence walk with contiguity + boundary bonuses.
        var ti = 0
        var score = 0
        var streak = 0
        var matchedFrom = -1
        for (pi in p.indices) {
            val pc = p[pi]
            var found = -1
            var j = ti
            while (j < t.length) {
                if (t[j] == pc) { found = j; break }
                j++
            }
            if (found < 0) return 0
            if (matchedFrom < 0) matchedFrom = found
            streak = if (found == ti) streak + 1 else 0
            score += 6 + streak * 5
            if (found == 0 || isBoundary(t, found)) score += 8
            ti = found + 1
        }
        // Prefer matches that start early.
        score -= matchedFrom * 2
        // Penalize long identifiers slightly.
        score -= (target.length - pattern.length).coerceAtMost(40)
        return score.coerceAtLeast(1)
    }

    fun matches(pattern: String, target: String): Boolean = score(pattern, target) > 0

    /** Filters and ranks completion items for [pattern]. */
    fun filter(
        pattern: String,
        items: List<CompletionItem>,
        limit: Int = 32
    ): List<CompletionItem> {
        if (pattern.isEmpty()) return items.take(limit)
        val scored = ArrayList<Pair<Int, CompletionItem>>(items.size / 2 + 8)
        for (item in items) {
            val s = score(pattern, item.label)
            if (s > 0) scored.add(s to item)
        }
        scored.sortWith(compareByDescending<Pair<Int, CompletionItem>> { it.first }.thenBy { it.second.label.length })
        return scored.take(limit).map { it.second }
    }

    private fun isBoundary(t: String, i: Int): Boolean {
        if (i == 0) return true
        val prev = t[i - 1]
        val cur = t[i]
        return (prev == '_' || prev == ':') ||
                (prev.isLowerCase() && cur.isUpperCase()) ||
                (prev.isDigit() && !cur.isDigit())
    }
}
