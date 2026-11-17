package com.mdblisthub.desktop.dev

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.player.MpvVideoSurface
import com.mdblisthub.tv.player.PlaybackController
import com.mdblisthub.tv.player.VideoScaleType
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.swing.Swing
import org.jetbrains.skia.EncodedImageFormat

/**
 * Development aid: drives the real PlaybackController through a source
 * cascade (args: output png, then candidate URLs in order) and reports the
 * phases it went through.
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    val urls = args.drop(1)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    lateinit var controller: PlaybackController
    lateinit var scene: ImageComposeScene
    SwingUtilities.invokeAndWait {
        controller = PlaybackController(Context.app, scope)
        scene = ImageComposeScene(960, 540, Density(1f), coroutineContext = Dispatchers.Swing) {
            MpvVideoSurface(controller, VideoScaleType.FIT, Modifier.fillMaxSize())
        }
        controller.play(
            flowOf(*urls.mapIndexed { i, u -> PlayableStream(key = "c$i", addon = "probe", title = "c$i", url = u) }.toTypedArray()),
            expectedRuntimeMinutes = null,
        )
    }
    val start = System.currentTimeMillis()
    var lastPhase = ""
    while (System.currentTimeMillis() - start < 25_000) {
        SwingUtilities.invokeAndWait {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            val image = scene.render()
            val s = controller.state.value
            val p = controller.position.value
            val line = "${s.phase} attempt=${s.attempt}/${s.candidateCount} audio=${s.audioTracks.size} subs=${s.subtitleTracks.size} err=${s.error}"
            if (line != lastPhase) {
                println("+${System.currentTimeMillis() - start}ms $line pos=${p.positionMs}/${p.durationMs}")
                lastPhase = line
            }
            if (System.currentTimeMillis() - start > 24_000) out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        Thread.sleep(40)
    }
    SwingUtilities.invokeAndWait {
        println("final position=${controller.position.value} hint=${controller.hint()}")
        controller.seekTo(60_000)
    }
    Thread.sleep(3000)
    SwingUtilities.invokeAndWait { println("after seek: ${controller.position.value} phase=${controller.state.value.phase}") }
    SwingUtilities.invokeAndWait { controller.release() }
    kotlin.system.exitProcess(0)
}
