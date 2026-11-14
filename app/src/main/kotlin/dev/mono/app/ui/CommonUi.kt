package dev.mono.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mono.editor.render.MonoTheme

/** Draws an [ImageVector] tinted with [tint]. */
@Composable
fun MonoIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp
) {
    val painter = rememberVectorPainter(icon)
    Canvas(modifier.size(size)) {
        with(painter) {
            draw(size = this@Canvas.size, colorFilter = ColorFilter.tint(tint))
        }
    }
}

/** Minimal square icon button. */
@Composable
fun IconAction(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(38.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        MonoIcon(icon, if (enabled) tint else tint.copy(alpha = 0.3f), size = size)
    }
}

/** Hairline separator. */
@Composable
fun Hairline(theme: MonoTheme, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.border)
    )
}

/** Section label used across screens. */
@Composable
fun SectionLabel(text: String, theme: MonoTheme, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = TextStyle(
            color = theme.textTertiary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp
        ),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Plain monospace text shorthand. */
@Composable
fun MonoText(
    text: String,
    theme: MonoTheme,
    modifier: Modifier = Modifier,
    color: Color? = null,
    fontSize: Int = 13,
    bold: Boolean = false
) {
    BasicText(
        text,
        style = TextStyle(
            color = color ?: theme.textPrimary,
            fontSize = fontSize.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        ),
        modifier = modifier
    )
}

/** Minimal action chip (flat surface, hairline border, monospace label). */
@Composable
fun ActionChip(label: String, theme: MonoTheme, enabledStyle: Boolean = true, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .background(if (enabledStyle) theme.surfaceHigh else theme.background)
            .border(1.dp, theme.border)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (enabledStyle) theme.textPrimary else theme.textTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal
            )
        )
    }
}

/** Minimal centered dialog with a single monospace input. */
@Composable
fun NameDialog(
    title: String,
    hint: String,
    theme: MonoTheme,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(theme.surface)
                .border(1.dp, theme.border)
                .padding(16.dp)
        ) {
            MonoText(title, theme, fontSize = 14, bold = true)
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.accent,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.caret),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.background)
                    .border(1.dp, theme.border)
                    .padding(10.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                ActionChip("cancel", theme, onClick = onDismiss)
                ActionChip("ok", theme, enabledStyle = value.isNotBlank()) {
                    if (value.isNotBlank()) onConfirm(value.trim())
                }
            }
        }
    }
}

/** Minimal confirmation dialog. */
@Composable
fun ConfirmDialog(
    title: String,
    theme: MonoTheme,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(theme.surface)
                .border(1.dp, theme.border)
                .padding(16.dp)
        ) {
            MonoText(title, theme, fontSize = 14, bold = true)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                ActionChip("cancel", theme, onClick = onDismiss)
                ActionChip("delete", theme, onClick = onConfirm)
            }
        }
    }
}
