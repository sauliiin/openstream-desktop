package com.mdblisthub.desktop

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageBitmapResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mdblisthub.tv.BuildConfig
import com.mdblisthub.tv.LocalHostActivity
import com.mdblisthub.tv.R
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.work.ImageMemoryTrimmer
import com.mdblisthub.tv.core.data.work.ImageWarmer
import com.mdblisthub.tv.core.model.HubThemeVariant
import com.mdblisthub.tv.core.network.ApiConfig
import com.mdblisthub.tv.core.network.ApiCredentials
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubTheme
import com.mdblisthub.tv.navigation.HubNavHost
import com.mdblisthub.desktop.ui.UnfocusedKeys
import com.mdblisthub.desktop.ui.remoteControl
import com.mdblisthub.tv.torrent.TorrentStreaming
import com.mdblisthub.tv.ui.intro.IntroScreen
import com.mdblisthub.tv.update.AppUpdateManager
import com.mdblisthub.tv.update.AppUpdateOverlay
import java.util.Locale
import kotlin.system.exitProcess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path.Companion.toOkioPath

/**
 * OmniStream for the desktop.
 *
 * The same app as the Android TV build — same Kotlin sources for data,
 * screens and navigation — hosted in a native window instead of an Activity.
 * This file is the union of the TV app's `HubApplication` (the object graph,
 * API keys, image loader, theme) and `MainActivity` (the window, locale,
 * intro and update check).
 */
fun main() {
    System.setProperty("skiko.vsync.enabled", "true")
    LinuxIntegration.setWindowClass()
    SingleInstance.acquireOrSignal { DesktopWindow.bringToFront() } ?: run {
        // Another OmniStream is already open; it was asked to come forward.
        exitProcess(0)
    }

    LinuxIntegration.installDesktopEntry()
    // A logout or `kill` ends the process without closing the window: the
    // native engines still get stopped first.
    Runtime.getRuntime().addShutdownHook(Thread { shutdownNatives() })
    val graph = bootstrap()
    val updateManager = AppUpdateManager(Activity { DesktopWindow.setFullscreen(false) }, GITHUB_REPOSITORY)

    application(exitProcessOnExit = false) {
        val windowState = rememberWindowState(
            size = DpSize(1360.dp, 820.dp),
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        )
        Window(
            onCloseRequest = {
                updateManager.close()
                exitApplication()
            },
            state = windowState,
            title = "OmniStream",
            icon = remember { BitmapPainter(imageBitmapResource(R.drawable.ic_omnistream_infinity)) },
            onPreviewKeyEvent = ::handleWindowKey,
            onKeyEvent = UnfocusedKeys::handle,
        ) {
            LaunchedEffect(window) {
                DesktopWindow.window = window
                DesktopWindow.state = windowState
                window.minimumSize = java.awt.Dimension(800, 480)
            }
            AppContent(graph, updateManager)
        }
    }
    shutdownNatives()
    // Skips C++ static destructors racing threads that native libraries may
    // still own; everything worth persisting was written synchronously.
    Runtime.getRuntime().halt(0)
}

private val shutDown = java.util.concurrent.atomic.AtomicBoolean(false)

/** Stops libmpv and libtorrent before the JVM goes away. */
private fun shutdownNatives() {
    if (!shutDown.compareAndSet(false, true)) return
    runCatching { com.mdblisthub.desktop.mpv.MpvPlayer.shutdownAll() }
    runCatching { TorrentStreaming.shutdown() }
}

private const val GITHUB_REPOSITORY = "sauliiin/openstream-desktop"
private const val STARTUP_SETTLE_MS = 1_500L
private const val IMAGE_WARM_CONCURRENCY = 4

/** The TV layout's reference canvas: everything was designed for 960×540 dp. */
private const val TV_WIDTH_DP = 960f
private const val TV_HEIGHT_DP = 540f

internal fun bootstrap(): DataGraph {
    val context = Context.app
    TorrentStreaming.install(context)
    val graph = DataGraph(context)
    ApiConfig.configure(
        ApiCredentials(
            tmdb = BuildConfig.TMDB_API_KEY,
            omdb = BuildConfig.OMDB_API_KEY,
            fanartTv = BuildConfig.FANART_TV_API_KEY,
            traktClientId = BuildConfig.TRAKT_CLIENT_ID,
            traktClientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
            simklClientId = BuildConfig.SIMKL_CLIENT_ID,
            openSubtitles = BuildConfig.OPENSUBTITLES_API_KEY,
            wyzie = BuildConfig.WYZIE_API_KEY,
            appVersion = BuildConfig.VERSION_NAME,
        ),
    )

    HubColors.apply(runCatching { graph.uiPreferences.startupTheme() }.getOrDefault(HubThemeVariant.NORMAL))
    val startupLanguage = graph.uiPreferences.startupLanguage()
    Locale.setDefault(Locale.forLanguageTag(startupLanguage))
    ApiConfig.LANGUAGE = ApiConfig.metadataLanguageFor(startupLanguage)

    // Same loader configuration as the TV app: the image OkHttp client (one
    // connection pool for artwork and metadata), a quarter of the heap in
    // memory, half a gigabyte on disk.
    SingletonImageLoader.setSafe { platform ->
        ImageLoader.Builder(platform)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.network.imageClient })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(platform, 0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(AppDirs.cache.resolve("artwork").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    val warmPermits = Semaphore(IMAGE_WARM_CONCURRENCY)
    graph.imageWarmer = ImageWarmer { urls ->
        val platform = PlatformContext.INSTANCE
        val loader = SingletonImageLoader.get(platform)
        coroutineScope {
            urls.map { url ->
                async {
                    warmPermits.withPermit {
                        loader.execute(ImageRequest.Builder(platform).data(url).size(1280, 720).build())
                    }
                }
            }.awaitAll()
        }
    }
    graph.imageMemoryTrimmer = CoilMemoryTrimmer

    graph.scope.launch {
        var previous: String? = null
        graph.uiPreferences.language.collect { tag ->
            ApiConfig.LANGUAGE = ApiConfig.metadataLanguageFor(tag)
            if (previous != null && previous != tag) runCatching { graph.media.forgetLocalizedMetadata() }
            previous = tag
        }
    }
    graph.scope.launch {
        graph.uiPreferences.theme.collect { variant -> HubColors.apply(variant) }
    }
    return graph
}

@Composable
internal fun AppContent(graph: DataGraph, updateManager: AppUpdateManager) {
    val language by graph.uiPreferences.language
        .collectAsStateWithLifecycle(initialValue = graph.uiPreferences.startupLanguage())
    var introVisible by remember { mutableStateOf(graph.uiPreferences.startupIntroEnabled()) }

    LaunchedEffect(introVisible) {
        if (introVisible) {
            graph.warmHomeArtworkDuringIntro()
        } else {
            delay(STARTUP_SETTLE_MS)
            updateManager.checkForUpdate()
        }
    }
    LaunchedEffect(language) { Locale.setDefault(Locale.forLanguageTag(language)) }

    val locale = remember(language) { Locale.forLanguageTag(language) }
    val localeContext = remember(locale) { Context.app.createConfigurationContext(Configuration(locale)) }
    val host = remember { Activity { DesktopWindow.setFullscreen(false) } }

    CompositionLocalProvider(
        LocalContext provides localeContext,
        LocalConfiguration provides Configuration(locale),
        LocalResources provides localeContext.resources,
        LocalHostActivity provides host,
    ) {
        TvCanvas {
            HubTheme {
                Box(Modifier.fillMaxSize()) {
                    HubNavHost(graph = graph)
                    AppUpdateOverlay(manager = updateManager)
                    if (introVisible) IntroScreen(onFinished = { introVisible = false })
                    if (!introVisible) MpvMissingNotice()
                }
            }
        }
    }
}

/**
 * Scales the TV layout to the window: the screens were authored for a
 * 960×540 dp canvas, so the density is chosen to make the window exactly that
 * many dp. Any window size then looks like the TV app on a TV — just bigger or
 * smaller — instead of a 10-foot layout stranded in a corner of a monitor.
 */
@Composable
private fun TvCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .remoteControl(onUnhandledBack = {
                if (DesktopWindow.isFullscreen) { DesktopWindow.setFullscreen(false); true } else false
            }),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val scale = minOf(widthPx / TV_WIDTH_DP, heightPx / TV_HEIGHT_DP).coerceAtLeast(0.5f)
        CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) {
            content()
        }
    }
}

/**
 * Window keys, whatever has focus: fullscreen (F11, Alt+Enter) and quitting
 * (Ctrl+Q). Back and the arrows are the content's — see `remoteControl`.
 */
private fun handleWindowKey(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.F11 || (event.key == Key.Enter && event.isAltPressed) -> {
            DesktopWindow.toggleFullscreen(); true
        }
        event.key == Key.Q && event.isCtrlPressed -> exitProcess(0)
        else -> false
    }
}

@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private object CoilMemoryTrimmer : ImageMemoryTrimmer {
    override fun trimForPlayback() {
        val cache = SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache ?: return
        cache.maxSize = (cache.initialMaxSize / 8).coerceAtLeast(8L * 1024 * 1024)
    }

    override fun restore() {
        val cache = SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache ?: return
        cache.maxSize = cache.initialMaxSize
    }
}

/** Says once per launch, in the app's language, why nothing will play. */
@Composable
private fun MpvMissingNotice() {
    var dismissed by remember { mutableStateOf(false) }
    val missing = remember { com.mdblisthub.desktop.mpv.LibMpv.instance == null }
    if (!missing || dismissed) return
    com.mdblisthub.desktop.ui.Dialog(onDismissRequest = { dismissed = true }) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .background(HubColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
        ) {
            androidx.tv.material3.Text(
                text = androidx.compose.ui.res.stringResource(R.string.desktop_mpv_missing),
                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                color = HubColors.Text,
            )
            com.mdblisthub.tv.ui.component.HubButton(
                text = androidx.compose.ui.res.stringResource(R.string.detail_close),
                primary = true,
                onClick = { dismissed = true },
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}
