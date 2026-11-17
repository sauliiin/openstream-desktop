package com.mdblisthub.tv.core.data.work

import com.mdblisthub.tv.core.data.DataGraph
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When each metadata worker runs — the desktop counterpart of the TV app's
 * WorkManager schedule.
 *
 * Same shape: the lists refresh on the timescale mdblist rebuilds them, the
 * expensive hydration runs unattended in the background, and neither is ever
 * something the user waits on. A desktop app has no OS job scheduler that
 * outlives it, so the periodic work lives on the graph's scope for as long as
 * the window is open — which is exactly when its results can be seen.
 */
class MetadataScheduler(private val graph: DataGraph) {

    private val unique = ConcurrentHashMap<String, Job>()
    private var periodic: List<Job> = emptyList()

    /** A fresh sign-in has nothing cached, so it starts everything at once. */
    fun onSignedIn() {
        syncNow(force = true)
        schedulePeriodic()
    }

    fun onSignedOut() {
        unique.values.forEach { it.cancel() }
        unique.clear()
        periodic.forEach { it.cancel() }
        periodic = emptyList()
    }

    /**
     * The catch-up run on app open. Artwork warming is chained behind the list
     * sync rather than run beside it — there is no point pre-fetching posters
     * for rows that are about to be replaced.
     */
    fun syncNow(force: Boolean = false) {
        replace(ListSyncWorker.NAME) {
            if (runWithRetry { ListSyncWorker(graph.lists, force) }) {
                runWithRetry(maxAttempts = 1) {
                    ArtworkWorker(graph.database, graph.addons, graph.imageWarmer)
                }
            }
        }
        replace(ResumeSyncWorker.NAME) {
            runWithRetry { ResumeSyncWorker(graph.playback) }
        }
        if (periodic.isEmpty()) schedulePeriodic()
    }

    /** Nudges the hydration queue — after a sync brought in new titles. */
    fun hydrateSoon() {
        keep("${MetadataWorker.NAME}-now") {
            delay(IDLE_GRACE_MS)
            runWithRetry(maxAttempts = 1) { MetadataWorker(graph.media, graph.database) }
        }
    }

    private fun schedulePeriodic() {
        if (periodic.isNotEmpty()) return
        periodic = listOf(
            every(6 * HOUR) { ListSyncWorker(graph.lists) },
            every(4 * HOUR, initialDelay = IDLE_GRACE_MS) { MetadataWorker(graph.media, graph.database) },
            every(3 * HOUR) { ResumeSyncWorker(graph.playback) },
            every(24 * HOUR, initialDelay = 10 * MINUTE) { CachePruneWorker(graph.database) },
        )
    }

    private fun every(periodMs: Long, initialDelay: Long = periodMs, worker: () -> DesktopWorker): Job =
        graph.scope.launch {
            delay(initialDelay)
            while (isActive) {
                runWithRetry(worker = worker)
                delay(periodMs)
            }
        }

    private fun replace(name: String, block: suspend () -> Unit) {
        unique.remove(name)?.cancel()
        unique[name] = graph.scope.launch { block() }
    }

    private fun keep(name: String, block: suspend () -> Unit) {
        if (unique[name]?.isActive == true) return
        unique[name] = graph.scope.launch { block() }
    }

    /** Runs a worker with WorkManager-style exponential backoff. True on success. */
    private suspend fun runWithRetry(
        maxAttempts: Int = MAX_ATTEMPTS,
        worker: () -> DesktopWorker,
    ): Boolean {
        var backoff = FIRST_BACKOFF_MS
        repeat(maxAttempts) { attempt ->
            val instance = worker().also { it.runAttemptCount = attempt }
            val result = runCatching { instance.doWork() }.getOrDefault(DesktopWorker.Result.RETRY)
            when (result) {
                DesktopWorker.Result.SUCCESS -> return true
                DesktopWorker.Result.FAILURE -> return false
                DesktopWorker.Result.RETRY -> {
                    delay(backoff)
                    backoff *= 2
                }
            }
        }
        return false
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val FIRST_BACKOFF_MS = 30_000L
        const val MAX_ATTEMPTS = 4
        const val IDLE_GRACE_MS = 2 * MINUTE
    }
}
