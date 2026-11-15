package dev.mono.storage.settings

import android.content.Context
import android.content.SharedPreferences
import dev.mono.storage.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A project the user opened, with when they last worked on it. */
data class RecentProject(val name: String, val openedAtMs: Long)

/**
 * Application settings backed by SharedPreferences.
 *
 * Every write bumps [revision], a StateFlow the UI collects to re-read values
 * reactively without per-field flows.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mono_settings", Context.MODE_PRIVATE)

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        // Must actually commit. (`prefs.edit().apply(block)` is Kotlin's scope
        // function, not Editor.apply(), so nothing was ever saved.)
        editor.apply()
        _revision.value = _revision.value + 1
    }

    var themeMode: ThemeMode
        get() = if (prefs.getString(KEY_THEME, "dark") == "light") ThemeMode.LIGHT else ThemeMode.DARK
        set(value) = edit { putString(KEY_THEME, if (value == ThemeMode.LIGHT) "light" else "dark") }

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT, 13f).coerceIn(8f, 32f)
        set(value) = edit { putFloat(KEY_FONT, value.coerceIn(8f, 32f)) }

    var indentWidth: Int
        get() = prefs.getInt(KEY_INDENT, 4).coerceIn(2, 8)
        set(value) = edit { putInt(KEY_INDENT, value.coerceIn(2, 8)) }

    // --- compiler ---

    var cppStandard: String
        get() = prefs.getString(KEY_STD, "c++17") ?: "c++17"
        set(value) = edit { putString(KEY_STD, value) }

    var optLevel: String
        get() = prefs.getString(KEY_OPT, "-O0") ?: "-O0"
        set(value) = edit { putString(KEY_OPT, value) }

    var extraFlags: String
        get() = prefs.getString(KEY_FLAGS, "") ?: ""
        set(value) = edit { putString(KEY_FLAGS, value) }

    var usePch: Boolean
        get() = prefs.getBoolean(KEY_PCH, true)
        set(value) = edit { putBoolean(KEY_PCH, value) }

    var pchIncludes: String
        get() = prefs.getString(KEY_PCH_INC, "<iostream>\n<cstdio>\n<cstdlib>\n<cstring>\n<vector>\n<string>") ?: ""
        set(value) = edit { putString(KEY_PCH_INC, value) }

    var runTimeoutSec: Int
        get() = prefs.getInt(KEY_TIMEOUT, 30).coerceIn(1, 600)
        set(value) = edit { putInt(KEY_TIMEOUT, value.coerceIn(1, 600)) }

    var autosaveSec: Int
        get() = prefs.getInt(KEY_AUTOSAVE, 3).coerceIn(1, 60)
        set(value) = edit { putInt(KEY_AUTOSAVE, value.coerceIn(1, 60)) }

    // --- storage ---

    var useSharedStorage: Boolean
        get() = prefs.getBoolean(KEY_SHARED, false)
        set(value) = edit { putBoolean(KEY_SHARED, value) }

    var sharedRootUri: String
        get() = prefs.getString(KEY_SHARED_URI, "") ?: ""
        set(value) = edit { putString(KEY_SHARED_URI, value) }

    // --- toolchain ---

    var toolchainUrl: String
        get() = prefs.getString(KEY_TC_URL, "") ?: ""
        set(value) = edit { putString(KEY_TC_URL, value) }

    // --- session ---

    var lastProject: String
        get() = prefs.getString(KEY_LAST_PROJECT, "") ?: ""
        set(value) = edit { putString(KEY_LAST_PROJECT, value) }

    // --- recent projects ---

    /** Most recently opened first, at most [MAX_RECENTS]. */
    val recentProjects: List<RecentProject>
        get() = (prefs.getString(KEY_RECENTS, "") ?: "")
            .lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val ts = line.substring(0, tab).toLongOrNull() ?: return@mapNotNull null
                val name = line.substring(tab + 1)
                if (name.isBlank()) null else RecentProject(name, ts)
            }
            .toList()

    /** Marks [name] as just worked on (moves it to the top). */
    fun touchRecent(name: String) {
        val clean = name.replace('\n', ' ').replace('\t', ' ')
        val rest = recentProjects.filter { it.name != clean }
        writeRecents(listOf(RecentProject(clean, System.currentTimeMillis())) + rest)
    }

    fun renameRecent(old: String, new: String) {
        writeRecents(recentProjects.map { if (it.name == old) it.copy(name = new) else it })
    }

    fun removeRecent(name: String) {
        writeRecents(recentProjects.filter { it.name != name })
    }

    private fun writeRecents(list: List<RecentProject>) {
        val raw = list.take(MAX_RECENTS).joinToString("\n") { "${it.openedAtMs}\t${it.name}" }
        edit { putString(KEY_RECENTS, raw) }
    }

    var focusMode: Boolean
        get() = prefs.getBoolean(KEY_FOCUS, false)
        set(value) = edit { putBoolean(KEY_FOCUS, value) }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_FONT = "font_size"
        const val KEY_INDENT = "indent_width"
        const val KEY_STD = "cpp_std"
        const val KEY_OPT = "opt_level"
        const val KEY_FLAGS = "extra_flags"
        const val KEY_PCH = "use_pch"
        const val KEY_PCH_INC = "pch_includes"
        const val KEY_TIMEOUT = "run_timeout"
        const val KEY_AUTOSAVE = "autosave"
        const val KEY_SHARED = "use_shared_storage"
        const val KEY_SHARED_URI = "shared_root_uri"
        const val KEY_TC_URL = "toolchain_url"
        const val KEY_LAST_PROJECT = "last_project"
        const val KEY_FOCUS = "focus_mode"
        const val KEY_RECENTS = "recent_projects"
        const val MAX_RECENTS = 8
    }
}
