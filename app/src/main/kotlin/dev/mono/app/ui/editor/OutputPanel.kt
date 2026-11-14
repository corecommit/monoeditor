package dev.mono.app.ui.editor

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mono.app.session.ProjectSession
import dev.mono.app.ui.ActionChip
import dev.mono.app.ui.Hairline
import dev.mono.app.ui.IconAction
import dev.mono.app.ui.MonoText
import dev.mono.app.ui.icons.MonoIcons
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.view.MinimalTextField
import kotlinx.coroutines.launch

/**
 * Bottom output panel: Build log, Run console (with stdin, stop, timeout)
 * and Issues. Collapsed to a hairline; drag the handle or use the tabs to
 * open; auto-opens while a build runs.
 */
@Composable
fun OutputPanel(session: ProjectSession, theme: MonoTheme) {
    var open by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var heightDp by remember { mutableStateOf(280f) }
    val animatedHeight by animateDpAsState(
        targetValue = if (open) heightDp.dp else 0.dp,
        animationSpec = tween(180),
        label = "output-height"
    )

    // Auto-open on build activity.
    LaunchedEffect(session.building, session.running) {
        if (session.building || session.running) {
            open = true
            tab = if (session.building) 0 else 1
        }
    }
    // Switch to issues when the build fails.
    LaunchedEffect(session.lastBuildOk, session.building) {
        if (!session.building && !session.lastBuildOk && session.diagnostics.isNotEmpty()) {
            open = true
            tab = 2
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
    ) {
        Hairline(theme)

        // Handle + tabs row.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        if (open) {
                            heightDp = (heightDp - dragAmount / 2f).coerceIn(120f, 520f)
                        }
                    }
                }
        ) {
            Box(
                Modifier
                    .width(28.dp)
                    .height(34.dp)
                    .clickable { open = !open },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(14.dp)
                        .height(2.dp)
                        .background(theme.textTertiary)
                )
            }
            TabLabel("build", tab == 0, theme) { tab = 0; open = true }
            TabLabel("run", tab == 1, theme) { tab = 1; open = true }
            TabLabel("issues (${session.diagnostics.size})", tab == 2, theme) { tab = 2; open = true }
            Spacer(Modifier.weight(1f))
            if (session.building || session.running) {
                MonoText(
                    if (session.building) session.buildStatusText else "running",
                    theme,
                    color = theme.textTertiary,
                    fontSize = 10
                )
                Spacer(Modifier.width(6.dp))
            }
            if (session.running) {
                IconAction(MonoIcons.Stop, theme.accent, size = 16.dp) { session.stopRun() }
            }
            IconAction(MonoIcons.Trash, theme.textTertiary, size = 15.dp) {
                when (tab) {
                    0 -> session.buildLog.clear()
                    1 -> session.console.clear()
                    2 -> session.diagnostics.clear()
                }
            }
            IconAction(MonoIcons.ChevronDown, theme.textTertiary, size = 16.dp) { open = !open }
        }

        if (animatedHeight > 0.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(animatedHeight)
            ) {
                Hairline(theme)
                when (tab) {
                    0 -> BuildLog(session, theme)
                    1 -> RunConsole(session, theme)
                    else -> Issues(session, theme)
                }
            }
        }
    }
}

@Composable
private fun TabLabel(label: String, active: Boolean, theme: MonoTheme, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (active) theme.accent else theme.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
            )
        )
    }
}

@Composable
private fun BuildLog(session: ProjectSession, theme: MonoTheme) {
    val listState = rememberLazyListState()
    LaunchedEffect(session.buildLog.size) {
        if (session.buildLog.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(session.buildLog.size - 1) }
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        itemsIndexed(session.buildLog) { _, line ->
            BasicText(
                line,
                style = TextStyle(
                    color = if (line.startsWith("error") || line.contains(": error:")) theme.diagnosticError else theme.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun RunConsole(session: ProjectSession, theme: MonoTheme) {
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(session.console.size) {
        if (session.console.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(session.console.size - 1) }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
        ) {
            itemsIndexed(session.console, key = { i, _ -> i }) { _, line ->
                val color = when (line.kind) {
                    ProjectSession.ConsoleLine.ERR -> theme.diagnosticWarning
                    ProjectSession.ConsoleLine.IN -> theme.textTertiary
                    ProjectSession.ConsoleLine.META -> theme.textTertiary
                    else -> theme.textPrimary
                }
                BasicText(
                    line.text,
                    style = TextStyle(color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            MinimalTextField(
                value = input,
                onValueChange = { input = it },
                hint = "stdin",
                theme = theme,
                singleLine = true,
                modifier = Modifier.weight(1f),
                onDone = {
                    if (input.isNotEmpty()) {
                        session.sendStdin(input)
                        input = ""
                    }
                }
            )
            ActionChip("send", theme, enabledStyle = input.isNotEmpty() && session.running) {
                session.sendStdin(input)
                input = ""
            }
            if (!session.running && session.runFinished) {
                MonoText("exit ${session.exitCode}", theme, color = theme.textTertiary, fontSize = 10)
            }
        }
    }
}

@Composable
private fun Issues(session: ProjectSession, theme: MonoTheme) {
    if (session.diagnostics.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            MonoText("no issues", theme, color = theme.textTertiary, fontSize = 11)
        }
        return
    }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxWidth()) {
        itemsIndexed(session.diagnostics, key = { i, _ -> i }) { _, issue ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // jumpTo may read the file list (and open a new tab)
                        // — both are suspend, disk-bound operations.
                        scope.launch { session.jumpTo(issue.file, issue.line, issue.col) }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                MonoText(
                    if (issue.isError) "err" else "wrn",
                    theme,
                    color = if (issue.isError) theme.diagnosticError else theme.diagnosticWarning,
                    fontSize = 10
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    val loc = if (issue.line > 0) "${issue.file.substringAfterLast('/')}:${issue.line}:${issue.col}" else "link"
                    MonoText(loc, theme, color = theme.textTertiary, fontSize = 10)
                    MonoText(issue.message, theme, fontSize = 11)
                }
            }
        }
    }
}
