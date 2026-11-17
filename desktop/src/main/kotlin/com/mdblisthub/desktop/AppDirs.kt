package com.mdblisthub.desktop

import java.io.File

/**
 * Where the app keeps its files, following each OS's convention:
 * XDG on Linux, %APPDATA% / %LOCALAPPDATA% on Windows, ~/Library on macOS.
 */
object AppDirs {
    private const val NAME = "OmniStream"
    private val home = File(System.getProperty("user.home"))
    private val os = System.getProperty("os.name").orEmpty().lowercase()

    val isWindows = os.contains("win")
    val isMac = os.contains("mac")

    val data: File by lazy {
        when {
            isWindows -> File(System.getenv("APPDATA") ?: home.path, NAME)
            isMac -> File(home, "Library/Application Support/$NAME")
            else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", NAME)
        }.also { it.mkdirs() }
    }

    val cache: File by lazy {
        when {
            isWindows -> File(System.getenv("LOCALAPPDATA") ?: data.path, "$NAME/cache")
            isMac -> File(home, "Library/Caches/$NAME")
            else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.cache", NAME)
        }.also { it.mkdirs() }
    }

    val files: File by lazy { File(data, "files").also { it.mkdirs() } }
    val databases: File by lazy { File(data, "databases").also { it.mkdirs() } }
    val datastore: File by lazy { File(data, "datastore").also { it.mkdirs() } }
    val preferences: File by lazy { File(data, "shared_prefs").also { it.mkdirs() } }
}
