package android.content

import java.io.File
import java.util.Properties

/** The subset of `SharedPreferences` the app uses, persisted as a properties file. */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun apply()
        fun commit(): Boolean
    }
}

internal class FileSharedPreferences(private val file: File) : SharedPreferences {
    private val props = Properties().apply {
        if (file.isFile) runCatching { file.reader(Charsets.UTF_8).use(::load) }
    }

    @Synchronized override fun getString(key: String, defValue: String?) = props.getProperty(key) ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: defValue
    @Synchronized override fun getInt(key: String, defValue: Int) = props.getProperty(key)?.toIntOrNull() ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long) = props.getProperty(key)?.toLongOrNull() ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float) = props.getProperty(key)?.toFloatOrNull() ?: defValue
    @Synchronized override fun contains(key: String) = props.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, String?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value.toString() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value.toString() }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value.toString() }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value.toString() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun apply() { commit() }
        override fun commit(): Boolean = synchronized(this@FileSharedPreferences) {
            if (clear) props.clear()
            changes.forEach { (k, v) -> if (v == null) props.remove(k) else props.setProperty(k, v) }
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writer(Charsets.UTF_8).use { props.store(it, null) }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.isSuccess
        }
    }
}
