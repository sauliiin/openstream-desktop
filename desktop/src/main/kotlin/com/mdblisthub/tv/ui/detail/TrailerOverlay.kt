package com.mdblisthub.tv.ui.detail

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mdblisthub.tv.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.repository.TrailerRepository
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.HubButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The trailer, played over the detail screen instead of handing the viewer to
 * the YouTube app.
 *
 * Three tiers, in order, mirroring the fallback the user already relies on in
 * their Kodi setup:
 *
 * 1. IMDb's own trailer file — a plain MP4, resolved by [TrailerRepository]
 *    and played by Media3 exactly like anything else this app plays. No embed
 *    policy applies to it because it was never an embed.
 * 2. YouTube's IFrame embed, run in a `WebView`, for when IMDb doesn't have a
 *    trailer for this title. Not every video plays this way — an uploader can
 *    disable embedding, and YouTube separately refuses some embedded playback
 *    on television user agents — so this is a fallback, not the primary path.
 * 3. The YouTube app (or a browser) itself, one press away via
 *    [onOpenExternally], for whenever neither of the above panned out.
 */
@Composable
fun TrailerOverlay(
    imdbId: String?,
    youtubeKey: String?,
    title: String,
    trailers: TrailerRepository,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
) {
    var stage by remember { mutableStateOf<TrailerStage>(TrailerStage.Resolving) }

    LaunchedEffect(imdbId, youtubeKey) {
        val mp4 = imdbId?.let { id -> runCatching { trailers.mp4For(id) }.getOrNull() }
        stage = when {
            mp4 != null -> TrailerStage.Mp4(mp4)
            youtubeKey != null -> TrailerStage.YoutubeEmbed
            else -> TrailerStage.Failed
        }
    }

    // Focus has to leave the "Trailer" button behind the scrim, or the D-pad
    // is talking to a control nobody can see — the same trap the subtitle and
    // cast overlays fell into. Re-requested on every stage change, not just
    // once at the start: the `WebView` and `PlayerView` below are native
    // Android views, and a `WebView` in particular grabs the real Android
    // view focus for itself the moment it's attached — Compose's own focus
    // state still says "Fechar", but the D-pad is no longer reaching it. When
    // that view is torn down on a failed tier, nothing was pulling focus back
    // until this ran again.
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(stage) { closeFocus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(HubColors.Background.copy(alpha = 0.92f)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.82f)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = HubColors.Text,
                textAlign = TextAlign.Center,
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AndroidBlack)
                    .border(1.dp, HubColors.Border, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                when (val current = stage) {
                    is TrailerStage.Resolving -> HubSpinner(size = 40.dp)

                    is TrailerStage.Mp4 -> {
                        var mp4Loading by remember { mutableStateOf(true) }
                        Mp4Trailer(
                            url = current.url,
                            onReady = { mp4Loading = false },
                            onError = {
                                // IMDb handed back a URL that didn't actually
                                // play — a signed link that expired between
                                // lookup and playback, most likely, or one
                                // that just stalled without ever buffering.
                                // Same fallback as no URL at all.
                                stage = if (youtubeKey != null) TrailerStage.YoutubeEmbed else TrailerStage.Failed
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (mp4Loading) HubSpinner(size = 40.dp)
                    }

                    is TrailerStage.YoutubeEmbed -> {
                        var embedLoading by remember { mutableStateOf(true) }
                        YoutubeEmbed(
                            youtubeKey = youtubeKey!!,
                            onReady = { embedLoading = false },
                            onError = { stage = TrailerStage.Failed },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (embedLoading) HubSpinner(size = 40.dp)
                    }

                    is TrailerStage.Failed -> Text(
                        text = stringResource(R.string.trailer_failed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = HubColors.TextDim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }

            Spacer(Modifier.height(0.dp))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HubButton(
                    text = stringResource(R.string.detail_close),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(closeFocus),
                )
                if (youtubeKey != null) {
                    HubButton(text = stringResource(R.string.trailer_open_youtube), onClick = onOpenExternally)
                }
            }
        }
    }
}

private sealed interface TrailerStage {
    data object Resolving : TrailerStage
    data class Mp4(val url: String) : TrailerStage
    data object YoutubeEmbed : TrailerStage
    data object Failed : TrailerStage
}

private val AndroidBlack = androidx.compose.ui.graphics.Color.Black

/**
 * Tier 1: IMDb's own file. Tier 2 reuses this too: on desktop the YouTube
 * trailer is played by mpv through yt-dlp (when installed) instead of an
 * IFrame embed — no embed policy, no web view.
 */
@Composable
private fun Mp4Trailer(
    url: String,
    onReady: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clip = com.mdblisthub.desktop.mpv.rememberMpvClip(url)
    val ready = androidx.compose.runtime.rememberUpdatedState(onReady)
    val error = androidx.compose.runtime.rememberUpdatedState(onError)

    LaunchedEffect(clip) {
        if (clip == null) {
            error.value()
            return@LaunchedEffect
        }
        clip.load(url)
        launch { clip.playing.first { it }; ready.value() }
        launch { clip.finished.first { it }; if (!clip.playing.value) error.value() }
        // A stalled connection can sit buffering forever. Past this,
        // black-with-a-spinner has stopped being "still loading".
        delay(15_000)
        if (!clip.playing.value) error.value()
    }

    if (clip != null) com.mdblisthub.desktop.mpv.MpvVideo(clip.renderer, modifier)
}

@Composable
private fun YoutubeEmbed(
    youtubeKey: String,
    onReady: () -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) = Mp4Trailer(
    url = "https://www.youtube.com/watch?v=" + android.net.Uri.encode(youtubeKey),
    onReady = onReady,
    onError = onError,
    modifier = modifier,
)
