package com.mdblisthub.tv.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mdblisthub.desktop.mpv.LibMpv
import com.mdblisthub.desktop.mpv.MpvPlayer
import com.mdblisthub.desktop.mpv.MpvRenderer
import com.mdblisthub.tv.core.model.PlayableStream
import com.mdblisthub.tv.core.model.PlaybackHint
import com.mdblisthub.tv.core.model.SourceReadiness
import com.mdblisthub.tv.core.model.StreamKind
import com.mdblisthub.tv.core.model.SubtitleOption
import com.mdblisthub.tv.core.model.SubtitleTrack
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a title, deciding on its own which link to use — until it can't.
 *
 * The desktop engine behind the same contract the TV app's Media3 controller
 * has: **the user does not pick a source while automatic playback still has a
 * chance.** A queue of candidates (arriving as a [Flow] while the repository
 * still probes mirrors) is walked until one produces a frame; a dead mirror,
 * an expired debrid token or a removal-notice clip are all "next one". Only
 * when every candidate has failed is the queue offered to the user through
 * [PlaybackState.availableSources] and [playManual].
 *
 * libmpv does the playback itself (see [MpvPlayer]); "ready" here means mpv
 * reported a playback restart after loading the file — it demuxed far enough
 * to decode — not that a URL was accepted.
 *
 * Everything here runs on [scope]'s dispatcher (the UI thread); mpv's own
 * events are collected onto it.
 */
class PlaybackController(
    context: Context,
    private val scope: CoroutineScope,
    /** Kept for signature parity with the TV app; mpv opens links itself. */
    @Suppress("UNUSED_PARAMETER") callFactory: okhttp3.Call.Factory? = null,
    private val onSourceOpened: (PlayableStream?) -> Unit = {},
) {
    @Suppress("unused") private val appContext = context.applicationContext

    /** Null when libmpv is missing; the UI then shows the failure instead of crashing. */
    val player: MpvPlayer? = runCatching {
        MpvPlayer(
            options = mapOf(
                "stream-lavf-o" to "reconnect=1,reconnect_on_network_error=1,reconnect_streamed=1,reconnect_delay_max=7",
                "sub-ass-override" to "force",
                "sub-font-size" to "44",
                "sub-border-size" to "2",
                "sub-shadow-offset" to "2",
                "sub-color" to "#FFFF00",
            ),
            tag = TAG,
        )
    }.onFailure { Log.e(TAG, "mpv unavailable", it) }.getOrNull()

    /** Frames for [MpvVideoSurface]. Lives as long as the controller, so a mini-player hand-off keeps it. */
    val renderer: MpvRenderer? = player?.let { MpvRenderer(it) }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(PlaybackPosition())
    val position: StateFlow<PlaybackPosition> = _position.asStateFlow()

    private val _activeSubtitleCue = MutableStateFlow<String?>(null)
    val activeSubtitleCue: StateFlow<String?> = _activeSubtitleCue.asStateFlow()

    private var queue: MutableList<PlayableStream> = mutableListOf()
    private var queueIndex = -1
    private var passes = 0
    private var candidatesCollecting = false
    private var awaitingCandidate = false
    private var candidatesJob: Job? = null
    private var manualMode = false

    private var resumePercent: Float? = null
    private var resumeApplied = false
    private var resumeExactMs: Long? = null
    private var preferredFilename: String? = null
    private var awaitingPreferred = false
    private var preferredWatch: Job? = null
    private var expectedRuntimeMs: Long? = null
    private val rejectedIndices = mutableSetOf<Int>()

    private var watchdog: Job? = null
    private var ticker: Job? = null
    private var stallWatch: Job? = null
    private var subtitleTicker: Job? = null

    private var playStartedAtMs = 0L
    private var attemptStartedAtMs = 0L

    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekJob: Job? = null
    private var nudgeCount = 0
    private var osdVisible = true

    @Volatile private var committedStream: PlayableStream? = null
    private var committedRetries = 0
    private var failoverPositionMs: Long? = null
    private var failoverPaused: Boolean? = null

    /** True between `loadfile` and the first playback restart of that file. */
    private var opening = false
    private var fileLoaded = false

    private var subtitleTrack: SubtitleTrack? = null

    /** App track index -> mpv track id. */
    private var audioIds: List<Int> = emptyList()
    private var subtitleIds: List<Int> = emptyList()

    private var volume = 100
    private var muted = false

    init {
        val p = player
        if (p != null) {
            scope.launch {
                p.events.collect { event ->
                    when (event) {
                        MpvPlayer.Event.FileLoaded -> fileLoaded = true
                        MpvPlayer.Event.PlaybackRestart -> if (opening && fileLoaded) onReady()
                        is MpvPlayer.Event.EndFile -> onEndFile(event)
                        else -> Unit
                    }
                }
            }
            scope.launch { p.eofReached.collect { if (it && committedStream != null) onEnded() } }
            scope.launch { p.trackListVersion.collect { refreshTracks() } }
            scope.launch {
                p.pausedForCache.collect { waiting ->
                    if (committedStream == null) return@collect
                    _state.update { it.copy(phase = nextPollPhase(it.phase)) }
                    if (waiting) Log.i(TAG, "rebuffer at ${positionMs()}ms")
                }
            }
            scope.launch { p.paused.collect { _state.update { it.copy(phase = nextPollPhase(it.phase)) } } }
        }
    }

    // ----------------------------------------------------------------------
    // Starting playback
    // ----------------------------------------------------------------------

    fun play(
        candidates: Flow<PlayableStream>,
        resumeAtPercent: Float? = null,
        expectedRuntimeMinutes: Int? = null,
        selectManually: Boolean = false,
        hint: PlaybackHint? = null,
    ) {
        stopInternal()
        subtitleTrack = null

        queue = mutableListOf()
        queueIndex = -1
        passes = 0
        rejectedIndices.clear()
        manualMode = false
        committedStream = null
        committedRetries = 0
        expectedRuntimeMs = expectedRuntimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
        candidatesCollecting = true
        awaitingCandidate = true
        resumePercent = resumeAtPercent?.takeIf { it > 1f && it < 95f }
        resumeApplied = false

        val storedPercent = hint?.progressPercent
        val syncedPercent = resumePercent
        val stillCurrent = storedPercent != null &&
            (syncedPercent == null || storedPercent >= syncedPercent - HINT_PERCENT_TOLERANCE)
        resumeExactMs = hint?.positionMs?.takeIf { it > 0 && stillCurrent }
        preferredFilename = hint?.sourceFilename?.takeIf { it.isNotBlank() }

        preferredWatch?.cancel()
        awaitingPreferred = preferredFilename != null && !selectManually
        preferredWatch = if (awaitingPreferred) {
            scope.launch {
                delay(PREFERRED_GRACE_MS)
                awaitingPreferred = false
                if (awaitingCandidate && queue.isNotEmpty()) {
                    awaitingCandidate = false
                    tryAdvance()
                }
            }
        } else null

        playStartedAtMs = SystemClock.elapsedRealtime()

        if (player == null) {
            _state.value = PlaybackState(phase = PlaybackPhase.FAILED, error = PlaybackFailure.ManualFailed)
            return
        }

        val initialPhase = if (selectManually) PlaybackPhase.SELECTING else PlaybackPhase.RESOLVING
        _state.value = PlaybackState(phase = initialPhase, candidateCount = 0)

        candidatesJob = scope.launch {
            candidates
                .filter { it.playable }
                .catch { }
                .collect { stream ->
                    queue += stream
                    if (committedStream == null && preferredFilename != null &&
                        stream.filename != null && stream.filename == preferredFilename
                    ) {
                        queue.removeAt(queue.lastIndex)
                        queue.add((queueIndex + 1).coerceIn(0, queue.size), stream)
                        preferredFilename = null
                        awaitingPreferred = false
                        preferredWatch?.cancel()
                    }
                    _state.update {
                        it.copy(
                            candidateCount = queue.size,
                            availableSources = if (selectManually) queue.toList() else it.availableSources,
                        )
                    }
                    if (!selectManually && awaitingCandidate && !awaitingPreferred) {
                        awaitingCandidate = false
                        tryAdvance()
                    }
                }
            candidatesCollecting = false
            awaitingPreferred = false
            preferredWatch?.cancel()
            if (selectManually) {
                if (queue.isEmpty()) fail(PlaybackFailure.NoCandidates)
            } else if (awaitingCandidate) {
                awaitingCandidate = false
                tryAdvance()
            }
        }
    }

    private fun tryAdvance() {
        watchdog?.cancel()
        val nextIndex = queueIndex + 1

        if (nextIndex >= queue.size) {
            if (candidatesCollecting) {
                awaitingCandidate = true
                _state.update { it.copy(phase = PlaybackPhase.RESOLVING) }
                return
            }
            if (queue.isEmpty()) {
                fail(PlaybackFailure.NoCandidates)
                return
            }
            passes++
            val retryable = queue.indices.any { it !in rejectedIndices && queue[it].url != null }
            if (!retryable || (passes >= MAX_PASSES && sincePlayMs() >= PERSIST_MS)) {
                fail(
                    if (queue.all { it.readiness == SourceReadiness.NOT_CACHED }) PlaybackFailure.NotCachedYet
                    else PlaybackFailure.AllCandidatesFailed(queue.size),
                )
                return
            }
            queueIndex = -1
            if (passes >= MAX_PASSES) {
                val pauseMs = (PASS_PAUSE_MS shl (passes - MAX_PASSES)).coerceAtMost(MAX_PASS_PAUSE_MS)
                watchdog = scope.launch {
                    delay(pauseMs)
                    tryAdvance()
                }
                return
            }
            tryAdvance()
            return
        }

        queueIndex = nextIndex
        if (queueIndex in rejectedIndices) return tryAdvance()
        val candidate = queue[queueIndex]
        if (candidate.url == null) return tryAdvance()

        _state.update {
            it.copy(
                phase = PlaybackPhase.RESOLVING,
                attempt = queueIndex + 1,
                candidateCount = queue.size,
                error = null,
                activeStream = candidate,
            )
        }
        open(candidate)
        watchdog = scope.launch { watchAttempt() }
    }

    private fun open(stream: PlayableStream) {
        val url = stream.url ?: return
        val mpv = player ?: return
        attemptStartedAtMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "open #${queueIndex + 1}/${queue.size} at +${sincePlayMs()}ms addon=${stream.addon} " +
                "${stream.readiness} quality=${stream.quality} file=${stream.filename}",
        )
        onSourceOpened(stream)

        opening = true
        fileLoaded = false
        val startMs = failoverPositionMs ?: estimatedResumePositionMs()
        mpv.setPaused(failoverPaused ?: false)
        mpv.load(url, startMs?.let { it / 1000.0 }, perFileOptions(stream))
    }

    /** Request headers an addon attached to a link, in mpv's per-file option form. */
    private fun perFileOptions(stream: PlayableStream): Map<String, String> = buildMap {
        val headers = stream.headers
        if (headers.isEmpty()) return@buildMap
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { put("user-agent", it.value) }
        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { put("referrer", it.value) }
        val rest = headers.filterKeys { !it.equals("User-Agent", true) && !it.equals("Referer", true) }
        if (rest.isNotEmpty()) {
            put("http-header-fields", rest.entries.joinToString(",") { (k, v) -> "$k: $v" })
        }
    }

    private fun estimatedResumePositionMs(): Long? {
        if (resumeApplied) return null
        resumeExactMs?.let { return it }
        val percent = resumePercent ?: return null
        val runtimeMs = expectedRuntimeMs ?: return null
        return (runtimeMs * percent / 100f).toLong().coerceAtLeast(0L)
    }

    fun playManual(stream: PlayableStream) {
        watchdog?.cancel()
        ticker?.cancel()
        candidatesJob?.cancel()
        candidatesCollecting = false
        awaitingCandidate = false
        manualMode = true
        committedStream = null
        committedRetries = 0

        if (stream.url == null) {
            fail(PlaybackFailure.ManualNoLink)
            return
        }
        _state.update {
            it.copy(phase = PlaybackPhase.RESOLVING, attempt = 0, candidateCount = 0, error = null, activeStream = stream)
        }
        open(stream)
        watchdog = scope.launch { watchAttempt() }
    }

    /**
     * Gives up on an attempt that never gets going. "Progress" is the demuxer
     * cache growing: a link that delivers keeps the attempt alive however slow
     * it is; one that stops delivering for [ATTEMPT_STALL_MS], or never plays
     * within [ATTEMPT_HARD_CAP_MS], is abandoned for the next candidate.
     */
    private suspend fun watchAttempt() {
        val mpv = player ?: return
        var bestCache = 0.0
        var everDelivered = false
        var stalledMs = 0L
        var waitedMs = 0L
        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_POLL_MS)
            waitedMs += WATCHDOG_POLL_MS
            val cache = mpv.cacheTime.value
            if (cache > bestCache + 0.05) {
                bestCache = cache
                everDelivered = true
                stalledMs = 0L
            } else {
                stalledMs += WATCHDOG_POLL_MS
            }
            val dead = everDelivered && stalledMs >= ATTEMPT_STALL_MS
            if (dead || waitedMs >= ATTEMPT_HARD_CAP_MS) {
                logAttemptEnd(if (dead) "stalled" else "hard-cap")
                if (manualMode) fail(PlaybackFailure.ManualUnresponsive) else tryAdvance()
                return
            }
        }
    }

    // ----------------------------------------------------------------------
    // mpv events
    // ----------------------------------------------------------------------

    private fun onReady() {
        val mpv = player ?: return
        opening = false
        if (isDecoy()) return rejectDecoy()

        watchdog?.cancel()
        watchdog = null
        if (committedStream == null) {
            Log.i(TAG, "playing #${queueIndex + 1} after ${sincePlayMs()}ms")
        }
        committedStream = _state.value.activeStream
        committedRetries = 0

        val duration = durationMs()
        val percent = resumePercent
        val exactMs = resumeExactMs
        if (!resumeApplied && duration > 0 && (exactMs != null || percent != null)) {
            val exactFits = exactMs != null && exactMs < duration - RESUME_TOLERANCE_MS
            if (!exactFits && percent != null) {
                val corrected = (duration * percent / 100f).toLong().coerceIn(0L, duration)
                if (abs(corrected - positionMs()) > RESUME_TOLERANCE_MS) {
                    Log.i(TAG, "resume correction to ${corrected}ms (was ${positionMs()}ms)")
                    mpv.seekAbsolute(corrected / 1000.0)
                }
            }
            resumeApplied = true
        }

        _state.update { it.copy(phase = if (mpv.paused.value) PlaybackPhase.PAUSED else PlaybackPhase.PLAYING, error = null) }
        _position.update { it.copy(durationMs = duration.coerceAtLeast(0)) }
        failoverPositionMs = null
        failoverPaused = null
        refreshTracks()
        startTicker()
        startStallWatch()
    }

    private fun onEndFile(event: MpvPlayer.Event.EndFile) {
        if (event.reason != LibMpv.END_ERROR) return
        // A load that failed before playing, or a committed link that died.
        Log.w(TAG, "end-file error=${event.message} committed=${committedStream != null}")
        opening = false
        rememberPlaybackForFailover()
        if (committedStream != null) {
            reopenCommitted()
            return
        }
        if (manualMode) fail(PlaybackFailure.ManualFailed) else tryAdvance()
    }

    private fun reopenCommitted() {
        val stream = committedStream ?: return
        if (committedRetries >= COMMITTED_RETRY_LIMIT) {
            committedStream = null
            committedRetries = 0
            if (manualMode) fail(PlaybackFailure.ManualFailed) else tryAdvance()
            return
        }
        committedRetries++
        Log.w(TAG, "reopening committed source, retry $committedRetries")
        _state.update { it.copy(phase = PlaybackPhase.BUFFERING, error = null) }
        scope.launch {
            delay(REOPEN_BACKOFF_MS * committedRetries)
            open(stream)
        }
    }

    private fun onEnded() {
        if (isDecoy()) return rejectDecoy()
        watchdog?.cancel(); watchdog = null
        ticker?.cancel(); ticker = null
        stallWatch?.cancel(); stallWatch = null
        publishPosition()
        _state.update { it.copy(phase = PlaybackPhase.ENDED) }
    }

    private fun isDecoy(): Boolean {
        val expected = expectedRuntimeMs ?: return false
        val duration = durationMs()
        if (duration <= 0) return false
        return duration < expected * DECOY_MAX_FRACTION && duration < DECOY_ABSOLUTE_CAP_MS
    }

    private fun rejectDecoy() {
        watchdog?.cancel()
        watchdog = null
        opening = false
        if (manualMode) {
            fail(PlaybackFailure.ManualDecoy)
            return
        }
        logAttemptEnd("decoy duration=${durationMs()} expected=$expectedRuntimeMs")
        rejectedIndices += queueIndex
        tryAdvance()
    }

    private fun fail(failure: PlaybackFailure) {
        watchdog?.cancel()
        ticker?.cancel()
        stallWatch?.cancel()
        opening = false
        player?.stop()
        _state.update { it.copy(phase = PlaybackPhase.FAILED, error = failure, availableSources = queue.toList()) }
    }

    /**
     * Supervises a source that is already playing: a picture stuck waiting on
     * the network with the cache not growing for [STALL_REOPEN_MS] gets the
     * same link reopened at the current position — the equivalent of the TV
     * app's reconnect, which recovers far more streams than giving up does.
     */
    private fun startStallWatch() {
        stallWatch?.cancel()
        val mpv = player ?: return
        stallWatch = scope.launch {
            var waitingMs = 0L
            var lastCache = mpv.cacheTime.value
            while (isActive) {
                delay(STALL_POLL_MS)
                val waiting = mpv.pausedForCache.value && !mpv.paused.value
                val cache = mpv.cacheTime.value
                val growing = cache > lastCache + 0.1
                lastCache = cache
                waitingMs = if (waiting && !growing) waitingMs + STALL_POLL_MS else 0L
                if (waitingMs >= STALL_REOPEN_MS && committedStream != null) {
                    waitingMs = 0L
                    rememberPlaybackForFailover()
                    reopenCommitted()
                }
            }
        }
    }

    private fun refreshTracks() {
        val mpv = player ?: return
        val tracks = mpv.tracks()
        val audio = tracks.filter { it.type == "audio" }
        val subs = tracks.filter { it.type == "sub" && !it.external }
        audioIds = audio.map { it.mpvId }
        subtitleIds = subs.map { it.mpvId }
        _state.update {
            it.copy(
                audioTracks = audio.mapIndexed { i, t ->
                    TrackInfo(i, t.title, t.lang, audioMime(t.codec), t.channels, playable = true)
                },
                subtitleTracks = subs.mapIndexed { i, t ->
                    TrackInfo(i, t.title, t.lang, t.codec?.let { c -> "text/$c" }, null, playable = true)
                },
                currentAudioId = audio.indexOfFirst { t -> t.selected },
                currentSubtitleId = if (subtitleTrack != null) NO_TRACK else subs.indexOfFirst { t -> t.selected },
            )
        }
    }

    // ----------------------------------------------------------------------
    // Position
    // ----------------------------------------------------------------------

    private fun positionMs(): Long = ((player?.timePos?.value ?: 0.0) * 1000).toLong().coerceAtLeast(0)
    private fun durationMs(): Long = ((player?.duration?.value ?: 0.0) * 1000).toLong().coerceAtLeast(0)

    fun setOsdVisible(visible: Boolean) {
        if (osdVisible == visible) return
        osdVisible = visible
        if (visible) publishPosition()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(if (osdVisible) TICK_MS else IDLE_TICK_MS)
                publishPosition()
            }
        }
    }

    private fun publishPosition() {
        if (pendingSeekTargetMs == null) {
            _position.value = PlaybackPosition(positionMs = positionMs(), durationMs = durationMs())
        }
        _state.update { it.copy(phase = nextPollPhase(it.phase)) }
    }

    private fun nextPollPhase(current: PlaybackPhase): PlaybackPhase = when (current) {
        PlaybackPhase.RESOLVING, PlaybackPhase.FAILED, PlaybackPhase.ENDED, PlaybackPhase.IDLE,
        PlaybackPhase.SELECTING -> current
        else -> {
            val mpv = player
            when {
                mpv == null -> current
                mpv.pausedForCache.value && !mpv.paused.value -> PlaybackPhase.BUFFERING
                mpv.paused.value -> PlaybackPhase.PAUSED
                else -> PlaybackPhase.PLAYING
            }
        }
    }

    private fun sincePlayMs() = SystemClock.elapsedRealtime() - playStartedAtMs

    private fun logAttemptEnd(reason: String) {
        Log.i(TAG, "drop #${queueIndex + 1} reason=$reason after ${SystemClock.elapsedRealtime() - attemptStartedAtMs}ms")
    }

    // ----------------------------------------------------------------------
    // Controls
    // ----------------------------------------------------------------------

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun pause() {
        player?.setPaused(true)
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PAUSED) else it }
    }

    fun resume() {
        player?.setPaused(false)
        _state.update { if (it.canShowVideo) it.copy(phase = PlaybackPhase.PLAYING) else it }
    }

    fun nudge(forward: Boolean) {
        val duration = _position.value.durationMs
        if (duration <= 0) return
        val base = pendingSeekTargetMs ?: _position.value.positionMs
        val step = stepForBurst(nudgeCount)
        nudgeCount++
        val target = (base + if (forward) step else -step).coerceIn(0, duration)
        pendingSeekTargetMs = target
        _position.update { it.copy(positionMs = target) }
        pendingSeekJob?.cancel()
        pendingSeekJob = scope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            val committed = pendingSeekTargetMs ?: return@launch
            clearPendingSeek()
            seekTo(committed)
        }
    }

    private fun clearPendingSeek() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        pendingSeekTargetMs = null
        nudgeCount = 0
    }

    private fun stepForBurst(count: Int): Long = when {
        count < NUDGE_FINE_STEPS -> NUDGE_STEP_MS
        count < NUDGE_MEDIUM_STEPS -> NUDGE_STEP_MS * 3
        else -> NUDGE_STEP_MS * 6
    }

    fun seekTo(positionMs: Long) {
        val duration = _position.value.durationMs.takeIf { it > 0 } ?: durationMs()
        if (duration <= 0) return
        clearPendingSeek()
        val clamped = positionMs.coerceIn(0, duration)
        player?.seekAbsolute(clamped / 1000.0)
        _position.update { it.copy(positionMs = clamped) }
        updateActiveCueNow(clamped)
    }

    fun cycleScale() {
        val next = SCALE_CYCLE.getOrElse(SCALE_CYCLE.indexOf(_state.value.scaleType) + 1) { SCALE_CYCLE[0] }
        _state.update { it.copy(scaleType = next) }
    }

    /** Applies the "esticar" mode and caption styling to mpv's own output. */
    internal fun applyPresentation(
        scaleType: VideoScaleType,
        cropToFill: Boolean,
        subtitleColor: String,
        subtitleBackground: String,
    ) {
        val mpv = player ?: return
        when {
            cropToFill -> {
                mpv.setProperty("keepaspect", "yes"); mpv.setProperty("panscan", "1.0"); mpv.setProperty("video-zoom", "0")
            }
            scaleType == VideoScaleType.STRETCH -> {
                mpv.setProperty("keepaspect", "no"); mpv.setProperty("panscan", "0"); mpv.setProperty("video-zoom", "0")
            }
            scaleType == VideoScaleType.ZOOM -> {
                mpv.setProperty("keepaspect", "yes"); mpv.setProperty("panscan", "0")
                mpv.setProperty("video-zoom", "%.4f".format(Locale.ROOT, ln(FIXED_ZOOM_SCALE) / ln(2.0)))
            }
            else -> {
                mpv.setProperty("keepaspect", "yes"); mpv.setProperty("panscan", "0"); mpv.setProperty("video-zoom", "0")
            }
        }
        mpv.setProperty("sub-color", subtitleColor)
        mpv.setProperty("sub-back-color", subtitleBackground)
        mpv.setProperty("sub-border-style", if (subtitleBackground.endsWith("00")) "outline-and-shadow" else "background-box")
    }

    fun selectAudioTrack(id: Int) {
        val mpvId = audioIds.getOrNull(id) ?: return
        player?.setProperty("aid", mpvId.toString())
        _state.update { it.copy(currentAudioId = id, audioSilenced = false) }
    }

    fun selectSubtitleTrack(id: Int) {
        val mpvId = subtitleIds.getOrNull(id)
        if (mpvId == null) {
            player?.setProperty("sid", "no")
        } else {
            player?.setProperty("sid", mpvId.toString())
            selectExternalSubtitle(null, null)
        }
        _state.update { it.copy(currentSubtitleId = if (mpvId == null) NO_TRACK else id) }
    }

    fun selectExternalSubtitle(option: SubtitleOption?, track: SubtitleTrack?) {
        val resolvedTrack = track.takeIf { option != null }
        subtitleTrack = resolvedTrack
        if (resolvedTrack != null) selectSubtitleTrack(NO_TRACK)
        _state.update {
            it.copy(externalSubtitle = option.takeIf { resolvedTrack != null }, subtitleOffsetMs = 0L)
        }
        _activeSubtitleCue.value = null
        if (resolvedTrack == null) {
            subtitleTicker?.cancel()
            subtitleTicker = null
        } else {
            subtitleTicker?.cancel()
            subtitleTicker = scope.launch {
                while (isActive) {
                    updateActiveCueNow()
                    delay(SUBTITLE_TICK_MS)
                }
            }
        }
    }

    fun adjustSubtitleOffset(deltaMs: Long) {
        if (subtitleTrack == null) return
        setSubtitleOffset(_state.value.subtitleOffsetMs + deltaMs)
    }

    fun setSubtitleOffset(offsetMs: Long) {
        if (subtitleTrack == null) return
        val clamped = offsetMs.coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
        if (clamped == _state.value.subtitleOffsetMs) return
        _state.update { it.copy(subtitleOffsetMs = clamped) }
        updateActiveCueNow()
    }

    private fun updateActiveCueNow(atMs: Long = positionMs()) {
        val track = subtitleTrack ?: return
        _activeSubtitleCue.value = track.cueAt(atMs - _state.value.subtitleOffsetMs)?.text
    }

    /** Desktop-only: the window has its own volume, unlike a TV's remote. */
    fun adjustVolume(delta: Int) {
        volume = (volume + delta).coerceIn(0, 150)
        muted = false
        player?.setProperty("mute", "no")
        player?.setProperty("volume", volume.toString())
    }

    fun toggleMute() {
        muted = !muted
        player?.setProperty("mute", if (muted) "yes" else "no")
    }

    val volumePercent: Int get() = if (muted) 0 else volume

    private fun rememberPlaybackForFailover() {
        val position = maxOf(_position.value.positionMs, positionMs()).coerceAtLeast(0L)
        if (!_state.value.canShowVideo && position == 0L) return
        failoverPositionMs = position
        failoverPaused = player?.paused?.value
    }

    fun progressPercent(): Float = _position.value.progress * 100f

    fun hint(): PlaybackHint? {
        val position = _position.value.positionMs
        val duration = _position.value.durationMs
        if (position <= 0 || duration <= 0) return null
        return PlaybackHint(positionMs = position, durationMs = duration, sourceFilename = committedStream?.filename)
    }

    private fun stopInternal() {
        watchdog?.cancel(); watchdog = null
        ticker?.cancel(); ticker = null
        stallWatch?.cancel(); stallWatch = null
        candidatesJob?.cancel(); candidatesJob = null
        subtitleTicker?.cancel(); subtitleTicker = null
        candidatesCollecting = false
        awaitingCandidate = false
        committedStream = null
        committedRetries = 0
        failoverPositionMs = null
        failoverPaused = null
        opening = false
        clearPendingSeek()
        preferredWatch?.cancel(); preferredWatch = null
        awaitingPreferred = false
        preferredFilename = null
        resumeExactMs = null
        _position.value = PlaybackPosition()
        _activeSubtitleCue.value = null
        player?.stop()
    }

    fun stop() {
        stopInternal()
        subtitleTrack = null
        manualMode = false
        _state.value = PlaybackState()
    }

    fun release() {
        stopInternal()
        onSourceOpened(null)
        subtitleTrack = null
        player?.close()
    }

    private companion object {
        const val TAG = "Playback"
        const val ATTEMPT_STALL_MS = 9_000L
        const val ATTEMPT_HARD_CAP_MS = 45_000L
        const val WATCHDOG_POLL_MS = 1_500L
        const val DECOY_MAX_FRACTION = 0.5
        const val DECOY_ABSOLUTE_CAP_MS = 15 * 60_000L
        const val MAX_PASSES = 2
        const val PERSIST_MS = 60_000L
        const val PASS_PAUSE_MS = 2_000L
        const val MAX_PASS_PAUSE_MS = 15_000L
        const val TICK_MS = 500L
        const val IDLE_TICK_MS = 4_000L
        const val SUBTITLE_TICK_MS = 120L
        const val SEEK_COMMIT_DELAY_MS = 350L
        const val NUDGE_STEP_MS = 10_000L
        const val NUDGE_FINE_STEPS = 4
        const val NUDGE_MEDIUM_STEPS = 12
        const val STALL_POLL_MS = 1_000L
        const val STALL_REOPEN_MS = 20_000L
        const val RESUME_TOLERANCE_MS = 5_000L
        const val HINT_PERCENT_TOLERANCE = 2f
        const val PREFERRED_GRACE_MS = 3_000L
        const val COMMITTED_RETRY_LIMIT = 6
        const val REOPEN_BACKOFF_MS = 750L
        const val FIXED_ZOOM_SCALE = 1.34
    }
}

/** mpv codec names -> the sample MIME types the player's track labels are written against. */
private fun audioMime(codec: String?): String? = when (codec?.lowercase()) {
    null -> null
    "aac" -> "audio/mp4a-latm"
    "mp3", "mp2", "mp1" -> "audio/mpeg"
    "ac3" -> "audio/ac3"
    "eac3" -> "audio/eac3"
    "truehd", "mlp" -> "audio/true-hd"
    "dts" -> "audio/vnd.dts"
    "dts-hd", "dtshd" -> "audio/vnd.dts.hd"
    "opus" -> "audio/opus"
    "vorbis" -> "audio/vorbis"
    "flac" -> "audio/flac"
    "alac" -> "audio/alac"
    else -> if (codec.startsWith("pcm")) "audio/raw" else "audio/$codec"
}

@Suppress("unused")
private val ignored = StreamKind.DIRECT
