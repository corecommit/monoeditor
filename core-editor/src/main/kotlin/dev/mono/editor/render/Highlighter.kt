package dev.mono.editor.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.mono.editor.state.EditorState
import dev.mono.indexer.lexer.Tok
import dev.mono.indexer.lexer.TokType

/**
 * Builds the styled [AnnotatedString] for one line from its tokens.
 *
 * Colors come from the theme's shade system; this class is stateless per
 * call and cheap enough to run for the ~50 visible lines on each font or
 * theme change.
 */
object Highlighter {

    fun annotatedFor(state: EditorState, theme: MonoTheme, line: Int): AnnotatedString {
        val text = state.text
        if (line < 0 || line >= text.lineCount) return AnnotatedString("")
        val raw = text.line(line)
        val toks = state.tokenCache.tokensFor(text, line)
        val s = theme.syntax
        return buildAnnotatedString {
            for (t in toks) {
                if (t.length == 0) continue
                val style = styleFor(t, s)
                if (style == null) {
                    // WHITESPACE / unclassified: plain
                    append(raw.substring(t.start.coerceIn(0, raw.length), t.end.coerceAtMost(raw.length)))
                } else {
                    addStyle(style, t.start.coerceIn(0, raw.length), t.end.coerceAtMost(raw.length))
                    append(raw.substring(t.start.coerceIn(0, raw.length), t.end.coerceAtMost(raw.length)))
                }
            }
            // Trailing text not covered by tokens (should not happen; be safe).
            val covered = toks.lastOrNull()?.end ?: 0
            if (covered < raw.length) append(raw.substring(covered))
        }
    }

    private fun styleFor(t: Tok, s: MonoTheme.SyntaxShades): SpanStyle? = when (t.type) {
        TokType.KEYWORD -> SpanStyle(color = s.keyword, fontWeight = if (s.keywordBold) FontWeight.Bold else null)
        TokType.TYPE -> SpanStyle(color = s.type, fontWeight = if (s.typeBold) FontWeight.Bold else null)
        TokType.PREPROC -> SpanStyle(color = s.preprocessor, fontStyle = if (s.preprocessorItalic) FontStyle.Italic else null)
        TokType.STRING -> SpanStyle(color = s.string)
        TokType.NUMBER -> SpanStyle(color = s.number)
        TokType.COMMENT -> SpanStyle(color = s.comment, fontStyle = if (s.commentItalic) FontStyle.Italic else null)
        TokType.FUNC_CALL -> SpanStyle(color = s.function, fontWeight = if (s.functionBold) FontWeight.Bold else null)
        TokType.PUNCT -> SpanStyle(color = s.punctuation)
        TokType.IDENT -> SpanStyle(color = s.identifier)
        else -> null
    }
}
