package dev.mono.app.ui.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.mono.app.session.AppContainer
import dev.mono.app.session.ProjectSession
import dev.mono.app.ui.ActionChip
import dev.mono.app.ui.Hairline
import dev.mono.app.ui.IconAction
import dev.mono.app.ui.MonoText
import dev.mono.app.ui.icons.MonoIcons
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.view.FindReplaceBar
import dev.mono.editor.view.MonoEditor
import dev.mono.editor.view.SymbolRow
import dev.mono.indexer.index.Includes
import dev.mono.indexer.index.SearchDir
import dev.mono.indexer.symbols.CompletionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The workspace: tabs, editor, output panel, symbol row, overlays. */
@Composable
fun EditorScreen(
    container: AppContainer,
    session: ProjectSession,
    theme: MonoTheme,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var findVisible by remember { mutableStateOf(false) }
    // Focus mode is a temporary view, never restored on launch: starting in it
    // hid every control (including the way out).
    var focusMode by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var fileSheetOpen by remember { mutableStateOf(false) }

    val view = LocalView.current

    // Focus mode hides system bars; a small dimmed dot remains to exit.
    LaunchedEffect(focusMode) {
        val window = (view.context as? android.app.Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (focusMode) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Always give the system bars back when leaving the editor.
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // System back leaves focus mode first.
    BackHandler(enabled = focusMode) { focusMode = false }

    val openFromSystem = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            container.scope.launch { session.openFromExternal(uri) }
        }
    }

    // Auto-open the output panel when a build starts.
    LaunchedEffect(session.building) {
        if (session.building) menuOpen = false
    }

    val completionProvider: suspend (String, Boolean) -> List<CompletionItem> = { prefix, memberAccess ->
        val tab = session.active
        if (tab == null) {
            emptyList()
        } else {
            // Include parsing, SAF probes and the module dir stats all touch
            // disk — keep them off the main thread.
            withContext(Dispatchers.IO) {
                val includes = Includes.parse(tab.state.text.text())
                val usingShared = container.repository.usingSharedStorage
                val searchDirs = if (usingShared) emptyList() else listOf(
                    SearchDir(
                        File(container.internalRoot(), "modules").absolutePath,
                        "modules"
                    ),
                    SearchDir(
                        File(container.internalRoot(), "projects/${session.project.name}").absolutePath,
                        "projects/${session.project.name}"
                    )
                )
                val virtual = if (usingShared) session.moduleNames().toSet() else emptySet()
                container.symbolDb.complete(
                    prefix, tab.path, includes, searchDirs, virtual, memberAccess
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .background(theme.background)
        ) {
            // ---- top bar ------------------------------------------------
            if (!focusMode) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 2.dp)
                ) {
                    IconAction(MonoIcons.ChevronLeft, theme.textSecondary) { onExit() }
                    IconAction(MonoIcons.Modules, theme.textSecondary) { drawerOpen = true }
                    TabsRow(session, theme, Modifier.weight(1f))
                    IconAction(MonoIcons.Plus, theme.textSecondary) { fileSheetOpen = true }
                    IconAction(
                        MonoIcons.Play,
                        theme.accent,
                        enabled = !session.building
                    ) { session.buildAndRun() }
                    IconAction(MonoIcons.More, theme.textSecondary) { menuOpen = !menuOpen }
                }
                Hairline(theme)
                ProgressLine(session, theme)
            }

            // ---- find bar -----------------------------------------------
            AnimatedVisibility(
                visible = findVisible,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val tab = session.active
                if (tab != null) {
                    FindReplaceBar(tab.state, tab.controller, theme) { findVisible = false }
                }
            }

            // ---- editor -------------------------------------------------
            Box(Modifier.weight(1f)) {
                val tab = session.active
                if (tab != null) {
                    MonoEditor(
                        state = tab.state,
                        controller = tab.controller,
                        theme = theme,
                        modifier = Modifier.fillMaxSize(),
                        completionProvider = completionProvider
                    )

                    // Diagnostic chip: shows the tapped underline's message.
                    val chip = tab.state.diagnosticChip
                    if (chip != null) {
                        LaunchedEffect(chip) {
                            kotlinx.coroutines.delay(2500)
                            tab.state.diagnosticChip = null
                        }
                        // Typing or moving to another line dismisses it.
                        LaunchedEffect(tab.state.textRevision, tab.state.caretLine) {
                            if (tab.state.caretLine != chip.line) tab.state.diagnosticChip = null
                        }
                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(8.dp)
                                .background(theme.surface)
                                .border(1.dp, theme.border)
                                .clickable { tab.state.diagnosticChip = null }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            MonoText(
                                if (chip.isError) "error" else "warning",
                                theme,
                                color = theme.accent,
                                fontSize = 10
                            )
                            MonoText(chip.message, theme, fontSize = 11)
                        }
                    }

                    // External change banner.
                    if (tab.externalChange) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp)
                                .background(theme.surface)
                                .border(1.dp, theme.border)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MonoText("changed on disk", theme, fontSize = 11)
                            ActionChip("reload", theme) { session.reloadTab(tab) }
                            ActionChip("keep", theme) { tab.externalChange = false }
                        }
                    }
                }

                // Exit-focus affordance: clearly visible, with a generous tap target.
                if (focusMode) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(40.dp)
                            .background(theme.surfaceHigh.copy(alpha = 0.92f))
                            .border(1.dp, theme.border)
                            .clickable { focusMode = false }
                    ) {
                        dev.mono.app.ui.MonoIcon(
                            MonoIcons.Focus,
                            theme.textPrimary,
                            size = 18.dp
                        )
                    }
                }
            }

            // ---- output panel --------------------------------------------
            OutputPanel(session, theme)

            // ---- symbol row ------------------------------------------------
            if (!focusMode) {
                val tab = session.active
                if (tab != null) {
                    SymbolRow(tab.controller, theme)
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }

        // ---- modules drawer ---------------------------------------------
        if (drawerOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0x66000000))
                    .clickable { drawerOpen = false }
            )
            Box(
                contentAlignment = Alignment.TopStart,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxSize()
                    .width(304.dp)
                    .background(theme.surface)
            ) {
                ModulesDrawer(container, session, theme) { drawerOpen = false }
            }
        }

        // ---- overflow menu -------------------------------------------------
        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0x33000000))
                    .clickable { menuOpen = false }
            )
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 46.dp, end = 8.dp)
                    .width(216.dp)
                    .background(theme.surfaceHigh)
                    .border(1.dp, theme.border)
            ) {
                MenuRow("undo", theme) {
                    menuOpen = false
                    session.active?.controller?.undo()
                }
                MenuRow("redo", theme) {
                    menuOpen = false
                    session.active?.controller?.redo()
                }
                MenuRow("find / replace", theme) {
                    menuOpen = false
                    findVisible = !findVisible
                }
                MenuRow("open file…", theme) {
                    menuOpen = false
                    fileSheetOpen = true
                }
                MenuRow("open from…", theme) {
                    menuOpen = false
                    runCatching { openFromSystem.launch(arrayOf("*/*")) }
                }
                MenuRow("save now", theme) {
                    menuOpen = false
                    container.scope.launch { session.saveAll() }
                }
                MenuRow("focus mode", theme) {
                    menuOpen = false
                    focusMode = true
                }
                MenuRow(
                    if (container.settings.themeMode == dev.mono.storage.model.ThemeMode.DARK) "light theme" else "dark theme",
                    theme
                ) {
                    menuOpen = false
                    container.settings.themeMode =
                        if (container.settings.themeMode == dev.mono.storage.model.ThemeMode.DARK)
                            dev.mono.storage.model.ThemeMode.LIGHT
                        else dev.mono.storage.model.ThemeMode.DARK
                }
                MenuRow("settings", theme) {
                    menuOpen = false
                    onOpenSettings()
                }
            }
        }

        // ---- open file sheet ------------------------------------------------
        if (fileSheetOpen) {
            Dialog(onDismissRequest = { fileSheetOpen = false }) {
                OpenFileSheet(container, session, theme) { fileSheetOpen = false }
            }
        }
    }
}

// ---------------------------------------------------------------- sub views

@Composable
private fun TabsRow(session: ProjectSession, theme: MonoTheme, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(session.activeIndex) {
        if (session.activeIndex >= 0) {
            runCatching { listState.animateScrollToItem(session.activeIndex) }
        }
    }
    LazyRow(
        state = listState,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .padding(horizontal = 2.dp)
    ) {
        itemsIndexed(session.tabs, key = { _, t -> t.path }) { index, tab ->
            val activeNow = index == session.activeIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(if (activeNow) theme.surfaceHigh else theme.background)
                    .border(1.dp, if (activeNow) theme.border else theme.background)
                    .clickable { session.activeIndex = index }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                BasicText(
                    tab.name,
                    style = TextStyle(
                        color = if (activeNow) theme.accent else theme.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
                if (tab.dirty) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(theme.accent)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { session.closeTab(index) }
                ) {
                    dev.mono.app.ui.MonoIcon(MonoIcons.Close, theme.textTertiary, size = 11.dp)
                }
            }
        }
    }
}

/** Thin build progress line directly under the top bar. */
@Composable
private fun ProgressLine(session: ProjectSession, theme: MonoTheme) {
    if (!session.building) return
    Canvas(Modifier.fillMaxWidth().height(2.dp)) {
        drawRect(theme.border, size = size)
        val frac = session.buildProgress.coerceIn(0f, 1f)
        drawRect(
            theme.accent.copy(alpha = 0.65f),
            size = androidx.compose.ui.geometry.Size(size.width * frac, size.height)
        )
    }
}

@Composable
private fun MenuRow(label: String, theme: MonoTheme, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        MonoText(label, theme, fontSize = 12)
    }
}

@Composable
private fun OpenFileSheet(
    container: AppContainer,
    session: ProjectSession,
    theme: MonoTheme,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .background(theme.surface)
            .border(1.dp, theme.border)
            .padding(12.dp)
    ) {
        MonoText("open file", theme, fontSize = 13, bold = true)
        Spacer(Modifier.height(8.dp))
        MonoText("project", theme, color = theme.textTertiary, fontSize = 10)
        for (f in container.repository.projectFiles(session.project.name)) {
            MenuRow(f.name, theme) {
                container.scope.launch { session.openTab(f.path) }
                onClose()
            }
        }
        Spacer(Modifier.height(8.dp))
        MonoText("modules", theme, color = theme.textTertiary, fontSize = 10)
        for (m in container.repository.listModules()) {
            MenuRow(m.name, theme) {
                container.scope.launch { session.openTab(m.path) }
                onClose()
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            ActionChip("close", theme, onClick = onClose)
        }
    }
}
