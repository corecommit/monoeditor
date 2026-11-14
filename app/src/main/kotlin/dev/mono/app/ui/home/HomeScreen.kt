package dev.mono.app.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import dev.mono.app.session.AppContainer
import dev.mono.app.ui.ActionChip
import dev.mono.app.ui.ConfirmDialog
import dev.mono.app.ui.Hairline
import dev.mono.app.ui.IconAction
import dev.mono.app.ui.MonoText
import dev.mono.app.ui.NameDialog
import dev.mono.app.ui.SectionLabel
import dev.mono.app.ui.icons.MonoIcons
import dev.mono.compiler.toolchain.ToolchainManager
import dev.mono.editor.render.MonoTheme
import dev.mono.storage.model.ProjectInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Project list, toolchain status card, new-project dialog. */
@Composable
fun HomeScreen(
    container: AppContainer,
    theme: MonoTheme,
    onOpenProject: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var projects by remember { mutableStateOf(container.repository.projects()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectInfo?>(null) }

    // Refresh when the underlying storage changes.
    LaunchedEffect(container.projectsRevision.value) {
        projects = container.repository.projects()
    }

    Column(Modifier.fillMaxSize()) {
        // Header.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            MonoText("Mono", theme, fontSize = 20, bold = true)
            Spacer(Modifier.width(10.dp))
            MonoText("— c++ studio", theme, color = theme.textTertiary, fontSize = 11)
            Spacer(Modifier.weight(1f))
            IconAction(MonoIcons.Settings, theme.textSecondary) { onOpenSettings() }
        }
        Hairline(theme)

        LazyColumn(Modifier.weight(1f)) {
            item {
                ToolchainCard(container, theme)
            }
            item { SectionLabel("PROJECTS", theme) }
            if (projects.isEmpty()) {
                item {
                    MonoText(
                        "No projects yet. Create one below.",
                        theme,
                        color = theme.textTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            items(projects, key = { it.name }) { p ->
                ProjectRow(
                    project = p,
                    theme = theme,
                    onOpen = {
                        container.openSession(p)
                        onOpenProject()
                    },
                    onRename = { renameTarget = p },
                    onDelete = { deleteTarget = p }
                )
                Hairline(theme, Modifier.padding(start = 16.dp))
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showNewDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    IconAction(MonoIcons.Plus, theme.accent, size = 18.dp) { }
                    Spacer(Modifier.width(8.dp))
                    MonoText("new project", theme, color = theme.textSecondary)
                }
            }
        }
    }

    if (showNewDialog) {
        NameDialog(
            title = "New project",
            hint = "project name",
            theme = theme,
            onDismiss = { showNewDialog = false },
            onConfirm = { name ->
                showNewDialog = false
                val res = container.repository.createProject(name)
                val ok = res.getOrNull()
                if (ok != null) {
                    projects = container.repository.projects()
                    container.openSession(ok)
                    onOpenProject()
                }
            }
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "Rename project",
            hint = target.name,
            theme = theme,
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                container.repository.renameProject(target.name, name).getOrNull()?.let {
                    projects = container.repository.projects()
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
                container.repository.deleteProject(target.name)
                projects = container.repository.projects()
                deleteTarget = null
            }
        )
    }
}

@Composable
private fun ToolchainCard(container: AppContainer, theme: MonoTheme) {
    val status by container.toolchain.status.collectAsState()
    val s = status
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(theme.surface)
            .border(1.dp, theme.border)
            .padding(12.dp)
    ) {
        MonoText("toolchain", theme, fontSize = 11, color = theme.textTertiary)
        Spacer(Modifier.height(6.dp))
        val line = when (s) {
            is ToolchainManager.Status.Ready -> "clang ready — ${s.compiler}"
            is ToolchainManager.Status.Extracting ->
                if (s.progress >= 0) "extracting headers… ${(s.progress * 100).toInt()}%"
                else "downloading headers…"
            is ToolchainManager.Status.NeedsExtraction ->
                if (s.hasBundledAssets) "bundled headers ready to extract" else "sysroot missing"
            is ToolchainManager.Status.MissingBinaries -> "no compiler in this build"
            is ToolchainManager.Status.Error -> "error: ${s.message}"
        }
        MonoText(line, theme, fontSize = 12)
        val needs = s as? ToolchainManager.Status.NeedsExtraction
        if (needs != null && needs.hasBundledAssets) {
            Spacer(Modifier.height(8.dp))
            ActionChip("extract now", theme) {
                container.scope.launch { container.toolchain.ensureReady() }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectInfo,
    theme: MonoTheme,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MonoText(project.name, theme, fontSize = 15, bold = true)
            Spacer(Modifier.weight(1f))
            val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
            MonoText(fmt.format(Date()), theme, color = theme.textTertiary, fontSize = 11)
            IconAction(MonoIcons.More, theme.textTertiary, size = 16.dp) { menu = !menu }
        }
        MonoText("projects/${project.name}/main.cpp", theme, color = theme.textTertiary, fontSize = 11)
        if (menu) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip("rename", theme, onClick = onRename)
                ActionChip("delete", theme, onClick = onDelete)
            }
        }
    }
}
