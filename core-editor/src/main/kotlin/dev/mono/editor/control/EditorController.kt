package dev.mono.editor.control

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.mono.editor.edit.EditorOps
import dev.mono.editor.state.EditorDiagnostic
import dev.mono.editor.state.EditorState
import dev.mono.editor.state.Match
import dev.mono.editor.state.Pos
import dev.mono.editor.text.UndoStack
import dev.mono.indexer.snippet.Snippet
import dev.mono.indexer.symbols.CompletionItem

/** Clipboard abstraction so the controller stays testable without Compose. */
interface ClipboardAccess {
    fun getText(): String?
    fun setText(s: String)
}

/**
 * All editing operations for one [EditorState]: typing with bracket pairing
 * and auto-indent, selection, clipboard, undo/redo, find/replace, snippet
 * expansion and completion navigation.
 */
class EditorController(
    val state: EditorState,
    val clipboard: ClipboardAccess,
    private val onEdited: (EditorController) -> Unit = {}
) {

    val undoStack = UndoStack()

    /** Bumped whenever the view should scroll the caret into view. */
    var scrollRequest by mutableIntStateOf(0)
        private set

    /**
     * Notified with the identifier prefix at the caret after every edit (or
     * caret move). [memberAccess] is true when the prefix is preceded by
     * ".", "->" or "::", in which case completion is offered even with an
     * empty prefix.
     */
    var completionQueryListener: ((prefix: String, memberAccess: Boolean) -> Unit)? = null

    /** Notified when the user asks for the find bar from the keyboard. */
    var findRequestListener: (() -> Unit)? = null

    // ------------------------------------------------------------- lifecycle

    /** Replaces the whole document (tab switch restore): caret, selection and undo reset. */
    fun loadText(newText: String) {
        val t = state.text
        t.delete(0, 0, t.lineCount - 1, t.lineLength(t.lineCount - 1))
        t.insert(0, 0, newText)
        state.setCaret(0, 0)
        state.clearSelection()
        undoStack.clear()
        state.tokenCache.reset()
        state.onDocumentStructureChanged(0)
        onEdited(this)
    }

    /**
     * Replaces the document after an external on-disk change while keeping
     * the caret (clamped into the new text), the scroll position and the
     * undo history — the swap itself is recorded as a single undoable
     * COMPOSITE step, so an unwanted reload can still be undone.
     */
    fun reloadExternal(newText: String) {
        val t = state.text
        val before = snapshot()
        val hadSelection = state.anchorLine >= 0
        val lastLine = t.lineCount - 1
        val removed = t.delete(0, 0, lastLine, t.lineLength(lastLine))
        t.insert(0, 0, newText)
        // Clamp the previous caret and anchor into the new document.
        state.setCaret(before.caretLine, before.caretCol)
        if (hadSelection) {
            state.anchorLine = before.anchorLine.coerceIn(0, t.lineCount - 1)
            state.anchorCol = before.anchorCol.coerceIn(0, t.lineLength(state.anchorLine))
        } else {
            state.clearSelection()
        }
        state.scrollY = state.scrollY.coerceIn(0f, state.maxScrollY())
        state.scrollX = state.scrollX.coerceIn(0f, state.maxScrollX())
        state.hideCompletion()
        undoStack.push(
            UndoStack.Command(
                UndoStack.Type.COMPOSITE, 0, 0, "", before, snapshot(),
                children = listOf(
                    UndoStack.Command(UndoStack.Type.DELETE, 0, 0, removed, before, before),
                    UndoStack.Command(UndoStack.Type.INSERT, 0, 0, newText, before, before)
                )
            )
        )
        state.tokenCache.reset()
        state.onDocumentStructureChanged(0)
        refreshBracketMatch()
        onEdited(this)
    }

    fun getText(): String = state.text.text()

    // -------------------------------------------------------------- typing

    /** General typing path with bracket pairing and selection wrapping. */
    fun inputText(s: String) {
        if (s.isEmpty()) return
        if (s == "\n") {
            enter()
            return
        }

        // Typing an opening pair with a selection wraps the selection.
        if (s.length == 1 && s[0] in EditorOps.AUTO_PAIRS && state.hasSelection) {
            wrapSelection(s[0])
            return
        }

        // Typing the closing char when it is already at the caret: step over.
        if (s.length == 1 && (s[0] in EditorOps.CLOSE_BRACKETS || s[0] in EditorOps.QUOTES)) {
            val next = EditorOps.nextChar(state.text, state.caretLine, state.caretCol)
            if (next == s[0]) {
                state.clearSelection()
                state.setCaret(state.caretLine, state.caretCol + 1)
                requestScroll()
                return
            }
        }

        if (state.hasSelection) deleteSelection()

        // Auto-pair.
        if (s.length == 1 && s[0] in EditorOps.AUTO_PAIRS) {
            val close = EditorOps.OPEN_BRACKETS[s[0]] ?: s[0]
            val next = EditorOps.nextChar(state.text, state.caretLine, state.caretCol)
            val shouldPair = if (s[0] in EditorOps.QUOTES) {
                next == null || next == ' ' || next == '\t' || next in EditorOps.PUNCT_BOUNDARY
            } else true
            if (shouldPair) {
                insertWithUndo(s + close, caretOffsetBack = 1)
                return
            }
        }

        insertWithUndo(s)
    }

    /** Raw insert used by the symbol row. */
    fun inputSymbol(s: String) {
        if (s.isEmpty()) return
        if (state.hasSelection) deleteSelection()
        insertWithUndo(s, coalesce = false)
    }

    /**
     * Unrecorded raw insert used by IME composition drafts: no auto-pairing,
     * no undo command. The region is either replaced by the next
     * setComposingText (deleted unrecorded) or finalized by
     * [recordAppliedInsert] / commitText, which records exactly one command.
     */
    fun insertRaw(s: String) {
        if (s.isEmpty()) return
        if (state.hasSelection) deleteSelection()
        val line = state.caretLine
        val col = state.caretCol
        state.text.insert(line, col, s)
        val end = state.text.spanEnd(line, col, s)
        state.setCaret(end.first, end.second)
        state.onDocumentStructureChanged(line)
    }

    /**
     * Records an insert that has already been applied to the buffer (an IME
     * composition finalized as-is) as one undoable step.
     */
    fun recordAppliedInsert(line: Int, col: Int, s: String) {
        if (s.isEmpty()) return
        undoStack.push(
            UndoStack.Command(
                UndoStack.Type.INSERT, line, col, s,
                UndoStack.Snapshot(line, col, -1, -1), snapshot(),
                run = undoStack.newRun(), atMs = System.currentTimeMillis()
            )
        )
        afterEdit(line)
    }

    fun enter() {
        if (state.hasSelection) deleteSelection()
        val line = state.caretLine
        val indent = EditorOps.nextIndent(state.text, line, state.indentWidth)
        insertWithUndo("\n" + indent, coalesce = false)
    }

    fun backspace() {
        if (state.hasSelection) {
            deleteSelection()
            return
        }
        val line = state.caretLine
        val col = state.caretCol
        if (line == 0 && col == 0) return
        val t = state.text
        val before = snapshot()
        // Empty pair between caret: remove both.
        if (EditorOps.betweenEmptyPair(t, line, col)) {
            val removed = t.delete(line, col - 1, line, col + 1)
            state.setCaret(line, col - 1)
            record(UndoStack.Command(UndoStack.Type.DELETE, line, col - 1, removed, before, snapshot()))
            afterEdit(line)
            return
        }
        if (col > 0) {
            val removed = t.delete(line, col - 1, line, col)
            state.setCaret(line, col - 1)
            record(UndoStack.Command(UndoStack.Type.DELETE, line, col - 1, removed, before, snapshot()))
            afterEdit(line)
        } else {
            val prevLen = t.lineLength(line - 1)
            val removed = t.delete(line - 1, prevLen, line, 0)
            state.setCaret(line - 1, prevLen)
            record(UndoStack.Command(UndoStack.Type.DELETE, line - 1, prevLen, removed, before, snapshot()))
            afterEdit(line - 1)
        }
    }

    fun deleteForward() {
        if (state.hasSelection) {
            deleteSelection()
            return
        }
        val t = state.text
        val line = state.caretLine
        val col = state.caretCol
        val before = snapshot()
        if (col < t.lineLength(line)) {
            val removed = t.delete(line, col, line, col + 1)
            record(UndoStack.Command(UndoStack.Type.DELETE, line, col, removed, before, snapshot()))
            afterEdit(line)
        } else if (line < t.lineCount - 1) {
            val removed = t.delete(line, col, line + 1, 0)
            record(UndoStack.Command(UndoStack.Type.DELETE, line, col, removed, before, snapshot()))
            afterEdit(line)
        }
    }

    fun tab() {
        if (state.hasSelection) deleteSelection()
        insertWithUndo(" ".repeat(state.indentWidth), coalesce = false)
    }

    // ------------------------------------------------------------ caret move

    fun moveCaret(line: Int, col: Int, extend: Boolean = false) {
        if (extend) {
            if (!state.hasSelection) {
                state.anchorLine = state.caretLine
                state.anchorCol = state.caretCol
            }
            state.setCaret(line, col)
        } else {
            state.clearSelection()
            state.setCaret(line, col)
        }
        requestScroll()
        refreshBracketMatch()
        updateCompletionPrefix()
    }

    fun moveCaretRelative(dLine: Int, dCol: Int, extend: Boolean = false) {
        if (state.completionVisible && dLine != 0) {
            moveCompletionSelection(dLine)
            return
        }
        val newLine = (state.caretLine + dLine).coerceIn(0, state.text.lineCount - 1)
        if (dLine == 0) {
            moveCaret(newLine, state.caretCol + dCol, extend)
        } else {
            val target = state.desiredCol
            moveCaret(newLine, target.coerceAtMost(state.text.lineLength(newLine)), extend)
        }
    }

    fun beginSelectionAt(line: Int, col: Int, word: Boolean) {
        if (word) {
            val (s, e) = EditorOps.wordAround(state.text, line, col)
            state.anchorLine = line
            state.anchorCol = s
            state.setCaret(line, e)
        } else {
            state.anchorLine = line
            state.anchorCol = col
            state.setCaret(line, col)
        }
        state.showHandles = true
    }

    /** Selection handle drag: caret follows, anchor stays. */
    fun updateSelectionTo(line: Int, col: Int) {
        state.setCaret(line, col)
    }

    fun selectAll() {
        val last = state.text.lineCount - 1
        state.anchorLine = 0
        state.anchorCol = 0
        state.setCaret(last, state.text.lineLength(last))
        state.showHandles = true
    }

    fun clearSelection() {
        state.clearSelection()
    }

    // -------------------------------------------------------------- clipboard

    fun copy() {
        val sel = state.selectedText()
        if (sel.isNotEmpty()) clipboard.setText(sel)
    }

    fun cut() {
        val sel = state.selectedText()
        if (sel.isNotEmpty()) clipboard.setText(sel)
        if (state.hasSelection) deleteSelection()
    }

    fun paste() {
        val paste = clipboard.getText() ?: return
        if (paste.isEmpty()) return
        if (state.hasSelection) deleteSelection()
        insertWithUndo(paste, coalesce = false)
    }

    // -------------------------------------------------------------- undo/redo

    fun undo() {
        if (undoStack.undo(state.text, state)) {
            afterEdit(0)
        }
    }

    fun redo() {
        if (undoStack.redo(state.text, state)) {
            afterEdit(0)
        }
    }

    // ------------------------------------------------------------ find/replace

    fun find(query: String, regex: Boolean, caseSensitive: Boolean): Int {
        state.findQuery = query
        state.findRegex = regex
        state.findCaseSensitive = caseSensitive
        if (query.isEmpty()) {
            state.findMatches = emptyList()
            state.findIndex = -1
            return 0
        }
        val matches = ArrayList<Match>(64)
        val t = state.text
        if (regex) {
            val pattern = runCatching {
                Regex(if (caseSensitive) query else "(?i)$query")
            }.getOrNull()
            if (pattern != null) {
                for (l in 0 until t.lineCount) {
                    for (m in pattern.findAll(t.line(l))) {
                        matches.add(Match(l, m.range.first, m.range.last + 1))
                        if (matches.size > 5000) return finishFind(matches)
                    }
                }
            }
        } else {
            val needle = if (caseSensitive) query else query.lowercase()
            for (l in 0 until t.lineCount) {
                val raw = t.line(l)
                val hay = if (caseSensitive) raw else raw.lowercase()
                var idx = hay.indexOf(needle)
                while (idx >= 0) {
                    matches.add(Match(l, idx, idx + needle.length))
                    if (matches.size > 5000) return finishFind(matches)
                    idx = hay.indexOf(needle, idx + needle.length)
                }
            }
        }
        return finishFind(matches)
    }

    private fun finishFind(matches: List<Match>): Int {
        state.findMatches = matches
        state.findIndex = if (matches.isEmpty()) -1 else 0
        if (matches.isNotEmpty()) goToMatch(0)
        return matches.size
    }

    fun findNext(): Boolean {
        if (state.findMatches.isEmpty()) return false
        val i = (state.findIndex + 1).mod(state.findMatches.size)
        state.findIndex = i
        goToMatch(i)
        return true
    }

    fun findPrev(): Boolean {
        if (state.findMatches.isEmpty()) return false
        val i = (state.findIndex - 1).mod(state.findMatches.size)
        state.findIndex = i
        goToMatch(i)
        return true
    }

    fun replaceCurrent(replacement: String): Boolean {
        if (state.findMatches.isEmpty() || state.findIndex !in state.findMatches.indices) return false
        val m = state.findMatches[state.findIndex]
        val before = snapshot()
        val removed = state.text.delete(m.line, m.startCol, m.line, m.endCol)
        state.text.insert(m.line, m.startCol, replacement)
        state.setCaret(m.line, m.startCol + replacement.length)
        record(
            UndoStack.Command(
                UndoStack.Type.COMPOSITE, 0, 0, "", before, snapshot(),
                children = listOf(
                    UndoStack.Command(UndoStack.Type.DELETE, m.line, m.startCol, removed, before, before),
                    UndoStack.Command(UndoStack.Type.INSERT, m.line, m.startCol, replacement, before, before)
                )
            )
        )
        afterEdit(m.line)
        find(state.findQuery, state.findRegex, state.findCaseSensitive)
        return true
    }

    fun replaceAll(replacement: String): Int {
        if (state.findMatches.isEmpty()) return 0
        val before = snapshot()
        val children = ArrayList<UndoStack.Command>()
        var count = 0
        // Replace from the end so earlier positions stay valid.
        for (idx in state.findMatches.indices.reversed()) {
            val m = state.findMatches[idx]
            val removed = state.text.delete(m.line, m.startCol, m.line, m.endCol)
            state.text.insert(m.line, m.startCol, replacement)
            children.add(UndoStack.Command(UndoStack.Type.DELETE, m.line, m.startCol, removed, before, before))
            children.add(UndoStack.Command(UndoStack.Type.INSERT, m.line, m.startCol, replacement, before, before))
            count++
        }
        state.setCaret(0, 0)
        record(UndoStack.Command(UndoStack.Type.COMPOSITE, 0, 0, "", before, snapshot(), children = children))
        afterEdit(0)
        find(state.findQuery, state.findRegex, state.findCaseSensitive)
        return count
    }

    // ------------------------------------------------------------- completion

    fun acceptSelectedCompletion() {
        val item = state.completionItems.getOrNull(state.completionSelected) ?: return
        acceptCompletion(item)
    }

    fun acceptCompletion(item: CompletionItem) {
        val (start, prefix) = EditorOps.prefixBeforeCaret(state.text, state.caretLine, state.caretCol)
        state.hideCompletion()
        val line = state.caretLine
        val before = snapshot()
        val children = ArrayList<UndoStack.Command>()
        if (prefix.isNotEmpty()) {
            state.text.delete(line, start, line, start + prefix.length)
            // The caret must follow the text it was measuring: after the
            // delete it sits at the prefix start, where the insert lands.
            state.setCaret(line, start)
            children.add(UndoStack.Command(UndoStack.Type.DELETE, line, start, prefix, before, before))
        }
        if (item.snippet != null) {
            insertSnippetBody(item.insert, children)
        } else {
            val col = state.caretCol
            state.text.insert(line, col, item.insert)
            val end = state.text.spanEnd(line, col, item.insert)
            state.setCaret(end.first, end.second)
            children.add(UndoStack.Command(UndoStack.Type.INSERT, line, col, item.insert, before, before))
            // Delete + insert form ONE undoable step.
            record(UndoStack.Command(UndoStack.Type.COMPOSITE, 0, 0, "", before, snapshot(), children = children))
            afterEdit(line)
        }
    }

    fun moveCompletionSelection(delta: Int) {
        if (state.completionItems.isEmpty()) return
        state.completionSelected = (state.completionSelected + delta).mod(state.completionItems.size)
    }

    fun dismissCompletion() {
        state.hideCompletion()
    }

    private fun insertSnippetBody(body: String, preChildren: List<UndoStack.Command> = emptyList()) {
        val caretInBody = body.indexOf(Snippet.CARET)
        val clean = body.replace(Snippet.CARET, "")
        val line = state.caretLine
        val col = state.caretCol
        val before = snapshot()
        state.text.insert(line, col, clean)
        var endLine = line
        var endCol = col
        if (caretInBody >= 0) {
            val head = clean.substring(0, caretInBody)
            val nl = head.count { it == '\n' }
            endLine = line + nl
            endCol = if (nl == 0) col + head.length else head.length - head.lastIndexOf('\n') - 1
        } else {
            val end = state.text.spanEnd(line, col, clean)
            endLine = end.first
            endCol = end.second
        }
        state.setCaret(endLine, endCol)
        val insertCmd = UndoStack.Command(UndoStack.Type.INSERT, line, col, clean, before, snapshot())
        if (preChildren.isEmpty()) {
            record(insertCmd)
        } else {
            // Prefix delete + snippet expansion form one undoable step.
            record(
                UndoStack.Command(
                    UndoStack.Type.COMPOSITE, 0, 0, "", before, snapshot(),
                    children = preChildren + insertCmd
                )
            )
        }
        afterEdit(line)
    }

    // ------------------------------------------------------------ diagnostics

    fun setDiagnostics(list: List<EditorDiagnostic>) {
        val expanded = list.map { d ->
            if (d.colEnd > d.colStart) d
            else {
                val (s, e) = EditorOps.wordAround(state.text, d.line, d.colStart)
                d.copy(colStart = s, colEnd = maxOf(e, s + 1))
            }
        }
        state.diagnostics = expanded
    }

    // -------------------------------------------------------------- internals

    private fun wrapSelection(open: Char) {
        val b = state.selectionBounds() ?: return
        val sel = state.selectedText()
        val close = EditorOps.OPEN_BRACKETS[open] ?: open
        val t = state.text
        val before = snapshot()
        val removed = t.delete(b.first.line, b.first.col, b.second.line, b.second.col)
        val inserted = open.toString() + removed + close
        t.insert(b.first.line, b.first.col, inserted)
        // Select the wrapped text.
        val selStart = Pos(b.first.line, b.first.col + 1)
        val selEndPos = t.spanEnd(b.first.line, b.first.col + 1, removed)
        state.anchorLine = selStart.line
        state.anchorCol = selStart.col
        state.setCaret(selEndPos.first, selEndPos.second)
        record(
            UndoStack.Command(
                UndoStack.Type.COMPOSITE, 0, 0, "", before, snapshot(),
                children = listOf(
                    UndoStack.Command(UndoStack.Type.DELETE, b.first.line, b.first.col, removed, before, before),
                    UndoStack.Command(UndoStack.Type.INSERT, b.first.line, b.first.col, inserted, before, before)
                )
            )
        )
        afterEdit(b.first.line)
    }

    private fun insertWithUndo(s: String, coalesce: Boolean = true, caretOffsetBack: Int = 0) {
        val before = snapshot()
        val line = state.caretLine
        val col = state.caretCol
        state.text.insert(line, col, s)
        val end = state.text.spanEnd(line, col, s)
        state.setCaret(end.first, (end.second - caretOffsetBack).coerceAtLeast(0))
        val after = snapshot()
        if (coalesce) {
            if (!undoStack.tryCoalesceInsert(line, col, s, before, after)) {
                record(newInsert(line, col, s, before, after))
            }
        } else {
            record(newInsert(line, col, s, before, after))
        }
        afterEdit(line)
    }

    private fun newInsert(line: Int, col: Int, s: String, before: UndoStack.Snapshot, after: UndoStack.Snapshot) =
        UndoStack.Command(
            UndoStack.Type.INSERT, line, col, s, before, after,
            run = undoStack.newRun(), atMs = System.currentTimeMillis()
        )

    private fun record(cmd: UndoStack.Command) {
        undoStack.push(cmd)
    }

    private fun deleteSelection() {
        val b = state.selectionBounds() ?: return
        val before = snapshot()
        val removed = state.text.delete(b.first.line, b.first.col, b.second.line, b.second.col)
        state.clearSelection()
        state.setCaret(b.first.line, b.first.col)
        record(UndoStack.Command(UndoStack.Type.DELETE, b.first.line, b.first.col, removed, before, snapshot()))
        afterEdit(b.first.line)
    }

    private fun snapshot(): UndoStack.Snapshot =
        UndoStack.Snapshot(state.caretLine, state.caretCol, state.anchorLine, state.anchorCol)

    private fun afterEdit(fromLine: Int) {
        state.onDocumentStructureChanged(fromLine)
        refreshBracketMatch()
        requestScroll()
        updateCompletionPrefix()
        onEdited(this)
    }

    private fun requestScroll() {
        scrollRequest++
    }

    fun refreshBracketMatch() {
        val line = state.caretLine
        val col = state.caretCol
        val l = if (line < state.text.lineCount) state.text.line(line) else ""
        val before = if (col in 1..l.length) l[col - 1] else null
        val at = if (col < l.length) l[col] else null
        val probe = when {
            at != null && (at in EditorOps.OPEN_BRACKETS.keys || at in EditorOps.CLOSE_BRACKETS.keys) -> col
            before != null && (before in EditorOps.OPEN_BRACKETS.keys || before in EditorOps.CLOSE_BRACKETS.keys) -> col - 1
            else -> {
                state.bracketMatch = null
                return
            }
        }
        state.bracketMatch = EditorOps.findMatchingBracket(state, line, probe)
    }

    private fun updateCompletionPrefix() {
        val (start, prefix) = EditorOps.prefixBeforeCaret(state.text, state.caretLine, state.caretCol)
        val member = EditorOps.memberAccessBefore(state.text, state.caretLine, start)
        if (prefix.length >= 1 || member) {
            completionQueryListener?.invoke(prefix, member)
        } else {
            state.hideCompletion()
        }
    }

    private fun goToMatch(index: Int) {
        val m = state.findMatches.getOrNull(index) ?: return
        state.anchorLine = m.line
        state.anchorCol = m.startCol
        state.setCaret(m.line, m.endCol)
        requestScroll()
    }
}
