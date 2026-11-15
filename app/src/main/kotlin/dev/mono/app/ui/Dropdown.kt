package dev.mono.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.mono.editor.render.MonoTheme

/** One choice in a [DropdownField]: a short label and an optional hint line. */
data class DropdownOption(val label: String, val description: String = "")

/**
 * Compact dropdown: a chip showing the current value that opens a list of
 * options (each with a small description) right under it. Used where a row
 * of chips would not fit the screen width.
 */
@Composable
fun DropdownField(
    selectedIndex: Int,
    options: List<DropdownOption>,
    theme: MonoTheme,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    val current = options.getOrNull(selectedIndex)?.label ?: ""

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(theme.surfaceHigh)
                .border(1.dp, theme.border)
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            BasicText(
                current,
                style = TextStyle(
                    color = theme.textPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
            Spacer(Modifier.width(8.dp))
            BasicText(
                if (open) "^" else "v",
                style = TextStyle(
                    color = theme.textTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            )
        }

        if (open) {
            Popup(
                popupPositionProvider = remember { DropdownPositioner() },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true)
            ) {
                Column(
                    Modifier
                        .width(264.dp)
                        .heightIn(max = 360.dp)
                        .background(theme.surfaceHigh)
                        .border(1.dp, theme.border)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEachIndexed { i, o ->
                        val selected = i == selectedIndex
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selected) theme.surface else theme.surfaceHigh)
                                .clickable {
                                    open = false
                                    onSelect(i)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            MonoText(o.label, theme, fontSize = 12, bold = selected)
                            if (o.description.isNotEmpty()) {
                                MonoText(
                                    o.description,
                                    theme,
                                    color = theme.textTertiary,
                                    fontSize = 10
                                )
                            }
                        }
                        if (i < options.lastIndex) Hairline(theme)
                    }
                }
            }
        }
    }
}

/** One entry in an [ActionMenu]. */
data class MenuItem(val label: String, val onClick: () -> Unit)

/**
 * Small popup menu that opens directly under the composable it is called
 * from (call it inside the Box that wraps the 3-dots button).
 */
@Composable
fun ActionMenu(
    theme: MonoTheme,
    items: List<MenuItem>,
    onDismiss: () -> Unit
) {
    Popup(
        popupPositionProvider = remember { DropdownPositioner() },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            Modifier
                .width(150.dp)
                .background(theme.surfaceHigh)
                .border(1.dp, theme.border)
        ) {
            items.forEachIndexed { i, item ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = item.onClick)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    MonoText(item.label, theme, fontSize = 13)
                }
                if (i < items.lastIndex) Hairline(theme)
            }
        }
    }
}

/** Right-aligned under the anchor; flips above it when there is no room below. */
private class DropdownPositioner : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val margin = 8
        val gap = 4
        val w = popupContentSize.width
        val h = popupContentSize.height
        val minX = margin
        val maxX = (windowSize.width - w - margin).coerceAtLeast(minX)
        val x = (anchorBounds.right - w).coerceIn(minX, maxX)

        val below = anchorBounds.bottom + gap
        val y = if (below + h <= windowSize.height - margin) {
            below
        } else {
            (anchorBounds.top - gap - h).coerceAtLeast(margin)
        }
        return IntOffset(x, y)
    }
}
