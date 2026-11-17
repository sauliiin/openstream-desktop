package com.mdblisthub.desktop.mpv

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Draws [renderer]'s frames, filling the composable.
 *
 * mpv already did the letterboxing, stretching or cropping at the size this
 * reports (see [MpvRenderer.setTargetSize]), so the frame is simply drawn
 * edge to edge; the GPU only scales when the frame was capped below the
 * on-screen size.
 */
@Composable
fun MpvVideo(renderer: MpvRenderer, modifier: Modifier = Modifier) {
    Canvas(
        modifier.onSizeChanged { renderer.setTargetSize(it.width, it.height) },
    ) {
        val image = renderer.frame.value ?: return@Canvas
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
