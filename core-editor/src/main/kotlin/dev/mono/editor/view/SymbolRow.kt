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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.withTimeoutOrNull
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
        ) {
            // Always visible: indents the caret line / every selected line.
            KeyLabel("tab", theme) { controller.tab() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                for (sym in PRIMARY_SYMBOLS) {
                    SymbolKey(sym, theme) { controller.inputSymbol(sym) }
                }
            }
            // Always-visible arrow cluster (hold to repeat).
            ArrowKey(ArrowDir.LEFT, theme) { controller.moveCaretRelative(0, -1) }
            ArrowKey(ArrowDir.UP, theme) { controller.moveCaretRelative(-1, 0) }
            ArrowKey(ArrowDir.DOWN, theme) { controller.moveCaretRelative(1, 0) }
            ArrowKey(ArrowDir.RIGHT, theme) { controller.moveCaretRelative(0, 1) }
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
                    KeyLabel("untab", theme) { controller.untab() }
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

private enum class ArrowDir { LEFT, UP, DOWN, RIGHT }

/** Drawn chevron key; fires once on press, then repeats while held. */
@Composable
private fun ArrowKey(dir: ArrowDir, theme: MonoTheme, onStep: () -> Unit) {
    val color: Color = theme.textPrimary
    // pointerInput below is keyed only by direction, so it must read the
    // latest callback; otherwise it keeps driving the first tab's controller.
    val step by rememberUpdatedState(onStep)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(36.dp)
            .height(40.dp)
            .pointerInput(dir) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    step()
                    var released = false
                    withTimeoutOrNull(350L) {
                        waitForUpOrCancellation()
                        released = true
                    }
                    while (!released) {
                        step()
                        withTimeoutOrNull(45L) {
                            waitForUpOrCancellation()
                            released = true
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                when (dir) {
                    ArrowDir.LEFT -> { moveTo(w * 0.65f, h * 0.1f); lineTo(w * 0.3f, h * 0.5f); lineTo(w * 0.65f, h * 0.9f) }
                    ArrowDir.RIGHT -> { moveTo(w * 0.35f, h * 0.1f); lineTo(w * 0.7f, h * 0.5f); lineTo(w * 0.35f, h * 0.9f) }
                    ArrowDir.UP -> { moveTo(w * 0.1f, h * 0.65f); lineTo(w * 0.5f, h * 0.3f); lineTo(w * 0.9f, h * 0.65f) }
                    ArrowDir.DOWN -> { moveTo(w * 0.1f, h * 0.35f); lineTo(w * 0.5f, h * 0.7f); lineTo(w * 0.9f, h * 0.35f) }
                }
            }
            drawPath(path, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
        }
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
