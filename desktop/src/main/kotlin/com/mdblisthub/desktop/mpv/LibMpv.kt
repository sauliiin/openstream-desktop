package com.mdblisthub.desktop.mpv

import com.mdblisthub.desktop.AppDirs
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.File

/** The slice of libmpv's C API (client.h + render.h) the player uses. */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_async(ctx: Pointer, reply: Long, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_async(ctx: Pointer, reply: Long, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, reply: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int

    fun mpv_render_context_create(res: PointerByReference, mpv: Pointer, params: Pointer): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, callback: UpdateCallback?, callbackCtx: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: Pointer): Int
    fun mpv_render_context_report_swap(ctx: Pointer)
    fun mpv_render_context_free(ctx: Pointer)

    fun interface UpdateCallback : Callback {
        fun invoke(ctx: Pointer?)
    }

    companion object {
        // mpv_format
        const val FORMAT_NONE = 0
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        // mpv_event_id
        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_LOG_MESSAGE = 2
        const val EVENT_COMMAND_REPLY = 5
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_VIDEO_RECONFIG = 17
        const val EVENT_SEEK = 20
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        // mpv_end_file_reason
        const val END_EOF = 0
        const val END_STOP = 2
        const val END_QUIT = 3
        const val END_ERROR = 4
        const val END_REDIRECT = 5

        // mpv_render_param_type
        const val RENDER_PARAM_INVALID = 0
        const val RENDER_PARAM_API_TYPE = 1
        const val RENDER_PARAM_SW_SIZE = 17
        const val RENDER_PARAM_SW_FORMAT = 18
        const val RENDER_PARAM_SW_STRIDE = 19
        const val RENDER_PARAM_SW_POINTER = 20
        const val RENDER_UPDATE_FRAME = 1L

        private var loadError: Throwable? = null

        /**
         * libmpv, looked up next to the app first (the AppImage and the Windows
         * installer bundle it), then wherever the system keeps it.
         */
        val instance: LibMpv? by lazy {
            fixNumericLocale()
            bundledDirs().forEach { NativeLibrary.addSearchPath(LIB_NAME, it.absolutePath) }
            NAMES.firstNotNullOfOrNull { name ->
                runCatching { Native.load(name, LibMpv::class.java) }
                    .onFailure { loadError = it }
                    .getOrNull()
            }
        }

        val unavailableReason: String? get() = if (instance == null) loadError?.message ?: "libmpv not found" else null

        private const val LIB_NAME = "mpv"
        private val NAMES = if (AppDirs.isWindows) listOf("libmpv-2", "mpv-2", "mpv") else listOf("mpv", "libmpv.so.2", "libmpv.so.1")

        private fun bundledDirs(): List<File> = buildList {
            System.getProperty("compose.application.resources.dir")?.let { add(File(it)) }
            System.getProperty("omnistream.lib.dir")?.let { add(File(it)) }
            System.getenv("APPDIR")?.let { add(File(it, "usr/lib")) }
            System.getProperty("java.home")?.let { add(File(it).parentFile ?: File(it)) }
        }.filter { it.isDirectory }

        /**
         * libmpv refuses to start unless LC_NUMERIC is "C" — a decimal comma
         * would break every option it parses. GTK/AWT may have set the user's
         * locale, so it is put back for the numeric category only.
         */
        private fun fixNumericLocale() {
            if (AppDirs.isWindows) return
            runCatching {
                val libc = NativeLibrary.getInstance("c")
                val setlocale = libc.getFunction("setlocale")
                setlocale.invoke(Pointer::class.java, arrayOf<Any>(LC_NUMERIC, "C"))
            }
        }

        private const val LC_NUMERIC = 1
    }
}
