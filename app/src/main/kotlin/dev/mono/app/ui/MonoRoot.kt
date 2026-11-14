package dev.mono.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.mono.app.session.AppContainer
import dev.mono.app.ui.editor.EditorScreen
import dev.mono.app.ui.home.HomeScreen
import dev.mono.app.ui.settings.SettingsScreen
import dev.mono.editor.control.ClipboardAccess
import dev.mono.editor.render.MonoThemes
import dev.mono.storage.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Top-level navigation: Home, Editor, Settings. */
sealed class Screen {
    data object Home : Screen()
    data object Editor : Screen()
    data object Settings : Screen()
}

@Composable
fun MonoRoot(container: AppContainer) {
    val settingsRevision by container.settings.revision.collectAsState()
    val theme = remember(settingsRevision) {
        if (container.settings.themeMode == ThemeMode.LIGHT) MonoThemes.light else MonoThemes.dark
    }

    var screen by remember { androidx.compose.runtime.mutableStateOf<Screen>(Screen.Home) }

    // Wire the clipboard bridge so controllers can copy/paste.
    val clipboardManager = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        container.clipboardBridge.impl = object : ClipboardAccess {
            override fun getText(): String? = clipboardManager.getText()?.text
            override fun setText(s: String) = clipboardManager.setText(AnnotatedString(s))
        }
    }

    // Restore the last session.
    LaunchedEffect(Unit) {
        val last = container.settings.lastProject
        if (last.isNotBlank() && container.session == null) {
            val info = withContext(Dispatchers.IO) {
                container.repository.projects().firstOrNull { it.name == last }
            }
            if (info != null) container.openSession(info)
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        when (screen) {
            Screen.Editor -> {
                container.scope.launch {
                    container.saveAll()
                    container.closeSession()
                    screen = Screen.Home
                }
            }
            else -> screen = Screen.Home
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .statusBarsPadding()
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = screen,
            transitionSpec = {
                val dir = if (targetState == Screen.Home) -1 else 1
                (slideInHorizontally(tween(220)) { it / 6 * dir } + fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(180)) { -it / 6 * dir } + fadeOut(tween(140)))
            },
            label = "screens"
        ) { current ->
            when (current) {
                Screen.Home -> HomeScreen(
                    container = container,
                    theme = theme,
                    onOpenProject = { screen = Screen.Editor },
                    onOpenSettings = { screen = Screen.Settings }
                )
                Screen.Editor -> {
                    val session = container.session
                    if (session != null) {
                        EditorScreen(
                            container = container,
                            session = session,
                            theme = theme,
                            onExit = {
                                container.scope.launch {
                                    container.saveAll()
                                    container.closeSession()
                                    screen = Screen.Home
                                }
                            },
                            onOpenSettings = { screen = Screen.Settings }
                        )
                    } else {
                        HomeScreen(container, theme, { screen = Screen.Editor }, { screen = Screen.Settings })
                    }
                }
                Screen.Settings -> SettingsScreen(
                    container = container,
                    theme = theme,
                    onBack = { screen = if (container.session != null) Screen.Editor else Screen.Home }
                )
            }
        }
    }
}
