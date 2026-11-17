package com.mdblisthub.tv.ui.intro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import com.mdblisthub.desktop.mpv.MpvClip
import com.mdblisthub.desktop.mpv.MpvVideo
import com.mdblisthub.desktop.mpv.rememberMpvClip
import com.mdblisthub.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Full-screen, one-shot opening video shown over the app while it starts. */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val clip = rememberMpvClip(Unit)
    val finish = rememberUpdatedState(onFinished)

    BackHandler(onBack = onFinished)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(clip) {
        val file = MpvClip.rawResourceFile(R.raw.intro_4k, "intro_4k.mp4")
        if (clip == null || file == null) {
            finish.value()
            return@LaunchedEffect
        }
        clip.load(file.absolutePath)
        clip.finished.first { it }
        finish.value()
    }

    // A local file should start almost immediately. Do not leave the viewer
    // on black forever if this machine cannot decode this particular MP4.
    LaunchedEffect(clip) {
        delay(READY_TIMEOUT_MS)
        if (clip?.playing?.value != true) finish.value()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            // Any key or click skips, as Back does on the remote.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key != Key.Escape) finish.value()
                true
            }
            .pointerInput(Unit) { detectTapGestures { finish.value() } }
            .focusable(),
    ) {
        if (clip != null) MpvVideo(clip.renderer, Modifier.fillMaxSize())
    }
}

private const val READY_TIMEOUT_MS = 5_000L
