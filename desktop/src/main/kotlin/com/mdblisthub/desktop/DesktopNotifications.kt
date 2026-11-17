package com.mdblisthub.desktop

import java.awt.SystemTray
import java.awt.TrayIcon

/** Fire-and-forget OS notifications: notify-send on Linux, the tray balloon elsewhere. */
object DesktopNotifications {
    fun show(title: String, text: String) {
        if (!AppDirs.isWindows && !AppDirs.isMac) {
            val sent = runCatching {
                ProcessBuilder("notify-send", "--app-name=OmniStream", "--icon=omnistream", title, text)
                    .redirectErrorStream(true).start(); true
            }.getOrDefault(false)
            if (sent) return
        }
        runCatching {
            if (!SystemTray.isSupported()) return
            val tray = SystemTray.getSystemTray()
            val image = java.awt.Toolkit.getDefaultToolkit().createImage(
                DesktopNotifications::class.java.getResource("/icon.png"),
            )
            val icon = TrayIcon(image, "OmniStream").apply { isImageAutoSize = true }
            tray.add(icon)
            icon.displayMessage(title, text, TrayIcon.MessageType.INFO)
            Thread {
                Thread.sleep(8_000)
                runCatching { tray.remove(icon) }
            }.apply { isDaemon = true }.start()
        }
    }
}
