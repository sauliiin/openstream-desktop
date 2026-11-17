package com.mdblisthub.desktop

import android.util.Log
import java.awt.Toolkit
import java.io.File

/** What makes the AppImage behave like an installed Linux app. */
object LinuxIntegration {

    /** Matches `StartupWMClass` in the .desktop file, so the dock shows our icon. */
    const val WM_CLASS = "omnistream"

    fun setWindowClass() {
        if (AppDirs.isWindows || AppDirs.isMac) return
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(toolkit, WM_CLASS)
        }
    }

    /**
     * When run from an AppImage, puts OmniStream in the application menu:
     * a `.desktop` entry pointing at the AppImage's current path, and the
     * icon. Rewritten on every launch, so moving the file keeps the entry
     * working; skipped when an AppImage integrator (AppImageLauncher, Gear
     * Lever) already manages one, or with OMNISTREAM_NO_DESKTOP_ENTRY set.
     */
    fun installDesktopEntry() {
        if (AppDirs.isWindows || AppDirs.isMac) return
        val appImage = System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() } ?: return
        if (System.getenv("OMNISTREAM_NO_DESKTOP_ENTRY") != null) return
        runCatching {
            val home = System.getProperty("user.home")
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
            val applications = File(dataHome, "applications")
            val managed = applications.listFiles().orEmpty().any {
                it.name.startsWith("appimagekit_") && it.readText().contains(appImage)
            }
            if (managed) return

            val icon = File(dataHome, "icons/hicolor/512x512/apps/omnistream.png")
            if (!icon.isFile) {
                icon.parentFile.mkdirs()
                LinuxIntegration::class.java.getResourceAsStream("/icon.png")?.use { input ->
                    icon.outputStream().use { input.copyTo(it) }
                }
            }
            val entry = File(applications, "omnistream.desktop")
            val content = """
                [Desktop Entry]
                Type=Application
                Name=OmniStream
                GenericName=Media Center
                Comment=Your lists, a cinematic home and a native player
                Exec="${appImage.replace("\"", "\\\"")}" %U
                Icon=omnistream
                Terminal=false
                Categories=AudioVideo;Video;Player;
                StartupWMClass=$WM_CLASS
                X-AppImage-Integrate=false
            """.trimIndent() + "\n"
            if (!entry.isFile || entry.readText() != content) {
                applications.mkdirs()
                entry.writeText(content)
                runCatching { ProcessBuilder("update-desktop-database", applications.path).start() }
            }
        }.onFailure { Log.w("Linux", "desktop entry: ${it.message}") }
    }
}
