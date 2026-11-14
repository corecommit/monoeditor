package dev.mono.editor.text

import dev.mono.editor.state.EditorState

/**
 * Undo/redo over primitive edit commands. The controller coalesces adjacent
 * typing into single commands (same run, within [coalesceMs]) so undo steps
 * feel like words/lines rather than single characters.
 */
class UndoStack(
    private val limit: Int = 1024,
    private val coalesceMs: Long = 700
) {

    enum class Type { INSERT, DELETE, COMPOSITE }

    class Snapshot(
        val caretLine: Int,
        val caretCol: Int,
        val anchorLine: Int,
        val anchorCol: Int
    )

    class Command(
        val type: Type,
        val line: Int,
        val col: Int,
        val text: String,
        val before: Snapshot,
        val after: Snapshot,
        /** For COMPOSITE: ordered child ops applied left-to-right on redo. */
        val children: List<Command> = emptyList(),
        /** Coalescing run marker. */
        internal val run: Long = 0,
        internal val atMs: Long = 0
    )

    private val undo = ArrayDeque<Command>()
    private val redo = ArrayDeque<Command>()
    private var runCounter = 0L
    var revision: Long = 0
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun push(cmd: Command) {
        undo.addLast(cmd)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
        revision++
    }

    /** Attempts to merge an INSERT into the previous command; returns true when merged. */
    fun tryCoalesceInsert(line: Int, col: Int, s: String, before: Snapshot, after: Snapshot): Boolean {
        val last = undo.lastOrNull() ?: return false
        if (last.type != Type.INSERT) return false
        if (System.currentTimeMillis() - last.atMs > coalesceMs) return false
        val endOfLast = spanEndOf(last)
        if (endOfLast.first != line || endOfLast.second != col) return false
        if (last.text.length + s.length > 200) return false
        val merged = Command(
            Type.INSERT, last.line, last.col, last.text + s,
            last.before, after, run = last.run, atMs = System.currentTimeMillis()
        )
        undo.removeLast()
        undo.addLast(merged)
        // History changed: any pending redo would replay against a buffer
        // it no longer matches, so it must go.
        redo.clear()
        revision++
        return true
    }

    /** Attempts to merge a DELETE (backspace at col) into the previous DELETE. */
    fun tryCoalesceDelete(line: Int, col: Int, s: String, before: Snapshot, after: Snapshot): Boolean {
        val last = undo.lastOrNull() ?: return false
        if (last.type != Type.DELETE) return false
        if (System.currentTimeMillis() - last.atMs > coalesceMs) return false
        // Deleting backwards: new delete must end exactly where last started.
        if (last.line != line) return false
        if (col + s.length != last.col) return false
        if (last.text.length + s.length > 200) return false
        val merged = Command(
            Type.DELETE, line, col, s + last.text,
            before, last.after, run = last.run, atMs = System.currentTimeMillis()
        )
        undo.removeLast()
        undo.addLast(merged)
        // Same as above: mutating history invalidates the redo stack.
        redo.clear()
        revision++
        return true
    }

    fun newRun(): Long = ++runCounter

    fun undo(text: EditorText, state: EditorState): Boolean {
        val cmd = undo.removeLastOrNull() ?: return false
        applyInverse(text, cmd)
        restore(state, cmd.before)
        redo.addLast(cmd)
        revision++
        return true
    }

    fun redo(text: EditorText, state: EditorState): Boolean {
        val cmd = redo.removeLastOrNull() ?: return false
        applyForward(text, cmd)
        restore(state, cmd.after)
        undo.addLast(cmd)
        revision++
        return true
    }

    private fun applyForward(text: EditorText, cmd: Command) {
        if (cmd.type == Type.COMPOSITE) {
            for (c in cmd.children) applyForward(text, c)
        } else if (cmd.type == Type.INSERT) {
            text.insert(cmd.line, cmd.col, cmd.text)
        } else {
            val end = text.spanEnd(cmd.line, cmd.col, cmd.text)
            text.delete(cmd.line, cmd.col, end.first, end.second)
        }
    }

    private fun applyInverse(text: EditorText, cmd: Command) {
        if (cmd.type == Type.COMPOSITE) {
            for (c in cmd.children.asReversed()) applyInverse(text, c)
        } else if (cmd.type == Type.INSERT) {
            val end = text.spanEnd(cmd.line, cmd.col, cmd.text)
            text.delete(cmd.line, cmd.col, end.first, end.second)
        } else {
            text.insert(cmd.line, cmd.col, cmd.text)
        }
    }

    private fun restore(state: EditorState, s: Snapshot) {
        state.setCaret(s.caretLine, s.caretCol)
        if (s.anchorLine >= 0) {
            state.anchorLine = s.anchorLine
            state.anchorCol = s.anchorCol
        } else {
            state.clearSelection()
        }
        state.onDocumentStructureChanged(fromLine = 0)
    }

    private fun spanEndOf(cmd: Command): Pair<Int, Int> =
        EditorTextSpan.end(cmd.line, cmd.col, cmd.text)

    fun clear() {
        undo.clear()
        redo.clear()
        revision++
    }
}

/** Small helper so UndoStack does not need an EditorText instance to compute spans. */
internal object EditorTextSpan {
    fun end(line: Int, col: Int, text: String): Pair<Int, Int> {
        val nl = text.count { it == '\n' }
        return if (nl == 0) line to col + text.length
        else (line + nl) to (text.length - text.lastIndexOf('\n') - 1)
    }
}
