package com.mdblisthub.tv.ui.player

import android.content.Context
import com.mdblisthub.desktop.DesktopNotifications
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.model.MediaType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Posts one desktop notification when one concrete playback reaches its end —
 * the TV app's completion notice, through the OS notification centre.
 */
class PlaybackCompletionNotifier(
    context: Context,
    private val type: MediaType,
    @Suppress("unused") private val tmdbId: Int,
    @Suppress("unused") private val season: Int?,
    @Suppress("unused") private val episode: Int?,
) {
    private val appContext = context.applicationContext
    private val delivered = AtomicBoolean(false)

    @Volatile private var title: String = ""
    @Volatile private var episodeLabel: String? = null

    fun update(title: String, episodeLabel: String?) {
        if (title.isNotBlank()) this.title = title
        if (!episodeLabel.isNullOrBlank()) this.episodeLabel = episodeLabel
    }

    fun show(languageTag: String) {
        if (!delivered.compareAndSet(false, true)) return
        val localized = appContext.createConfigurationContext(
            android.content.res.Configuration(Locale.forLanguageTag(languageTag)),
        )
        val contentTitle = localized.getString(
            if (type == MediaType.SHOW) R.string.playback_notification_episode_title
            else R.string.playback_notification_movie_title,
        )
        val mediaTitle = title.ifBlank { localized.getString(R.string.app_name) }
        val contentText = if (type == MediaType.SHOW && !episodeLabel.isNullOrBlank()) {
            localized.getString(R.string.playback_notification_episode_content, mediaTitle, episodeLabel)
        } else mediaTitle
        DesktopNotifications.show(contentTitle, contentText)
    }

    companion object {
        /** Android needs a channel created up front; a desktop does not. */
        fun createChannel(@Suppress("UNUSED_PARAMETER") context: Context) = Unit
    }
}
