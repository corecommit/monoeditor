package dev.mono.storage.fs

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.mono.storage.model.FileMeta
import dev.mono.storage.model.ProjectInfo
import dev.mono.storage.model.StorageResult
import dev.mono.storage.provider.MonoProvider
import dev.mono.storage.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the on-disk layout
 *
 *   Mono/
 *     projects/<name>/main.cpp
 *     modules/
 *
 * in either app-internal storage (default; exposed to the system Files app
 * through [MonoProvider]) or a user-picked shared Documents/Mono tree
 * (SAF). All mutating operations notify the DocumentsProvider so other apps
 * see live changes.
 */
class ProjectRepository(
    private val context: Context,
    private val settings: Settings
) {

    /** Abstraction over the two possible backing stores. */
    interface StorageRoot {
        fun exists(rel: String): Boolean
        fun listDir(rel: String): List<FileMeta>
        fun readFile(rel: String): String?
        fun writeFile(rel: String, content: String): Boolean
        fun mkdirs(rel: String): Boolean
        fun delete(rel: String): Boolean
        fun rename(from: String, to: String): Boolean
        fun lastModified(rel: String): Long
        fun size(rel: String): Long
    }

    inner class LocalRoot(private val base: File) : StorageRoot {
        private fun f(rel: String) = File(base, rel.trimStart('/'))
        override fun exists(rel: String) = f(rel).exists()
        override fun listDir(rel: String): List<FileMeta> {
            val dir = f(rel)
            val children = dir.listFiles() ?: return emptyList()
            val prefix = rel.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            return children
                .filterNot { it.name.startsWith(".") }
                .map {
                    FileMeta(it.name, prefix + it.name, it.isDirectory, it.length(), it.lastModified())
                }
                .sortedWith(compareByDescending<FileMeta> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
        override fun readFile(rel: String): String? = runCatching { f(rel).readText() }.getOrNull()
        override fun writeFile(rel: String, content: String): Boolean = runCatching {
            val file = f(rel)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".mono-tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
        override fun mkdirs(rel: String): Boolean = f(rel).mkdirs() || f(rel).isDirectory
        override fun delete(rel: String): Boolean = f(rel).deleteRecursively()
        override fun rename(from: String, to: String): Boolean {
            val src = f(from); val dst = f(to)
            dst.parentFile?.mkdirs()
            return src.renameTo(dst)
        }
        override fun lastModified(rel: String): Long = f(rel).lastModified()
        override fun size(rel: String): Long = f(rel).length()
    }

    inner class SafRoot(private val tree: DocumentFile) : StorageRoot {
        private fun doc(rel: String): DocumentFile? {
            var cur: DocumentFile = tree
            for (seg in rel.trim('/').split('/').filter { it.isNotEmpty() }) {
                cur = cur.findFile(seg) ?: return null
            }
            return cur
        }
        private fun parentOf(rel: String): DocumentFile {
            val segs = rel.trim('/').split('/').filter { it.isNotEmpty() }
            var cur: DocumentFile = tree
            for (i in 0 until segs.size - 1) cur = cur.findFile(segs[i]) ?: return cur
            return cur
        }
        override fun exists(rel: String): Boolean = doc(rel)?.exists() == true
        override fun listDir(rel: String): List<FileMeta> {
            val d = doc(rel) ?: return emptyList()
            if (!d.isDirectory) return emptyList()
            return d.listFiles()
                .filterNot { (it.name ?: "").startsWith(".") }
                .map {
                    val name = it.name ?: "?"
                    FileMeta(name, rel.trim('/') + "/" + name, it.isDirectory, it.length(), it.lastModified())
                }
                .sortedWith(compareByDescending<FileMeta> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
        override fun readFile(rel: String): String? {
            val d = doc(rel) ?: return null
            return runCatching {
                context.contentResolver.openInputStream(d.uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
        override fun writeFile(rel: String, content: String): Boolean {
            val segs = rel.trim('/').split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty()) return false
            var cur: DocumentFile = tree
            for (i in 0 until segs.size - 1) {
                cur = cur.findFile(segs[i]) ?: cur.createDirectory(segs[i]) ?: return false
            }
            var file = cur.findFile(segs.last())
            if (file != null && file.isDirectory) return false
            if (file == null) {
                file = cur.createFile(mimeFor(segs.last()), segs.last()) ?: return false
            }
            return runCatching {
                context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray()) } != null
            }.getOrDefault(false)
        }
        override fun mkdirs(rel: String): Boolean {
            var cur: DocumentFile = tree
            for (seg in rel.trim('/').split('/').filter { it.isNotEmpty() }) {
                cur = cur.findFile(seg) ?: cur.createDirectory(seg) ?: return false
            }
            return true
        }
        override fun delete(rel: String): Boolean = doc(rel)?.delete() ?: false
        override fun rename(from: String, to: String): Boolean {
            val d = doc(from) ?: return false
            val newName = to.trim('/').split('/').lastOrNull() ?: return false
            return d.renameTo(newName)
        }
        override fun lastModified(rel: String): Long = doc(rel)?.lastModified() ?: 0L
        override fun size(rel: String): Long = doc(rel)?.length() ?: 0L
    }

    private val internalRootFile: File = File(context.filesDir, "Mono")

    val root: StorageRoot
        get() =
            if (settings.useSharedStorage && settings.sharedRootUri.isNotEmpty()) {
                val tree = runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(settings.sharedRootUri))
                }.getOrNull()
                if (tree != null && tree.canWrite()) SafRoot(tree) else LocalRoot(internalRootFile)
            } else {
                LocalRoot(internalRootFile)
            }

    /** True when the SAF shared Documents/Mono tree is active. */
    val usingSharedStorage: Boolean
        get() = settings.useSharedStorage && settings.sharedRootUri.isNotEmpty() && root is SafRoot

    private fun notify(rel: String) {
        if (root is LocalRoot) {
            runCatching {
                context.contentResolver.notifyChange(MonoProvider.uriFor(rel), null)
            }
        }
    }

    // ------------------------------------------------------------------ setup

    fun ensureSeeded() {
        val r = root
        r.mkdirs("projects")
        r.mkdirs("modules")
        if (!r.exists("projects/Hello/main.cpp")) {
            r.mkdirs("projects/Hello")
            r.writeFile("projects/Hello/main.cpp", newProjectTemplate("Hello"))
            notify("projects/Hello/main.cpp")
        } else if (r.readFile("projects/Hello/main.cpp") == legacyProjectTemplate("Hello")) {
            // Untouched sample from an older version: upgrade it to iostream.
            r.writeFile("projects/Hello/main.cpp", newProjectTemplate("Hello"))
            notify("projects/Hello/main.cpp")
        }
        if (r.listDir("modules").isEmpty() && !r.exists("modules/greet.h")) {
            r.writeFile("modules/greet.h", SAMPLE_MODULE)
            notify("modules/greet.h")
        }
    }

    // -------------------------------------------------------------- projects

    fun projects(): List<ProjectInfo> =
        root.listDir("projects")
            .filter { it.isDirectory }
            .map { ProjectInfo(it.name, "projects/${it.name}") }

    fun createProject(name: String): StorageResult<ProjectInfo> {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\') || clean.startsWith(".")) {
            return StorageResult.Err("Project name must be a plain folder name")
        }
        if (root.exists("projects/$clean")) {
            return StorageResult.Err("A project named \"$clean\" already exists")
        }
        root.mkdirs("projects/$clean")
        root.writeFile("projects/$clean/main.cpp", newProjectTemplate(clean))
        notify("projects/$clean/main.cpp")
        return StorageResult.Ok(ProjectInfo(clean, "projects/$clean"))
    }

    fun deleteProject(name: String): Boolean {
        val ok = root.delete("projects/$name")
        if (ok) notify("projects")
        return ok
    }

    fun renameProject(old: String, new: String): StorageResult<ProjectInfo> {
        val clean = new.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\') || clean.startsWith(".")) {
            return StorageResult.Err("Project name must be a plain folder name")
        }
        if (root.exists("projects/$clean")) return StorageResult.Err("A project named \"$clean\" already exists")
        if (!root.rename("projects/$old", "projects/$clean")) return StorageResult.Err("Rename failed")
        notify("projects")
        return StorageResult.Ok(ProjectInfo(clean, "projects/$clean"))
    }

    fun projectMainPath(name: String): String = "projects/$name/main.cpp"

    fun projectFiles(name: String): List<FileMeta> =
        root.listDir("projects/$name").filter { !it.isDirectory }

    // --------------------------------------------------------------- modules

    fun listModules(): List<FileMeta> =
        root.listDir("modules").filter { !it.isDirectory }

    fun createModule(name: String, header: Boolean): StorageResult<FileMeta> {
        var clean = name.trim()
        if (clean.isEmpty()) return StorageResult.Err("Module name is empty")
        if (clean.contains('/') || clean.contains('\\')) return StorageResult.Err("Module name cannot contain slashes")
        if (!clean.endsWith(".h") && !clean.endsWith(".hpp") && !clean.endsWith(".cpp") && !clean.endsWith(".c")) {
            clean += if (header) ".h" else ".cpp"
        }
        if (root.exists("modules/$clean")) return StorageResult.Err("Module \"$clean\" already exists")
        val body = if (clean.endsWith(".h") || clean.endsWith(".hpp")) {
            "#pragma once\n\n// $clean\n\n"
        } else {
            "// $clean\n\n"
        }
        if (!root.writeFile("modules/$clean", body)) return StorageResult.Err("Could not create module")
        notify("modules/$clean")
        return StorageResult.Ok(FileMeta(clean, "modules/$clean", false, body.length.toLong(), System.currentTimeMillis()))
    }

    fun renameModule(old: String, new: String): StorageResult<FileMeta> {
        var clean = new.trim()
        if (clean.isEmpty()) return StorageResult.Err("Module name is empty")
        if (clean.contains('/') || clean.contains('\\')) return StorageResult.Err("Module name cannot contain slashes")
        if (!clean.endsWith(".h") && !clean.endsWith(".hpp") && !clean.endsWith(".cpp") && !clean.endsWith(".c")) {
            clean += if (old.endsWith(".h") || old.endsWith(".hpp")) ".h" else ".cpp"
        }
        if (root.exists("modules/$clean") && clean != old) return StorageResult.Err("Module \"$clean\" already exists")
        if (!root.rename("modules/$old", "modules/$clean")) return StorageResult.Err("Rename failed")
        notify("modules")
        return StorageResult.Ok(FileMeta(clean, "modules/$clean", false, 0, System.currentTimeMillis()))
    }

    fun deleteModule(name: String): Boolean {
        val ok = root.delete("modules/$name")
        if (ok) notify("modules")
        return ok
    }

    /** Copies files picked in the system document picker into modules/. */
    suspend fun importModules(uris: List<Uri>): StorageResult<List<FileMeta>> = withContext(Dispatchers.IO) {
        val imported = ArrayList<FileMeta>()
        for (uri in uris) {
            val name = queryDisplayName(uri) ?: continue
            if (name.isBlank()) continue
            var target = name
            var n = 1
            while (root.exists("modules/$target")) {
                val dot = name.lastIndexOf('.')
                target = if (dot > 0) "${name.substring(0, dot)}-$n${name.substring(dot)}" else "$name-$n"
                n++
            }
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: continue
            if (root.writeFile("modules/$target", text)) {
                imported.add(FileMeta(target, "modules/$target", false, text.length.toLong(), System.currentTimeMillis()))
                notify("modules/$target")
            }
        }
        if (imported.isEmpty()) StorageResult.Err("Nothing imported (unsupported files?)")
        else StorageResult.Ok(imported)
    }

    /** Copies an external file into a project so it can be opened as a tab. */
    suspend fun importIntoProject(project: String, uri: Uri): StorageResult<String> = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: return@withContext StorageResult.Err("Cannot read file name")
        if (name.isBlank()) return@withContext StorageResult.Err("Cannot read file name")
        var target = name
        var n = 1
        while (root.exists("projects/$project/$target")) {
            val dot = name.lastIndexOf('.')
            target = if (dot > 0) "${name.substring(0, dot)}-$n${name.substring(dot)}" else "$name-$n"
            n++
        }
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return@withContext StorageResult.Err("Cannot read file")
        if (!root.writeFile("projects/$project/$target", text)) return@withContext StorageResult.Err("Write failed")
        notify("projects/$project/$target")
        StorageResult.Ok("projects/$project/$target")
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------ raw access

    fun read(rel: String): String? = root.readFile(rel)
    fun write(rel: String, content: String): Boolean {
        val ok = root.writeFile(rel, content)
        if (ok) notify(rel)
        return ok
    }
    fun lastModified(rel: String): Long = root.lastModified(rel)
    fun mimeFor(name: String): String = when {
        name.endsWith(".cpp", true) || name.endsWith(".cc", true) || name.endsWith(".cxx", true) -> "text/x-c++src"
        name.endsWith(".c", true) -> "text/x-csrc"
        name.endsWith(".h", true) || name.endsWith(".hpp", true) -> "text/x-chdr"
        name.endsWith(".txt", true) || name.endsWith(".md", true) -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        fun newProjectTemplate(project: String): String = """
            // $project — build it with the play button in the top bar.
            //
            // Reusable code can live in modules (Modules drawer) and be used
            // from any project with a plain include:
            //   #include <greet.h>        // your module, resolved via -I modules
            //   #include "greet.h"       // quoted form works too
            //
            #include <iostream>

            int main() {
                std::cout << "Hello, World!" << std::endl;
                return 0;
            }
        """.trimIndent()

        /** The previous default (cstdio/printf), so an untouched Hello can be upgraded. */
        private fun legacyProjectTemplate(project: String): String = """
            // $project — build it with the play button in the top bar.
            //
            // Reusable code can live in modules (Modules drawer) and be used
            // from any project with a plain include:
            //   #include <greet.h>        // your module, resolved via -I modules
            //   #include "greet.h"       // quoted form works too
            //
            #include <cstdio>

            int main() {
                printf("Hello, Mono.\n");
                return 0;
            }
        """.trimIndent()

        val SAMPLE_MODULE = """
            #pragma once
            // greet.h — a sample Mono module.
            // Any project can use it with:  #include <greet.h>

            inline const char* greet(const char* who) {
                return "Hello from the greet module";
            }
        """.trimIndent()
    }
}
