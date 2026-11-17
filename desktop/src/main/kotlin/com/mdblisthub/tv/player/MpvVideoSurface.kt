package com.mdblisthub.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mdblisthub.desktop.mpv.MpvVideo

/**
 * The video output — the desktop counterpart of the TV app's `ExoVideoSurface`.
 *
 * mpv renders the picture, the resize mode and the container's own subtitles
 * (styled with the app's caption colour and background) into each frame; this
 * just draws the frames. The app's external subtitles are drawn over it by
 * `PlayerScreen`, exactly as on TV.
 */
@Composable
fun MpvVideoSurface(
    controller: PlaybackController,
    scaleType: VideoScaleType,
    modifier: Modifier = Modifier,
    subtitleColor: Color = Color.Yellow,
    subtitleBackgroundOpacity: Float = 0f,
    cropToFill: Boolean = false,
) {
    val colour = subtitleColor.toMpv()
    val background = Color.Black.copy(alpha = subtitleBackgroundOpacity.coerceIn(0f, 1f)).toMpv()
    LaunchedEffect(controller, scaleType, cropToFill, colour, background) {
        controller.applyPresentation(scaleType, cropToFill, colour, background)
    }
    Box(modifier.background(Color.Black)) {
        val renderer = controller.renderer ?: return@Box
        MpvVideo(renderer, Modifier.matchParentSize())
    }
}

/** `#AARRGGBB`, the form mpv's colour options take. */
private fun Color.toMpv(): String = "#%08X".format(toArgb())
