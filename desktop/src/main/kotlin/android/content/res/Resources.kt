package android.content.res

import com.mdblisthub.desktop.res.AndroidResources
import java.util.Locale

class Configuration(val locale: Locale = Locale.getDefault()) {
    val locales: List<Locale> get() = listOf(locale)
    fun setLocale(newLocale: Locale): Configuration = Configuration(newLocale)
    override fun equals(other: Any?) = other is Configuration && other.locale == locale
    override fun hashCode() = locale.hashCode()
}

/** String lookups against the TV app's `res/values*` files — see [AndroidResources]. */
class Resources(private val localeProvider: () -> Locale) {
    val configuration: Configuration get() = Configuration(localeProvider())

    fun getString(id: Int): String = AndroidResources.string(id, localeProvider())
    fun getString(id: Int, vararg args: Any?): String = AndroidResources.string(id, localeProvider(), *args)
    fun getQuantityString(id: Int, quantity: Int): String =
        AndroidResources.plural(id, quantity, localeProvider())
    fun getQuantityString(id: Int, quantity: Int, vararg args: Any?): String =
        AndroidResources.plural(id, quantity, localeProvider(), *args)
    fun getStringArray(id: Int): Array<String> = AndroidResources.stringArray(id, localeProvider())
}
