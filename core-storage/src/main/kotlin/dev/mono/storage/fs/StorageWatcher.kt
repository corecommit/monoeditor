package dev.mono.storage.fs

import android.os.FileObserver
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches the internal Mono tree so that edits made from the system Files
 * app (or any other editor, through the DocumentsProvider) appear in Mono
 * live. Events are debounced per path and delivered on a background thread.
 *
 * Only used for the internal storage root; the shared Documents mode relies
 * on the fact that its files live in real shared storage.
 */
class StorageWatcher {

    private val observers = ArrayList<FileObserver>()
    private val pending = ConcurrentHashMap<String, Long>()
    private var callback: ((Set<String>) -> Unit)? = null
    private val handler = android.os.Handler(android.os.HandlerThread("mono-watcher").apply { start() }.looper)
    private var debounceMs = 400L

    /** Starts watching [root]; [onChange] receives changed relative paths. */
    fun start(root: File, onChange: (Set<String>) -> Unit) {
        stop()
        callback = onChange
        val dirs = mutableSetOf(root)
        val projects = File(root, "projects")
        val modules = File(root, "modules")
        if (projects.isDirectory) dirs += projects
        if (modules.isDirectory) dirs += modules
        projects.listFiles()?.filter { it.isDirectory }?.let { dirs += it }

        for (dir in dirs) {
            val observer = object : FileObserver(dir, MASK) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path.startsWith(".")) return
                    if (path.endsWith(".mono-tmp")) return
                    val rel = relativize(root, File(dir, path))
                    if (rel.isEmpty()) return
                    pending[rel] = System.currentTimeMillis()
                    handler.removeCallbacks(flush)
                    handler.postDelayed(flush, debounceMs)
                }
            }
            observer.startWatching()
            observers.add(observer)
        }
    }

    private val flush: Runnable = Runnable {
        val now = System.currentTimeMillis()
        val ready: List<String> = pending.entries
            .filter { now - it.value >= debounceMs - 50 }
            .map { it.key }
        for (k in ready) pending.remove(k)
        if (ready.isNotEmpty()) {
            val cb = callback
            if (cb != null) cb(ready.toSet())
        }
        if (pending.isNotEmpty()) {
            handler.removeCallbacks(flush)
            handler.postDelayed(flush, debounceMs)
        }
    }

    /** Re-checks immediately (e.g. after the provider reports a write). */
    fun flushNow() {
        handler.removeCallbacks(flush)
        handler.post(flush)
    }

    fun stop() {
        for (o in observers) o.stopWatching()
        observers.clear()
        pending.clear()
    }

    private fun relativize(root: File, f: File): String {
        val r = root.absolutePath.trimEnd('/') + "/"
        val p = f.absolutePath
        return if (p.startsWith(r)) p.substring(r.length) else ""
    }

    private companion object {
        val MASK = FileObserver.CLOSE_WRITE or FileObserver.CREATE or
                FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
                FileObserver.MODIFY
    }
}
