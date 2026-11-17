package com.mdblisthub.desktop.mpv

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.mdblisthub.desktop.AppDirs
import com.mdblisthub.desktop.res.AndroidResources
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A short, self-contained video: the intro, a hero autotrailer, a trailer.
 * One mpv instance plus its renderer, released with the composable.
 */
class MpvClip(
    muted: Boolean,
    loop: Boolean,
    maxWidth: Int,
    maxHeight: Int,
) : AutoCloseable {
    val player = MpvPlayer(
        options = buildMap {
            put("mute", if (muted) "yes" else "no")
            put("loop-file", if (loop) "inf" else "no")
            put("cache-pause-initial", "no")
            put("demuxer-readahead-secs", "20")
            put("ytdl", "yes")
            put("ytdl-format", "bestvideo[height<=?1080]+bestaudio/best")
        },
        tag = "clip",
    )
    val renderer = MpvRenderer(player, maxWidth, maxHeight)

    private val _playing = MutableStateFlow(false)
    /** True once the first frame is on its way (mpv restarted playback after load). */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _finished = MutableStateFlow(false)
    /** True when it ended or failed. */
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val watcher = Thread({
        runCatching {
            kotlinx.coroutines.runBlocking {
                launch {
                    player.eofReached.collect { if (it) _finished.value = true }
                }
                player.events.collect { event ->
                    when (event) {
                        MpvPlayer.Event.PlaybackRestart -> _playing.value = true
                        is MpvPlayer.Event.EndFile -> if (event.reason == LibMpv.END_ERROR) {
                            Log.w("clip", "clip failed: ${event.message}")
                            _finished.value = true
                        }
                        else -> Unit
                    }
                }
            }
        }
    }, "clip-watch").apply { isDaemon = true; start() }

    fun load(url: String, startSeconds: Double? = null) {
        _playing.value = false
        _finished.value = false
        player.load(url, startSeconds)
    }

    fun setMuted(muted: Boolean) {
        player.setProperty("mute", if (muted) "yes" else "no")
    }

    override fun close() {
        watcher.interrupt()
        player.close()
    }

    companion object {
        /** Null when libmpv is missing — callers skip the video and show the artwork. */
        fun createOrNull(muted: Boolean = false, loop: Boolean = false, maxWidth: Int = 1920, maxHeight: Int = 1080): MpvClip? =
            runCatching { MpvClip(muted, loop, maxWidth, maxHeight) }
                .onFailure { Log.w("clip", "video unavailable: ${it.message}") }
                .getOrNull()

        /** mpv plays files, not classpath entries: raw resources are copied out once. */
        fun rawResourceFile(id: Int, name: String): File? = runCatching {
            val out = File(AppDirs.cache, "raw/$name")
            if (!out.isFile || out.length() == 0L) {
                out.parentFile.mkdirs()
                val tmp = File(out.path + ".tmp")
                AndroidResources.open(id).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(out)
            }
            out
        }.getOrNull()
    }
}

@Composable
fun rememberMpvClip(
    key: Any?,
    muted: Boolean = false,
    loop: Boolean = false,
    maxWidth: Int = 1920,
    maxHeight: Int = 1080,
): MpvClip? {
    val clip = remember(key) { MpvClip.createOrNull(muted, loop, maxWidth, maxHeight) }
    DisposableEffect(clip) { onDispose { clip?.close() } }
    return clip
}
