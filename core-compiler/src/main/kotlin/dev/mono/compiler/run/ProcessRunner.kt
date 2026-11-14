package dev.mono.compiler.run

import dev.mono.compiler.model.ConsoleEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a compiled binary on a background dispatcher and streams its console
 * activity: stdout/stderr as [ConsoleEvent] lines, stdin through [sendLine],
 * a hard [timeoutSec] with kill, and [stop] for the Stop button.
 *
 * Android 10+ (targetSdk 29+) forbids execve() on anything under the app's
 * data directories, which is exactly where build outputs live. The primary
 * launch therefore routes the program through the system's own dynamic
 * linker — `/system/bin/linker64 <binary>` loads any readable PIE
 * executable in userspace without execve — with a direct exec kept as the
 * fallback. If both attempts fail, the console gets a clear error instead
 * of a raw stack trace.
 */
class ProcessRunner {

    private var process: Process? = null

    private val _events = MutableSharedFlow<ConsoleEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ConsoleEvent> = _events

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val stopped = AtomicBoolean(false)

    /** Writes one line to the child's stdin. */
    fun sendLine(line: String) {
        val p = process ?: return
        runCatching {
            p.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            p.outputStream.flush()
            _events.tryEmit(ConsoleEvent.StdinEcho(line))
        }
    }

    /** Stop button: destroys the process. */
    fun stop() {
        stopped.set(true)
        process?.destroy()
    }

    /**
     * Blocking run — call from a background dispatcher. Returns when the
     * process exits (naturally, timed out or stopped).
     *
     * @param nativeLibDir the app's nativeLibraryDir; put on
     *   LD_LIBRARY_PATH so the program finds libc++_shared.so and the other
     *   toolchain runtime libraries.
     */
    suspend fun run(
        binary: File,
        args: List<String> = emptyList(),
        workDir: File,
        nativeLibDir: File? = null,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Int
    ): WithResult = withContext(Dispatchers.IO) {
        stopped.set(false)
        _running.value = true
        try {
            val p = launch(binary, args, workDir, nativeLibDir, env)
                ?: return@withContext WithResult(-1)
            process = p

            val outReader = Thread({
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines ->
                        for (l in lines) _events.tryEmit(ConsoleEvent.Stdout(l))
                    }
                }
            }, "mono-stdout")
            val errReader = Thread({
                runCatching {
                    p.errorStream.bufferedReader().useLines { lines ->
                        for (l in lines) _events.tryEmit(ConsoleEvent.Stderr(l))
                    }
                }
            }, "mono-stderr")
            outReader.isDaemon = true
            errReader.isDaemon = true
            outReader.start()
            errReader.start()

            val finished = waitForWithTimeout(p, timeoutSec * 1000L)
            if (finished == null) {
                p.destroy()
                Thread.sleep(150)
                if (p.isAlive) p.destroyForcibly()
                _events.tryEmit(ConsoleEvent.TimedOut)
                outReader.join(300)
                errReader.join(300)
                WithResult(-1, timedOut = true)
            } else {
                outReader.join(500)
                errReader.join(500)
                _events.tryEmit(ConsoleEvent.Exit(finished))
                WithResult(finished, stoppedByUser = stopped.get())
            }
        } finally {
            _running.value = false
            process = null
        }
    }

    /**
     * Tries `linker64 <binary>` first (works on targetSdk 29+ because the
     * program is loaded by the linker in userspace, not execve'd), then a
     * direct exec for older layouts. Returns the started process, or null
     * after emitting a [ConsoleEvent.Failed] that explains both attempts.
     */
    private fun launch(
        binary: File,
        args: List<String>,
        workDir: File,
        nativeLibDir: File?,
        env: Map<String, String>
    ): Process? {
        val attempts = ArrayList<Pair<String, List<String>>>(2)
        if (File(SYSTEM_LINKER).canExecute()) {
            attempts.add(
                "launch via /system/bin/linker64" to
                        listOf(SYSTEM_LINKER, binary.absolutePath) + args
            )
        }
        attempts.add("direct exec" to listOf(binary.absolutePath) + args)

        var lastMode = ""
        var lastError: IOException? = null
        for ((mode, command) in attempts) {
            try {
                val pb = ProcessBuilder(command)
                pb.directory(workDir)
                pb.redirectInput(ProcessBuilder.Redirect.PIPE)
                val penv = pb.environment()
                if (nativeLibDir != null) {
                    // Programs link against the shared libc++ runtime, and
                    // nativeLibraryDir is the only place targetSdk 29+
                    // allows executable mappings from.
                    penv["LD_LIBRARY_PATH"] = listOfNotNull(
                        nativeLibDir.absolutePath,
                        env["LD_LIBRARY_PATH"]?.takeIf { it.isNotBlank() }
                    ).joinToString(":")
                }
                for ((k, v) in env) {
                    if (nativeLibDir != null && k == "LD_LIBRARY_PATH") continue
                    penv[k] = v
                }
                return pb.start()
            } catch (e: IOException) {
                lastMode = mode
                lastError = e
            }
        }
        val detail = lastError?.message
            ?: lastError?.javaClass?.simpleName
            ?: "unknown error"
        _events.tryEmit(
            ConsoleEvent.Failed(
                "could not start '${binary.name}' (${lastMode} failed: ${detail}). " +
                        "Android 10+ blocks executing files from app storage — Mono normally " +
                        "routes around this with /system/bin/linker64. " +
                        "Rebuild the project and try again; if this persists the binary may be corrupt."
            )
        )
        return null
    }

    private fun waitForWithTimeout(p: Process, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                return p.exitValue()
            } catch (_: IllegalThreadStateException) {
            }
            if (stopped.get()) {
                // give destroy() a moment to land
                repeat(10) {
                    try { return p.exitValue() } catch (_: IllegalThreadStateException) { Thread.sleep(50) }
                }
                return null
            }
            if (System.currentTimeMillis() > deadline) return null
            Thread.sleep(25)
        }
    }

    data class WithResult(val exitCode: Int, val timedOut: Boolean = false, val stoppedByUser: Boolean = false)

    private companion object {
        const val SYSTEM_LINKER = "/system/bin/linker64"
    }
}
