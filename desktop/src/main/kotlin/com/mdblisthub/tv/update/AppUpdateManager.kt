package com.mdblisthub.tv.update

import android.app.Activity
import android.util.Log
import com.mdblisthub.desktop.AppDirs
import com.mdblisthub.desktop.DesktopShell
import com.mdblisthub.tv.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

data class ReleaseInfo(
    val tag: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
    val notes: String? = null,
)

sealed interface UpdateUiState {
    data object Hidden : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(
        val release: ReleaseInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateUiState
    /** Desktop: the new build is in place and only a restart is missing. */
    data class AwaitingInstallPermission(val release: ReleaseInfo) : UpdateUiState
    data class Failed(val release: ReleaseInfo?, val detail: String?) : UpdateUiState
}

/**
 * Checks the desktop repository's latest stable GitHub release and installs it.
 *
 * * Running as an AppImage (`$APPIMAGE` is set): the new `.AppImage` is
 *   downloaded next to the running one, verified, and swapped in atomically;
 *   a restart finishes the update.
 * * On Windows: the installer is downloaded and launched, and the app exits so
 *   it can replace the files.
 * * Anywhere else (a .deb, a dev run): the release page opens in the browser.
 */
class AppUpdateManager(
    @Suppress("unused") private val activity: Activity,
    private val repository: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val updateDirectory = File(AppDirs.cache, "updates")

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    private var installed: File? = null

    private val appImage: File? = System.getenv("APPIMAGE")?.let(::File)?.takeIf { it.isFile }

    fun checkForUpdate() {
        if (System.getenv("OMNISTREAM_NO_UPDATE_CHECK") != null) return
        scope.launch {
            val release = runCatching { withContext(Dispatchers.IO) { fetchLatestRelease() } }
                .onFailure { Log.i(TAG, "update check skipped: ${it.message}") }
                .getOrNull() ?: return@launch
            val newer = compareVersions(release.tag, BuildConfig.VERSION_NAME) ?: return@launch
            if (newer > 0) _state.value = UpdateUiState.Available(release)
        }
    }

    fun downloadAndInstall(release: ReleaseInfo) {
        val asset = assetForThisPlatform(release)
        if (asset == null) {
            DesktopShell.open(release.pageUrl)
            _state.value = UpdateUiState.Hidden
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.value = UpdateUiState.Downloading(release, 0L, asset.size)
            runCatching {
                val file = withContext(Dispatchers.IO) { download(release, asset) }
                install(file, release)
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.value = UpdateUiState.Failed(release, error.message)
            }
        }
    }

    private fun assetForThisPlatform(release: ReleaseInfo): ReleaseAsset? = when {
        appImage != null -> release.assets.firstOrNull { it.name.endsWith(".AppImage", ignoreCase = true) }
        AppDirs.isWindows -> release.assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
        else -> null
    }

    private fun install(file: File, release: ReleaseInfo) {
        when {
            appImage != null -> {
                file.setExecutable(true, false)
                val target = appImage
                val backup = File(target.path + ".old")
                backup.delete()
                require(target.renameTo(backup)) { "Could not move ${target.name} aside" }
                if (!file.renameTo(target)) {
                    backup.renameTo(target)
                    error("Could not replace ${target.name}")
                }
                backup.delete()
                installed = target
                _state.value = UpdateUiState.AwaitingInstallPermission(release)
            }
            AppDirs.isWindows -> {
                ProcessBuilder(file.absolutePath).start()
                exitProcess(0)
            }
        }
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateUiState.Hidden
    }

    fun retry() {
        val failure = _state.value as? UpdateUiState.Failed
        val release = failure?.release
        if (release == null) checkForUpdate() else downloadAndInstall(release)
    }

    /** Desktop meaning: restart into the freshly installed build. */
    fun openInstallPermissionSettings() {
        val target = installed ?: appImage ?: return
        runCatching {
            ProcessBuilder(target.absolutePath).inheritIO().start()
            exitProcess(0)
        }.onFailure { _state.value = UpdateUiState.Failed(null, it.message) }
    }

    fun onHostResumed() = Unit

    fun close() {
        scope.cancel()
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val endpoint = "https://api.github.com/repos/$repository/releases/latest"
        val connection = openConnection(endpoint).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "OmniStream-Desktop-Updater")
        }
        return try {
            require(connection.responseCode in 200..299) { "GitHub returned HTTP ${connection.responseCode}" }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString<GitHubReleaseResponse>(payload)
            ReleaseInfo(
                tag = response.tagName,
                pageUrl = response.htmlUrl,
                notes = formatReleaseNotes(response.body),
                assets = response.assets.map { asset ->
                    ReleaseAsset(
                        name = asset.name,
                        downloadUrl = asset.downloadUrl,
                        size = asset.size,
                        sha256 = asset.digest?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':'),
                    )
                },
            )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun download(release: ReleaseInfo, asset: ReleaseAsset): File {
        require(asset.downloadUrl.startsWith("https://")) { "Refusing a non-HTTPS download" }
        // Next to the AppImage when replacing it, so the final rename never
        // crosses filesystems.
        val dir = appImage?.parentFile ?: updateDirectory
        dir.mkdirs()
        val partial = File(dir, ".${asset.name}.part")
        val connection = openConnection(asset.downloadUrl).apply {
            setRequestProperty("User-Agent", "OmniStream-Desktop-Updater")
        }
        try {
            require(connection.responseCode in 200..299) { "Download returned HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: asset.size
            var downloaded = 0L
            var lastPublished = 0L
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastPublished >= PROGRESS_STEP_BYTES || downloaded == total) {
                            lastPublished = downloaded
                            _state.value = UpdateUiState.Downloading(release, downloaded, total)
                        }
                    }
                }
            }
            require(downloaded > 0L) { "Downloaded file is empty" }
            if (asset.size > 0) require(downloaded == asset.size) { "Download incomplete" }
            asset.sha256?.let { expected ->
                require(sha256(partial).equals(expected, ignoreCase = true)) { "Download failed SHA-256 verification" }
            }
            return partial
        } catch (t: Throwable) {
            partial.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
        }

    private companion object {
        const val TAG = "Update"
        const val NETWORK_TIMEOUT_MS = 20_000
        const val PROGRESS_STEP_BYTES = 256L * 1024L
    }
}

internal fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
    val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    supportedAbis.forEach { abi ->
        apkAssets.firstOrNull { it.name.contains("-$abi-", ignoreCase = true) }?.let { return it }
    }
    return apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
        ?: apkAssets.firstOrNull()
}

/** Returns positive when [remote] is newer, zero when equal, and negative when local is newer. */
internal fun compareVersions(remote: String, local: String): Int? {
    val remoteParts = versionParts(remote) ?: return null
    val localParts = versionParts(local) ?: return null
    val size = maxOf(remoteParts.size, localParts.size)
    repeat(size) { index ->
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val localPart = localParts.getOrElse(index) { 0 }
        if (remotePart != localPart) return remotePart.compareTo(localPart)
    }
    return 0
}

/** Turns the common GitHub Release Markdown into readable Compose text. */
internal fun formatReleaseNotes(markdown: String?): String? = markdown
    ?.replace(Regex("(?s)<!--.*?-->"), "")
    ?.replace(Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]*"""), "")
    ?.replace(Regex("""!\[([^]]*)]\([^)]+\)"""), "$1")
    ?.replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
    ?.replace(Regex("""(?m)^[ \t]*[-*+][ \t]+"""), "• ")
    ?.replace("**", "")
    ?.replace("__", "")
    ?.replace("`", "")
    ?.replace(Regex("\n{3,}"), "\n\n")
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun versionParts(version: String): List<Int>? {
    val parts = Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
    return parts.takeIf { it.isNotEmpty() }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val assets: List<GitHubAssetResponse> = emptyList(),
)

@Serializable
private data class GitHubAssetResponse(
    val name: String,
    val size: Long = 0L,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)
