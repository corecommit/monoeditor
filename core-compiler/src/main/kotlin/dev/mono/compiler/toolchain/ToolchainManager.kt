package dev.mono.compiler.toolchain

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Locates, verifies and installs the on-device clang toolchain.
 *
 * Layout contract (produced by toolchain/setup-toolchain.sh):
 * - Executables ship as native libs inside the APK and land, extracted and
 *   executable, in nativeLibraryDir: libclang.so, libld_lld.so (+ runtime
 *   libs). Android allows execve only there since API 29.
 * - The header sysroot ships in assets/toolchain/sysroot.zip together with
 *   manifest.json (sha256 of every file). On first use the app unpacks it
 *   into filesDir/toolchain/sysroot and verifies every checksum.
 * - Alternatively (or when the APK was built without bundled assets) a
 *   sysroot-only archive can be downloaded from a URL — see [downloadAndInstall].
 */
class ToolchainManager(private val context: Context) {

    sealed class Status {
        /** Executables present and sysroot extracted + verified. */
        data class Ready(val compiler: String) : Status()

        /** Executables present; sysroot still extracting. */
        data class Extracting(val progress: Float) : Status()

        /** Executables present; sysroot must be extracted from assets. */
        data class NeedsExtraction(val hasBundledAssets: Boolean) : Status()

        /** No compiler binaries in the APK; a toolchain-enabled build is needed. */
        data class MissingBinaries(val detail: String) : Status()

        data class Error(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.NeedsExtraction(false))
    val status: StateFlow<Status> = _status

    private val mutex = Mutex()

    val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir ?: "")

    val clangFile: File get() = File(nativeLibDir, "libclang.so")
    val lldFile: File get() = File(nativeLibDir, "libld_lld.so")

    val toolchainDir: File get() = File(context.filesDir, "toolchain")
    val sysrootDir: File get() = File(toolchainDir, "sysroot")

    /**
     * lld picks its flavor (GNU / COFF / ...) from the name it is started
     * under. The bundled copy is called libld_lld.so, which it does not
     * recognise, so expose it through a symlink named "ld.lld". Returns null
     * if the link cannot be created.
     */
    fun lldAlias(): File? = try {
        val alias = File(toolchainDir, "bin/ld.lld")
        alias.parentFile?.mkdirs()
        alias.delete()
        Os.symlink(lldFile.absolutePath, alias.absolutePath)
        alias
    } catch (t: Throwable) {
        null
    }

    val binariesPresent: Boolean
        get() = clangFile.canExecute()

    /** Idempotent; safe to call from the UI on every app start. */
    suspend fun ensureReady(): Status {
        if (!binariesPresent) {
            val st = Status.MissingBinaries(
                "This APK does not bundle the clang toolchain. Build with toolchain/setup-toolchain.sh or download a sysroot for a toolchain-enabled build."
            )
            _status.value = st
            return st
        }
        if (sysrootVerified()) {
            val st = Status.Ready(readCompilerName())
            _status.value = st
            return st
        }
        return mutex.withLock {
            if (sysrootVerified()) {
                Status.Ready(readCompilerName()).also { _status.value = it }
            } else {
                extractBundled()
            }
        }
    }

    private fun sysrootVerified(): Boolean =
        File(sysrootDir, "usr/include").isDirectory &&
                File(sysrootDir, ".verified").exists()

    private fun readCompilerName(): String {
        val info = File(toolchainDir, "info.json")
        if (info.isFile) {
            runCatching {
                val obj = JSONObject(info.readText())
                return obj.optString("compiler", "clang")
            }
        }
        return "clang"
    }

    /** Unzips assets/toolchain/sysroot.zip, verifying every sha256 in manifest.json. */
    private suspend fun extractBundled(): Status = withContext(Dispatchers.IO) {
        val assets = context.assets
        val hasZip = runCatching { assets.open("toolchain/sysroot.zip").close(); true }.getOrDefault(false)
        val hasManifest = runCatching { assets.open("toolchain/manifest.json").close(); true }.getOrDefault(false)
        if (!hasZip || !hasManifest) {
            val st = Status.NeedsExtraction(false)
            _status.value = st
            return@withContext st
        }
        val manifestJson = assets.open("toolchain/manifest.json").bufferedReader().use { it.readText() }
        val entries = parseManifest(manifestJson)
        val staging = File(toolchainDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val status = withContext(Dispatchers.IO) {
                unzipWithVerification(
                    ZipInputStream(assets.open("toolchain/sysroot.zip")),
                    staging,
                    entries
                ) { done, total ->
                    _status.value = Status.Extracting(if (total > 0) done.toFloat() / total else 0f)
                }
            }
            if (!status.isOk) {
                staging.deleteRecursively()
                val st = Status.Error(status.message ?: "sysroot verification failed")
                _status.value = st
                return@withContext st
            }
            // The zip holds sysroot/ + manifest.json at its root, so the real
            // sysroot is staging/sysroot (not staging itself).
            val sysrootInStage = File(staging, "sysroot")
            if (!sysrootInStage.isDirectory) {
                staging.deleteRecursively()
                val st = Status.Error("bundled sysroot.zip has no sysroot/ folder")
                _status.value = st
                return@withContext st
            }
            // info.json lives next to the sysroot (readCompilerName reads it there).
            runCatching {
                toolchainDir.mkdirs()
                assets.open("toolchain/info.json").use { input ->
                    File(toolchainDir, "info.json").outputStream().use { input.copyTo(it) }
                }
            }
            val final = sysrootDir
            final.deleteRecursively()
            if (!sysrootInStage.renameTo(final)) {
                staging.deleteRecursively()
                val st = Status.Error("could not move extracted sysroot into place")
                _status.value = st
                return@withContext st
            }
            staging.deleteRecursively()
            File(final, ".verified").writeText("ok")
            val st = Status.Ready(readCompilerName())
            _status.value = st
            st
        } catch (t: Throwable) {
            val st = Status.Error("toolchain extraction failed: ${t.message ?: t.javaClass.simpleName}")
            _status.value = st
            st
        }
    }

    /**
     * Downloads a sysroot-only archive (same layout as the bundled zip) from
     * [url] and installs it. Used when the APK carries the binaries but the
     * assets were stripped, or to update headers.
     */
    suspend fun downloadAndInstall(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!binariesPresent) {
                error("This build has no compiler binaries (libclang.so); download cannot fix that. Install a toolchain-enabled APK.")
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} for $url")
                val staging = File(toolchainDir, "staging-dl")
                staging.deleteRecursively()
                staging.mkdirs()
                val tmpZip = File(context.cacheDir, "toolchain-dl.zip")
                conn.inputStream.use { input ->
                    FileOutputStream(tmpZip).use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            _status.value = Status.Extracting(-1f)
                        }
                    }
                }
                val zis = ZipInputStream(FileInputStream(tmpZip))
                var manifestText: String? = null
                // First pass: find manifest.json.
                val zipFile = java.util.zip.ZipFile(tmpZip)
                val manifestEntry = zipFile.getEntry("manifest.json")
                if (manifestEntry != null) {
                    manifestText = zipFile.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                }
                val entries: Map<String, String> = manifestText?.let { parseManifest(it) } ?: emptyMap()
                val result = unzipWithVerification(zis, staging, entries) { _, _ -> }
                zis.close()
                zipFile.close()
                if (!result.isOk) error(result.message ?: "verification failed")
                val sysrootInStage = File(staging, "sysroot")
                if (!sysrootInStage.isDirectory) error("archive has no sysroot/ folder")
                val final = sysrootDir
                final.deleteRecursively()
                check(sysrootInStage.renameTo(final)) { "could not install sysroot" }
                File(final, ".verified").writeText("ok")
                tmpZip.delete()
                staging.deleteRecursively()
                _status.value = Status.Ready(readCompilerName())
            } finally {
                conn.disconnect()
            }
        }
    }

    // ------------------------------------------------------------ internals

    private class VerifyResult(val isOk: Boolean, val message: String?)

    private fun parseManifest(text: String): Map<String, String> {
        val arr = JSONArray(text)
        val map = HashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            map[obj.getString("path")] = obj.getString("sha256")
        }
        return map
    }

    private fun unzipWithVerification(
        zis: ZipInputStream,
        target: File,
        manifest: Map<String, String>,
        onProgress: (Int, Int) -> Unit
    ): VerifyResult {
        val digests = HashMap<String, String>(manifest.size)
        var done = 0
        val total = manifest.size.coerceAtLeast(1)
        try {
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                try {
                    if (entry.isDirectory) continue
                    val out = File(target, entry.name)
                    if (!out.canonicalPath.startsWith(target.canonicalPath)) {
                        return VerifyResult(false, "blocked zip escape: ${entry.name}")
                    }
                    out.parentFile?.mkdirs()
                    val md = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                            md.update(buf, 0, n)
                        }
                    }
                    val hex = md.digest().toHex()
                    val rel = entry.name.removePrefix("sysroot/")
                    digests[rel] = hex
                    onProgress(++done, total)
                } finally {
                    zis.closeEntry()
                }
            }
        } catch (t: Throwable) {
            return VerifyResult(false, "extraction failed: ${t.message}")
        }
        if (manifest.isEmpty()) {
            return VerifyResult(true, null) // no manifest: trust the source
        }
        for ((path, expected) in manifest) {
            val actual = digests[path]
            if (actual == null) return VerifyResult(false, "missing file in archive: $path")
            if (!actual.equals(expected, ignoreCase = true)) {
                return VerifyResult(false, "checksum mismatch for $path")
            }
        }
        return VerifyResult(true, null)
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
