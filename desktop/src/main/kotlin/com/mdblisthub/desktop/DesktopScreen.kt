package com.mdblisthub.desktop

import java.awt.GraphicsEnvironment
import java.awt.Toolkit

object DesktopScreen {
    data class Size(val width: Int, val height: Int)

    /** The primary monitor in device pixels — the TV code's `displayMetrics`. */
    fun sizePx(): Size = runCatching {
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val mode = device.displayMode
        Size(mode.width, mode.height)
    }.getOrElse {
        val d = Toolkit.getDefaultToolkit().screenSize
        Size(d.width, d.height)
    }
}
