package android.os

/** Reports a modern platform so every "is this API available" branch takes the newest path. */
object Build {
    object VERSION {
        const val SDK_INT: Int = 36
        const val RELEASE: String = "desktop"
    }
    object VERSION_CODES {
        const val M = 23; const val N = 24; const val O = 26; const val P = 28; const val Q = 29
        const val R = 30; const val S = 31; const val TIRAMISU = 33; const val UPSIDE_DOWN_CAKE = 34
        const val VANILLA_ICE_CREAM = 35
    }
    val MODEL: String = System.getProperty("os.name") ?: "desktop"
    val MANUFACTURER: String = "desktop"
    val DEVICE: String = "desktop"
}
