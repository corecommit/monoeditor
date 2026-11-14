package dev.mono.indexer.snippet

import dev.mono.indexer.symbols.CompletionItem
import dev.mono.indexer.symbols.SymKind

/**
 * A text snippet. The body may contain the [CARET] marker (`$0`); the editor
 * places the caret there after expansion and removes all other markers.
 */
data class Snippet(
    val trigger: String,
    val title: String,
    val body: String
) {
    companion object {
        const val CARET = "$0"
    }
}

/**
 * Built-in snippets. Triggered by typing a short prefix and picking the
 * snippet row in the completion popup.
 */
object Snippets {

    val all: List<Snippet> = listOf(
        Snippet(
            "main", "main function",
            "int main() {\n    \$0\n    return 0;\n}"
        ),
        Snippet(
            "for", "for loop",
            "for (int i = 0; i < \$0; ++i) {\n    \n}"
        ),
        Snippet(
            "forr", "range-based for",
            "for (auto& \$0item : items) {\n    \n}"
        ),
        Snippet(
            "while", "while loop",
            "while (\$0condition) {\n    \n}"
        ),
        Snippet(
            "if", "if statement",
            "if (\$0condition) {\n    \n}"
        ),
        Snippet(
            "ife", "if / else",
            "if (\$0condition) {\n    \n} else {\n    \n}"
        ),
        Snippet(
            "struct", "struct definition",
            "struct \$0Name {\n    \n};"
        ),
        Snippet(
            "class", "class definition",
            "class \$0Name {\npublic:\n    \nprivate:\n    \n};"
        ),
        Snippet(
            "enum", "enum class",
            "enum class \$0Name {\n    \n};"
        ),
        Snippet(
            "cout", "print to stdout",
            "std::cout << \$0 << std::endl;"
        ),
        Snippet(
            "inc", "angle include",
            "#include <\$0>"
        ),
        Snippet(
            "incq", "quoted include",
            "#include \"\$0\""
        ),
        Snippet(
            "def", "macro definition",
            "#define \$0NAME value"
        ),
        Snippet(
            "guard", "include guard",
            "#pragma once\n\$0"
        ),
        Snippet(
            "cls", "clear screen",
            "printf(\"\\033[2J\\033[H\");\$0"
        )
    )

    private val byTrigger = all.associateBy { it.trigger }

    fun byTrigger(trigger: String): Snippet? = byTrigger[trigger]

    fun completionItems(): List<CompletionItem> = all.map {
        CompletionItem(
            label = it.trigger,
            kind = SymKind.SNIPPET,
            detail = it.title,
            module = "snippet",
            insert = it.body,
            snippet = it
        )
    }
}
