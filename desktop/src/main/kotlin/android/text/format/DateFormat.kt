package android.text.format

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale

object DateFormat {
    /** 24-hour unless the locale's own short time pattern uses AM/PM. */
    @JvmStatic fun is24HourFormat(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        val pattern = (java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Locale.getDefault())
            as? SimpleDateFormat)?.toPattern().orEmpty()
        return !pattern.contains('a')
    }

    @JvmStatic fun getTimeFormat(context: Context): java.text.DateFormat =
        SimpleDateFormat(if (is24HourFormat(context)) "HH:mm" else "h:mm a", Locale.getDefault())
}
