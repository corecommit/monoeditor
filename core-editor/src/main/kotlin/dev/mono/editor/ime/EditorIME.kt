package dev.mono.editor.ime

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.mono.editor.control.EditorController

/**
 * An invisible, focusable view whose only job is to host the soft keyboard's
 * InputConnection and translate key events into controller operations.
 *
 * The editor itself is pure Compose Canvas — no TextField — so this view is
 * the bridge between the IME world and Mono's own text model.
 */
class EditorIMEView(
    context: Context,
    private val controller: EditorController
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        visibility = VISIBLE
        alpha = 0f
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL makes soft keyboards send discrete key events (with
        // prediction/composition off), which is exactly what a code editor
        // wants. Keyboards that still compose/commit are handled too.
        outAttrs.inputType = android.text.InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.actionId = EditorInfo.IME_ACTION_NONE
        outAttrs.fieldName = "mono-editor"
        return MonoInputConnection(this, controller)
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, 0)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val ctrl = event.isCtrlPressed
        // Caps lock is a latch for letters, not a navigation modifier:
        // caps + arrow keys must not extend the selection.
        val shift = event.isShiftPressed
        if (handleKey(keyCode, event, ctrl, shift)) return true
        // Printable characters.
        val uc = event.unicodeChar
        if (!ctrl && !event.isAltPressed && uc >= 32 && uc != KeyEvent.KEYCODE_FORWARD_DEL) {
            controller.inputText(uc.toChar().toString())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKey(keyCode: Int, event: KeyEvent, ctrl: Boolean, shift: Boolean): Boolean {
        if (ctrl) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> { if (shift) controller.redo() else controller.undo(); return true }
                KeyEvent.KEYCODE_Y -> { controller.redo(); return true }
                KeyEvent.KEYCODE_F -> { controller.findRequestListener?.invoke(); return true }
                KeyEvent.KEYCODE_C -> { controller.copy(); return true }
                KeyEvent.KEYCODE_V -> { controller.paste(); return true }
                KeyEvent.KEYCODE_X -> { controller.cut(); return true }
                KeyEvent.KEYCODE_A -> { controller.selectAll(); return true }
            }
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> { controller.backspace(); return true }
            KeyEvent.KEYCODE_FORWARD_DEL -> { controller.deleteForward(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { controller.enter(); return true }
            KeyEvent.KEYCODE_TAB -> { controller.tab(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { controller.moveCaretRelative(0, -1, shift); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { controller.moveCaretRelative(0, 1, shift); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { controller.moveCaretRelative(-1, 0, shift); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { controller.moveCaretRelative(1, 0, shift); return true }
            KeyEvent.KEYCODE_MOVE_HOME -> { controller.moveCaret(controller.state.caretLine, 0, shift); return true }
            KeyEvent.KEYCODE_MOVE_END -> {
                val l = controller.state.caretLine
                controller.moveCaret(l, controller.state.text.lineLength(l), shift)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> { controller.moveCaretRelative(-20, 0, shift); return true }
            KeyEvent.KEYCODE_PAGE_DOWN -> { controller.moveCaretRelative(20, 0, shift); return true }
        }
        return false
    }
}

/**
 * InputConnection over the controller. With TYPE_NULL most keyboards use
 * key events; the composing/commit calls cover the rest.
 *
 * A composing region is tracked properly: each setComposingText replaces the
 * previous draft (it is inserted unrecorded and deleted unrecorded), while
 * commitText first removes any pending draft and then inserts the final text
 * as one undoable command. finishComposingText keeps the draft and records
 * it as one undoable step.
 */
private class MonoInputConnection(
    private val view: EditorIMEView,
    private val controller: EditorController
) : BaseInputConnection(view, false) {

    // Composing region: the draft the IME is still editing, tracked as
    // (line, col, text). Assumed newline-free; multi-line drafts bypass
    // region tracking and go through the normal typing path.
    private var composing = false
    private var composeLine = -1
    private var composeCol = -1
    private var composeText = ""

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Commit finalizes: drop the draft first, then insert the final text.
        dropComposing()
        val s = text.toString()
        if (s.isEmpty()) return true
        if (s == "\n") {
            controller.enter()
        } else {
            controller.inputText(s)
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // Per the InputConnection contract, composing text is removed first.
        dropComposing()
        repeat(afterLength.coerceAtLeast(0)) { controller.deleteForward() }
        repeat(beforeLength.coerceAtLeast(0)) { controller.backspace() }
        return true
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        // Each call replaces the previous draft.
        dropComposing()
        val s = text.toString()
        if (s.isEmpty()) return true
        if (s.contains('\n')) {
            controller.inputText(s)
            return true
        }
        val st = controller.state
        composing = true
        composeLine = st.caretLine
        composeCol = st.caretCol
        composeText = s
        controller.insertRaw(s)
        return true
    }

    override fun finishComposingText(): Boolean {
        // The draft stays in the buffer; make it one undoable step.
        if (composing) {
            val line = composeLine
            val col = composeCol
            val draft = composeText
            composing = false
            composeText = ""
            if (draft.isNotEmpty() && draftStillIntact(line, col, draft)) {
                controller.recordAppliedInsert(line, col, draft)
            }
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            view.onKeyDown(event.keyCode, event)
        }
        return true
    }

    /** Removes the current composing draft from the buffer without recording undo. */
    private fun dropComposing() {
        if (!composing) return
        composing = false
        val line = composeLine
        val col = composeCol
        val draft = composeText
        composeText = ""
        if (draft.isEmpty()) return
        // If the buffer changed under the draft (hardware keys during
        // composition, watcher reloads...), deleting a stale span would
        // corrupt the text — leave it and let the next edit clean up.
        if (!draftStillIntact(line, col, draft)) return
        val st = controller.state
        st.text.delete(line, col, line, col + draft.length)
        st.setCaret(line, col)
        st.onDocumentStructureChanged(line)
    }

    private fun draftStillIntact(line: Int, col: Int, draft: String): Boolean {
        val st = controller.state
        if (line !in 0 until st.text.lineCount) return false
        val l = st.text.line(line)
        return col >= 0 && col + draft.length <= l.length &&
                l.regionMatches(col, draft, 0, draft.length)
    }
}
