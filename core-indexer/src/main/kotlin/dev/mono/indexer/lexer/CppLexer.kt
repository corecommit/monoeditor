package dev.mono.indexer.lexer

/**
 * Token categories produced by the C++ lexer.
 *
 * The editor renders every category in a shade of gray; the categories below
 * are purely semantic, never visual.
 */
enum class TokType {
    KEYWORD,      // int, return, class, ...
    TYPE,         // size_t, uint32_t, FILE, ...
    PREPROC,      // #include, #define, #pragma ...
    STRING,       // "text", 'c', R"(...)", <header> in #include
    NUMBER,       // 42, 0x1F, 3.14f
    COMMENT,      // // line, /* block */
    PUNCT,        // operators and punctuation
    IDENT,        // identifiers (may be re-classified as calls later)
    FUNC_CALL,    // identifier immediately followed by '('
    WHITESPACE,
    OTHER
}

/** A single token on a single line. [start] is a column offset. */
class Tok(
    val type: TokType,
    val start: Int,
    val text: String
) {
    val length: Int get() = text.length
    val end: Int get() = start + text.length
    override fun toString(): String = "$type@$start:'$text'"
}

/**
 * Lexer state carried across line boundaries so the lexer can run
 * incrementally, one line at a time (essential for 10k+ line files).
 */
data class LexerState(
    val blockComment: Boolean = false,
    /** Non-null while inside a multi-line raw string; holds the delimiter. */
    val rawString: String? = null
) {
    val isPlain: Boolean get() = !blockComment && rawString == null
    companion object { val PLAIN = LexerState() }
}

/**
 * Single-pass, line-oriented C++ lexer.
 *
 * [lexLine] is allocation-frugal and never backtracks beyond a few
 * characters, so re-lexing a modified line is effectively free even in very
 * large files. Multi-line constructs (block comments, raw strings) survive
 * across lines through [LexerState].
 */
object CppLexer {

    val KEYWORDS: Set<String> = setOf(
        "alignas", "alignof", "and", "asm", "auto", "bitand", "bitor",
        "bool", "break", "case", "catch", "char", "char8_t", "char16_t",
        "char32_t", "class", "co_await", "co_return", "co_yield", "concept",
        "const", "consteval", "constexpr", "constinit", "const_cast",
        "continue", "decltype", "default", "delete", "do", "double",
        "dynamic_cast", "else", "enum", "explicit", "export", "extern",
        "false", "float", "for", "friend", "goto", "if", "inline", "int",
        "long", "mutable", "namespace", "new", "noexcept", "not", "nullptr",
        "operator", "or", "private", "protected", "public", "register",
        "reinterpret_cast", "requires", "return", "short", "signed",
        "sizeof", "static", "static_assert", "static_cast", "struct",
        "switch", "template", "this", "thread_local", "throw", "true", "try",
        "typedef", "typeid", "typename", "union", "unsigned", "using",
        "virtual", "void", "volatile", "wchar_t", "while", "xor"
    )

    val TYPES: Set<String> = setOf(
        "size_t", "ssize_t", "ptrdiff_t", "intptr_t", "uintptr_t", "intmax_t",
        "uintmax_t", "int8_t", "int16_t", "int32_t", "int64_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "FILE", "va_list", "time_t", "clock_t", "tm", "div_t", "jmp_buf",
        "std", "string", "vector", "map", "set", "pair"
    )

    private const val ID_START = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz_"
    private const val ID_PART = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_"

    /** Lexes [line] given the state at the line start; returns tokens plus the state at line end. */
    fun lexLine(line: String, state: LexerState = LexerState.PLAIN): Pair<List<Tok>, LexerState> {
        val toks = ArrayList<Tok>(24)
        var i = 0
        var st = state

        // Continue an unterminated raw string.
        if (st.rawString != null) {
            val close = ")" + st.rawString + "\""
            val idx = line.indexOf(close)
            if (idx >= 0) {
                val end = idx + close.length
                toks.add(Tok(TokType.STRING, 0, line.substring(0, end)))
                // Tokens from the remainder are relative to the substring:
                // shift their start offsets back onto the original line.
                val rest = lexLine(line.substring(end), LexerState.PLAIN)
                for (t in rest.first) toks.add(Tok(t.type, t.start + end, t.text))
                return Pair(toks, rest.second)
            }
            toks.add(Tok(TokType.STRING, 0, line))
            return Pair(toks, st)
        }

        // Continue a block comment.
        if (st.blockComment) {
            val idx = line.indexOf("*/")
            if (idx >= 0) {
                val end = idx + 2
                toks.add(Tok(TokType.COMMENT, 0, line.substring(0, end)))
                // Same offset shift as above for everything after the "*/".
                val rest = lexLine(line.substring(end), LexerState.PLAIN)
                for (t in rest.first) toks.add(Tok(t.type, t.start + end, t.text))
                return Pair(toks, rest.second)
            }
            toks.add(Tok(TokType.COMMENT, 0, line))
            return Pair(toks, st)
        }

        var directive = false   // inside a preprocessor directive line
        var includeAngle = false

        while (i < line.length) {
            val c = line[i]
            when {
                c == ' ' || c == '\t' -> {
                    var j = i
                    while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
                    toks.add(Tok(TokType.WHITESPACE, i, line.substring(i, j)))
                    i = j
                }

                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                    toks.add(Tok(TokType.COMMENT, i, line.substring(i)))
                    i = line.length
                }

                c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end >= 0) {
                        toks.add(Tok(TokType.COMMENT, i, line.substring(i, end + 2)))
                        i = end + 2
                    } else {
                        toks.add(Tok(TokType.COMMENT, i, line.substring(i)))
                        st = st.copy(blockComment = true)
                        i = line.length
                    }
                }

                c == '"' -> {
                    var j = i + 1
                    while (j < line.length) {
                        if (line[j] == '\\') { j += 2; continue }
                        if (line[j] == '"') { j++; break }
                        j++
                    }
                    toks.add(Tok(TokType.STRING, i, line.substring(i, minOf(j, line.length))))
                    i = minOf(j, line.length)
                }

                c == '\'' -> {
                    var j = i + 1
                    while (j < line.length) {
                        if (line[j] == '\\') { j += 2; continue }
                        if (line[j] == '\'') { j++; break }
                        j++
                    }
                    toks.add(Tok(TokType.STRING, i, line.substring(i, minOf(j, line.length))))
                    i = minOf(j, line.length)
                }

                // Raw string literals: R"delim( ... )delim" (plus u8R, uR, LR, UR forms).
                (c == 'R' && i + 1 < line.length && line[i + 1] == '"' && !prevIsIdPart(line, i)) ||
                    (isRawPrefixAt(line, i)) -> {
                    val open = line.indexOf('(', i + 2)
                    if (open < 0) {
                        toks.add(Tok(TokType.STRING, i, line.substring(i)))
                        i = line.length
                    } else {
                        val delim = line.substring(i + 2, open)
                        val close = ")" + delim + "\""
                        val end = line.indexOf(close, open + 1)
                        if (end >= 0) {
                            toks.add(Tok(TokType.STRING, i, line.substring(i, end + close.length)))
                            i = end + close.length
                        } else {
                            toks.add(Tok(TokType.STRING, i, line.substring(i)))
                            st = st.copy(rawString = delim)
                            i = line.length
                        }
                    }
                }

                directive && includeAngle && c == '<' -> {
                    val end = line.indexOf('>', i + 1)
                    if (end >= 0) {
                        toks.add(Tok(TokType.STRING, i, line.substring(i, end + 1)))
                        i = end + 1
                    } else {
                        toks.add(Tok(TokType.STRING, i, line.substring(i)))
                        i = line.length
                    }
                }

                c == '#' && !directive && toks.none { it.type != TokType.WHITESPACE } -> {
                    directive = true
                    includeAngle = false
                    var j = i + 1
                    while (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
                    var k = j
                    while (k < line.length && ID_PART.indexOf(line[k]) >= 0) k++
                    if (k > j) {
                        val name = line.substring(j, k)
                        if (name == "include") includeAngle = true
                        toks.add(Tok(TokType.PREPROC, i, line.substring(i, k)))
                        i = k
                    } else {
                        toks.add(Tok(TokType.PREPROC, i, "#"))
                        i++
                    }
                }

                c.isDigit() -> {
                    var j = i + 1
                    if (c == '0' && j < line.length && (line[j] == 'x' || line[j] == 'X')) {
                        j++
                        while (j < line.length && isHex(line[j])) j++
                    } else {
                        while (j < line.length && (line[j].isDigit() || line[j] == '.' ||
                                    line[j] == 'e' || line[j] == 'E' ||
                                    ((line[j] == '+' || line[j] == '-') && (line[j - 1] == 'e' || line[j - 1] == 'E')))) j++
                    }
                    while (j < line.length && "uUlLfFzZ".indexOf(line[j]) >= 0) j++
                    toks.add(Tok(TokType.NUMBER, i, line.substring(i, j)))
                    i = j
                }

                ID_START.indexOf(c) >= 0 -> {
                    var j = i + 1
                    while (j < line.length && ID_PART.indexOf(line[j]) >= 0) j++
                    val word = line.substring(i, j)
                    // A raw-string prefix directly followed by a quote lexes as a string opener.
                    if (j < line.length && line[j] == '"' && word in RAW_PREFIXES) {
                        val open = line.indexOf('(', j)
                        if (open >= 0) {
                            val delim = line.substring(j + 1, open)
                            val close = ")" + delim + "\""
                            val end = line.indexOf(close, open + 1)
                            if (end >= 0) {
                                toks.add(Tok(TokType.STRING, i, line.substring(i, end + close.length)))
                                i = end + close.length
                            } else {
                                toks.add(Tok(TokType.STRING, i, line.substring(i)))
                                st = st.copy(rawString = delim)
                                i = line.length
                            }
                            continue
                        }
                    }
                    val type = when {
                        KEYWORDS.contains(word) -> TokType.KEYWORD
                        TYPES.contains(word) -> TokType.TYPE
                        else -> TokType.IDENT
                    }
                    toks.add(Tok(type, i, word))
                    i = j
                }

                else -> {
                    // Multi-character operators first.
                    val two = if (i + 1 < line.length) line.substring(i, i + 2) else ""
                    val three = if (i + 2 < line.length) line.substring(i, i + 3) else ""
                    val len = when {
                        three == "<<=" || three == ">>=" || three == "<=>" || three == "..." -> 3
                        two in TWO_CHAR_OPS -> 2
                        else -> 1
                    }
                    toks.add(Tok(TokType.PUNCT, i, line.substring(i, i + len)))
                    i += len
                }
            }
        }

        // Post-pass: mark call positions (identifier directly followed by '(').
        for (k in toks.indices) {
            val t = toks[k]
            if (t.type == TokType.IDENT || t.type == TokType.TYPE) {
                var m = k + 1
                while (m < toks.size && toks[m].type == TokType.WHITESPACE) m++
                if (m < toks.size && toks[m].type == TokType.PUNCT && toks[m].text == "(") {
                    // Do not mark control-flow keywords; those are already KEYWORD.
                    if (t.type == TokType.IDENT) {
                        toks[k] = Tok(TokType.FUNC_CALL, t.start, t.text)
                    }
                }
            }
        }

        return Pair(toks, st)
    }

    /** Lexes whole text into per-line token lists (convenience for batch indexing). */
    fun lex(text: String): List<List<Tok>> {
        val lines = text.split('\n')
        val out = ArrayList<List<Tok>>(lines.size)
        var st = LexerState.PLAIN
        for (l in lines) {
            val r = lexLine(l, st)
            out.add(r.first)
            st = r.second
        }
        return out
    }

    private val TWO_CHAR_OPS = setOf(
        "->", "::", "++", "--", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||",
        "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", ".*", "->*"
    )

    private val RAW_PREFIXES = setOf("u8R", "uR", "LR", "UR", "R")

    private fun prevIsIdPart(line: String, i: Int): Boolean =
        i > 0 && ID_PART.indexOf(line[i - 1]) >= 0

    private fun isRawPrefixAt(line: String, i: Int): Boolean {
        for (p in RAW_PREFIXES) {
            if (p == "R") continue // handled by the main branch
            if (line.regionMatches(i, p, 0, p.length) &&
                i + p.length < line.length && line[i + p.length] == '"' &&
                (i == 0 || ID_PART.indexOf(line[i - 1]) < 0)
            ) return true
        }
        return false
    }

    private fun isHex(c: Char): Boolean =
        c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
}
