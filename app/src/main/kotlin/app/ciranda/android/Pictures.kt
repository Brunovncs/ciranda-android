package app.ciranda.android

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures by upload hash: asked of the engine, which puts each in a file, then decoded here at
 * most `MAX` on a side and kept while there is room.
 */
object Pictures {
    private const val MAX = 1280
    private const val BUDGET = 96L shl 20

    private val shown = mutableStateMapOf<String, ImageBitmap>()
    private val asked = mutableSetOf<String>()

    /** When each picture failed: drawn as failed (state, so it shows), asked again after a while. */
    private val failed = mutableStateMapOf<String, Long>()
    private const val RETRY_MS = 60_000L
    private val order = ArrayDeque<String>()
    private var bytes = 0L

    fun onEvent(e: Event.Picture) {
        val path = e.path
        if (path == null) {
            CirandaApp.scope.launch { fail(e.hash) }
            return
        }
        CirandaApp.scope.launch {
            val bmp = withContext(Dispatchers.IO) { decode(File(path)) }
            if (bmp == null) {
                fail(e.hash)
                return@launch
            }
            withContext(Dispatchers.Main) {
                synchronized(this@Pictures) {
                    asked.remove(e.hash)
                    shown[e.hash] = bmp
                    order.addLast(e.hash)
                    bytes += bmp.width.toLong() * bmp.height * 4
                    while (bytes > BUDGET && order.size > 1) {
                        val old = order.removeFirst()
                        shown.remove(old)?.let { bytes -= it.width.toLong() * it.height * 4 }
                    }
                }
            }
        }
    }

    private fun decode(f: File): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX || bounds.outHeight / (sample * 2) >= MAX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.path, opts)?.asImageBitmap()
    }

    private fun fail(hash: String) {
        synchronized(this) { asked.remove(hash) }
        failed[hash] = android.os.SystemClock.elapsedRealtime()
    }

    private fun ask(hash: String, kind: String) {
        synchronized(this) {
            val at = failed[hash]
            if (at != null && android.os.SystemClock.elapsedRealtime() - at < RETRY_MS) return
            if (shown.containsKey(hash) || hash in asked) return
            failed.remove(hash)
            asked.add(hash)
        }
        Engine.send("picture", "hash" to hash, "kind" to kind)
    }

    /** The picture once it is here; asks for it the first time. */
    @Composable
    fun get(hash: String?, kind: String = "picture"): ImageBitmap? {
        if (hash.isNullOrEmpty()) return null
        val got = shown[hash]
        if (got == null) LaunchedEffect(hash) { ask(hash, kind) }
        return got
    }

    fun failed(hash: String?): Boolean = hash != null && failed.containsKey(hash)
}
