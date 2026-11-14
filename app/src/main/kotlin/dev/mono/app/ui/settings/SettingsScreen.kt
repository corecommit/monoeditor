package dev.mono.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mono.app.session.AppContainer
import dev.mono.app.ui.ActionChip
import dev.mono.app.ui.Hairline
import dev.mono.app.ui.IconAction
import dev.mono.app.ui.MonoText
import dev.mono.app.ui.SectionLabel
import dev.mono.app.ui.icons.MonoIcons
import dev.mono.compiler.model.CppStandard
import dev.mono.compiler.model.OptLevel
import dev.mono.compiler.toolchain.ToolchainManager
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.view.MinimalTextField
import dev.mono.storage.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: appearance, editor, compiler, storage, toolchain. */
@Composable
fun SettingsScreen(
    container: AppContainer,
    theme: MonoTheme,
    onBack: () -> Unit
) {
    val settings = container.settings
    var revision by remember { mutableStateOf(0) }

    fun bump() {
        revision++
    }

    var url by remember { mutableStateOf(settings.toolchainUrl) }
    val status by container.toolchain.status.collectAsState()

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                container.appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            settings.sharedRootUri = uri.toString()
            settings.useSharedStorage = true
            container.scope.launch {
                withContext(Dispatchers.IO) { container.repository.ensureSeeded() }
                bump()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconAction(MonoIcons.ChevronLeft, theme.textSecondary) { onBack() }
            MonoText("settings", theme, fontSize = 16, bold = true)
        }
        Hairline(theme)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            // ------------------------------------------------ appearance
            SectionLabel("APPEARANCE", theme)
            SettingRow(theme) {
                MonoText("theme", theme)
                Spacer(Modifier.weight(1f))
                ActionChip(
                    if (settings.themeMode == ThemeMode.DARK) "dark" else "light",
                    theme
                ) {
                    settings.themeMode =
                        if (settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
                    bump()
                }
            }
            SettingRow(theme) {
                MonoText("editor font", theme)
                Spacer(Modifier.weight(1f))
                ActionChip("-", theme) {
                    settings.fontSizeSp = (settings.fontSizeSp - 1f).coerceIn(8f, 32f)
                    container.session?.tabs?.forEach { it.state.fontSizeSp = settings.fontSizeSp }
                    bump()
                }
                Spacer(Modifier.padding(2.dp))
                MonoText("${settings.fontSizeSp.toInt()}sp", theme, color = theme.textSecondary)
                Spacer(Modifier.padding(2.dp))
                ActionChip("+", theme) {
                    settings.fontSizeSp = (settings.fontSizeSp + 1f).coerceIn(8f, 32f)
                    container.session?.tabs?.forEach { it.state.fontSizeSp = settings.fontSizeSp }
                    bump()
                }
            }
            SettingRow(theme) {
                MonoText("indent width", theme)
                Spacer(Modifier.weight(1f))
                for (w in listOf(2, 4, 8)) {
                    ActionChip("$w", theme, enabledStyle = settings.indentWidth != w) {
                        settings.indentWidth = w
                        container.session?.tabs?.forEach { it.state.indentWidth = w }
                        bump()
                    }
                    Spacer(Modifier.padding(2.dp))
                }
            }

            // ------------------------------------------------ compiler
            SectionLabel("COMPILER", theme)
            SettingRow(theme) {
                MonoText("standard", theme)
                Spacer(Modifier.weight(1f))
                for (s in CppStandard.entries) {
                    ActionChip(s.label, theme, enabledStyle = settings.cppStandard != s.flag) {
                        settings.cppStandard = s.flag
                        bump()
                    }
                    Spacer(Modifier.padding(2.dp))
                }
            }
            SettingRow(theme) {
                MonoText("optimization", theme)
                Spacer(Modifier.weight(1f))
                for (o in listOf(OptLevel.O0, OptLevel.O1, OptLevel.O2, OptLevel.O3, OptLevel.OS)) {
                    ActionChip(o.flag.removePrefix("-O"), theme, enabledStyle = settings.optLevel != o.flag) {
                        settings.optLevel = o.flag
                        bump()
                    }
                    Spacer(Modifier.padding(2.dp))
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                MonoText("extra flags", theme, color = theme.textTertiary, fontSize = 11)
                Spacer(Modifier.height(6.dp))
                var flags by remember(revision) { mutableStateOf(settings.extraFlags) }
                MinimalTextField(
                    value = flags,
                    onValueChange = { flags = it; settings.extraFlags = it },
                    hint = "-Wall -Wextra …",
                    theme = theme,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            SettingRow(theme) {
                MonoText("precompiled header", theme)
                Spacer(Modifier.weight(1f))
                ActionChip(if (settings.usePch) "on" else "off", theme) {
                    settings.usePch = !settings.usePch
                    bump()
                }
            }
            if (settings.usePch) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    MonoText("prefix header includes (one per line)", theme, color = theme.textTertiary, fontSize = 11)
                    Spacer(Modifier.height(6.dp))
                    var pch by remember(revision) { mutableStateOf(settings.pchIncludes) }
                    MinimalTextField(
                        value = pch,
                        onValueChange = { pch = it; settings.pchIncludes = it },
                        hint = "<cstdio> …",
                        theme = theme,
                        modifier = Modifier.fillMaxWidth().height(96.dp)
                    )
                }
            }
            SettingRow(theme) {
                MonoText("run timeout (s)", theme)
                Spacer(Modifier.weight(1f))
                ActionChip("-", theme) {
                    settings.runTimeoutSec = (settings.runTimeoutSec - 5).coerceIn(1, 600); bump()
                }
                Spacer(Modifier.padding(2.dp))
                MonoText("${settings.runTimeoutSec}s", theme, color = theme.textSecondary)
                Spacer(Modifier.padding(2.dp))
                ActionChip("+", theme) {
                    settings.runTimeoutSec = (settings.runTimeoutSec + 5).coerceIn(1, 600); bump()
                }
            }
            SettingRow(theme) {
                MonoText("autosave (s)", theme)
                Spacer(Modifier.weight(1f))
                ActionChip("-", theme) {
                    settings.autosaveSec = (settings.autosaveSec - 1).coerceIn(1, 60); bump()
                }
                Spacer(Modifier.padding(2.dp))
                MonoText("${settings.autosaveSec}s", theme, color = theme.textSecondary)
                Spacer(Modifier.padding(2.dp))
                ActionChip("+", theme) {
                    settings.autosaveSec = (settings.autosaveSec + 1).coerceIn(1, 60); bump()
                }
            }

            // ------------------------------------------------ storage
            SectionLabel("STORAGE", theme)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                MonoText(
                    if (container.repository.usingSharedStorage)
                        "using shared Documents/Mono (SAF tree)"
                    else "using app storage — visible as \"Mono\" in the Files app",
                    theme,
                    color = theme.textSecondary,
                    fontSize = 11
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("use app storage", theme, enabledStyle = !settings.useSharedStorage) {
                        settings.useSharedStorage = false
                        container.scope.launch {
                            withContext(Dispatchers.IO) { container.repository.ensureSeeded() }
                            bump()
                        }
                    }
                    ActionChip("use Documents/Mono", theme, enabledStyle = !container.repository.usingSharedStorage) {
                        runCatching { treePicker.launch(null) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                MonoText(
                    "switching does not move files; both roots keep their data",
                    theme,
                    color = theme.textTertiary,
                    fontSize = 10
                )
            }

            // ------------------------------------------------ toolchain
            SectionLabel("TOOLCHAIN", theme)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val s = status
                val line = when (s) {
                    is ToolchainManager.Status.Ready -> "clang ready — ${s.compiler}"
                    is ToolchainManager.Status.Extracting ->
                        if (s.progress >= 0) "extracting… ${(s.progress * 100).toInt()}%" else "downloading…"
                    is ToolchainManager.Status.NeedsExtraction ->
                        if (s.hasBundledAssets) "bundled headers ready" else "sysroot missing (set URL below)"
                    is ToolchainManager.Status.MissingBinaries ->
                        "no compiler binaries in this build — install a toolchain-enabled APK"
                    is ToolchainManager.Status.Error -> "error: ${s.message}"
                }
                MonoText(line, theme, color = theme.textSecondary, fontSize = 11)
                Spacer(Modifier.height(8.dp))
                MinimalTextField(
                    value = url,
                    onValueChange = { url = it; settings.toolchainUrl = it },
                    hint = "https://…/sysroot-only.zip",
                    theme = theme,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("verify / extract", theme) {
                        container.scope.launch { container.toolchain.ensureReady() }
                    }
                    ActionChip("download", theme, enabledStyle = url.isNotBlank()) {
                        val target = url
                        if (target.isNotBlank()) {
                            container.scope.launch {
                                container.toolchain.downloadAndInstall(target)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ about
            SectionLabel("ABOUT", theme)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                MonoText("Mono 1.0 — a minimal C++ studio.", theme, color = theme.textSecondary, fontSize = 11)
                Spacer(Modifier.height(4.dp))
                MonoText(
                    "shades only · no hues · bundles community clang builds",
                    theme,
                    color = theme.textTertiary,
                    fontSize = 10
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingRow(theme: MonoTheme, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        content = content
    )
}
