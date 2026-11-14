package dev.mono.editor.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import dev.mono.editor.render.MonoTheme

/**
 * Minimal monochrome text field used by find/replace and the console input.
 * A BasicTextField with Mono styling: flat surface, hairline border, no
 * decorations.
 */
@Composable
fun MinimalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    theme: MonoTheme,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    onDone: (() -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Ascii
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .background(theme.background)
            .border(1.dp, theme.border)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = theme.textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(theme.caret),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.None
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        BasicText(
                            hint,
                            style = TextStyle(
                                color = theme.textTertiary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    inner()
                }
            }
        )
    }
}
