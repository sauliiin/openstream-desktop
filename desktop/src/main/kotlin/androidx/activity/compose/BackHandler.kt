package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Android's back dispatcher, for a window: Esc, Backspace (outside text
 * fields) and the mouse's back button all arrive here via
 * `com.mdblisthub.desktop.DesktopBack`.
 *
 * Same contract as `OnBackPressedDispatcher`: the most recently registered
 * enabled callback wins, and when none takes it the navigation back stack pops.
 */
object BackDispatcher {
    internal class Entry(
        var enabled: Boolean,
        var lifecycle: Lifecycle?,
        var onBack: () -> Unit,
    ) {
        /**
         * Android only delivers back to callbacks whose lifecycle is at least
         * STARTED — a screen left behind on the navigation back stack keeps
         * its handler registered but inactive. Same rule here.
         */
        val active: Boolean
            get() = enabled && (lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
    }

    private val entries = mutableListOf<Entry>()

    /** Called when no screen-level handler took the press (pop the NavHost). */
    var fallback: () -> Boolean = { false }

    internal fun add(entry: Entry) = synchronized(entries) { entries += entry }
    internal fun remove(entry: Entry) = synchronized(entries) { entries -= entry }

    fun hasEnabledHandler(): Boolean = synchronized(entries) { entries.any { it.active } }

    /** True when something consumed the press. */
    fun dispatch(): Boolean {
        val top = synchronized(entries) { entries.lastOrNull { it.active } }
        if (top != null) {
            top.onBack()
            return true
        }
        return fallback()
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val currentOnBack = rememberUpdatedState(onBack)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val entry = remember { BackDispatcher.Entry(enabled, lifecycle) { currentOnBack.value() } }
    SideEffect {
        entry.enabled = enabled
        entry.lifecycle = lifecycle
    }
    DisposableEffect(entry) {
        BackDispatcher.add(entry)
        onDispose { BackDispatcher.remove(entry) }
    }
}

/** Android's startup-metrics hook. A desktop window has nothing to report it to. */
@Composable
fun ReportDrawnWhen(predicate: () -> Boolean) {
    @Suppress("UNUSED_VARIABLE") val unused = predicate
}
