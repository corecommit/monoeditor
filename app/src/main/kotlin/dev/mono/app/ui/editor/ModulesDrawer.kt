package dev.mono.app.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mono.app.session.AppContainer
import dev.mono.app.session.ProjectSession
import dev.mono.app.ui.ActionChip
import dev.mono.app.ui.ConfirmDialog
import dev.mono.app.ui.Hairline
import dev.mono.app.ui.IconAction
import dev.mono.app.ui.MonoText
import dev.mono.app.ui.NameDialog
import dev.mono.app.ui.icons.MonoIcons
import dev.mono.editor.render.MonoTheme
import dev.mono.indexer.index.Includes
import dev.mono.storage.model.FileMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modules drawer: list, create, rename, delete, import from the Files app.
 * Each module row shows its symbol count and an include-guard reminder for
 * headers missing #pragma once.
 */
@Composable
fun ModulesDrawer(
    container: AppContainer,
    session: ProjectSession,
    theme: MonoTheme,
    onClose: () -> Unit
) {
    var modules by remember { mutableStateOf(container.repository.listModules()) }
    val symbolCounts = remember { mutableStateMapOf<String, Int>() }
    val guardMissing = remember { mutableStateMapOf<String, Boolean>() }
    var newModule by remember { mutableStateOf(false) }
    var newModuleHeader by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<FileMeta?>(null) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            container.scope.launch {
                container.repository.importModules(uris)
                modules = withContext(Dispatchers.IO) { container.repository.listModules() }
                session.indexAllModules()
            }
        }
    }

    fun refresh() {
        container.scope.launch {
            modules = withContext(Dispatchers.IO) { container.repository.listModules() }
        }
    }

    // Load symbol counts + guard status in the background.
    LaunchedEffect(session.modulesRevision, modules.size) {
        for (m in modules) {
            // Index keys are full relative paths ("modules/<name>").
            val idx = container.symbolDb.indexFor(m.path)
            symbolCounts[m.name] = idx?.symbols?.size ?: 0
            val src = withContext(Dispatchers.IO) { container.repository.read(m.path) } ?: ""
            guardMissing[m.name] = m.isHeader && !Includes.hasIncludeGuard(src)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.surface)
    ) {
        // Header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            MonoText("modules", theme, fontSize = 15, bold = true)
            Spacer(Modifier.weight(1f))
            IconAction(MonoIcons.Close, theme.textSecondary, size = 18.dp) { onClose() }
        }
        Hairline(theme)

        // Info line.
        BasicText(
            "#include <name> resolves to modules/",
            style = TextStyle(color = theme.textTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // Actions.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            ActionChip("+ .h", theme) { newModule = true; newModuleHeader = true }
            ActionChip("+ .cpp", theme) { newModule = true; newModuleHeader = false }
            ActionChip("import", theme) {
                runCatching { importPicker.launch(arrayOf("*/*")) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Hairline(theme)

        LazyColumn(Modifier.weight(1f)) {
            if (modules.isEmpty()) {
                item {
                    MonoText(
                        "no modules yet — create one above",
                        theme,
                        color = theme.textTertiary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(modules, key = { it.path }) { m ->
                ModuleRow(
                    module = m,
                    count = symbolCounts[m.name] ?: 0,
                    guardMissing = guardMissing[m.name] == true && m.isHeader,
                    theme = theme,
                    onOpen = {
                        container.scope.launch { session.openTab(m.path) }
                        onClose()
                    },
                    onRename = { renameTarget = m },
                    onDelete = { deleteTarget = m },
                    onAddGuard = {
                        container.scope.launch {
                            withContext(Dispatchers.IO) {
                                val src = container.repository.read(m.path) ?: ""
                                container.repository.write(m.path, "#pragma once\n\n$src")
                            }
                            session.reindexModule(m.path)
                            guardMissing[m.name] = false
                        }
                    }
                )
                Hairline(theme, Modifier.padding(start = 12.dp))
            }
        }
    }

    if (newModule) {
        NameDialog(
            title = if (newModuleHeader) "New header module" else "New source module",
            hint = "module name",
            theme = theme,
            onDismiss = { newModule = false },
            onConfirm = { name ->
                newModule = false
                container.scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        container.repository.createModule(name, newModuleHeader)
                    }
                    if (res.getOrNull() != null) {
                        refresh()
                        session.indexAllModules()
                    }
                }
            }
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "Rename module",
            hint = target.name,
            theme = theme,
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                container.scope.launch {
                    container.repository.renameModule(target.name, name).getOrNull()?.let {
                        refresh()
                        session.indexAllModules()
                    }
                }
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Delete \"${target.name}\"?",
            theme = theme,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                container.scope.launch {
                    container.repository.deleteModule(target.name)
                    refresh()
                    session.indexAllModules()
                }
                deleteTarget = null
            }
        )
    }
}

@Composable
private fun ModuleRow(
    module: FileMeta,
    count: Int,
    guardMissing: Boolean,
    theme: MonoTheme,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddGuard: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(module.name, theme, fontSize = 13, bold = true)
            Spacer(Modifier.width(8.dp))
            MonoText("$count sym", theme, color = theme.textTertiary, fontSize = 10)
            Spacer(Modifier.weight(1f))
            IconAction(MonoIcons.More, theme.textTertiary, size = 14.dp) { menu = !menu }
        }
        if (guardMissing) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoText("no include guard", theme, color = theme.diagnosticWarning, fontSize = 10)
                ActionChip("add #pragma once", theme, onClick = onAddGuard)
            }
        }
        if (menu) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("open", theme, onClick = onOpen)
                ActionChip("rename", theme, onClick = onRename)
                ActionChip("delete", theme, onClick = onDelete)
            }
        }
    }
}
