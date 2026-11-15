package dev.mono.editor.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mono.editor.control.EditorController
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.state.EditorState
import dev.mono.indexer.symbols.SymKind

/**
 * Slim completion list that appears under the caret: symbol name, kind badge,
 * module group and signature, all in grays. Tap to insert.
 */
@Composable
fun CompletionList(
    state: EditorState,
    controller: EditorController,
    theme: MonoTheme,
    height: Dp = 228.dp
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.completionSelected) {
        if (state.completionSelected >= 0) {
            runCatching { listState.animateScrollToItem(state.completionSelected) }
        }
    }

    Column(
        modifier = Modifier
            .width(320.dp)
            .height(height)
            .background(theme.surface)
            .border(1.dp, theme.border)
    ) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(
                state.completionItems,
                key = { i, item -> (i.toLong() * 1_000_003L) + item.label.hashCode().toLong() + item.module.hashCode().toLong() }
            ) { index, item ->
                val selected = index == state.completionSelected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) theme.surfaceHigh else theme.surface)
                        .clickable { controller.acceptCompletion(item) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    BasicText(
                        text = item.kind.badge,
                        style = TextStyle(
                            color = theme.textTertiary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.width(22.dp)
                    )
                    BasicText(
                        text = item.label,
                        style = TextStyle(
                            color = if (selected) theme.accent else theme.textPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (item.kind.boldWorthy) FontWeight.Bold else FontWeight.Normal
                        ),
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = item.detail,
                        style = TextStyle(
                            color = theme.textTertiary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        text = item.module,
                        style = TextStyle(
                            color = theme.textSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

private val SymKind.boldWorthy: Boolean
    get() = when (this) {
        SymKind.FUNCTION, SymKind.METHOD, SymKind.CLASS, SymKind.STRUCT,
        SymKind.SNIPPET -> true
        else -> false
    }
