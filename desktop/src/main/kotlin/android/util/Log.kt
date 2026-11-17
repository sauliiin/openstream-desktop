package android.util

/** Desktop stand-in for `android.util.Log`, writing to stderr. */
object Log {
    @Volatile var minLevel: Int = if (System.getenv("OMNISTREAM_DEBUG") != null) 2 else 4

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    private fun write(level: Int, tag: String?, msg: String?, tr: Throwable? = null): Int {
        if (level < minLevel) return 0
        val letter = "??VDIWE".getOrElse(level) { '?' }
        System.err.println("$letter/$tag: $msg")
        tr?.printStackTrace()
        return 0
    }

    @JvmStatic fun v(tag: String?, msg: String): Int = write(VERBOSE, tag, msg)
    @JvmStatic fun v(tag: String?, msg: String?, tr: Throwable?): Int = write(VERBOSE, tag, msg, tr)
    @JvmStatic fun d(tag: String?, msg: String): Int = write(DEBUG, tag, msg)
    @JvmStatic fun d(tag: String?, msg: String?, tr: Throwable?): Int = write(DEBUG, tag, msg, tr)
    @JvmStatic fun i(tag: String?, msg: String): Int = write(INFO, tag, msg)
    @JvmStatic fun i(tag: String?, msg: String?, tr: Throwable?): Int = write(INFO, tag, msg, tr)
    @JvmStatic fun w(tag: String?, msg: String): Int = write(WARN, tag, msg)
    @JvmStatic fun w(tag: String?, msg: String?, tr: Throwable?): Int = write(WARN, tag, msg, tr)
    @JvmStatic fun w(tag: String?, tr: Throwable?): Int = write(WARN, tag, tr?.toString(), tr)
    @JvmStatic fun e(tag: String?, msg: String): Int = write(ERROR, tag, msg)
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int = write(ERROR, tag, msg, tr)
    @JvmStatic fun isLoggable(tag: String?, level: Int): Boolean = level >= minLevel
}
