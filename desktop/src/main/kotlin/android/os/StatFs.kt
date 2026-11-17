package android.os

import java.io.File

class StatFs(path: String) {
    private val file = File(path)
    val availableBytes: Long get() = file.usableSpace
    val totalBytes: Long get() = file.totalSpace
    val freeBytes: Long get() = file.freeSpace
}
