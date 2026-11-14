package dev.mono.editor.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mono.editor.text.EditorText
import dev.mono.indexer.lexer.CppLexer
import dev.mono.indexer.lexer.LexerState
import dev.mono.indexer.lexer.Tok

/** A caret or selection position. */
data class Pos(val line: Int, val col: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos): Int {
        val l = line.compareTo(other.line)
        return if (l != 0) l else col.compareTo(other.col)
    }
}

/** One search match. */
data class Match(val line: Int, val startCol: Int, val endCol: Int)

/**
 * A diagnostic rendered in the editor: a faint underline on the offending
 * token plus the message shown when tapped.
 */
data class EditorDiagnostic(
    val line: Int,
    val colStart: Int,
    val colEnd: Int,
    val isError: Boolean,
    val message: String,
    val file: String = ""
)

/**
 * Theme-independent token cache: per-line tokens plus the lexer state at
 * each line start. Recomputes lazily from the first dirty line forward and
 * stops as soon as the carry-over state stabilizes (so a normal edit
 * re-lexes exactly one line).
 */
class TokenCache {
    private var tokens: Array<List<Tok>?> = arrayOfNulls(0)
    private var states: Array<LexerState?> = arrayOfNulls(0)
    /** First line whose cached data may be stale. */
    private var dirtyFrom = 0

    fun reset() {
        tokens = arrayOfNulls(0)
        states = arrayOfNulls(0)
        dirtyFrom = 0
    }

    fun invalidateFrom(line: Int) {
        dirtyFrom = minOf(dirtyFrom, line.coerceAtLeast(0))
    }

    internal fun ensureSize(lineCount: Int) {
        if (states.size < lineCount) {
            tokens = tokens.copyOf(lineCount)
            states = states.copyOf(lineCount)
        }
    }

    /**
     * Lexes [line], using cached results below it and re-lexing forward only
     * until the carry-over state matches a still-valid cached state.
     */
    fun tokensFor(text: EditorText, line: Int): List<Tok> {
        if (line < 0 || line >= text.lineCount) return emptyList()
        ensureSize(text.lineCount)
        if (line < dirtyFrom) {
            tokens[line]?.let { return it }
        }

        // Find the nearest line at-or-before `line` with a valid start state.
        var i = line
        while (i > 0 && (i >= dirtyFrom || states[i] == null)) i--
        var carry: LexerState = if (i == 0) LexerState.PLAIN else states[i]!!
        while (i < line) {
            val r = CppLexer.lexLine(text.line(i), carry)
            tokens[i] = r.first
            states[i] = carry
            carry = r.second
            i++
        }

        val r = CppLexer.lexLine(text.line(line), carry)
        tokens[line] = r.first
        states[line] = carry

        // Propagate the edit forward until the state chain re-syncs.
        var j = line + 1
        var next = r.second
        while (j < text.lineCount) {
            val cached = states.getOrNull(j)
            if (cached != null && cached == next) break
            val rr = CppLexer.lexLine(text.line(j), next)
            tokens[j] = rr.first
            states[j] = next
            next = rr.second
            j++
        }
        dirtyFrom = j
        return r.first
    }

    /** All token lines (for the symbol parser). */
    fun allTokens(text: EditorText): List<List<Tok>> {
        val out = ArrayList<List<Tok>>(text.lineCount)
        for (i in 0 until text.lineCount) out.add(tokensFor(text, i))
        return out
    }
}

/**
 * The single source of truth for everything the custom canvas editor draws.
 * Plain Compose state fields; the view reads them and invalidates precisely.
 */
class EditorState(initialText: String = "") {

    val text = EditorText(initialText)
    val tokenCache = TokenCache()

    /** Bumped on every content change. */
    var textRevision by mutableLongStateOf(0L)
        private set

    var caretLine by mutableIntStateOf(0)
    var caretCol by mutableIntStateOf(0)

    /** Selection anchor; -1 means no selection. */
    var anchorLine by mutableIntStateOf(-1)
    var anchorCol by mutableIntStateOf(-1)

    var scrollX by mutableFloatStateOf(0f)
    var scrollY by mutableFloatStateOf(0f)

    var fontSizeSp by mutableFloatStateOf(13f)

    var indentWidth: Int = 4

    // Viewport, set by the composable.
    var viewportWidthPx by mutableFloatStateOf(1f)
    var viewportHeightPx by mutableFloatStateOf(1f)
    var gutterWidthPx by mutableFloatStateOf(64f)
    var lineHeightPx by mutableFloatStateOf(48f)
    var contentMaxWidthPx by mutableFloatStateOf(0f)

    // Diagnostics (mapped from compiler output or include resolution).
    var diagnostics by mutableStateOf<List<EditorDiagnostic>>(emptyList())

    // Find / replace.
    var findQuery by mutableStateOf("")
    var findRegex by mutableStateOf(false)
    var findCaseSensitive by mutableStateOf(true)
    var findMatches by mutableStateOf<List<Match>>(emptyList())
    var findIndex by mutableIntStateOf(-1)

    // Completion.
    var completionItems by mutableStateOf<List<dev.mono.indexer.symbols.CompletionItem>>(emptyList())
    var completionSelected by mutableIntStateOf(0)
    var completionVisible by mutableStateOf(false)

    // Selection handles.
    var showHandles by mutableStateOf(false)

    // IME.
    var imeVisible by mutableStateOf(false)
    var hasFocus by mutableStateOf(false)
    var requestIme: ((Boolean) -> Unit)? = null

    // Bracket match pair (positions of both brackets).
    var bracketMatch by mutableStateOf<Pair<Pos, Pos>?>(null)

    // Diagnostics chip: message of the most recently tapped diagnostic.
    var diagnosticChip by mutableStateOf<EditorDiagnostic?>(null)

    // External change banner.
    var externalChangePending by mutableStateOf(false)

    var desiredCol: Int = 0

    fun setCaret(line: Int, col: Int) {
        val l = line.coerceIn(0, text.lineCount - 1)
        val c = col.coerceIn(0, text.lineLength(l))
        caretLine = l
        caretCol = c
        desiredCol = c
    }

    fun clearSelection() {
        if (anchorLine != -1) {
            anchorLine = -1
            anchorCol = -1
        }
        showHandles = false
    }

    val hasSelection: Boolean get() = anchorLine != -1

    /** Ordered selection bounds (start <= end). */
    fun selectionBounds(): Pair<Pos, Pos>? {
        if (anchorLine == -1) return null
        val a = Pos(anchorLine, anchorCol)
        val b = Pos(caretLine, caretCol)
        return if (a <= b) a to b else b to a
    }

    fun selectedText(): String {
        val b = selectionBounds() ?: return ""
        return text.region(b.first.line, b.first.col, b.second.line, b.second.col)
    }

    fun setCompletion(items: List<dev.mono.indexer.symbols.CompletionItem>) {
        completionItems = items
        completionSelected = 0
        completionVisible = items.isNotEmpty()
    }

    fun hideCompletion() {
        if (completionVisible) {
            completionVisible = false
        }
    }

    fun selectionLength(): Int {
        val b = selectionBounds() ?: return 0
        var n = 0
        if (b.first.line == b.second.line) {
            n = b.second.col - b.first.col
        } else {
            n = text.lineLength(b.first.line) - b.first.col + 1
            for (l in b.first.line + 1 until b.second.line) n += text.lineLength(l) + 1
            n += b.second.col
        }
        return n
    }

    /**
     * Called by the controller after any structural change. [fromLine] is
     * the first line the edit could have touched; the visual cache uses it
     * to re-measure only the affected lines.
     */
    fun onDocumentStructureChanged(fromLine: Int) {
        textRevision++
        structureDirtyFrom = minOf(structureDirtyFrom, fromLine.coerceAtLeast(0))
        tokenCache.ensureSize(text.lineCount)
        tokenCache.invalidateFrom(fromLine)
    }

    /**
     * One-shot: the lowest line index changed since the last call. The first
     * consumer after an edit (the visual line cache) applies the range and
     * resets the marker; later consumers see "nothing pending".
     */
    fun consumeStructureDirtyFrom(): Int {
        val v = structureDirtyFrom
        structureDirtyFrom = Int.MAX_VALUE
        return v
    }

    /** Min line index touched by edits not yet applied to the visual cache. */
    private var structureDirtyFrom = Int.MAX_VALUE

    fun maxScrollX(): Float = (contentMaxWidthPx - (viewportWidthPx - gutterWidthPx)).coerceAtLeast(0f)

    fun maxScrollY(): Float = (text.lineCount * lineHeightPx - viewportHeightPx).coerceAtLeast(0f)
}
