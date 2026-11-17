package com.mdblisthub.desktop.mpv

import android.util.Log
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.util.ArrayDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Pulls video frames out of libmpv's software render API and hands them to
 * Compose as images.
 *
 * Each frame is rendered straight into the pixel memory of a fresh Skia bitmap
 * (no intermediate copy), marked immutable and published through a snapshot
 * state, so only the draw phase of the video composable is invalidated.
 * Frames are rendered at the size the video occupies on screen, capped at
 * [maxWidth]×[maxHeight]: the renderer is CPU-bound, and the GPU scales the
 * rest of the way for free.
 */
class MpvRenderer(
    private val player: MpvPlayer,
    private val maxWidth: Int = 1920,
    private val maxHeight: Int = 1080,
) : AutoCloseable {

    private val lib = LibMpv.instance!!
    private val closed = AtomicBoolean(false)
    private val wake = Semaphore(0)

    @Volatile private var targetW = 0
    @Volatile private var targetH = 0

    /** The newest frame, or null before the first one. Read it during draw. */
    val frame = mutableStateOf<ImageBitmap?>(null)

    /** Incremented per frame, for anything that wants to observe progress cheaply. */
    val frameCount = mutableLongStateOf(0L)

    /** Kept alive until two newer frames exist, so a draw in flight never sees a freed bitmap. */
    private val retired = ArrayDeque<Bitmap>()

    // Held in a field: JNA callbacks are only weakly referenced from native code.
    private val updateCallback = LibMpv.UpdateCallback { wake.release() }

    private val thread = Thread({ run() }, "mpv-render").apply {
        isDaemon = true
        priority = Thread.MAX_PRIORITY - 1
        start()
    }

    init {
        player.attachRenderer(this)
    }

    /** The on-screen size in pixels; frames are produced at this size (capped). */
    fun setTargetSize(width: Int, height: Int) {
        var w = width
        var h = height
        if (w > maxWidth || h > maxHeight) {
            val scale = minOf(maxWidth.toDouble() / w, maxHeight.toDouble() / h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        if (w != targetW || h != targetH) {
            targetW = w.coerceAtLeast(0)
            targetH = h.coerceAtLeast(0)
            wake.release()
        }
    }

    private fun run() {
        val apiType = Memory(3).apply { setString(0, "sw", "US-ASCII") }
        val createParams = renderParams(LibMpv.RENDER_PARAM_API_TYPE to apiType)
        val ctxRef = PointerByReference()
        val rc = lib.mpv_render_context_create(ctxRef, player.handle, createParams)
        if (rc < 0) {
            Log.e("mpv", "render context: ${lib.mpv_error_string(rc)}")
            return
        }
        val ctx = ctxRef.value
        lib.mpv_render_context_set_update_callback(ctx, updateCallback, null)

        // "bgra", not "bgr0": the 0 variant leaves the fourth byte as garbage
        // (usually zero), and Skia then blends the frame as fully transparent.
        val format = Memory(5).apply { setString(0, "bgra", "US-ASCII") }
        val size = Memory(8)
        val stride = Memory(Native.SIZE_T_SIZE.toLong())
        var lastW = 0
        var lastH = 0
        try {
            while (!closed.get()) {
                wake.tryAcquire(250, TimeUnit.MILLISECONDS)
                wake.drainPermits()
                if (closed.get()) break
                val flags = lib.mpv_render_context_update(ctx)
                val w = targetW
                val h = targetH
                val resized = w != lastW || h != lastH
                if (w <= 0 || h <= 0) continue
                if ((flags and LibMpv.RENDER_UPDATE_FRAME) == 0L && !resized) continue
                lastW = w
                lastH = h

                val renderStart = System.nanoTime()
                val rowBytes = ((w * 4 + 63) / 64) * 64
                val bitmap = Bitmap()
                val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE, org.jetbrains.skia.ColorSpace.sRGB)
                if (!bitmap.allocPixels(info, rowBytes)) {
                    bitmap.close()
                    continue
                }
                val addr = bitmap.peekPixels()?.addr ?: run { bitmap.close(); continue }

                size.setInt(0, w)
                size.setInt(4, h)
                if (Native.SIZE_T_SIZE == 8) stride.setLong(0, rowBytes.toLong()) else stride.setInt(0, rowBytes)
                val renderParams = renderParams(
                    LibMpv.RENDER_PARAM_SW_SIZE to size,
                    LibMpv.RENDER_PARAM_SW_FORMAT to format,
                    LibMpv.RENDER_PARAM_SW_STRIDE to stride,
                    LibMpv.RENDER_PARAM_SW_POINTER to Pointer(addr),
                )
                val result = lib.mpv_render_context_render(ctx, renderParams)
                if (result < 0) {
                    bitmap.close()
                    continue
                }
                bitmap.notifyPixelsChanged()
                bitmap.setImmutable()
                frame.value = bitmap.asComposeImageBitmap()
                frameCount.longValue = frameCount.longValue + 1
                if (frameCount.longValue % 120L == 1L) {
                    Log.d("mpv", "frame ${frameCount.longValue} ${w}x$h in ${(System.nanoTime() - renderStart) / 1_000_000}ms")
                }
                retired.addLast(bitmap)
                while (retired.size > KEEP_FRAMES) retired.removeFirst().close()
            }
        } catch (t: Throwable) {
            Log.e("mpv", "render loop crashed", t)
        } finally {
            lib.mpv_render_context_set_update_callback(ctx, null, null)
            lib.mpv_render_context_free(ctx)
            frame.value = null
            // The last frames may still be on screen for one more draw; let
            // the GC finalize them rather than freeing under the compositor.
            retired.clear()
        }
    }

    /** Builds a `mpv_render_param[]` terminated by `{0, NULL}`. */
    private fun renderParams(vararg params: Pair<Int, Pointer>): Memory {
        val entry = 16L // int type + padding + void* data
        val mem = Memory(entry * (params.size + 1))
        mem.clear()
        params.forEachIndexed { i, (type, data) ->
            mem.setInt(i * entry, type)
            mem.setPointer(i * entry + 8, data)
        }
        return mem
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        wake.release()
        if (Thread.currentThread() !== thread) runCatching { thread.join(3_000) }
    }

    private companion object {
        const val KEEP_FRAMES = 3
    }
}
