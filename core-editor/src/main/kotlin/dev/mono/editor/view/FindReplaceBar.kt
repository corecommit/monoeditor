package dev.mono.editor.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mono.editor.control.EditorController
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.state.EditorState

/**
 * Compact find/replace bar: query with match counter, next/prev, optional
 * regex + case toggles, replace / replace-all.
 */
@Composable
fun FindReplaceBar(
    state: EditorState,
    controller: EditorController,
    theme: MonoTheme,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(state.findQuery) }
    var replacement by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(state.findRegex) }
    var caseSensitive by remember { mutableStateOf(state.findCaseSensitive) }

    LaunchedEffect(query, regex, caseSensitive) {
        controller.find(query, regex, caseSensitive)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .border(0.5.dp, theme.border)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MinimalTextField(
                value = query,
                onValueChange = { query = it },
                hint = "find",
                theme = theme,
                singleLine = true,
                modifier = Modifier.weight(1f),
                onDone = { controller.findNext() }
            )
            val countText = if (state.findMatches.isEmpty()) "0/0"
            else "${state.findIndex + 1}/${state.findMatches.size}"
            BasicText(
                countText,
                style = TextStyle(color = theme.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(52.dp)
            )
            Chip("\u2039", theme) { controller.findPrev() }
            Chip("\u203A", theme) { controller.findNext() }
            Chip("Aa", theme, active = caseSensitive) { caseSensitive = !caseSensitive }
            Chip(".*", theme, active = regex) { regex = !regex }
            Chip("x", theme) { onDismiss() }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MinimalTextField(
                value = replacement,
                onValueChange = { replacement = it },
                hint = "replace",
                theme = theme,
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Chip("repl", theme) { controller.replaceCurrent(replacement) }
            Chip("all", theme) { controller.replaceAll(replacement) }
        }
    }
}

/** Small monochrome action chip. */
@Composable
fun Chip(
    label: String,
    theme: MonoTheme,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (active) theme.surfaceHigh else theme.background)
            .border(1.dp, theme.border)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (active) theme.accent else theme.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}
