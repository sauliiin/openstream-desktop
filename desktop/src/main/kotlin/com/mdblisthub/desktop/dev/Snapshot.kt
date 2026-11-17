package com.mdblisthub.desktop.dev

import android.app.Activity
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import com.mdblisthub.desktop.AppContent
import com.mdblisthub.desktop.bootstrap
import com.mdblisthub.tv.update.AppUpdateManager
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat

/**
 * Development aid: runs the whole app in an offscreen scene — no window — and
 * writes PNG snapshots, optionally pressing keys in between. Used to check
 * screens on machines (and CI) without a display.
 *
 * Arguments: output dir, then steps of `<ms>:shot:<name>` or `<ms>:key:<KeyName>`.
 */
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "snapshots" }).also { it.mkdirs() }
    val steps = args.drop(1).map { it.split(':', limit = 3) }.sortedBy { it[0].toLong() }
    val graph = bootstrap()
    val updates = AppUpdateManager(Activity {}, "sauliiin/openstream-desktop")
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        scene = ImageComposeScene(1280, 720, Density(1f), coroutineContext = Dispatchers.Swing) {
            AppContent(graph, updates)
        }
    }
    val start = System.nanoTime()
    var next = 0
    val end = (steps.lastOrNull()?.get(0)?.toLong() ?: 5_000L) + 200
    while (true) {
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        var image: org.jetbrains.skia.Image? = null
        SwingUtilities.invokeAndWait {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            image = scene.render(System.nanoTime() - start)
        }
        while (next < steps.size && steps[next][0].toLong() <= elapsedMs) {
            val (_, kind, value) = steps[next]
            when (kind) {
                "shot" -> {
                    val data = image!!.encodeToData(EncodedImageFormat.PNG)!!
                    File(out, "$value.png").writeBytes(data.bytes)
                    println("snapshot $value at ${elapsedMs}ms")
                }
                "key" -> {
                    // AWT names: RIGHT, DOWN, ENTER, ESCAPE...
                    val vk = java.awt.event.KeyEvent::class.java.getField("VK_$value").getInt(null)
                    SwingUtilities.invokeAndWait {
                        // Same as the window: what the scene did not use goes to UnfocusedKeys.
                        val down = keyEvent(vk, KeyEventType.KeyDown)
                        if (!scene.sendKeyEvent(down)) com.mdblisthub.desktop.ui.UnfocusedKeys.handle(down)
                        scene.sendKeyEvent(keyEvent(vk, KeyEventType.KeyUp))
                    }
                }
            }
            next++
        }
        if (elapsedMs > end) break
        Thread.sleep(16)
    }
    SwingUtilities.invokeAndWait { scene.close() }
    kotlin.system.exitProcess(0)
}

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun keyEvent(vk: Int, type: KeyEventType): androidx.compose.ui.input.key.KeyEvent =
    androidx.compose.ui.input.key.KeyEvent(
        key = androidx.compose.ui.input.key.Key(vk),
        type = type,
    )
