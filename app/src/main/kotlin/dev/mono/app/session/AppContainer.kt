package dev.mono.app.session

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import dev.mono.compiler.build.BuildEngine
import dev.mono.compiler.toolchain.ToolchainManager
import dev.mono.indexer.index.SymbolDatabase
import dev.mono.storage.fs.ProjectRepository
import dev.mono.storage.fs.StorageWatcher
import dev.mono.storage.model.ProjectInfo
import dev.mono.storage.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Application-scoped dependency holder: settings, storage, indexer, toolchain
 * and the current project session. Survives configuration changes because it
 * lives on [MonoApplication], not in composition.
 */
class AppContainer(private val app: Application) {

    val settings = Settings(app)
    val repository = ProjectRepository(app, settings)
    val symbolDb = SymbolDatabase()
    val toolchain = ToolchainManager(app)
    val buildEngine = BuildEngine(app, toolchain)
    val watcher = StorageWatcher()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Late-bound clipboard bridge; the editor screen assigns the real one. */
    val clipboardBridge = LateClipboard()

    /** Bumped whenever the project list changes (Home refresh). */
    val projectsRevision = mutableStateOf(0)

    private var _session: ProjectSession? = null
    val session: ProjectSession? get() = _session

    /** True while a session is open; observed from composition. */
    val sessionOpen = mutableStateOf(false)

    /** Application context for permission and resolver calls. */
    val appContext: android.content.Context get() = app

    /** Application cache directory, used for build staging. */
    val cacheDir: File get() = app.cacheDir

    fun start() {
        scope.launch {
            // First-run seeding writes files — keep it off the main thread,
            // then let Home know the list may have changed.
            withContext(Dispatchers.IO) { repository.ensureSeeded() }
            projectsRevision.value++
            runCatching { toolchain.ensureReady() }
        }
    }

    fun openSession(project: ProjectInfo): ProjectSession {
        _session?.close()
        val s = ProjectSession(this, project)
        _session = s
        sessionOpen.value = true
        settings.lastProject = project.name
        return s
    }

    fun closeSession() {
        _session?.close()
        _session = null
        sessionOpen.value = false
    }

    suspend fun saveAll() {
        _session?.saveAll()
    }

    fun shutdown() {
        // onTerminate is rare and synchronous; flush tiny writes before the
        // scope dies.
        runCatching { runBlocking { saveAll() } }
        watcher.stop()
        scope.cancel()
    }

    /** Internal storage root, used by the watcher. */
    fun internalRoot(): File = File(app.filesDir, "Mono")

    class LateClipboard : dev.mono.editor.control.ClipboardAccess {
        var impl: dev.mono.editor.control.ClipboardAccess? = null
        override fun getText(): String? = impl?.getText()
        override fun setText(s: String) {
            impl?.setText(s)
        }
    }
}
