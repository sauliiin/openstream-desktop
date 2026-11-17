package android.os

/** Monotonic clocks, as Android defines them (a desktop never deep-sleeps mid-film). */
object SystemClock {
    private val origin = System.nanoTime()
    @JvmStatic fun elapsedRealtime(): Long = (System.nanoTime() - origin) / 1_000_000L + BOOT_OFFSET_MS
    @JvmStatic fun uptimeMillis(): Long = elapsedRealtime()
    @JvmStatic fun elapsedRealtimeNanos(): Long = System.nanoTime() - origin + BOOT_OFFSET_MS * 1_000_000L
    @JvmStatic fun sleep(ms: Long) = Thread.sleep(ms)

    /** Keeps values well away from zero, which several callers use as "never". */
    private const val BOOT_OFFSET_MS = 1_000_000L
}
