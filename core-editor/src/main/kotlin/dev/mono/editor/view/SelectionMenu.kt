package dev.mono.editor.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import dev.mono.editor.control.EditorController
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.state.EditorState

/**
 * Floating Cut / Copy / Paste / Select all bar. Mono draws its own text, so
 * the system selection toolbar never appears; this is the replacement.
 */
@Composable
fun SelectionMenu(
    state: EditorState,
    controller: EditorController,
    theme: MonoTheme
) {
    val bounds = state.selectionBounds()
    val hasText = bounds != null &&
        (bounds.first.line != bounds.second.line || bounds.first.col != bounds.second.col)
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = Modifier
            .background(theme.surfaceHigh, shape)
            .border(1.dp, theme.border, shape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasText) {
            MenuItem("Cut", theme) {
                state.showSelectionMenu = false
                controller.cut()
            }
            MenuItem("Copy", theme) {
                controller.copy()
                controller.clearSelection()
                state.showSelectionMenu = false
            }
        }
        MenuItem("Paste", theme) {
            state.showSelectionMenu = false
            controller.paste()
        }
        MenuItem("Select all", theme) {
            controller.selectAll()
            state.showSelectionMenu = true
        }
    }
}

@Composable
private fun MenuItem(label: String, theme: MonoTheme, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(
            color = theme.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        ),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/**
 * Places the menu centred on [centerX] just above [aboveY] (or below
 * [belowY] when there is no room), always inside the editor bounds.
 * All values are relative to the editor's top-left corner.
 */
internal class SelectionMenuPositioner(
    private val centerX: Int,
    private val aboveY: Int,
    private val belowY: Int,
    private val margin: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val minX = anchorBounds.left + margin
        val maxX = (anchorBounds.right - w - margin).coerceAtLeast(minX)
        val x = (anchorBounds.left + centerX - w / 2).coerceIn(minX, maxX)

        val minY = anchorBounds.top + margin
        val maxY = (anchorBounds.bottom - h - margin).coerceAtLeast(minY)
        var y = anchorBounds.top + aboveY - h
        if (y < minY) y = anchorBounds.top + belowY
        return IntOffset(x, y.coerceIn(minY, maxY))
    }
}
