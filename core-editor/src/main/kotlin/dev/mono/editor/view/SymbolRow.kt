package dev.mono.editor.view

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mono.editor.control.EditorController
import dev.mono.editor.render.MonoTheme
import kotlin.math.abs

/**
 * Symbol row above the keyboard: the C++ characters missing from soft
 * keyboards. Swipe up anywhere on the row to reveal an extras palette with
 * more operators, the tab key and arrow keys.
 */
@Composable
fun SymbolRow(
    controller: EditorController,
    theme: MonoTheme,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.background)
            .pointerInput(Unit) {
                val threshold = 24.dp.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var swiped = false
                    var dy = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        for (c in pressed) {
                            dy += c.positionChange().y
                        }
                        if (!swiped && ((dy < -threshold && !expanded) || (dy > threshold && expanded))) {
                            swiped = true
                        }
                        if (swiped) event.changes.forEach { it.consume() }
                    }
                    if (swiped) {
                        expanded = !expanded
                    }
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            for (sym in PRIMARY_SYMBOLS) {
                SymbolKey(sym, theme) { controller.inputSymbol(sym) }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    KeyLabel("tab", theme) { controller.tab() }
                    KeyLabel("<", theme) { controller.moveCaretRelative(0, -1) }
                    KeyLabel(">", theme) { controller.moveCaretRelative(0, 1) }
                    KeyLabel("^", theme) { controller.moveCaretRelative(-1, 0) }
                    KeyLabel("v", theme) { controller.moveCaretRelative(1, 0) }
                    for (sym in EXTRA_SYMBOLS_1) SymbolKey(sym, theme) { controller.inputSymbol(sym) }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    for (sym in EXTRA_SYMBOLS_2) SymbolKey(sym, theme) { controller.inputSymbol(sym) }
                }
            }
        }

        // Hairline top border.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(theme.border)
        )
    }
}

@Composable
private fun SymbolKey(label: String, theme: MonoTheme, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(44.dp)
            .height(40.dp)
            .clickable(onClick = onClick)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = theme.textPrimary,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

@Composable
private fun KeyLabel(label: String, theme: MonoTheme, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(52.dp)
            .height(40.dp)
            .clickable(onClick = onClick)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = theme.textSecondary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

private val PRIMARY_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", ";", ":", "<", ">", "*", "&", "->", "#", "\"", "'"
)

private val EXTRA_SYMBOLS_1 = listOf(
    "=", "+", "-", "/", "%", "!", "?", "|", "~", "^"
)

private val EXTRA_SYMBOLS_2 = listOf(
    ".", ",", "\\", "_", "@", "$", "&&", "||", "==", "!=", "+=", "-=", "*=", "/=", "++", "--", "::", "<<", ">>", ".h", ".cpp"
)
