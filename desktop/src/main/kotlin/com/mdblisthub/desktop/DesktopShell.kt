package com.mdblisthub.desktop

import java.awt.Desktop
import java.net.URI

/** Hands URLs to the operating system — the browser, or whatever owns the scheme. */
object DesktopShell {
    fun open(url: String): Boolean {
        val target = url.trim()
        if (target.isEmpty()) return false
        // xdg-open first on Linux: AWT's Desktop browse is missing on many
        // Wayland/GNOME setups and silently does nothing on others.
        if (!AppDirs.isWindows && !AppDirs.isMac) {
            if (runCatching { ProcessBuilder("xdg-open", target).start(); true }.getOrDefault(false)) return true
        }
        return runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(target)); true
            } else if (AppDirs.isWindows) {
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target).start(); true
            } else if (AppDirs.isMac) {
                ProcessBuilder("open", target).start(); true
            } else false
        }.getOrDefault(false)
    }
}
