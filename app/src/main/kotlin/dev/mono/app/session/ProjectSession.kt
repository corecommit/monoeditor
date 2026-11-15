package dev.mono.app.session

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.mono.compiler.model.BuildEvent
import dev.mono.compiler.model.CppStandard
import dev.mono.compiler.model.OptLevel
import dev.mono.editor.control.EditorController
import dev.mono.editor.state.EditorDiagnostic
import dev.mono.editor.state.EditorState
import dev.mono.indexer.index.Includes
import dev.mono.indexer.symbols.CppSymbolParser
import dev.mono.storage.model.ProjectInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One open project: editor tabs, autosave, live external-change reloading,
 * background symbol indexing, build & run state, console and diagnostics.
 *
 * All file reads/writes, include checks and indexing run on Dispatchers.IO;
 * only the editor state (Compose state) is touched on the main thread.
 */
class ProjectSession(
    val container: AppContainer,
    val project: ProjectInfo
) {

    class Tab(
        val path: String // relative to storage root, e.g. projects/Hello/main.cpp
    ) {
        val name: String get() = path.substringAfterLast('/')
        lateinit var state: EditorState
        lateinit var controller: EditorController
        var dirty by mutableStateOf(false)
        var externalChange by mutableStateOf(false)
        var lastSavedChecksum: String = ""
    }

    val tabs = mutableStateListOf<Tab>()
    var activeIndex by mutableIntStateOf(0)
    val active: Tab? get() = tabs.getOrNull(activeIndex)

    // Build / run state.
    var building by mutableStateOf(false)
    var buildProgress by mutableFloatStateOf(0f)
    var buildStatusText by mutableStateOf("")
    val buildLog = mutableStateListOf<String>()
    val diagnostics = mutableStateListOf<Issue>()
    var lastBuildOk by mutableStateOf(false)

    var running by mutableStateOf(false)
    var exitCode by mutableIntStateOf(-1)
    var runFinished by mutableStateOf(false)
    val console = mutableStateListOf<ConsoleLine>()
    var binaryPath: String? = null

    /** Bumped whenever the module list changes (drawer refresh). */
    var modulesRevision by mutableIntStateOf(0)

    data class Issue(
        val file: String,
        val line: Int,
        val col: Int,
        val isError: Boolean,
        val message: String
    )

    data class ConsoleLine(val text: String, val kind: Int) {
        companion object {
            const val OUT = 0
            const val ERR = 1
            const val IN = 2
            const val META = 3
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner = dev.mono.compiler.run.ProcessRunner()
    private var buildJob: Job? = null
    private var runJob: Job? = null
    private var reindexJob: Job? = null

    private val settings get() = container.settings
    private val repository get() = container.repository

    init {
        scope.launch { openTab(repository.projectMainPath(project.name)) }
        scope.launch {
            while (isActive) {
                delay(settings.autosaveSec * 1000L)
                saveAll()
            }
        }
        scope.launch { indexAllModules() }
        startWatcher()
    }

    // ------------------------------------------------------------------ tabs

    suspend fun openTab(path: String): Tab? {
        val existing = tabs.firstOrNull { it.path == path }
        if (existing != null) {
            activeIndex = tabs.indexOf(existing)
            return existing
        }
        val content = withContext(Dispatchers.IO) { repository.read(path) } ?: return null
        val state = EditorState(content)
        state.indentWidth = settings.indentWidth
        state.fontSizeSp = settings.fontSizeSp
        val controller = EditorController(state, container.clipboardBridge) { c ->
            onTabEdited(c)
        }
        val tab = Tab(path)
        tab.state = state
        tab.controller = controller
        tab.lastSavedChecksum = CppSymbolParser.checksum(content)
        tabs.add(tab)
        activeIndex = tabs.size - 1
        refreshIncludeDiagnostics(tab)
        scope.launch { reindexTab(tab) }
        return tab
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        // Flush before dropping the tab. Writes are tiny (tmp + rename), so
        // blocking briefly here is the only way to guarantee they land.
        runCatching { runBlocking { saveTab(tab) } }
        tabs.removeAt(index)
        activeIndex = (if (activeIndex > index) activeIndex - 1 else activeIndex).coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    }

    suspend fun saveTab(tab: Tab) {
        val text = tab.state.text.text()
        if (!tab.dirty && tab.lastSavedChecksum == CppSymbolParser.checksum(text)) return
        val ok = withContext(Dispatchers.IO) { repository.write(tab.path, text) }
        if (ok) {
            tab.dirty = false
            tab.externalChange = false
            tab.lastSavedChecksum = CppSymbolParser.checksum(text)
        }
    }

    suspend fun saveAll() {
        for (t in tabs) if (t.dirty) saveTab(t)
    }

    suspend fun jumpTo(fileName: String, line: Int, col: Int) {
        val name = fileName.substringAfterLast('/')
        var tab = tabs.firstOrNull { it.path.substringAfterLast('/') == name }
        if (tab == null) {
            val meta = withContext(Dispatchers.IO) {
                repository.projectFiles(project.name).firstOrNull { it.name == name }
            } ?: return
            tab = openTab("projects/${project.name}/${meta.name}") ?: return
        }
        activeIndex = tabs.indexOf(tab)
        tab.controller.moveCaret((line - 1).coerceAtLeast(0), (col - 1).coerceAtLeast(0))
    }

    // -------------------------------------------------------------- editing

    private fun onTabEdited(controller: EditorController) {
        val tab = tabs.firstOrNull { it.controller === controller } ?: return
        tab.dirty = true
        tab.externalChange = false
        reindexJob?.cancel()
        reindexJob = scope.launch {
            delay(500)
            reindexTab(tab)
            refreshIncludeDiagnostics(tab)
        }
    }

    private suspend fun reindexTab(tab: Tab) {
        container.symbolDb.reindex(tab.path, tab.state.text.text())
    }

    private suspend fun refreshIncludeDiagnostics(tab: Tab) {
        val text = tab.state.text.text()
        val directives = Includes.parse(text)
        val candidates = directives.filter { !Includes.isStandardHeader(it.target) }
        // Disk probes (exists()) stay off the main thread.
        val missing = withContext(Dispatchers.IO) {
            candidates.filter { d ->
                !repository.root.exists("modules/${d.target}") &&
                        !repository.root.exists("projects/${project.name}/${d.target}")
            }
        }
        val problems = ArrayList<EditorDiagnostic>()
        for (d in missing) {
            // Any angle include that resolves to no user file is a system
            // header — the compiler owns those, never an error here.
            if (d.angle) continue
            problems.add(
                EditorDiagnostic(
                    line = d.line,
                    colStart = 0,
                    colEnd = 0,
                    isError = true,
                    message = "Module '${d.target}' not found — create it in the Modules drawer or check the name."
                )
            )
        }
        tab.controller.setDiagnostics(problems)
    }

    /** Banner button: re-read the file from disk and reload in place. */
    fun reloadTab(tab: Tab) {
        scope.launch {
            val content = withContext(Dispatchers.IO) { repository.read(tab.path) } ?: return@launch
            applyExternalContent(tab, content)
        }
    }

    private fun applyExternalContent(tab: Tab, content: String) {
        tab.controller.reloadExternal(content)
        tab.dirty = false
        tab.externalChange = false
        tab.lastSavedChecksum = CppSymbolParser.checksum(content)
        scope.launch { reindexTab(tab) }
    }

    // ------------------------------------------------------------- modules

    suspend fun indexAllModules() {
        val sources = withContext(Dispatchers.IO) {
            repository.listModules().mapNotNull { m ->
                repository.read(m.path)?.let { m.path to it }
            }
        }
        for ((path, src) in sources) {
            container.symbolDb.reindex(path, src)
        }
        modulesRevision++
    }

    fun reindexModule(path: String) {
        scope.launch {
            val src = withContext(Dispatchers.IO) { repository.read(path) } ?: return@launch
            container.symbolDb.reindex(path, src)
            modulesRevision++
        }
    }

    fun moduleNames(): List<String> = repository.listModules().map { it.name }

    // -------------------------------------------------------------- watcher

    private fun startWatcher() {
        if (repository.usingSharedStorage) return
        val root = container.internalRoot()
        container.watcher.start(root) { changed ->
            scope.launch { handleExternalChanges(changed) }
        }
    }

    private fun handleExternalChanges(changed: Set<String>) {
        scope.launch {
            data class Pending(val tab: Tab, val content: String)
            val reloads = ArrayList<Pending>()
            val banners = ArrayList<Tab>()
            val moduleEvents = HashSet<String>()
            var otherProjectChanged = false

            // Read + checksum everything off the main thread first.
            withContext(Dispatchers.IO) {
                for (rel in changed) {
                    val tab = tabs.firstOrNull { it.path == rel }
                    if (tab != null) {
                        val disk = repository.read(rel) ?: continue
                        val diskSum = CppSymbolParser.checksum(disk)
                        // Our own writes echo back through the watcher:
                        // identical checksum means nothing happened on disk.
                        if (diskSum == tab.lastSavedChecksum) continue
                        val currentSum = CppSymbolParser.checksum(tab.state.text.text())
                        if (diskSum == currentSum) {
                            // Editor already matches disk (a save raced the
                            // event): sync the flags and move on.
                            tab.dirty = false
                            tab.externalChange = false
                            tab.lastSavedChecksum = diskSum
                            continue
                        }
                        if (!tab.dirty) reloads.add(Pending(tab, disk)) else banners.add(tab)
                        continue
                    }
                    if (rel.startsWith("modules/")) {
                        moduleEvents += rel
                    } else if (rel.startsWith("projects/") && !rel.startsWith("projects/${project.name}/")) {
                        otherProjectChanged = true
                    }
                }
            }

            // Apply on the main thread.
            for (p in reloads) applyExternalContent(p.tab, p.content)
            for (t in banners) t.externalChange = true
            for (m in moduleEvents) reindexModule(m)
            if (otherProjectChanged) container.projectsRevision.value++
        }
    }

    // ------------------------------------------------------------- building

    fun buildAndRun() {
        if (building) return
        // Build the file that is open in the editor (not always main.cpp).
        val entryPath = active?.path
        buildJob = scope.launch {
            saveAll()
            building = true
            lastBuildOk = false
            buildProgress = 0.02f
            buildStatusText = "preparing"
            buildLog.clear()
            diagnostics.clear()
            console.clear()
            runFinished = false
            exitCode = -1

            val staged = withContext(Dispatchers.IO) { stageFiles(entryPath) }
            if (staged == null) {
                building = false
                buildStatusText = "could not stage files"
                buildLog.add("error: could not stage project files")
                return@launch
            }

            buildLog.add("building ${staged.entryName}")
            val req = dev.mono.compiler.build.BuildEngine.Request(
                projectName = project.name,
                mainFile = staged.mainFile,
                includeDirs = listOf(staged.modulesDir, staged.projectDir),
                std = CppStandard.fromFlag(settings.cppStandard),
                opt = OptLevel.fromFlag(settings.optLevel),
                extraFlags = settings.extraFlags.trim().split(Regex("\\s+")).filter { it.isNotBlank() },
                usePch = settings.usePch,
                pchIncludeLines = settings.pchIncludes.lines().map { it.trim() }.filter { it.isNotBlank() }
            )

            var success = false
            var binary: String? = null
            container.buildEngine.build(req).collect { ev ->
                when (ev) {
                    is BuildEvent.Started -> buildStatusText = "building"
                    is BuildEvent.Phase -> {
                        buildProgress = ev.progress
                        buildStatusText = ev.detail
                    }
                    is BuildEvent.Log -> buildLog.add(ev.line)
                    is BuildEvent.DiagnosticEvent -> addDiagnostic(ev)
                    is BuildEvent.Finished -> {
                        success = ev.success
                        binary = ev.binaryPath
                        buildProgress = 1f
                        buildStatusText = ev.message
                        buildLog.add(ev.message)
                    }
                }
            }
            building = false
            lastBuildOk = success
            binaryPath = binary
            if (success && binary != null) {
                runProgram(File(binary), staged.entryName)
            }
        }
    }

    private fun addDiagnostic(ev: BuildEvent.DiagnosticEvent) {
        val d = ev.diagnostic
        diagnostics.add(Issue(d.file, d.line, d.col, d.severity == dev.mono.compiler.model.Severity.ERROR, d.message))
        if (d.file.isBlank()) return
        val name = d.file.substringAfterLast('/')
        val tab = tabs.firstOrNull { it.path.substringAfterLast('/') == name } ?: return
        val existing = tab.state.diagnostics.filter { it.file != name }.map { tabDiag ->
            EditorDiagnostic(tabDiag.line, tabDiag.colStart, tabDiag.colEnd, tabDiag.isError, tabDiag.message, name)
        }
        val mapped = existing + EditorDiagnostic(
            (d.line - 1).coerceAtLeast(0),
            (d.col - 1).coerceAtLeast(0),
            (d.col - 1).coerceAtLeast(0),
            d.severity == dev.mono.compiler.model.Severity.ERROR,
            d.message,
            name
        )
        tab.controller.setDiagnostics(mapped)
    }

    private class Staged(
        val mainFile: File,
        val modulesDir: File,
        val projectDir: File,
        /** File name that is compiled and run (main.cpp unless another source is open). */
        val entryName: String
    )

    private fun isSourceFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "cpp" || ext == "cc" || ext == "cxx" || ext == "c++"
    }

    /** Copies sources + modules into cache so clang gets plain File paths. */
    private fun stageFiles(entryPath: String?): Staged? {
        val base = File(cacheSub("stage"), project.name)
        base.deleteRecursively()
        val projectDir = File(base, "project").apply { mkdirs() }
        val modulesDir = File(base, "modules").apply { mkdirs() }

        // Main + extra project files.
        val mainContent = repository.read(repository.projectMainPath(project.name)) ?: return null
        File(projectDir, "main.cpp").writeText(mainContent)
        for (f in repository.projectFiles(project.name)) {
            if (f.name == "main.cpp") continue
            repository.read(f.path)?.let { File(projectDir, f.name).writeText(it) }
        }
        // Modules.
        for (m in repository.listModules()) {
            repository.read(m.path)?.let { File(modulesDir, m.name).writeText(it) }
        }

        // Entry point: the open source file when it belongs to this project,
        // otherwise main.cpp (e.g. a header or a module is in front).
        val inProject = entryPath != null && entryPath.startsWith("projects/${project.name}/")
        val entryName = entryPath?.substringAfterLast('/')
            ?.takeIf { inProject && isSourceFile(it) && File(projectDir, it).isFile }
            ?: "main.cpp"
        return Staged(File(projectDir, entryName), modulesDir, projectDir, entryName)
    }

    // ----------------------------------------------------------------- run

    private fun runProgram(binary: File, entryName: String = "main.cpp") {
        running = true
        console.add(ConsoleLine("— running ${project.name}/$entryName (timeout ${settings.runTimeoutSec}s) —", ConsoleLine.META))
        runJob = scope.launch {
            val events = runner.events
            val collector = launch {
                events.collect { ev ->
                    when (ev) {
                        is dev.mono.compiler.model.ConsoleEvent.Stdout -> appendConsole(ev.text, ConsoleLine.OUT)
                        is dev.mono.compiler.model.ConsoleEvent.Stderr -> appendConsole(ev.text, ConsoleLine.ERR)
                        is dev.mono.compiler.model.ConsoleEvent.StdinEcho -> appendConsole("> ${ev.text}", ConsoleLine.IN)
                        is dev.mono.compiler.model.ConsoleEvent.Exit -> {
                            exitCode = ev.code
                            appendConsole("— exit code ${ev.code} —", ConsoleLine.META)
                        }
                        is dev.mono.compiler.model.ConsoleEvent.TimedOut ->
                            appendConsole("— timed out after ${settings.runTimeoutSec}s —", ConsoleLine.META)
                        is dev.mono.compiler.model.ConsoleEvent.Failed ->
                            appendConsole("— ${ev.message} —", ConsoleLine.META)
                    }
                }
            }
            // LD_LIBRARY_PATH is set by the runner from nativeLibDir so the
            // launched program finds libc++_shared.so in nativeLibraryDir
            // (the only place targetSdk 29+ allows executable mappings from).
            runner.run(
                binary = binary,
                workDir = binary.parentFile ?: cacheSub("stage"),
                nativeLibDir = container.toolchain.nativeLibDir,
                env = mapOf(
                    "PATH" to container.toolchain.nativeLibDir.absolutePath + ":/system/bin",
                    "HOME" to cacheSub("home").absolutePath,
                    "TMPDIR" to cacheSub("home").absolutePath
                ),
                timeoutSec = settings.runTimeoutSec
            )
            collector.cancel()
            running = false
            runFinished = true
        }
    }

    fun sendStdin(line: String) {
        if (line == "cls" || line == "clear") {
            console.clear()
            return
        }
        runner.sendLine(line)
    }

    /** Copies an externally picked file into the project and opens it. */
    fun openFromExternal(uri: android.net.Uri) {
        scope.launch {
            val res = repository.importIntoProject(project.name, uri)
            val rel = res.getOrNull()
            if (rel != null) openTab(rel)
        }
    }

    fun stopRun() {
        runner.stop()
    }

    private fun appendConsole(text: String, kind: Int) {
        var s = text
        if (s.contains("\u001B[2J") || s.contains("\u001B[H")) {
            console.clear()
            s = ANSI_RE.replace(s, "")
        } else if (s.contains("\u001B")) {
            s = ANSI_RE.replace(s, "")
        }
        if (s.isNotEmpty()) console.add(ConsoleLine(s, kind))
        while (console.size > 4000) console.removeAt(0)
    }

    // -------------------------------------------------------------- lifecycle

    fun close() {
        // Flush pending edits before the scope goes away. Writes are small
        // (tmp + rename); blocking here is the only way to guarantee they
        // land before the session is torn down.
        runCatching { runBlocking { saveAll() } }
        buildJob?.cancel()
        runJob?.cancel()
        runner.stop()
        scope.cancel()
    }

    companion object {
        private val ANSI_RE = Regex("\\u001B\\[[0-9;?]*[A-Za-z]")
    }

    private fun cacheSub(name: String): File {
        val f = File(container.cacheDir, name)
        f.mkdirs()
        return f
    }
}
