package dev.mono.editor.render

import androidx.compose.ui.graphics.Color

/**
 * Mono's visual language: layered shades, never one solid black or white.
 * White (or near-white in dark mode / near-black in light mode) is reserved
 * for the active line and the caret. Syntax highlighting distinguishes token
 * categories only by brightness, weight and slant — no hues anywhere.
 */
class MonoTheme(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val border: Color,
    val gutterBackground: Color,
    val gutterText: Color,
    val gutterTextActive: Color,
    val lineHighlight: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val caret: Color,
    val selection: Color,
    val selectionHandle: Color,
    val findMatch: Color,
    val findMatchActive: Color,
    val diagnosticError: Color,
    val diagnosticWarning: Color,
    val scrollbar: Color,
    val syntax: SyntaxShades
) {
    class SyntaxShades(
        val keyword: Color,
        val keywordBold: Boolean,
        val type: Color,
        val typeBold: Boolean,
        val preprocessor: Color,
        val preprocessorItalic: Boolean,
        val string: Color,
        val number: Color,
        val comment: Color,
        val commentItalic: Boolean,
        val function: Color,
        val functionBold: Boolean,
        val punctuation: Color,
        val identifier: Color
    )

    /** Bumped whenever a new theme instance is applied; invalidates caches. */
    val generation: Long = ++gen

    companion object {
        private var gen = 0L
    }
}

/** The two shade systems Mono ships: layered dark (default) and light. */
object MonoThemes {
    val dark: MonoTheme = MonoTheme(
            background = Color(0xFF0A0A0B),
            surface = Color(0xFF111113),
            surfaceHigh = Color(0xFF18181B),
            border = Color(0xFF242429),
            gutterBackground = Color(0xFF0D0D0F),
            gutterText = Color(0xFF4A4A52),
            gutterTextActive = Color(0xFFFAFAFA),
            lineHighlight = Color(0x08FFFFFF),
            textPrimary = Color(0xFFD4D4D8),
            textSecondary = Color(0xFF71717A),
            textTertiary = Color(0xFF52525B),
            accent = Color(0xFFFAFAFA),
            caret = Color(0xFFFAFAFA),
            selection = Color(0x1FFFFFFF),
            selectionHandle = Color(0xFFFAFAFA),
            findMatch = Color(0x14FFFFFF),
            findMatchActive = Color(0x2EFFFFFF),
            diagnosticError = Color(0xB3FAFAFA),
            diagnosticWarning = Color(0x66FAFAFA),
            scrollbar = Color(0x14FFFFFF),
            syntax = MonoTheme.SyntaxShades(
                keyword = Color(0xFFFAFAFA), keywordBold = true,
                type = Color(0xFFE4E4E7), typeBold = false,
                preprocessor = Color(0xFFDADADD), preprocessorItalic = true,
                string = Color(0xFF9C9CA4),
                number = Color(0xFF9C9CA4),
                comment = Color(0xFF565661), commentItalic = true,
                function = Color(0xFFEDEDF0), functionBold = true,
                punctuation = Color(0xFF6E6E76),
                identifier = Color(0xFFC4C4CA)
            )
        )

    val light: MonoTheme = MonoTheme(
            background = Color(0xFFE9E9E6),
            surface = Color(0xFFF1F1EF),
            surfaceHigh = Color(0xFFF7F7F5),
            border = Color(0xFFD4D4D0),
            gutterBackground = Color(0xFFE4E4E1),
            gutterText = Color(0xFF9A9A94),
            gutterTextActive = Color(0xFF111113),
            lineHighlight = Color(0x0A000000),
            textPrimary = Color(0xFF26262B),
            textSecondary = Color(0xFF6B6B70),
            textTertiary = Color(0xFF8E8E93),
            accent = Color(0xFF111113),
            caret = Color(0xFF111113),
            selection = Color(0x1F000000),
            selectionHandle = Color(0xFF111113),
            findMatch = Color(0x14000000),
            findMatchActive = Color(0x2B000000),
            diagnosticError = Color(0xB3111113),
            diagnosticWarning = Color(0x66111113),
            scrollbar = Color(0x14000000),
            syntax = MonoTheme.SyntaxShades(
                keyword = Color(0xFF111113), keywordBold = true,
                type = Color(0xFF2E2E33), typeBold = false,
                preprocessor = Color(0xFF3A3A40), preprocessorItalic = true,
                string = Color(0xFF5E5E66),
                number = Color(0xFF5E5E66),
                comment = Color(0xFF8E8E96), commentItalic = true,
                function = Color(0xFF1E1E22), functionBold = true,
                punctuation = Color(0xFF77777D),
                identifier = Color(0xFF35353B)
            )
        )
}