package dev.mono.editor.text

/**
 * Line-based text storage for the editor.
 *
 * Structural operations (line splits/merges) are O(lines), which is fine for
 * the 10k+ line files Mono targets; character reads are O(1) per line. The
 * editor never copies the whole document except for [text].
 */
class EditorText(initial: String = "") {

    private val lines = ArrayList<String>()

    init {
        lines.ensureCapacity(1024)
        if (initial.isEmpty()) {
            lines.add("")
        } else {
            splitLines(initial)
        }
    }

    private fun splitLines(s: String) {
        var start = 0
        for (i in s.indices) {
            if (s[i] == '\n') {
                lines.add(s.substring(start, i))
                start = i + 1
            }
        }
        lines.add(s.substring(start))
    }

    val lineCount: Int get() = lines.size

    fun line(index: Int): String = lines[index]

    fun lineLength(index: Int): Int = lines[index].length

    fun text(): String = lines.joinToString("\n")

    fun charCount(): Int {
        var n = lines.size - 1
        for (l in lines) n += l.length
        return n
    }

    fun region(startLine: Int, startCol: Int, endLine: Int, endCol: Int): String {
        if (startLine == endLine) {
            val l = lines[startLine]
            val sc = startCol.coerceIn(0, l.length)
            val ec = endCol.coerceIn(0, l.length)
            return l.substring(minOf(sc, ec), maxOf(sc, ec))
        }
        val sb = StringBuilder()
        sb.append(lines[startLine].substring(startCol.coerceIn(0, lines[startLine].length)))
        for (i in startLine + 1 until endLine) {
            sb.append('\n').append(lines[i])
        }
        sb.append('\n').append(lines[endLine].substring(0, endCol.coerceIn(0, lines[endLine].length)))
        return sb.toString()
    }

    /** Inserts [s] (which may contain '\n') at the position; lines split as needed. */
    fun insert(line: Int, col: Int, s: String) {
        if (s.isEmpty()) return
        if ('\n' !in s) {
            val l = lines[line]
            val c = col.coerceIn(0, l.length)
            lines[line] = l.substring(0, c) + s + l.substring(c)
            return
        }
        val l = lines[line]
        val c = col.coerceIn(0, l.length)
        val before = l.substring(0, c)
        val after = l.substring(c)
        val pieces = s.split('\n')
        val newLines = ArrayList<String>(pieces.size + 1)
        newLines.add(before + pieces[0])
        for (i in 1 until pieces.size) newLines.add(pieces[i])
        newLines[newLines.size - 1] = newLines.last() + after
        lines.removeAt(line)
        lines.addAll(line, newLines)
    }

    /** Deletes the inclusive range; returns the removed text. */
    fun delete(startLine: Int, startCol: Int, endLine: Int, endCol: Int): String {
        val ordered = if (startLine < endLine || (startLine == endLine && startCol <= endCol)) {
            Triple(startLine, startCol, endLine to endCol)
        } else {
            Triple(endLine, endCol, startLine to startCol)
        }
        val sl = ordered.first
        val sc = ordered.second.coerceIn(0, lines[sl].length)
        val el = ordered.third.first
        val ec = ordered.third.second.coerceIn(0, lines[el].length)

        if (sl == el) {
            val l = lines[sl]
            val a = minOf(sc, ec); val b = maxOf(sc, ec)
            val removed = l.substring(a, b)
            lines[sl] = l.substring(0, a) + l.substring(b)
            return removed
        }
        val removed = region(sl, sc, el, ec)
        val merged = lines[sl].substring(0, sc) + lines[el].substring(ec)
        lines[sl] = merged
        for (i in el downTo sl + 1) lines.removeAt(i)
        if (lines.isEmpty()) lines.add("")
        return removed
    }

    fun replace(startLine: Int, startCol: Int, endLine: Int, endCol: Int, s: String): String {
        val removed = delete(startLine, startCol, endLine, endCol)
        // After delete, the range collapses to (startLine, startCol).
        insert(startLine, startCol, s)
        return removed
    }

    fun appendLine(s: String) {
        lines.add(s)
    }

    /** End position of [text] inserted at (line, col) — used by the undo stack. */
    fun spanEnd(line: Int, col: Int, text: String): Pair<Int, Int> {
        val nl = text.count { it == '\n' }
        if (nl == 0) return line to col + text.length
        val lastLineLen = text.length - text.lastIndexOf('\n') - 1
        return (line + nl) to lastLineLen
    }
}
