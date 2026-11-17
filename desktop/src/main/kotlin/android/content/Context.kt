package android.content

import android.content.res.Configuration
import android.content.res.Resources
import com.mdblisthub.desktop.AppDirs
import com.mdblisthub.desktop.DesktopShell
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The one "Context" a desktop app has: directories, preferences, strings and
 * a way to open links. Everything the shared code used `Context` for maps onto
 * one of those.
 */
open class Context(private val localeOverride: Locale? = null) {

    open val applicationContext: Context get() = this

    val filesDir: File get() = AppDirs.files
    val cacheDir: File get() = AppDirs.cache
    val dataDir: File get() = AppDirs.data
    fun getDatabasePath(name: String): File = File(AppDirs.databases, name)
    fun getDir(name: String, mode: Int): File = File(AppDirs.data, "app_$name").also { it.mkdirs() }

    val resources: Resources = Resources { localeOverride ?: Locale.getDefault() }

    fun getString(id: Int): String = resources.getString(id)
    fun getString(id: Int, vararg args: Any?): String = resources.getString(id, *args)

    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefs.getOrPut(name) { FileSharedPreferences(File(AppDirs.preferences, "$name.properties")) }

    fun createConfigurationContext(configuration: Configuration): Context = Context(configuration.locale)

    /** Only ACTION_VIEW of a URL exists on desktop: it opens the system browser. */
    fun startActivity(intent: Intent) {
        val data = intent.data?.toString() ?: throw ActivityNotFoundException("No data")
        if (!DesktopShell.open(data)) throw ActivityNotFoundException("Cannot open $data")
    }

    companion object {
        const val MODE_PRIVATE = 0
        private val prefs = ConcurrentHashMap<String, SharedPreferences>()

        /** The application context. */
        val app: Context = Context()
    }
}

class ActivityNotFoundException(message: String? = null) : RuntimeException(message)

class Intent(val action: String? = null, val data: android.net.Uri? = null) {
    var flags: Int = 0
        private set
    fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }
    fun setPackage(@Suppress("UNUSED_PARAMETER") pkg: String?): Intent = this

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}
