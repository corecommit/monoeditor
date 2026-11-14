package dev.mono.storage.model

/** A user project: `Mono/projects/<name>/main.cpp`. */
data class ProjectInfo(
    val name: String,
    val dirPath: String
) {
    val mainFileName: String get() = "main.cpp"
    val isValid: Boolean get() = name.isNotBlank()
}

/** Metadata of one file (or directory) shown in lists and drawers. */
data class FileMeta(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long
) {
    val isCpp: Boolean get() = name.endsWith(".cpp", ignoreCase = true) || name.endsWith(".cc", ignoreCase = true)
    val isHeader: Boolean get() = name.endsWith(".h", ignoreCase = true) || name.endsWith(".hpp", ignoreCase = true)
    val isSourceLike: Boolean get() = isCpp || isHeader || name.endsWith(".c", true)
}

enum class ThemeMode { DARK, LIGHT }

/** Result wrapper used by repository operations so UI can render clear errors. */
sealed class StorageResult<out T> {
    data class Ok<T>(val value: T) : StorageResult<T>()
    data class Err(val message: String) : StorageResult<Nothing>()

    inline fun <R> map(f: (T) -> R): StorageResult<R> = when (this) {
        is Ok -> Ok(f(value))
        is Err -> this
    }

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): String? = (this as? Err)?.message
}
