package com.mdblisthub.desktop.mpv

import android.util.Log
import com.sun.jna.Pointer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class MpvUnavailableException(reason: String?) : IllegalStateException(
    "libmpv could not be loaded (${reason ?: "unknown reason"}). Install mpv/libmpv to play video.",
)

/**
 * One libmpv instance: options, commands, and its event loop turned into flows.
 *
 * mpv does all the hard parts this app used to get from Media3 — HTTP/HLS/DASH
 * demuxing through FFmpeg, every audio codec (DTS, TrueHD, E-AC3) in software,
 * hardware video decoding where the GPU has it, and libass subtitles. Video is
 * pulled out frame by frame through [MpvRenderer] so Compose can draw the OSD
 * over it.
 */
class MpvPlayer(
    options: Map<String, String> = emptyMap(),
    private val tag: String = "mpv",
) : AutoCloseable {

    sealed interface Event {
        data object StartFile : Event
        data object FileLoaded : Event
        data object PlaybackRestart : Event
        data object VideoReconfig : Event
        data object Seek : Event
        data class EndFile(val reason: Int, val error: Int, val message: String?) : Event
    }

    private val lib: LibMpv = LibMpv.instance ?: throw MpvUnavailableException(LibMpv.unavailableReason)

    internal val handle: Pointer = lib.mpv_create() ?: throw MpvUnavailableException("mpv_create returned NULL")

    private val closed = AtomicBoolean(false)
    private val replyIds = AtomicLong(1000)

    private val _timePos = MutableStateFlow(0.0)
    private val _duration = MutableStateFlow(0.0)
    private val _paused = MutableStateFlow(false)
    private val _pausedForCache = MutableStateFlow(false)
    private val _coreIdle = MutableStateFlow(true)
    private val _eofReached = MutableStateFlow(false)
    private val _seeking = MutableStateFlow(false)
    private val _cacheTime = MutableStateFlow(0.0)
    private val _trackListVersion = MutableStateFlow(0L)
    private val _videoSize = MutableStateFlow(0 to 0)

    /** Seconds. */
    val timePos: StateFlow<Double> = _timePos.asStateFlow()
    val duration: StateFlow<Double> = _duration.asStateFlow()
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val pausedForCache: StateFlow<Boolean> = _pausedForCache.asStateFlow()
    val coreIdle: StateFlow<Boolean> = _coreIdle.asStateFlow()
    val eofReached: StateFlow<Boolean> = _eofReached.asStateFlow()
    val seeking: StateFlow<Boolean> = _seeking.asStateFlow()

    /** How far ahead (absolute seconds) the demuxer has the file in hand. */
    val cacheTime: StateFlow<Double> = _cacheTime.asStateFlow()

    /** Bumped whenever the track list changes; read the tracks with [tracks]. */
    val trackListVersion: StateFlow<Long> = _trackListVersion.asStateFlow()
    val videoSize: StateFlow<Pair<Int, Int>> = _videoSize.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    private var renderer: MpvRenderer? = null

    private val eventThread: Thread

    init {
        val defaults = linkedMapOf(
            "vo" to "libmpv",
            "config" to "no",
            "terminal" to "no",
            "msg-level" to "all=warn",
            "input-default-bindings" to "no",
            "input-vo-keyboard" to "no",
            "osc" to "no",
            "osd-level" to "0",
            "idle" to "yes",
            "keep-open" to "yes",
            "load-scripts" to "no",
            "ytdl" to "no",
            "audio-display" to "no",
            "sub-auto" to "no",
            "hwdec" to "auto-copy",
            "vd-lavc-threads" to "0",
            "zimg-threads" to "auto",
            "sws-fast" to "yes",
            "cache" to "yes",
            "demuxer-max-bytes" to "512MiB",
            "demuxer-max-back-bytes" to "64MiB",
            "demuxer-readahead-secs" to "120",
            "cache-pause-initial" to "yes",
            "cache-pause-wait" to "3",
            "network-timeout" to "30",
            "user-agent" to "Mozilla/5.0 (X11; Linux x86_64) OmniStream",
            "tls-verify" to "no",
            "hr-seek" to "no",
            "audio-client-name" to "OmniStream",
            "volume-max" to "150",
        )
        defaults.putAll(options)
        defaults.forEach { (k, v) ->
            val rc = lib.mpv_set_option_string(handle, k, v)
            if (rc < 0) Log.w(tag, "option $k=$v rejected: ${lib.mpv_error_string(rc)}")
        }
        val rc = lib.mpv_initialize(handle)
        if (rc < 0) {
            lib.mpv_terminate_destroy(handle)
            throw MpvUnavailableException("mpv_initialize: ${lib.mpv_error_string(rc)}")
        }
        observe(ID_TIME_POS, "time-pos", LibMpv.FORMAT_DOUBLE)
        observe(ID_DURATION, "duration", LibMpv.FORMAT_DOUBLE)
        observe(ID_PAUSE, "pause", LibMpv.FORMAT_FLAG)
        observe(ID_PAUSED_FOR_CACHE, "paused-for-cache", LibMpv.FORMAT_FLAG)
        observe(ID_CORE_IDLE, "core-idle", LibMpv.FORMAT_FLAG)
        observe(ID_EOF, "eof-reached", LibMpv.FORMAT_FLAG)
        observe(ID_SEEKING, "seeking", LibMpv.FORMAT_FLAG)
        observe(ID_CACHE_TIME, "demuxer-cache-time", LibMpv.FORMAT_DOUBLE)
        observe(ID_TRACKS, "track-list/count", LibMpv.FORMAT_INT64)
        observe(ID_AID, "aid", LibMpv.FORMAT_STRING)
        observe(ID_SID, "sid", LibMpv.FORMAT_STRING)
        observe(ID_WIDTH, "width", LibMpv.FORMAT_INT64)
        observe(ID_HEIGHT, "height", LibMpv.FORMAT_INT64)

        eventThread = Thread({ eventLoop() }, "$tag-events").apply {
            isDaemon = true
            start()
        }
        live += this
    }

    private fun observe(id: Long, name: String, format: Int) {
        lib.mpv_observe_property(handle, id, name, format)
    }

    // ----------------------------------------------------------------------
    // Commands
    // ----------------------------------------------------------------------

    fun command(vararg args: String): Int {
        if (closed.get()) return -1
        val rc = lib.mpv_command(handle, arrayOf(*args, null))
        if (rc < 0) Log.w(tag, "command ${args.firstOrNull()} failed: ${lib.mpv_error_string(rc)}")
        return rc
    }

    /** Fire-and-forget: never blocks the caller on network I/O (loadfile, seek). */
    fun commandAsync(vararg args: String) {
        if (closed.get()) return
        lib.mpv_command_async(handle, replyIds.incrementAndGet(), arrayOf(*args, null))
    }

    fun setProperty(name: String, value: String): Int {
        if (closed.get()) return -1
        return lib.mpv_set_property_string(handle, name, value)
    }

    fun getProperty(name: String): String? {
        if (closed.get()) return null
        val ptr = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            lib.mpv_free(ptr)
        }
    }

    fun load(url: String, startSeconds: Double? = null, extra: Map<String, String> = emptyMap()) {
        _eofReached.value = false
        val opts = buildList {
            if (startSeconds != null && startSeconds > 0) add("start=${"%.3f".format(java.util.Locale.ROOT, startSeconds)}")
            extra.forEach { (k, v) -> add("$k=${v.replace(",", "\\,")}") }
        }.joinToString(",")
        // mpv 0.38 added an index argument before the options; "-1" keeps
        // the same meaning (append/replace) the older 3-argument form had.
        if (opts.isEmpty()) commandAsync("loadfile", url, "replace")
        else commandAsync("loadfile", url, "replace", "-1", opts)
    }

    fun stop() = commandAsync("stop")

    fun setPaused(paused: Boolean) {
        setProperty("pause", if (paused) "yes" else "no")
    }

    fun seekAbsolute(seconds: Double, exact: Boolean = false) {
        commandAsync("seek", "%.3f".format(java.util.Locale.ROOT, seconds.coerceAtLeast(0.0)), if (exact) "absolute+exact" else "absolute+keyframes")
    }

    data class Track(
        /** mpv's own track id, as used by `aid`/`sid`. */
        val mpvId: Int,
        val type: String,
        val title: String?,
        val lang: String?,
        val codec: String?,
        val channels: Int?,
        val external: Boolean,
        val selected: Boolean,
    )

    fun tracks(): List<Track> {
        val count = getProperty("track-list/count")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { i ->
            val p = "track-list/$i"
            val id = getProperty("$p/id")?.toIntOrNull() ?: return@mapNotNull null
            Track(
                mpvId = id,
                type = getProperty("$p/type").orEmpty(),
                title = getProperty("$p/title"),
                lang = getProperty("$p/lang"),
                codec = getProperty("$p/codec"),
                channels = getProperty("$p/demux-channel-count")?.toIntOrNull(),
                external = getProperty("$p/external") == "yes",
                selected = getProperty("$p/selected") == "yes",
            )
        }
    }

    internal fun attachRenderer(r: MpvRenderer?) {
        renderer = r
    }

    // ----------------------------------------------------------------------
    // Event loop
    // ----------------------------------------------------------------------

    private fun eventLoop() {
        while (!closed.get()) {
            val ev = lib.mpv_wait_event(handle, 0.5)
            val id = ev.getInt(0)
            when (id) {
                LibMpv.EVENT_NONE -> Unit
                LibMpv.EVENT_SHUTDOWN -> return
                LibMpv.EVENT_START_FILE -> _events.tryEmit(Event.StartFile)
                LibMpv.EVENT_FILE_LOADED -> _events.tryEmit(Event.FileLoaded)
                LibMpv.EVENT_PLAYBACK_RESTART -> _events.tryEmit(Event.PlaybackRestart)
                LibMpv.EVENT_VIDEO_RECONFIG -> _events.tryEmit(Event.VideoReconfig)
                LibMpv.EVENT_SEEK -> _events.tryEmit(Event.Seek)
                LibMpv.EVENT_END_FILE -> {
                    val data = ev.getPointer(16)
                    val reason = data?.getInt(0) ?: LibMpv.END_ERROR
                    val error = data?.getInt(4) ?: 0
                    val message = if (error < 0) runCatching { lib.mpv_error_string(error) }.getOrNull() else null
                    _events.tryEmit(Event.EndFile(reason, error, message))
                }
                LibMpv.EVENT_PROPERTY_CHANGE -> onProperty(ev.getLong(8), ev.getPointer(16))
            }
        }
    }

    private fun onProperty(id: Long, prop: Pointer?) {
        prop ?: return
        val format = prop.getInt(8)
        val data = prop.getPointer(16)
        fun double(): Double? = if (format == LibMpv.FORMAT_DOUBLE && data != null) data.getDouble(0) else null
        fun flag(): Boolean? = if (format == LibMpv.FORMAT_FLAG && data != null) data.getInt(0) != 0 else null
        fun long(): Long? = if (format == LibMpv.FORMAT_INT64 && data != null) data.getLong(0) else null
        when (id) {
            ID_TIME_POS -> _timePos.value = double() ?: 0.0
            ID_DURATION -> _duration.value = double() ?: 0.0
            ID_PAUSE -> _paused.value = flag() ?: false
            ID_PAUSED_FOR_CACHE -> _pausedForCache.value = flag() ?: false
            ID_CORE_IDLE -> _coreIdle.value = flag() ?: true
            ID_EOF -> _eofReached.value = flag() ?: false
            ID_SEEKING -> _seeking.value = flag() ?: false
            ID_CACHE_TIME -> _cacheTime.value = double() ?: 0.0
            ID_TRACKS, ID_AID, ID_SID -> _trackListVersion.value = _trackListVersion.value + 1
            ID_WIDTH -> _videoSize.value = (long()?.toInt() ?: 0) to _videoSize.value.second
            ID_HEIGHT -> _videoSize.value = _videoSize.value.first to (long()?.toInt() ?: 0)
        }
    }

    /** Frees libmpv. The render context goes first, as the API requires. */
    override fun close() = close(wait = false)

    /**
     * [wait] makes the caller block until mpv is gone — what process exit
     * needs, since a libmpv still running while C++ statics are destroyed
     * aborts the process ("pure virtual method called").
     */
    fun close(wait: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        live -= this
        renderer?.close()
        renderer = null
        val destroy = Runnable {
            runCatching { lib.mpv_wakeup(handle) }
            runCatching { eventThread.join(2_000) }
            lib.mpv_terminate_destroy(handle)
        }
        if (wait) destroy.run()
        else Thread(destroy, "$tag-destroy").apply { isDaemon = true }.start()
    }

    val isClosed: Boolean get() = closed.get()

    companion object {
        private val live: MutableSet<MpvPlayer> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Stops every player still open. Called on the way out of the process. */
        fun shutdownAll() {
            live.toList().forEach { runCatching { it.close(wait = true) } }
        }

        private const val ID_TIME_POS = 1L
        private const val ID_DURATION = 2L
        private const val ID_PAUSE = 3L
        private const val ID_PAUSED_FOR_CACHE = 4L
        private const val ID_CORE_IDLE = 5L
        private const val ID_EOF = 6L
        private const val ID_SEEKING = 7L
        private const val ID_CACHE_TIME = 8L
        private const val ID_TRACKS = 9L
        private const val ID_AID = 10L
        private const val ID_SID = 11L
        private const val ID_WIDTH = 12L
        private const val ID_HEIGHT = 13L
    }
}
