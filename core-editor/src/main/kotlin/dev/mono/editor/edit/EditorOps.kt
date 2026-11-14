package dev.mono.editor.edit

import dev.mono.editor.state.EditorState
import dev.mono.editor.text.EditorText
import dev.mono.indexer.lexer.TokType

/** Bracket/indent/word heuristics shared by the controller and the renderer. */
object EditorOps {

    val OPEN_BRACKETS = mapOf('(' to ')', '[' to ']', '{' to '}')
    val CLOSE_BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')
    val QUOTES = setOf('"', '\'')
    val AUTO_PAIRS = setOf('(', '[', '{', '"', '\'')
    /** Characters next to which a quote auto-pair still makes sense. */
    val PUNCT_BOUNDARY = setOf('(', ')', '[', ']', '{', '}', ',', ';', ':', '=', '+', '-', '*', '/', '<', '>', '&', '|', '!', '?', '#', '"', '\'')

    /** Leading whitespace of a line. */
    fun indentOf(line: String): String {
        val sb = StringBuilder()
        for (c in line) {
            if (c == ' ' || c == '\t') sb.append(c) else break
        }
        return sb.toString()
    }

    /** Auto-indent for a new line typed at (line, col) on [line]. */
    fun nextIndent(text: EditorText, line: Int, indentWidth: Int): String {
        val src = text.line(line)
        val indent = indentOf(src)
        val body = src.trimStart()
        val opens = body.startsWith("{") || body.endsWith("{") ||
                body.removeSuffix(";").trimEnd().endsWith("{")
        val closes = body.startsWith("}")
        return when {
            opens && closes -> indent
            opens -> indent + " ".repeat(indentWidth)
            closes -> indent.dropLast(indentWidth.coerceAtMost(indent.length))
            else -> indent
        }
    }

    /** True when the caret sits between an empty bracket/quote pair. */
    fun betweenEmptyPair(text: EditorText, line: Int, col: Int): Boolean {
        val l = text.line(line)
        if (col <= 0 || col >= l.length) return false
        val prev = l[col - 1]
        val next = l[col]
        return (OPEN_BRACKETS[prev] == next) || (prev in QUOTES && prev == next)
    }

    /** Next character at the caret ("" at end of line). */
    fun nextChar(text: EditorText, line: Int, col: Int): Char? {
        val l = text.line(line)
        return if (col < l.length) l[col] else null
    }

    /**
     * Finds the matching bracket for the bracket at (line, col), skipping
     * tokens inside strings and comments (token-driven, so it is correct
     * inside comments too). Returns null when there is no match.
     */
    fun findMatchingBracket(state: EditorState, line: Int, col: Int): Pair<dev.mono.editor.state.Pos, dev.mono.editor.state.Pos>? {
        val text = state.text
        val l = text.lineOrNull(line) ?: return null
        if (col >= l.length) return null
        val ch = l[col]
        val open = OPEN_BRACKETS[ch]
        val close = CLOSE_BRACKETS[ch]
        if (open == null && close == null) return null
        val origin = dev.mono.editor.state.Pos(line, col)

        var li = line
        var ti = col
        if (open != null) {
            var depth = 0
            while (li < text.lineCount) {
                val toks = state.tokenCache.tokensFor(text, li)
                for (t in toks) {
                    if (t.start + t.length <= ti) continue
                    if (t.type == TokType.PUNCT) {
                        for (k in 0 until t.text.length) {
                            val c = t.text[k]
                            if (t.start + k < ti) continue
                            if (c == ch) depth++
                            if (c == open) {
                                depth--
                                if (depth == 0) return origin to dev.mono.editor.state.Pos(li, t.start + k)
                            }
                        }
                    }
                }
                li++
                ti = 0
            }
        } else {
            var depth = 0
            while (li >= 0) {
                val toks = state.tokenCache.tokensFor(text, li)
                for (i in toks.indices.reversed()) {
                    val t = toks[i]
                    if (t.type == TokType.PUNCT) {
                        for (k in t.text.length - 1 downTo 0) {
                            if (li == line && t.start + k > ti) continue
                            val c = t.text[k]
                            if (c == ch) depth++
                            if (c == close) {
                                depth--
                                if (depth == 0) return origin to dev.mono.editor.state.Pos(li, t.start + k)
                            }
                        }
                    }
                }
                li--
                ti = Int.MAX_VALUE
            }
        }
        return null
    }

    /** Word (identifier chars) around a position; returns (start, end) cols. */
    fun wordAround(text: EditorText, line: Int, col: Int): Pair<Int, Int> {
        val l = text.lineOrNull(line) ?: return col to col
        if (l.isEmpty()) return col to col
        var s = col.coerceIn(0, l.length)
        var e = s
        if (s < l.length && !isWordChar(l[s])) {
            // Standing on a non-word char: pick the word to the left.
            s = s.coerceAtMost(l.length)
            while (s > 0 && isWordChar(l[s - 1])) s--
            e = s
            return s to e
        }
        while (s > 0 && isWordChar(l[s - 1])) s--
        while (e < l.length && isWordChar(l[e])) e++
        return s to e
    }

    fun isWordChar(c: Char): Boolean =
        c == '_' || c.isLetterOrDigit()

    /** The identifier prefix ending at the caret ("" when none). */
    fun prefixBeforeCaret(text: EditorText, line: Int, col: Int): Pair<Int, String> {
        val l = text.lineOrNull(line) ?: return col to ""
        var s = col.coerceIn(0, l.length)
        while (s > 0 && isWordChar(l[s - 1])) s--
        return s to l.substring(s, col.coerceIn(s, l.length))
    }

    /**
     * True when the characters immediately before [col] on [line] are a
     * member/qualifier access operator: ".", "->" or "::". Used to offer
     * completion with an empty prefix after these triggers.
     */
    fun memberAccessBefore(text: EditorText, line: Int, col: Int): Boolean {
        val l = text.lineOrNull(line) ?: return false
        if (col <= 0) return false
        val prev = l[col - 1]
        if (prev == '.') return true
        if (col >= 2) {
            if (prev == '>' && l[col - 2] == '-') return true
            if (prev == ':' && l[col - 2] == ':') return true
        }
        return false
    }
}

internal fun EditorText.lineOrNull(index: Int): String? =
    if (index in 0 until lineCount) line(index) else null
