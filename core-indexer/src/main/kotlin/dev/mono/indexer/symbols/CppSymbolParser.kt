package dev.mono.indexer.symbols

import dev.mono.indexer.lexer.CppLexer
import dev.mono.indexer.lexer.Tok
import dev.mono.indexer.lexer.TokType

/**
 * Heuristic C++ symbol extractor.
 *
 * Not a compiler front-end: it walks the token stream of a file, tracks brace
 * depth and class/namespace context, and emits the symbols that matter for
 * autocomplete: functions, methods, classes, structs, enums, unions,
 * namespaces, members, typedefs, macros and file-scope variables.
 *
 * Symbols are only emitted at file, namespace or class scope — function
 * bodies are skipped entirely. A definition whose '{' opens on the same
 * line pushes a suppressing "function" container; when only the parameter
 * list closed on the line, the body may open on the next line, which the
 * pending-function marker handles.
 */
object CppSymbolParser {

    fun checksum(source: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = d.digest(source.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(64)
        for (b in bytes) sb.append(((b.toInt() and 0xF0) shr 4).toString(16)).append((b.toInt() and 0x0F).toString(16))
        return sb.toString()
    }

    fun parse(source: String): List<Symbol> {
        val lines = CppLexer.lex(source)
        return parseTokens(lines)
    }

    /** Second entry point reusing already-lexed lines (used by the editor). */
    fun parseTokens(lines: List<List<Tok>>): List<Symbol> {
        val out = ArrayList<Symbol>()
        val containers = ArrayDeque<Container>()   // innermost last
        var depth = 0
        // Function name whose parameter list closed at end of line — the
        // body '{' may open on the next line.
        var pendingFunction: String? = null

        for (lineIdx in lines.indices) {
            val toks = lines[lineIdx].filter { it.type != TokType.WHITESPACE }
            if (toks.isEmpty()) continue
            var i = 0

            // Preprocessor directives.
            if (toks[0].type == TokType.PREPROC) {
                if (containers.lastOrNull()?.isFunction != true) {
                    val head = toks[0].text.replace(Regex("\\s+"), "")
                    if (head == "#define" && toks.size > 1 &&
                        toks[1].type in setOf(
                            TokType.IDENT, TokType.KEYWORD, TokType.TYPE, TokType.FUNC_CALL
                        )
                    ) {
                        val name = toks[1].text
                        if (name.isNotEmpty() && !name.startsWith("_MONO")) {
                            // Function-like macro: collect the parameter list.
                            var params = ""
                            if (toks.size > 2 && toks[2].text.startsWith("(")) {
                                val sb = StringBuilder()
                                var depth = 0
                                var j = 2
                                loop@ while (j < toks.size) {
                                    val t = toks[j]
                                    if (t.text.startsWith("(")) depth += t.text.count { it == '(' }
                                    if (t.text.contains(")")) {
                                        depth -= t.text.count { it == ')' }
                                        sb.append(t.text)
                                        if (depth <= 0) {
                                            params = sb.toString()
                                            break@loop
                                        }
                                        j++
                                        continue
                                    }
                                    sb.append(t.text)
                                    j++
                                }
                            }
                            out.add(Symbol(name, SymKind.MACRO, "#define $name$params", line = lineIdx))
                        }
                    }
                }
                continue
            }

            var sawBrace = false
            while (i < toks.size) {
                val t = toks[i]
                // A pending function body must open with '{' as the first
                // significant (non-comment) token of the line; anything
                // else cancels it. The brace branch below consumes it.
                if (pendingFunction != null && t.type != TokType.COMMENT && t.text != "{") {
                    pendingFunction = null
                }
                // Inside a function body nothing is emitted and no container
                // is opened — only the braces themselves are tracked.
                val inFunctionBody = containers.lastOrNull()?.isFunction == true

                when {
                    t.text == "{" -> {
                        depth++
                        sawBrace = true
                        pendingFunction?.let { name ->
                            containers.addLast(Container(name, depth - 1, SymKind.FUNCTION, isFunction = true))
                        }
                        pendingFunction = null
                        i++
                    }

                    t.text == "}" -> {
                        depth--
                        val c = containers.lastOrNull()
                        if (c != null && depth <= c.depth) containers.removeLast()
                        i++
                    }

                    inFunctionBody -> i++

                    t.text == ";" -> i++

                    // class / struct / enum [class|struct] / union NAME ... {
                    t.type == TokType.KEYWORD && t.text in setOf("class", "struct", "union") -> {
                        var j = i + 1
                        if (j < toks.size && toks[j].text == "struct" || j < toks.size && toks[j].text == "class") j++
                        // Skip attributes / alignas / final etc. up to the name.
                        var name: String? = null
                        while (j < toks.size && toks[j].text != "{" && toks[j].text != ";") {
                            if (toks[j].type == TokType.IDENT && name == null &&
                                toks[j].text !in MODIFIER_WORDS
                            ) name = toks[j].text
                            j++
                        }
                        if (name != null && j < toks.size && toks[j].text == "{") {
                            val kind = when (t.text) {
                                "class" -> SymKind.CLASS
                                "union" -> SymKind.UNION
                                else -> SymKind.STRUCT
                            }
                            out.add(Symbol(name, kind, "${t.text} $name", container = containers.lastOrNull()?.name ?: "", line = lineIdx))
                            containers.addLast(Container(name, depth, kind))
                        }
                        i = j
                    }

                    t.type == TokType.KEYWORD && t.text == "enum" -> {
                        var j = i + 1
                        if (j < toks.size && (toks[j].text == "class" || toks[j].text == "struct")) j++
                        var name: String? = null
                        while (j < toks.size && toks[j].text != "{" && toks[j].text != ";") {
                            if (toks[j].type == TokType.IDENT && name == null &&
                                toks[j].text !in MODIFIER_WORDS
                            ) name = toks[j].text
                            j++
                        }
                        if (name != null && j < toks.size && toks[j].text == "{") {
                            out.add(Symbol(name, SymKind.ENUM, "enum $name", container = containers.lastOrNull()?.name ?: "", line = lineIdx))
                            containers.addLast(Container(name, depth, SymKind.ENUM))
                        }
                        i = j
                    }

                    t.type == TokType.KEYWORD && t.text == "namespace" -> {
                        var j = i + 1
                        var name: String? = null
                        if (j < toks.size && toks[j].type == TokType.IDENT) {
                            name = toks[j].text; j++
                        }
                        if (name != null && j < toks.size && toks[j].text == "{") {
                            out.add(Symbol(name, SymKind.NAMESPACE, "namespace $name", line = lineIdx))
                            containers.addLast(Container(name, depth, SymKind.NAMESPACE))
                        }
                        i = j
                    }

                    t.type == TokType.KEYWORD && t.text == "typedef" -> {
                        // typedef ... NAME;
                        var j = i + 1
                        var lastIdent: String? = null
                        while (j < toks.size && toks[j].text != ";" && toks[j].text != "{") {
                            if (toks[j].type == TokType.IDENT) lastIdent = toks[j].text
                            j++
                        }
                        if (lastIdent != null && j < toks.size && toks[j].text == ";") {
                            out.add(Symbol(lastIdent, SymKind.TYPEDEF, "typedef ... $lastIdent", line = lineIdx))
                        }
                        i = j
                    }

                    // Name followed by '(' — function, method or constructor.
                    // The paren must directly follow the name: without that
                    // check, "void Foo::bar()" would match "Foo" (scanning
                    // through "::" to bar's parens) instead of "bar".
                    (t.type == TokType.IDENT || t.type == TokType.FUNC_CALL) &&
                        toks.getOrNull(i + 1)?.text == "(" && looksLikeDefinition(toks, i) -> {
                        val close = findMatchingParen(toks, i + 1)
                        if (close >= 0) {
                            val container = containers.lastOrNull()
                            val classContainer = container?.takeIf { it.kind != SymKind.NAMESPACE }
                            // Out-of-class definition (Foo::bar): recover
                            // the qualifier as the container.
                            val qualifier = classContainer?.name
                                ?: (toks.getOrNull(i - 1)?.takeIf { it.text == "::" }
                                    ?.let { toks.getOrNull(i - 2)?.text } ?: "")
                            val isCtor = classContainer != null && t.text == classContainer.name
                            // Trailing specifiers: const, noexcept, override ...
                            var k = close + 1
                            val tail = StringBuilder()
                            while (k < toks.size && toks[k].text != "{" && toks[k].text != ";" &&
                                toks[k].text != "}" && k - close < 8
                            ) {
                                tail.append(toks[k].text).append(' ')
                                k++
                            }
                            val params = joinText(toks, i + 1, close)
                            val kind = if (qualifier.isNotEmpty()) SymKind.METHOD else SymKind.FUNCTION
                            val sig = buildString {
                                if (!isCtor) {
                                    append(returnTypeBefore(toks, i))
                                    append(' ')
                                }
                                append(t.text).append(params)
                                if (tail.isNotEmpty()) append(' ').append(tail.toString().trim())
                            }
                            out.add(
                                Symbol(
                                    t.text, kind, sig.trim(),
                                    container = qualifier,
                                    line = lineIdx
                                )
                            )
                            if (k < toks.size && toks[k].text == "{") {
                                // Definition with its body opening on this line.
                                containers.addLast(Container(t.text, depth, SymKind.FUNCTION, isFunction = true))
                            } else if (k >= toks.size) {
                                // Neither ';' nor '{' before end of line: the
                                // body may open on the next line.
                                pendingFunction = t.text
                            }
                            i = close + 1
                        } else i++
                    }

                    // Type + name at class scope (member) or global scope (variable).
                    (t.type == TokType.IDENT || t.type == TokType.TYPE || t.type == TokType.KEYWORD) &&
                        i + 1 < toks.size && toks[i + 1].type == TokType.IDENT &&
                        t.text !in FLOW_KEYWORDS && t.text !in MODIFIER_WORDS &&
                        (toks.getOrNull(i + 2)?.text == ";" || toks.getOrNull(i + 2)?.text == "=" ||
                            toks.getOrNull(i + 2)?.text == "(") -> {
                        val nameTok = toks[i + 1]
                        val container = containers.lastOrNull()
                        val kind = if (container != null && container.kind != SymKind.NAMESPACE) SymKind.MEMBER
                        else SymKind.VARIABLE
                        out.add(
                            Symbol(
                                nameTok.text, kind, "${t.text} ${nameTok.text}",
                                container = container?.takeIf { it.kind != SymKind.NAMESPACE }?.name ?: "",
                                line = lineIdx
                            )
                        )
                        i += 2
                    }

                    else -> i++
                }
            }
            if (!sawBrace) { /* nothing */ }
        }
        return out
    }

    private class Container(
        val name: String,
        val depth: Int,
        val kind: SymKind,
        val isFunction: Boolean = false
    )

    private val MODIFIER_WORDS = setOf(
        "public", "private", "protected", "virtual", "static", "const",
        "constexpr", "inline", "explicit", "final", "override", "noexcept",
        "friend", "typedef", "unsigned", "signed", "long", "short", "typename",
        "template", "using", "namespace", "struct", "class", "enum", "union",
        "operator", "new", "delete", "this", "return", "if", "for", "while",
        "switch", "case", "do", "else", "alignas", "thread_local", "mutable"
    )

    private val FLOW_KEYWORDS = setOf(
        "if", "for", "while", "switch", "catch", "return", "sizeof", "else",
        "do", "case", "static_assert", "decltype", "using", "operator"
    )

    /** Definition-ish: identifier + '(' where the previous token is a type/keyword/':'/'}' or it is a constructor. */
    private fun looksLikeDefinition(toks: List<Tok>, i: Int): Boolean {
        if (i == 0) return false
        val prev = toks[i - 1]
        if (prev.type == TokType.KEYWORD) return prev.text !in FLOW_KEYWORDS && prev.text != "return"
        if (prev.type == TokType.TYPE || prev.type == TokType.IDENT) return true
        // ":" covers constructor initializers and access specifiers, "::"
        // covers out-of-class method definitions (Foo::bar).
        if (prev.text == ":" || prev.text == "::" || prev.text == ">" || prev.text == "*" || prev.text == "&") return true
        return false
    }

    private fun findMatchingParen(toks: List<Tok>, open: Int): Int {
        var d = 0
        var j = open
        while (j < toks.size) {
            when (toks[j].text) {
                "(" -> d++
                ")" -> { d--; if (d == 0) return j }
                ";" -> return -1
                "{" -> return -1
            }
            j++
        }
        return -1
    }

    private fun joinText(toks: List<Tok>, from: Int, to: Int): String =
        buildString {
            append('(')
            for (k in from + 1 until to) append(toks[k].text)
            append(')')
        }

    private fun returnTypeBefore(toks: List<Tok>, nameIdx: Int): String {
        val parts = ArrayList<String>()
        var k = nameIdx - 1
        while (k >= 0) {
            val t = toks[k]
            if (t.type == TokType.IDENT || t.type == TokType.TYPE || t.type == TokType.KEYWORD ||
                t.text == "*" || t.text == "&" || t.text == "::"
            ) {
                if (t.type == TokType.KEYWORD && t.text in FLOW_KEYWORDS) break
                parts.add(t.text)
                k--
            } else break
        }
        return parts.asReversed().joinToString(" ")
    }
}
