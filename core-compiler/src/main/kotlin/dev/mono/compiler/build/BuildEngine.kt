package dev.mono.compiler.build

import android.content.Context
import dev.mono.compiler.model.BuildEvent
import dev.mono.compiler.model.CppStandard
import dev.mono.compiler.model.Diagnostic
import dev.mono.compiler.model.OptLevel
import dev.mono.compiler.model.Severity
import dev.mono.compiler.toolchain.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.security.MessageDigest

/**
 * Incremental on-device build pipeline.
 *
 * - Translation units are compiled to .o files cached by a hash of
 *   (content, flags, std, pch, toolchain) **plus a fingerprint of every
 *   file reachable through the include path**, so editing a module
 *   invalidates the object cache even when main.cpp itself is unchanged.
 * - An optional precompiled header is built once per flag set and reused
 *   across builds and projects.
 * - User modules resolve through -I <modulesDir> so `#include <mod.cpp>`
 *   and `#include "mod.h"` both work.
 * - The link step uses the same driver settings as the compile step
 *   (driver mode, target, sysroot, --ld-path, -B/-L search paths) plus the
 *   libc++ shared runtime, whose presence is verified before anything
 *   compiles so failures come with a clear message.
 * - Everything runs in a flow on Dispatchers.IO and honours cancellation.
 */
class BuildEngine(
    private val context: Context,
    private val toolchain: ToolchainManager
) {

    data class Request(
        val projectName: String,
        val mainFile: File,
        val includeDirs: List<File>,
        val std: CppStandard = CppStandard.CXX17,
        val opt: OptLevel = OptLevel.O0,
        val extraFlags: List<String> = emptyList(),
        val usePch: Boolean = true,
        val pchIncludeLines: List<String> = emptyList()
    )

    fun build(req: Request): Flow<BuildEvent> = flow {
        val started = System.currentTimeMillis()
        emit(BuildEvent.Phase(0.02f, "checking toolchain"))

        val ready = toolchain.ensureReady()
        val sysroot = toolchain.sysrootDir
        when {
            ready is ToolchainManager.Status.Error -> {
                emit(finish(false, null, started, ready.message))
                return@flow
            }
            !toolchain.binariesPresent -> {
                val detail = (ready as? ToolchainManager.Status.MissingBinaries)?.detail
                    ?: "clang not installed"
                emit(finish(false, null, started, detail))
                return@flow
            }
            !File(sysroot, "usr/include").isDirectory -> {
                emit(finish(false, null, started, "sysroot not installed (see Settings — Toolchain)"))
                return@flow
            }
        }

        // The link step adds -lc++_shared; verify the shared runtime is
        // actually installed before compiling anything, so the user gets a
        // clear message instead of an opaque "unable to find library
        // -lc++_shared" after a long compile.
        if (findLibcxxShared(sysroot) == null) {
            emit(
                finish(
                    false, null, started,
                    "libc++_shared.so is missing — the toolchain install is incomplete. " +
                            "Reinstall the toolchain (Settings — Toolchain) and try again."
                )
            )
            return@flow
        }

        val buildDir = File(context.cacheDir, "build/${san(req.projectName)}")
        buildDir.mkdirs()
        val objDir = File(buildDir, "obj").apply { mkdirs() }
        val outFile = File(buildDir, "program")

        val tus = listOf(req.mainFile)
        emit(BuildEvent.Started(tus.size))

        // Fingerprint of every file on the include path (user modules and
        // the extra project sources). Folded into each object-cache key so
        // that any module edit forces a recompile of everything that can
        // reach it through -I.
        val depsKey = includeDirsFingerprint(req.includeDirs)

        // ---- PCH --------------------------------------------------------------
        // The header is stored NEXT TO the .pch inside the content-addressed
        // cache directory and is written exactly once. clang records the
        // header's mtime inside the .pch and refuses to use it if the file
        // is touched afterwards ("has been modified since the precompiled
        // header was built"). Rewriting a per-project header on every build
        // is what used to trigger that fatal error.
        var pchFile: File? = null
        if (req.usePch && req.pchIncludeLines.isNotEmpty()) {
            val headerText = req.pchIncludeLines.joinToString("\n") { "#include $it" } + "\n"
            val pchKey = hashOf(
                headerText + req.std.flag + req.opt.flag +
                        req.extraFlags.joinToString(" ") + sysroot.absolutePath + "pch-v3"
            )
            val candidate = File(File(context.cacheDir, "pch"), pchKey).apply { mkdirs() }
            val pchHeader = File(candidate, "mono_pch.h")
            val pch = File(candidate, "mono_pch.h.pch")
            if (pch.isFile && pchHeader.isFile) {
                pchFile = pch
                emit(BuildEvent.Log("pch: cached"))
            } else {
                emit(BuildEvent.Phase(0.08f, "building precompiled header"))
                pch.delete()
                pchHeader.delete()
                pchHeader.writeText(headerText)
                val r = runClang(buildPchArgs(req, sysroot, pchHeader, pch), sysroot)
                if (r.success && pch.isFile) {
                    pchFile = pch
                    emit(BuildEvent.Log("pch: built"))
                } else {
                    candidate.deleteRecursively()
                    val firstLine = r.output.lineSequence()
                        .firstOrNull { it.contains("error", true) } ?: "failed"
                    emit(BuildEvent.Log("pch: unavailable ($firstLine); continuing without"))
                }
            }
        }

        // ---- compile ----------------------------------------------------------
        val objects = ArrayList<File>(tus.size)
        for (idx in tus.indices) {
            currentCoroutineContext().ensureActive()
            val tu = tus[idx]
            val content = tu.readText()

            fun objFor(pch: File?): File {
                val flagsKey = req.std.flag + req.opt.flag + req.extraFlags.joinToString(" ") +
                        (pch?.parentFile?.name ?: "nopch")
                val key = hashOf(
                    tu.absolutePath + "\u0000" + content + "\u0000" + flagsKey +
                            "\u0000" + depsKey
                )
                return File(objDir, "$key.o")
            }

            var obj = objFor(pchFile)
            if (obj.isFile) {
                emit(BuildEvent.Log("${tu.name}: cached"))
            } else {
                val frac = 0.15f + 0.6f * (idx.toFloat() / tus.size)
                emit(BuildEvent.Phase(frac, "compiling ${tu.name}"))
                var r = runClang(buildCompileArgs(req, sysroot, pchFile, tu, obj), sysroot)
                // Self-heal: a stale / rejected PCH must never fail the build.
                // Drop it and compile again without.
                if (!r.success && pchFile != null && looksLikePchProblem(r.output)) {
                    pchFile.parentFile?.deleteRecursively()
                    pchFile = null
                    obj = objFor(null)
                    emit(BuildEvent.Log("pch: stale, rebuilding without it"))
                    r = runClang(buildCompileArgs(req, sysroot, null, tu, obj), sysroot)
                }
                for (d in r.diagnostics) emit(BuildEvent.DiagnosticEvent(d))
                if (!r.success || !obj.isFile) {
                    val first = r.diagnostics.firstOrNull()
                    val msg = if (first != null) {
                        "${first.file.substringAfterLast('/')}:${first.line}: ${first.message}"
                    } else {
                        val raw = rawLines(r.output)
                        raw.forEach { emit(BuildEvent.Log(it)) }
                        pickErrorLine(raw) ?: "compilation failed"
                    }
                    emit(finish(false, null, started, msg))
                    return@flow
                }
            }
            objects.add(obj)
        }

        // ---- link -------------------------------------------------------------
        currentCoroutineContext().ensureActive()
        emit(BuildEvent.Phase(0.85f, "linking"))
        val (rtBuiltins, rtUnwind) = runtimeLibs(sysroot)
        if (rtBuiltins == null || rtUnwind == null) {
            emit(
                BuildEvent.Log(
                    "link: sysroot lacks " +
                            listOfNotNull(
                                "compiler-rt builtins".takeIf { rtBuiltins == null },
                                "libunwind.a".takeIf { rtUnwind == null }
                            ).joinToString(" and ") +
                            " — linking without; reinstall a newer toolchain to fix"
                )
            )
        }
        // lld picks its flavor from the name it is started under, and the
        // bundled copy is called libld_lld.so. Use the "ld.lld" alias from the
        // start so there is no failed first attempt and no retry message.
        val lldPrimary = toolchain.lldAlias() ?: toolchain.lldFile
        var linkR = runClang(buildLinkArgs(req, sysroot, objects, outFile, lldPrimary), sysroot)
        if ((!linkR.success || !outFile.isFile) && linkR.output.contains("generic driver") &&
            lldPrimary != toolchain.lldFile
        ) {
            linkR = runClang(buildLinkArgs(req, sysroot, objects, outFile, toolchain.lldFile), sysroot)
        }
        for (d in linkR.diagnostics) emit(BuildEvent.DiagnosticEvent(d))
        if (!linkR.success || !outFile.isFile) {
            val raw = rawLines(linkR.output)
            raw.forEach { emit(BuildEvent.Log(it)) }
            val msg = linkR.diagnostics.firstOrNull()?.message
                ?: pickErrorLine(raw)
                ?: "linking failed"
            emit(finish(false, null, started, msg))
            return@flow
        }
        outFile.setExecutable(true)

        val dur = System.currentTimeMillis() - started
        emit(BuildEvent.Phase(1f, "done"))
        emit(finish(true, outFile.absolutePath, dur, "built in ${dur}ms"))
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- internals

    private fun finish(
        ok: Boolean,
        binary: String?,
        started: Long,
        message: String
    ): BuildEvent.Finished = BuildEvent.Finished(ok, binary, System.currentTimeMillis() - started, message)

    /**
     * Flags every clang invocation that parses C++ must share (compile AND
     * precompiled header): driver mode, target, sysroot, resource dir,
     * search paths, language standard, optimisation and -fPIE. A PCH built
     * with different flags is rejected by clang, and one built without the
     * sysroot cannot even find <cstdio>.
     */
    private fun commonCompileFlags(req: Request, sysroot: File): MutableList<String> {
        val args = mutableListOf(
            "--driver-mode=g++",
            "--target=aarch64-unknown-linux-android$TARGET_API",
            "--sysroot=${sysroot.absolutePath}",
            "-Qunused-arguments"
        )
        val resourceDir = File(sysroot, "resource")
        if (resourceDir.isDirectory) args += "-resource-dir=${resourceDir.absolutePath}"
        // crt objects + libc++ live both at .../<api>/ and .../ (probing both
        // makes the build resilient to sysroot layout differences).
        val libRoot = File(sysroot, "usr/lib/aarch64-linux-android")
        args += "-B${File(libRoot, "$TARGET_API").absolutePath}/"
        args += "-B${libRoot.absolutePath}/"
        args += "-L${File(libRoot, "$TARGET_API").absolutePath}"
        args += "-L${libRoot.absolutePath}"
        // Bionic runtime lives on the device itself.
        args += "-L/system/lib64"

        for (dir in req.includeDirs) args += "-I${dir.absolutePath}"
        args += req.std.flag
        args += req.opt.flag
        args += "-fPIE"
        return args
    }

    private fun buildCompileArgs(
        req: Request,
        sysroot: File,
        pch: File?,
        src: File,
        obj: File
    ): List<String> {
        val args = commonCompileFlags(req, sysroot)
        if (pch != null) {
            args += "-include-pch"
            args += pch.absolutePath
        }
        args += "-fno-color-diagnostics"
        args += "-fno-caret-diagnostics"
        args += "-fdiagnostics-absolute-paths"
        args += req.extraFlags
        args += listOf("-c", src.absolutePath, "-o", obj.absolutePath)
        return args
    }

    private fun buildPchArgs(req: Request, sysroot: File, header: File, pch: File): List<String> {
        val args = commonCompileFlags(req, sysroot)
        args += "-fno-color-diagnostics"
        args += "-fno-caret-diagnostics"
        args += "-fdiagnostics-absolute-paths"
        args += req.extraFlags
        args += listOf("-x", "c++-header", header.absolutePath, "-o", pch.absolutePath)
        return args
    }

    private fun looksLikePchProblem(output: String): Boolean {
        val o = output.lowercase()
        return "precompiled header" in o || ".pch" in o
    }

    /** Non-blank output lines, capped, for the build log. */
    private fun rawLines(output: String): List<String> =
        output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(40).toList()

    private fun pickErrorLine(lines: List<String>): String? =
        lines.firstOrNull { it.contains("error", ignoreCase = true) || it.contains("undefined") }
            ?: lines.firstOrNull()

    /**
     * Link flags mirror the compile step: same driver mode, target, sysroot,
     * --ld-path and crt/lib search paths, plus the libc++ link flags. Without
     * these the driver looks for a host "ld.lld" on PATH, misses the crt
     * objects and the C++ runtime, and linking fails with confusing errors
     * like "crtbegin_dynamic.o: No such file or directory".
     */
    private fun buildLinkArgs(
        req: Request,
        sysroot: File,
        objects: List<File>,
        outFile: File,
        lld: File
    ): List<String> {
        val args = mutableListOf(
            "--driver-mode=g++",
            "--target=aarch64-unknown-linux-android$TARGET_API",
            "--sysroot=${sysroot.absolutePath}",
            "--ld-path=${lld.absolutePath}",
            "-fuse-ld=lld"
        )
        val resourceDir = File(sysroot, "resource")
        if (resourceDir.isDirectory) args += "-resource-dir=${resourceDir.absolutePath}"

        // Same crt/lib search paths the compile step uses (probing both the
        // per-API and the flat sysroot layouts), the device's bionic for
        // -lc/-lm, and the toolchain runtime — libc++_shared.so among
        // others, which also lives in nativeLibraryDir where compiled
        // programs load it at run time.
        val libRoot = File(sysroot, "usr/lib/aarch64-linux-android")
        args += "-B${File(libRoot, "$TARGET_API").absolutePath}/"
        args += "-B${libRoot.absolutePath}/"
        args += "-L${File(libRoot, "$TARGET_API").absolutePath}"
        args += "-L${libRoot.absolutePath}"
        args += "-L${toolchain.nativeLibDir.absolutePath}"
        args += "-L/system/lib64"

        args += "-fPIE"
        args += req.extraFlags

        // libc++ link flags: suppress the driver's own (sysroot-layout-
        // dependent) stdlib selection and link the shared runtime that the
        // toolchain actually ships instead.
        args += "-stdlib=libc++"
        args += "-nostdlib++"
        args += "-lc++_shared"

        args += objects.map { it.absolutePath }

        // The driver links compiler-rt builtins and the static unwinder by
        // path / -l:libunwind.a. When the sysroot does not ship them that
        // fails ("cannot open libclang_rt.builtins.a", "unable to find
        // library -l:libunwind.a"), so skip the default libs, link the
        // system ones explicitly and add whichever runtime piece exists.
        val (builtins, unwind) = runtimeLibs(sysroot)
        if (builtins == null || unwind == null) {
            args += "-nodefaultlibs"
            args += listOf("-lc", "-lm", "-ldl")
            if (builtins != null) args += builtins.absolutePath
            if (unwind != null) args += unwind.absolutePath
        }

        args += listOf("-o", outFile.absolutePath)
        return args
    }

    /** compiler-rt builtins and static unwinder shipped in the sysroot, if any. */
    private fun runtimeLibs(sysroot: File): Pair<File?, File?> {
        val libRoot = File(sysroot, "usr/lib/aarch64-linux-android")
        val builtins = listOf(
            File(sysroot, "resource/lib/aarch64-unknown-linux-android$TARGET_API/libclang_rt.builtins.a"),
            File(sysroot, "resource/lib/linux/libclang_rt.builtins-aarch64-android.a")
        ).firstOrNull { it.isFile }
        val unwind = listOf(
            File(libRoot, "$TARGET_API/libunwind.a"),
            File(libRoot, "libunwind.a")
        ).firstOrNull { it.isFile }
        return builtins to unwind
    }

    /**
     * The shared libc++ runtime must be reachable on one of the link search
     * paths. The sysroot normally carries it; a toolchain-enabled APK also
     * has it in nativeLibraryDir (jniLibs), and at run time that is the only
     * location targetSdk 29+ allows executable mappings from.
     */
    private fun findLibcxxShared(sysroot: File): File? {
        val libRoot = File(sysroot, "usr/lib/aarch64-linux-android")
        return listOf(
            File(libRoot, "$TARGET_API/libc++_shared.so"),
            File(libRoot, "libc++_shared.so"),
            File(toolchain.nativeLibDir, "libc++_shared.so")
        ).firstOrNull { it.isFile }
    }

    /**
     * Digests the content of every file under [dirs] (modules, extra project
     * sources) so the object-cache key reflects the whole include path, not
     * just the translation unit. Sorted by relative path for determinism.
     */
    private fun includeDirsFingerprint(dirs: List<File>): String {
        val sb = StringBuilder("deps-v1")
        for (dir in dirs) {
            sb.append("\n[").append(dir.absolutePath).append("]\n")
            val files = dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeToOrSelf(dir).invariantSeparatorsPath }
            for (f in files) {
                sb.append(f.relativeToOrSelf(dir).invariantSeparatorsPath)
                sb.append('\u0000')
                sb.append(hashOfFile(f))
                sb.append('\n')
            }
        }
        return hashOf(sb.toString())
    }

    private fun hashOfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 14)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private class RunResult(val success: Boolean, val output: String, val diagnostics: List<Diagnostic>)

    private fun runClang(args: List<String>, sysroot: File): RunResult {
        val clang = toolchain.clangFile
        val tmp = File(context.cacheDir, "tmp").apply { mkdirs() }
        val pb = ProcessBuilder(listOf(clang.absolutePath) + args)
        pb.directory(tmp)
        pb.environment()["LD_LIBRARY_PATH"] = toolchain.nativeLibDir.absolutePath
        pb.environment()["PATH"] = toolchain.nativeLibDir.absolutePath + ":/system/bin"
        pb.environment()["TMPDIR"] = tmp.absolutePath
        return try {
            val p = pb.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = Thread({ p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }, "clang-out")
            val t2 = Thread({ p.errorStream.bufferedReader().forEachLine { err.append(it).append('\n') } }, "clang-err")
            t1.isDaemon = true; t2.isDaemon = true
            t1.start(); t2.start()
            val code = p.waitFor()
            t1.join(1000); t2.join(1000)
            val text = out.toString() + err.toString()
            RunResult(code == 0, text, DiagnosticsParser.parse(text))
        } catch (t: Throwable) {
            val msg = t.message ?: "could not execute clang"
            RunResult(
                false,
                msg,
                listOf(Diagnostic("", 0, 0, Severity.ERROR, msg))
            )
        }
    }

    private fun hashOf(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun san(name: String): String = name.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private companion object {
        const val TARGET_API = 26
    }
}
