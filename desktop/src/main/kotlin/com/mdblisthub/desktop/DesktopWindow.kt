package com.mdblisthub.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import javax.swing.SwingUtilities

/** The main window, reachable from code that is not composed inside it. */
object DesktopWindow {
    @Volatile var window: ComposeWindow? = null
    @Volatile var state: WindowState? = null

    fun bringToFront() = SwingUtilities.invokeLater {
        val w = window ?: return@invokeLater
        if (w.extendedState and Frame.ICONIFIED != 0) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
        w.toFront()
        w.requestFocus()
    }

    val isFullscreen: Boolean get() = state?.placement == WindowPlacement.Fullscreen

    fun setFullscreen(on: Boolean) {
        val s = state ?: return
        s.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }

    fun toggleFullscreen() = setFullscreen(!isFullscreen)
}
